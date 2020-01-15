package com.wavefront.agent;

import com.beust.jcommander.ParameterException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.exc.UnrecognizedPropertyException;
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory;
import com.google.common.annotations.VisibleForTesting;
import com.google.common.base.Splitter;
import com.google.common.collect.ImmutableSet;
import com.wavefront.agent.api.APIContainer;
import com.wavefront.agent.config.LogsIngestionConfig;
import com.wavefront.agent.data.EntityPropertiesFactory;
import com.wavefront.agent.data.EntityPropertiesFactoryImpl;
import com.wavefront.agent.logsharvesting.InteractiveLogsTester;
import com.wavefront.agent.preprocessor.PointLineBlacklistRegexFilter;
import com.wavefront.agent.preprocessor.PointLineWhitelistRegexFilter;
import com.wavefront.agent.preprocessor.PreprocessorConfigManager;
import com.wavefront.agent.preprocessor.PreprocessorRuleMetrics;
import com.wavefront.api.agent.AgentConfiguration;
import com.wavefront.api.agent.ValidationConfiguration;
import com.wavefront.common.TaggedMetricName;
import com.wavefront.metrics.ExpectedAgentMetric;
import com.yammer.metrics.Metrics;
import com.yammer.metrics.core.Counter;
import org.apache.commons.lang.StringUtils;
import org.apache.commons.lang3.ObjectUtils;

import java.io.File;
import java.io.FileNotFoundException;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.Timer;
import java.util.TimerTask;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.logging.Level;
import java.util.logging.Logger;
import java.util.stream.Collectors;
import java.util.stream.IntStream;

import static com.wavefront.agent.ProxyUtil.getOrCreateProxyId;
import static com.wavefront.common.Utils.getBuildVersion;

/**
 * Agent that runs remotely on a server collecting metrics.
 *
 * @author Clement Pang (clement@wavefront.com)
 */
public abstract class AbstractAgent {
  protected static final Logger logger = Logger.getLogger("proxy");
  final Counter activeListeners =
      Metrics.newCounter(ExpectedAgentMetric.ACTIVE_LISTENERS.metricName);

  /**
   * A set of commandline parameters to hide when echoing command line arguments
   */
  protected static final Set<String> PARAMETERS_TO_HIDE = ImmutableSet.of("-t", "--token",
      "--proxyPassword");

  protected final ProxyConfig proxyConfig = new ProxyConfig();
  protected APIContainer apiContainer;
  protected final List<ExecutorService> managedExecutors = new ArrayList<>();
  protected final List<Runnable> shutdownTasks = new ArrayList<>();
  protected final PreprocessorConfigManager preprocessors = new PreprocessorConfigManager();
  protected final ValidationConfiguration validationConfiguration = new ValidationConfiguration();
  protected final EntityPropertiesFactory entityProps =
      new EntityPropertiesFactoryImpl(proxyConfig);
  protected final AtomicBoolean shuttingDown = new AtomicBoolean(false);
  protected ProxyCheckInScheduler proxyCheckinScheduler;
  protected UUID agentId;

  @Deprecated
  public AbstractAgent(boolean localAgent, boolean pushAgent) {
    this();
  }

  public AbstractAgent() {
  }

  private void addPreprocessorFilters(String ports, String whitelist,
                                      String blacklist) {
    if (ports != null && (whitelist != null || blacklist != null)) {
      for (String strPort : Splitter.on(",").omitEmptyStrings().trimResults().split(ports)) {
        PreprocessorRuleMetrics ruleMetrics = new PreprocessorRuleMetrics(
            Metrics.newCounter(new TaggedMetricName("validationRegex", "points-rejected",
                "port", strPort)),
            Metrics.newCounter(new TaggedMetricName("validationRegex", "cpu-nanos",
                "port", strPort)),
            Metrics.newCounter(new TaggedMetricName("validationRegex", "points-checked",
                "port", strPort))
        );
        if (blacklist != null) {
          preprocessors.getSystemPreprocessor(strPort).forPointLine().addFilter(
              new PointLineBlacklistRegexFilter(blacklist, ruleMetrics));
        }
        if (whitelist != null) {
          preprocessors.getSystemPreprocessor(strPort).forPointLine().addFilter(
              new PointLineWhitelistRegexFilter(whitelist, ruleMetrics));
        }
      }
    }
  }

  private void initPreprocessors() {
    String configFileName = proxyConfig.getPreprocessorConfigFile();
    if (configFileName != null) {
      try {
        preprocessors.loadFile(configFileName);
        preprocessors.setUpConfigFileMonitoring(configFileName, 5000); // check every 5s
      } catch (FileNotFoundException ex) {
        throw new RuntimeException("Unable to load preprocessor rules - file does not exist: " +
            configFileName);
      }
      logger.info("Preprocessor configuration loaded from " + configFileName);
    }

    // convert blacklist/whitelist fields to filters for full backwards compatibility.
    // blacklistRegex and whitelistRegex are applied to pushListenerPorts,
    // graphitePorts and picklePorts
    String allPorts = StringUtils.join(new String[]{
        ObjectUtils.firstNonNull(proxyConfig.getPushListenerPorts(), ""),
        ObjectUtils.firstNonNull(proxyConfig.getGraphitePorts(), ""),
        ObjectUtils.firstNonNull(proxyConfig.getPicklePorts(), ""),
        ObjectUtils.firstNonNull(proxyConfig.getTraceListenerPorts(), "")
    }, ",");
    addPreprocessorFilters(allPorts, proxyConfig.getWhitelistRegex(),
        proxyConfig.getBlacklistRegex());
    // opentsdbBlacklistRegex and opentsdbWhitelistRegex are applied to opentsdbPorts only
    addPreprocessorFilters(proxyConfig.getOpentsdbPorts(), proxyConfig.getOpentsdbWhitelistRegex(),
        proxyConfig.getOpentsdbBlacklistRegex());
  }

  // Returns null on any exception, and logs the exception.
  protected LogsIngestionConfig loadLogsIngestionConfig() {
    try {
      if (proxyConfig.getLogsIngestionConfigFile() == null) {
        return null;
      }
      ObjectMapper objectMapper = new ObjectMapper(new YAMLFactory());
      return objectMapper.readValue(new File(proxyConfig.getLogsIngestionConfigFile()),
          LogsIngestionConfig.class);
    } catch (UnrecognizedPropertyException e) {
      logger.severe("Unable to load logs ingestion config: " + e.getMessage());
    } catch (Exception e) {
      logger.log(Level.SEVERE, "Could not load logs ingestion config", e);
    }
    return null;
  }

  private void postProcessConfig() {
    System.setProperty("org.apache.commons.logging.Log",
        "org.apache.commons.logging.impl.SimpleLog");
    System.setProperty("org.apache.commons.logging.simplelog.log.org.apache.http", "warn");

    if (StringUtils.isBlank(proxyConfig.getHostname().trim())) {
      logger.severe("hostname cannot be blank! Please correct your configuration settings.");
      System.exit(1);
    }
  }

  @VisibleForTesting
  void parseArguments(String[] args) {
    // read build information and print version.
    String versionStr = "Wavefront Proxy version " + getBuildVersion();
    try {
      proxyConfig.parseArguments(args, this.getClass().getCanonicalName());
    } catch (ParameterException e) {
      logger.info(versionStr);
      logger.severe("Parameter exception: " + e.getMessage());
      System.exit(1);
    }
    logger.info(versionStr);
    logger.info("Arguments: " + IntStream.range(0, args.length).
        mapToObj(i -> (i > 0 && PARAMETERS_TO_HIDE.contains(args[i - 1])) ? "<HIDDEN>" : args[i]).
        collect(Collectors.joining(" ")));
    proxyConfig.verifyAndInit();
  }

  /**
   * Entry-point for the application.
   *
   * @param args Command-line parameters passed on to JCommander to configure the daemon.
   */
  public void start(String[] args) {
    try {

      /* ------------------------------------------------------------------------------------
       * Configuration Setup.
       * ------------------------------------------------------------------------------------ */

      // Parse commandline arguments and load configuration file
      parseArguments(args);
      postProcessConfig();
      initPreprocessors();

      // Conditionally enter an interactive debugging session for logsIngestionConfig.yaml
      if (proxyConfig.isTestLogs()) {
        InteractiveLogsTester interactiveLogsTester = new InteractiveLogsTester(
            this::loadLogsIngestionConfig, proxyConfig.getPrefix());
        logger.info("Reading line-by-line sample log messages from STDIN");
        //noinspection StatementWithEmptyBody
        while (interactiveLogsTester.interactiveTest()) {
          // empty
        }
        System.exit(0);
      }

      // 2. Read or create the unique Id for the daemon running on this machine.
      try {
        agentId = getOrCreateProxyId(proxyConfig);
      } catch (RuntimeException e) {
        logger.severe(e.getMessage());
        System.exit(1);
      }
      apiContainer = new APIContainer(proxyConfig);

      // Perform initial proxy check-in and schedule regular check-ins (once a minute)
      proxyCheckinScheduler = new ProxyCheckInScheduler(agentId, proxyConfig, apiContainer,
          this::processConfiguration);
      proxyCheckinScheduler.scheduleCheckins();

      // Start the listening endpoints
      startListeners();

      Timer startupTimer = new Timer("Timer-startup");
      shutdownTasks.add(startupTimer::cancel);
      startupTimer.schedule(
          new TimerTask() {
            @Override
            public void run() {
              // exit if no active listeners
              if (activeListeners.count() == 0) {
                logger.severe("**** All listener threads failed to start - there is already a " +
                    "running instance listening on configured ports, or no listening ports " +
                    "configured!");
                logger.severe("Aborting start-up");
                System.exit(1);
              }

              Runtime.getRuntime().addShutdownHook(new Thread("proxy-shutdown-hook") {
                @Override
                public void run() {
                  shutdown();
                }
              });

              logger.info("setup complete");
            }
          },
          5000
      );
    } catch (Throwable t) {
      logger.log(Level.SEVERE, "Aborting start-up", t);
      System.exit(1);
    }
  }

  /**
   * Actual agents can do additional configuration.
   *
   * @param config The configuration to process.
   */
  protected void processConfiguration(AgentConfiguration config) {
    try {
      apiContainer.getProxyV2API().proxyConfigProcessed(agentId);
    } catch (RuntimeException e) {
      // cannot throw or else configuration update thread would die.
    }
  }

  /**
   * Best-effort graceful shutdown.
   */
  public void shutdown() {
    if (!shuttingDown.compareAndSet(false, true)) return;
    try {
      try {
        logger.info("Shutting down the proxy...");
      } catch (Throwable t) {
        // ignore logging errors
      }

      System.out.println("Shutting down: Stopping listeners...");
      stopListeners();

      System.out.println("Shutting down: Stopping schedulers...");
      if (proxyCheckinScheduler != null) proxyCheckinScheduler.shutdown();
      managedExecutors.forEach(ExecutorService::shutdownNow);
        // wait for up to request timeout
      managedExecutors.forEach(x -> {
        try {
          x.awaitTermination(proxyConfig.getHttpRequestTimeout(), TimeUnit.MILLISECONDS);
        } catch (InterruptedException e) {
          // ignore
        }
      });

      System.out.println("Shutting down: Running finalizing tasks...");
      shutdownTasks.forEach(Runnable::run);

      System.out.println("Shutdown complete.");
    } catch (Throwable t) {
      try {
        logger.log(Level.SEVERE, "Error during shutdown: ", t);
      } catch (Throwable loggingError) {
        t.addSuppressed(loggingError);
        t.printStackTrace();
      }
    }
  }

  /**
   * Starts all listeners as configured.
   */
  protected abstract void startListeners();

  /**
   * Stops all listeners before terminating the process.
   */
  protected abstract void stopListeners();

  /**
   * Shut down specific listener pipeline.
   *
   * @param port port number.
   */
  protected abstract void stopListener(int port);
}
