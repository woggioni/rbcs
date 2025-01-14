package net.woggioni.gbcs.benchmark;

import lombok.Getter;
import lombok.SneakyThrows;
import net.woggioni.jwo.Fun;
import org.openjdk.jmh.annotations.Benchmark;
import org.openjdk.jmh.annotations.BenchmarkMode;
import org.openjdk.jmh.annotations.Level;
import org.openjdk.jmh.annotations.Mode;
import org.openjdk.jmh.annotations.OutputTimeUnit;
import org.openjdk.jmh.annotations.Scope;
import org.openjdk.jmh.annotations.Setup;
import org.openjdk.jmh.annotations.State;
import org.openjdk.jmh.annotations.TearDown;

import javax.net.ssl.KeyManagerFactory;
import javax.net.ssl.SSLContext;
import javax.net.ssl.TrustManager;
import javax.net.ssl.TrustManagerFactory;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.KeyStore;
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
        private final HttpClient client = createHttpClient();

        private final Map<String, byte[]> entries = new HashMap<>();


        private HttpClient createHttpClient() {
            final var clientBuilder = HttpClient.newBuilder();
            getSslContext().ifPresent(clientBuilder::sslContext);
            return clientBuilder.build();
        }

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
        public Optional<String> getClientTrustStorePassword() {
            return Optional.ofNullable(properties.getProperty("gbcs.client.ssl.truststore.password"))
                    .filter(Predicate.not(String::isEmpty));

        }

        @SneakyThrows
        public Optional<KeyStore> getClientTrustStore() {
            return Optional.ofNullable(properties.getProperty("gbcs.client.ssl.truststore.file"))
                    .filter(Predicate.not(String::isEmpty))
                    .map(Path::of)
                    .map((Fun<Path, KeyStore>) keyStoreFile -> {
                        final var keyStore = KeyStore.getInstance("PKCS12");
                        try (final var is = Files.newInputStream(keyStoreFile)) {
                            keyStore.load(is, getClientTrustStorePassword().map(String::toCharArray).orElse(null));
                        }
                        return keyStore;
                    });

        }

        @SneakyThrows
        public Optional<KeyStore> getClientKeyStore() {
            return Optional.ofNullable(properties.getProperty("gbcs.client.ssl.keystore.file"))
                    .filter(Predicate.not(String::isEmpty))
                    .map(Path::of)
                    .map((Fun<Path, KeyStore>) keyStoreFile -> {
                        final var keyStore = KeyStore.getInstance("PKCS12");
                        try (final var is = Files.newInputStream(keyStoreFile)) {
                            keyStore.load(is, getClientKeyStorePassword().map(String::toCharArray).orElse(null));
                        }
                        return keyStore;
                    });

        }

        @SneakyThrows
        public Optional<String> getClientKeyStorePassword() {
            return Optional.ofNullable(properties.getProperty("gbcs.client.ssl.keystore.password"))
                    .filter(Predicate.not(String::isEmpty));

        }

        @SneakyThrows
        public Optional<String> getClientKeyPassword() {
            return Optional.ofNullable(properties.getProperty("gbcs.client.ssl.key.password"))
                    .filter(Predicate.not(String::isEmpty));

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
        private Optional<SSLContext> getSslContext() {
            return getClientKeyStore().map((Fun<KeyStore, SSLContext>) clientKeyStore -> {
                final var kmf = KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm());
                kmf.init(clientKeyStore, getClientKeyStorePassword().map(String::toCharArray).orElse(null));


                // Set up trust manager factory with the truststore
                final var trustManagers = getClientTrustStore().map((Fun<KeyStore, TrustManager[]>) ts -> {
                    final var tmf = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm());
                    tmf.init(ts);
                    return tmf.getTrustManagers();
                }).orElse(new TrustManager[0]);

                // Create SSL context with the key and trust managers
                final var sslContext = SSLContext.getInstance("TLS");
                sslContext.init(kmf.getKeyManagers(), trustManagers, null);
                return sslContext;
            });
        }

        @SneakyThrows
        @Setup(Level.Trial)
        public void setUp() {
            final var client = getClient();
            for (int i = 0; i < 1000; i++) {
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

        @TearDown
        public void tearDown() {
            client.close();
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
