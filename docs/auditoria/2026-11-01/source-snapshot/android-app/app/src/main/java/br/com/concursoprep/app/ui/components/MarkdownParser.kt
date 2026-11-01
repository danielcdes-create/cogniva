package br.com.concursoprep.app.ui.components

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.withStyle
import java.text.Normalizer

object MarkdownParser {

    private val PREFIX_REGEX = Regex(
        "^\\s*(?:🔥\\s*)?(?:\\*\\*Dr\\.\\s*Sincero:?\\*\\*:?\\s*)",
        RegexOption.IGNORE_CASE
    )
    private val MULTI_NEWLINE_REGEX = Regex("\\n{3,}")

    fun sanitizeRawText(raw: String): String {
        val normalized = Normalizer.normalize(raw, Normalizer.Form.NFC)
        val withoutPrefix = PREFIX_REGEX.replace(normalized.trim(), "")
        return MULTI_NEWLINE_REGEX.replace(withoutPrefix.trim(), "\n\n")
    }

    fun parseSimpleMarkdownToAnnotatedString(
        raw: String,
        codeBackgroundColor: Color = Color.Unspecified
    ): AnnotatedString {
        val sanitized = sanitizeRawText(raw)
        return buildAnnotatedString {
            val lines = sanitized.lines()
            lines.forEachIndexed { lineIdx, line ->
                val trimmedLine = line.trimStart()
                val isBullet = trimmedLine.startsWith("- ") || (trimmedLine.startsWith("* ") && !trimmedLine.startsWith("**"))
                val isHeader = trimmedLine.startsWith("#")

                val processedLine = when {
                    isBullet -> "• " + trimmedLine.substring(2)
                    isHeader -> trimmedLine.dropWhile { it == '#' }.trimStart()
                    else -> line
                }

                if (isHeader) {
                    withStyle(SpanStyle(fontWeight = FontWeight.Bold)) {
                        appendInlineMarkdown(processedLine, codeBackgroundColor)
                    }
                } else {
                    appendInlineMarkdown(processedLine, codeBackgroundColor)
                }

                if (lineIdx < lines.size - 1) {
                    append("\n")
                }
            }
        }
    }

    private fun AnnotatedString.Builder.appendInlineMarkdown(
        text: String,
        codeBg: Color
    ) {
        var i = 0
        val len = text.length

        while (i < len) {
            // Check `code`
            if (text[i] == '`') {
                val nextTick = text.indexOf('`', i + 1)
                if (nextTick != -1) {
                    val codeContent = text.substring(i + 1, nextTick)
                    withStyle(
                        SpanStyle(
                            fontFamily = FontFamily.Monospace,
                            background = codeBg
                        )
                    ) {
                        append(codeContent)
                    }
                    i = nextTick + 1
                    continue
                }
            }

            // Check **bold**
            if (text.startsWith("**", i)) {
                val nextDoubleStar = text.indexOf("**", i + 2)
                if (nextDoubleStar != -1) {
                    val boldContent = text.substring(i + 2, nextDoubleStar)
                    withStyle(SpanStyle(fontWeight = FontWeight.Bold)) {
                        appendInlineMarkdown(boldContent, codeBg)
                    }
                    i = nextDoubleStar + 2
                    continue
                }
            }

            // Check *italic*
            if (text[i] == '*' && (i == 0 || text[i - 1] != '*') && (i + 1 >= len || text[i + 1] != '*')) {
                val nextStar = text.indexOf('*', i + 1)
                if (nextStar != -1 && (nextStar + 1 >= len || text[nextStar + 1] != '*')) {
                    val italicContent = text.substring(i + 1, nextStar)
                    withStyle(SpanStyle(fontStyle = FontStyle.Italic)) {
                        appendInlineMarkdown(italicContent, codeBg)
                    }
                    i = nextStar + 1
                    continue
                }
            }

            append(text[i])
            i++
        }
    }
}
