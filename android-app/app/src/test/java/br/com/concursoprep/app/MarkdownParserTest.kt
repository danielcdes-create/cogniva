package br.com.concursoprep.app

import androidx.compose.ui.text.LinkAnnotation
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import br.com.concursoprep.app.ui.components.MarkdownParser
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
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
    fun parsesBulletAndNumberedLists() {
        val input = "- Item 1\n* Item 2\n1) Passo um\n2. Passo dois\nItem normal"
        val annotated = MarkdownParser.parseSimpleMarkdownToAnnotatedString(input)

        val lines = annotated.text.lines()
        assertEquals(5, lines.size)
        assertTrue(lines[0].startsWith("• Item 1"))
        assertTrue(lines[1].startsWith("• Item 2"))
        assertEquals("1. Passo um", lines[2])
        assertEquals("2. Passo dois", lines[3])
        assertEquals("Item normal", lines[4])
    }

    @Test
    fun parsesHeadingsBlockquotesAndMultilineFencedCodeBlocks() {
        val input = """
            ## Título da Regra
            > Súmula vinculante com **destaque**
            ```sql
            SELECT * FROM questoes
            WHERE ativa = **1**;
            ```
            Fim.
        """.trimIndent()

        val annotated = MarkdownParser.parseSimpleMarkdownToAnnotatedString(input)
        val lines = annotated.text.lines()
        assertEquals(
            listOf(
                "Título da Regra",
                "▎ Súmula vinculante com destaque",
                "SELECT * FROM questoes",
                "WHERE ativa = **1**;",
                "Fim."
            ),
            lines
        )

        val codeSpans = annotated.spanStyles.filter { it.item.fontFamily == FontFamily.Monospace }
        assertEquals(2, codeSpans.size)
        assertTrue(annotated.text.contains("WHERE ativa = **1**;"))
    }

    @Test
    fun allowsOnlyHttpsLinksAndStripsUnsafeSchemes() {
        val input = "Confira [Planalto](https://www.planalto.gov.br/ccivil_03/constituicao/constituicao.htm), " +
            "[HTTP inseguro](http://example.com), [XSS](javascript:alert(1)), " +
            "[Arquivo](file:///data/data/secret) e [Intent](intent://scan/#Intent;end)."
        val annotated = MarkdownParser.parseSimpleMarkdownToAnnotatedString(input)

        assertEquals(
            "Confira Planalto, HTTP inseguro, XSS, Arquivo e Intent.",
            annotated.text
        )

        val links = annotated.getLinkAnnotations(0, annotated.text.length)
        assertEquals(1, links.size)
        val urlAnnotation = links.single().item as LinkAnnotation.Url
        assertEquals(
            "https://www.planalto.gov.br/ccivil_03/constituicao/constituicao.htm",
            urlAnnotation.url
        )

        assertTrue(MarkdownParser.isSafeHttpsUrl("https://cogniva.com.br/edital"))
        assertFalse(MarkdownParser.isSafeHttpsUrl("http://cogniva.com.br/edital"))
        assertFalse(MarkdownParser.isSafeHttpsUrl("javascript:alert(1)"))
        assertFalse(MarkdownParser.isSafeHttpsUrl("file:///etc/passwd"))
        assertFalse(MarkdownParser.isSafeHttpsUrl("intent://evil"))
    }

    @Test
    fun allowsUtf8WikipediaHttpsLinksAndRejectsUserInfoUrls() {
        val wikiUrl = "https://pt.wikipedia.org/wiki/Constituição_de_1988"
        val wikiUrl2 = "https://pt.wikipedia.org/wiki/Administração_pública"
        assertTrue(MarkdownParser.isSafeHttpsUrl(wikiUrl))
        assertTrue(MarkdownParser.isSafeHttpsUrl(wikiUrl2))
        assertFalse(MarkdownParser.isSafeHttpsUrl("https://user@evil.com"))
        assertFalse(MarkdownParser.isSafeHttpsUrl("https://user:secret@evil.com/phish"))

        val md = "Leia [Constituição]($wikiUrl) mas ignore [Phishing](https://user@evil.com/login)."
        val annotated = MarkdownParser.parseSimpleMarkdownToAnnotatedString(md)
        assertEquals("Leia Constituição mas ignore Phishing.", annotated.text)

        val links = annotated.getLinkAnnotations(0, annotated.text.length)
        assertEquals(1, links.size)
        val link = links.single().item as LinkAnnotation.Url
        assertEquals(wikiUrl, link.url)
    }

    @Test
    fun treatsFourOrMoreBacktickFencesAsMultilineCodeBlocksInsteadOfInlineCode() {
        val bareFourFence = """
            Introdução
            ````
            val x = 1 + 2
            val y = **literal**
            ````
            Conclusão
        """.trimIndent()
        val parsedBare = MarkdownParser.parseSimpleMarkdownToAnnotatedString(bareFourFence)
        assertEquals(
            listOf(
                "Introdução",
                "val x = 1 + 2",
                "val y = **literal**",
                "Conclusão"
            ),
            parsedBare.text.lines()
        )
        val bareCodeSpans = parsedBare.spanStyles.filter { it.item.fontFamily == FontFamily.Monospace }
        assertEquals(2, bareCodeSpans.size)

        // Nested 3-backtick fence inside a 4-backtick fence stays literal until closing 4-backtick fence
        val nestedFence = """
            ````markdown
            ```sql
            SELECT 1;
            ```
            ````
            Fora do bloco.
        """.trimIndent()
        val parsedNested = MarkdownParser.parseSimpleMarkdownToAnnotatedString(nestedFence)
        assertEquals(
            listOf(
                "```sql",
                "SELECT 1;",
                "```",
                "Fora do bloco."
            ),
            parsedNested.text.lines()
        )
        val nestedCodeSpans = parsedNested.spanStyles.filter { it.item.fontFamily == FontFamily.Monospace }
        assertEquals(3, nestedCodeSpans.size)
    }
}
