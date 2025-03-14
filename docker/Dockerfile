FROM eclipse-temurin:21-jre-alpine AS base-release
RUN adduser -D luser
USER luser
WORKDIR /home/luser

FROM base-release AS release-vanilla
ADD rbcs-cli-envelope-*.jar rbcs.jar
ENTRYPOINT ["java", "-XX:+UseSerialGC", "-XX:GCTimeRatio=24", "-jar", "/home/luser/rbcs.jar", "server"]

FROM base-release AS release-memcache
ADD --chown=luser:luser rbcs-cli-envelope-*.jar rbcs.jar
RUN mkdir plugins
WORKDIR /home/luser/plugins
RUN --mount=type=bind,source=.,target=/build/distributions tar -xf /build/distributions/rbcs-server-memcache*.tar
WORKDIR /home/luser
ADD logback.xml .
ENTRYPOINT ["java", "-Dlogback.configurationFile=logback.xml", "-XX:+UseSerialGC", "-XX:GCTimeRatio=24", "-jar", "/home/luser/rbcs.jar", "server"]

FROM scratch AS release-native
ADD rbcs-cli.upx /rbcs/rbcs-cli
ENV RBCS_CONFIGURATION_DIR="/rbcs"
WORKDIR /rbcs
ENTRYPOINT ["/rbcs/rbcs-cli"]
