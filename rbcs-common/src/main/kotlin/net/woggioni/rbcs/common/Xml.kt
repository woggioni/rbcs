package net.woggioni.rbcs.common

import net.woggioni.jwo.JWO
import org.slf4j.LoggerFactory
import org.slf4j.event.Level
import org.w3c.dom.Document
import org.w3c.dom.Element
import org.w3c.dom.Node
import org.w3c.dom.NodeList
import org.xml.sax.SAXNotRecognizedException
import org.xml.sax.SAXNotSupportedException
import org.xml.sax.SAXParseException
import java.io.InputStream
import java.io.OutputStream
import java.net.URL
import javax.xml.XMLConstants.ACCESS_EXTERNAL_DTD
import javax.xml.XMLConstants.ACCESS_EXTERNAL_SCHEMA
import javax.xml.XMLConstants.FEATURE_SECURE_PROCESSING
import javax.xml.XMLConstants.W3C_XML_SCHEMA_NS_URI
import javax.xml.parsers.DocumentBuilder
import javax.xml.parsers.DocumentBuilderFactory
import javax.xml.transform.OutputKeys
import javax.xml.transform.TransformerFactory
import javax.xml.transform.dom.DOMSource
import javax.xml.transform.stream.StreamResult
import javax.xml.transform.stream.StreamSource
import javax.xml.validation.Schema
import javax.xml.validation.SchemaFactory
import org.xml.sax.ErrorHandler as ErrHandler


class NodeListIterator(private val nodeList: NodeList) : Iterator<Node> {
    private var cursor: Int = 0
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

class Xml(val doc: Document, val element: Element) {

    class ErrorHandler(private val fileURL: URL) : ErrHandler {

        companion object {
            private val log = createLogger<ErrorHandler>()
        }

         override fun warning(ex: SAXParseException)= err(ex, Level.WARN)

        private fun err(ex: SAXParseException, level: Level) {
            log.log(level) {
                "Problem at ${fileURL}:${ex.lineNumber}:${ex.columnNumber} parsing deployment configuration: ${ex.message}"
            }
            throw ex
        }

        override fun error(ex: SAXParseException) = err(ex, Level.ERROR)
        override fun fatalError(ex: SAXParseException) = err(ex, Level.ERROR)
    }

    companion object {
        private val dictMap: Map<String, Map<String, Any>> = sequenceOf(
            "env" to System.getenv().asSequence().map { (k, v) -> k to (v as Any) }.toMap(),
            "sys" to System.getProperties().asSequence().map { (k, v) -> k as String to (v as Any) }.toMap()
        ).toMap()

        private fun renderConfigurationTemplate(template: String): String {
            return JWO.renderTemplate(template, emptyMap(), dictMap).replace("$$", "$")
        }

        fun Element.renderAttribute(name : String, namespaceURI: String? = null) = if(namespaceURI == null) {
            getAttribute(name)
        } else {
            getAttributeNS(name, namespaceURI)
        }.takeIf(String::isNotEmpty)?.let(Companion::renderConfigurationTemplate)


        fun Element.asIterable() = Iterable { ElementIterator(this, null) }
        fun NodeList.asIterable() = Iterable { NodeListIterator(this) }

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
            sf.setFeature(FEATURE_SECURE_PROCESSING, false)
            sf.errorHandler = ErrorHandler(schema)
            return sf.newSchema(schema)
        }

        fun getSchema(inputStream: InputStream): Schema {
            val sf = SchemaFactory.newInstance(W3C_XML_SCHEMA_NS_URI)
            sf.setFeature(FEATURE_SECURE_PROCESSING, true)
            return sf.newSchema(StreamSource(inputStream))
        }

        fun newDocumentBuilderFactory(schemaResourceURL: URL?): DocumentBuilderFactory {
            val dbf = DocumentBuilderFactory.newInstance()
            dbf.setFeature(FEATURE_SECURE_PROCESSING, false)
            dbf.setAttribute(ACCESS_EXTERNAL_SCHEMA, "all")
            disableProperty(dbf, ACCESS_EXTERNAL_DTD)
            dbf.isExpandEntityReferences = true
            dbf.isIgnoringComments = true
            dbf.isNamespaceAware = true
            dbf.isValidating = schemaResourceURL == null
            dbf.setFeature("http://apache.org/xml/features/validation/schema", true)
            schemaResourceURL?.let {
                dbf.schema = getSchema(it)
            }
            return dbf
        }

        fun newDocumentBuilder(resource: URL, schemaResourceURL: URL?): DocumentBuilder {
            val db = newDocumentBuilderFactory(schemaResourceURL).newDocumentBuilder()
            db.setErrorHandler(ErrorHandler(resource))
            return db
        }

        fun parseXmlResource(resource: URL, schemaResourceURL: URL?): Document {
            val db = newDocumentBuilder(resource, schemaResourceURL)
            return resource.openStream().use(db::parse)
        }

        fun parseXml(sourceURL: URL, sourceStream: InputStream? = null, schemaResourceURL: URL? = null): Document {
            val db = newDocumentBuilder(sourceURL, schemaResourceURL)
            return sourceStream?.let(db::parse) ?: sourceURL.openStream().use(db::parse)
        }

        fun write(doc: Document, output: OutputStream) {
            val transformerFactory = TransformerFactory.newInstance()
            val transformer = transformerFactory.newTransformer()
            transformer.setOutputProperty(OutputKeys.INDENT, "yes")
            transformer.setOutputProperty(OutputKeys.INDENT, "yes")
            transformer.setOutputProperty("{http://xml.apache.org/xslt}indent-amount", "4")
            transformer.setOutputProperty(OutputKeys.STANDALONE, "yes")
            transformer.setOutputProperty(OutputKeys.ENCODING, "UTF-8")
            val source = DOMSource(doc)
            val result = StreamResult(output)
            transformer.transform(source, result)
        }

        fun of(
            namespaceURI: String,
            qualifiedName: String,
            schemaResourceURL: URL? = null,
            cb: Xml.(el: Element) -> Unit
        ): Document {
            val dbf = newDocumentBuilderFactory(schemaResourceURL)
            val db = dbf.newDocumentBuilder()
            val doc = db.newDocument()
            val root = doc.createElementNS(namespaceURI, qualifiedName)
                .also(doc::appendChild)
            Xml(doc, root).cb(root)
            return doc
        }

        fun of(doc: Document, el: Element, cb: Xml.(el: Element) -> Unit): Element {
            Xml(doc, el).cb(el)
            return el
        }

        fun Element.removeChildren() {
            while (true) {
                removeChild(firstChild ?: break)
            }
        }
    }

    fun node(
        name: String,
        namespaceURI: String? = null,
        attrs: Map<String, String> = emptyMap(),
        cb: Xml.(el: Element) -> Unit = {}
    ): Element {
        val child = doc.createElementNS(namespaceURI, name)
        for ((key, value) in attrs) {
            child.setAttribute(key, value)
        }
        return child
            .also {
                element.appendChild(it)
                Xml(doc, it).cb(it)
            }
    }

    fun attr(key: String, value: String, namespaceURI: String? = null) {
        element.setAttributeNS(namespaceURI, key, value)
    }

    fun text(txt: String) {
        element.appendChild(doc.createTextNode(txt))
    }
}
