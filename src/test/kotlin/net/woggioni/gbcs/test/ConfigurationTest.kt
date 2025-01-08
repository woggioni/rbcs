package net.woggioni.gbcs.test

import net.woggioni.gbcs.base.GBCS.toUrl
import net.woggioni.gbcs.base.Xml
import net.woggioni.gbcs.configuration.Parser
import net.woggioni.gbcs.configuration.Serializer
import net.woggioni.gbcs.url.ClasspathUrlStreamHandlerFactoryProvider
import org.junit.jupiter.api.Assertions
import org.junit.jupiter.api.io.TempDir
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource
import java.nio.file.Files
import java.nio.file.Path

class ConfigurationTest {

    @ValueSource(
        strings = [
            "classpath:net/woggioni/gbcs/test/gbcs-default.xml",
            "classpath:net/woggioni/gbcs/test/gbcs-memcached.xml",
        ]
    )
    @ParameterizedTest
    fun test(configurationUrl: String, @TempDir testDir: Path) {
        ClasspathUrlStreamHandlerFactoryProvider.install()
        val doc = Xml.parseXml(configurationUrl.toUrl())
        val cfg = Parser.parse(doc)
        val configFile = testDir.resolve("gbcs.xml")
        Files.newOutputStream(configFile).use {
            Xml.write(Serializer.serialize(cfg), it)
        }
        Xml.write(Serializer.serialize(cfg), System.out)

        val parsed = Parser.parse(Xml.parseXml(configFile.toUri().toURL()))
        Assertions.assertEquals(cfg, parsed)
    }
}