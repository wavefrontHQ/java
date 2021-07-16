FROM photon:3.0

# This script may automatically configure wavefront without prompting, based on
# these variables:
#  WAVEFRONT_URL           (required)
#  WAVEFRONT_TOKEN         (required)
#  JAVA_HEAP_USAGE         (default is 4G)
#  WAVEFRONT_HOSTNAME      (default is the docker containers hostname)
#  WAVEFRONT_PROXY_ARGS    (default is none)
#  JAVA_ARGS               (default is none)

RUN tdnf install -y \
  openjdk11 \
  shadow \
  curl

# Add new group:user "wavefront"
RUN /usr/sbin/groupadd -g 2000 wavefront
RUN useradd --comment '' --uid 1000 --gid 2000 wavefront
RUN chown -R wavefront:wavefront /var
RUN chown -R wavefront:wavefront /usr/lib/jvm/OpenJDK-1.11.0/lib/security/cacerts
RUN chmod 755 /var

# tdnf doesn't support "download only" so we have to go with a
# full fledged package install, but it merely just unpacks the RPM.
RUN curl -sSf "https://packagecloud.io/install/repositories/wavefront/proxy/config_file.repo?os=el&dist=7&source=script" > /etc/yum.repos.d/wavefront_proxy.repo
RUN tdnf -q makecache -y --disablerepo='*' --enablerepo='wavefront_proxy'
RUN tdnf install -y wavefront-proxy
RUN cp /etc/wavefront/wavefront-proxy/log4j2-stdout.xml.default /etc/wavefront/wavefront-proxy/log4j2.xml

ENV PATH /usr/lib/jvm/OpenJDK-1.11.0/bin/:$PATH

# Run the agent
EXPOSE 3878
EXPOSE 2878
EXPOSE 4242

USER 1000:2000

ADD run.sh run.sh
CMD ["/bin/bash", "/run.sh"]
