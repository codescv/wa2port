package com.wa2port.core.ai

import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long
import kotlinx.serialization.json.put
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.net.URLDecoder
import java.security.KeyPair
import java.security.KeyPairGenerator
import java.security.Signature
import java.util.Base64

class ServiceAccountJwtTest {
    companion object {
        val keyPair: KeyPair = KeyPairGenerator.getInstance("RSA").apply { initialize(2048) }.generateKeyPair()

        fun pem(): String {
            val b64 = Base64.getMimeEncoder(64, "\n".toByteArray()).encodeToString(keyPair.private.encoded)
            return "-----BEGIN PRIVATE KEY-----\n$b64\n-----END PRIVATE KEY-----\n"
        }

        /** SA JSON as Google emits it (newlines in the key escaped as `\n`). */
        fun saJson(tokenUri: String = "https://oauth2.googleapis.com/token"): String = buildJsonObject {
            put("type", "service_account")
            put("project_id", "gps-babel-tower")
            put("private_key_id", "kid123")
            put("private_key", pem())
            put("client_email", "bot@gps-babel-tower.iam.gserviceaccount.com")
            put("token_uri", tokenUri)
        }.toString()
    }

    private fun b64(s: String) = String(Base64.getUrlDecoder().decode(s), Charsets.UTF_8)

    @Test
    fun parsesGoogleJson() {
        val k = ServiceAccountJwt.parse(saJson())
        assertEquals("bot@gps-babel-tower.iam.gserviceaccount.com", k.clientEmail)
        assertEquals("kid123", k.privateKeyId)
        assertEquals("gps-babel-tower", k.projectId)
        assertTrue(!k.toString().contains("PRIVATE KEY"))
        for (bad in listOf("nope", "{}", """{"type":"authorized_user","client_email":"a","private_key":"-----BEGIN PRIVATE KEY-----"}""", """{"client_email":"a","private_key":"zzz"}""")) {
            assertEquals(AiErrorCode.AUTH, assertThrows(AiException::class.java) { ServiceAccountJwt.parse(bad) }.code)
        }
    }

    @Test
    fun pemParsing() {
        val der = ServiceAccountJwt.pemToDer(pem().replace("\n", "\\n")) // literal \n escapes
        assertTrue(der.contentEquals(keyPair.private.encoded))
        val pkcs1 = assertThrows(AiException::class.java) { ServiceAccountJwt.pemToDer("-----BEGIN RSA PRIVATE KEY-----\nAAAA\n-----END RSA PRIVATE KEY-----") }
        assertTrue(pkcs1.message!!.contains("PKCS#8"))
        assertThrows(AiException::class.java) { ServiceAccountJwt.pemToDer("-----BEGIN PRIVATE KEY-----\n!!!!\n-----END PRIVATE KEY-----") }
    }

    @Test
    fun signsVerifiableRs256() {
        val key = ServiceAccountJwt.parse(saJson())
        val now = 1_700_000_000_000L
        val jwt = ServiceAccountJwt.signJwt(key, now)
        val (h, p, s) = jwt.split('.')
        val header = AiJson.parseToJsonElement(b64(h)).jsonObject
        val payload = AiJson.parseToJsonElement(b64(p)).jsonObject
        assertEquals("RS256", header["alg"]!!.jsonPrimitive.content)
        assertEquals("JWT", header["typ"]!!.jsonPrimitive.content)
        assertEquals("kid123", header["kid"]!!.jsonPrimitive.content)
        assertEquals(key.clientEmail, payload["iss"]!!.jsonPrimitive.content)
        assertEquals(key.tokenUri, payload["aud"]!!.jsonPrimitive.content)
        assertEquals(ServiceAccountJwt.CLOUD_PLATFORM_SCOPE, payload["scope"]!!.jsonPrimitive.content)
        val iat = payload["iat"]!!.jsonPrimitive.long
        assertEquals(now / 1000 - 30, iat) // clock-skew allowance
        assertEquals(3600L, payload["exp"]!!.jsonPrimitive.long - iat)
        val ok = Signature.getInstance("SHA256withRSA").run {
            initVerify(keyPair.public)
            update("$h.$p".toByteArray())
            verify(Base64.getUrlDecoder().decode(s))
        }
        assertTrue(ok)
        assertTrue(!jwt.contains('=') && !jwt.contains('+') && !jwt.contains('/'))
    }

    @Test
    fun tokenProviderCachesAndRefreshes() {
        var clock = 1_000_000L
        val t = FakeTransport(
            """{"access_token":"T1","expires_in":3600}""",
            """{"access_token":"T2","expires_in":"3600"}""",
        )
        val p = ServiceAccountTokenProvider(saJson("https://tok.example.com/token"), t, now = { clock })
        assertEquals("T1", p.token())
        clock += 3_000_000 // < exp − 60 s
        assertEquals("T1", p.token())
        assertEquals(1, t.calls.size)
        clock += 600_000 // past exp − 60 s
        assertEquals("T2", p.token())
        assertEquals(2, t.calls.size)
        val call = t.calls[0]
        assertEquals("https://tok.example.com/token", call.url)
        assertEquals("application/x-www-form-urlencoded", call.headers["Content-Type"])
        val form = call.body.split('&').associate { it.substringBefore('=') to URLDecoder.decode(it.substringAfter('='), "UTF-8") }
        assertEquals("urn:ietf:params:oauth:grant-type:jwt-bearer", form["grant_type"])
        assertEquals(3, form["assertion"]!!.split('.').size)
        assertNotEquals("", p.keyFingerprint)
    }

    @Test
    fun tokenProviderErrors() {
        val denied = ServiceAccountTokenProvider(saJson(), FakeTransport(HttpTransport.Response(400, """{"error":"invalid_grant","error_description":"Invalid JWT Signature."}""")))
        val e = assertThrows(AiException::class.java) { denied.token() }
        assertEquals(AiErrorCode.AUTH, e.code)
        assertTrue(e.message!!.contains("设备时间"))
        val noToken = ServiceAccountTokenProvider(saJson(), FakeTransport("""{"foo":1}"""))
        assertEquals(AiErrorCode.AUTH, assertThrows(AiException::class.java) { noToken.token() }.code)
        val offline = ServiceAccountTokenProvider(saJson(), FakeTransport(java.io.IOException("down")), viaProxy = true)
        val net = assertThrows(AiException::class.java) { offline.token() }
        assertEquals(AiErrorCode.NETWORK, net.code)
        assertTrue(net.message!!.contains("oauth2.googleapis.com"))
        assertEquals(JsonPrimitive("x"), JsonPrimitive("x"))
    }
}
