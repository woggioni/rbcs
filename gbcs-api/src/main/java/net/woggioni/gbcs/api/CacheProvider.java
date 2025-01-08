package net.woggioni.gbcs.api;

import org.w3c.dom.Document;
import org.w3c.dom.Element;

public interface CacheProvider<T extends Configuration.Cache> {

    String getXmlSchemaLocation();

    String getXmlNamespace();

    String getXmlType();

    T deserialize(Element parent);

    Element serialize(Document doc, T cache);
}
