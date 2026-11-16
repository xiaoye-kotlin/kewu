package com.kewu.admin

import java.util.Base64
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * 本地凭证混淆存取的纯逻辑测试。
 * LoginCredentials 的编解码是私有方法，这里通过反射验证「混淆后不明文 + 可逆」。
 */
class LoginCredentialsTest {

    private fun invokePrivate(name: String, arg: String): String {
        val m = LoginCredentials::class.java.getDeclaredMethod(name, String::class.java)
        m.isAccessible = true
        return m.invoke(LoginCredentials, arg) as String
    }

    @Test
    fun `obfuscation hides plaintext and is reversible`() {
        val plain = "admin123456"
        val encoded = invokePrivate("encode", plain)
        assertTrue(encoded.startsWith("obf1:"))
        assertFalse(encoded.contains(plain), "混淆后不应出现明文密码")
        val decoded = invokePrivate("decode", encoded)
        assertEquals(plain, decoded)
    }

    @Test
    fun `decode supports legacy plaintext`() {
        assertEquals("legacy-pass", invokePrivate("decode", "legacy-pass"))
    }

    @Test
    fun `base64 payload is not plaintext`() {
        val encoded = invokePrivate("encode", "S3cretPwd")
        val payload = encoded.removePrefix("obf1:")
        val raw = String(Base64.getDecoder().decode(payload))
        assertFalse(raw.contains("S3cretPwd"))
    }
}
