package com.wa2port.core.update

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File

class DirectInstallCoordinatorTest {

    @TempDir
    lateinit var dir: File

    private fun apk(name: String = "wa2port-1.4.7.apk") = File(dir, name).apply { writeBytes(byteArrayOf(1, 2, 3)) }

    @Test
    fun `verified APK with permission launches installer immediately without pending`() {
        val c = DirectInstallCoordinator()
        val f = apk()
        assertEquals(DirectInstallAction.LaunchInstaller(f), c.onApkVerified(f, canRequestPackageInstalls = true))
        assertFalse(c.pendingInstallAfterSettings)
        assertNull(c.pendingInstallApkFile)
        // Nothing left to auto-install on resume.
        assertEquals(DirectInstallAction.None, c.onAppForegrounded(canRequestPackageInstalls = true))
    }

    @Test
    fun `missing permission requests it and auto installs once granted on resume`() {
        val c = DirectInstallCoordinator()
        val f = apk()
        assertEquals(DirectInstallAction.RequestInstallPermission(f), c.onApkVerified(f, canRequestPackageInstalls = false))
        assertTrue(c.pendingInstallAfterSettings)
        assertEquals(f, c.pendingInstallApkFile)

        // Returned without granting → stays pending, nothing launched.
        assertEquals(DirectInstallAction.None, c.onAppForegrounded(canRequestPackageInstalls = false))
        assertTrue(c.pendingInstallAfterSettings)

        // Granted → installer launched exactly once.
        assertEquals(DirectInstallAction.LaunchInstaller(f), c.onAppForegrounded(canRequestPackageInstalls = true))
        assertFalse(c.pendingInstallAfterSettings)
        assertEquals(DirectInstallAction.None, c.onAppForegrounded(canRequestPackageInstalls = true))
    }

    @Test
    fun `pending APK deleted meanwhile is dropped instead of launching a broken install`() {
        val c = DirectInstallCoordinator()
        val f = apk()
        c.onApkVerified(f, canRequestPackageInstalls = false)
        assertTrue(f.delete())
        assertEquals(DirectInstallAction.None, c.onAppForegrounded(canRequestPackageInstalls = true))
        assertFalse(c.pendingInstallAfterSettings)
    }

    @Test
    fun `re-trigger after the user cancelled the system installer launches again`() {
        val c = DirectInstallCoordinator()
        val f = apk()
        assertEquals(DirectInstallAction.LaunchInstaller(f), c.onApkVerified(f, true))
        // User dismissed the installer dialog and taps 重新安装 → launches again.
        assertEquals(DirectInstallAction.LaunchInstaller(f), c.onApkVerified(f, true))
    }

    @Test
    fun `clearPending cancels an armed auto install`() {
        val c = DirectInstallCoordinator()
        c.onApkVerified(apk(), canRequestPackageInstalls = false)
        c.clearPending()
        assertFalse(c.pendingInstallAfterSettings)
        assertEquals(DirectInstallAction.None, c.onAppForegrounded(canRequestPackageInstalls = true))
    }

    @Test
    fun `restorePending re-arms auto install after process death only when flagged and file exists`() {
        val f = apk()
        val c = DirectInstallCoordinator()
        c.restorePending(f, wasPending = true)
        assertEquals(f, c.pendingInstallApkFile)
        assertEquals(DirectInstallAction.LaunchInstaller(f), c.onAppForegrounded(canRequestPackageInstalls = true))

        val notFlagged = DirectInstallCoordinator()
        notFlagged.restorePending(f, wasPending = false)
        assertFalse(notFlagged.pendingInstallAfterSettings)

        val missing = DirectInstallCoordinator()
        missing.restorePending(File(dir, "gone.apk"), wasPending = true)
        assertFalse(missing.pendingInstallAfterSettings)
        missing.restorePending(null, wasPending = true)
        assertFalse(missing.pendingInstallAfterSettings)
    }
}
