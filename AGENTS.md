# AGENTS.md — Coding Agent Guide for RBCS

## Project Overview

RBCS (Remote Build Cache Server) is a Kotlin/Java multi-module Gradle project built on Netty.
It serves as a remote build cache for Gradle and Maven. Java is used for the public API layer
(with Lombok); Kotlin is used for all implementation modules. All modules use JPMS
(`module-info.java`). The project is built on Netty with Java 25.

**Modules:** `rbcs-api`, `rbcs-common`, `rbcs-server`, `rbcs-server-memcache`, `rbcs-client`,
`rbcs-cli`, `rbcs-servlet`, `docker`

## Build Commands

```bash
./gradlew build                          # Full build: compile + test + assemble
./gradlew assemble                       # Build JARs without running tests
./gradlew compileJava compileKotlin      # Compile only
./gradlew clean                          # Clean build outputs
./gradlew :rbcs-server:compileKotlin     # Compile a single module
./gradlew -q version                     # Print project version
```

## Test Commands

```bash
./gradlew test                           # Run all tests (all modules)
./gradlew :rbcs-server:test              # Run tests for a single module
./gradlew :rbcs-server:test --tests "net.woggioni.rbcs.server.test.NoAuthServerTest"
                                         # Run a single test class
./gradlew :rbcs-server:test --tests "net.woggioni.rbcs.server.test.NoAuthServerTest.putWithNoAuthorizationHeader"
                                         # Run a single test method
./gradlew test --tests "*TlsServer*"     # Run tests matching a pattern
./gradlew :rbcs-server:jacocoTestReport  # Generate code coverage (rbcs-server only)
```

**Test framework:** JUnit 5 (Jupiter). Tests are integration-style — they start real Netty
servers and use `java.net.http.HttpClient` to make HTTP requests. Netty leak detection is
set to `PARANOID` in test configurations.

**Test locations:** `src/test/kotlin/` and `src/test/java/` following standard Gradle layout.
Test resources (XML configs, logback) are in `src/test/resources/`.

## Lint / Format

No linting or formatting tools are configured (no Checkstyle, Detekt, ktlint, Spotless, or
similar). Follow the existing code style described below.

## Code Style Guidelines

### Language Split

- **Java** for `rbcs-api` (public API consumed by Java clients) with Lombok annotations
- **Kotlin** for all implementation modules
- **`module-info.java`** in every module (JPMS)

### Import Ordering (Kotlin)

Three groups separated by blank lines, each alphabetically sorted:
1. External/third-party (`io.netty.*`, `org.slf4j.*`)
2. Java standard library (`java.*`, `javax.*`)
3. Internal project (`net.woggioni.rbcs.*`)

Import aliases are rare; used only to resolve conflicts:
`import io.netty.util.concurrent.Future as NettyFuture`

### Naming Conventions

| Element             | Convention              | Examples                                          |
|---------------------|-------------------------|---------------------------------------------------|
| Classes/interfaces  | PascalCase              | `RemoteBuildCacheServer`, `CacheHandler`           |
| Abstract classes    | `Abstract` prefix       | `AbstractServerTest`, `AbstractNettyHttpAuthenticator` |
| Functions/methods   | camelCase               | `loadConfiguration()`, `sendShutdownSignal()`      |
| Variables/properties| camelCase               | `bucketManager`, `sslContext`                      |
| Constants           | SCREAMING_SNAKE_CASE    | `SSL_HANDLER_NAME`, `RBCS_NAMESPACE_URI`           |
| Handler names       | `val NAME = ...::class.java.name` in companion object                    |
| Packages            | lowercase dot-separated | `net.woggioni.rbcs.server.throttling`              |
| Enum values         | PascalCase              | `Role.Reader`, `Role.Writer`                       |
| Kotlin files        | PascalCase matching primary class; lowercase for files with only top-level functions |

### Error Handling

- Custom unchecked exception hierarchy rooted at `RbcsException extends RuntimeException`
- Domain subclasses: `CacheException`, `ConfigurationException`, `ContentTooLargeException`
- Async errors propagated via `CompletableFuture.completeExceptionally()`
- Synchronous errors thrown directly: `throw IllegalArgumentException(...)`
- Kotlin null safety idioms preferred over null checks: `?.let`, `?:`, `takeIf`
- `ExceptionHandler` maps exception types to HTTP responses via exhaustive `when`

### Type Annotations

- **Kotlin:** Heavy use of type inference for local variables. Explicit types required on:
  - Class properties: `private val sslContext: SslContext?`
  - Non-trivial return types: `fun run(): ServerHandle`
  - `lateinit var` in tests: `protected lateinit var cfg: Configuration`
- **Java:** Lombok `@Value` for immutable data classes; modern pattern matching with `instanceof`
- No `typealias` declarations are used in this project

### Async Patterns

- Primary abstraction: `CompletableFuture<T>` — **no Kotlin coroutines**
- Netty event-driven callbacks (`ChannelFuture`, `GenericFutureListener`)
- Custom `AsyncCloseable` interface wraps `CompletableFuture<Void>` for async shutdown
- Retry logic uses `CompletableFuture` composition with exponential backoff + jitter
- Virtual threads used selectively (background GC, configurable event executors)
- Connection pooling via Netty `FixedChannelPool`

### Logging

- SLF4J via custom Kotlin lazy-evaluation extension functions (defined in `rbcs-common`)
- Logger creation: `private val log = createLogger<ClassName>()` (in companion object)
- Lazy log calls: `log.debug { "message with ${interpolation}" }` (lambda only evaluated if enabled)
- Channel-aware variants: `log.debug(ctx) { "message" }` (adds MDC: channel-id, remote-address)
- Java classes use Lombok `@Slf4j`
- String templates with `${}` for Kotlin log messages

### Kotlin Idioms

- `apply` as builder pattern: `ServerBootstrap().apply { group(...); option(...) }`
- `also` for side effects, `let` for transformation, `run` for scoping
- Trailing commas in constructor parameter lists and multi-line function calls
- `sealed class`/`sealed interface` for algebraic types (e.g., `OperationOutcome`, `Authentication`)
- `data class` for value types; `companion object` for static members and constants
- No trailing semicolons

### Java Idioms (API module)

- Lombok annotations: `@Value`, `@Getter`, `@RequiredArgsConstructor`, `@EqualsAndHashCode.Include`
- `sealed interface` with `final` permitted subtypes (e.g., `CacheMessage`)
- `@FunctionalInterface` on single-method interfaces
- JPMS `module-info.java` in every module with `requires`, `exports`, `uses`, `provides`

### Testing Patterns

- `@TestInstance(Lifecycle.PER_CLASS)` — single instance per test class
- `@TestMethodOrder(MethodOrderer.OrderAnnotation)` with `@Order(n)` for sequential execution
- Abstract base class hierarchy: `AbstractServerTest` → `AbstractBasicAuthServerTest` → concrete
- Server lifecycle in `@BeforeAll` / `@AfterAll` (start/stop real Netty server)
- `@TempDir` for temporary directories
- `@ParameterizedTest` with `@ValueSource` and `@ArgumentsSource` for parameterized tests
- Assertions via `org.junit.jupiter.api.Assertions` (`assertEquals`, `assertArrayEquals`)
- No mocking framework — all tests are integration-style against real servers

### Documentation

Minimal doc comments in the codebase. Inline comments used sparingly for clarification.
When adding new public API, follow existing style — doc comments are not enforced but
are welcome on complex logic.

## Module Dependency Graph

```
rbcs-api        → (standalone, Lombok, Netty types)
rbcs-common     → Netty, Kotlin stdlib
rbcs-server     → rbcs-api, rbcs-common
rbcs-server-memcache → rbcs-api, rbcs-common
rbcs-client     → rbcs-api, rbcs-common
rbcs-cli        → rbcs-client, rbcs-server (Picocli for CLI)
rbcs-servlet    → rbcs-api, rbcs-common (Jakarta Servlet/CDI)
docker          → rbcs-cli, rbcs-server-memcache
```

## Plugin System

Cache backends use the `ServiceLoader` pattern via JPMS `provides`/`uses` directives.
To add a new cache provider, implement `CacheProvider<T extends Configuration.Cache>` and
register it in your module's `module-info.java`.

## Configuration

- XML-based with XSD schema validation
- Schemas in `src/main/resources/.../schema/*.xsd`
- Default config loaded from JPMS resources: `jpms://net.woggioni.rbcs.server/...`
