package com.wa2port.core.ai

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class Wa2ExplainPromptsTest {
    private fun user(req: ExplainRequest) = buildExplainPrompt(req).contents.single().text

    @Test
    fun targetSentenceIsQuotedVerbatim() {
        val p = buildExplainPrompt(ExplainRequest("雪だね"))
        assertEquals("user", p.contents.single().role)
        assertTrue(p.contents.single().text.endsWith("<<<\n雪だね\n>>>"))
        assertTrue(p.systemInstruction.contains("WHITE ALBUM 2"))
        assertTrue(p.systemInstruction.contains("officialTranslationNote"))
        assertTrue(p.systemInstruction.contains("Simplified Chinese"))
    }

    @Test
    fun focusCharIndexBounds() {
        assertTrue(user(ExplainRequest("雪だね", focusCharIndex = 0)).contains("tapped the character 「雪」"))
        assertTrue(user(ExplainRequest("雪だね", focusCharIndex = 2)).contains("「ね」"))
        assertFalse(user(ExplainRequest("雪だね", focusCharIndex = 3)).contains("tapped"))
        assertFalse(user(ExplainRequest("雪だね", focusCharIndex = -1)).contains("tapped"))
        assertFalse(user(ExplainRequest("雪だね")).contains("tapped"))
        // Surrogate pair stays intact.
        assertTrue(user(ExplainRequest("𠮷野家", focusCharIndex = 0)).contains("「𠮷」"))
    }

    @Test
    fun speakerVsNarration() {
        assertTrue(user(ExplainRequest("x", speakerJp = "雪菜", speakerCn = "雪菜")).contains("Speaker: 雪菜\n"))
        assertTrue(user(ExplainRequest("x", speakerJp = "かずさ", speakerCn = "和纱")).contains("Speaker: かずさ (和纱)"))
        assertTrue(user(ExplainRequest("x")).contains("Speaker: (narration / inner monologue)"))
    }

    @Test
    fun backlogContextAndOfficialTranslation() {
        val u = user(
            ExplainRequest(
                "x",
                contextBefore = listOf("a: 1", "b: 2", "", "c: 3", "d: 4"),
                officialCnTranslation = "官方译文",
                sceneHint = "WHITE ALBUM 2 · IC",
                scriptRuby = listOf(ScriptRuby(CharSpan(0, 1), "エックス")),
            ),
        )
        val ctx = u.substringAfter("Context before (do not explain):\n").substringBefore("\nOfficial")
        assertEquals("- b: 2\n- c: 3\n- d: 4", ctx) // ≤ 3, oldest first, blanks dropped
        assertTrue(u.contains("Official Chinese translation (CK-GAL, reference only): 官方译文"))
        assertTrue(u.startsWith("Scene: WHITE ALBUM 2 · IC\n"))
        assertTrue(u.contains("Script furigana (authoritative, keep these readings): x=エックス"))
        assertTrue(u.indexOf("Context before") < u.indexOf("TARGET sentence"))
    }

    @Test
    fun followUpPersonaAndHistory() {
        val expl = AiSampleExplanation.build()
        val history = listOf(
            FollowUpMessage("1", "user", "Q1", 1),
            FollowUpMessage("2", "model", "A1", 2),
            FollowUpMessage("3", "user", "  ", 3),
        )
        val p = buildFollowUpPrompt(expl, history, "Q2", AiPersonaSettings.KAZUSA)
        assertTrue(p.systemInstruction.contains("冬马和纱"))
        assertTrue(p.systemInstruction.contains(AiPersonaSettings.KAZUSA.soul))
        assertTrue(p.systemInstruction.contains(AiPersonaSettings.ACCURACY_CLAUSE))
        assertTrue(p.systemInstruction.contains(AiSampleExplanation.SENTENCE))
        assertTrue(p.systemInstruction.contains("officialCnTranslation"))
        assertEquals(listOf("user" to "Q1", "model" to "A1", "user" to "Q2"), p.contents.map { it.role to it.text })
        val k = buildFollowUpPrompt(expl, emptyList(), "Q", AiPersonaSettings.byId("unknown"))
        assertTrue(k.systemInstruction.contains("小鳥"))
    }

    @Test
    fun customPersonaResolution() {
        val c = AiPersonaSettings.resolve("custom", "千冬", "毒舌但靠谱的青梅竹马日语老师")
        assertEquals(AiPersonaSettings.ID_CUSTOM, c.id)
        assertEquals("千冬", c.name)
        assertEquals("毒舌但靠谱的青梅竹马日语老师", c.soul)
        // Legacy "kotori" / unknown / null ids resolve to the user's custom persona.
        assertEquals(c, AiPersonaSettings.resolve("kotori", " 千冬 ", "毒舌但靠谱的青梅竹马日语老师"))
        assertEquals(c, AiPersonaSettings.resolve(null, "千冬", "毒舌但靠谱的青梅竹马日语老师"))
        // Blank custom fields fall back to the defaults.
        assertEquals(AiPersonaSettings.CUSTOM, AiPersonaSettings.resolve("kotori", "  ", ""))
        assertEquals(AiPersonaSettings.CUSTOM, AiPersonaSettings.KOTORI)
        // Built-in characters are unaffected by custom fields.
        assertEquals(AiPersonaSettings.KAZUSA, AiPersonaSettings.resolve("kazusa", "千冬", "x"))
        assertEquals(AiPersonaSettings.SETSUNA, AiPersonaSettings.resolve("setsuna", "千冬", "x"))
        assertEquals(listOf("kazusa", "setsuna", "custom"), AiPersonaSettings.PRESETS.map { it.id })

        val p = buildFollowUpPrompt(AiSampleExplanation.build(), emptyList(), "Q", c)
        assertTrue(p.systemInstruction.contains("千冬"))
        assertTrue(p.systemInstruction.contains("毒舌但靠谱的青梅竹马日语老师"))
        assertTrue(p.systemInstruction.contains(AiPersonaSettings.ACCURACY_CLAUSE))
    }

    @Test
    fun summaryIsCompactJson() {
        val s = summariseExplanation(AiSampleExplanation.build())
        val o = AiJson.parseToJsonElement(s)
        assertTrue(o.toString().contains("雪ですね"))
        assertEquals("雪菜", formatContextLine(" 雪菜 ", "x").substringBefore(':'))
        assertEquals("台詞 続き", formatContextLine(null, "台詞\n続き"))
    }
}
