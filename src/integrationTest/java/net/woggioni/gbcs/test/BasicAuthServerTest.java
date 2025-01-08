package net.woggioni.gbcs.test;

import io.netty.handler.codec.http.HttpResponseStatus;
import lombok.SneakyThrows;
import net.woggioni.gbcs.AbstractNettyHttpAuthenticator;
import net.woggioni.gbcs.api.Role;
import net.woggioni.gbcs.base.Xml;
import net.woggioni.gbcs.api.Configuration;
import net.woggioni.gbcs.cache.FileSystemCacheConfiguration;
import net.woggioni.gbcs.configuration.Serializer;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;

import java.net.ServerSocket;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Duration;
import java.util.*;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import java.util.zip.Deflater;
import java.io.IOException;

public class BasicAuthServerTest extends AbstractServerTest {

    private static final String PASSWORD = "password";
    private Path cacheDir;
    private final Random random = new Random(101325);
    private final Map.Entry<String, byte[]> keyValuePair;

    public BasicAuthServerTest() {
        this.keyValuePair = newEntry(random);
    }

    @Override
    @SneakyThrows
    protected void setUp() {
        this.cacheDir = testDir.resolve("cache");
        Configuration.Group readersGroup = new Configuration.Group("readers", Set.of(Role.Reader));
        Configuration.Group writersGroup = new Configuration.Group("writers", Set.of(Role.Writer));

        List<Configuration.User> users = Arrays.asList(
                new Configuration.User("user1", AbstractNettyHttpAuthenticator.Companion.hashPassword(PASSWORD, null), Set.of(readersGroup)),
                new Configuration.User("user2", AbstractNettyHttpAuthenticator.Companion.hashPassword(PASSWORD, null), Set.of(writersGroup)),
                new Configuration.User("user3", AbstractNettyHttpAuthenticator.Companion.hashPassword(PASSWORD, null), Set.of(readersGroup, writersGroup))
        );

        Map<String, Configuration.User> usersMap = users.stream()
                .collect(Collectors.toMap(user -> user.getName(), user -> user));

        Map<String, Configuration.Group> groupsMap = Stream.of(writersGroup, readersGroup)
                .collect(Collectors.toMap(group -> group.getName(), group -> group));

        cfg = new Configuration(
                "127.0.0.1",
                new ServerSocket(0).getLocalPort() + 1,
                "/",
                usersMap,
                groupsMap,
                new FileSystemCacheConfiguration(
                        this.cacheDir,
                        Duration.ofSeconds(3600 * 24),
                        "MD5",
                        false,
                        Deflater.DEFAULT_COMPRESSION
                ),
                new Configuration.BasicAuthentication(),
                null,
                true
        );

        Xml.Companion.write(Serializer.INSTANCE.serialize(cfg), System.out);
    }

    @Override
    protected void tearDown() {
        // Empty implementation
    }

    private String buildAuthorizationHeader(Configuration.User user, String password) {
        String credentials = user.getName() + ":" + password;
        byte[] encodedCredentials = Base64.getEncoder().encode(credentials.getBytes(StandardCharsets.UTF_8));
        return "Basic " + new String(encodedCredentials, StandardCharsets.UTF_8);
    }

    private HttpRequest.Builder newRequestBuilder(String key) {
        return HttpRequest.newBuilder()
                .uri(URI.create(String.format("http://%s:%d/%s", cfg.getHost(), cfg.getPort(), key)));
    }

    private Map.Entry<String, byte[]> newEntry(Random random) {
        byte[] keyBytes = new byte[0x10];
        random.nextBytes(keyBytes);
        String key = Base64.getUrlEncoder().encodeToString(keyBytes);

        byte[] value = new byte[0x1000];
        random.nextBytes(value);

        return Map.entry(key, value);
    }

    @Test
    @Order(1)
    public void putWithNoAuthorizationHeader() throws IOException, InterruptedException {
        try(HttpClient client = HttpClient.newHttpClient()) {
            String key = keyValuePair.getKey();
            byte[] value = keyValuePair.getValue();

            HttpRequest.Builder requestBuilder = newRequestBuilder(key)
                    .header("Content-Type", "application/octet-stream")
                    .PUT(HttpRequest.BodyPublishers.ofByteArray(value));

            HttpResponse<String> response = client.send(requestBuilder.build(), HttpResponse.BodyHandlers.ofString());
            Assertions.assertEquals(HttpResponseStatus.UNAUTHORIZED.code(), response.statusCode());
        }
    }

    @Test
    @Order(2)
    public void putAsAReaderUser() throws IOException, InterruptedException {
        try(HttpClient client = HttpClient.newHttpClient()) {
            String key = keyValuePair.getKey();
            byte[] value = keyValuePair.getValue();

            Configuration.User user = cfg.getUsers().values().stream()
                    .filter(u -> u.getRoles().contains(Role.Reader) && !u.getRoles().contains(Role.Writer))
                    .findFirst()
                    .orElseThrow(() -> new RuntimeException("Reader user not found"));

            HttpRequest.Builder requestBuilder = newRequestBuilder(key)
                    .header("Authorization", buildAuthorizationHeader(user, PASSWORD))
                    .header("Content-Type", "application/octet-stream")
                    .PUT(HttpRequest.BodyPublishers.ofByteArray(value));

            HttpResponse<String> response = client.send(requestBuilder.build(), HttpResponse.BodyHandlers.ofString());
            Assertions.assertEquals(HttpResponseStatus.FORBIDDEN.code(), response.statusCode());
        }
    }

    @Test
    @Order(3)
    public void getAsAWriterUser() throws IOException, InterruptedException {
        try (HttpClient client = HttpClient.newHttpClient()) {
            String key = keyValuePair.getKey();

            Configuration.User user = cfg.getUsers().values().stream()
                    .filter(u -> u.getRoles().contains(Role.Writer))
                    .findFirst()
                    .orElseThrow(() -> new RuntimeException("Writer user not found"));

            HttpRequest.Builder requestBuilder = newRequestBuilder(key)
                    .header("Authorization", buildAuthorizationHeader(user, PASSWORD))
                    .GET();

            HttpResponse<String> response = client.send(requestBuilder.build(), HttpResponse.BodyHandlers.ofString());
            Assertions.assertEquals(HttpResponseStatus.FORBIDDEN.code(), response.statusCode());
        }
    }

    @Test
    @Order(4)
    public void putAsAWriterUser() throws IOException, InterruptedException {
        try (HttpClient client = HttpClient.newHttpClient()) {
            String key = keyValuePair.getKey();
            byte[] value = keyValuePair.getValue();

            Configuration.User user = cfg.getUsers().values().stream()
                    .filter(u -> u.getRoles().contains(Role.Writer))
                    .findFirst()
                    .orElseThrow(() -> new RuntimeException("Writer user not found"));

            HttpRequest.Builder requestBuilder = newRequestBuilder(key)
                    .header("Content-Type", "application/octet-stream")
                    .header("Authorization", buildAuthorizationHeader(user, PASSWORD))
                    .PUT(HttpRequest.BodyPublishers.ofByteArray(value));

            HttpResponse<String> response = client.send(requestBuilder.build(), HttpResponse.BodyHandlers.ofString());
            Assertions.assertEquals(HttpResponseStatus.CREATED.code(), response.statusCode());
        }
    }

    @Test
    @Order(5)
    public void getAsAReaderUser() throws IOException, InterruptedException {
        try (HttpClient client = HttpClient.newHttpClient()) {
            String key = keyValuePair.getKey();
            byte[] value = keyValuePair.getValue();

            Configuration.User user = cfg.getUsers().values().stream()
                    .filter(u -> u.getRoles().contains(Role.Reader))
                    .findFirst()
                    .orElseThrow(() -> new RuntimeException("Reader user not found"));

            HttpRequest.Builder requestBuilder = newRequestBuilder(key)
                    .header("Authorization", buildAuthorizationHeader(user, PASSWORD))
                    .GET();

            HttpResponse<byte[]> response = client.send(requestBuilder.build(), HttpResponse.BodyHandlers.ofByteArray());
            Assertions.assertEquals(HttpResponseStatus.OK.code(), response.statusCode());
            Assertions.assertArrayEquals(value, response.body());
        }
    }

    @Test
    @Order(6)
    public void getMissingKeyAsAReaderUser() throws IOException, InterruptedException {
        try (HttpClient client = HttpClient.newHttpClient()) {
            Map.Entry<String, byte[]> entry = newEntry(random);
            String key = entry.getKey();

            Configuration.User user = cfg.getUsers().values().stream()
                    .filter(u -> u.getRoles().contains(Role.Reader))
                    .findFirst()
                    .orElseThrow(() -> new RuntimeException("Reader user not found"));

            HttpRequest.Builder requestBuilder = newRequestBuilder(key)
                    .header("Authorization", buildAuthorizationHeader(user, PASSWORD))
                    .GET();

            HttpResponse<byte[]> response = client.send(requestBuilder.build(), HttpResponse.BodyHandlers.ofByteArray());
            Assertions.assertEquals(HttpResponseStatus.NOT_FOUND.code(), response.statusCode());
        }
    }
}