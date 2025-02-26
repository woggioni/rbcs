# Remote Build Cache Server

![Release](https://img.shields.io/gitea/v/release/woggioni/rbcs?gitea_url=https%3A%2F%2Fgitea.woggioni.net)
![Version](https://img.shields.io/maven-metadata/v?metadataUrl=https%3A%2F%2Fgitea.woggioni.net%2Fapi%2Fpackages%2Fwoggioni%2Fmaven%2Fnet%2Fwoggioni%2Frbcs-cli%2Fmaven-metadata.xml)
![License](https://img.shields.io/badge/license-MIT-green)
![Language](https://img.shields.io/gitea/languages/count/woggioni/rbcs?gitea_url=https%3A%2F%2Fgitea.woggioni.net)

<!--
![Last commit](https://img.shields.io/gitea/last-commit/woggioni/rbcs?gitea_url=https%3A%2F%2Fgitea.woggioni.net)
-->

Speed up your builds by sharing and reusing unchanged build outputs across your team.

Remote Build Cache Server (RBCS) allows teams to share and reuse unchanged build and test outputs, 
significantly reducing build times for both local and CI environments. By eliminating redundant work, 
RBCS helps teams become more productive and efficient.

**Key Features:**
- Support for both Gradle and Maven build environments
- Pluggable storage backends (in-memory, disk-backed, memcached)
- Flexible authentication (HTTP basic or TLS certificate)
- Role-based access control
- Request throttling

## Table of Contents
- [Quickstart](#quickstart)
- [Integration with build tools](#integration-with-build-tools)
  - [Use RBCS with Gradle](#use-rbcs-with-gradle)
  - [Use RBCS with Maven](#use-rbcs-with-maven)
- [Server configuration](#server-configuration)
- [Authentication](#authentication)
  - [HTTP Basic authentication](#configure-http-basic-authentication)
  - [TLS client certificate authentication](#configure-tls-certificate-authentication)
- [Authentication & Access Control](#access-control)
- [Plugins](#plugins)
- [Client Tools](#rbcs-client)
- [Logging](#logging)
- [FAQ](#faq)



Remote Build Cache Server (shortened to RBCS) allows you to share and reuse unchanged build 
and test outputs across the team. This speeds up local and CI builds since cycles are not wasted
re-building components that are unaffected by new code changes. RBCS supports both Gradle and
Maven build tool environments.

It comes with pluggable storage backends, the core application offers in-memory storage or disk-backed storage,
in addition to this there is an official plugin to use memcached as the storage backend.

It supports HTTP basic authentication or, alternatively, TLS certificate authentication, role-based access control (RBAC),
and throttling.

## Quickstart

### Use the all-in-one jar file 
You can download the latest version from [this link](https://gitea.woggioni.net/woggioni/-/packages/maven/net.woggioni:rbcs-cli/)


Assuming you have Java 21 or later installed, you can launch the server directly with

```bash
java -jar rbcs-cli.jar server
```

By default it will start an HTTP server bound to localhost and listening on port 8080 with no authentication,
writing data to the disk, that you can use for testing

### Use the Docker image
You can pull the latest Docker image with
```bash
docker pull gitea.woggioni.net/woggioni/rbcs:latest
```

By default it will start an HTTP server bound to localhost and listening on port 8080 with no authentication,
writing data to the disk, that you can use for testing

### Use the native executable
If you are on a Linux X86_64 machine you can download the native executable
from [here](https://gitea.woggioni.net/woggioni/-/packages/maven/net.woggioni:rbcs-cli/).
It behaves the same as the jar file but it doesn't require a JVM and it has faster startup times.
becausue of GraalVm's [closed-world assumption](https://www.graalvm.org/latest/reference-manual/native-image/basics/#static-analysis),
the native executable does not supports plugins, so it comes with all plugins embedded into it.

## Integration with build tools

### Use RBCS with Gradle

Add this to the `settings.gradle` file of your project

```groovy
buildCache {
    remote(HttpBuildCache) {
        url = 'https://rbcs.example.com/'
        push = true
        allowInsecureProtocol = false
        // The credentials block is only required if you enable 
        // HTTP basic authentication on RBCS
        credentials {
            username = 'build-cache-user'
            password = 'some-complicated-password'
        }
    }
}
```

alternatively you can add this to `${GRADLE_HOME}/init.gradle` to configure the remote cache
at the system level

```groovy
gradle.settingsEvaluated { settings ->
    settings.buildCache {
        remote(HttpBuildCache) {
            url = 'https://rbcs.example.com/'
            push = true
            allowInsecureProtocol = false
            // The credentials block is only required if you enable 
            // HTTP basic authentication on RBCS
            credentials {
                username = 'build-cache-user'
                password = 'some-complicated-password'
            }
        }
    }
}
```

add `org.gradle.caching=true` to your `<project>/gradle.properties` or run gradle with `--build-cache`.

Read [Gradle documentation](https://docs.gradle.org/current/userguide/build_cache.html) for more detailed information.

### Use RBCS with Maven

1. Create an `extensions.xml` in `<project>/.mvn/extensions.xml` with the following content
  ```xml
  <extensions xmlns="http://maven.apache.org/EXTENSIONS/1.1.0" xmlns:xsi="http://www.w3.org/2001/XMLSchema-instance"
            xsi:schemaLocation="http://maven.apache.org/EXTENSIONS/1.1.0 https://maven.apache.org/xsd/core-extensions-1.0.0.xsd">
      <extension>
          <groupId>org.apache.maven.extensions</groupId>
          <artifactId>maven-build-cache-extension</artifactId>
          <version>1.2.0</version>
      </extension>
  </extensions>
  ```
2. Copy [maven-build-cache-config.xml](https://maven.apache.org/extensions/maven-build-cache-extension/maven-build-cache-config.xml) into `<project>/.mvn/` folder
3. Edit the `cache/configuration/remote` element
  ```xml
    <remote enabled="true" id="rbcs">
        <url>https://rbcs.example.com/</url>
    </remote>
  ```
4. Run maven with
  ```bash
    mvn -Dmaven.build.cache.enabled=true -Dmaven.build.cache.debugOutput=true -Dmaven.build.cache.remote.save.enabled=true package
  ```

Alternatively you can set those properties in your `<project>/pom.xml`

Read [here](https://maven.apache.org/extensions/maven-build-cache-extension/remote-cache.html)
for more informations


## Server configuration
RBCS reads an XML configuration file, by default named `rbcs-server.xml`.
The expected location of the `rbcs-server.xml` file depends on the operating system,
if the configuration file is not found a default one will be created and its location is printed
on the console

```bash
user@76a90cbcd75d:~$ rbcs-cli server
2025-01-01 00:00:00,000 [INFO ] (main) n.w.r.c.impl.commands.ServerCommand -- Creating default configuration file at '/home/user/.config/rbcs/rbcs-server.xml'
```

Alternatively it can be changed setting the `RBCS_CONFIGURATION_DIR` environmental variable or `net.woggioni.rbcs.conf.dir` 
Java system property to the directory that contain the `rbcs-server.xml` file.
It can also be directly specified from the command line with
```bash
java -jar rbcs-cli.jar server -c /path/to/rbcs-server.xml
```

The server configuration file follows the XML format and uses XML schema for validation
(you can find the schema for the `rbcs-server.xml` configuration file [here](https://gitea.woggioni.net/woggioni/rbcs/src/branch/master/rbcs-server/src/main/resources/net/woggioni/rbcs/server/schema/rbcs-server.xsd)).

The configuration values are enclosed inside XML attribute and support system property / environmental variable interpolation.
As an example, you can configure RBCS to read the server port number from the `RBCS_SERVER_PORT` environmental variable
and the bind address from the `rbc.bind.address` JVM system property with

```xml
<bind host="${sys:rpc.bind.address}" port="${env:RBCS_SERVER_PORT}"/>
```

Full documentation for all tags and attributes and configuration file examples
are available [here](doc/server_configuration.md).

### Plugins
If you want to use memcache as a storage backend you'll also need to download [the memcache plugin](https://gitea.woggioni.net/woggioni/-/packages/maven/net.woggioni:rbcs-server-memcache/)

Plugins need to be stored in a folder named `plugins` in the located server's working directory
(the directory where the server process is started). They are shipped as TAR archives, so you need to extract
the content of the archive into the `plugins` directory for the server to pick them up.

## Authentication

RBCS supports 2 authentication mechanisms:

- HTTP basic authentication
- TLS certificate authentication

### Configure HTTP basic authentication

Add a `<basic>` element to the `<authentication>` element in your `rbcs-server.xml`
```xml
    <authentication>
      <basic/>
    </authentication>
```

### Configure TLS certificate authentication

Add a `<client-certificate>` element to the `<authentication>` element in your `rbcs-server.xml`
```xml
    <authentication>
        <client-certificate>
            <user-extractor attribute-name="CN" pattern="(.*)"/>
            <group-extractor attribute-name="O" pattern="(.*)"/>
        </client-certificate>
    </authentication>
```
The `<user-extractor>` here determines how the username is extracted from the
subject's X.500 name in the TLS certificate presented by the client, where `attribute-name`
is the `RelativeDistinguishedName` (RDN) identifier and pattern is a regular expression
that will be applied to extract the username from the first group present in the regex.
An error will be thrown if the regular expression contains no groups, while additional
groups are ignored.

Similarly, the `<group-extractor>` here determines how the group name is extracted from the
subject's X.500 name in the TLS certificate presented by the client. 
Note that this allows to assign roles to incoming requests without necessarily assigning them
a username.



## Access control

RBCS supports role-based access control (RBAC), three roles are available:
- `Reader` can perform `GET` calls
- `Writer` can perform `PUT` calls
- `Healthcheck` can perform `TRACE` calls

Roles are assigned to groups so that a user will have a role only if that roles belongs
to one of the groups he is a member of.

There is also a special `<anonymous>` user 
which matches any request who hasn't been authenticated and that can be assigned
to any group like a normal user. This permits to have a build cache that is
publicly readable but only writable by authenticated users (e.g. CI/CD pipeline).

### Defining users

Users can be defined in the `<authorization>` element
```xml
    <authorization>
        <users>
            <user name="user1" password="kb/vNnkn2RvyPkTN6Q07uH0F7wI7u61MkManD3NHregRukBg4KHehfbqtLTb39fZjHA+SRH+EpEWDCf+Rihr5H5C1YN5qwmArV0p8O5ptC4="/>
            <user name="user2" password="2J7MAhdIzZ3SO+JGB+K6wPhb4P5LH1L4L7yJCl5QrxNfAWRr5jTUExJRbcgbH1UfnkCbIO1p+xTDq+FCj3LFBZeMZUNZ47npN+WR7AX3VTo="/>
            <anonymous/>
        </users>
        <groups>
            <group name="readers">
                <users>
                    <anonymous/>
                </users>
                <roles>
                    <reader/>
                </roles>
            </group>
            <group name="writers">
                <users>
                    <user ref="user1"/>
                    <user ref="user2"/>
                </users>
                <roles>
                    <reader/>
                    <writer/>
                    <healthcheck/>
                </roles>
            </group>
        </groups>
    </authorization>
```

The `password` attribute is only used for HTTP Basic authentication, so it can be omitted
if you use TLS certificate authentication. It must contain a password hash that can be derived from
the actual password using the following command

```bash
java -jar rbcs-cli.jar password
```

## Reliability

RBCS implements the [TRACE](https://developer.mozilla.org/en-US/docs/Web/HTTP/Methods/TRACE) HTTP method and this functionality can be used
as a health check (mind you need to have `Healthcheck` role in order to perform it and match the server's `prefix` in the URL).

## RBCS Client

RBCS ships with a command line client that can be used for testing, benchmarking or to manually 
upload/download files to the cache. It must be configured with the `rbcs-client.xml`, 
whose location follows the same logic of the `rbcs-server.xml`. 
The `rbcs-client.xml` must adhere to the [rbcs-client.xsd](rbcs-client/src/main/resources/net/woggioni/rbcs/client/schema/rbcs-client.xsd) 
XML schema

The documentation for the `rbcs-client.xml` configuration file is available [here](conf/client_configuration.md)

### GET command

```bash
java -jar rbcs-cli.jar client -p $CLIENT_PROFILE_NAME get -k $CACHE_KEY -v $FILE_WHERE_THE_VALUE_WILL_BE_STORED
```

### PUT command

```bash
java -jar rbcs-cli.jar client -p $CLIENT_PROFILE_NAME put -k $CACHE_KEY -v $FILE_TO_BE_UPLOADED
```

If you don't specify the key, a UUID key based on the file content will be used, 
if you add the `-i` command line parameter, the uploaded file will be served with
`Content-Disposition: inline` HTTP header so that browser will attempt to render 
it in the page instead of triggering a file download (in this way you can create a temporary web page).

The client will try to detect the file mime type upon upload but if you want to be sure you can specify
it manually with the `-t` parameter. 

### Benchmark command

```bash
java -jar rbcs-cli.jar client -p $CLIENT_PROFILE_NAME benchamrk -s 4096 -e 10000
```
This will insert 10000 randomly generates entries of 4096 bytes into RBCS, then retrieve them
and check that the retrieved value matches what was inserted. 
It will also print throughput stats on the way.

## Logging

RBCS uses [logback](https://logback.qos.ch/) and ships with a [default logging configuration](./conf/logback.xml) that
can be overridden with `-Dlogback.configurationFile=path/to/custom/configuration.xml`, refer to
[Logback documentation](https://logback.qos.ch/manual/configuration.html) for more details about
how to configure Logback

## FAQ
### Why should I use a build cache?

#### Build Caches Improve Build & Test Performance

Building software consists of a number of steps, like compiling sources, executing tests, and linking binaries. We’ve seen that a binary artifact repository helps when such a step requires an external component by downloading the artifact from the repository rather than building it locally.
However, there are many additional steps in this build process which can be optimized to reduce the build time. An obvious strategy is to avoid executing build steps which dominate the total build time when these build steps are not needed.
Most build times are dominated by the testing step. 

While binary repositories cannot capture the outcome of a test build step (only the test reports 
when included in binary artifacts), build caches are designed to eliminate redundant executions 
for every build step. Moreover, it generalizes the concept of avoiding work associated with any 
incremental step of the build, including test execution, compilation and resource processing. 
The mechanism itself is comparable to a pure function. That is, given some inputs such as source 
files and  environment parameters we know that the output is always going to be the same. 
As a result, we can cache it and retrieve it based on a simple cryptographic hash of the inputs.
Build caching is supported natively by some build tools. 

#### Improve CI builds with a remote build cache

When analyzing the role of a build cache it is important to take into account the granularity 
of the changes that it caches. Imagine a full build for a project with 40 to 50 modules 
which fails at the last step (deployment) because the staging environment is temporarily unavailable.
Although the vast majority of the build steps (potentially thousands) succeed, 
the change can not be deployed to the staging environment. 
Without a build cache one typically relies on a very complex CI configuration to reuse build step outputs
or would have to repeat the full build once the environment is available.

Some build tools don’t support incremental builds properly. For example, outputs of a build started
from scratch may vary when compared to subsequent builds that rely on the initial build’s output.
As a result, to preserve build integrity, it’s crucial to rebuild from scratch, or ‘cleanly,’ in this
scenario.

With a build cache, only the last step needs to be executed and the build can be re-triggered 
when the environment is back online. This automatically saves all of the time and 
resources required across the different build steps which were successfully executed. 
Instead of executing the intermediate steps, the build tool pulls the outputs from the build cache,
avoiding a lot of redundant work

#### Share outputs with a remote build cache

One of the most important advantages of a remote build cache is the ability to share build outputs. 
In most CI configurations, for example, a number of pipelines are created.
These may include one for building the sources, one for testing, one for publishing the outcomes 
to a remote repository, and other pipelines to test on different platforms. 
There are even situations where CI builds partially build a project (i.e. some modules and not others).

Most of those pipelines share a lot of intermediate build steps. All builds which perform testing
require the binaries to be ready. All publishing builds require all previous steps to be executed.
And because modern CI infrastructure means executing everything in containerized (isolated) environments,
significant resources are wasted by repeatedly building the same intermediate artifacts.

A remote build cache greatly reduces this overhead by orders of magnitudes because it provides a way
for all those pipelines to share their outputs. After all, there is no point recreating an output that
is already available in the cache.

Because there are inherent dependencies between software components of a build,
introducing a build cache dramatically reduces the impact of exploding a component into multiple pieces,
allowing for increased modularity without increased overhead.

#### Make local developers more efficient with remote build caches

It is common for different teams within a company to work on different modules of a single large
application. In this case, most teams don’t care about building the other parts of the software.
By introducing a remote cache developers immediately benefit from pre-built artifacts when checking out code. 
Because it has already been built on CI, they don’t have to do it locally.

Introducing a remote cache is a huge benefit for those developers. Consider that a typical developer’s
day begins by performing a code checkout. Most likely the checked out code has already been built on CI.
Therefore, no time is wasted running the first build of the day. The remote cache provides all of the
intermediate artifacts needed. And, in the event local changes are made, the remote cache still leverages
partial cache hits for projects which are independent. As other developers in the organization request 
CI builds, the remote cache continues to populate, increasing the likelihood of these remote cache hits
across team members.

