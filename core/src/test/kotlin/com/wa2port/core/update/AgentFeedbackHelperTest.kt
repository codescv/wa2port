package com.wa2port.core.update

import com.wa2port.core.engine.DialogueLine
import com.wa2port.core.engine.LineRef
import com.wa2port.core.engine.VoiceRef
import com.wa2port.core.text.BilingualTextDecoder
import com.wa2port.core.text.ScenarioTextParser
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.io.File
import java.net.URI
import java.net.URLDecoder
import java.nio.charset.Charset
import java.nio.charset.StandardCharsets

class AgentFeedbackHelperTest {

    @Test
    fun defaultVersionAndConstantsMatchReleaseSpec() {
        assertEquals("wa2port", AgentFeedbackHelper.APP_ALIAS)
        assertEquals("1.4.5", AgentFeedbackHelper.DEFAULT_APP_VERSION)
        assertEquals("agent@example.com", AgentFeedbackHelper.PLACEHOLDER_EMAIL)
    }

    @Test
    fun normalizesInvisiblePasteArtifactsAndWhitespace() {
        assertEquals(
            "agent@example.com",
            AgentFeedbackHelper.normalizeEmail(" \uFEFFagent\u200B@example.com\u00A0 "),
        )
        assertEquals("", AgentFeedbackHelper.normalizeEmail("  \u200B\uFEFF "))
    }

    @Test
    fun validatesEmailAddresses() {
        val valid = listOf(
            "agent@example.com",
            " \u200Bdev.agent+wa2@sub.example.org ",
            "a_b-c@vibe-host.co.jp",
        )
        for (addr in valid) {
            assertNull(AgentFeedbackHelper.validateEmail(addr), "expected valid: $addr")
            assertTrue(AgentFeedbackHelper.isValidEmail(addr), "expected valid: $addr")
        }

        assertTrue(AgentFeedbackHelper.validateEmail("")!!.contains("请先填写"))
        assertTrue(AgentFeedbackHelper.validateEmail("   ")!!.contains("请先填写"))

        val invalid = listOf(
            "not-an-email",
            "@example.com",
            "agent@",
            "agent@localhost",
            "agent@.example.com",
            "agent@example..com",
            ".agent@example.com",
            "agent.@example.com",
            "agent..two@example.com",
            "a@b@example.com",
            "agent name@example.com",
            "agent@-example.com",
            "agent@example-.com",
            "agent@example.c",
            "agent@example.123",
            "mailto:agent@example.com",
            "agent@example.com?subject=inject",
            "agent@example.com\nBcc:other@example.org",
        )
        for (addr in invalid) {
            assertFalse(AgentFeedbackHelper.isValidEmail(addr), "expected invalid: $addr")
            assertNotNull(AgentFeedbackHelper.validateEmail(addr), "expected error: $addr")
        }
    }

    @Test
    fun buildsVibeCodingSubjectWithVersionPrefix() {
        assertEquals(
            "wa2port: [v1.4.5] 双语模式下第四行标点优化",
            AgentFeedbackHelper.buildSubject("  \n  双语模式下第四行标点优化\n详细复现步骤…  "),
        )
        assertEquals(
            "wa2port: [v1.4.5] Custom summary",
            AgentFeedbackHelper.buildSubject("Body line", summaryOverride = "  Custom   summary ", version = "v1.4.5"),
        )
        assertEquals(
            "wa2port: [v1.4.5] App Feedback",
            AgentFeedbackHelper.buildSubject("   ", version = "0.0.0"),
        )
        val longLine = "あ".repeat(100)
        val subject = AgentFeedbackHelper.buildSubject(longLine)
        assertTrue(subject.startsWith("wa2port: [v1.4.5] "))
        val summaryPart = subject.removePrefix("wa2port: [v1.4.5] ")
        assertEquals(AgentFeedbackHelper.MAX_SUMMARY_CHARS, summaryPart.length)
        assertTrue(summaryPart.endsWith("…"))
    }

    @Test
    fun buildsBodyWithAndWithoutDialogueContext() {
        val plainBody = AgentFeedbackHelper.buildBody(
            feedbackText = "CG 鉴赏界面希望支持按章节记忆筛选标签。",
            version = "1.4.5",
            deviceInfo = "Pixel 9 (Android 15, SDK 35)",
            gameContext = null,
        )
        assertTrue(plainBody.startsWith("CG 鉴赏界面希望支持按章节记忆筛选标签。\n\n---\nApp: wa2port v1.4.5"))
        assertTrue(plainBody.contains("Device: Pixel 9 (Android 15, SDK 35)"))
        assertFalse(plainBody.contains("Scene:"))

        val decoder = BilingualTextDecoder()
        val ms932 = Charset.forName("MS932")
        val jpRich = ScenarioTextParser.parse("「……あ、雪だね」".toByteArray(ms932))
        val spkJp = ScenarioTextParser.parse("雪菜".toByteArray(ms932))
        val line = DialogueLine(
            ref = LineRef(
                scene = "1002",
                token = 194,
                lineNo = 42,
                speakerToken = 3,
                voice = VoiceRef("1002_0042_02.OGG", 2),
            ),
            jp = jpRich,
            cn = null,
            speakerJp = spkJp,
            speakerCn = null,
            read = true,
        )
        val ctx = FeedbackGameContext.fromDialogue(
            scene = "1002",
            line = line,
            decoder = decoder,
            sceneTitle = "序章 IC · 1002",
            windowMode = "ADV",
            displayLang = "双",
            textScalePercent = 115,
            inReplayMode = true,
        )
        assertEquals("序章 IC · 1002 · #42 · 回想", ctx.summaryBadge())

        val ctxBody = AgentFeedbackHelper.buildBody(
            feedbackText = "这里的语音重听按钮位置很好，建议补充振动反馈。",
            version = "v1.4.5",
            gameContext = ctx,
        )
        assertTrue(ctxBody.contains("Scene: 序章 IC · 1002 [Scene Replay]"))
        assertTrue(ctxBody.contains("Line: #42 token=194 voice=1002_0042_02.OGG"))
        assertTrue(ctxBody.contains("Display: ADV, lang=双, scale=115%"))
        assertTrue(ctxBody.contains("Speaker: 雪菜"))
        assertTrue(ctxBody.contains("JP: 「……あ、雪だね」"))
    }

    @Test
    fun buildsRfc6068MailtoUriWithoutPlusSpaceCorruption() {
        val mail = AgentFeedbackHelper.prepareMail(
            email = "agent@example.com",
            feedbackText = "测试 A+B & C=1? #tag\n第二行日本語テスト",
            version = "1.4.5",
        )
        assertEquals("agent@example.com", mail.toEmail)
        assertEquals("wa2port: [v1.4.5] 测试 A+B & C=1? #tag", mail.subject)
        assertTrue(mail.mailtoUri.startsWith("mailto:agent@example.com?subject="))
        // URI must parse cleanly under strict RFC 2396 / 3986 rules and never use raw '+' for spaces.
        val parsed = URI.create(mail.mailtoUri)
        assertEquals("mailto", parsed.scheme)
        val rawSchemePart = parsed.rawSchemeSpecificPart
        assertFalse(rawSchemePart.contains(' '), "spaces must be %20-encoded")
        assertFalse(rawSchemePart.contains("wa2port+"), "spaces must not become '+'")

        val query = rawSchemePart.substringAfter('?')
        val params = query.split('&').associate { pair ->
            val k = pair.substringBefore('=')
            val v = pair.substringAfter('=')
            // Simulate Android Uri.decode (percent-only decoding, preserving literal '+'):
            k to percentDecodeOnly(v)
        }
        assertEquals(mail.subject, params["subject"])
        assertEquals(mail.body, params["body"])
    }

    @Test
    fun prepareMailRejectsMissingOrInvalidEmail() {
        assertThrows(IllegalArgumentException::class.java) {
            AgentFeedbackHelper.prepareMail(email = "", feedbackText = "Hello")
        }
        assertThrows(IllegalArgumentException::class.java) {
            AgentFeedbackHelper.prepareMail(email = "invalid-email", feedbackText = "Hello")
        }
    }

    @Test
    fun sourceTreeContainsNoHardcodedCorporateOrPersonalEmails() {
        val repoRoot = File(System.getProperty("user.dir")).let { cwd ->
            if (File(cwd, "settings.gradle.kts").isFile) cwd else cwd.parentFile
        }
        val forbiddenDomain = "@" + "goo" + "gle" + ".com"
        val forbiddenUser = "ch" + "ii@"
        val scannedDirs = listOf(File(repoRoot, "core/src"), File(repoRoot, "app/src"))
        for (dir in scannedDirs) {
            if (!dir.isDirectory) continue
            dir.walkTopDown()
                .filter { it.isFile && (it.extension in setOf("kt", "kts", "xml", "c", "h")) }
                .forEach { file ->
                    val content = file.readText().lowercase()
                    assertFalse(content.contains(forbiddenDomain), "Forbidden domain found in ${file.path}")
                    assertFalse(content.contains(forbiddenUser), "Forbidden user prefix found in ${file.path}")
                }
        }
    }

    private fun percentDecodeOnly(encoded: String): String =
        URLDecoder.decode(encoded.replace("+", "%2B"), StandardCharsets.UTF_8.name())
}
