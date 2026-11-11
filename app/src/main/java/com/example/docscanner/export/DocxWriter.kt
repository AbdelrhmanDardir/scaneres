package com.example.docscanner.export

import java.io.OutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/**
 * Builds a real Microsoft Word (.docx) file with no third-party library.
 * A .docx is just a ZIP of a few XML files (Office Open XML), so plain java.util.zip is enough
 * (and avoids Apache POI's size, multidex and java.awt problems on Android).
 *
 * Text rules: a blank line starts a new paragraph; a single line break inside a block stays a line break.
 */
object DocxWriter {

    fun writeTo(text: String, output: OutputStream) {
        ZipOutputStream(output).use { zip ->
            // [Content_Types].xml must be the first entry of the package.
            zip.addEntry("[Content_Types].xml", CONTENT_TYPES)
            zip.addEntry("_rels/.rels", PACKAGE_RELS)
            zip.addEntry("word/document.xml", documentXml(text))
            zip.addEntry("word/styles.xml", STYLES)
            zip.addEntry("word/_rels/document.xml.rels", DOCUMENT_RELS)
        }
    }

    /** Splits OCR text into paragraphs; each paragraph is a list of lines. */
    internal fun splitParagraphs(text: String): List<List<String>> {
        val paragraphs = ArrayList<List<String>>()
        var current = ArrayList<String>()
        for (raw in text.replace("\r\n", "\n").replace('\r', '\n').split('\n')) {
            val line = raw.trimEnd()
            if (line.isBlank()) {
                if (current.isNotEmpty()) {
                    paragraphs.add(current)
                    current = ArrayList()
                }
            } else {
                current.add(line)
            }
        }
        if (current.isNotEmpty()) paragraphs.add(current)
        return paragraphs
    }

    private fun documentXml(text: String): String {
        val body = StringBuilder()
        val paragraphs = splitParagraphs(text)
        if (paragraphs.isEmpty()) {
            body.append("<w:p/>")
        } else {
            paragraphs.forEach { body.append(paragraphXml(it)) }
        }
        return buildString {
            append("""<?xml version="1.0" encoding="UTF-8" standalone="yes"?>""")
            append("""<w:document xmlns:w="http://schemas.openxmlformats.org/wordprocessingml/2006/main">""")
            append("<w:body>")
            append(body)
            append("""<w:sectPr><w:pgSz w:w="11906" w:h="16838"/>""")
            append("""<w:pgMar w:top="1440" w:right="1440" w:bottom="1440" w:left="1440" w:header="708" w:footer="708" w:gutter="0"/></w:sectPr>""")
            append("</w:body></w:document>")
        }
    }

    private fun paragraphXml(lines: List<String>): String {
        val rtl = lines.any { containsRtl(it) }
        val sb = StringBuilder("<w:p>")
        if (rtl) sb.append("<w:pPr><w:bidi/></w:pPr>")
        sb.append("<w:r>")
        if (rtl) sb.append("<w:rPr><w:rtl/></w:rPr>")
        lines.forEachIndexed { index, line ->
            if (index > 0) sb.append("<w:br/>")
            sb.append("""<w:t xml:space="preserve">""").append(escape(line)).append("</w:t>")
        }
        sb.append("</w:r></w:p>")
        return sb.toString()
    }

    private fun containsRtl(s: String): Boolean = s.any { ch ->
        ch in '֐'..'ࣿ' || ch in 'יִ'..'﷿' || ch in 'ﹰ'..'﻿'
    }

    /** Escapes XML special characters and drops characters that are illegal in XML 1.0. */
    private fun escape(s: String): String {
        val sb = StringBuilder(s.length + 16)
        for (ch in s) {
            when {
                ch == '&' -> sb.append("&amp;")
                ch == '<' -> sb.append("&lt;")
                ch == '>' -> sb.append("&gt;")
                ch == '"' -> sb.append("&quot;")
                ch == '\'' -> sb.append("&apos;")
                ch == '\t' -> sb.append(' ')
                ch < ' ' -> Unit                       // other control characters are not allowed in XML
                ch == '￾' || ch == '￿' -> Unit
                else -> sb.append(ch)
            }
        }
        return sb.toString()
    }

    private fun ZipOutputStream.addEntry(name: String, content: String) {
        putNextEntry(ZipEntry(name))
        write(content.toByteArray(Charsets.UTF_8))
        closeEntry()
    }

    private const val CONTENT_TYPES =
        """<?xml version="1.0" encoding="UTF-8" standalone="yes"?>""" +
            """<Types xmlns="http://schemas.openxmlformats.org/package/2006/content-types">""" +
            """<Default Extension="rels" ContentType="application/vnd.openxmlformats-package.relationships+xml"/>""" +
            """<Default Extension="xml" ContentType="application/xml"/>""" +
            """<Override PartName="/word/document.xml" ContentType="application/vnd.openxmlformats-officedocument.wordprocessingml.document.main+xml"/>""" +
            """<Override PartName="/word/styles.xml" ContentType="application/vnd.openxmlformats-officedocument.wordprocessingml.styles+xml"/>""" +
            """</Types>"""

    private const val PACKAGE_RELS =
        """<?xml version="1.0" encoding="UTF-8" standalone="yes"?>""" +
            """<Relationships xmlns="http://schemas.openxmlformats.org/package/2006/relationships">""" +
            """<Relationship Id="rId1" Type="http://schemas.openxmlformats.org/officeDocument/2006/relationships/officeDocument" Target="word/document.xml"/>""" +
            """</Relationships>"""

    private const val DOCUMENT_RELS =
        """<?xml version="1.0" encoding="UTF-8" standalone="yes"?>""" +
            """<Relationships xmlns="http://schemas.openxmlformats.org/package/2006/relationships">""" +
            """<Relationship Id="rId1" Type="http://schemas.openxmlformats.org/officeDocument/2006/relationships/styles" Target="styles.xml"/>""" +
            """</Relationships>"""

    private const val STYLES =
        """<?xml version="1.0" encoding="UTF-8" standalone="yes"?>""" +
            """<w:styles xmlns:w="http://schemas.openxmlformats.org/wordprocessingml/2006/main">""" +
            """<w:docDefaults>""" +
            """<w:rPrDefault><w:rPr><w:rFonts w:ascii="Calibri" w:hAnsi="Calibri" w:eastAsia="Calibri" w:cs="Arial"/>""" +
            """<w:sz w:val="22"/><w:szCs w:val="22"/><w:lang w:val="en-US" w:eastAsia="en-US" w:bidi="ar-SA"/></w:rPr></w:rPrDefault>""" +
            """<w:pPrDefault><w:pPr><w:spacing w:after="160" w:line="259" w:lineRule="auto"/></w:pPr></w:pPrDefault>""" +
            """</w:docDefaults>""" +
            """<w:style w:type="paragraph" w:default="1" w:styleId="Normal"><w:name w:val="Normal"/><w:qFormat/></w:style>""" +
            """</w:styles>"""
}
