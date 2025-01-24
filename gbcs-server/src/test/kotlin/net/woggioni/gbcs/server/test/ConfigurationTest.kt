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
import org.xml.sax.SAXParseException
import java.nio.file.Files
import java.nio.file.Path

class ConfigurationTest {

    @ValueSource(
        strings = [
            "classpath:net/woggioni/gbcs/server/test/valid/gbcs-default.xml",
            "classpath:net/woggioni/gbcs/server/test/valid/gbcs-memcached.xml",
            "classpath:net/woggioni/gbcs/server/test/valid/gbcs-tls.xml",
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

    @ValueSource(
        strings = [
            "classpath:net/woggioni/gbcs/server/test/invalid/invalid-user-ref.xml",
            "classpath:net/woggioni/gbcs/server/test/invalid/duplicate-anonymous-user.xml",
            "classpath:net/woggioni/gbcs/server/test/invalid/duplicate-anonymous-user2.xml",
            "classpath:net/woggioni/gbcs/server/test/invalid/multiple-user-quota.xml",
        ]
    )
    @ParameterizedTest
    fun invalidConfigurationTest(configurationUrl: String) {
        GbcsUrlStreamHandlerFactory.install()
        Assertions.assertThrows(SAXParseException::class.java) {
            Xml.parseXml(configurationUrl.toUrl())
        }
    }
}