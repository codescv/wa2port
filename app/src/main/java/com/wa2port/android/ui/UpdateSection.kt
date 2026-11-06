package com.wa2port.android.ui

import android.app.Activity
import android.content.ActivityNotFoundException
import android.content.ClipData
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ResolveInfo
import android.net.Uri
import android.os.Build
import android.provider.OpenableColumns
import android.webkit.MimeTypeMap
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
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
import androidx.compose.runtime.rememberCoroutineScope
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
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.FileProvider
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.wa2port.android.data.Settings
import com.wa2port.android.game.GameSession
import com.wa2port.android.update.UpdateController
import com.wa2port.android.update.UpdateState
import com.wa2port.core.update.AgentFeedbackHelper
import com.wa2port.core.update.AgentFeedbackMail
import com.wa2port.core.update.FeedbackEmailTargetResolver
import com.wa2port.core.update.FeedbackGameContext
import com.wa2port.core.update.FeedbackSendIntentSpec
import com.wa2port.core.update.ReleaseInfo
import com.wa2port.core.update.ResolvedEmailActivity
import java.io.File
import java.io.IOException
import java.util.Locale
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

private val UPDATE_ERR = Color(0xFFFFB0A0)
private val UPDATE_OK = Color(0xFF8FD3A8)

/**
 * In-layout update overlay for the title screen (no window `Dialog`: a new window would steal focus
 * and flash the hidden system bars). Tapping the scrim dismisses it, except while downloading.
 */
@OptIn(ExperimentalLayoutApi::class)
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
            }
            .screenSafePadding(),
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
                    FlowRow(Modifier.fillMaxWidth().padding(top = 4.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
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
@OptIn(ExperimentalLayoutApi::class)
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
    // Action rows wrap (FlowRow) instead of scrolling sideways, so no button hides off-screen.
    val actions = Modifier.fillMaxWidth().padding(vertical = 6.dp)
    val rowGap = Arrangement.spacedBy(6.dp)
    Column(Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
        when (val s = update.state) {
            UpdateState.Idle -> if (!compact) {
                Text("点击「检查更新」从 GitHub 获取最新版本\nTap Check to look for a newer release", color = Wa2Colors.TextDim, fontSize = 13.sp)
                FlowRow(actions, horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = rowGap) {
                    UpdateButton("检查更新 Check", primary = true) { update.checkForUpdates() }
                }
            }
            UpdateState.Checking -> {
                LinearProgressIndicator(Modifier.fillMaxWidth().padding(vertical = 6.dp), color = Wa2Colors.Accent, trackColor = Wa2Colors.Button)
                Text("正在检查更新… Checking for updates", color = Wa2Colors.TextDim, fontSize = 13.sp)
                FlowRow(actions, verticalArrangement = rowGap) { UpdateButton("取消 Cancel") { update.cancelDownload() } }
            }
            is UpdateState.UpToDate -> {
                Text("✓ 已是最新版本 v${update.currentVersion} Up to date", color = UPDATE_OK, fontSize = 14.sp)
                Text("最新发布 Latest release: ${s.latest.tagName}", color = Wa2Colors.TextDim, fontSize = 12.sp)
                FlowRow(actions, horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = rowGap) {
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
                FlowRow(actions, horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = rowGap) {
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
                FlowRow(actions, verticalArrangement = rowGap) { UpdateButton("取消下载 Cancel") { update.cancelDownload() } }
            }
            is UpdateState.Downloaded -> {
                Text("✓ v${s.latest.version} 已下载并校验 Downloaded & verified", color = UPDATE_OK, fontSize = 14.sp)
                Text(
                    "下载校验完成后会直接启动安装；如需授予「安装未知应用」权限，返回后将自动继续安装。若关闭了安装窗口，可点击「重新安装」。更新会保留存档与设置。",
                    color = Wa2Colors.TextDim, fontSize = 12.sp,
                )
                if (!compact) ReleaseNotes(s.latest)
                FlowRow(actions, horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = rowGap) {
                    UpdateButton("重新安装 Install", primary = true) { update.installApk(context) }
                    if (s.latest.apkAsset != null) {
                        UpdateButton("重新下载 Re-download") { update.downloadUpdate(context) }
                    } else {
                        UpdateButton("检查更新 Check") { update.checkForUpdates() }
                    }
                }
            }
            is UpdateState.Error -> {
                Text("✗ ${s.message}", color = UPDATE_ERR, fontSize = 13.sp)
                FlowRow(actions, horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = rowGap) {
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
 * 设置 → Agent 反馈: a compact single-row trigger (local email status + one **撰写反馈 Feedback**
 * button). [onOpenFeedback] opens the in-layout [AgentFeedbackDialog].
 */
@Composable
fun AgentFeedbackSection(
    settings: Settings,
    onOpenFeedback: () -> Unit,
) {
    val email = settings.agentFeedbackEmail
    val emailError = if (email.isEmpty()) null else AgentFeedbackHelper.validateEmail(email)
    SectionTitle("Agent 反馈 Agent Feedback")
    OptionRow("反馈给 Agent") {
        Text(
            when {
                email.isEmpty() -> "未配置邮箱 Email not set"
                emailError == null -> "✓ $email"
                else -> "✗ 邮箱无效 Invalid email"
            },
            color = when {
                email.isEmpty() -> Wa2Colors.TextDim
                emailError == null -> UPDATE_OK
                else -> UPDATE_ERR
            },
            fontSize = 13.sp,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.widthIn(max = 280.dp).padding(end = 12.dp),
        )
        UpdateButton("撰写反馈 Feedback", primary = true, onClick = onOpenFeedback)
    }
}

/**
 * In-layout Agent feedback overlay (no window `Dialog` / `AlertDialog`, styled like [UpdateDialog],
 * so immersive system bars never flash). Fields: **Agent 反馈邮箱** (saved locally to
 * `settings.agentFeedbackEmail`), **标题**, **问题描述**, **增加图片** (system picker) and the
 * optional active WA2 scene/line context. Sends `wa2port: [v1.4.7] <title>`:
 * - text only → `Intent.ACTION_SENDTO` `mailto:`;
 * - with an image → `Intent.ACTION_SEND` carrying a FileProvider copy (`cacheDir/feedback`) as
 *   `EXTRA_STREAM`, sent as an explicit `ACTION_SEND` to the resolved email client's compose activity
 *   (no selector; chooser fallback only when no email client is found).
 *
 * Tapping the scrim dismisses only while the draft is empty, so a half-written report is not lost.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun AgentFeedbackDialog(
    settings: Settings,
    onChange: ((Settings) -> Settings) -> Unit,
    onDismiss: () -> Unit,
    currentVersion: String = AgentFeedbackHelper.DEFAULT_APP_VERSION,
    session: GameSession? = null,
) {
    val context = LocalContext.current
    val focusManager = LocalFocusManager.current
    val scope = rememberCoroutineScope()
    val resolvedVersion = AgentFeedbackHelper.normalizeVersion(currentVersion)
    val email = settings.agentFeedbackEmail
    val emailError = AgentFeedbackHelper.validateEmail(email)

    var title by rememberSaveable { mutableStateOf("") }
    var feedbackText by rememberSaveable { mutableStateOf("") }
    var attachedImageUri by rememberSaveable { mutableStateOf<Uri?>(null) }
    var attachedImageName by rememberSaveable { mutableStateOf<String?>(null) }
    var includeGameContext by rememberSaveable { mutableStateOf(true) }
    var statusMessage by remember { mutableStateOf<Pair<String, Boolean>?>(null) }
    var sending by remember { mutableStateOf(false) }

    val pickImage = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { uri ->
        if (uri != null) {
            attachedImageUri = uri
            attachedImageName = queryImageDisplayName(context, uri)
            statusMessage = null
        }
    }

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

    val hasDraft = title.isNotBlank() || feedbackText.isNotBlank() || attachedImageUri != null
    BackHandler(onBack = onDismiss)

    fun send() {
        focusManager.clearFocus()
        if (sending) return
        if (emailError != null) {
            statusMessage = emailError to true
            return
        }
        if (title.isBlank() && feedbackText.isBlank()) {
            statusMessage = "请先输入标题或问题描述 Please enter a title or description" to true
            return
        }
        val imageUri = attachedImageUri
        val deviceInfo = "${Build.MANUFACTURER} ${Build.MODEL} (Android ${Build.VERSION.RELEASE}, SDK ${Build.VERSION.SDK_INT})"
        val mail = AgentFeedbackHelper.prepareMail(
            email = email,
            feedbackText = feedbackText,
            version = resolvedVersion,
            deviceInfo = deviceInfo,
            gameContext = if (includeGameContext) gameContext else null,
            title = title,
            attachmentName = if (imageUri != null) attachedImageName ?: "image" else null,
        )
        if (imageUri == null) {
            statusMessage = launchMailto(context, mail)
            return
        }
        sending = true
        statusMessage = null
        scope.launch {
            val copied = runCatching { withContext(Dispatchers.IO) { copyFeedbackImage(context, imageUri, attachedImageName) } }
            sending = false
            statusMessage = copied.fold(
                onSuccess = { (file, mimeType) ->
                    try {
                        val contentUri = FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file)
                        launchImageMail(context, mail, contentUri, mimeType)
                    } catch (e: Exception) {
                        "无法共享图片 Cannot share image: ${e.message ?: e.javaClass.simpleName}" to true
                    }
                },
                onFailure = { e -> "无法读取图片 Cannot read image: ${e.message ?: e.javaClass.simpleName}" to true },
            )
        }
    }

    val noRipple = remember { MutableInteractionSource() }
    Box(
        Modifier
            .fillMaxSize()
            .background(Color(0xAA000000))
            .clickable(interactionSource = noRipple, indication = null) {
                focusManager.clearFocus()
                if (!hasDraft && !sending) onDismiss()
            }
            .screenSafePadding(),
        contentAlignment = Alignment.Center,
    ) {
        val shape = RoundedCornerShape(14.dp)
        Column(
            Modifier
                .padding(horizontal = 24.dp, vertical = 12.dp)
                .widthIn(max = 640.dp)
                .fillMaxWidth()
                .clip(shape)
                .background(Wa2Colors.Panel)
                .border(BorderStroke(1.dp, Wa2Colors.AccentDim), shape)
                // Swallow taps so they do not reach the scrim.
                .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null) {}
                .padding(horizontal = 20.dp, vertical = 10.dp),
        ) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text("Agent 反馈 Agent Feedback", color = Wa2Colors.Text, fontSize = 20.sp, modifier = Modifier.weight(1f))
                CloseButton(onDismiss)
            }
            Text(
                "主题自动使用 wa2port: [v$resolvedVersion] 前缀；邮箱仅保存在本机。",
                color = Wa2Colors.TextDim,
                fontSize = 12.sp,
            )
            Column(
                Modifier
                    .fillMaxWidth()
                    .weight(1f, fill = false)
                    .verticalScroll(rememberScrollState())
                    .padding(top = 4.dp),
            ) {
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
                Text(
                    when {
                        email.isEmpty() -> "仅保存在设备本地（默认留空，示例格式：${AgentFeedbackHelper.PLACEHOLDER_EMAIL}）"
                        emailError == null -> "✓ 已保存在本机：$email"
                        else -> "✗ $emailError"
                    },
                    color = when {
                        email.isEmpty() -> Wa2Colors.TextDim
                        emailError == null -> UPDATE_OK
                        else -> UPDATE_ERR
                    },
                    fontSize = 11.sp,
                    modifier = Modifier.padding(start = 4.dp, bottom = 2.dp),
                )

                OutlinedTextField(
                    value = title,
                    onValueChange = {
                        title = it.replace('\n', ' ').replace('\r', ' ')
                        statusMessage = null
                    },
                    modifier = Modifier.fillMaxWidth().padding(vertical = 2.dp),
                    singleLine = true,
                    label = { Text("标题 Title", fontSize = 12.sp) },
                    placeholder = { Text("一句话概括问题或需求（留空则取描述首行）", fontSize = 12.sp, color = Wa2Colors.TextDim) },
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Next),
                    colors = feedbackFieldColors(),
                )

                OutlinedTextField(
                    value = feedbackText,
                    onValueChange = {
                        feedbackText = it
                        statusMessage = null
                    },
                    modifier = Modifier.fillMaxWidth().padding(vertical = 2.dp),
                    label = { Text("问题描述 Description", fontSize = 12.sp) },
                    placeholder = {
                        Text(
                            "复现步骤、排版异常，或希望 Agent 自动开发的新功能…",
                            fontSize = 12.sp,
                            color = Wa2Colors.TextDim,
                        )
                    },
                    minLines = 3,
                    maxLines = 6,
                    colors = feedbackFieldColors(),
                )

                Row(
                    Modifier.fillMaxWidth().padding(vertical = 4.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    UpdateButton(
                        if (attachedImageUri == null) "增加图片 Add Image" else "更换图片 Change Image",
                        enabled = !sending,
                    ) {
                        focusManager.clearFocus()
                        try {
                            pickImage.launch("image/*")
                        } catch (_: ActivityNotFoundException) {
                            statusMessage = "未找到图片选择器 No image picker found" to true
                        }
                    }
                    if (attachedImageUri != null) {
                        Text(
                            "🖼 ${attachedImageName ?: "image"}",
                            color = Wa2Colors.Text,
                            fontSize = 13.sp,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.weight(1f, fill = false),
                        )
                        UpdateButton("移除 Remove", enabled = !sending) {
                            attachedImageUri = null
                            attachedImageName = null
                            statusMessage = null
                        }
                    }
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

                val previewSubject = AgentFeedbackHelper.buildSubject(
                    feedbackText = feedbackText,
                    version = resolvedVersion,
                    title = title,
                )
                Text(
                    "邮件主题 Subject: $previewSubject",
                    color = Wa2Colors.TextDim,
                    fontSize = 11.sp,
                    modifier = Modifier.padding(start = 4.dp, top = 2.dp, bottom = 2.dp),
                )
            }

            statusMessage?.let { (msg, isError) ->
                Text(
                    (if (isError) "✗ " else "✓ ") + msg,
                    color = if (isError) UPDATE_ERR else UPDATE_OK,
                    fontSize = 13.sp,
                    modifier = Modifier.padding(top = 4.dp),
                )
            }
            val canSend = emailError == null && (title.isNotBlank() || feedbackText.isNotBlank()) && !sending
            FlowRow(
                Modifier.fillMaxWidth().padding(vertical = 6.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                UpdateButton(
                    when {
                        sending -> "准备附件… Preparing"
                        attachedImageUri != null -> "发送反馈（含图片） Send"
                        else -> "发送反馈给 Agent"
                    },
                    primary = canSend,
                    enabled = !sending,
                ) { send() }
                if (hasDraft) {
                    UpdateButton("清空 Clear", enabled = !sending) {
                        title = ""
                        feedbackText = ""
                        attachedImageUri = null
                        attachedImageName = null
                        statusMessage = null
                    }
                }
                UpdateButton("关闭 Close", onClick = onDismiss)
            }
        }
    }
}

/** FileProvider-shared cache subdirectory for feedback image copies (see `res/xml/file_paths.xml`). */
private const val FEEDBACK_CACHE_DIR = "feedback"

/** Display name of a picked document (`OpenableColumns.DISPLAY_NAME`), sanitized for the email body. */
private fun queryImageDisplayName(context: Context, uri: Uri): String? {
    val name = runCatching {
        context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { c ->
            val idx = c.getColumnIndex(OpenableColumns.DISPLAY_NAME)
            if (idx >= 0 && c.moveToFirst()) c.getString(idx) else null
        }
    }.getOrNull()
    return AgentFeedbackHelper.normalizeAttachmentName(name)
        ?: AgentFeedbackHelper.normalizeAttachmentName(uri.lastPathSegment)
}

/**
 * Copies the picked image into `cacheDir/feedback` (replacing older copies) so it can be shared
 * through the app's FileProvider. Returns the copy and its MIME type. Runs on [Dispatchers.IO].
 */
private fun copyFeedbackImage(context: Context, source: Uri, displayName: String?): Pair<File, String> {
    val resolver = context.contentResolver
    val mimeType = resolver.getType(source)?.takeIf { it.startsWith("image/") } ?: "image/*"
    val ext = MimeTypeMap.getSingleton().getExtensionFromMimeType(mimeType)
        ?: displayName?.substringAfterLast('.', "")?.lowercase(Locale.ROOT)?.takeIf { it.matches(Regex("[a-z0-9]{1,5}")) }
        ?: "jpg"
    val dir = File(context.cacheDir, FEEDBACK_CACHE_DIR)
    if (!dir.isDirectory && !dir.mkdirs()) throw IOException("无法创建缓存目录 Cannot create cache dir")
    dir.listFiles()?.forEach { it.delete() }
    val out = File(dir, "wa2port_feedback_${System.currentTimeMillis()}.$ext")
    val input = resolver.openInputStream(source) ?: throw IOException("无法打开图片 Cannot open image")
    input.use { inp -> out.outputStream().use { inp.copyTo(it) } }
    return out to mimeType
}

/** Text-only feedback: `Intent.ACTION_SENDTO` with the encoded `mailto:` URI. Returns the status line. */
private fun launchMailto(context: Context, mail: AgentFeedbackMail): Pair<String, Boolean> {
    val intent = Intent(Intent.ACTION_SENDTO, Uri.parse(mail.mailtoUri)).apply {
        if (context !is Activity) addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    }
    return try {
        context.startActivity(intent)
        "已唤起邮件客户端发送至 ${mail.toEmail} Opened email app" to false
    } catch (_: ActivityNotFoundException) {
        "未找到可用的邮件客户端 No email app found" to true
    } catch (e: Exception) {
        "无法打开邮件客户端 Cannot open email app: ${e.message ?: e.javaClass.simpleName}" to true
    }
}

/**
 * Feedback with an image. Android 15–17 / ColorOS reject `ACTION_SEND(image/any)` + a `mailto:`
 * selector (`MATCH_NO_TYPE`) or hijack it into the generic "分享1张图片" share sheet, so instead:
 * probe `mailto:` handlers (default app if it is a real client → Gmail → other handlers; system
 * resolvers filtered by [FeedbackEmailTargetResolver]), find each one's `ACTION_SEND` compose activity
 * (`image/any`, `any/any`, `message/rfc822`), grant read access to [contentUri] and start an explicit
 * component `ACTION_SEND` **without selector** so the compose screen opens directly. Only when no email
 * client accepts it does it fall back to a chooser. Returns the status line.
 */
private fun launchImageMail(
    context: Context,
    mail: AgentFeedbackMail,
    contentUri: Uri,
    mimeType: String,
): Pair<String, Boolean> {
    val pm = context.packageManager
    val mailtoProbe = Intent(Intent.ACTION_SENDTO, Uri.parse("mailto:"))
    val defaultMailto = pm.resolveDefaultActivity(mailtoProbe)
    val mailtoActivities = pm.queryActivitiesSafe(mailtoProbe, PackageManager.MATCH_DEFAULT_ONLY)
    val targets = FeedbackEmailTargetResolver.resolveComposeTargets(
        defaultMailto = defaultMailto,
        mailtoActivities = mailtoActivities,
        attachmentMime = mimeType,
    ) { pkg, mime ->
        pm.queryActivitiesSafe(Intent(Intent.ACTION_SEND).setType(mime).setPackage(pkg), PackageManager.MATCH_DEFAULT_ONLY)
            .ifEmpty { pm.queryActivitiesSafe(Intent(Intent.ACTION_SEND).setType(mime).setPackage(pkg), 0) }
    }
    for (target in targets) {
        val spec = FeedbackEmailTargetResolver.buildAttachmentSendSpec(mail, target, contentUri.toString())
        runCatching { context.grantUriPermission(target.packageName, contentUri, Intent.FLAG_GRANT_READ_URI_PERMISSION) }
        val intent = spec.toIntent(contentUri).apply {
            if (context !is Activity) addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        if (context.tryStart(intent)) {
            return "已打开邮件撰写页（含图片）发送至 ${mail.toEmail} Opened email compose with image" to false
        }
    }
    // Last resort: no email client exposes a visible ACTION_SEND compose activity.
    val fallbackSend = Intent(Intent.ACTION_SEND).apply {
        type = mimeType.ifBlank { FeedbackEmailTargetResolver.DEFAULT_IMAGE_MIME }
        putExtra(Intent.EXTRA_EMAIL, arrayOf(mail.toEmail))
        putExtra(Intent.EXTRA_SUBJECT, mail.subject)
        putExtra(Intent.EXTRA_TEXT, mail.body)
        putExtra(Intent.EXTRA_STREAM, contentUri)
        clipData = ClipData.newRawUri(FeedbackEmailTargetResolver.CLIP_LABEL, contentUri)
        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
    }
    val chooser = Intent.createChooser(fallbackSend, "发送反馈 Send feedback").apply {
        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        if (context !is Activity) addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    }
    return try {
        context.startActivity(chooser)
        "未找到邮件客户端，已打开分享面板 Choose an app to send" to false
    } catch (_: ActivityNotFoundException) {
        "未找到可用的邮件客户端 No email app found" to true
    } catch (e: Exception) {
        "无法打开邮件客户端 Cannot open email app: ${e.message ?: e.javaClass.simpleName}" to true
    }
}

/** Maps the pure [FeedbackSendIntentSpec] 1:1 onto an explicit `ACTION_SEND` (never a selector). */
private fun FeedbackSendIntentSpec.toIntent(streamUri: Uri): Intent {
    val intent = Intent(action)
    val explicitClass = className
    if (explicitClass != null) intent.component = ComponentName(packageName, explicitClass) else intent.setPackage(packageName)
    intent.type = mimeType
    intent.putExtra(Intent.EXTRA_EMAIL, recipients.toTypedArray())
    intent.putExtra(Intent.EXTRA_SUBJECT, subject)
    intent.putExtra(Intent.EXTRA_TEXT, text)
    intent.putExtra(Intent.EXTRA_STREAM, streamUri)
    intent.clipData = ClipData.newRawUri(clipLabel, streamUri)
    if (grantReadUriPermission) intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
    // hasSelector is always false by contract.
    return intent
}

private fun Context.tryStart(intent: Intent): Boolean =
    try {
        startActivity(intent)
        true
    } catch (_: ActivityNotFoundException) {
        false
    } catch (_: SecurityException) {
        false
    }

private fun PackageManager.resolveDefaultActivity(intent: Intent): ResolvedEmailActivity? {
    val info = runCatching {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            resolveActivity(intent, PackageManager.ResolveInfoFlags.of(PackageManager.MATCH_DEFAULT_ONLY.toLong()))
        } else {
            @Suppress("DEPRECATION")
            resolveActivity(intent, PackageManager.MATCH_DEFAULT_ONLY)
        }
    }.getOrNull()
    return info?.toResolvedEmailActivity()
}

private fun PackageManager.queryActivitiesSafe(intent: Intent, flags: Int): List<ResolvedEmailActivity> =
    runCatching {
        val infos = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            queryIntentActivities(intent, PackageManager.ResolveInfoFlags.of(flags.toLong()))
        } else {
            @Suppress("DEPRECATION")
            queryIntentActivities(intent, flags)
        }
        infos.mapNotNull { it.toResolvedEmailActivity() }
    }.getOrDefault(emptyList())

private fun ResolveInfo.toResolvedEmailActivity(): ResolvedEmailActivity? {
    val ai = activityInfo ?: return null
    val pkg = ai.packageName ?: return null
    val name = ai.name ?: return null
    return ResolvedEmailActivity(packageName = pkg, activityName = name)
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
