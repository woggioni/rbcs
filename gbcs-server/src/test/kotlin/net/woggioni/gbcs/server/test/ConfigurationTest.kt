package net.woggioni.gbcs.server.test

import net.woggioni.gbcs.common.GBCS.toUrl
import net.woggioni.gbcs.common.GbcsUrlStreamHandlerFactory
import net.woggioni.gbcs.common.Xml
import net.woggioni.gbcs.server.configuration.Parser
import net.woggioni.gbcs.server.configuration.Serializer
import org.junit.jupiter.api.Assertions
import org.junit.jupiter.api.io.TempDir
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource
import java.nio.file.Files
import java.nio.file.Path

class ConfigurationTest {

    @ValueSource(
        strings = [
            "classpath:net/woggioni/gbcs/server/test/gbcs-default.xml",
            "classpath:net/woggioni/gbcs/server/test/gbcs-memcached.xml",
            "classpath:net/woggioni/gbcs/server/test/gbcs-tls.xml",
        ]
    )
    @ParameterizedTest
    fun test(configurationUrl: String, @TempDir testDir: Path) {
        GbcsUrlStreamHandlerFactory.install()
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