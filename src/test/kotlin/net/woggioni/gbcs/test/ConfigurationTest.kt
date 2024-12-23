package net.woggioni.gbcs.test

import net.woggioni.gbcs.configuration.Configuration
import net.woggioni.gbcs.GradleBuildCacheServer
import net.woggioni.gbcs.Xml
import net.woggioni.gbcs.configuration.Serializer
import net.woggioni.gbcs.url.ClasspathUrlStreamHandlerFactoryProvider
import org.junit.jupiter.api.Assertions
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.net.URL
import java.nio.file.Files
import java.nio.file.Path

class ConfigurationTest {

    @Test
    fun test(@TempDir testDir : Path) {
        URL.setURLStreamHandlerFactory(ClasspathUrlStreamHandlerFactoryProvider())
        val dbf = Xml.newDocumentBuilderFactory(GradleBuildCacheServer.CONFIGURATION_SCHEMA_URL)
        val db = dbf.newDocumentBuilder()
        val configurationUrl = GradleBuildCacheServer.DEFAULT_CONFIGURATION_URL
        val doc = configurationUrl.openStream().use(db::parse)
        val cfg = Configuration.parse(doc)
        val configFile = testDir.resolve("gbcs.xml")
        Files.newOutputStream(configFile).use {
            Xml.write(Serializer.serialize(cfg), it)
        }
        val parsed = Configuration.parse(Xml.parseXml(configFile.toUri().toURL()))
        Assertions.assertEquals(cfg, parsed)
    }
}