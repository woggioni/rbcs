package net.woggioni.gbcs.benchmark;

import lombok.Getter;
import lombok.SneakyThrows;
import org.openjdk.jmh.annotations.Benchmark;
import org.openjdk.jmh.annotations.BenchmarkMode;
import org.openjdk.jmh.annotations.Level;
import org.openjdk.jmh.annotations.Mode;
import org.openjdk.jmh.annotations.OutputTimeUnit;
import org.openjdk.jmh.annotations.Scope;
import org.openjdk.jmh.annotations.Setup;
import org.openjdk.jmh.annotations.State;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.Base64;
import java.util.Collections;
import java.util.HashMap;
import java.util.Iterator;
import java.util.Map;
import java.util.Optional;
import java.util.Properties;
import java.util.Random;
import java.util.concurrent.TimeUnit;
import java.util.function.Predicate;

public class Main {

    @SneakyThrows
    private static Properties loadProperties() {
        Properties properties = new Properties();
        try (final var is = Main.class.getResourceAsStream("/benchmark.properties")) {
            properties.load(is);
        }
        return properties;
    }

    private static final Properties properties = loadProperties();

    @State(Scope.Thread)
    public static class ExecutionPlan {
        private final Random random = new Random(101325);

        @Getter
        private final HttpClient client = HttpClient.newHttpClient();

        private final Map<String, byte[]> entries = new HashMap<>();

        public final Map<String, byte[]> getEntries() {
            return Collections.unmodifiableMap(entries);
        }

        public Map.Entry<String, byte[]> newEntry() {
            final var keyBuffer = new byte[0x20];
            random.nextBytes(keyBuffer);
            final var key = Base64.getUrlEncoder().encodeToString(keyBuffer);
            final var value = new byte[0x1000];
            random.nextBytes(value);
            return Map.entry(key, value);
        }

        @SneakyThrows
        public HttpRequest.Builder newRequestBuilder(String key) {
            final var requestBuilder = HttpRequest.newBuilder()
                    .uri(getServerURI().resolve(key));
            String user = getUser();
            if (user != null) {
                requestBuilder.header("Authorization", buildAuthorizationHeader(user, getPassword()));
            }
            return requestBuilder;
        }

        @SneakyThrows
        public URI getServerURI() {
            return new URI(properties.getProperty("gbcs.server.url"));
        }

        @SneakyThrows
        public String getUser() {
            return Optional.ofNullable(properties.getProperty("gbcs.server.username"))
                    .filter(Predicate.not(String::isEmpty))
                    .orElse(null);

        }

        @SneakyThrows
        public String getPassword() {
            return Optional.ofNullable(properties.getProperty("gbcs.server.password"))
                    .filter(Predicate.not(String::isEmpty))
                    .orElse(null);
        }

        private String buildAuthorizationHeader(String user, String password) {
            final var b64 = Base64.getEncoder().encode(String.format("%s:%s", user, password).getBytes(StandardCharsets.UTF_8));
            return "Basic " + new String(b64);
        }


        @SneakyThrows
        @Setup(Level.Trial)
        public void setUp() {
            try (final var client = HttpClient.newHttpClient()) {
                for (int i = 0; i < 10000; i++) {
                    final var pair = newEntry();
                    final var requestBuilder = newRequestBuilder(pair.getKey())
                            .header("Content-Type", "application/octet-stream")
                            .PUT(HttpRequest.BodyPublishers.ofByteArray(pair.getValue()));
                    final var response = client.send(requestBuilder.build(), HttpResponse.BodyHandlers.ofString());
                    if (201 != response.statusCode()) {
                        throw new IllegalStateException(Integer.toString(response.statusCode()));
                    } else {
                        entries.put(pair.getKey(), pair.getValue());
                    }
                }
            }
        }

        private Iterator<Map.Entry<String, byte[]>> it = null;

        private Map.Entry<String, byte[]> nextEntry() {
            if (it == null || !it.hasNext()) {
                it = getEntries().entrySet().iterator();
            }
            return it.next();
        }
    }

    @SneakyThrows
    @Benchmark
    @BenchmarkMode(Mode.Throughput)
    @OutputTimeUnit(TimeUnit.SECONDS)
    public void get(ExecutionPlan plan) {
        final var client = plan.getClient();
        final var entry = plan.nextEntry();
        final var requestBuilder = plan.newRequestBuilder(entry.getKey())
                .header("Accept", "application/octet-stream")
                .GET();
        final var response = client.send(requestBuilder.build(), HttpResponse.BodyHandlers.ofByteArray());
        if (200 != response.statusCode()) {
            throw new IllegalStateException(Integer.toString(response.statusCode()));
        } else {
            if (!Arrays.equals(entry.getValue(), response.body())) {
                throw new IllegalStateException("Retrieved unexpected value");
            }
        }
    }


    @SneakyThrows
    @Benchmark
    @BenchmarkMode(Mode.Throughput)
    @OutputTimeUnit(TimeUnit.SECONDS)
    public void put(Main.ExecutionPlan plan) {
        final var client = plan.getClient();
        final var entry = plan.nextEntry();

        final var requestBuilder = plan.newRequestBuilder(entry.getKey())
                .header("Content-Type", "application/octet-stream")
                .PUT(HttpRequest.BodyPublishers.ofByteArray(entry.getValue()));

        final var response = client.send(requestBuilder.build(), HttpResponse.BodyHandlers.ofByteArray());
        if (201 != response.statusCode()) {
            throw new IllegalStateException(Integer.toString(response.statusCode()));
        }
    }
}
