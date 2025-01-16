FROM alpine:latest AS base-release
RUN --mount=type=cache,target=/var/cache/apk apk update
RUN --mount=type=cache,target=/var/cache/apk apk add openjdk21-jre
RUN adduser -D luser
USER luser
WORKDIR /home/luser

FROM base-release AS release
ADD gbcs-cli-envelope-*.jar gbcs.jar
ENTRYPOINT ["java", "-jar", "/home/luser/gbcs.jar", "server"]

FROM base-release AS release-memcached
ADD --chown=luser:luser gbcs-cli-envelope-*.jar gbcs.jar
RUN mkdir plugins
WORKDIR /home/luser/plugins
RUN --mount=type=bind,source=.,target=/build/distributions tar -xf /build/distributions/gbcs-memcached*.tar
WORKDIR /home/luser
ENTRYPOINT ["java", "-jar", "/home/luser/gbcs.jar", "server"]

FROM release-memcached as compose
COPY --chown=luser:luser conf/gbcs-memcached.xml /home/luser/.config/gbcs/gbcs.xml