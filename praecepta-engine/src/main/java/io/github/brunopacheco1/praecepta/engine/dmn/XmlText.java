/*
 * SPDX-FileCopyrightText: Bruno Pacheco
 *
 * SPDX-License-Identifier: Apache-2.0
 */
package io.github.brunopacheco1.praecepta.engine.dmn;

/** Writes the pieces of XML text that every element is made of: ids, attributes and escaped text. */
final class XmlText {

    private XmlText() {
    }

    /**
     * A DMN id from a name: characters other than ASCII letters, digits and {@code _} become {@code _},
     * and an id that does not start with a letter gets a {@code d_} prefix.
     */
    static String identifier(String name) {
        var id = name.replaceAll("\\W", "_");
        return id.isEmpty() || !Character.isLetter(id.charAt(0)) ? "d_" + id : id;
    }

    /** The attributes {@code id="..." name="..."}. */
    static String idAndName(String id, String name) {
        return id(id) + attribute("name", name);
    }

    static String id(String id) {
        return attribute("id", id);
    }

    /** An attribute, with a leading space and its value escaped: {@code  name="value"}. */
    static String attribute(String name, String value) {
        return " " + name + "=\"" + escapeText(value).replace("\"", "&quot;") + "\"";
    }

    /** Text with the characters XML reserves in content escaped. */
    static String escapeText(String value) {
        return value.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;");
    }
}
