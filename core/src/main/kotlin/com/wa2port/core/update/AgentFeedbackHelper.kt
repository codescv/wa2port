package com.wa2port.core.update

import com.wa2port.core.ai.JpSentence
import com.wa2port.core.engine.DialogueLine
import com.wa2port.core.text.BilingualTextDecoder
import com.wa2port.core.text.TextLang
import java.net.URLEncoder
import java.nio.charset.StandardCharsets

/**
 * Optional snapshot of the active game session attached to an Agent feedback email so the Vibe
 * Coding agent can jump straight to the exact `.bnr` scene and dialogue token.
 */
data class FeedbackGameContext(
    val scene: String,
    val sceneTitle: String? = null,
    val lineNo: Int? = null,
    val token: Int? = null,
    val speaker: String? = null,
    val textJp: String? = null,
    val textCn: String? = null,
    val voiceFile: String? = null,
    val windowMode: String? = null,
    val displayLang: String? = null,
    val textScalePercent: Int? = null,
    val inReplayMode: Boolean = false,
) {
    /** Compact one-line summary shown next to the in-game context toggle. */
    fun summaryBadge(): String = buildString {
        append((sceneTitle?.takeIf { it.isNotBlank() } ?: scene).trim())
        if (lineNo != null && lineNo >= 0) append(" · #").append(lineNo)
        if (inReplayMode) append(" · 回想")
    }

    companion object {
        /** Extracts plain UTF-8 Japanese / Chinese line & speaker metadata from a live [DialogueLine]. */
        fun fromDialogue(
            scene: String,
            line: DialogueLine?,
            decoder: BilingualTextDecoder = BilingualTextDecoder(),
            sceneTitle: String? = null,
            windowMode: String? = null,
            displayLang: String? = null,
            textScalePercent: Int? = null,
            inReplayMode: Boolean = false,
        ): FeedbackGameContext {
            val jp = line?.let { JpSentence.fromRich(it.jp, decoder).text.trim().ifEmpty { null } }
            val cn = line?.let { JpSentence.officialCn(it.cn, decoder)?.trim()?.ifEmpty { null } }
            val spkJp = line?.let { JpSentence.speaker(it.speakerJp, decoder, TextLang.JP)?.trim()?.ifEmpty { null } }
            val spkCn = line?.let { JpSentence.speaker(it.speakerCn, decoder, TextLang.CN)?.trim()?.ifEmpty { null } }
            val speaker = when {
                spkCn != null && spkJp != null && spkCn != spkJp -> "$spkCn ($spkJp)"
                spkCn != null -> spkCn
                else -> spkJp
            }
            return FeedbackGameContext(
                scene = scene.trim(),
                sceneTitle = sceneTitle?.trim()?.ifEmpty { null },
                lineNo = line?.ref?.lineNo?.takeIf { it >= 0 },
                token = line?.ref?.token?.takeIf { it >= 0 },
                speaker = speaker,
                textJp = jp,
                textCn = cn,
                voiceFile = line?.ref?.voice?.file?.trim()?.ifEmpty { null },
                windowMode = windowMode?.trim()?.ifEmpty { null },
                displayLang = displayLang?.trim()?.ifEmpty { null },
                textScalePercent = textScalePercent,
                inReplayMode = inReplayMode,
            )
        }
    }
}

/**
 * Fully validated and encoded payload: [mailtoUri] for `Intent.ACTION_SENDTO` (text only), or
 * [toEmail] / [subject] / [body] as `Intent.ACTION_SEND` extras when an image is attached
 * ([attachmentName] = the attached image's display name, if any).
 */
data class AgentFeedbackMail(
    val toEmail: String,
    val subject: String,
    val body: String,
    val mailtoUri: String,
    val attachmentName: String? = null,
)

/**
 * Pure-JVM builder and validator for one-click Agent feedback emails (`wa2port: [v1.4.7] <title>`).
 *
 * The recipient address is configured exclusively on-device by the user (default `""`) and never
 * hardcoded in source or resources.
 */
object AgentFeedbackHelper {
    const val APP_ALIAS = "wa2port"
    const val DEFAULT_APP_VERSION = "1.4.7"
    const val PLACEHOLDER_EMAIL = "agent@example.com"
    const val PREFS_NAME = "wa2port_agent_feedback"
    const val PREF_KEY_EMAIL = "agent_feedback_email"
    const val MAX_SUMMARY_CHARS = 72
    const val DEFAULT_SUMMARY = "App Feedback"
    const val MAX_TITLE_CHARS = 120
    const val MAX_ATTACHMENT_NAME_CHARS = 120

    private val INVISIBLE_PASTE_CHARS = Regex("[\\u200B-\\u200F\\uFEFF\\u00A0]")
    private val WHITESPACE_RUN = Regex("\\s+")
    private val LOCAL_PART_CHARS = Regex("^[A-Za-z0-9.!#$%&'*+/=?^_`{|}~-]+$")
    private val DOMAIN_LABEL_CHARS = Regex("^[A-Za-z0-9-]+$")
    private val ALL_DIGITS = Regex("^\\d+$")

    /** Strips zero-width / non-breaking paste artifacts and outer whitespace. */
    fun normalizeEmail(raw: String): String =
        raw.replace(INVISIBLE_PASTE_CHARS, "").trim()

    /**
     * Returns `null` when [raw] is a valid RFC-compatible email address safe for `mailto:`, or a
     * bilingual user-facing error message when blank or malformed.
     */
    fun validateEmail(raw: String): String? {
        val email = normalizeEmail(raw)
        if (email.isEmpty()) {
            return "请先填写 Agent 反馈邮箱 Please configure Agent feedback email"
        }
        if (email.length > 254 || email.any { it.isWhitespace() || it.code < 0x20 || it.code == 0x7F }) {
            return "邮箱格式无效 Invalid email address"
        }
        val at = email.indexOf('@')
        if (at <= 0 || at != email.lastIndexOf('@') || at == email.lastIndex) {
            return "邮箱格式无效 Invalid email address"
        }
        val local = email.substring(0, at)
        val domain = email.substring(at + 1)
        if (local.length > 64 || local.startsWith('.') || local.endsWith('.') || ".." in local) {
            return "邮箱格式无效 Invalid email address"
        }
        if (!LOCAL_PART_CHARS.matches(local)) {
            return "邮箱格式无效 Invalid email address"
        }
        if (domain.length > 253 || domain.startsWith('.') || domain.endsWith('.') || ".." in domain) {
            return "邮箱格式无效 Invalid email address"
        }
        val labels = domain.split('.')
        if (labels.size < 2) {
            return "邮箱格式无效 Invalid email address"
        }
        for (label in labels) {
            if (label.isEmpty() || label.length > 63 || label.startsWith('-') || label.endsWith('-') || !DOMAIN_LABEL_CHARS.matches(label)) {
                return "邮箱格式无效 Invalid email address"
            }
        }
        val tld = labels.last()
        if (tld.length < 2 || ALL_DIGITS.matches(tld)) {
            return "邮箱格式无效 Invalid email address"
        }
        return null
    }

    /** True when [raw] passes [validateEmail]. */
    fun isValidEmail(raw: String): Boolean = validateEmail(raw) == null

    /** Normalizes a version string (strips leading `v`/`V`, falls back to [DEFAULT_APP_VERSION]). */
    fun normalizeVersion(version: String): String {
        val clean = version.trim().removePrefix("v").removePrefix("V").trim()
        return if (clean.isEmpty() || clean == "0.0.0") DEFAULT_APP_VERSION else clean
    }

    /** Collapses [title] to one trimmed line (CR/LF/tabs → single spaces), capped at [MAX_TITLE_CHARS]. */
    fun normalizeTitle(title: String): String {
        val collapsed = title.replace(INVISIBLE_PASTE_CHARS, " ").replace(WHITESPACE_RUN, " ").trim()
        if (collapsed.length <= MAX_TITLE_CHARS) return collapsed
        return collapsed.substring(0, MAX_TITLE_CHARS - 1).trimEnd() + "…"
    }

    /**
     * Sanitizes an attachment display name for the email body (single line, no path separators or
     * control characters). Returns `null` when [name] is `null` or blank.
     */
    fun normalizeAttachmentName(name: String?): String? {
        if (name == null) return null
        val leaf = name.substringAfterLast('/').substringAfterLast('\\')
        val clean = leaf.map { if (it.code < 0x20 || it.code == 0x7F) ' ' else it }.joinToString("")
            .replace(INVISIBLE_PASTE_CHARS, " ")
            .replace(WHITESPACE_RUN, " ")
            .trim()
        if (clean.isEmpty()) return null
        return if (clean.length <= MAX_ATTACHMENT_NAME_CHARS) clean else clean.substring(0, MAX_ATTACHMENT_NAME_CHARS - 1).trimEnd() + "…"
    }

    /** Extracts a single-line summary from [summaryOverride] or the first non-blank line of [feedbackText]. */
    fun extractSummary(
        feedbackText: String,
        summaryOverride: String = "",
        maxChars: Int = MAX_SUMMARY_CHARS,
    ): String {
        val candidate = summaryOverride.trim().ifEmpty {
            feedbackText.lineSequence().map { it.trim() }.firstOrNull { it.isNotEmpty() }.orEmpty()
        }
        val collapsed = candidate.replace(WHITESPACE_RUN, " ").trim()
        if (collapsed.isEmpty()) return DEFAULT_SUMMARY
        val cap = maxChars.coerceAtLeast(8)
        if (collapsed.length <= cap) return collapsed
        return collapsed.substring(0, cap - 1).trimEnd() + "…"
    }

    /**
     * Builds the Vibe Coding dispatcher subject: **`wa2port: [v1.4.7] <title>`**. The summary is
     * [title] when non-blank, else [summaryOverride], else the first non-blank line of [feedbackText].
     */
    fun buildSubject(
        feedbackText: String,
        summaryOverride: String = "",
        version: String = DEFAULT_APP_VERSION,
        title: String = "",
    ): String {
        val v = normalizeVersion(version)
        val summary = extractSummary(feedbackText, title.trim().ifEmpty { summaryOverride })
        return "$APP_ALIAS: [v$v] $summary"
    }

    /**
     * Builds the plain-text email body combining the user's optional [title] and feedback with app
     * version, optional device info, optional attached image name ([attachmentName]) and optional
     * active game scene/line diagnostics.
     */
    fun buildBody(
        feedbackText: String,
        version: String = DEFAULT_APP_VERSION,
        deviceInfo: String? = null,
        gameContext: FeedbackGameContext? = null,
        title: String = "",
        attachmentName: String? = null,
    ): String {
        val v = normalizeVersion(version)
        val normalizedTitle = normalizeTitle(title)
        val normalizedAttachment = normalizeAttachmentName(attachmentName)
        val normalizedFeedback = feedbackText.replace("\r\n", "\n").replace('\r', '\n').trim()
        return buildString {
            if (normalizedTitle.isNotEmpty()) {
                append("标题 Title: ").append(normalizedTitle)
                append("\n\n")
            }
            if (normalizedFeedback.isNotEmpty()) {
                append(normalizedFeedback)
                append("\n\n")
            }
            append("---\n")
            append("App: ").append(APP_ALIAS).append(" v").append(v).append('\n')
            deviceInfo?.trim()?.takeIf { it.isNotEmpty() }?.let {
                append("Device: ").append(it).append('\n')
            }
            normalizedAttachment?.let {
                append("Attachment: ").append(it).append('\n')
            }
            if (gameContext != null && gameContext.scene.isNotBlank()) {
                val sceneDisplay = gameContext.sceneTitle?.takeIf { it.isNotBlank() } ?: gameContext.scene
                append("Scene: ").append(sceneDisplay)
                if (gameContext.inReplayMode) append(" [Scene Replay]")
                append('\n')
                val lineParts = buildList {
                    gameContext.lineNo?.let { add("#$it") }
                    gameContext.token?.let { add("token=$it") }
                    gameContext.voiceFile?.let { add("voice=$it") }
                }
                if (lineParts.isNotEmpty()) {
                    append("Line: ").append(lineParts.joinToString(" ")).append('\n')
                }
                val displayParts = buildList {
                    gameContext.windowMode?.let { add(it) }
                    gameContext.displayLang?.let { add("lang=$it") }
                    gameContext.textScalePercent?.let { add("scale=${it}%") }
                }
                if (displayParts.isNotEmpty()) {
                    append("Display: ").append(displayParts.joinToString(", ")).append('\n')
                }
                gameContext.speaker?.let {
                    append("Speaker: ").append(it).append('\n')
                }
                gameContext.textJp?.let {
                    append("JP: ").append(it.replace('\n', ' ')).append('\n')
                }
                gameContext.textCn?.let {
                    append("CN: ").append(it.replace('\n', ' ')).append('\n')
                }
            }
        }.trimEnd()
    }

    /**
     * Builds an RFC 6068 `mailto:` URI using `%20` percent-encoding (never `+`, because Android's
     * `MailTo.parse` / `Uri.decode` preserves literal `+` signs in subjects and bodies).
     */
    fun buildMailtoUri(email: String, subject: String, body: String): String {
        val err = validateEmail(email)
        require(err == null) { err!! }
        val cleanEmail = normalizeEmail(email)
        val normalizedSubject = subject.replace("\r", "").replace('\n', ' ').trim()
        val normalizedBody = body.replace("\r\n", "\n").replace('\r', '\n')
        return "mailto:$cleanEmail?subject=${percentEncode(normalizedSubject)}&body=${percentEncode(normalizedBody)}"
    }

    /** Validates inputs and assembles the complete [AgentFeedbackMail] payload. */
    fun prepareMail(
        email: String,
        feedbackText: String,
        summaryOverride: String = "",
        version: String = DEFAULT_APP_VERSION,
        deviceInfo: String? = null,
        gameContext: FeedbackGameContext? = null,
        title: String = "",
        attachmentName: String? = null,
    ): AgentFeedbackMail {
        val err = validateEmail(email)
        require(err == null) { err!! }
        val cleanEmail = normalizeEmail(email)
        val subject = buildSubject(
            feedbackText = feedbackText,
            summaryOverride = summaryOverride,
            version = version,
            title = title,
        )
        val body = buildBody(
            feedbackText = feedbackText,
            version = version,
            deviceInfo = deviceInfo,
            gameContext = gameContext,
            title = title,
            attachmentName = attachmentName,
        )
        val uri = buildMailtoUri(cleanEmail, subject, body)
        return AgentFeedbackMail(
            toEmail = cleanEmail,
            subject = subject,
            body = body,
            mailtoUri = uri,
            attachmentName = normalizeAttachmentName(attachmentName),
        )
    }

    internal fun percentEncode(value: String): String =
        URLEncoder.encode(value, StandardCharsets.UTF_8.name()).replace("+", "%20")
}
