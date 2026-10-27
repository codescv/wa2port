package com.wa2port.core.ai

import java.io.IOException

/** Recording [HttpTransport] that replays canned responses (or throws) in order. */
class FakeTransport(vararg responses: Any) : HttpTransport {
    data class Call(val url: String, val headers: Map<String, String>, val body: String, val timeoutMs: Int)

    val calls = mutableListOf<Call>()
    private val queue = ArrayDeque(responses.toList())

    /** Response used when the queue is empty. */
    var fallback: Any = HttpTransport.Response(500, "no canned response")

    /** Invoked before answering (e.g. to cancel mid-flight). */
    var onPost: ((Call, CancelToken?) -> Unit)? = null

    override fun post(url: String, headers: Map<String, String>, body: ByteArray, timeoutMs: Int, cancel: CancelToken?): HttpTransport.Response {
        val call = Call(url, headers, body.toString(Charsets.UTF_8), timeoutMs)
        calls += call
        cancel?.attach { }
        try {
            onPost?.invoke(call, cancel)
            if (cancel?.isCancelled == true) throw IOException("Socket closed")
            return when (val r = queue.removeFirstOrNull() ?: fallback) {
                is HttpTransport.Response -> r
                is String -> HttpTransport.Response(200, r)
                is Exception -> throw r
                else -> error("bad canned response $r")
            }
        } finally {
            cancel?.detach()
        }
    }

    companion object {
        /** Minimal `generateContent` success body whose candidate text is [text]. */
        fun geminiBody(text: String, finishReason: String = "STOP", modelVersion: String? = null): String {
            val escaped = kotlinx.serialization.json.JsonPrimitive(text).toString()
            val mv = modelVersion?.let { ",\"modelVersion\":\"$it\"" } ?: ""
            return "{\"candidates\":[{\"content\":{\"role\":\"model\",\"parts\":[{\"text\":$escaped}]},\"finishReason\":\"$finishReason\"}]," +
                "\"usageMetadata\":{\"promptTokenCount\":10,\"candidatesTokenCount\":5,\"thoughtsTokenCount\":2}$mv}"
        }
    }
}
