ARG VERSION, REVISION
FROM eclipse-temurin:25-jre-alpine AS base-release
LABEL org.opencontainers.image.authors="Walter Oggioni <walter.oggioni@agentmail.to>"
LABEL org.opencontainers.image.version="${VERSION}"
LABEL org.opencontainers.image.revision="${REVISION}"
LABEL org.opencontainers.image.source=https://gitea.woggioni.net/woggioni/rbcs

RUN adduser -D rbcs
USER rbcs
ENV RBCS_CONFIGURATION_DIR="/etc/rbcs"
WORKDIR /var/lib/rbcs

FROM base-release AS release-vanilla
LABEL org.opencontainers.image.title=rbcs
LABEL org.opencontainers.image.description=RBCS vanilla image
ADD rbcs-cli-envelope-*.jar rbcs.jar
ADD logback.xml /etc/rbcs/logback.xml
ENTRYPOINT ["java", "-jar", "/var/lib/rbcs/rbcs.jar"]

FROM base-release AS release-memcache
LABEL org.opencontainers.image.title=rbcs-memcache
LABEL org.opencontainers.image.description=RBCS image with memcache plugin
ADD --chown=rbcs:rbcs rbcs-cli-envelope-*.jar rbcs.jar
RUN mkdir plugins
WORKDIR /var/lib/rbcs/plugins
RUN --mount=type=bind,source=.,target=/build/distributions tar -xf /build/distributions/rbcs-server-memcache*.tar
WORKDIR /var/lib/rbcs
ADD logback.xml /etc/rbcs/logback.xml
ENTRYPOINT ["java", "-jar", "/var/lib/rbcs/rbcs.jar"]

FROM base-release AS release-redis
LABEL org.opencontainers.image.title=rbcs-redis
LABEL org.opencontainers.image.description=RBCS image with redis plugin
ADD --chown=rbcs:rbcs rbcs-cli-envelope-*.jar rbcs.jar
RUN mkdir plugins
WORKDIR /var/lib/rbcs/plugins
RUN --mount=type=bind,source=.,target=/build/distributions tar -xf /build/distributions/rbcs-server-redis*.tar
WORKDIR /var/lib/rbcs
ADD logback.xml /etc/rbcs/logback.xml
ENTRYPOINT ["java", "-jar", "/var/lib/rbcs/rbcs.jar"]

FROM base-release AS release-full
LABEL org.opencontainers.image.title=rbcs-full
LABEL org.opencontainers.image.description=RBCS image with all plugins
ADD --chown=rbcs:rbcs rbcs-cli-envelope-*.jar rbcs.jar
RUN mkdir plugins
WORKDIR /var/lib/rbcs/plugins
RUN --mount=type=bind,source=.,target=/build/distributions tar -xf /build/distributions/rbcs-server-memcache*.tar
RUN --mount=type=bind,source=.,target=/build/distributions tar -xf /build/distributions/rbcs-server-redis*.tar
RUN --mount=type=bind,source=.,target=/build/distributions tar -xf /build/distributions/rbcs-server-otel*.tar
WORKDIR /var/lib/rbcs
ADD logback.xml /etc/rbcs/logback.xml
ENV OTEL_SDK_DISABLED="true"
ENTRYPOINT ["java", "-jar", "/var/lib/rbcs/rbcs.jar"]

FROM busybox:musl AS base-native
RUN mkdir -p /var/lib/rbcs /var/tmp/rbcs /etc/rbcs
RUN adduser -D -u 1000 rbcs -h /var/lib/rbcs
RUN chown rbcs:rbcs /var/tmp/rbcs

FROM scratch AS release-native
LABEL org.opencontainers.image.title=rbcs-native
LABEL org.opencontainers.image.description=RBCS image with a native executable with GraalVM
COPY --from=base-native /etc/passwd /etc/passwd
COPY --from=base-native /etc/rbcs /etc/rbcs
COPY --from=base-native /var/lib/rbcs /var/lib/rbcs
COPY --from=base-native /var/tmp/rbcs /var/tmp/rbcs
ADD rbcs-cli.upx /usr/bin/rbcs-cli
ADD logback.xml /etc/rbcs/logback.xml
USER rbcs
WORKDIR /var/lib/rbcs
ENV RBCS_CONFIGURATION_DIR="/etc/rbcs"
ENTRYPOINT ["/usr/bin/rbcs-cli", "-XX:MaximumHeapSizePercent=70", "-Dio.netty.tmpdir=/var/tmp/rbcs", "-Dlogback.configurationFile=/etc/rbcs/logback.xml"]

FROM debian:12-slim AS release-jlink
LABEL org.opencontainers.image.title=rbcs-jlink
LABEL org.opencontainers.image.description=RBCS image with a jlink distribution
RUN mkdir -p /usr/share/java/rbcs
RUN --mount=type=bind,source=.,target=/build/distributions tar -xf /build/distributions/rbcs-cli*.tar -C /usr/share/java/rbcs
RUN chmod 755 /usr/share/java/rbcs/bin/*
ADD --chmod=755 rbcs-cli.sh /usr/local/bin/rbcs-cli
RUN adduser -u 1000 rbcs
USER rbcs
WORKDIR /var/lib/rbcs
ADD logback.xml /etc/rbcs/logback.xml
ENV RBCS_CONFIGURATION_DIR="/etc/rbcs"
ENTRYPOINT ["/usr/local/bin/rbcs-cli"]
