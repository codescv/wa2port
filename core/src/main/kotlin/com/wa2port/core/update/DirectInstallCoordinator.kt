package com.wa2port.core.update

import java.io.File

/**
 * Next step decided by [DirectInstallCoordinator] (`v1.4.7`: tapping `下载并安装` downloads, verifies
 * and installs in one go — no second "Install" tap).
 */
sealed interface DirectInstallAction {
    /** Nothing to launch (no pending APK, permission still not granted, or the APK vanished). */
    data object None : DirectInstallAction

    /** Launch the system package installer for [apkFile] right away. */
    data class LaunchInstaller(val apkFile: File) : DirectInstallAction

    /**
     * "Install unknown apps" is not granted: open `ACTION_MANAGE_UNKNOWN_APP_SOURCES`; [apkFile] stays
     * pending and is installed automatically once the user grants it and returns to the app.
     */
    data class RequestInstallPermission(val apkFile: File) : DirectInstallAction
}

/**
 * Pure (Android-free, unit-testable) state machine for the "download → verify → install directly" flow.
 *
 * - [onApkVerified]: called as soon as the downloaded APK passed validation (or when the user re-taps
 *   `重新安装 Install` after cancelling the system installer). Launches the installer immediately when
 *   allowed; otherwise records the APK as pending and asks for the unknown-sources permission.
 * - [onAppForegrounded]: called from `Activity.onResume`. If an APK is pending and the permission is now
 *   granted, clears the pending flag and launches the installer automatically.
 * - [restorePending]: re-arms a pending install after process death (Android may kill the app when the
 *   unknown-sources permission is toggled), from a persisted flag + the restored APK.
 */
class DirectInstallCoordinator {

    @Volatile
    var pendingInstallApkFile: File? = null
        private set

    val pendingInstallAfterSettings: Boolean
        get() = pendingInstallApkFile != null

    fun onApkVerified(apkFile: File, canRequestPackageInstalls: Boolean): DirectInstallAction =
        if (canRequestPackageInstalls) {
            pendingInstallApkFile = null
            DirectInstallAction.LaunchInstaller(apkFile)
        } else {
            pendingInstallApkFile = apkFile
            DirectInstallAction.RequestInstallPermission(apkFile)
        }

    fun onAppForegrounded(canRequestPackageInstalls: Boolean): DirectInstallAction {
        val pending = pendingInstallApkFile ?: return DirectInstallAction.None
        if (!canRequestPackageInstalls) return DirectInstallAction.None
        pendingInstallApkFile = null
        return if (pending.isFile) DirectInstallAction.LaunchInstaller(pending) else DirectInstallAction.None
    }

    /** Re-arms [apkFile] as pending when [wasPending] was persisted before the process died. */
    fun restorePending(apkFile: File?, wasPending: Boolean) {
        pendingInstallApkFile = if (wasPending && apkFile != null && apkFile.isFile) apkFile else null
    }

    fun clearPending() {
        pendingInstallApkFile = null
    }
}
