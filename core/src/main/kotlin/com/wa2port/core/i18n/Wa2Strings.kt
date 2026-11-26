package com.wa2port.core.i18n

import com.wa2port.core.ai.AiErrorCode
import com.wa2port.core.ai.RegisterTag
import com.wa2port.core.sync.ConflictReason

/**
 * Complete single-language UI string contract for WA2Port (Simplified Chinese, English, Japanese).
 *
 * Every UI chrome string across `:core` and `:app` lives here so the Kotlin compiler enforces 100%
 * key parity across [Wa2StringsZh], [Wa2StringsEn], and [Wa2StringsJa].
 */
interface Wa2Strings {
    val language: ResolvedLanguage

    // ---- Common
    val commonOk: String
    val commonCancel: String
    val commonClose: String
    val commonBack: String
    val commonSave: String
    val commonDelete: String
    val commonClear: String
    val commonRemove: String
    val commonRetry: String
    val commonAll: String
    val commonOn: String
    val commonOff: String
    val commonLoading: String
    val commonDefault: String
    val commonCustom: String
    val commonReset: String
    val commonConfirm: String
    val commonUse: String
    val commonPause: String
    val commonLocked: String
    val commonAssetNotFound: String
    val commonNarration: String
    val commonNoLine: String

    // ---- Language / Orientation / Script Language / Window / Scale / Opacity
    val langSystemDefault: String
    val orientationPortrait: String
    val orientationLandscape: String
    val orientationAuto: String
    val scriptLangBoth: String
    val scriptLangCn: String
    val scriptLangJp: String
    val windowModeAdv: String
    val windowModeNovel: String
    val textScaleSmall: String
    val textScaleNormal: String
    val textScaleLarge: String
    val textScaleXLarge: String
    val textScaleXXLarge: String
    val opacityPresetClear: String
    val opacityPresetOriginal: String
    val opacityPresetTranslucent: String

    fun textScalePresetLabel(percent: Int): String? = when (percent) {
        85 -> textScaleSmall
        100 -> textScaleNormal
        115 -> textScaleLarge
        130 -> textScaleXLarge
        145 -> textScaleXXLarge
        else -> null
    }

    // ---- TitleScreen
    fun titleSubtitle(version: String): String
    val titleNotebookPill: String
    val titleLandscapePill: String
    val titlePortraitPill: String
    fun titleAssetsReady(path: String): String
    val titleAssetsMissing: String
    val titleContinue: String
    fun titleContinueSub(scene: String, relTime: String): String
    val titleIc: String
    val titleIcSub: String
    val titleCc: String
    val titleCcSub: String
    val titleLoad: String
    val titleSceneReplay: String
    val titleCgGallery: String
    val titleBgmGallery: String
    val titleConfig: String
    val titleAssets: String

    // ---- ConfigScreen
    val configTitle: String
    val configTabAll: String
    val configTabTextDisplay: String
    val configTabAudio: String
    val configTabCloudSync: String
    val configTabAi: String
    val configTabUpdate: String
    val configSectionText: String
    val configTextSpeed: String
    val configTextSpeedInstant: String
    val configAutoDelay: String
    val configFontPresets: String
    val configFontSize: String
    val configWindowOpacityPresets: String
    val configWindowOpacity: String
    val configSectionDisplay: String
    val configAppLanguage: String
    val configOrientation: String
    val configOrientationHint: String
    val configScriptLanguage: String
    val configWindowMode: String
    val configSkip: String
    val configSkipReadOnly: String
    val configSkipAll: String
    val configSectionVolume: String
    val configVolumeBgm: String
    val configVolumeSe: String
    val configVolumeVoice: String
    val configPreviewTitle: String
    val configPreviewPortraitTitle: String
    fun configPreviewCaptionLandscape(percent: Int, preset: String?, lines: Int, capChars: Int, windowPercent: Int): String
    fun configPreviewCaptionPortrait(percent: Int, preset: String?, layoutMode: String, capChars: Int): String
    val configPreviewLayoutTwoCol: String
    val configPreviewLayoutStacked: String
    val configPreviewLayoutSingle: String

    // ---- SaveLoadScreen
    val saveTitle: String
    val loadTitle: String
    val saveBackToGame: String
    val saveOverwrite: String
    fun saveOverwriteConfirm(slot: String): String
    fun saveDeleteConfirm(slot: String): String
    val saveTagQuick: String
    val saveTagAuto: String
    val saveTagLocked: String
    val saveTagJustSaved: String
    val saveSlotEmpty: String
    val saveStatusLockedSuffix: String
    val timeJustNow: String
    fun timeMinutesAgo(m: Long): String
    fun timeHoursAgo(h: Long): String
    fun timeDaysAgo(d: Long): String
    fun sceneLabelIc(scene: String): String
    fun sceneLabelCc(scene: String): String
    fun sceneLabelCoda(scene: String): String

    // ---- AssetSetupScreen / AssetLocator / SafImporter
    val assetsTitle: String
    fun assetsGuide(importPath: String): String
    val assetsSectionSearch: String
    val assetsLabelCustom: String
    val assetsLabelAppStorage: String
    val assetsLabelAdb: String
    val assetsStatusInUse: String
    val assetsStatusValid: String
    val assetsStatusMissingPak: String
    val assetsStatusNotFound: String
    val assetsImportBtn: String
    val assetsImportSub: String
    val assetsResumeBtn: String
    val assetsResumeSub: String
    val assetsPauseSub: String
    val assetsRescanBtn: String
    val assetsSectionCustomPath: String
    val importPhaseScanning: String
    fun importPhaseCopying(done: Int, total: Int, doneGb: String, totalGb: String): String
    fun importPhaseDone(files: Int, totalGb: String): String
    val importPhaseFailed: String
    val importPhaseCancelled: String
    fun importErrNotEnoughSpace(needGb: String, freeGb: String): String
    val importPausedMsg: String
    val importErrNoScriptPak: String

    // ---- Galleries (CG / BGM / Scene Replay)
    val galleryShowAllOn: String
    val galleryShowAllOff: String
    val galleryShowAllShortOn: String
    val galleryShowAllShortOff: String
    val cgTitle: String
    fun cgCountSummary(total: Int, unlocked: Int): String
    val cgFilterAll: String
    val cgFilterIc: String
    val cgFilterCc: String
    val cgCategoryOther: String
    val cgLockedVariant: String
    val cgViewerLockedShort: String
    val cgViewerLockedTag: String
    val bgmTitle: String
    fun bgmCountSummary(unlocked: Int, total: Int, visible: Int): String
    fun bgmFilterAll(total: Int): String
    fun bgmFilterPage1(count: Int): String
    fun bgmFilterPage2(count: Int): String
    val bgmPage1Label: String
    val bgmPage2Label: String
    val bgmLockedTitle: String
    val bgmLockedHint: String
    val bgmLoopSeamless: String
    val bgmLoopSingle: String
    val bgmStatePaused: String
    val bgmStatePlaying: String
    val bgmStateStopped: String
    val bgmDefaultSubtitle: String
    val bgmPrev: String
    val bgmPlay: String
    val bgmResume: String
    val bgmPause: String
    val bgmStop: String
    val bgmNext: String
    val replayTitle: String
    fun replayCountSummary(unlocked: Int, total: Int, visible: Int): String
    fun replayFilterAll(total: Int): String
    fun replayFilterIc(count: Int): String
    fun replayFilterCc(count: Int): String
    fun replayFilterCoda(count: Int): String
    val replayCategoryIc: String
    val replayCategoryCc: String
    val replayCategoryCoda: String
    val replayLockedTitle: String
    fun replayScriptFlag(script: String, flagId: Int): String

    // ---- In-Game (GameScreen / GameSession / Overlays / AdvPills / MessageWindow / Movie / AppViewModel)
    val advPillAi: String
    val advPillAiShort: String
    val advPillVoice: String
    val quickBarExitReplay: String
    val quickBarExitReplayShort: String
    val gameTapAnalyzeBadge: String
    val gameTapAnalyzeOffToast: String
    val toastLangBilingual: String
    val toastLangJapanese: String
    val toastSaved: String
    val toastSaveFailed: String
    val toastLoaded: String
    val toastLoadFailed: String
    val toastPersonaSwitched: String
    val toastUndoBtn: String
    val toastLoadUndone: String
    val toastNothingToUndo: String
    val toastSaveDisabledReplay: String
    val toastQuickSlotLocked: String
    val toastQuickSaved: String
    val toastQuickSaveUndone: String
    val toastLoadDisabledReplay: String
    val toastNoQuickSave: String
    val toastQuickLoaded: String
    val backlogTitle: String
    val backlogLatestBtn: String
    val menuHeader: String
    val menuExitReplay: String
    val menuReturnTitle: String
    val menuReturnTitleConfirm: String
    fun menuChipWindow(mode: String): String
    fun menuChipFont(percent: Int): String
    val menuChipTapOn: String
    val menuChipTapOff: String
    val menuChipNotebook: String
    val menuChipHideWindow: String
    val menuSave: String
    val menuLoad: String
    val menuQuickLoad: String
    val menuLog: String
    val menuConfig: String
    val menuResume: String
    val movieTapAgainToSkip: String
    val movieUnavailableTitle: String
    fun movieUnavailableDetail(stem: String): String
    fun movieStubUnavailable(stem: String): String
    val vmLoading: String
    val vmLoadingReplay: String
    fun vmCannotStartScene(scene: String): String
    fun vmCannotStartReplay(slot: Int): String
    fun vmStartFailed(detail: String): String
    val vmSaveUnreadable: String
    fun vmLoadFailed(detail: String): String
    fun vmEngineError(detail: String): String

    // ---- AI Settings / Breakdown / Notebook / Offline Pack
    val aiSectionTitle: String
    val aiProviderLabel: String
    val aiCustomBaseUrlLabel: String
    val aiCustomBaseUrlHelper: String
    val aiApiVersionLabel: String
    fun aiPastedVersionWins(v: String): String
    val aiProxyAuthLabel: String
    val aiProxyAuthHeader: String
    val aiProxyAuthBearer: String
    val aiProxyAuthQuery: String
    val aiVertexProjectLabel: String
    val aiVertexLocationLabel: String
    val aiVertexLocationCustomLabel: String
    val aiVertexAuthLabel: String
    val aiVertexAuthSa: String
    val aiVertexAuthToken: String
    val aiVertexAuthExpress: String
    val aiVertexTokenHelper: String
    fun aiVertexBaseDefault(host: String): String
    val aiVertexBaseProxyHint: String
    fun aiEndpointPrefix(url: String): String
    val aiModelLabel: String
    val aiCustomModelLabel: String
    val aiPersonaLabel: String
    fun aiPersonaCustomChip(name: String): String
    val aiCustomNameLabel: String
    val aiCustomSoulLabel: String
    val aiPersonaKazusaName: String
    val aiPersonaSetsunaName: String
    val aiPersonaKazusaDesc: String
    val aiPersonaSetsunaDesc: String
    val aiTapToAnalyzeLabel: String
    val aiTapToAnalyzeOn: String
    val aiCacheLabel: String
    val aiPrefetchLabel: String
    fun aiPrefetchHint(prefetched: Int, hits: Int): String
    val aiTemperatureLabel: String
    val aiThinkingLabel: String
    val aiThinkingOff: String
    val aiTimeoutLabel: String
    val aiTestingBtn: String
    val aiTestBtn: String
    val aiClearCacheBtn: String
    fun aiCacheStats(entries: Int, kb: Long): String
    val aiPreviewBtn: String
    val aiNotebookBtn: String
    fun aiNotebookSub(sentences: Int, stars: Int): String
    val aiClearCacheConfirm: String
    val aiSecretHide: String
    val aiSaJsonLabel: String
    val aiExtraHeadersToggle: String
    val aiExtraHeadersFieldLabel: String
    fun aiExtraHeadersValidCount(valid: Int, total: Int): String

    fun aiBreakdownTitle(persona: String): String
    val aiPrevLine: String
    val aiNextLine: String
    val aiVoiceBtn: String
    val aiNotebookChip: String
    val aiRegenerateBtn: String
    val aiSampleBtn: String
    fun aiAnalyzing(persona: String): String
    fun aiRegisterTag(label: String): String
    fun aiToneTag(tone: String): String
    val aiSampleTag: String
    val aiOfflinePackTag: String
    val aiOfflineTag: String
    val aiCachedTag: String
    val aiChunksHeader: String
    val aiTranslationLabel: String
    val aiLiteralLabel: String
    val aiOfficialCnLabel: String
    val aiNoOfficialCn: String
    val aiOfficialNoteLabel: String
    val aiContextLabel: String
    val aiNotConfiguredHint: String
    val aiGoSettingsBtn: String
    val aiOfflineSplitBtn: String
    val aiFollowUpWaitLoading: String
    val aiFollowUpLockedOffline: String
    val aiSkeletonLoading: String
    val aiChunkBreakdownHeader: String
    fun aiVocabHeader(count: Int): String
    fun aiGrammarHeader(count: Int): String
    val aiTextbookLabel: String
    fun aiSpokenEvoHeader(kind: String): String
    val aiUnalignedWarning: String
    val aiVocabLabel: String
    val aiGrammarLabel: String
    val aiFormalityCasual: String
    val aiFormalityFormal: String
    fun aiConjugationPrefix(v: String): String
    fun aiNotesPrefix(v: String): String
    fun aiGrammarSeenPrefix(surfaces: String): String
    fun aiFollowUpHeader(persona: String): String
    fun aiThinkingStatus(persona: String): String
    fun aiFollowUpPlaceholder(persona: String): String
    val aiSendBtn: String
    val aiQuickQuestions: List<String>

    val aiNoJapaneseText: String
    val aiSampleSceneHint: String
    val aiNotebookSceneHint: String
    val aiPackRegenerateNotice: String
    val aiPackFollowUpNotice: String
    val aiFollowUpNeedConfig: String
    val aiFollowUpOfflineUnsupported: String
    val aiRequestTimedOut: String
    fun aiRequestTimedOutAfter(sec: Long): String
    fun aiErrorText(code: AiErrorCode): String
    val aiOfflineChunkRole: String
    val aiOfflineChunkMeaning: String
    val aiOfflineTranslationFallback: String
    fun registerLabel(tag: RegisterTag): String
    fun posLabel(pos: String): String

    fun notebookTitleCompact(sentences: Int, stars: Int): String
    fun notebookTitleWide(sentences: Int, stars: Int): String
    val notebookExportAnki: String
    val notebookExportAnkiShort: String
    fun notebookExportedNotice(rows: Int): String
    val notebookExportSubject: String
    val notebookExportChooser: String
    val notebookTabVocab: String
    val notebookTabGrammar: String
    val notebookTabSentences: String
    val notebookTabQa: String
    val notebookFilterStarred: String
    val notebookSearchPlaceholder: String
    val notebookEmptyVocab: String
    val notebookEmptyGrammar: String
    val notebookEmptySentences: String
    val notebookEmptyQa: String
    fun notebookSentenceStats(vocab: Int, grammar: Int): String
    val notebookTapToOpen: String
    val notebookQaQPrefix: String
    val notebookQaAPrefix: String
    fun notebookSourceSentencePrefix(s: String): String
    val notebookStarred: String
    val notebookStar: String
    val notebookGrammarTag: String

    val packSettingsTitle: String
    val packInstalled: String
    fun packCoverage(scenes: Int, lines: Int, mb: String): String
    val packInstalledHint: String
    val packUpdateAvailable: String
    fun packUpdateAvailableSize(mb: String): String
    val packUpToDate: String
    val packDownloading: String
    val packChecking: String
    fun packProgressKnown(doneMb: String, totalMb: String, pct: Long): String
    fun packProgressUnknown(doneMb: String): String
    fun packResumableHint(mb: String): String
    fun packStillUsingInstalled(coverage: String): String
    val packNotInstalledDesc: String
    fun packSizeLabel(mb: String): String
    fun packPartialResumeHint(mb: String): String
    val packUpdateBtn: String
    val packCheckUpdateBtn: String
    val packDeleteBtn: String
    val packConfirmDeleteBtn: String
    fun packFreeSpaceSub(mb: String): String
    val packRetryBtn: String
    val packResumeDownloadBtn: String
    val packDownloadBtn: String
    val packOfferTitle: String
    val packOfferDesc: String
    val packOfferBtn: String

    // ---- Update & Agent Feedback
    val updateDialogTitle: String
    fun updateCurrentVersion(v: String): String
    val updateSendFeedbackBtn: String
    val updatePillCheck: String
    val updatePillChecking: String
    val updatePillUpToDate: String
    fun updatePillAvailable(v: String): String
    fun updatePillDownloading(progress: String): String
    fun updatePillInstall(v: String): String
    val updateIdleHint: String
    val updateCheckBtn: String
    val updateCheckingHint: String
    fun updateUpToDateMsg(v: String): String
    fun updateLatestRelease(tag: String): String
    val updateCheckAgainBtn: String
    val updateOpenGithubBtn: String
    fun updateNewVersion(v: String): String
    val updateNoApk: String
    val updateDownloadInstallBtn: String
    val updateDownloadInstallNowBtn: String
    val updateOpenBrowserBtn: String
    val updateLaterBtn: String
    fun updateDownloadingLine(v: String, progress: String): String
    val updateCancelDownloadBtn: String
    fun updateDownloadedMsg(v: String): String
    val updateDownloadedHint: String
    val updateReinstallBtn: String
    val updateRedownloadBtn: String
    val updateSectionTitle: String
    val updateVersionRow: String
    val updateCheckUpdateBtn: String
    fun updatePublishedAt(date: String): String
    val updatePreRelease: String
    fun updateToastCheckFailed(detail: String): String
    val updateToastUnknownApps: String
    fun updateToastCannotOpenSettings(detail: String): String
    fun updateErrCannotStartInstaller(detail: String): String
    fun updateToastNoBrowser(url: String): String
    fun updateToastCannotOpenUrl(url: String): String
    val updateErrApkMissing: String
    val updateErrApkCorrupt: String
    fun updateErrApkWrongPackage(pkg: String): String
    fun updateErrApkNotNewer(v1: String, c1: Long, v2: String, c2: Long): String

    val feedbackSectionTitle: String
    val feedbackEmailNotSet: String
    val feedbackEmailInvalid: String
    val feedbackRowLabel: String
    val feedbackWriteBtn: String
    val feedbackDialogTitle: String
    fun feedbackDialogHint(v: String): String
    val feedbackEmailFieldLabel: String
    fun feedbackEmailHelperEmpty(placeholder: String): String
    fun feedbackEmailHelperSaved(email: String): String
    val feedbackTitleFieldLabel: String
    val feedbackTitlePlaceholder: String
    val feedbackDescFieldLabel: String
    val feedbackDescPlaceholder: String
    val feedbackAddImage: String
    val feedbackChangeImage: String
    val feedbackNoImagePicker: String
    val feedbackSceneContextLabel: String
    val feedbackAttachOn: String
    val feedbackAttachOff: String
    val feedbackReplayTag: String
    fun feedbackSubjectPreview(subject: String): String
    val feedbackPreparingBtn: String
    val feedbackSendWithImageBtn: String
    val feedbackSendBtn: String
    val feedbackErrEnterContent: String
    fun feedbackErrShareImage(detail: String): String
    fun feedbackErrReadImage(detail: String): String
    val feedbackErrCreateCacheDir: String
    val feedbackErrOpenImage: String
    fun feedbackOpenedEmail(to: String): String
    fun feedbackOpenedImageEmail(to: String): String
    val feedbackNoEmailApp: String
    fun feedbackCannotOpenEmail(detail: String): String
    val feedbackShareChooserTitle: String
    val feedbackOpenedShareSheet: String
    val feedbackValidateEmpty: String
    val feedbackValidateInvalid: String

    // ---- Cloud Sync
    val syncSectionTitle: String
    val syncConnected: String
    val syncSyncing: String
    fun syncLastSynced(time: String): String
    val syncDirInfo: String
    val syncSigningIn: String
    val syncNotSignedIn: String
    val syncSignInDesc: String
    val syncCancelSignInBtn: String
    val syncSignInBtn: String
    val syncNoBrowserErr: String
    val syncSyncingBtn: String
    val syncSyncNowBtn: String
    val syncSignOutBtn: String
    val syncConfirmSignOutBtn: String
    val syncAutoLabel: String
    val syncAutoDesc: String
    val syncAdvancedToggle: String
    val syncAdvancedDesc: String
    val syncClientIdInvalidWarn: String
    fun syncManifestSchemeWarn(scheme: String): String
    val syncSaveConfigBtn: String
    val syncSavedNotice: String
    val syncResetNotice: String
    val syncConflictTitle: String
    val syncLocalSaveTitle: String
    val syncCloudSaveTitle: String
    val syncResolving: String
    val syncKeepLocal: String
    val syncKeepLocalUpload: String
    val syncKeepLocalDeleteCloud: String
    val syncUseCloud: String
    val syncUseCloudDownload: String
    val syncUseCloudDeleteLocal: String
    fun syncKeepAllLocal(n: Int): String
    fun syncUseAllCloud(n: Int): String
    val syncHandleLater: String
    val syncNewerTag: String
    val syncDeletedOrEmpty: String
    fun syncUnreliableTs(formatted: String): String
    val syncNoTimestamp: String
    fun syncSummaryConflicts(up: Int, down: Int, conflicts: Int): String
    val syncSummaryUpToDate: String
    fun syncSummaryUploaded(n: Int): String
    fun syncSummaryDownloaded(n: Int): String
    fun syncSummaryDeleted(n: Int): String
    fun syncSummaryMerged(n: Int): String
    val syncPathSystem: String
    val syncPathLocks: String
    val syncPathAuto: String
    val syncPathQuick: String
    fun syncPathSlot(slot: Int): String
    fun syncConflictReason(reason: ConflictReason): String
    val syncProgressStart: String
    val syncProgressConnecting: String
    val syncProgressSystem: String
    fun syncProgressSlot(i: Int, total: Int): String
    val syncProgressLocks: String
    fun syncSystemLocalSummary(bytes: Int): String
    fun syncSystemCloudSummary(bytes: Int): String
    fun syncErrSaveOAuth(detail: String): String
    val syncErrMissingClientId: String
    val syncErrSignInFailed: String
    val syncErrNotSignedIn: String
    val syncErrSessionExpired: String

    // ---- Core Network / Validation / Parsing Errors
    val errCheckUpdateFailed: String
    val errDownloadFailed: String
    val errPackQueryFailed: String
    val errPackDownloadFailed: String
    val errServerReturnedWebPageApk: String
    val errServerReturnedWebPagePack: String
    fun errCannotSaveFile(name: String): String
    fun errCannotSavePack(name: String): String
    fun errDownloadInterrupted(written: Long, total: String, detail: String): String
    fun errDownloadLarger(written: Long, total: Long): String
    fun errDownloadTooLarge(mib: Long): String
    fun errIncompleteDownload(written: Long, total: Long): String
    fun errIncompleteDownloadResumable(written: Long, total: Long): String
    fun errSizeMismatch(actual: Long, expected: Long): String
    val errNotValidApk: String
    val errDownloadCorrupt: String
    val errNotApkMissingManifest: String
    fun errInvalidUrl(url: String): String
    fun errRedirectMissingLocation(status: Int): String
    val errTooManyRedirects: String
    fun errInvalidRedirect(loc: String): String
    val errInsecureRedirectHttps: String
    fun errInsecureRedirectScheme(proto: String): String
    fun errUnsupportedScheme(proto: String): String
    fun errUrlNoHost(url: String): String
    fun errRateLimitedWithHint(status: Int): String
    fun errRateLimited(status: Int): String
    val errNoReleaseFound404: String
    val errPackNotFound404: String
    val errCancelled: String
    fun errTimedOutMs(ms: Int, where: String): String
    fun errCleartextBlocked(where: String): String
    fun errUnknownHost(where: String): String
    fun errNetworkError(where: String, detail: String): String
    fun errInvalidArg(where: String, detail: String): String
    fun errStorageWrite(detail: String): String
    val errResponseTooLarge: String
    val errInvalidReleaseJson: String
    val errNoReleaseFound: String
    val errReleaseJsonNotObject: String
    val errReleaseJsonNoTag: String
    val errUrlMustStartHttp: String
    val errUrlNoSpaces: String
    val errUrlMissingHost: String
    val errPackInvalidReleaseJson: String
    fun errPackAssetMissing(name: String): String
    fun errPackConnectionDropped(written: Long, total: String): String
    val errPackDownloadMissing: String
    fun errPackSha256Mismatch(got: String, want: String): String
    val errPackEmpty: String
    val errPackCorruptFirstBlock: String
    val errPackCorruptLastBlock: String
    fun errPackInvalid(detail: String): String
    val errPackTooSmall: String
    val errPackBadMagic: String
    fun errPackUnsupportedVersion(v: Int): String
    val errPackBadMeta: String
    val errPackBadIndex: String
    fun errPackBlockOutOfBounds(b: Int): String
    val errAiModelEmpty: String
    val errAiMissingGeminiKey: String
    val errAiMissingVertexExpressKey: String
    val errAiMissingVertexProject: String
    val errAiMissingVertexToken: String
    val errAiMissingVertexSaJson: String
    val errAiCleartextWarning: String
    val errSaNotValidJson: String
    fun errSaWrongType(type: String): String
    val errSaMissingEmail: String
    val errSaMissingPrivateKey: String
    val errSaNotPem: String
    val errSaPkcs1NotSupported: String
    val errSaBadBase64Pem: String
    fun errSaCannotImportKey(detail: String): String
    val errSaProxyHint: String
    fun errSaTokenEndpointUnreachable(host: String, detail: String, hint: String): String
    val errSaCheckClockHint: String
    fun errSaTokenExchangeFailed(status: Int, body: String, hint: String): String
    val errSaTokenNonJson: String
    val errSaTokenMissingAccessToken: String
    fun errAiTimedOutMs(ms: Int, where: String): String
    fun errAiInvalidRequest(where: String, detail: String): String
    fun errAiUnparseableContent(detail: String): String
    val errAiTruncatedMaxTokens: String
    fun errAiCannotAssemble(detail: String): String
    val errAiEmptyQuestion: String
    val errAiEmptyAnswer: String
    val errAiEmptyModelOutput: String
    fun errAiInvalidModelJson(detail: String): String
    val errAiOutputNotObject: String
    val errAiOutputMissingTranslation: String
    fun errAiNonJsonBody(body: String): String
    val errAiNoCandidates: String

    companion object {
        fun forLanguage(lang: ResolvedLanguage): Wa2Strings = when (lang) {
            ResolvedLanguage.ZH -> Wa2StringsZh
            ResolvedLanguage.EN -> Wa2StringsEn
            ResolvedLanguage.JA -> Wa2StringsJa
        }
    }
}
