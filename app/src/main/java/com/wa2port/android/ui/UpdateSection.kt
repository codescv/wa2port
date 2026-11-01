package com.wa2port.android.ui

import android.app.Activity
import android.content.ActivityNotFoundException
import android.content.Intent
import android.net.Uri
import android.os.Build
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.wa2port.android.data.Settings
import com.wa2port.android.game.GameSession
import com.wa2port.android.update.UpdateController
import com.wa2port.android.update.UpdateState
import com.wa2port.core.update.AgentFeedbackHelper
import com.wa2port.core.update.FeedbackGameContext
import com.wa2port.core.update.ReleaseInfo
import java.util.Locale

private val UPDATE_ERR = Color(0xFFFFB0A0)
private val UPDATE_OK = Color(0xFF8FD3A8)

/**
 * In-layout update overlay for the title screen (no window `Dialog`: a new window would steal focus
 * and flash the hidden system bars). Tapping the scrim dismisses it, except while downloading.
 */
@Composable
fun UpdateDialog(
    update: UpdateController,
    onDismiss: () -> Unit,
    onOpenFeedback: (() -> Unit)? = null,
) {
    val noRipple = remember { MutableInteractionSource() }
    Box(
        Modifier
            .fillMaxSize()
            .background(Color(0xAA000000))
            .clickable(interactionSource = noRipple, indication = null) {
                if (update.state !is UpdateState.Downloading) onDismiss()
            },
        contentAlignment = Alignment.Center,
    ) {
        val shape = RoundedCornerShape(14.dp)
        Column(
            Modifier
                .padding(24.dp)
                .widthIn(max = 560.dp)
                .fillMaxWidth()
                .clip(shape)
                .background(Wa2Colors.Panel)
                .border(BorderStroke(1.dp, Wa2Colors.AccentDim), shape)
                // Swallow taps so they do not reach the scrim.
                .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null) {}
                .padding(horizontal = 20.dp, vertical = 12.dp),
        ) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text("版本更新 App Update", color = Wa2Colors.Text, fontSize = 20.sp, modifier = Modifier.weight(1f))
                CloseButton(onDismiss)
            }
            Text("当前版本 Current: v${update.currentVersion}", color = Wa2Colors.TextDim, fontSize = 12.sp)
            Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState())) {
                UpdateStatusBlock(update, compact = false, onDismiss = onDismiss)
                if (onOpenFeedback != null) {
                    Row(Modifier.fillMaxWidth().padding(top = 4.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        UpdateButton("发送反馈给 Agent", onClick = onOpenFeedback)
                    }
                }
            }
        }
    }
}

/** Title-screen pill: shows the update state at a glance and opens [UpdateDialog]. */
@Composable
fun UpdatePill(update: UpdateController, onClick: () -> Unit) {
    val s = update.state
    val highlight = s is UpdateState.Available || s is UpdateState.Downloaded
    val label = when (s) {
        UpdateState.Idle, is UpdateState.Error -> "检查更新 Update"
        UpdateState.Checking -> "检查中… Checking"
        is UpdateState.UpToDate -> "已是最新 ✓ Up to date"
        is UpdateState.Available -> "发现新版本 v${s.latest.version} · 点击下载"
        is UpdateState.Downloading -> "下载中 Downloading ${s.fraction?.let { "${Math.round(it * 100)}%" } ?: mb(s.downloadedBytes)}"
        is UpdateState.Downloaded -> "安装 v${s.latest.version} Install"
    }
    val shape = RoundedCornerShape(18.dp)
    Box(
        Modifier
            .heightIn(min = 36.dp)
            .clip(shape)
            .background(if (highlight) Wa2Colors.Accent else Wa2Colors.Button)
            .border(BorderStroke(1.dp, Wa2Colors.AccentDim), shape)
            .clickable(onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 6.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(label, color = if (highlight) Wa2Colors.Night else Wa2Colors.Text, fontSize = 13.sp, fontWeight = FontWeight.Medium)
    }
}

/**
 * The per-state body shared by the dialog and the config section: progress, release notes and the
 * actions available in that state. [compact] (config) omits the long release notes.
 */
@Composable
fun UpdateStatusBlock(update: UpdateController, compact: Boolean, onDismiss: (() -> Unit)? = null) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                update.onHostResume(context)
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }
    val actions = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(vertical = 6.dp)
    Column(Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
        when (val s = update.state) {
            UpdateState.Idle -> if (!compact) {
                Text("点击「检查更新」从 GitHub 获取最新版本\nTap Check to look for a newer release", color = Wa2Colors.TextDim, fontSize = 13.sp)
                Row(actions, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    UpdateButton("检查更新 Check", primary = true) { update.checkForUpdates() }
                }
            }
            UpdateState.Checking -> {
                LinearProgressIndicator(Modifier.fillMaxWidth().padding(vertical = 6.dp), color = Wa2Colors.Accent, trackColor = Wa2Colors.Button)
                Text("正在检查更新… Checking for updates", color = Wa2Colors.TextDim, fontSize = 13.sp)
                Row(actions) { UpdateButton("取消 Cancel") { update.cancelDownload() } }
            }
            is UpdateState.UpToDate -> {
                Text("✓ 已是最新版本 v${update.currentVersion} Up to date", color = UPDATE_OK, fontSize = 14.sp)
                Text("最新发布 Latest release: ${s.latest.tagName}", color = Wa2Colors.TextDim, fontSize = 12.sp)
                Row(actions, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    UpdateButton("重新检查 Check again") { update.checkForUpdates() }
                    UpdateButton("浏览器打开 GitHub") { update.openInBrowser(context) }
                }
            }
            is UpdateState.Available -> {
                Text("发现新版本 New version: v${s.latest.version}", color = Wa2Colors.Accent, fontSize = 16.sp, fontWeight = FontWeight.Bold)
                ReleaseMeta(s.latest)
                if (!compact) ReleaseNotes(s.latest)
                if (s.latest.apkAsset == null) {
                    Text("该版本没有 APK 附件 This release has no APK", color = UPDATE_ERR, fontSize = 13.sp)
                }
                Row(actions, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    if (s.latest.apkAsset != null) {
                        UpdateButton(if (compact) "下载并安装 Download & Install" else "立即下载并安装 Download & Install", primary = true) {
                            update.downloadUpdate(context)
                        }
                    }
                    UpdateButton("浏览器下载 Open in browser") { update.openInBrowser(context) }
                    if (onDismiss != null) UpdateButton("取消 Later") { onDismiss() }
                }
            }
            is UpdateState.Downloading -> {
                val f = s.fraction
                if (f != null) {
                    LinearProgressIndicator(progress = { f }, modifier = Modifier.fillMaxWidth().padding(vertical = 6.dp), color = Wa2Colors.Accent, trackColor = Wa2Colors.Button)
                } else {
                    LinearProgressIndicator(Modifier.fillMaxWidth().padding(vertical = 6.dp), color = Wa2Colors.Accent, trackColor = Wa2Colors.Button)
                }
                Text(
                    "下载 v${s.latest.version} · ${mb(s.downloadedBytes)}" +
                        (if (s.totalBytes > 0) " / ${mb(s.totalBytes)}" else "") +
                        (f?.let { " · ${Math.round(it * 100)}%" } ?: ""),
                    color = Wa2Colors.Text, fontSize = 13.sp,
                )
                Row(actions) { UpdateButton("取消下载 Cancel") { update.cancelDownload() } }
            }
            is UpdateState.Downloaded -> {
                Text("✓ v${s.latest.version} 下载完成，点击安装 Ready to install", color = UPDATE_OK, fontSize = 14.sp)
                Text("安装时系统会请求「安装未知应用」权限；更新会保留存档与设置。", color = Wa2Colors.TextDim, fontSize = 12.sp)
                if (!compact) ReleaseNotes(s.latest)
                Row(actions, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    UpdateButton("安装 APK Install", primary = true) { update.installApk(context) }
                    if (s.latest.apkAsset != null) {
                        UpdateButton("重新下载 Re-download") { update.downloadUpdate(context) }
                    } else {
                        UpdateButton("检查更新 Check") { update.checkForUpdates() }
                    }
                }
            }
            is UpdateState.Error -> {
                Text("✗ ${s.message}", color = UPDATE_ERR, fontSize = 13.sp)
                Row(actions, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    UpdateButton("重试 Retry", primary = true) {
                        if (s.lastRelease?.apkAsset != null) update.downloadUpdate(context) else update.checkForUpdates()
                    }
                    UpdateButton("浏览器打开 GitHub") { update.openInBrowser(context) }
                }
            }
        }
    }
}

/** 设置 → 版本与更新: current version + 检查更新 and the shared status block (GitHub releases only). */
@Composable
fun UpdateSettingsSection(update: UpdateController) {
    SectionTitle("版本与更新 App Update")
    OptionRow("当前版本 Version") {
        Text("v${update.currentVersion}", color = Wa2Colors.Text, fontSize = 15.sp, modifier = Modifier.padding(end = 12.dp))
        UpdateButton(if (update.state is UpdateState.Checking) "检查中… Checking" else "检查更新 Check Update", enabled = !update.busy) {
            update.checkForUpdates()
        }
    }
    UpdateStatusBlock(update, compact = true)
}

/**
 * In-layout Agent feedback section (no window `AlertDialog` so immersive system bars never flash):
 * lets the user configure their **Agent 反馈邮箱** locally (default `""`, placeholder
 * `agent@example.com`), write feedback text, optionally attach the active WA2 scene/line context,
 * and click **发送反馈给 Agent** (`Intent.ACTION_SENDTO` `mailto:` with subject
 * `wa2port: [v1.4.5] <summary>`).
 */
@Composable
fun AgentFeedbackSection(
    settings: Settings,
    onChange: ((Settings) -> Settings) -> Unit,
    currentVersion: String = AgentFeedbackHelper.DEFAULT_APP_VERSION,
    session: GameSession? = null,
) {
    val context = LocalContext.current
    val focusManager = LocalFocusManager.current
    val resolvedVersion = AgentFeedbackHelper.normalizeVersion(currentVersion)
    val email = settings.agentFeedbackEmail
    val emailError = AgentFeedbackHelper.validateEmail(email)

    var feedbackText by rememberSaveable { mutableStateOf("") }
    var includeGameContext by rememberSaveable { mutableStateOf(true) }
    var statusMessage by remember { mutableStateOf<Pair<String, Boolean>?>(null) }

    val gameContext = remember(
        session,
        session?.engine?.scene,
        session?.line,
        session?.inReplayMode,
        settings.windowMode,
        settings.lang,
        settings.textScale,
    ) {
        session?.let { s ->
            FeedbackGameContext.fromDialogue(
                scene = s.engine.scene,
                line = s.line,
                decoder = s.engine.decoder,
                sceneTitle = sceneLabel(s.engine.scene),
                windowMode = settings.windowMode.label,
                displayLang = settings.lang.label,
                textScalePercent = settings.textScale,
                inReplayMode = s.inReplayMode,
            )
        }
    }

    SectionTitle("Agent 反馈 Agent Feedback")
    Text(
        "在本机配置 Agent 反馈邮箱后，可一键通过邮件发送问题报告或新功能需求（主题自动使用 wa2port: [v$resolvedVersion] 前缀）。",
        color = Wa2Colors.TextDim,
        fontSize = 12.sp,
        modifier = Modifier.padding(bottom = 4.dp),
    )

    OutlinedTextField(
        value = email,
        onValueChange = { next ->
            statusMessage = null
            onChange { it.copy(agentFeedbackEmail = AgentFeedbackHelper.normalizeEmail(next)) }
        },
        modifier = Modifier.fillMaxWidth().padding(vertical = 2.dp),
        singleLine = true,
        label = { Text("Agent 反馈邮箱 Agent Email", fontSize = 12.sp) },
        placeholder = { Text(AgentFeedbackHelper.PLACEHOLDER_EMAIL, fontSize = 13.sp, color = Wa2Colors.TextDim) },
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Email, imeAction = ImeAction.Next),
        colors = feedbackFieldColors(),
    )
    if (email.isNotEmpty()) {
        Text(
            if (emailError == null) "✓ 已保存在本机：$email" else "✗ $emailError",
            color = if (emailError == null) UPDATE_OK else UPDATE_ERR,
            fontSize = 11.sp,
            modifier = Modifier.padding(start = 4.dp, bottom = 2.dp),
        )
    } else {
        Text(
            "仅保存在设备本地（默认留空，示例格式：${AgentFeedbackHelper.PLACEHOLDER_EMAIL}）",
            color = Wa2Colors.TextDim,
            fontSize = 11.sp,
            modifier = Modifier.padding(start = 4.dp, bottom = 2.dp),
        )
    }

    if (gameContext != null && gameContext.scene.isNotBlank()) {
        OptionRow("场景上下文 Scene context") {
            Segmented(
                options = listOf(true to "附带 On", false to "不附带 Off"),
                selected = includeGameContext,
                onSelect = { includeGameContext = it },
            )
            Text(
                "  ${gameContext.summaryBadge()}",
                color = Wa2Colors.TextDim,
                fontSize = 12.sp,
            )
        }
    }

    OutlinedTextField(
        value = feedbackText,
        onValueChange = {
            feedbackText = it
            statusMessage = null
        },
        modifier = Modifier.fillMaxWidth().heightIn(min = 96.dp).padding(vertical = 2.dp),
        label = { Text("反馈内容 Feedback / Bug / 需求描述", fontSize = 12.sp) },
        placeholder = {
            Text(
                "输入遇到的问题、排版异常或希望 Agent 自动开发的新功能（打开邮件客户端后也可添加截图附件）…",
                fontSize = 12.sp,
                color = Wa2Colors.TextDim,
            )
        },
        maxLines = 6,
        colors = feedbackFieldColors(),
    )

    val previewSubject = AgentFeedbackHelper.buildSubject(feedbackText = feedbackText, version = resolvedVersion)
    Text(
        "邮件主题 Subject: $previewSubject",
        color = Wa2Colors.TextDim,
        fontSize = 11.sp,
        modifier = Modifier.padding(start = 4.dp, top = 2.dp, bottom = 4.dp),
    )

    statusMessage?.let { (msg, isError) ->
        Text(
            (if (isError) "✗ " else "✓ ") + msg,
            color = if (isError) UPDATE_ERR else UPDATE_OK,
            fontSize = 13.sp,
            modifier = Modifier.padding(vertical = 2.dp),
        )
    }

    val canSend = emailError == null && feedbackText.isNotBlank()
    Row(
        Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(vertical = 6.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        UpdateButton("发送反馈给 Agent", primary = canSend) {
            focusManager.clearFocus()
            if (emailError != null) {
                statusMessage = emailError to true
                return@UpdateButton
            }
            if (feedbackText.isBlank()) {
                statusMessage = "请先输入反馈内容 Please enter feedback text" to true
                return@UpdateButton
            }
            val deviceInfo = "${Build.MANUFACTURER} ${Build.MODEL} (Android ${Build.VERSION.RELEASE}, SDK ${Build.VERSION.SDK_INT})"
            val mail = AgentFeedbackHelper.prepareMail(
                email = email,
                feedbackText = feedbackText,
                version = resolvedVersion,
                deviceInfo = deviceInfo,
                gameContext = if (includeGameContext) gameContext else null,
            )
            val intent = Intent(Intent.ACTION_SENDTO, Uri.parse(mail.mailtoUri)).apply {
                if (context !is Activity) addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            try {
                context.startActivity(intent)
                statusMessage = "已唤起邮件客户端发送至 ${mail.toEmail} Opened email app" to false
            } catch (_: ActivityNotFoundException) {
                statusMessage = "未找到可用的邮件客户端 No email app found" to true
            } catch (e: Exception) {
                statusMessage = "无法打开邮件客户端 Cannot open email app: ${e.message ?: e.javaClass.simpleName}" to true
            }
        }
        if (feedbackText.isNotEmpty()) {
            UpdateButton("清空 Clear") {
                feedbackText = ""
                statusMessage = null
            }
        }
    }
}

@Composable
private fun feedbackFieldColors() = OutlinedTextFieldDefaults.colors(
    focusedTextColor = Wa2Colors.Text,
    unfocusedTextColor = Wa2Colors.Text,
    focusedBorderColor = Wa2Colors.Accent,
    unfocusedBorderColor = Wa2Colors.AccentDim,
    focusedLabelColor = Wa2Colors.Accent,
    unfocusedLabelColor = Wa2Colors.TextDim,
    cursorColor = Wa2Colors.Accent,
)

@Composable
private fun ReleaseMeta(release: ReleaseInfo) {
    val parts = buildList {
        release.apkAsset?.let { a -> add(a.name + if (a.sizeBytes > 0) " · ${mb(a.sizeBytes)}" else "") }
        release.publishedAt?.takeIf { it.length >= 10 }?.let { add("发布于 ${it.substring(0, 10)}") }
        if (release.isPreRelease) add("预发布 pre-release")
    }
    if (parts.isNotEmpty()) Text(parts.joinToString("  ·  "), color = Wa2Colors.TextDim, fontSize = 12.sp)
}

@Composable
private fun ReleaseNotes(release: ReleaseInfo) {
    val body = release.body.trim()
    if (body.isEmpty()) return
    Column(
        Modifier
            .fillMaxWidth()
            .padding(vertical = 6.dp)
            .heightIn(max = 180.dp)
            .clip(RoundedCornerShape(8.dp))
            .background(Color(0x33000000))
            .verticalScroll(rememberScrollState())
            .padding(10.dp),
    ) {
        Text(release.title, color = Wa2Colors.Text, fontSize = 13.sp, fontWeight = FontWeight.Medium)
        Text(body, color = Wa2Colors.TextDim, fontSize = 12.sp, lineHeight = 17.sp)
    }
}

/** Compact rounded action button (≥ 44 dp tall) in the Wa2 style; [primary] = filled accent. */
@Composable
private fun UpdateButton(label: String, primary: Boolean = false, enabled: Boolean = true, onClick: () -> Unit) {
    val shape = RoundedCornerShape(10.dp)
    Box(
        Modifier
            .heightIn(min = 44.dp)
            .clip(shape)
            .background(
                when {
                    !enabled -> Color(0x33202030)
                    primary -> Wa2Colors.Accent
                    else -> Wa2Colors.Button
                },
            )
            .border(BorderStroke(1.dp, if (enabled) Wa2Colors.AccentDim else Color(0x33FFFFFF)), shape)
            .clickable(enabled = enabled, onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 8.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            label,
            color = when {
                !enabled -> Wa2Colors.TextDim
                primary -> Wa2Colors.Night
                else -> Wa2Colors.Text
            },
            fontSize = 14.sp, fontWeight = if (primary) FontWeight.Bold else FontWeight.Normal,
        )
    }
}

private fun mb(bytes: Long): String = "%.1f MB".format(Locale.ROOT, bytes / (1024.0 * 1024.0))
