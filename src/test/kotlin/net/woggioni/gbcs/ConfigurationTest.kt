package net.woggioni.gbcs

import java.net.URI
import java.net.URL
import org.junit.jupiter.api.Test

class ConfigurationTest {

    @Test
    fun test() {
        GradleBuildCacheServer.registerUrlProtocolHandler()
        val schemaUrl = this::class.java.getResource("/net/woggioni/gbcs/gbcs.xsd")
        val dbf = Xml.newDocumentBuilderFactory()
        dbf.schema = Xml.getSchema(schemaUrl)
        val db = dbf.newDocumentBuilder().apply {
            setErrorHandler(Xml.ErrorHandler(schemaUrl))
        }
        val configurationUrl = this::class.java.getResource("/net/woggioni/gbcs/gbcs-default.xml")
        val doc = configurationUrl.openStream().use(db::parse)
        Configuration.parse(doc.documentElement)
    }
}