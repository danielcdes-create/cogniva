package br.com.concursoprep.app

import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import br.com.concursoprep.app.ui.components.MarkdownParser
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class MarkdownParserTest {

    @Test
    fun stripsRedundantPrefixes() {
        val input1 = "🔥 **Dr. Sincero:** Esta questão é uma pegadinha clássica."
        val sanitized1 = MarkdownParser.sanitizeRawText(input1)
        assertEquals("Esta questão é uma pegadinha clássica.", sanitized1)

        val input2 = "**Dr. Sincero:** Candidato desatento cai na letra B."
        val sanitized2 = MarkdownParser.sanitizeRawText(input2)
        assertEquals("Candidato desatento cai na letra B.", sanitized2)

        val input3 = "   🔥   **Dr. Sincero:**   Preste atenção no comando."
        val sanitized3 = MarkdownParser.sanitizeRawText(input3)
        assertEquals("Preste atenção no comando.", sanitized3)
    }

    @Test
    fun collapsesMultipleNewlines() {
        val input = "Primeiro parágrafo.\n\n\n\nSegundo parágrafo."
        val sanitized = MarkdownParser.sanitizeRawText(input)
        assertEquals("Primeiro parágrafo.\n\nSegundo parágrafo.", sanitized)
    }

    @Test
    fun parsesBoldItalicAndCode() {
        val input = "Texto com **negrito**, *itálico* e `código` inline."
        val annotated = MarkdownParser.parseSimpleMarkdownToAnnotatedString(input)

        assertEquals("Texto com negrito, itálico e código inline.", annotated.text)

        val boldSpans = annotated.spanStyles.filter { it.item.fontWeight == FontWeight.Bold }
        assertEquals(1, boldSpans.size)
        assertEquals(10, boldSpans[0].start)
        assertEquals(17, boldSpans[0].end)

        val italicSpans = annotated.spanStyles.filter { it.item.fontStyle == FontStyle.Italic }
        assertEquals(1, italicSpans.size)
        assertEquals(19, italicSpans[0].start)
        assertEquals(26, italicSpans[0].end)

        val codeSpans = annotated.spanStyles.filter { it.item.fontFamily == FontFamily.Monospace }
        assertEquals(1, codeSpans.size)
        assertEquals(29, codeSpans[0].start)
        assertEquals(35, codeSpans[0].end)
    }

    @Test
    fun parsesBulletLists() {
        val input = "- Item 1\n* Item 2\nItem normal"
        val annotated = MarkdownParser.parseSimpleMarkdownToAnnotatedString(input)

        val lines = annotated.text.lines()
        assertEquals(3, lines.size)
        assertTrue(lines[0].startsWith("• Item 1"))
        assertTrue(lines[1].startsWith("• Item 2"))
        assertEquals("Item normal", lines[2])
    }
}
