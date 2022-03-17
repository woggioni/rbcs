package net.woggioni.gbcs

import org.slf4j.LoggerFactory
import org.w3c.dom.Document
import org.xml.sax.ErrorHandler
import org.xml.sax.SAXNotRecognizedException
import org.xml.sax.SAXNotSupportedException
import org.xml.sax.SAXParseException
import java.net.URL
import javax.xml.XMLConstants.ACCESS_EXTERNAL_DTD
import javax.xml.XMLConstants.ACCESS_EXTERNAL_SCHEMA
import javax.xml.XMLConstants.FEATURE_SECURE_PROCESSING
import javax.xml.XMLConstants.W3C_XML_SCHEMA_NS_URI
import javax.xml.parsers.DocumentBuilder
import javax.xml.parsers.DocumentBuilderFactory
import javax.xml.validation.Schema
import javax.xml.validation.SchemaFactory


object Xml {

    private class XmlErrorHandler(private val fileURL: URL) : ErrorHandler {

        companion object {
            private val log = LoggerFactory.getLogger(XmlErrorHandler::class.java)
        }

        override fun warning(ex: SAXParseException) {
            log.warn(
                    "Problem at {}:{}:{} parsing deployment configuration: {}",
                    fileURL, ex.lineNumber, ex.columnNumber, ex.message
            )
        }

        override fun error(ex: SAXParseException) {
            log.error(
                    "Problem at {}:{}:{} parsing deployment configuration: {}",
                    fileURL, ex.lineNumber, ex.columnNumber, ex.message
            )
            throw ex
        }

        override fun fatalError(ex: SAXParseException) {
            log.error(
                    "Problem at {}:{}:{} parsing deployment configuration: {}",
                    fileURL, ex.lineNumber, ex.columnNumber, ex.message
            )
            throw ex
        }
    }

    private fun disableProperty(dbf: DocumentBuilderFactory, propertyName: String) {
        try {
            dbf.setAttribute(propertyName, "")
        } catch (iae: IllegalArgumentException) {
            // Property not supported.
        }
    }

    private fun disableProperty(sf: SchemaFactory, propertyName: String) {
        try {
            sf.setProperty(propertyName, "")
        } catch (ex: SAXNotRecognizedException) {
            // Property not supported.
        } catch (ex: SAXNotSupportedException) {
        }
    }

    private fun getSchema(schemaResourceURL: String): Schema {
        val sf = SchemaFactory.newInstance(W3C_XML_SCHEMA_NS_URI)
        sf.setFeature(FEATURE_SECURE_PROCESSING, true)
        disableProperty(sf, ACCESS_EXTERNAL_SCHEMA)
        disableProperty(sf, ACCESS_EXTERNAL_DTD)
        val schemaUrl: URL = Xml::class.java.classLoader.getResource(schemaResourceURL)
                ?: throw IllegalStateException(String.format("Missing configuration schema '%s'", schemaResourceURL))
        return sf.newSchema(schemaUrl)
    }

    private fun newDocumentBuilderFactory(schemaResourceURL: String?): DocumentBuilderFactory {
        val dbf = DocumentBuilderFactory.newInstance()
        dbf.setFeature(FEATURE_SECURE_PROCESSING, true)
        disableProperty(dbf, ACCESS_EXTERNAL_SCHEMA)
        disableProperty(dbf, ACCESS_EXTERNAL_DTD)
        dbf.isExpandEntityReferences = false
        dbf.isIgnoringComments = true
        dbf.isNamespaceAware = true
        val sf = SchemaFactory.newInstance(W3C_XML_SCHEMA_NS_URI)
        sf.setFeature(FEATURE_SECURE_PROCESSING, true)
        disableProperty(sf, ACCESS_EXTERNAL_SCHEMA)
        disableProperty(sf, ACCESS_EXTERNAL_DTD)
        if (schemaResourceURL != null) {
            dbf.schema = getSchema(schemaResourceURL)
        }
        return dbf
    }

    fun newDocumentBuilder(resource: URL, schemaResourceURL: String?): DocumentBuilder {
        val db = newDocumentBuilderFactory(schemaResourceURL).newDocumentBuilder()
        db.setErrorHandler(XmlErrorHandler(resource))
        return db
    }

    fun parseXmlResource(resource: URL, schemaResourceURL: String?): Document {
        val db = newDocumentBuilder(resource, schemaResourceURL)
        return resource.openStream().use(db::parse)
    }

    fun newDocumentBuilder(resource: URL): DocumentBuilder {
        val db = newDocumentBuilderFactory(null).newDocumentBuilder()
        db.setErrorHandler(XmlErrorHandler(resource))
        return db
    }

    fun parseXmlResource(resource: URL): Document {
        val db = newDocumentBuilder(resource, null)
        return resource.openStream().use(db::parse)
    }
}
