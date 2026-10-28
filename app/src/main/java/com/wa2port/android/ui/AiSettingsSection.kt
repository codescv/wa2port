package com.wa2port.android.ui

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.wa2port.android.ai.AiLearningController
import com.wa2port.android.ai.NotebookOverlay
import com.wa2port.android.data.Settings
import com.wa2port.android.game.GameSession
import com.wa2port.core.ai.AiEndpoints
import com.wa2port.core.ai.AiPersonaSettings
import com.wa2port.core.ai.AiProviderSettings
import com.wa2port.core.ai.ServiceAccountJwt
import java.util.Locale

private val OK_GREEN = Color(0xFF8FD3A8)
private val ERR_RED = Color(0xFFFF8A80)

/**
 * 设置 → AI 学习: provider (Gemini API Key / Vertex AI), API key, 自定义服务器地址 (custom base URL /
 * reverse proxy) with proxy auth style and extra headers, Vertex project / location / auth, model,
 * tutor persona, tap-to-analyze & cache switches, generation tuning, 测试连接 / 清除缓存 / 示例预览.
 * Every edit is saved immediately through [onChange], except the multi-line secrets (SA JSON, extra
 * headers) which are committed on focus loss or 保存 to avoid a disk write per keystroke.
 */
@Composable
fun AiSettingsSection(
    settings: Settings,
    onChange: ((Settings) -> Settings) -> Unit,
    ai: AiLearningController,
    session: GameSession?,
) {
    val p = settings.ai
    val focus = LocalFocusManager.current
    fun set(f: (AiProviderSettings) -> AiProviderSettings) = onChange { it.copy(ai = f(it.ai)) }
    LaunchedEffect(Unit) { ai.refreshCacheStats() }

    SectionTitle("AI 学习 AI Learning")
    OptionRow("服务 Provider") {
        Segmented(
            listOf(AiProviderSettings.PROVIDER_GEMINI_API to "Gemini API Key", AiProviderSettings.PROVIDER_VERTEX_AI to "Vertex AI"),
            p.providerType, { v -> set { it.copy(providerType = v) } },
        )
    }

    if (!p.isVertex) {
        AiTextField("API Key", p.geminiApiKey, { v -> set { it.copy(geminiApiKey = v) } }, placeholder = "AIza…", secret = true)
        AiTextField(
            "自定义服务器地址 Custom base URL / 代理", p.customBaseUrl, { v -> set { it.copy(customBaseUrl = v) } },
            placeholder = AiEndpoints.DEFAULT_GEMINI_BASE,
            helper = "留空 = 官方地址。反代示例：https://my-proxy.example.com 或 …/v1beta（版本自动去重）；仅支持 https（本机 / 模拟器 10.0.2.2 可用 http）",
            keyboardType = KeyboardType.Uri,
        )
        OptionRow("API 版本 Version") {
            Segmented(AiProviderSettings.API_VERSIONS.map { it to it }, p.geminiApiVersion, { v -> set { it.copy(geminiApiVersion = v) } })
            AiEndpoints.pastedVersion(p.customBaseUrl)?.let { v ->
                Text("  地址中的 /$v 优先", color = Wa2Colors.TextDim, fontSize = 12.sp)
            }
        }
        if (p.customBaseUrl.isNotBlank()) {
            OptionRow("代理鉴权 Proxy auth") {
                Segmented(
                    listOf(
                        AiProviderSettings.AUTH_STYLE_HEADER to "x-goog-api-key 请求头",
                        AiProviderSettings.AUTH_STYLE_BEARER to "Bearer",
                        AiProviderSettings.AUTH_STYLE_QUERY to "?key= 查询参数",
                    ),
                    p.proxyAuthStyle, { v -> set { it.copy(proxyAuthStyle = v) } },
                    Modifier.horizontalScroll(rememberScrollState()),
                )
            }
        }
    } else {
        AiTextField("项目 Project", p.vertexProject, { v -> set { it.copy(vertexProject = v) } }, placeholder = "my-gcp-project")
        OptionRow("区域 Location") {
            Segmented(
                AiProviderSettings.VERTEX_LOCATION_PRESETS.map { it to it }, p.vertexLocation, { v -> set { it.copy(vertexLocation = v) } },
                Modifier.horizontalScroll(rememberScrollState()),
            )
        }
        AiTextField("区域 Location（可自定义）", p.vertexLocation, { v -> set { it.copy(vertexLocation = v.trim()) } }, placeholder = "global / us-east5 …")
        OptionRow("鉴权 Auth") {
            Segmented(
                listOf(
                    AiProviderSettings.VERTEX_AUTH_SERVICE_ACCOUNT to "服务账号 JSON",
                    AiProviderSettings.VERTEX_AUTH_ACCESS_TOKEN to "Access Token",
                    AiProviderSettings.VERTEX_AUTH_EXPRESS to "Express API Key",
                ),
                p.vertexAuthMode, { v -> set { it.copy(vertexAuthMode = v) } },
                Modifier.horizontalScroll(rememberScrollState()),
            )
        }
        when (p.vertexAuthMode) {
            AiProviderSettings.VERTEX_AUTH_ACCESS_TOKEN ->
                AiTextField("Access Token", p.vertexAccessToken, { v -> set { it.copy(vertexAccessToken = v) } }, secret = true, helper = "gcloud auth print-access-token（约 1 小时过期）")
            AiProviderSettings.VERTEX_AUTH_EXPRESS ->
                AiTextField("Express API Key", p.vertexApiKey, { v -> set { it.copy(vertexApiKey = v) } }, secret = true)
            else -> ServiceAccountField(p.vertexServiceAccountJson) { v -> set { it.copy(vertexServiceAccountJson = v) } }
        }
        val defaultHost = AiEndpoints.vertexBase(p.copy(customBaseUrl = ""))
        AiTextField(
            "自定义服务器地址 Custom base URL / 代理", p.customBaseUrl, { v -> set { it.copy(customBaseUrl = v) } },
            placeholder = defaultHost,
            helper = "默认 $defaultHost" + if (p.vertexAuthMode == AiProviderSettings.VERTEX_AUTH_SERVICE_ACCOUNT && p.customBaseUrl.isNotBlank()) {
                "。注意：服务账号换取令牌需直连 oauth2.googleapis.com；走代理时建议用 Access Token / Express Key 或由代理注入鉴权"
            } else {
                ""
            },
            keyboardType = KeyboardType.Uri,
        )
    }
    ExtraHeadersField(p.extraHeaders) { v -> set { it.copy(extraHeaders = v) } }

    // Effective endpoint (secrets redacted) + http warning: makes proxy typos visible before testing.
    val endpoint = runCatching { AiEndpoints.redactUrl(AiEndpoints.generateContentUrl(p)) }.getOrDefault("")
    Text("请求地址 Endpoint: $endpoint", color = Wa2Colors.TextDim, fontSize = 11.sp, modifier = Modifier.padding(vertical = 2.dp))
    p.connectionWarning()?.let { Text("⚠ $it", color = ERR_RED, fontSize = 12.sp) }

    OptionRow("模型 Model") {
        Segmented(
            AiProviderSettings.MODEL_PRESETS.map { it to it }, p.model, { v -> set { it.copy(model = v) } },
            Modifier.horizontalScroll(rememberScrollState()),
        )
    }
    AiTextField("自定义模型 Custom model", p.model, { v -> set { it.copy(model = v.trim()) } }, placeholder = AiProviderSettings.DEFAULT_MODEL)

    val persona = ai.persona
    OptionRow("家教人设 Persona") {
        Segmented(
            ai.personas.map { p -> p.id to (if (p.id == AiPersonaSettings.ID_CUSTOM) "自定义 (${p.shortName})" else p.shortName) },
            persona.id, { v -> onChange { it.copy(aiPersonaId = v) } },
        )
    }
    if (persona.id == AiPersonaSettings.ID_CUSTOM) {
        // User-defined tutor: name + soul (blank → defaults, see AiPersonaSettings.custom).
        AiTextField(
            "自定义名称 Custom name", settings.aiCustomPersonaName, { v -> onChange { it.copy(aiCustomPersonaName = v) } },
            placeholder = AiPersonaSettings.DEFAULT_CUSTOM_NAME,
        )
        DraftField(
            "自定义 Soul / 人设 Custom soul", settings.aiCustomPersonaSoul, AiPersonaSettings.DEFAULT_CUSTOM_SOUL,
            { v -> onChange { it.copy(aiCustomPersonaSoul = v) } },
        )
    } else {
        Text(persona.soul, color = Wa2Colors.TextDim, fontSize = 12.sp)
    }

    OptionRow("点词解析 Tap-to-analyze") {
        Segmented(listOf(false to "关 Off", true to "开 On（ADV 日文列）"), settings.aiTapToAnalyze, { v -> onChange { it.copy(aiTapToAnalyze = v) } })
    }
    OptionRow("缓存解说 Cache") {
        Segmented(listOf(true to "开 On", false to "关 Off"), p.cacheEnabled, { v -> set { it.copy(cacheEnabled = v) } })
    }
    OptionRow("⚡ 智能预解析（学习突发期 10 句内后台预取）") {
        Segmented(
            listOf(false to "关 Off", true to "开 On"),
            settings.aiPrefetchEnabled,
            { v -> onChange { it.copy(aiPrefetchEnabled = v) } },
        )
    }
    Text(
        "全周目全量预取约 $100–300，因此仅在手动打开 AI 后的 10 句学习突发期内且针对未读行后台预取。" +
            "（本次会话预取 ${ai.prefetchedCount} 句 · 命中 ${ai.prefetchHitCount} 次）",
        color = Wa2Colors.TextDim,
        fontSize = 11.sp,
        modifier = Modifier.padding(start = 4.dp, bottom = 4.dp),
    )
    LabeledSlider("温度 Temperature", Math.round(p.temperature * 10), 0..10, "%.1f".format(Locale.ROOT, p.temperature)) { v -> set { it.copy(temperature = v / 10f) } }
    OptionRow("思考预算 Thinking") {
        Segmented(
            listOf(-1 to "默认", 0 to "关闭 0", 512 to "512", 2048 to "2048", 8192 to "8192"),
            p.thinkingBudget, { v -> set { it.copy(thinkingBudget = v) } },
            Modifier.horizontalScroll(rememberScrollState()),
        )
    }
    LabeledSlider("超时 Timeout", p.timeoutMs / 1000, 10..180, "${p.timeoutMs / 1000} s") { v -> set { it.copy(timeoutMs = v * 1000) } }

    // Test result is placed immediately above the action row so it is never hidden below the fold.
    ai.testResult?.let { r ->
        Text(
            if (r.ok) "✓ ${r.model} · ${r.latencyMs} ms · ${r.message}" else "✗ ${r.message}",
            color = if (r.ok) OK_GREEN else ERR_RED, fontSize = 13.sp, modifier = Modifier.padding(top = 4.dp, bottom = 2.dp),
        )
    }
    // Actions.
    var confirmClear by remember { mutableStateOf(false) }
    Row(
        Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(vertical = 6.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically,
    ) {
        // Clearing focus first commits any open multi-line draft (SA JSON / headers) into settings.
        MenuButton(if (ai.testing) "测试中… Testing" else "测试连接 Test", enabled = !ai.testing) {
            focus.clearFocus()
            ai.testConnection()
        }
        val stats = ai.cacheStats
        MenuButton("清除缓存 Clear cache", subtitle = stats?.let { "${it.entries} 条 · ${(it.bytes + 1023) / 1024} KB" }) { confirmClear = true }
        MenuButton("示例预览 Preview") {
            focus.clearFocus()
            ai.openSample(session)
        }
        val nbRev = ai.notebookRevision
        val nbSentenceCount = remember(nbRev) { ai.notebook.sentences().size }
        val nbStarCount = remember(nbRev) {
            val s = ai.notebook.stars()
            s.vocab.size + s.grammar.size + s.sentences.size
        }
        MenuButton("📒 学习记录 Notebook", subtitle = "$nbSentenceCount 句 · ★ $nbStarCount") {
            focus.clearFocus()
            ai.openNotebook(session)
        }
    }
    if (ai.notebookOpen && ai.breakdown == null) {
        Dialog(
            onDismissRequest = { ai.closeNotebook(session) },
            properties = DialogProperties(usePlatformDefaultWidth = false),
        ) {
            NotebookOverlay(
                ai = ai,
                onClose = { ai.closeNotebook(session) },
            )
        }
    }
    if (confirmClear) {
        AlertDialog(
            onDismissRequest = { confirmClear = false },
            confirmButton = { TextButton(onClick = { confirmClear = false; ai.clearCache() }) { Text("清除 Clear") } },
            dismissButton = { TextButton(onClick = { confirmClear = false }) { Text("取消 Cancel") } },
            text = { Text("删除所有已缓存的 AI 解说与追问记录？（注：📒 学习记录与收藏不会受影响）\nDelete all cached breakdowns? (Your Notebook & Stars are preserved.)") },
        )
    }
}

/** Single-line text field in the config style ([secret] → masked with a 👁 toggle). */
@Composable
private fun AiTextField(
    label: String,
    value: String,
    onValue: (String) -> Unit,
    placeholder: String = "",
    helper: String? = null,
    secret: Boolean = false,
    keyboardType: KeyboardType = KeyboardType.Text,
) {
    var reveal by remember { mutableStateOf(false) }
    Column(Modifier.fillMaxWidth().padding(vertical = 2.dp)) {
        OutlinedTextField(
            value = value,
            onValueChange = onValue,
            modifier = Modifier.fillMaxWidth(),
            singleLine = true,
            label = { Text(label, fontSize = 12.sp) },
            placeholder = { Text(placeholder, fontSize = 13.sp, color = Wa2Colors.TextDim) },
            visualTransformation = if (secret && !reveal) PasswordVisualTransformation() else VisualTransformation.None,
            trailingIcon = if (secret) {
                { TextButton(onClick = { reveal = !reveal }) { Text(if (reveal) "隐藏" else "👁", color = Wa2Colors.Accent) } }
            } else {
                null
            },
            keyboardOptions = KeyboardOptions(keyboardType = if (secret) KeyboardType.Password else keyboardType),
            colors = fieldColors(),
        )
        helper?.let { Text(it, color = Wa2Colors.TextDim, fontSize = 11.sp, modifier = Modifier.padding(start = 4.dp, top = 2.dp)) }
    }
}

/** Multi-line draft field committed on focus loss or 保存. */
@Composable
private fun DraftField(label: String, value: String, placeholder: String, onCommit: (String) -> Unit, footer: @Composable (String) -> Unit = {}) {
    var draft by remember(value) { mutableStateOf(value) }
    // Commit a pending draft when the field leaves composition (Config closed while editing).
    val latestDraft = rememberUpdatedState(draft)
    val latestValue = rememberUpdatedState(value)
    val latestCommit = rememberUpdatedState(onCommit)
    DisposableEffect(Unit) {
        onDispose { if (latestDraft.value != latestValue.value) latestCommit.value(latestDraft.value) }
    }
    Column(Modifier.fillMaxWidth().padding(vertical = 2.dp)) {
        OutlinedTextField(
            value = draft,
            onValueChange = { draft = it },
            modifier = Modifier.fillMaxWidth().heightIn(min = 96.dp).onFocusChanged { if (!it.isFocused && draft != value) onCommit(draft) },
            label = { Text(label, fontSize = 12.sp) },
            placeholder = { Text(placeholder, fontSize = 12.sp, color = Wa2Colors.TextDim) },
            maxLines = 6,
            colors = fieldColors(),
        )
        Row(verticalAlignment = Alignment.CenterVertically) {
            footer(draft)
            if (draft != value) TextButton(onClick = { onCommit(draft) }) { Text("保存 Save", color = Wa2Colors.Accent) }
        }
    }
}

@Composable
private fun ServiceAccountField(json: String, onCommit: (String) -> Unit) {
    DraftField("服务账号 JSON Service account key", json, "{\"type\": \"service_account\", \"client_email\": …, \"private_key\": …}", onCommit) { draft ->
        if (draft.isNotBlank()) {
            val parsed = runCatching { ServiceAccountJwt.parse(draft) }
            Text(
                parsed.fold({ "✓ ${it.clientEmail}" }, { "✗ ${it.message}" }),
                color = if (parsed.isSuccess) OK_GREEN else ERR_RED, fontSize = 12.sp, modifier = Modifier.padding(start = 4.dp),
            )
        }
    }
}

@Composable
private fun ExtraHeadersField(value: String, onCommit: (String) -> Unit) {
    var open by remember { mutableStateOf(value.isNotBlank()) }
    TextButton(onClick = { open = !open }) {
        Text((if (open) "▼" else "▶") + " 高级：额外请求头 Extra headers", color = Wa2Colors.Accent, fontSize = 13.sp)
    }
    if (open) {
        DraftField("每行一个 Name: value（可用 {apiKey} 占位）", value, "X-Proxy-Token: {apiKey}", onCommit) { draft ->
            val n = AiEndpoints.parseExtraHeaders(draft, "").size
            val lines = draft.lines().count { it.isNotBlank() }
            if (lines > 0) Text("$n / $lines 行有效", color = if (n == lines) OK_GREEN else ERR_RED, fontSize = 12.sp, modifier = Modifier.padding(start = 4.dp))
        }
    }
}

@Composable
private fun fieldColors() = OutlinedTextFieldDefaults.colors(
    focusedTextColor = Wa2Colors.Text, unfocusedTextColor = Wa2Colors.Text,
    focusedBorderColor = Wa2Colors.Accent, unfocusedBorderColor = Wa2Colors.AccentDim,
    focusedLabelColor = Wa2Colors.Accent, unfocusedLabelColor = Wa2Colors.TextDim,
    cursorColor = Wa2Colors.Accent,
)
