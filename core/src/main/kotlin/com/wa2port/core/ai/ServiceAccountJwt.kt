package com.wa2port.core.ai

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.put
import java.io.IOException
import java.net.URLEncoder
import java.security.KeyFactory
import java.security.PrivateKey
import java.security.Signature
import java.security.spec.PKCS8EncodedKeySpec
import java.util.Base64

/** Parsed Google service-account key. */
data class ServiceAccountKey(
    val clientEmail: String,
    val privateKeyPem: String,
    val tokenUri: String = ServiceAccountJwt.DEFAULT_TOKEN_URI,
    val projectId: String? = null,
    val privateKeyId: String? = null,
) {
    override fun toString(): String = "ServiceAccountKey(clientEmail=$clientEmail, tokenUri=$tokenUri, projectId=$projectId, privateKeyId=$privateKeyId)"
}

/** RS256 JWT signing for the OAuth2 JWT-bearer flow (port of kotori `ai/service-account.ts`, JCA based). */
object ServiceAccountJwt {
    const val CLOUD_PLATFORM_SCOPE = "https://www.googleapis.com/auth/cloud-platform"
    const val DEFAULT_TOKEN_URI = "https://oauth2.googleapis.com/token"

    /** Seconds subtracted from `iat` to absorb small device clock skew. */
    const val CLOCK_SKEW_SECONDS = 30L

    private fun JsonObject.str(k: String): String? = (this[k] as? JsonPrimitive)?.contentOrNull?.takeIf { it.isNotBlank() }

    /** Parses a service-account JSON key; [AiException] AUTH with a clear reason when unusable. */
    fun parse(json: String): ServiceAccountKey {
        val root = runCatching { AiJson.parseToJsonElement(json.trim()) as? JsonObject }.getOrNull()
            ?: throw AiException(AiErrorCode.AUTH, "服务账号 JSON 无法解析 Service account key is not valid JSON")
        val type = root.str("type")
        if (type != null && type != "service_account") throw AiException(AiErrorCode.AUTH, "JSON 不是服务账号密钥 (type=$type)")
        val email = root.str("client_email") ?: throw AiException(AiErrorCode.AUTH, "服务账号 JSON 缺少 client_email")
        val pk = root.str("private_key") ?: throw AiException(AiErrorCode.AUTH, "服务账号 JSON 缺少 private_key")
        if (!pk.contains("PRIVATE KEY")) throw AiException(AiErrorCode.AUTH, "private_key 不是 PEM 格式 Not a PEM private key")
        return ServiceAccountKey(
            clientEmail = email,
            privateKeyPem = pk,
            tokenUri = root.str("token_uri") ?: DEFAULT_TOKEN_URI,
            projectId = root.str("project_id"),
            privateKeyId = root.str("private_key_id"),
        )
    }

    /** PKCS#8 PEM → DER. Handles literal `\n` escapes and whitespace; PKCS#1 (`BEGIN RSA PRIVATE KEY`) is rejected. */
    fun pemToDer(pem: String): ByteArray {
        val text = pem.replace("\\n", "\n")
        if (text.contains("BEGIN RSA PRIVATE KEY")) {
            throw AiException(AiErrorCode.AUTH, "需要 PKCS#8 私钥 (BEGIN PRIVATE KEY)；PKCS#8 key required, got PKCS#1")
        }
        val body = text
            .replace(Regex("-----BEGIN [^-]+-----"), "")
            .replace(Regex("-----END [^-]+-----"), "")
            .filterNot { it.isWhitespace() }
        return try {
            Base64.getDecoder().decode(body)
        } catch (e: IllegalArgumentException) {
            throw AiException(AiErrorCode.AUTH, "private_key 不是有效的 base64 PEM", e)
        }
    }

    fun base64Url(bytes: ByteArray): String = Base64.getUrlEncoder().withoutPadding().encodeToString(bytes)

    private fun privateKey(pem: String): PrivateKey = try {
        KeyFactory.getInstance("RSA").generatePrivate(PKCS8EncodedKeySpec(pemToDer(pem)))
    } catch (e: AiException) {
        throw e
    } catch (e: Exception) {
        throw AiException(AiErrorCode.AUTH, "无法导入服务账号私钥 Cannot import private key: ${e.message}", e)
    }

    /**
     * Builds and signs the RS256 assertion: header `{alg:RS256, typ:JWT[, kid]}`, payload
     * `{iss, scope, aud = tokenUri, iat = now − 30 s, exp = iat + 3600}`.
     */
    fun signJwt(key: ServiceAccountKey, nowMs: Long, scope: String = CLOUD_PLATFORM_SCOPE): String {
        val iat = nowMs / 1000 - CLOCK_SKEW_SECONDS
        val header = buildJsonObject {
            put("alg", "RS256")
            put("typ", "JWT")
            key.privateKeyId?.let { put("kid", it) }
        }
        val payload = buildJsonObject {
            put("iss", key.clientEmail)
            put("scope", scope)
            put("aud", key.tokenUri)
            put("iat", iat)
            put("exp", iat + 3600)
        }
        val signingInput = base64Url(header.toString().toByteArray(Charsets.UTF_8)) + "." + base64Url(payload.toString().toByteArray(Charsets.UTF_8))
        val sig = Signature.getInstance("SHA256withRSA").run {
            initSign(privateKey(key.privateKeyPem))
            update(signingInput.toByteArray(Charsets.UTF_8))
            sign()
        }
        return "$signingInput.${base64Url(sig)}"
    }
}

/**
 * Cached OAuth2 access-token provider for a service-account key. Tokens are reused until 60 s
 * before expiry. Failures: non-2xx / non-JSON / no `access_token` → AUTH (with clock-skew hint on
 * `invalid_grant`), I/O → NETWORK (with a proxy hint when [viaProxy]).
 */
class ServiceAccountTokenProvider(
    json: String,
    private val transport: HttpTransport,
    private val now: () -> Long = System::currentTimeMillis,
    private val viaProxy: Boolean = false,
    private val timeoutMs: Int = 30_000,
) {
    private val key = ServiceAccountJwt.parse(json)

    /** Fingerprint of the key material (cache key for provider reuse). */
    val keyFingerprint: String = ExplainCache.sha256Hex(json.trim()).take(16)

    private var cached: Pair<String, Long>? = null

    @Synchronized
    fun token(cancel: CancelToken? = null): String {
        cached?.let { (t, exp) -> if (now() < exp - REFRESH_SKEW_MS) return t }
        val issuedAt = now()
        val assertion = ServiceAccountJwt.signJwt(key, issuedAt)
        val body = "grant_type=" + URLEncoder.encode("urn:ietf:params:oauth:grant-type:jwt-bearer", "UTF-8") +
            "&assertion=" + URLEncoder.encode(assertion, "UTF-8")
        val res = try {
            transport.post(key.tokenUri, mapOf("Content-Type" to "application/x-www-form-urlencoded"), body.toByteArray(Charsets.UTF_8), timeoutMs, cancel)
        } catch (e: IOException) {
            if (cancel?.isCancelled == true) throw AiException(AiErrorCode.CANCELLED, "Request cancelled", e)
            val hint = if (viaProxy) "；服务账号换取令牌需直连 oauth2.googleapis.com，走代理时建议用 Access Token / Express Key 或由代理注入鉴权" else ""
            throw AiException(AiErrorCode.NETWORK, "令牌服务器不可达 Token endpoint unreachable (${AiEndpoints.hostOf(key.tokenUri)}): ${e.message}$hint", e)
        }
        if (res.status !in 200..299) {
            val skew = if (Regex("invalid_grant|Invalid JWT", RegexOption.IGNORE_CASE).containsMatchIn(res.body)) "（请检查设备时间是否准确 check device clock）" else ""
            throw AiException(AiErrorCode.AUTH, "服务账号换取令牌失败 Token exchange failed (HTTP ${res.status}): ${res.body.take(200)}$skew", httpStatus = res.status)
        }
        val obj = runCatching { AiJson.parseToJsonElement(res.body) as? JsonObject }.getOrNull()
            ?: throw AiException(AiErrorCode.AUTH, "令牌服务器返回了非 JSON Token endpoint returned non-JSON")
        val token = (obj["access_token"] as? JsonPrimitive)?.contentOrNull?.takeIf { it.isNotBlank() }
            ?: throw AiException(AiErrorCode.AUTH, "令牌服务器未返回 access_token")
        val expiresIn = (obj["expires_in"] as? JsonPrimitive)?.contentOrNull?.toLongOrNull()?.takeIf { it > 0 } ?: 3600L
        cached = token to issuedAt + expiresIn * 1000
        return token
    }

    companion object {
        const val REFRESH_SKEW_MS = 60_000L
    }
}
