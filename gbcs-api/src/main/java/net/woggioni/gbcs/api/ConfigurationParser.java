package net.woggioni.gbcs.api;

import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.Node;

import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.Duration;
import java.util.*;
import java.util.stream.Collectors;
import java.util.stream.StreamSupport;

public class ConfigurationParser {

    public static Configuration parse(Document document) {
        Element root = document.getDocumentElement();
        Configuration.Cache cache = null;
        String host = "127.0.0.1";
        int port = 11080;
        Map<String, Configuration.User> users = Collections.emptyMap();
        Map<String, Configuration.Group> groups = Collections.emptyMap();
        Configuration.Tls tls = null;
        String serverPath = root.getAttribute("path");
        boolean useVirtualThread = !root.getAttribute("useVirtualThreads").isEmpty() &&
                Boolean.parseBoolean(root.getAttribute("useVirtualThreads"));
        Configuration.Authentication authentication = null;

        for (Node child : iterableOf(root)) {
            switch (child.getNodeName()) {
                case "authorization":
                    for (Node gchild : iterableOf((Element) child)) {
                        switch (gchild.getNodeName()) {
                            case "users":
                                users = parseUsers((Element) gchild);
                                break;
                            case "groups":
                                Map.Entry<Map<String, Configuration.User>, Map<String, Configuration.Group>> pair = parseGroups((Element) gchild, users);
                                users = pair.getKey();
                                groups = pair.getValue();
                                break;
                        }
                    }
                    break;

                case "bind":
                    Element bindEl = (Element) child;
                    host = bindEl.getAttribute("host");
                    port = Integer.parseInt(bindEl.getAttribute("port"));
                    break;

                case "cache":
                    Element cacheEl = (Element) child;
                    cacheEl.getAttributeNode("xs:type").getSchemaTypeInfo();
                    if ("gbcs:fileSystemCacheType".equals(cacheEl.getAttribute("xs:type"))) {
                        String cacheFolder = cacheEl.getAttribute("path");
                        Path cachePath = !cacheFolder.isEmpty()
                                ? Paths.get(cacheFolder)
                                : Paths.get(System.getProperty("user.home")).resolve(".gbcs");

                        String maxAgeStr = cacheEl.getAttribute("max-age");
                        Duration maxAge = !maxAgeStr.isEmpty()
                                ? Duration.parse(maxAgeStr)
                                : Duration.ofDays(1);

//                        cache = new Configuration.FileSystemCache(cachePath, maxAge);
                    }
                    break;

                case "authentication":
                    for (Node gchild : iterableOf((Element) child)) {
                        switch (gchild.getNodeName()) {
                            case "basic":
                                authentication = new Configuration.BasicAuthentication();
                                break;
                            case "client-certificate":
                                Configuration.TlsCertificateExtractor tlsExtractorUser = null;
                                Configuration.TlsCertificateExtractor tlsExtractorGroup = null;

                                for (Node authChild : iterableOf((Element) gchild)) {
                                    Element authEl = (Element) authChild;
                                    switch (authChild.getNodeName()) {
                                        case "group-extractor":
                                            String groupAttrName = authEl.getAttribute("attribute-name");
                                            String groupPattern = authEl.getAttribute("pattern");
                                            tlsExtractorGroup = new Configuration.TlsCertificateExtractor(groupAttrName, groupPattern);
                                            break;
                                        case "user-extractor":
                                            String userAttrName = authEl.getAttribute("attribute-name");
                                            String userPattern = authEl.getAttribute("pattern");
                                            tlsExtractorUser = new Configuration.TlsCertificateExtractor(userAttrName, userPattern);
                                            break;
                                    }
                                }
                                authentication = new Configuration.ClientCertificateAuthentication(tlsExtractorUser, tlsExtractorGroup);
                                break;
                        }
                    }
                    break;

                case "tls":
                    Element tlsEl = (Element) child;
                    boolean verifyClients = !tlsEl.getAttribute("verify-clients").isEmpty() &&
                            Boolean.parseBoolean(tlsEl.getAttribute("verify-clients"));
                    Configuration.KeyStore keyStore = null;
                    Configuration.TrustStore trustStore = null;

                    for (Node gchild : iterableOf(tlsEl)) {
                        Element tlsChild = (Element) gchild;
                        switch (gchild.getNodeName()) {
                            case "keystore":
                                Path keyStoreFile = Paths.get(tlsChild.getAttribute("file"));
                                String keyStorePassword = !tlsChild.getAttribute("password").isEmpty()
                                        ? tlsChild.getAttribute("password")
                                        : null;
                                String keyAlias = tlsChild.getAttribute("key-alias");
                                String keyPassword = !tlsChild.getAttribute("key-password").isEmpty()
                                        ? tlsChild.getAttribute("key-password")
                                        : null;
                                keyStore = new Configuration.KeyStore(keyStoreFile, keyStorePassword, keyAlias, keyPassword);
                                break;

                            case "truststore":
                                Path trustStoreFile = Paths.get(tlsChild.getAttribute("file"));
                                String trustStorePassword = !tlsChild.getAttribute("password").isEmpty()
                                        ? tlsChild.getAttribute("password")
                                        : null;
                                boolean checkCertificateStatus = !tlsChild.getAttribute("check-certificate-status").isEmpty() &&
                                        Boolean.parseBoolean(tlsChild.getAttribute("check-certificate-status"));
                                trustStore = new Configuration.TrustStore(trustStoreFile, trustStorePassword, checkCertificateStatus);
                                break;
                        }
                    }
                    tls = new Configuration.Tls(keyStore, trustStore, verifyClients);
                    break;
            }
        }

        return Configuration.of(host, port, serverPath, users, groups, cache, authentication, tls, useVirtualThread);
    }

    private static Set<Role> parseRoles(Element root) {
        return StreamSupport.stream(iterableOf(root).spliterator(), false)
                .map(node -> switch (node.getNodeName()) {
                    case "reader" -> Role.Reader;
                    case "writer" -> Role.Writer;
                    default -> throw new UnsupportedOperationException("Illegal node '" + node.getNodeName() + "'");
                })
                .collect(Collectors.toSet());
    }

    private static Set<String> parseUserRefs(Element root) {
        return StreamSupport.stream(iterableOf(root).spliterator(), false)
                .filter(node -> "user".equals(node.getNodeName()))
                .map(node -> ((Element) node).getAttribute("ref"))
                .collect(Collectors.toSet());
    }

    private static Map<String, Configuration.User> parseUsers(Element root) {
        return StreamSupport.stream(iterableOf(root).spliterator(), false)
                .filter(node -> "user".equals(node.getNodeName()))
                .map(node -> {
                    Element el = (Element) node;
                    String username = el.getAttribute("name");
                    String password = !el.getAttribute("password").isEmpty() ? el.getAttribute("password") : null;
                    return new AbstractMap.SimpleEntry<>(username, new Configuration.User(username, password, Collections.emptySet()));
                })
                .collect(Collectors.toMap(Map.Entry::getKey, Map.Entry::getValue));
    }

    private static Map.Entry<Map<String, Configuration.User>, Map<String, Configuration.Group>> parseGroups(Element root, Map<String, Configuration.User> knownUsers) {
        Map<String, Set<String>> userGroups = new HashMap<>();
        Map<String, Configuration.Group> groups = StreamSupport.stream(iterableOf(root).spliterator(), false)
                .filter(node -> "group".equals(node.getNodeName()))
                .map(node -> {
                    Element el = (Element) node;
                    String groupName = el.getAttribute("name");
                    Set<Role> roles = Collections.emptySet();

                    for (Node child : iterableOf(el)) {
                        switch (child.getNodeName()) {
                            case "users":
                                parseUserRefs((Element) child).stream()
                                        .map(knownUsers::get)
                                        .filter(Objects::nonNull)
                                        .forEach(user ->
                                                userGroups.computeIfAbsent(user.getName(), k -> new HashSet<>())
                                                        .add(groupName));
                                break;
                            case "roles":
                                roles = parseRoles((Element) child);
                                break;
                        }
                    }
                    return new AbstractMap.SimpleEntry<>(groupName, new Configuration.Group(groupName, roles));
                })
                .collect(Collectors.toMap(Map.Entry::getKey, Map.Entry::getValue));

        Map<String, Configuration.User> users = knownUsers.entrySet().stream()
                .map(entry -> {
                    String name = entry.getKey();
                    Configuration.User user = entry.getValue();
                    Set<Configuration.Group> userGroupSet = userGroups.getOrDefault(name, Collections.emptySet()).stream()
                            .map(groups::get)
                            .filter(Objects::nonNull)
                            .collect(Collectors.toSet());
                    return new AbstractMap.SimpleEntry<>(name, new Configuration.User(name, user.getPassword(), userGroupSet));
                })
                .collect(Collectors.toMap(Map.Entry::getKey, Map.Entry::getValue));

        return new AbstractMap.SimpleEntry<>(users, groups);
    }

    private static Iterable<Node> iterableOf(Element element) {
        return () -> new Iterator<Node>() {
            private Node current = element.getFirstChild();

            @Override
            public boolean hasNext() {
                while (current != null && !(current instanceof Element)) {
                    current = current.getNextSibling();
                }
                return current != null;
            }

            @Override
            public Node next() {
                if (!hasNext()) {
                    throw new NoSuchElementException();
                }
                Node result = current;
                current = current.getNextSibling();
                return result;
            }
        };
    }
}
