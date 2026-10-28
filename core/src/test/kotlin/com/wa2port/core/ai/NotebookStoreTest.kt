package com.wa2port.core.ai

import com.wa2port.core.engine.LineRef
import com.wa2port.core.engine.VoiceRef
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File

class NotebookStoreTest {
    @TempDir
    lateinit var tempDir: File

    @Test
    fun recordSentenceDeduplicatesByExplanationIdAndSurvivesExplainCacheClear() {
        val cacheDir = File(tempDir, "ai_cache")
        val notebookDir = File(tempDir, "notebook")
        val cache = ExplainCache(cacheDir)
        val notebook = NotebookStore(notebookDir)

        val expl = AiSampleExplanation.build(1_000L).copy(id = "ex_test_1", fromCache = false)
        val cachedCopy = expl.copy(fromCache = true)
        val ref = LineRef("1002", token = 42, lineNo = 12, voice = VoiceRef("1002_0012", 1))

        cache.put(expl)
        // Record fresh (fromCache = false) and then prefetched/cached (fromCache = true).
        notebook.recordSentence(ref, expl, now = 1_000L)
        notebook.recordSentence(ref, cachedCopy, now = 2_000L)

        // Offline fallback explanations must never enter the notebook.
        val offline = OfflineExplainer.build(AiSampleExplanation.request(), now = 3_000L)
        assertNull(notebook.recordSentence(ref, offline, now = 3_000L))

        val entries = notebook.sentences()
        assertEquals(1, entries.size, "Must deduplicate by explanationId")
        assertEquals("ex_test_1", entries[0].explanationId)
        assertEquals(1002, entries[0].scene)
        assertEquals(12, entries[0].lineNo)
        assertEquals("1002_0012", entries[0].voice)
        assertEquals(2_000L, entries[0].createdAt)

        // Save a Q&A thread and star a vocab item.
        val thread = listOf(
            FollowUpMessage("u1", FollowUpMessage.ROLE_USER, "为什么用マジ？", 2_100L),
            FollowUpMessage("m1", FollowUpMessage.ROLE_MODEL, "因为是口语强调。", 2_200L),
        )
        notebook.saveThread(expl.id, thread)
        assertTrue(notebook.toggleVocabStar(expl.vocab.first(), expl, ref, now = 2_500L))

        // Clearing ExplainCache must NOT affect NotebookStore!
        assertTrue(cache.clear() > 0)
        assertNull(cache.get(expl.id))

        val reloaded = NotebookStore(notebookDir)
        assertEquals(1, reloaded.sentences().size)
        val snap = reloaded.loadExplanation("ex_test_1")
        assertNotNull(snap)
        assertEquals(expl.sentence, snap!!.sentence)
        assertEquals(2, reloaded.loadThread("ex_test_1").size)
        assertEquals(1, reloaded.allQa().size)
        assertTrue(reloaded.isVocabStarred(expl.vocab.first().lemma))
    }

    @Test
    fun starUnstarPersistsAndExportsValidAnkiTsv() {
        val notebookDir = File(tempDir, "notebook")
        val notebook = NotebookStore(notebookDir)
        val expl = AiSampleExplanation.build(1_000L).copy(id = "ex_sample")
        val ref = LineRef("1001", token = 7, lineNo = 3, voice = VoiceRef("1001_0003", 2))

        notebook.recordSentence(ref, expl, now = 1_000L)

        val v0 = expl.vocab[0]
        val g0 = expl.grammar[0]
        assertTrue(notebook.toggleVocabStar(v0, expl, ref, now = 1_100L))
        assertTrue(notebook.toggleGrammarStar(g0, expl, ref, now = 1_200L))
        assertTrue(notebook.toggleSentenceStar(expl.id))

        assertTrue(notebook.isVocabStarred(v0.lemma))
        assertTrue(notebook.isGrammarStarred(g0.pattern))
        assertTrue(notebook.isSentenceStarred(expl.id))

        val starredTsv = notebook.exportAnkiTsv(onlyStarred = true)
        val starredRows = starredTsv.trim().lines()
        assertEquals(2, starredRows.size, "1 starred vocab + 1 starred grammar")
        starredRows.forEach { row ->
            val cols = row.split('\t')
            assertEquals(7, cols.size, "Each Anki TSV row must have 7 tab-separated columns: $row")
            assertTrue(cols[4].contains(expl.sentence))
            assertTrue(cols[6].contains("S1001#3"))
        }

        val allTsv = notebook.exportAnkiTsv(onlyStarred = false)
        val expectedUniqueRows =
            expl.vocab.map { it.lemma.ifBlank { it.surface } }.distinct().size +
                expl.grammar.map { it.pattern }.distinct().size
        assertEquals(expectedUniqueRows, allTsv.trim().lines().size)

        // Unstar and verify persistence across reload.
        assertFalse(notebook.toggleVocabStar(v0, expl, ref))
        assertFalse(notebook.toggleGrammarStar(g0, expl, ref))
        assertFalse(notebook.toggleSentenceStar(expl.id))

        val reloaded = NotebookStore(notebookDir)
        assertFalse(reloaded.isVocabStarred(v0.lemma))
        assertFalse(reloaded.isGrammarStarred(g0.pattern))
        assertFalse(reloaded.isSentenceStarred(expl.id))
        assertEquals("", reloaded.exportAnkiTsv(onlyStarred = true))
    }
}
