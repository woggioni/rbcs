package net.woggioni.gbcs.api;


import lombok.Value;
import org.w3c.dom.Document;
import org.w3c.dom.Element;

import java.nio.file.Path;
import java.security.cert.X509Certificate;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

@Value
public class Configuration {
    String host;
    int port;
    String serverPath;
    Map<String, User> users;
    Map<String, Group> groups;
    Cache cache;
    Authentication authentication;
    Tls tls;
    boolean useVirtualThread;

    @Value
    public static class Group {
        String name;
        Set<Role> roles;

        @Override
        public int hashCode() {
            return name.hashCode();
        }
    }

    @Value
    public static class User {
        String name;
        String password;
        Set<Group> groups;

        @Override
        public int hashCode() {
            return name.hashCode();
        }

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
        boolean verifyClients;
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
        net.woggioni.gbcs.api.Cache materialize();
        String getNamespaceURI();
        String getTypeName();
    }

//    @Value
//    public static class FileSystemCache implements Cache {
//        Path root;
//        Duration maxAge;
//    }

    public static Configuration of(
            String host,
            int port,
            String serverPath,
            Map<String, User> users,
            Map<String, Group> groups,
            Cache cache,
            Authentication authentication,
            Tls tls,
            boolean useVirtualThread
    ) {
        return new Configuration(
                host,
                port,
                serverPath != null && !serverPath.isEmpty() && !serverPath.equals("/") ? serverPath : null,
                users,
                groups,
                cache,
                authentication,
                tls,
                useVirtualThread
        );
    }
}