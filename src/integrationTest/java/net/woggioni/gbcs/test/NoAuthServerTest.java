package net.woggioni.gbcs.test;

import io.netty.handler.codec.http.HttpResponseStatus;
import lombok.SneakyThrows;
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
import java.nio.file.Path;
import java.time.Duration;
import java.util.Base64;
import java.util.Collections;
import java.util.Map;
import java.util.Random;
import java.util.zip.Deflater;
import java.io.IOException;

public class NoAuthServerTest extends AbstractServerTest {

    private Path cacheDir;
    private final Random random = new Random(101325);
    private final Map.Entry<String, byte[]> keyValuePair;

    public NoAuthServerTest() {
        this.keyValuePair = newEntry(random);
    }

    @Override
    @SneakyThrows
    protected void setUp() {
        this.cacheDir = testDir.resolve("cache");
        cfg = new Configuration(
                "127.0.0.1",
                new ServerSocket(0).getLocalPort() + 1,
                "/",
                Collections.emptyMap(),
                Collections.emptyMap(),
                new FileSystemCacheConfiguration(
                        this.cacheDir,
                        Duration.ofSeconds(3600 * 24),
                        "MD5",
                        true,
                        Deflater.DEFAULT_COMPRESSION
                ),
                null,
                null,
                true
        );
        Xml.Companion.write(Serializer.INSTANCE.serialize(cfg), System.out);
    }

    @Override
    protected void tearDown() {
        // Empty implementation
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
        try (HttpClient client = HttpClient.newHttpClient()) {
            String key = keyValuePair.getKey();
            byte[] value = keyValuePair.getValue();

            HttpRequest.Builder requestBuilder = newRequestBuilder(key)
                    .header("Content-Type", "application/octet-stream")
                    .PUT(HttpRequest.BodyPublishers.ofByteArray(value));

            HttpResponse<String> response = client.send(requestBuilder.build(), HttpResponse.BodyHandlers.ofString());
            Assertions.assertEquals(HttpResponseStatus.CREATED.code(), response.statusCode());
        }
    }

    @Test
    @Order(2)
    public void getWithNoAuthorizationHeader() throws IOException, InterruptedException {
        try (HttpClient client = HttpClient.newHttpClient()) {
            String key = keyValuePair.getKey();
            byte[] value = keyValuePair.getValue();

            HttpRequest.Builder requestBuilder = newRequestBuilder(key)
                    .GET();

            HttpResponse<byte[]> response = client.send(requestBuilder.build(), HttpResponse.BodyHandlers.ofByteArray());
            Assertions.assertEquals(HttpResponseStatus.OK.code(), response.statusCode());
            Assertions.assertArrayEquals(value, response.body());
        }
    }

    @Test
    @Order(3)
    public void getMissingKey() throws IOException, InterruptedException {
        try (HttpClient client = HttpClient.newHttpClient()) {

            Map.Entry<String, byte[]> entry = newEntry(random);
            String key = entry.getKey();

            HttpRequest.Builder requestBuilder = newRequestBuilder(key).GET();

            HttpResponse<byte[]> response = client.send(requestBuilder.build(), HttpResponse.BodyHandlers.ofByteArray());
            Assertions.assertEquals(HttpResponseStatus.NOT_FOUND.code(), response.statusCode());
        }
    }
}