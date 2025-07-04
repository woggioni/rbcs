package net.woggioni.rbcs.api;


import lombok.EqualsAndHashCode;
import lombok.NonNull;
import lombok.Value;

import java.nio.file.Path;
import java.security.cert.X509Certificate;
import java.time.Duration;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

@Value
public class Configuration {
    String host;
    int port;
    int incomingConnectionsBacklogSize;
    String serverPath;
    @NonNull
    EventExecutor eventExecutor;
    @NonNull
    RateLimiter rateLimiter;
    @NonNull
    Connection connection;
    Map<String, User> users;
    Map<String, Group> groups;
    Cache cache;
    Authentication authentication;
    Tls tls;

    @Value
    public static class RateLimiter {
        boolean delayRequest;
        int messageBufferSize;
        int maxQueuedMessages;
    }

    @Value
    public static class EventExecutor {
        boolean useVirtualThreads;
    }

    @Value
    public static class Connection {
        Duration idleTimeout;
        Duration readIdleTimeout;
        Duration writeIdleTimeout;
        int maxRequestSize;
        int chunkSize;
    }

    @Value
    public static class Quota {
        long calls;
        Duration period;
        long initialAvailableCalls;
        long maxAvailableCalls;
    }

    @Value
    public static class Group {
        @EqualsAndHashCode.Include
        String name;
        Set<Role> roles;
        Quota groupQuota;
        Quota userQuota;
    }

    @Value
    public static class User {
        @EqualsAndHashCode.Include
        String name;
        String password;
        Set<Group> groups;
        Quota quota;

        public Set<Role> getRoles() {
            return groups.stream()
                    .flatMap(group -> group.getRoles().stream())
                    .collect(Collectors.toSet());
        }
    }

    @FunctionalInterface
    public interface UserExtractor {
        User extract(X509Certificate cert);
    }

    @FunctionalInterface
    public interface GroupExtractor {
        Group extract(X509Certificate cert);
    }

    @Value
    public static class Tls {
        KeyStore keyStore;
        TrustStore trustStore;
    }

    @Value
    public static class KeyStore {
        Path file;
        String password;
        String keyAlias;
        String keyPassword;
    }

    @Value
    public static class TrustStore {
        Path file;
        String password;
        boolean checkCertificateStatus;
        boolean requireClientCertificate;
    }

    @Value
    public static class TlsCertificateExtractor {
        String rdnType;
        String pattern;
    }

    public interface Authentication {}

    public static class BasicAuthentication implements Authentication {}

    @Value
    public static class ClientCertificateAuthentication implements Authentication {
        TlsCertificateExtractor userExtractor;
        TlsCertificateExtractor groupExtractor;
    }

    public interface Cache {
        CacheHandlerFactory materialize();
        String getNamespaceURI();
        String getTypeName();
    }

    public static Configuration of(
            String host,
            int port,
            int incomingConnectionsBacklogSize,
            String serverPath,
            EventExecutor eventExecutor,
            RateLimiter rateLimiter,
            Connection connection,
            Map<String, User> users,
            Map<String, Group> groups,
            Cache cache,
            Authentication authentication,
            Tls tls
    ) {
        return new Configuration(
                host,
                port,
                incomingConnectionsBacklogSize,
                serverPath != null && !serverPath.isEmpty() && !serverPath.equals("/") ? serverPath : null,
                eventExecutor,
                rateLimiter,
                connection,
                users,
                groups,
                cache,
                authentication,
                tls
        );
    }
}