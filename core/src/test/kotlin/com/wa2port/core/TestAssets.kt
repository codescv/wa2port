package com.wa2port.core

import com.wa2port.core.archive.Wa2Assets
import org.junit.jupiter.api.Assumptions.assumeTrue
import java.io.File

/** Shared access to the real WA2 install used by the desktop test-suite. */
object TestAssets {
    val root: File = File(
        System.getProperty("wa2.root")
            ?: System.getenv("WA2_ROOT")
            ?: listOf(File("resource/WA2"), File("../resource/WA2"), File("../../resource/WA2"), File("../../../resource/WA2"))
                .firstOrNull { it.isDirectory }?.canonicalPath
            ?: "../resource/WA2"
    )

    val available: Boolean get() = File(root, "script.pak").isFile || File(root, "ck-gal.pak").isFile

    val assets: Wa2Assets by lazy { Wa2Assets(root) }

    fun require(): Wa2Assets {
        assumeTrue(available, "WA2 resources not found at $root")
        return assets
    }
}
