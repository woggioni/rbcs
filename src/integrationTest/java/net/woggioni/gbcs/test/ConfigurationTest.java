package net.woggioni.gbcs.test;

import net.woggioni.gbcs.GradleBuildCacheServer;
import net.woggioni.gbcs.base.GBCS;
import net.woggioni.gbcs.base.Xml;
import net.woggioni.gbcs.configuration.Parser;
import net.woggioni.gbcs.configuration.Serializer;
import net.woggioni.gbcs.url.ClasspathUrlStreamHandlerFactoryProvider;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.io.IOException;
import java.net.URL;
import java.nio.file.Files;
import java.nio.file.Path;

class ConfigurationTest {

    @ParameterizedTest
    @ValueSource(strings = {
            // "classpath:net/woggioni/gbcs/gbcs-default.xml",
            "classpath:net/woggioni/gbcs/test/gbcs-memcached.xml"
    })
    void test(String configurationUrl, @TempDir Path testDir) throws IOException {
        URL.setURLStreamHandlerFactory(new ClasspathUrlStreamHandlerFactoryProvider());
        // DocumentBuilderFactory dbf = Xml.newDocumentBuilderFactory(GradleBuildCacheServer.CONFIGURATION_SCHEMA_URL);
        // DocumentBuilder db = dbf.newDocumentBuilder();
        // URL configurationUrl = GradleBuildCacheServer.DEFAULT_CONFIGURATION_URL;

        var doc = Xml.Companion.parseXml(GBCS.INSTANCE.toUrl(configurationUrl), null, null);
        var cfg = Parser.INSTANCE.parse(doc);
        Path configFile = testDir.resolve("gbcs.xml");

        try (var outputStream = Files.newOutputStream(configFile)) {
            Xml.Companion.write(Serializer.INSTANCE.serialize(cfg), outputStream);
        }

        Xml.Companion.write(Serializer.INSTANCE.serialize(cfg), System.out);

        var parsed = Parser.INSTANCE.parse(Xml.Companion.parseXml(
                configFile.toUri().toURL(), null, null
        ));

        Assertions.assertEquals(cfg, parsed);
    }
}