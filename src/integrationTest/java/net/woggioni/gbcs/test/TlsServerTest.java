package net.woggioni.gbcs.test;

import io.netty.handler.codec.http.HttpResponseStatus;
import lombok.SneakyThrows;
import net.woggioni.gbcs.api.Configuration;
import net.woggioni.gbcs.api.Role;
import net.woggioni.gbcs.base.Xml;
import net.woggioni.gbcs.cache.FileSystemCacheConfiguration;
import net.woggioni.gbcs.configuration.Serializer;
import net.woggioni.gbcs.utils.CertificateUtils;
import net.woggioni.gbcs.utils.CertificateUtils.X509Credentials;
import org.bouncycastle.asn1.x500.X500Name;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;

import javax.net.ssl.KeyManagerFactory;
import javax.net.ssl.SSLContext;
import javax.net.ssl.TrustManagerFactory;
import java.net.ServerSocket;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.KeyStore;
import java.security.KeyStore.PasswordProtection;
import java.time.Duration;
import java.util.*;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import java.util.zip.Deflater;

public class TlsServerTest extends AbstractServerTest {

    private static final String CA_CERTIFICATE_ENTRY = "gbcs-ca";
    private static final String CLIENT_CERTIFICATE_ENTRY = "gbcs-client";
    private static final String SERVER_CERTIFICATE_ENTRY = "gbcs-server";
    private static final String PASSWORD = "password";

    private Path cacheDir;
    private Path serverKeyStoreFile;
    private Path clientKeyStoreFile;
    private Path trustStoreFile;
    private KeyStore serverKeyStore;
    private KeyStore clientKeyStore;
    private KeyStore trustStore;
    private X509Credentials ca;

    private final Configuration.Group readersGroup = new Configuration.Group("readers", Set.of(Role.Reader));
    private final Configuration.Group writersGroup = new Configuration.Group("writers", Set.of(Role.Writer));
    private final Random random = new Random(101325);
    private final Map.Entry<String, byte[]> keyValuePair;

    private final List<Configuration.User> users = Arrays.asList(
            new Configuration.User("user1", null, Set.of(readersGroup)),
            new Configuration.User("user2", null, Set.of(writersGroup)),
            new Configuration.User("user3", null, Set.of(readersGroup, writersGroup))
    );

    public TlsServerTest() {
        this.keyValuePair = newEntry(random);
    }

    private void createKeyStoreAndTrustStore() throws Exception {
        ca = CertificateUtils.createCertificateAuthority(CA_CERTIFICATE_ENTRY, 30);
        var serverCert = CertificateUtils.createServerCertificate(ca, new X500Name("CN=" + SERVER_CERTIFICATE_ENTRY), 30);
        var clientCert = CertificateUtils.createClientCertificate(ca, new X500Name("CN=" + CLIENT_CERTIFICATE_ENTRY), 30);

        serverKeyStore = KeyStore.getInstance("PKCS12");
        serverKeyStore.load(null, null);
        serverKeyStore.setEntry(
                CA_CERTIFICATE_ENTRY,
                new KeyStore.TrustedCertificateEntry(ca.certificate()),
                new PasswordProtection(null)
        );
        serverKeyStore.setEntry(
                SERVER_CERTIFICATE_ENTRY,
                new KeyStore.PrivateKeyEntry(
                        serverCert.keyPair().getPrivate(),
                        new java.security.cert.Certificate[]{serverCert.certificate(), ca.certificate()}
                ),
                new PasswordProtection(PASSWORD.toCharArray())
        );

        try (var out = Files.newOutputStream(this.serverKeyStoreFile)) {
            serverKeyStore.store(out, null);
        }

        clientKeyStore = KeyStore.getInstance("PKCS12");
        clientKeyStore.load(null, null);
        clientKeyStore.setEntry(
                CA_CERTIFICATE_ENTRY,
                new KeyStore.TrustedCertificateEntry(ca.certificate()),
                new PasswordProtection(null)
        );
        clientKeyStore.setEntry(
                CLIENT_CERTIFICATE_ENTRY,
                new KeyStore.PrivateKeyEntry(
                        clientCert.keyPair().getPrivate(),
                        new java.security.cert.Certificate[]{clientCert.certificate(), ca.certificate()}
                ),
                new PasswordProtection(PASSWORD.toCharArray())
        );

        try (var out = Files.newOutputStream(this.clientKeyStoreFile)) {
            clientKeyStore.store(out, null);
        }

        trustStore = KeyStore.getInstance("PKCS12");
        trustStore.load(null, null);
        trustStore.setEntry(
                CA_CERTIFICATE_ENTRY,
                new KeyStore.TrustedCertificateEntry(ca.certificate()),
                new PasswordProtection(null)
        );

        try (var out = Files.newOutputStream(this.trustStoreFile)) {
            trustStore.store(out, null);
        }
    }

    private KeyStore getClientKeyStore(X509Credentials ca, X500Name subject) throws Exception {
        var clientCert = CertificateUtils.createClientCertificate(ca, subject, 30);
        KeyStore keyStore = KeyStore.getInstance("PKCS12");
        keyStore.load(null, null);
        keyStore.setEntry(
                CA_CERTIFICATE_ENTRY,
                new KeyStore.TrustedCertificateEntry(ca.certificate()),
                new PasswordProtection(null)
        );
        keyStore.setEntry(
                CLIENT_CERTIFICATE_ENTRY,
                new KeyStore.PrivateKeyEntry(
                        clientCert.keyPair().getPrivate(),
                        new java.security.cert.Certificate[]{clientCert.certificate(), ca.certificate()}
                ),
                new PasswordProtection(PASSWORD.toCharArray())
        );
        return keyStore;
    }

    private HttpClient getHttpClient(KeyStore clientKeyStore) throws Exception {
        KeyManagerFactory kmf = null;
        if (clientKeyStore != null) {
            kmf = KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm());
            kmf.init(clientKeyStore, PASSWORD.toCharArray());
        }

        TrustManagerFactory tmf = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm());
        tmf.init(trustStore);

        SSLContext sslContext = SSLContext.getInstance("TLS");
        sslContext.init(
                kmf != null ? kmf.getKeyManagers() : null,
                tmf.getTrustManagers(),
                null
        );

        return HttpClient.newBuilder().sslContext(sslContext).build();
    }

    @Override
    @SneakyThrows
    protected void setUp() {
        this.clientKeyStoreFile = testDir.resolve("client-keystore.p12");
        this.serverKeyStoreFile = testDir.resolve("server-keystore.p12");
        this.trustStoreFile = testDir.resolve("truststore.p12");
        this.cacheDir = testDir.resolve("cache");

        createKeyStoreAndTrustStore();

        Map<String, Configuration.User> usersMap = users.stream()
                .collect(Collectors.toMap(user -> user.getName(), user -> user));

        Map<String, Configuration.Group> groupsMap = Stream.of(writersGroup, readersGroup)
                .collect(Collectors.toMap(group -> group.getName(), group -> group));

        cfg = new Configuration(
                "127.0.0.1",
                new ServerSocket(0).getLocalPort() + 1,
                "gbcs",
                usersMap,
                groupsMap,
                new FileSystemCacheConfiguration(
                        this.cacheDir,
                        Duration.ofSeconds(3600 * 24),
                        "MD5",
                        true,
                        Deflater.DEFAULT_COMPRESSION
                ),
                new Configuration.ClientCertificateAuthentication(
                        new Configuration.TlsCertificateExtractor("CN", "(.*)"),
                        null
                ),
                new Configuration.Tls(
                        new Configuration.KeyStore(this.serverKeyStoreFile, null, SERVER_CERTIFICATE_ENTRY, PASSWORD),
                        new Configuration.TrustStore(this.trustStoreFile, null, false),
                        true
                ),
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
                .uri(URI.create(String.format("https://%s:%d/%s", cfg.getHost(), cfg.getPort(), key)));
    }

    private String buildAuthorizationHeader(Configuration.User user, String password) {
        String credentials = user.getName() + ":" + password;
        byte[] encodedCredentials = Base64.getEncoder().encode(credentials.getBytes(StandardCharsets.UTF_8));
        return "Basic " + new String(encodedCredentials, StandardCharsets.UTF_8);
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
    public void putWithNoClientCertificate() throws Exception {
        try (HttpClient client = getHttpClient(null)) {
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
    public void putAsAReaderUser() throws Exception {
        String key = keyValuePair.getKey();
        byte[] value = keyValuePair.getValue();

        Configuration.User user = cfg.getUsers().values().stream()
                .filter(u -> u.getRoles().contains(Role.Reader) && !u.getRoles().contains(Role.Writer))
                .findFirst()
                .orElseThrow(() -> new RuntimeException("Reader user not found"));

        try (HttpClient client = getHttpClient(getClientKeyStore(ca, new X500Name("CN=" + user.getName())))) {
            HttpRequest.Builder requestBuilder = newRequestBuilder(key)
                    .header("Content-Type", "application/octet-stream")
                    .PUT(HttpRequest.BodyPublishers.ofByteArray(value));

            HttpResponse<String> response = client.send(requestBuilder.build(), HttpResponse.BodyHandlers.ofString());
            Assertions.assertEquals(HttpResponseStatus.FORBIDDEN.code(), response.statusCode());
        }
    }

    @Test
    @Order(3)
    public void getAsAWriterUser() throws Exception {
        String key = keyValuePair.getKey();

        Configuration.User user = cfg.getUsers().values().stream()
                .filter(u -> u.getRoles().contains(Role.Writer))
                .findFirst()
                .orElseThrow(() -> new RuntimeException("Writer user not found"));

        try (HttpClient client = getHttpClient(getClientKeyStore(ca, new X500Name("CN=" + user.getName())))) {
            HttpRequest.Builder requestBuilder = newRequestBuilder(key)
                    .header("Authorization", buildAuthorizationHeader(user, PASSWORD))
                    .GET();

            HttpResponse<String> response = client.send(requestBuilder.build(), HttpResponse.BodyHandlers.ofString());
            Assertions.assertEquals(HttpResponseStatus.FORBIDDEN.code(), response.statusCode());
        }
    }

    @Test
    @Order(4)
    public void putAsAWriterUser() throws Exception {
        String key = keyValuePair.getKey();
        byte[] value = keyValuePair.getValue();

        Configuration.User user = cfg.getUsers().values().stream()
                .filter(u -> u.getRoles().contains(Role.Writer))
                .findFirst()
                .orElseThrow(() -> new RuntimeException("Writer user not found"));

        try (HttpClient client = getHttpClient(getClientKeyStore(ca, new X500Name("CN=" + user.getName())))) {
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
    public void getAsAReaderUser() throws Exception {
        String key = keyValuePair.getKey();
        byte[] value = keyValuePair.getValue();

        Configuration.User user = cfg.getUsers().values().stream()
                .filter(u -> u.getRoles().contains(Role.Reader))
                .findFirst()
                .orElseThrow(() -> new RuntimeException("Reader user not found"));

        try (HttpClient client = getHttpClient(getClientKeyStore(ca, new X500Name("CN=" + user.getName())))) {
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
    public void getMissingKeyAsAReaderUser() throws Exception {
        Map.Entry<String, byte[]> entry = newEntry(random);
        String key = entry.getKey();

        Configuration.User user = cfg.getUsers().values().stream()
                .filter(u -> u.getRoles().contains(Role.Reader))
                .findFirst()
                .orElseThrow(() -> new RuntimeException("Reader user not found"));

        try (HttpClient client = getHttpClient(getClientKeyStore(ca, new X500Name("CN=" + user.getName())))) {
            HttpRequest.Builder requestBuilder = newRequestBuilder(key)
                    .header("Authorization", buildAuthorizationHeader(user, PASSWORD))
                    .GET();

            HttpResponse<byte[]> response = client.send(requestBuilder.build(), HttpResponse.BodyHandlers.ofByteArray());
            Assertions.assertEquals(HttpResponseStatus.NOT_FOUND.code(), response.statusCode());
        }
    }
}