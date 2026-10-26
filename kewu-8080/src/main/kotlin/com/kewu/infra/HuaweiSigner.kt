package com.kewu.infra

import java.security.MessageDigest
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec
import kotlin.text.Charsets.UTF_8

// 华为云 AK/SK 请求签名（SDK-HMAC-SHA256 / V11 算法）。
//
// 为什么不用 X-Auth-Token：华为云 IAM 换 token 的标准接口（POST /v3/auth/tokens）走“账号密码”
// 认证，并不接收 AK/SK；而 AK/SK 在华为云里的标准用法就是直接对请求做签名（SIS 官方也支持）。
// 服务器上只配了 AK/SK、没有账号密码，所以这里用签名方式完成鉴权：
//   签名是本地纯计算，不联网、不过期，比“换 token 再缓存 24h”更省事，鉴权目的一样达成。
//
// 用法：对每个请求算一次 Authorization 头即可，请求头里必须同时带上 Host 和 X-Sdk-Date。
object HuaweiSigner {

    // 计算 Authorization 请求头的值。dateStamp 必须是 UTC 的 yyyyMMdd'T'HHmmss'Z' 格式。
    // ★ 2026-10-27 按华为云 APIG 官方签名规范（apig-sdk Signer）重写：旧版是仿 AWS SigV4 的
    //   「派生密钥 + scope + Credentials=」写法，华为云网关不认，SIS 会回 401 / APIG.0301。
    //   规范：CanonicalURI 末尾补 "/"；StringToSign = 算法、时间、hex(sha256(规范请求)) 三行；
    //   签名 = hex(HMAC-SHA256(SK, StringToSign))；头为 "SDK-HMAC-SHA256 Access=AK, SignedHeaders=..., Signature=..."。
    //   region / service 参数保留只为兼容旧调用，APIG 签名不使用。
    @Suppress("UNUSED_PARAMETER")
    fun authHeader(
        ak: String, sk: String,
        method: String, uri: String, body: String,
        host: String, region: String, service: String,
        dateStamp: String
    ): String {
        val path = uri.substringBefore('?')
        val canonicalUri = if (path.endsWith("/")) path else "$path/"
        val canonicalQuery = uri.substringAfter('?', "").split('&').filter { it.isNotEmpty() }.sorted().joinToString("&")
        // 只签 host + x-sdk-date（华为云允许的最小签名头集），请求里必须同时带这两个头
        val canonicalHeaders = "host:$host\nx-sdk-date:$dateStamp\n"
        val signedHeaders = "host;x-sdk-date"
        val canonicalRequest = "$method\n$canonicalUri\n$canonicalQuery\n$canonicalHeaders\n$signedHeaders\n" +
            sha256Hex(body.toByteArray(UTF_8))
        val stringToSign = "SDK-HMAC-SHA256\n$dateStamp\n" + sha256Hex(canonicalRequest.toByteArray(UTF_8))
        val signature = hex(hmacSha256(sk.toByteArray(UTF_8), stringToSign.toByteArray(UTF_8)))
        return "SDK-HMAC-SHA256 Access=$ak, SignedHeaders=$signedHeaders, Signature=$signature"
    }

    private fun sha256Hex(data: ByteArray): String =
        hex(MessageDigest.getInstance("SHA-256").digest(data))

    private fun hmacSha256(key: ByteArray, data: ByteArray): ByteArray {
        val mac = Mac.getInstance("HmacSHA256")
        mac.init(SecretKeySpec(key, "HmacSHA256"))
        return mac.doFinal(data)
    }

    private val HEX = "0123456789abcdef".toCharArray()
    private fun hex(bytes: ByteArray): String {
        val sb = StringBuilder(bytes.size * 2)
        for (b in bytes) {
            val v = b.toInt() and 0xff
            sb.append(HEX[v ushr 4])
            sb.append(HEX[v and 0x0f])
        }
        return sb.toString()
    }
}
