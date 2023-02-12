package net.woggioni.gbcs

import java.net.URL
import javax.xml.XMLConstants.ACCESS_EXTERNAL_DTD
import javax.xml.XMLConstants.ACCESS_EXTERNAL_SCHEMA
import javax.xml.XMLConstants.FEATURE_SECURE_PROCESSING
import javax.xml.XMLConstants.W3C_XML_SCHEMA_NS_URI
import javax.xml.parsers.DocumentBuilder
import javax.xml.parsers.DocumentBuilderFactory
import javax.xml.validation.Schema
import javax.xml.validation.SchemaFactory
import org.slf4j.LoggerFactory
import org.w3c.dom.Document
import org.w3c.dom.Element
import org.w3c.dom.Node
import org.w3c.dom.NodeList
import org.xml.sax.ErrorHandler
import org.xml.sax.SAXNotRecognizedException
import org.xml.sax.SAXNotSupportedException
import org.xml.sax.SAXParseException

class NodeListIterator(private val nodeList: NodeList) : Iterator<Node> {
    private var cursor : Int = 0
    override fun hasNext(): Boolean {
        return cursor < nodeList.length
    }

    override fun next(): Node {
        return if (hasNext()) nodeList.item(cursor++) else throw NoSuchElementException()
    }
}

class ElementIterator(parent: Element, name: String? = null) : Iterator<Element> {
    private val it: NodeListIterator
    private val name: String?
    private var next: Element?

    init {
        it = NodeListIterator(parent.childNodes)
        this.name = name
        next = getNext()
    }

    override fun hasNext(): Boolean {
        return next != null
    }

    override fun next(): Element {
        val result = next ?: throw NoSuchElementException()
        next = getNext()
        return result
    }

    private fun getNext(): Element? {
        var result: Element? = null
        while (it.hasNext()) {
            val node: Node = it.next()
            if (node is Element && (name == null || name == node.tagName)) {
                result = node
                break
            }
        }
        return result
    }
}

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

    fun getSchema(schema: URL): Schema {
        val sf = SchemaFactory.newInstance(W3C_XML_SCHEMA_NS_URI)
        sf.setFeature(FEATURE_SECURE_PROCESSING, true)
//        disableProperty(sf, ACCESS_EXTERNAL_SCHEMA)
//        disableProperty(sf, ACCESS_EXTERNAL_DTD)
        return sf.newSchema(schema)
    }

    fun newDocumentBuilderFactory(): DocumentBuilderFactory {
        val dbf = DocumentBuilderFactory.newInstance()
        dbf.setFeature(FEATURE_SECURE_PROCESSING, true)
        disableProperty(dbf, ACCESS_EXTERNAL_SCHEMA)
        disableProperty(dbf, ACCESS_EXTERNAL_DTD)
        dbf.isExpandEntityReferences = false
        dbf.isIgnoringComments = true
        dbf.isNamespaceAware = true
        return dbf
    }

//    fun newDocumentBuilder(resource: URL, schemaResourceURL: String?): DocumentBuilder {
//        val db = newDocumentBuilderFactory(schemaResourceURL).newDocumentBuilder()
//        db.setErrorHandler(XmlErrorHandler(resource))
//        return db
//    }

//    fun parseXmlResource(resource: URL, schemaResourceURL: String?): Document {
//        val db = newDocumentBuilder(resource, schemaResourceURL)
//        return resource.openStream().use(db::parse)
//    }
//
//    fun newDocumentBuilder(resource: URL): DocumentBuilder {
//        val db = newDocumentBuilderFactory(null).newDocumentBuilder()
//        db.setErrorHandler(XmlErrorHandler(resource))
//        return db
//    }

//    fun parseXmlResource(resource: URL): Document {
//        val db = newDocumentBuilder(resource, null)
//        return resource.openStream().use(db::parse)
//    }

    fun Element.asIterable() = Iterable { ElementIterator(this, null) }
    fun NodeList.asIterable() = Iterable { NodeListIterator(this) }
}
