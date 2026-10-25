package com.wa2port.android.bridge

import android.content.Context
import com.wa2port.core.engine.Wa2Engine

/** Chooses the [RenderBackend]. Swap point for @render's StageCanvas/GlyphAtlasText/AudioEngine. */
object RenderBackends {
    /** Set to true to bypass the renderer (diagnostics stage, system-font text, no audio). */
    var useStub = false

    fun create(context: Context, engine: Wa2Engine): RenderBackend =
        if (useStub) StubRenderBackend(engine.decoder) else Wa2RenderBackend(context.applicationContext, engine)
}
