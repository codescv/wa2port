package com.wa2port.android.ai

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.wa2port.android.ui.CloseButton
import com.wa2port.android.ui.Wa2Colors
import com.wa2port.android.ui.sceneLabel
import com.wa2port.core.ai.NotebookQaEntry
import com.wa2port.core.ai.NotebookSentenceEntry
import com.wa2port.core.ai.RubyBuilder
import com.wa2port.core.ai.StarredGrammar
import com.wa2port.core.ai.StarredVocab
import com.wa2port.core.ai.partOfSpeechLabel
import com.wa2port.core.engine.VoiceRef

private val NB_SCRIM = Color(0xFF060B18)
private val NB_CARD = Color(0xFF111A2E)
private val NB_STAR = Color(0xFFFFD54F)
private val NB_TEXTBOOK = Color(0xFF8FD3A8)

enum class NotebookTab(val label: String) {
    VOCAB("生词 Vocab"),
    GRAMMAR("语法 Grammar"),
    SENTENCES("句子 Sentences"),
    QA("追问 Q&A"),
}

private val JLPT_FILTERS = listOf("全部", "N5", "N4", "N3", "N2", "N1")

/**
 * Full-screen `📒 学习记录 Notebook` overlay (#9):
 * - 4 Tabs: `生词 Vocab` · `语法 Grammar` · `句子 Sentences` · `追问 Q&A`
 * - Filter chips: `全部 All` / `★ 仅收藏 Starred` / `N5..N1` + search field
 * - Each card shows its source sentence, 1-tap `🔊` voice replay, and `★ / ☆` star toggle;
 *   tapping a sentence or `来源句` opens its persisted AI breakdown via [AiLearningController.openFromNotebook]
 * - Header button `📤 导出 Anki TSV` copies the 7-column TSV to clipboard and opens Android's ShareSheet
 */
@Composable
fun NotebookOverlay(
    ai: AiLearningController,
    onReplayVoice: ((VoiceRef) -> Unit)? = null,
    onClose: () -> Unit = { ai.closeNotebook() },
) {
    val context = LocalContext.current
    val rev = ai.notebookRevision
    var tab by remember { mutableStateOf(NotebookTab.VOCAB) }
    var onlyStarred by remember { mutableStateOf(false) }
    var jlptFilter by remember { mutableStateOf("全部") }
    var query by remember { mutableStateOf("") }
    var exportNotice by remember { mutableStateOf<String?>(null) }

    val sentences = remember(rev) { ai.notebook.sentences() }
    val sentenceById = remember(sentences) { sentences.associateBy { it.explanationId } }
    val stars = remember(rev) { ai.notebook.stars() }
    val allVocab = remember(rev) { ai.notebook.allVocab() }
    val allGrammar = remember(rev) { ai.notebook.allGrammar() }
    val allQa = remember(rev) { ai.notebook.allQa() }

    val q = query.trim().lowercase()

    val filteredVocab = remember(allVocab, stars, onlyStarred, jlptFilter, q) {
        val starredLemmas = stars.vocab.mapTo(HashSet()) { it.lemma }
        allVocab.filter { v ->
            (!onlyStarred || v.lemma in starredLemmas) &&
                (jlptFilter == "全部" || v.jlpt.equals(jlptFilter, ignoreCase = true)) &&
                (q.isEmpty() ||
                    v.surface.lowercase().contains(q) ||
                    v.lemma.lowercase().contains(q) ||
                    v.reading.lowercase().contains(q) ||
                    v.meaning.lowercase().contains(q) ||
                    v.exampleSentence.lowercase().contains(q) ||
                    (v.officialCn?.lowercase()?.contains(q) == true))
        }
    }

    val filteredGrammar = remember(allGrammar, stars, onlyStarred, jlptFilter, q) {
        val starredPatterns = stars.grammar.mapTo(HashSet()) { it.pattern }
        allGrammar.filter { g ->
            (!onlyStarred || g.pattern in starredPatterns) &&
                (jlptFilter == "全部" || g.jlpt.equals(jlptFilter, ignoreCase = true)) &&
                (q.isEmpty() ||
                    g.pattern.lowercase().contains(q) ||
                    g.explanation.lowercase().contains(q) ||
                    g.exampleSentence.lowercase().contains(q) ||
                    (g.officialCn?.lowercase()?.contains(q) == true))
        }
    }

    val filteredSentences = remember(sentences, stars, onlyStarred, q) {
        sentences.filter { s ->
            (!onlyStarred || s.explanationId in stars.sentences) &&
                (q.isEmpty() ||
                    s.sentence.lowercase().contains(q) ||
                    (s.officialCn?.lowercase()?.contains(q) == true) ||
                    s.translation.lowercase().contains(q) ||
                    (s.speakerJp?.lowercase()?.contains(q) == true) ||
                    (s.speakerCn?.lowercase()?.contains(q) == true))
        }
    }

    val filteredQa = remember(allQa, stars, onlyStarred, q) {
        allQa.filter { qa ->
            (!onlyStarred || qa.explanationId in stars.sentences) &&
                (q.isEmpty() ||
                    qa.sentence.lowercase().contains(q) ||
                    qa.question.lowercase().contains(q) ||
                    qa.answer.lowercase().contains(q))
        }
    }

    fun replay(ref: VoiceRef?) {
        val v = ref ?: return
        if (onReplayVoice != null) onReplayVoice(v) else ai.replayVoice(v)
    }

    Box(
        Modifier
            .fillMaxSize()
            .background(NB_SCRIM)
            .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null) {},
    ) {
        Column(
            Modifier
                .align(Alignment.Center)
                .widthIn(max = 1100.dp)
                .fillMaxSize()
                .padding(horizontal = 16.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            // Top bar
            Row(Modifier.fillMaxWidth().heightIn(min = 48.dp), verticalAlignment = Alignment.CenterVertically) {
                Text(
                    "📒 学习记录 Notebook (${sentences.size} 句 · ★ ${stars.vocab.size + stars.grammar.size + stars.sentences.size})",
                    color = Wa2Colors.Text,
                    fontSize = 18.sp,
                    fontWeight = FontWeight.Medium,
                    modifier = Modifier.weight(1f),
                )
                NbChip("📤 导出 Anki TSV", color = NB_TEXTBOOK) {
                    val tsv = ai.notebook.exportAnkiTsv(onlyStarred = onlyStarred)
                    val rows = tsv.lines().count { it.isNotBlank() }
                    exportAnkiTsv(context, tsv)
                    exportNotice = "已复制并导出 $rows 条卡片（Anki 7 列 TSV）"
                }
                Spacer(Modifier.width(8.dp))
                CloseButton(onClose)
            }

            exportNotice?.let { msg ->
                Text(
                    "✓ $msg",
                    color = NB_TEXTBOOK,
                    fontSize = 12.sp,
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(8.dp))
                        .background(NB_TEXTBOOK.copy(alpha = 0.14f))
                        .padding(horizontal = 10.dp, vertical = 6.dp),
                )
            }

            // Tabs + filter row
            Row(
                Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                NotebookTab.entries.forEach { t ->
                    val count = when (t) {
                        NotebookTab.VOCAB -> filteredVocab.size
                        NotebookTab.GRAMMAR -> filteredGrammar.size
                        NotebookTab.SENTENCES -> filteredSentences.size
                        NotebookTab.QA -> filteredQa.size
                    }
                    NbChip("${t.label} ($count)", selected = tab == t) { tab = t }
                }
                Spacer(Modifier.width(8.dp))
                NbChip("全部 All", selected = !onlyStarred) { onlyStarred = false }
                NbChip("★ 仅收藏 Starred", selected = onlyStarred, color = NB_STAR) { onlyStarred = true }
                if (tab == NotebookTab.VOCAB || tab == NotebookTab.GRAMMAR) {
                    Spacer(Modifier.width(4.dp))
                    JLPT_FILTERS.forEach { level ->
                        NbChip(level, selected = jlptFilter == level, color = NB_TEXTBOOK) { jlptFilter = level }
                    }
                }
            }

            // Search bar
            OutlinedTextField(
                value = query,
                onValueChange = { query = it },
                modifier = Modifier.fillMaxWidth(),
                singleLine = true,
                placeholder = { Text("🔍 搜索词条、读音、释义、语法或台词原句… Search notebook", fontSize = 13.sp, color = Wa2Colors.TextDim) },
                colors = OutlinedTextFieldDefaults.colors(
                    focusedTextColor = Wa2Colors.Text,
                    unfocusedTextColor = Wa2Colors.Text,
                    focusedBorderColor = Wa2Colors.Accent,
                    unfocusedBorderColor = Wa2Colors.AccentDim,
                    cursorColor = Wa2Colors.Accent,
                ),
            )

            // Content list
            LazyColumn(
                Modifier.weight(1f).fillMaxWidth(),
                contentPadding = PaddingValues(bottom = 16.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                when (tab) {
                    NotebookTab.VOCAB -> {
                        if (filteredVocab.isEmpty()) {
                            item(key = "empty-vocab") {
                                EmptyNotebookCard("暂无符合条件的生词记录 · 在台词上点「✨ AI 解说」即可自动收入学习记录，点 ★ 可加入重点收藏。")
                            }
                        } else {
                            items(filteredVocab, key = { "v-${it.lemma}" }) { v ->
                                val src = sentenceById[v.explanationId]
                                val starred = ai.notebook.isVocabStarred(v.lemma)
                                val voiceRef = v.voiceRef ?: src?.voiceRef
                                NotebookVocabCard(
                                    v = v,
                                    starred = starred,
                                    sourceEntry = src,
                                    onToggleStar = { ai.toggleVocabStar(v) },
                                    onReplayVoice = if (voiceRef != null) ({ replay(voiceRef) }) else null,
                                    onOpenSentence = if (src != null) ({ ai.openFromNotebook(src) }) else null,
                                )
                            }
                        }
                    }

                    NotebookTab.GRAMMAR -> {
                        if (filteredGrammar.isEmpty()) {
                            item(key = "empty-grammar") {
                                EmptyNotebookCard("暂无符合条件的语法记录 · 打开任意台词的 AI 解说后将自动汇总。")
                            }
                        } else {
                            items(filteredGrammar, key = { "g-${it.pattern}" }) { g ->
                                val src = sentenceById[g.explanationId]
                                val starred = ai.notebook.isGrammarStarred(g.pattern)
                                val voiceRef = g.voiceRef ?: src?.voiceRef
                                NotebookGrammarCard(
                                    g = g,
                                    starred = starred,
                                    sourceEntry = src,
                                    onToggleStar = { ai.toggleGrammarStar(g) },
                                    onReplayVoice = if (voiceRef != null) ({ replay(voiceRef) }) else null,
                                    onOpenSentence = if (src != null) ({ ai.openFromNotebook(src) }) else null,
                                )
                            }
                        }
                    }

                    NotebookTab.SENTENCES -> {
                        if (filteredSentences.isEmpty()) {
                            item(key = "empty-sentences") {
                                EmptyNotebookCard("尚未记录任何台词解说 · 在游戏中点击「✨ AI 解说」分析过的句子都会持久保存在这里（清除缓存也不会丢失）。")
                            }
                        } else {
                            items(filteredSentences, key = { "s-${it.explanationId}" }) { s ->
                                val starred = ai.isSentenceStarred(s.explanationId)
                                val voiceRef = s.voiceRef
                                NotebookSentenceCard(
                                    entry = s,
                                    starred = starred,
                                    onToggleStar = { ai.toggleSentenceStar(s.explanationId) },
                                    onReplayVoice = if (voiceRef != null) ({ replay(voiceRef) }) else null,
                                    onOpen = { ai.openFromNotebook(s) },
                                )
                            }
                        }
                    }

                    NotebookTab.QA -> {
                        if (filteredQa.isEmpty()) {
                            item(key = "empty-qa") {
                                EmptyNotebookCard("暂无追问记录 · 在 AI 解说底部向冬马和纱 / 小木曾雪菜提问后，对话会自动保存在这里。")
                            }
                        } else {
                            items(filteredQa, key = { "qa-${it.id}" }) { qa ->
                                val src = sentenceById[qa.explanationId]
                                val voiceRef = qa.voiceRef ?: src?.voiceRef
                                NotebookQaCard(
                                    qa = qa,
                                    onReplayVoice = if (voiceRef != null) ({ replay(voiceRef) }) else null,
                                    onOpenSentence = if (src != null) ({ ai.openFromNotebook(src) }) else null,
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

private fun exportAnkiTsv(context: Context, tsv: String) {
    runCatching {
        val cm = context.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager
        cm?.setPrimaryClip(ClipData.newPlainText("WA2 Notebook Anki TSV", tsv))
    }
    runCatching {
        val send = Intent(Intent.ACTION_SEND).apply {
            type = "text/tab-separated-values"
            putExtra(Intent.EXTRA_SUBJECT, "WHITE ALBUM 2 · AI 学习记录 (Anki TSV)")
            putExtra(Intent.EXTRA_TEXT, tsv)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        val chooser = Intent.createChooser(send, "导出 Anki TSV · Export Notebook").apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        context.startActivity(chooser)
    }
}

@Composable
private fun EmptyNotebookCard(text: String) {
    Box(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .background(NB_CARD)
            .padding(16.dp),
    ) {
        Text(text, color = Wa2Colors.TextDim, fontSize = 14.sp)
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun NotebookVocabCard(
    v: StarredVocab,
    starred: Boolean,
    sourceEntry: NotebookSentenceEntry?,
    onToggleStar: () -> Unit,
    onReplayVoice: (() -> Unit)?,
    onOpenSentence: (() -> Unit)?,
) {
    Column(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .background(NB_CARD)
            .border(1.dp, Wa2Colors.AccentDim.copy(alpha = 0.35f), RoundedCornerShape(12.dp))
            .padding(12.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            FlowRow(
                Modifier.weight(1f),
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                verticalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                val head = if (RubyBuilder.cleanReading(v.reading, v.lemma) != null) "${v.lemma}【${v.reading}】" else v.lemma
                Text(head, color = Wa2Colors.Text, fontSize = 16.sp, fontWeight = FontWeight.Medium)
                if (v.partOfSpeech.isNotBlank()) NbTag(partOfSpeechLabel(v.partOfSpeech))
                v.jlpt?.let { NbTag(it, NB_TEXTBOOK) }
                v.pitchAccent?.let { NbTag("[$it]", Color(0x33FFFFFF), Wa2Colors.TextDim) }
                if (v.surface != v.lemma) Text("（${v.surface}）", color = Wa2Colors.TextDim, fontSize = 13.sp)
            }
            if (onReplayVoice != null) NbChip("🔊 原声", onClick = onReplayVoice)
            StarButton(starred = starred, onClick = onToggleStar)
        }
        Text(v.meaning, color = Wa2Colors.Text, fontSize = 14.sp)
        v.conjugation?.takeIf { it.isNotBlank() }?.let { Text("变形：$it", color = Wa2Colors.TextDim, fontSize = 12.sp) }
        v.notes?.takeIf { it.isNotBlank() }?.let { Text("注：$it", color = Wa2Colors.TextDim, fontSize = 12.sp) }
        if (v.exampleSentence.isNotBlank()) {
            SourceSentenceRow(
                sentenceJp = v.exampleSentence,
                sentenceCn = v.officialCn ?: sourceEntry?.officialCn ?: sourceEntry?.translation ?: "",
                onClick = onOpenSentence,
            )
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun NotebookGrammarCard(
    g: StarredGrammar,
    starred: Boolean,
    sourceEntry: NotebookSentenceEntry?,
    onToggleStar: () -> Unit,
    onReplayVoice: (() -> Unit)?,
    onOpenSentence: (() -> Unit)?,
) {
    Column(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .background(NB_CARD)
            .border(1.dp, Wa2Colors.AccentDim.copy(alpha = 0.35f), RoundedCornerShape(12.dp))
            .padding(12.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            FlowRow(
                Modifier.weight(1f),
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                verticalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                Text(g.pattern, color = Wa2Colors.Text, fontSize = 16.sp, fontWeight = FontWeight.Medium)
                g.jlpt?.let { NbTag(it, NB_TEXTBOOK) }
            }
            if (onReplayVoice != null) NbChip("🔊 原声", onClick = onReplayVoice)
            StarButton(starred = starred, onClick = onToggleStar)
        }
        Text(g.explanation, color = Wa2Colors.Text, fontSize = 14.sp)
        if (g.exampleSentence.isNotBlank()) {
            SourceSentenceRow(
                sentenceJp = g.exampleSentence,
                sentenceCn = g.officialCn ?: sourceEntry?.officialCn ?: sourceEntry?.translation ?: "",
                onClick = onOpenSentence,
            )
        }
    }
}

@Composable
private fun NotebookSentenceCard(
    entry: NotebookSentenceEntry,
    starred: Boolean,
    onToggleStar: () -> Unit,
    onReplayVoice: (() -> Unit)?,
    onOpen: () -> Unit,
) {
    Column(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .background(NB_CARD)
            .border(1.dp, Wa2Colors.AccentDim.copy(alpha = 0.45f), RoundedCornerShape(12.dp))
            .clickable(onClick = onOpen)
            .padding(12.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            val speaker = listOfNotNull(entry.speakerJp, entry.speakerCn?.takeIf { it != entry.speakerJp }).joinToString(" · ")
            Text(
                speaker.ifEmpty { "（旁白 / 内心独白）" },
                color = if (speaker.isEmpty()) Wa2Colors.TextDim else Wa2Colors.Name,
                fontSize = 13.sp,
                modifier = Modifier.weight(1f),
            )
            if (entry.scene > 0) NbTag(sceneLabel(entry.scene.toString()), Color(0x33FFFFFF), Wa2Colors.TextDim)
            if (entry.vocabCount > 0 || entry.grammarCount > 0) {
                NbTag("${entry.vocabCount} 词 · ${entry.grammarCount} 语法")
            }
            if (onReplayVoice != null) NbChip("🔊 原声", onClick = onReplayVoice)
            StarButton(starred = starred, onClick = onToggleStar)
        }
        Text(entry.sentence, color = Wa2Colors.Text, fontSize = 17.sp, fontWeight = FontWeight.Medium)
        val cn = entry.officialCn ?: entry.translation
        if (cn.isNotBlank()) {
            Text(cn, color = Wa2Colors.TextDim, fontSize = 14.sp)
        }
        Text("点击打开完整 AI 解说与追问 →", color = Wa2Colors.Accent, fontSize = 12.sp)
    }
}

@Composable
private fun NotebookQaCard(
    qa: NotebookQaEntry,
    onReplayVoice: (() -> Unit)?,
    onOpenSentence: (() -> Unit)?,
) {
    Column(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .background(NB_CARD)
            .border(1.dp, Wa2Colors.AccentDim.copy(alpha = 0.4f), RoundedCornerShape(12.dp))
            .then(if (onOpenSentence != null) Modifier.clickable(onClick = onOpenSentence) else Modifier)
            .padding(12.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            Text(
                qa.sentence,
                color = Wa2Colors.Name,
                fontSize = 15.sp,
                fontWeight = FontWeight.Medium,
                modifier = Modifier.weight(1f),
            )
            if (onReplayVoice != null) NbChip("🔊 原声", onClick = onReplayVoice)
        }
        Row(Modifier.fillMaxWidth()) {
            Text("问：", color = Wa2Colors.Accent, fontSize = 13.sp, fontWeight = FontWeight.Bold)
            Text(qa.question, color = Wa2Colors.Text, fontSize = 13.sp)
        }
        Row(Modifier.fillMaxWidth()) {
            Text("答：", color = NB_TEXTBOOK, fontSize = 13.sp, fontWeight = FontWeight.Bold)
            Text(qa.answer, color = Wa2Colors.Text, fontSize = 13.sp)
        }
    }
}

@Composable
private fun SourceSentenceRow(sentenceJp: String, sentenceCn: String, onClick: (() -> Unit)?) {
    Column(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(8.dp))
            .background(Color(0x18FFFFFF))
            .then(if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier)
            .padding(horizontal = 10.dp, vertical = 6.dp),
        verticalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        Text("来源句：$sentenceJp", color = Wa2Colors.Accent, fontSize = 12.sp)
        if (sentenceCn.isNotBlank()) {
            Text(sentenceCn, color = Wa2Colors.TextDim, fontSize = 11.sp)
        }
    }
}

@Composable
fun StarButton(starred: Boolean, onClick: () -> Unit) {
    val shape = RoundedCornerShape(14.dp)
    Box(
        Modifier
            .heightIn(min = 32.dp)
            .clip(shape)
            .background(if (starred) NB_STAR.copy(alpha = 0.22f) else Color(0x26203050))
            .border(1.dp, if (starred) NB_STAR else Wa2Colors.AccentDim.copy(alpha = 0.5f), shape)
            .clickable(onClick = onClick)
            .padding(horizontal = 10.dp, vertical = 4.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            if (starred) "★ 已收藏" else "☆ 收藏",
            color = if (starred) NB_STAR else Wa2Colors.TextDim,
            fontSize = 12.sp,
            maxLines = 1,
        )
    }
}

@Composable
private fun NbChip(
    label: String,
    selected: Boolean = false,
    enabled: Boolean = true,
    color: Color = Wa2Colors.Accent,
    onClick: () -> Unit,
) {
    val shape = RoundedCornerShape(16.dp)
    Box(
        Modifier
            .heightIn(min = 34.dp)
            .clip(shape)
            .background(if (selected) color else Color(0x33203050))
            .border(1.dp, if (selected) color else Wa2Colors.AccentDim, shape)
            .clickable(enabled = enabled, onClick = onClick)
            .padding(horizontal = 11.dp, vertical = 5.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            label,
            color = if (selected) Wa2Colors.Night else if (enabled) Wa2Colors.Text else Wa2Colors.TextDim,
            fontSize = 12.sp,
            maxLines = 1,
        )
    }
}

@Composable
private fun NbTag(label: String, color: Color = Wa2Colors.AccentDim, textColor: Color = Wa2Colors.Text) {
    Text(
        label,
        color = textColor,
        fontSize = 11.sp,
        maxLines = 1,
        modifier = Modifier
            .clip(RoundedCornerShape(6.dp))
            .background(color.copy(alpha = 0.35f))
            .padding(horizontal = 6.dp, vertical = 2.dp),
    )
}
