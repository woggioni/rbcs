# Remote Build Cache Server
Remote Build Cache Server (shortened to RBCS) allows you to share and reuse unchanged build 
and test outputs across the team. This speeds up local and CI builds since cycles are not wasted
re-building components that are unaffected by new code changes. RBCS supports both Gradle and
Maven build tool environments.

It comes with pluggable storage backends, the core application offers in-memory storage or disk-backed storage,
in addition to this there is an official plugin to use memcached as the storage backend.

It supports HTTP basic authentication or, alternatively, TLS certificate authentication, role-based access control (RBAC),
and throttling.

## Quickstart

### Downloading the jar file 
You can download the latest version from [this link](https://gitea.woggioni.net/woggioni/-/packages/maven/net.woggioni:rbcs-cli/)

Assuming you have Java 21 or later installed, you can launch the server directly with

```bash
java -jar rbcs-cli.jar server
```

By default it will start an HTTP server bound to localhost and listening on port 8080 with no authentication,
writing data to the disk, that you can use for testing

### Using the Docker image
You can pull the latest Docker image with
```bash
docker pull gitea.woggioni.net/woggioni/rbcs:latest
```

By default it will start an HTTP server bound to localhost and listening on port 8080 with no authentication,
writing data to the disk, that you can use for testing

## Usage

### Configuration
The location of the `rbcs.xml` configuration file depends on the operating system, 
Alternatively it can be changed setting the `RBCS_CONFIGURATION_DIR` environmental variable or `net.woggioni.rbcs.conf.dir` Java system property
to the directory that contain the `rbcs.xml` file.

The server configuration file follows the XML format and uses XML schema for validation
(you can find the schema for the main configuration file [here](https://gitea.woggioni.net/woggioni/rbcs/src/branch/master/rbcs-server/src/main/resources/net/woggioni/rbcs/server/schema/rbcs.xsd)).

The configuration values are enclosed inside XML attribute and support system property / environmental variable interpolation.
As an example, you can configure RBCS to read the server port number from the `RBCS_SERVER_PORT` environmental variable
and the bind address from the `rbc.bind.address` JVM system property with.

Full documentation for all tags and attributes is available [here](doc/server_configuration.md).

### Plugins
If you want to use memcache as a storage backend you'll also need to download [the memcache plugin](https://gitea.woggioni.net/woggioni/-/packages/maven/net.woggioni:rbcs-server-memcache/)

Plugins need to be stored in a folder named `plugins` in the located server's working directory
(the directory where the server process is started). They are shipped as TAR archives, so you need to extract
the content of the archive into the `plugins` directory for the server to pick them up.

### Using RBCS with Gradle 

Add this to the `settings.gradle` file of your project

```groovy
buildCache {
    remote(HttpBuildCache) {
        url = 'https://rbcs.example.com/'
        push = true
        allowInsecureProtocol = false
    }
}
```

alternatively you can add this to `${GRADLE_HOME}/init.gradle`

```groovy
gradle.settingsEvaluated { settings ->
    settings.buildCache {
        remote(HttpBuildCache) {
            url = 'https://rbcs.example.com/'
            push = true
            allowInsecureProtocol = false
        }
    }
}
```

### Using RBCS with Maven

Read [here](https://maven.apache.org/extensions/maven-build-cache-extension/remote-cache.html)

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

