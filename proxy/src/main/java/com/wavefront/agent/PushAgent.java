package com.wavefront.agent;

import com.beust.jcommander.internal.Lists;
import com.google.common.base.Function;
import com.google.common.base.Preconditions;
import com.wavefront.agent.formatter.GraphiteFormatter;
import com.wavefront.api.agent.AgentConfiguration;
import com.wavefront.ingester.GraphiteDecoder;
import com.wavefront.ingester.GraphiteHostAnnotator;
import com.wavefront.ingester.Ingester;
import com.wavefront.ingester.StreamIngester;
import com.wavefront.ingester.OpenTSDBDecoder;
import com.wavefront.ingester.PickleProtocolDecoder;
import io.netty.channel.ChannelHandler;
import io.netty.channel.socket.SocketChannel;
import io.netty.handler.codec.LengthFieldBasedFrameDecoder;
import io.netty.channel.ChannelInboundHandler;
import org.glassfish.jersey.jackson.JacksonFeature;
import org.glassfish.jersey.jetty.JettyHttpContainerFactory;
import org.glassfish.jersey.server.ResourceConfig;

import javax.annotation.Nullable;
import java.io.IOException;
import java.nio.ByteOrder;
import java.net.URI;
import java.net.URISyntaxException;
import java.util.List;

/**
 * Push-only Agent.
 *
 * @author Clement Pang (clement@wavefront.com)
 */
public class PushAgent extends AbstractAgent {

  public static void main(String[] args) throws IOException {
    // Start the ssh daemon
    new PushAgent().start(args);
  }

  public PushAgent() {
    super(false, true);
  }

  protected PushAgent(boolean reportAsPushAgent) {
    super(false, reportAsPushAgent);
  }

  @Override
  protected void startListeners() {
    for (String strPort : pushListenerPorts.split(",")) {
      startGraphiteListener(strPort, null);
    }
    GraphiteFormatter graphiteFormatter = null;
    if (graphitePorts != null || picklePorts != null) {
      Preconditions.checkNotNull(graphiteFormat, "graphiteFormat must be supplied to enable graphite support");
      Preconditions.checkNotNull(graphiteDelimiters, "graphiteDelimiters must be supplied to enable graphite support");
      graphiteFormatter = new GraphiteFormatter(graphiteFormat, graphiteDelimiters, graphiteFieldsToRemove);
      for (String strPort : graphitePorts.split(",")) {
        if (strPort.trim().length() > 0) {
          startGraphiteListener(strPort, graphiteFormatter);
          logger.info("listening on port: " + strPort + " for graphite metrics");
        }
      }
    }
    if (opentsdbPorts != null) {
      for (String strPort : opentsdbPorts.split(",")) {
        if (strPort.trim().length() > 0) {
          startOpenTsdbListener(strPort);
          logger.info("listening on port: " + strPort + " for OpenTSDB metrics");
        }
      }
    }
    if (picklePorts != null) {
      for (String strPort : picklePorts.split(",")) {
        if (strPort.trim().length() > 0) {
          startPickleListener(strPort, graphiteFormatter);
          logger.info("listening on port: " + strPort + " for pickle protocol metrics");
        }
      }
    }
    if (httpJsonPorts != null) {
      for (String strPort : httpJsonPorts.split(",")) {
        if (strPort.trim().length() > 0) {
          try {
            int port = Integer.parseInt(strPort);
            // will immediately start the server.
            JettyHttpContainerFactory.createServer(
                new URI("http://localhost:" + strPort + "/"),
                new ResourceConfig(JacksonFeature.class).
                    register(new JsonMetricsEndpoint(agentAPI, agentId, port, hostname, prefix,
                        pushLogLevel, pushValidationLevel, pushFlushInterval, pushBlockedSamples
                    )),
                true);
            logger.info("listening on port: " + strPort + " for HTTP JSON metrics");
          } catch (URISyntaxException e) {
            throw new RuntimeException("Unable to bind to: " + strPort + " for HTTP JSON metrics", e);
          }
        }
      }
    }
    if (writeHttpJsonPorts != null) {
      for (String strPort : writeHttpJsonPorts.split(",")) {
        if (strPort.trim().length() > 0) {
          try {
            int port = Integer.parseInt(strPort);
            // will immediately start the server.
            JettyHttpContainerFactory.createServer(
                new URI("http://localhost:" + strPort + "/"),
                new ResourceConfig(JacksonFeature.class).
                    register(new WriteHttpJsonMetricsEndpoint(agentAPI, agentId, port, hostname, prefix,
                        pushLogLevel, pushValidationLevel, pushFlushInterval, pushBlockedSamples
                    )),
                true);
            logger.info("listening on port: " + strPort + " for Write HTTP JSON metrics");
          } catch (URISyntaxException e) {
            throw new RuntimeException("Unable to bind to: " + strPort + " for Write HTTP JSON metrics", e);
          }
        }
      }
    }
  }

  protected void startOpenTsdbListener(String strPort) {
    int port = Integer.parseInt(strPort);

    // Set up a custom graphite handler, with no formatter
    ChannelHandler graphiteHandler = new ChannelStringHandler(new OpenTSDBDecoder("unknown", customSourceTags),
        agentAPI, agentId, port, prefix, pushLogLevel, pushValidationLevel, pushFlushInterval,
        pushBlockedSamples, null, opentsdbWhitelistRegex,
        opentsdbBlacklistRegex);
    new Thread(new Ingester(graphiteHandler, port)).start();
  }

  protected void startPickleListener(String strPort, GraphiteFormatter formatter) {
    int port = Integer.parseInt(strPort);
    
    // Set up a custom handler
    ChannelHandler handler = new ChannelByteArrayHandler(new PickleProtocolDecoder("unknown", customSourceTags, formatter.getMetricMangler()), agentAPI, agentId, port, prefix, pushLogLevel, pushValidationLevel, pushFlushInterval, pushBlockedSamples, whitelistRegex, blacklistRegex);
    // create a class to use for StreamIngester to get a new FrameDecoder
    // for each request (not shareable since it's storing how many bytes
    // read, etc)
    // the pickle listener for carbon-relay streams data in its own format:
    //   [Length of pickled data to follow in a 4 byte unsigned int]
    //   [pickled data of the given length]
    //   <repeat ...>
    // the LengthFieldBasedFrameDecoder() parses out the length and grabs
    // <length> bytes from the stream and passes that chunk as a byte array
    // to the decoder.
    class FrameDecoderFactoryImpl implements StreamIngester.FrameDecoderFactory {
      @Override
      public ChannelInboundHandler getDecoder() {
        return new LengthFieldBasedFrameDecoder(ByteOrder.BIG_ENDIAN, 1000000, 0, 4, 0, 4, false);
      }
    }

    new Thread(new StreamIngester(new FrameDecoderFactoryImpl(), handler, port)).start();
  }

  protected void startGraphiteListener(String strPort,
                                       @Nullable Function<String, String> formatter) {
    int port = Integer.parseInt(strPort);

    // Set up a custom graphite handler, with no formatter
    ChannelHandler graphiteHandler = new ChannelStringHandler(new GraphiteDecoder("unknown", customSourceTags),
        agentAPI, agentId, port, prefix, pushLogLevel, pushValidationLevel, pushFlushInterval,
        pushBlockedSamples, formatter, whitelistRegex, blacklistRegex);

    if (formatter == null) {
      List<Function<SocketChannel, ChannelHandler>> handler = Lists.newArrayList(1);
      handler.add(new Function<SocketChannel, ChannelHandler>() {
        @Override
        public ChannelHandler apply(SocketChannel input) {
          return new GraphiteHostAnnotator(input.remoteAddress().getHostName(), customSourceTags);
        }
      });
      new Thread(new Ingester(handler, graphiteHandler, port)).start();
    } else {
      new Thread(new Ingester(graphiteHandler, port)).start();
    }
  }

  /**
   * Push agent configuration during check-in by the collector.
   *
   * @param config The configuration to process.
   */
  @Override
  protected void processConfiguration(AgentConfiguration config) {
    try {
      agentAPI.agentConfigProcessed(agentId);
      Long pointsPerBatch = config.getPointsPerBatch();
      if (config.getCollectorSetsPointsPerBatch() != null &&
          config.getCollectorSetsPointsPerBatch()) {
        if (pointsPerBatch != null) {
          // if the collector is in charge and it provided a setting, use it
          QueuedAgentService.setSplitBatchSize(pointsPerBatch.intValue());
          PostPushDataTimedTask.setPointsPerBatch(pointsPerBatch.intValue());
          if (pushLogLevel.equals("DETAILED")) {
            logger.info("Agent push batch set to (remotely) " + pointsPerBatch);
          }
        } // otherwise don't change the setting
      } else {
        // restores the agent setting
        QueuedAgentService.setSplitBatchSize(pushFlushMaxPoints);
        PostPushDataTimedTask.setPointsPerBatch(pushFlushMaxPoints);
        if (pushLogLevel.equals("DETAILED")) {
          logger.info("Agent push batch set to (locally) " + pushFlushMaxPoints);
        }
      }

      if (config.getCollectorSetsRetryBackoff() != null &&
          config.getCollectorSetsRetryBackoff()) {
        if (config.getRetryBackoffBaseSeconds() != null) {
          // if the collector is in charge and it provided a setting, use it
          QueuedAgentService.setRetryBackoffBaseSeconds(config.getRetryBackoffBaseSeconds());
          if (pushLogLevel.equals("DETAILED")) {
            logger.info("Agent backoff base set to (remotely) " +
                config.getRetryBackoffBaseSeconds());
          }
        } // otherwise don't change the setting
      } else {
        // restores the agent setting
        QueuedAgentService.setRetryBackoffBaseSeconds(retryBackoffBaseSeconds);
        if (pushLogLevel.equals("DETAILED")) {
          logger.info("Agent backoff base set to (locally) " + retryBackoffBaseSeconds);
        }
      }
    } catch (RuntimeException e) {
      // cannot throw or else configuration update thread would die.
    }
  }
}
