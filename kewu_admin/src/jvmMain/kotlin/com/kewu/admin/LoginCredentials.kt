package com.kewu.admin

import java.io.File
import java.util.Base64
import java.util.Properties

/**
 * 总控端本地登录凭证（2026-11-17，1.0.18）。
 *
 * 提供「记住密码 / 自动登录」：凭证做轻量混淆后存到 `<user.home>/.kewu-admin/auth.properties`。
 * 注意：这是本地团队工具的便利性存储，混淆不是强加密，不能防专业逆向；
 * 真正的管理员密码在服务器 kewu.env / MySQL（BCrypt），这里是本机快速登录副本。
 */
object LoginCredentials {
    private val dir = File(System.getProperty("user.home"), "kewu-admin")
    private val file = File(dir, "auth.properties")

    data class Saved(
        val username: String,
        val password: String,
        val remember: Boolean,
        val autoLogin: Boolean,
    )

    fun load(): Saved? {
        if (!file.isFile) return null
        return runCatching {
            val p = Properties()
            file.inputStream().use { p.load(it) }
            val remember = p.getProperty("remember", "false").toBoolean()
            val autoLogin = p.getProperty("autoLogin", "false").toBoolean()
            val user = p.getProperty("username", "").trim()
            val pwd = decode(p.getProperty("password", ""))
            if (user.isEmpty() || pwd.isEmpty()) null
            else Saved(user, pwd, remember, autoLogin)
        }.getOrNull()
    }

    /** remember=false 时清除已存凭证；autoLogin 隐含 remember。 */
    fun save(username: String, password: String, remember: Boolean, autoLogin: Boolean) {
        if (!dir.exists()) dir.mkdirs()
        if (!remember && !autoLogin) {
            runCatching { if (file.isFile) file.delete() }
            return
        }
        val p = Properties()
        p.setProperty("username", username.trim())
        p.setProperty("password", encode(password))
        p.setProperty("remember", remember.toString())
        p.setProperty("autoLogin", autoLogin.toString())
        runCatching { file.outputStream().use { p.store(it, "kewu-admin local login (obfuscated, not strong encryption)") } }
    }

    fun clear() = runCatching { if (file.isFile) file.delete() }

    /** 改密成功后同步本地记住的密码，避免自动登录用旧密码失败。 */
    fun updatePassword(newPassword: String) {
        val cur = load() ?: return
        save(cur.username, newPassword, cur.remember, cur.autoLogin)
    }

    // 轻量混淆：固定前缀 XOR 后 Base64，避免明文落盘；仅防肩窥，不防逆向。
    private val mask = "kewu-admin-local-credential-v1".toByteArray(Charsets.UTF_8)
    private fun encode(plain: String): String {
        val bytes = plain.toByteArray(Charsets.UTF_8)
        val xored = ByteArray(bytes.size) { (bytes[it].toInt() xor mask[it % mask.size].toInt()).toByte() }
        return "obf1:" + Base64.getEncoder().encodeToString(xored)
    }
    private fun decode(stored: String): String {
        if (!stored.startsWith("obf1:")) return stored // 兼容旧明文
        val bytes = Base64.getDecoder().decode(stored.removePrefix("obf1:"))
        val xored = ByteArray(bytes.size) { (bytes[it].toInt() xor mask[it % mask.size].toInt()).toByte() }
        return String(xored, Charsets.UTF_8)
    }
}
