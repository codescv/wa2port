package com.wa2port.core.script

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/** A value on the LSCR evaluation stack (and in variables). */
@Serializable
sealed class Value {
    @Serializable @SerialName("i")
    data class I(val v: Int) : Value()

    @Serializable @SerialName("f")
    data class F(val v: Float) : Value()

    /** A `.txt` token reference `(3, X)`; resolved to JP/CN text by the engine. */
    @Serializable @SerialName("t")
    data class Text(val token: Int) : Value()

    @Serializable @SerialName("s")
    data class Str(val s: String) : Value()

    /** `(1, X)`: global variable X (`Global.vrb`). */
    @Serializable @SerialName("g")
    data class GRef(val index: Int) : Value()

    /** `(2, X)`: local array X (before indexing). */
    @Serializable @SerialName("la")
    data class LArr(val index: Int) : Value()

    /** `(2, X) idx (6, 27)`: element of local array X. */
    @Serializable @SerialName("lr")
    data class LRef(val index: Int, val elem: Int) : Value()

    val isRef: Boolean get() = this is GRef || this is LRef || this is LArr

    companion object {
        val ZERO = I(0)
        val ONE = I(1)
        fun of(b: Boolean) = if (b) ONE else ZERO
    }
}
