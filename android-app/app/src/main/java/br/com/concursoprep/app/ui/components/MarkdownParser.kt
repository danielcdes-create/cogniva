package br.com.concursoprep.app.ui.components

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.LinkAnnotation
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextLinkStyles
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.withLink
import androidx.compose.ui.text.withStyle
import java.net.URI
import java.text.Normalizer

object MarkdownParser {

    private val PREFIX_REGEX = Regex(
        "^\\s*(?:🔥\\s*)?(?:\\*\\*Dr\\.\\s*Sincero:?\\*\\*:?\\s*)",
        RegexOption.IGNORE_CASE
    )
    private val MULTI_NEWLINE_REGEX = Regex("\\n{3,}")
    private val UNSAFE_URL_CHARS = setOf('<', '>', '"', '`', '\\', '{', '}', '|', '^')
    private val NUMBERED_LIST_REGEX = Regex("^(\\d+)[.)]\\s+(.*)$")

    fun sanitizeRawText(raw: String): String {
        val normalized = Normalizer.normalize(raw, Normalizer.Form.NFC)
        val withoutPrefix = PREFIX_REGEX.replace(normalized.trim(), "")
        return MULTI_NEWLINE_REGEX.replace(withoutPrefix.trim(), "\n\n")
    }

    fun isSafeHttpsUrl(rawUrl: String): Boolean {
        val trimmed = Normalizer.normalize(rawUrl.trim(), Normalizer.Form.NFC)
        if (!trimmed.startsWith("https://", ignoreCase = true)) return false
        if (trimmed.any { it.isWhitespace() || it.isISOControl() || it in UNSAFE_URL_CHARS }) return false
        val uri = runCatching { URI(trimmed) }.getOrNull() ?: return false
        return uri.scheme.equals("https", ignoreCase = true) &&
            !uri.host.isNullOrBlank() &&
            uri.userInfo == null &&
            uri.rawUserInfo == null &&
            uri.rawAuthority?.contains('@') != true
    }

    fun parse(
        raw: String,
        codeBackgroundColor: Color = Color.Unspecified,
        linkColor: Color = Color.Unspecified
    ): AnnotatedString = parseSimpleMarkdownToAnnotatedString(raw, codeBackgroundColor, linkColor)

    fun parseSimpleMarkdownToAnnotatedString(
        raw: String,
        codeBackgroundColor: Color = Color.Unspecified,
        linkColor: Color = Color.Unspecified
    ): AnnotatedString {
        val sanitized = sanitizeRawText(raw)
        return buildAnnotatedString {
            val lines = sanitized.lines()
            var activeFenceTicks = 0
            var emittedAnyLine = false

            for (line in lines) {
                val trimmedLine = line.trimStart()
                val leadingTicks = trimmedLine.takeWhile { it == '`' }.length

                if (activeFenceTicks > 0) {
                    if (leadingTicks >= activeFenceTicks && trimmedLine.substring(leadingTicks).isBlank()) {
                        activeFenceTicks = 0
                        continue
                    }
                    if (emittedAnyLine) {
                        append("\n")
                    }
                    emittedAnyLine = true
                    withStyle(
                        SpanStyle(
                            fontFamily = FontFamily.Monospace,
                            background = codeBackgroundColor
                        )
                    ) {
                        append(line)
                    }
                    continue
                }

                if (leadingTicks >= 3) {
                    val trimmedEnd = trimmedLine.trimEnd()
                    val afterLeading = trimmedEnd.substring(leadingTicks)
                    val trailingTicks = afterLeading.takeLastWhile { it == '`' }.length
                    val hasSingleLineClosedBody = afterLeading.length > trailingTicks &&
                        trailingTicks == leadingTicks
                    if (hasSingleLineClosedBody) {
                        val inlineFenceBody = afterLeading.substring(0, afterLeading.length - trailingTicks).trim()
                        if (inlineFenceBody.isNotEmpty()) {
                            if (emittedAnyLine) append("\n")
                            emittedAnyLine = true
                            withStyle(
                                SpanStyle(
                                    fontFamily = FontFamily.Monospace,
                                    background = codeBackgroundColor
                                )
                            ) {
                                append(inlineFenceBody)
                            }
                            continue
                        }
                    }
                    // Multi-line fenced code block opener: ```, ````, ```lang, ````markdown, etc.
                    if (!afterLeading.contains('`')) {
                        activeFenceTicks = leadingTicks
                        continue
                    }
                }

                if (emittedAnyLine) {
                    append("\n")
                }
                emittedAnyLine = true

                val isHeader = trimmedLine.startsWith("#")
                val isBlockquote = trimmedLine.startsWith(">")
                val isBullet = trimmedLine.startsWith("- ") || (trimmedLine.startsWith("* ") && !trimmedLine.startsWith("**"))
                val numberedMatch = NUMBERED_LIST_REGEX.matchEntire(trimmedLine)

                when {
                    isHeader -> {
                        val headerBody = trimmedLine.dropWhile { it == '#' }.trimStart()
                        withStyle(SpanStyle(fontWeight = FontWeight.Bold)) {
                            appendInlineMarkdown(headerBody, codeBackgroundColor, linkColor)
                        }
                    }
                    isBlockquote -> {
                        val quoteBody = trimmedLine.dropWhile { it == '>' }.trimStart()
                        withStyle(SpanStyle(fontStyle = FontStyle.Italic)) {
                            append("▎ ")
                            appendInlineMarkdown(quoteBody, codeBackgroundColor, linkColor)
                        }
                    }
                    isBullet -> {
                        append("• ")
                        appendInlineMarkdown(trimmedLine.substring(2), codeBackgroundColor, linkColor)
                    }
                    numberedMatch != null -> {
                        val num = numberedMatch.groupValues[1]
                        val body = numberedMatch.groupValues[2]
                        append("$num. ")
                        appendInlineMarkdown(body, codeBackgroundColor, linkColor)
                    }
                    else -> {
                        appendInlineMarkdown(line, codeBackgroundColor, linkColor)
                    }
                }
            }
        }
    }

    private data class ParsedLink(
        val label: String,
        val url: String,
        val nextIndex: Int
    )

    private fun parseMarkdownLinkAt(text: String, startIndex: Int): ParsedLink? {
        if (startIndex >= text.length || text[startIndex] != '[') return null
        var idx = startIndex + 1
        var bracketDepth = 1
        while (idx < text.length && bracketDepth > 0) {
            when (text[idx]) {
                '[' -> bracketDepth++
                ']' -> bracketDepth--
            }
            idx++
        }
        if (bracketDepth != 0) return null
        val closeBracket = idx - 1
        if (idx >= text.length || text[idx] != '(') return null
        val openParen = idx
        idx++
        var parenDepth = 1
        while (idx < text.length && parenDepth > 0) {
            when (text[idx]) {
                '(' -> parenDepth++
                ')' -> parenDepth--
            }
            idx++
        }
        if (parenDepth != 0) return null
        val closeParen = idx - 1
        val label = text.substring(startIndex + 1, closeBracket)
        val url = text.substring(openParen + 1, closeParen)
        return ParsedLink(label = label, url = url, nextIndex = closeParen + 1)
    }

    private fun AnnotatedString.Builder.appendInlineMarkdown(
        text: String,
        codeBg: Color,
        linkColor: Color
    ) {
        var i = 0
        val len = text.length

        while (i < len) {
            // Check `code` or ``code``
            if (text[i] == '`') {
                val tickCount = text.substring(i).takeWhile { it == '`' }.length
                val delimiter = "`".repeat(tickCount)
                val closeIdx = text.indexOf(delimiter, i + tickCount)
                if (closeIdx != -1) {
                    val codeContent = text.substring(i + tickCount, closeIdx)
                    withStyle(
                        SpanStyle(
                            fontFamily = FontFamily.Monospace,
                            background = codeBg
                        )
                    ) {
                        append(codeContent)
                    }
                    i = closeIdx + tickCount
                    continue
                }
            }

            // Check [label](url) — strictly allow only safe https:// URLs
            if (text[i] == '[') {
                val parsedLink = parseMarkdownLinkAt(text, i)
                if (parsedLink != null) {
                    val cleanUrl = parsedLink.url.trim()
                    if (isSafeHttpsUrl(cleanUrl)) {
                        withLink(
                            LinkAnnotation.Url(
                                url = cleanUrl,
                                styles = TextLinkStyles(
                                    style = SpanStyle(
                                        color = linkColor,
                                        textDecoration = TextDecoration.Underline
                                    )
                                )
                            )
                        ) {
                            appendInlineMarkdown(parsedLink.label, codeBg, linkColor)
                        }
                    } else {
                        appendInlineMarkdown(parsedLink.label, codeBg, linkColor)
                    }
                    i = parsedLink.nextIndex
                    continue
                }
            }

            // Check **bold**
            if (text.startsWith("**", i)) {
                val nextDoubleStar = text.indexOf("**", i + 2)
                if (nextDoubleStar != -1) {
                    val boldContent = text.substring(i + 2, nextDoubleStar)
                    withStyle(SpanStyle(fontWeight = FontWeight.Bold)) {
                        appendInlineMarkdown(boldContent, codeBg, linkColor)
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
                        appendInlineMarkdown(italicContent, codeBg, linkColor)
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
