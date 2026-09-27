package com.pablo.apksigner.util;

import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.NodeList;

import javax.xml.parsers.DocumentBuilder;
import javax.xml.parsers.DocumentBuilderFactory;
import javax.xml.transform.OutputKeys;
import javax.xml.transform.Transformer;
import javax.xml.transform.TransformerFactory;
import javax.xml.transform.dom.DOMSource;
import javax.xml.transform.stream.StreamResult;
import java.io.File;
import java.util.Objects;

/**
 * Manages loading and saving custom keystore configuration in XML format.
 */
public final class KeystoreConfigManager {

    private KeystoreConfigManager() {
    }

    public static class Config {
        public final String path;
        public final String alias;
        public final String keystorePass;
        public final String keyPass;

        public Config(String path, String alias, String keystorePass, String keyPass) {
            this.path = path != null ? path : "";
            this.alias = alias != null ? alias : "";
            this.keystorePass = keystorePass != null ? keystorePass : "";
            this.keyPass = keyPass != null ? keyPass : "";
        }

        @Override
        public boolean equals(Object o) {
            if (this == o) return true;
            if (o == null || getClass() != o.getClass()) return false;
            Config config = (Config) o;
            return Objects.equals(path, config.path) &&
                    Objects.equals(alias, config.alias) &&
                    Objects.equals(keystorePass, config.keystorePass) &&
                    Objects.equals(keyPass, config.keyPass);
        }

        @Override
        public int hashCode() {
            return Objects.hash(path, alias, keystorePass, keyPass);
        }
    }

    public static Config load(File xmlFile) {
        if (xmlFile == null || !xmlFile.exists() || !xmlFile.isFile()) {
            return null;
        }

        try {
            DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
            DocumentBuilder builder = factory.newDocumentBuilder();
            Document doc = builder.parse(xmlFile);
            doc.getDocumentElement().normalize();

            String path = getTagValue(doc, "path");
            String alias = getTagValue(doc, "alias");
            String ksPass = getTagValue(doc, "keystorePassword");
            String keyPass = getTagValue(doc, "keyPassword");

            return new Config(path, alias, ksPass, keyPass);
        } catch (Exception e) {
            System.err.println("Warning: Failed to parse keystore config: " + e.getMessage());
            return null;
        }
    }

    public static void save(File xmlFile, Config config) {
        if (xmlFile == null || config == null) {
            return;
        }

        try {
            DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
            DocumentBuilder builder = factory.newDocumentBuilder();
            Document doc = builder.newDocument();

            Element rootElement = doc.createElement("keystore-config");
            doc.appendChild(rootElement);

            appendElement(doc, rootElement, "path", config.path);
            appendElement(doc, rootElement, "alias", config.alias);
            appendElement(doc, rootElement, "keystorePassword", config.keystorePass);
            appendElement(doc, rootElement, "keyPassword", config.keyPass);

            TransformerFactory transformerFactory = TransformerFactory.newInstance();
            Transformer transformer = transformerFactory.newTransformer();
            transformer.setOutputProperty(OutputKeys.INDENT, "yes");
            transformer.setOutputProperty("{http://xml.apache.org/xslt}indent-amount", "4");
            transformer.setOutputProperty(OutputKeys.ENCODING, "UTF-8");

            DOMSource source = new DOMSource(doc);
            StreamResult result = new StreamResult(xmlFile);
            transformer.transform(source, result);
        } catch (Exception e) {
            System.err.println("Warning: Failed to save keystore config: " + e.getMessage());
        }
    }

    private static String getTagValue(Document doc, String tagName) {
        NodeList list = doc.getElementsByTagName(tagName);
        if (list.getLength() > 0 && list.item(0).getFirstChild() != null) {
            return list.item(0).getTextContent();
        }
        return "";
    }

    private static void appendElement(Document doc, Element parent, String tagName, String textContent) {
        Element element = doc.createElement(tagName);
        element.setTextContent(textContent != null ? textContent : "");
        parent.appendChild(element);
    }
}