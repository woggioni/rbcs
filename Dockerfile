FROM container-registry.oracle.com/graalvm/native-image:21 AS oracle

FROM ubuntu:24.04 AS build
COPY --from=oracle /usr/lib64/graalvm/ /usr/lib64/graalvm/
ENV JAVA_HOME=/usr/lib64/graalvm/graalvm-java21
USER ubuntu
WORKDIR /home/ubuntu

RUN mkdir gbcs
WORKDIR /home/ubuntu/gbcs

COPY --chown=ubuntu:users .git .git
COPY --chown=ubuntu:users gbcs-base gbcs-base
COPY --chown=ubuntu:users gbcs-api gbcs-api
COPY --chown=ubuntu:users gbcs-memcached gbcs-memcached
COPY --chown=ubuntu:users gbcs-cli gbcs-cli
COPY --chown=ubuntu:users src src
COPY --chown=ubuntu:users settings.gradle settings.gradle
COPY --chown=ubuntu:users build.gradle build.gradle
COPY --chown=ubuntu:users gradle.properties gradle.properties
COPY --chown=ubuntu:users gradle gradle
COPY --chown=ubuntu:users gradlew gradlew

RUN --mount=type=cache,target=/home/ubuntu/.gradle,uid=1000,gid=1000 ./gradlew --no-daemon assemble

FROM alpine:latest AS base-release
RUN --mount=type=cache,target=/var/cache/apk apk update
RUN --mount=type=cache,target=/var/cache/apk apk add openjdk21-jre
RUN adduser -D luser
USER luser
WORKDIR /home/luser

FROM base-release AS release
RUN --mount=type=bind,from=build,source=/home/ubuntu/gbcs/gbcs-cli/build,target=/home/luser/build cp build/libs/gbcs-cli-envelope-*.jar gbcs.jar
ENTRYPOINT ["java", "-jar", "/home/luser/gbcs.jar"]

FROM base-release AS release-memcached
RUN --mount=type=bind,from=build,source=/home/ubuntu/gbcs/gbcs-cli/build,target=/home/luser/build cp build/libs/gbcs-cli-envelope-*.jar gbcs.jar
RUN mkdir plugins
WORKDIR /home/luser/plugins
RUN --mount=type=bind,from=build,source=/home/ubuntu/gbcs/gbcs-memcached/build/distributions,target=/build/distributions tar -xf /build/distributions/gbcs-memcached*.tar
WORKDIR /home/luser
ENTRYPOINT ["java", "-jar", "/home/luser/gbcs.jar"]

FROM release-memcached as compose
COPY --chown=luser:luser conf/gbcs-memcached.xml /home/luser/.config/gbcs/gbcs.xml