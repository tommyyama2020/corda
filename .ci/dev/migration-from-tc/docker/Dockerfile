# Source: https://github.com/docker-library/openjdk/issues/158
FROM openjdk:8-jdk
RUN export DEBIAN_FRONTEND=noninteractive \
 && apt-get update \
 && LC_ALL=C apt-get install -y --no-install-recommends xvfb openjfx libopenjfx-java \
 && apt-get clean \
 && rm -rf /var/lib/apt/lists/* /tmp/* /var/tmp/* \
 && cd /usr/local/openjdk-8/jre/lib/ext \
 && ln -sf /usr/share/java/openjfx/jre/lib/ext/jfxrt.jar

# Make sure there is a user account created
ARG builder_user=builder
ARG builder_uid=1000
RUN useradd --uid ${builder_uid} -m ${builder_user}
