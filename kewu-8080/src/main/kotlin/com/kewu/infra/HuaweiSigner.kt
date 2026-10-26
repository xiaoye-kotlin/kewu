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
    fun authHeader(
        ak: String, sk: String,
        method: String, uri: String, body: String,
        host: String, region: String, service: String,
        dateStamp: String
    ): String {
        val bodyHash = sha256Hex(body.toByteArray(UTF_8))
        // 只签 host + x-sdk-date（华为云允许的最小签名头集），避免 content-type 细节不一致导致签名失败
        val canonicalHeaders = "host:$host\nx-sdk-date:$dateStamp\n"
        val signedHeaders = "host;x-sdk-date"
        val canonicalRequest = "$method\n$uri\n\n$canonicalHeaders\n$signedHeaders\n$bodyHash"

        val date = dateStamp.substring(0, 8) // 取 YYYYMMDD 部分
        val scope = "$date/$region/$service/signed-request"
        val stringToSign = "SDK-HMAC-SHA256\n$dateStamp\n$scope\n" +
            sha256Hex(canonicalRequest.toByteArray(UTF_8))

        val signingKey = deriveSigningKey(sk, date, region, service)
        val signature = hex(hmacSha256(signingKey, stringToSign.toByteArray(UTF_8)))

        return "SDK-HMAC-SHA256 Credentials=$ak/$scope, SignedHeaders=$signedHeaders, Signature=$signature"
    }

    private fun sha256Hex(data: ByteArray): String =
        hex(MessageDigest.getInstance("SHA-256").digest(data))

    private fun hmacSha256(key: ByteArray, data: ByteArray): ByteArray {
        val mac = Mac.getInstance("HmacSHA256")
        mac.init(SecretKeySpec(key, "HmacSHA256"))
        return mac.doFinal(data)
    }

    // 逐级派生签名密钥：SDK-HMAC-SHA256+SK -> date -> region -> service -> signed-request
    private fun deriveSigningKey(sk: String, date: String, region: String, service: String): ByteArray {
        var key = hmacSha256("SDK-HMAC-SHA256$sk".toByteArray(UTF_8), date.toByteArray(UTF_8))
        key = hmacSha256(key, region.toByteArray(UTF_8))
        key = hmacSha256(key, service.toByteArray(UTF_8))
        key = hmacSha256(key, "signed-request".toByteArray(UTF_8))
        return key
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
