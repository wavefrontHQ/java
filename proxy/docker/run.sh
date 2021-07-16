#!/bin/bash

if [[ -z "$WAVEFRONT_URL" ]]; then
  echo "WAVEFRONT_URL environment variable not configured - aborting startup " >&2
  exit 0
fi

if [[ -z "$WAVEFRONT_TOKEN" ]]; then
  echo "WAVEFRONT_TOKEN environment variable not configured - aborting startup " >&2
  exit 0
fi

spool_dir="/var/spool/wavefront-proxy"
mkdir -p $spool_dir

chown -R wavefront:wavefront $spool_dir

# Be receptive to core dumps
ulimit -c unlimited

# Allow high connection count per process (raise file descriptor limit)
ulimit -Sn 65536
ulimit -Hn 65536

java_heap_usage=${JAVA_HEAP_USAGE:-4G}
jvm_initial_ram_percentage=${JVM_INITIAL_RAM_PERCENTAGE:-50.0}
jvm_max_ram_percentage=${JVM_MAX_RAM_PERCENTAGE:-85.0}

# Use cgroup opts - Note that -XX:UseContainerSupport=true since Java 8u191.
# https://bugs.openjdk.java.net/browse/JDK-8146115
jvm_container_opts="-XX:InitialRAMPercentage=$jvm_initial_ram_percentage  -XX:MaxRAMPercentage=$jvm_max_ram_percentage"
if [ "${JVM_USE_CONTAINER_OPTS}" = false ] ; then
    jvm_container_opts="-Xmx$java_heap_usage -Xms$java_heap_usage"
fi

###################
# import CA certs #
###################
files=$(ls /tmp/ca/*.pem)
if [ ${#files[@]} -gt 0 ]; then
  echo
  echo "Adding credentials to JVM store.."
  echo
  for filename in ${files}; do
    alias=$(basename ${filename})
    alias=${alias%.*}
    echo "----------- Adding credential file:${filename} alias:${alias}"
    keytool -noprompt -cacerts -importcert -storepass changeit -file ${filename} -alias ${alias}
    keytool -storepass changeit -list -v -cacerts -alias ${alias}
    echo "----------- Done"
    echo
  done
fi

#############
# run proxy #
#############
java \
    $jvm_container_opts $JAVA_ARGS \
	-Djava.util.logging.manager=org.apache.logging.log4j.jul.LogManager \
	-Dlog4j.configurationFile=/etc/wavefront/wavefront-proxy/log4j2.xml \
	-jar /opt/wavefront/wavefront-proxy/bin/wavefront-proxy.jar \
	-h $WAVEFRONT_URL \
	-t $WAVEFRONT_TOKEN \
	--hostname ${WAVEFRONT_HOSTNAME:-$(hostname)} \
	--ephemeral true \
	--buffer ${spool_dir}/buffer \
	--flushThreads 6 \
	$WAVEFRONT_PROXY_ARGS
