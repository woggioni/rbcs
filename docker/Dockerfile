FROM eclipse-temurin:25-jre-alpine AS base-release
RUN adduser -D rbcs
USER rbcs
WORKDIR /var/lib/rbcs

FROM base-release AS release-vanilla
ADD rbcs-cli-envelope-*.jar rbcs.jar
ADD logback.xml /etc/rbcs/logback.xml
ENV RBCS_CONFIGURATION_DIR="/etc/rbcs"
ENTRYPOINT ["java", "-Dlogback.configurationFile=/etc/rbcs/logback.xml", "-XX:MaxRAMPercentage=70", "-XX:GCTimeRatio=24", "-XX:+UseZGC", "-jar", "/var/lib/rbcs/rbcs.jar"]

FROM base-release AS release-memcache
ADD --chown=rbcs:rbcs rbcs-cli-envelope-*.jar rbcs.jar
RUN mkdir plugins
WORKDIR /var/lib/rbcs/plugins
RUN --mount=type=bind,source=.,target=/build/distributions tar -xf /build/distributions/rbcs-server-memcache*.tar
WORKDIR /var/lib/rbcs
ADD logback.xml /etc/rbcs/logback.xml
ENV RBCS_CONFIGURATION_DIR="/etc/rbcs"
ENTRYPOINT ["java", "-Dlogback.configurationFile=/etc/rbcs/logback.xml", "-XX:MaxRAMPercentage=70", "-XX:GCTimeRatio=24", "-XX:+UseZGC", "-jar", "/var/lib/rbcs/rbcs.jar"]

FROM base-release AS release-redis
ADD --chown=rbcs:rbcs rbcs-cli-envelope-*.jar rbcs.jar
RUN mkdir plugins
WORKDIR /var/lib/rbcs/plugins
RUN --mount=type=bind,source=.,target=/build/distributions tar -xf /build/distributions/rbcs-server-redis*.tar
WORKDIR /var/lib/rbcs
ADD logback.xml /etc/rbcs/logback.xml
ENV RBCS_CONFIGURATION_DIR="/etc/rbcs"
ENTRYPOINT ["java", "-Dlogback.configurationFile=/etc/rbcs/logback.xml", "-XX:MaxRAMPercentage=70", "-XX:GCTimeRatio=24", "-XX:+UseZGC", "-jar", "/var/lib/rbcs/rbcs.jar"]

FROM base-release AS release-full
ADD --chown=rbcs:rbcs rbcs-cli-envelope-*.jar rbcs.jar
RUN mkdir plugins
WORKDIR /var/lib/rbcs/plugins
RUN --mount=type=bind,source=.,target=/build/distributions tar -xf /build/distributions/rbcs-server-memcache*.tar
RUN --mount=type=bind,source=.,target=/build/distributions tar -xf /build/distributions/rbcs-server-redis*.tar
RUN --mount=type=bind,source=.,target=/build/distributions tar -xf /build/distributions/rbcs-server-otel*.tar
WORKDIR /var/lib/rbcs
ADD logback.xml /etc/rbcs/logback.xml
ENV RBCS_CONFIGURATION_DIR="/etc/rbcs"
ENTRYPOINT ["java", "-Dlogback.configurationFile=/etc/rbcs/logback.xml", "-XX:MaxRAMPercentage=70", "-XX:GCTimeRatio=24", "-XX:+UseZGC", "-jar", "/var/lib/rbcs/rbcs.jar"]

FROM busybox:musl AS base-native
RUN mkdir -p /var/lib/rbcs /var/tmp/rbcs /etc/rbcs
RUN adduser -D -u 1000 rbcs -h /var/lib/rbcs
RUN chown rbcs:rbcs /var/tmp/rbcs

FROM scratch AS release-native
COPY --from=base-native /etc/passwd /etc/passwd
COPY --from=base-native /etc/rbcs /etc/rbcs
COPY --from=base-native /var/lib/rbcs /var/lib/rbcs
ADD rbcs-cli.upx /usr/bin/rbcs-cli
ENV RBCS_CONFIGURATION_DIR="/etc/rbcs"
USER rbcs
WORKDIR /var/lib/rbcs
ENTRYPOINT ["/usr/bin/rbcs-cli", "-XX:MaximumHeapSizePercent=70", "-Dio.netty.tmpdir=/var/tmp/rbcs", "-Dlogback.configurationFile=/etc/rbcs/logback.xml"]

FROM debian:12-slim AS release-jlink
RUN mkdir -p /usr/share/java/rbcs
RUN --mount=type=bind,source=.,target=/build/distributions tar -xf /build/distributions/rbcs-cli*.tar -C /usr/share/java/rbcs
RUN chmod 755 /usr/share/java/rbcs/bin/*
ADD --chmod=755 rbcs-cli.sh /usr/local/bin/rbcs-cli
RUN adduser -u 1000 rbcs
USER rbcs
WORKDIR /var/lib/rbcs
ADD logback.xml /etc/rbcs/logback.xml
ENV RBCS_CONFIGURATION_DIR="/etc/rbcs"
ENV JAVA_OPTS=-XX:-UseJVMCICompiler\ -Dlogback.configurationFile=/etc/rbcs/logback.xml\ -XX:MaxRAMPercentage=70\ -XX:GCTimeRatio=24\ -XX:+UseZGC
ENTRYPOINT ["/usr/local/bin/rbcs-cli"]
