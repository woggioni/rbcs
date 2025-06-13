package net.woggioni.rbcs.server.test

import java.nio.file.Files
import java.nio.file.Path
import net.woggioni.rbcs.common.RBCS.toUrl
import net.woggioni.rbcs.common.RbcsUrlStreamHandlerFactory
import net.woggioni.rbcs.common.Xml
import net.woggioni.rbcs.server.configuration.Parser
import net.woggioni.rbcs.server.configuration.Serializer
import org.junit.jupiter.api.Assertions
import org.junit.jupiter.api.io.TempDir
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource
import org.xml.sax.SAXParseException

class ConfigurationTest {

    @ValueSource(
        strings = [
            "classpath:net/woggioni/rbcs/server/test/valid/rbcs-default.xml",
            "classpath:net/woggioni/rbcs/server/test/valid/rbcs-memcached.xml",
            "classpath:net/woggioni/rbcs/server/test/valid/rbcs-tls.xml",
            "classpath:net/woggioni/rbcs/server/test/valid/rbcs-memcached-tls.xml",
        ]
    )
    @ParameterizedTest
    fun test(configurationUrl: String, @TempDir testDir: Path) {
        RbcsUrlStreamHandlerFactory.install()
        val doc = Xml.parseXml(configurationUrl.toUrl())
        val cfg = Parser.parse(doc)
        val configFile = testDir.resolve("rbcs.xml")
        Files.newOutputStream(configFile).use {
            Xml.write(Serializer.serialize(cfg), it)
        }
        Xml.write(Serializer.serialize(cfg), System.out)

        val parsed = Parser.parse(Xml.parseXml(configFile.toUri().toURL()))
        Assertions.assertEquals(cfg, parsed)
    }

    @ValueSource(
        strings = [
            "classpath:net/woggioni/rbcs/server/test/invalid/invalid-user-ref.xml",
            "classpath:net/woggioni/rbcs/server/test/invalid/duplicate-anonymous-user.xml",
            "classpath:net/woggioni/rbcs/server/test/invalid/duplicate-anonymous-user2.xml",
            "classpath:net/woggioni/rbcs/server/test/invalid/multiple-user-quota.xml",
        ]
    )
    @ParameterizedTest
    fun invalidConfigurationTest(configurationUrl: String) {
        RbcsUrlStreamHandlerFactory.install()
        Assertions.assertThrows(SAXParseException::class.java) {
            Xml.parseXml(configurationUrl.toUrl())
        }
    }
}