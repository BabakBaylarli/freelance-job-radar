package com.babakbaylarli.jobradar.source;

import java.io.StringReader;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import javax.xml.XMLConstants;
import javax.xml.parsers.DocumentBuilderFactory;
import org.w3c.dom.Element;
import org.w3c.dom.Node;
import org.w3c.dom.NodeList;
import org.xml.sax.InputSource;

/** Minimal, XXE-safe RSS reader: every {@code <item>} becomes a map of child tag name to its (trimmed) text. */
final class RssFeed {

    private RssFeed() {}

    static List<Map<String, String>> items(String xml, String sourceName) {
        NodeList nodes;
        try {
            DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
            factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
            factory.setFeature(XMLConstants.FEATURE_SECURE_PROCESSING, true);
            factory.setXIncludeAware(false);
            factory.setExpandEntityReferences(false);
            nodes = factory.newDocumentBuilder().parse(new InputSource(new StringReader(xml))).getElementsByTagName("item");
        } catch (Exception e) {
            throw new SourceException(sourceName + " returned invalid RSS", e);
        }
        List<Map<String, String>> items = new ArrayList<>();
        for (int i = 0; i < nodes.getLength(); i++) {
            Map<String, String> item = new LinkedHashMap<>();
            for (Node child = nodes.item(i).getFirstChild(); child != null; child = child.getNextSibling()) {
                if (child instanceof Element el) {
                    item.putIfAbsent(el.getTagName(), el.getTextContent().strip());
                }
            }
            items.add(item);
        }
        return items;
    }
}
