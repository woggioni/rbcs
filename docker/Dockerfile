FROM eclipse-temurin:21-jre-alpine AS base-release
RUN adduser -D luser
USER luser
WORKDIR /home/luser

FROM base-release AS release
ADD gbcs-cli-envelope-*.jar gbcs.jar
ENTRYPOINT ["java", "-XX:+UseZGC", "-XX:+ZGenerational", "-jar", "/home/luser/gbcs.jar", "server"]

FROM base-release AS release-memcache
ADD --chown=luser:luser gbcs-cli-envelope-*.jar gbcs.jar
RUN mkdir plugins
WORKDIR /home/luser/plugins
RUN --mount=type=bind,source=.,target=/build/distributions tar -xf /build/distributions/gbcs-server-memcache*.tar
WORKDIR /home/luser
ENTRYPOINT ["java", "-XX:+UseZGC", "-XX:+ZGenerational", "-jar", "/home/luser/gbcs.jar", "server"]
