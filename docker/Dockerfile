FROM eclipse-temurin:21-jre-alpine AS base-release
RUN adduser -D luser
USER luser
WORKDIR /home/luser

FROM base-release AS release-vanilla
ADD rbcs-cli-envelope-*.jar rbcs.jar
ENTRYPOINT ["java", "-Dlogback.configurationFile=logback.xml", "-XX:MaxRAMPercentage=70", "-XX:GCTimeRatio=24", "-XX:+UseZGC", "-XX:+ZGenerational", "-jar", "/home/luser/rbcs.jar"]

FROM base-release AS release-memcache
ADD --chown=luser:luser rbcs-cli-envelope-*.jar rbcs.jar
RUN mkdir plugins
WORKDIR /home/luser/plugins
RUN --mount=type=bind,source=.,target=/build/distributions tar -xf /build/distributions/rbcs-server-memcache*.tar
WORKDIR /home/luser
ADD logback.xml .
ENTRYPOINT ["java", "-Dlogback.configurationFile=logback.xml", "-XX:MaxRAMPercentage=70", "-XX:GCTimeRatio=24", "-XX:+UseZGC", "-XX:+ZGenerational", "-jar", "/home/luser/rbcs.jar"]

FROM scratch AS release-native
ADD rbcs-cli.upx /rbcs/rbcs-cli
ENV RBCS_CONFIGURATION_DIR="/rbcs"
WORKDIR /rbcs
ENTRYPOINT ["/rbcs/rbcs-cli", "-XX:MaximumHeapSizePercent=70"]

FROM debian:12-slim AS release-jlink
RUN mkdir -p /usr/share/java/rbcs
RUN --mount=type=bind,source=.,target=/build/distributions tar -xf /build/distributions/rbcs-cli*.tar -C /usr/share/java/rbcs
ADD --chmod=755 rbcs-cli.sh /usr/local/bin/rbcs-cli
RUN adduser -u 1000 luser
USER luser
WORKDIR /home/luser
ADD logback.xml .
ENV JAVA_OPTS=-XX:-UseJVMCICompiler\ -Dlogback.configurationFile=logback.xml\ -XX:MaxRAMPercentage=70\ -XX:GCTimeRatio=24\ -XX:+UseZGC\ -XX:+ZGenerational
ENTRYPOINT ["/usr/local/bin/rbcs-cli"]
