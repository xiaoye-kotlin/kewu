package com.kewu.recserver.services.storage

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

// 本地磁盘兜底存储：没配 OBS 时开发用，部署时切到 ObsStorageService 即可
class LocalStorageService(private val dir: String) : AudioStorage {
    private val root = File(dir).apply { if (!exists()) mkdirs() }

    // ★ 安全修复（2026-11-19）：save/saveObject 的文件名、get 的 local:// 路径此前未做限制，
    //   攻击者可用 `../` 或绝对路径把文件写到存储根目录之外、或读取服务器上任意文件。
    //   统一用 canonical 路径校验：解析符号链接与 .. 后，必须仍落在 root 目录内。
    private val rootCanonical: File by lazy { runCatching { root.canonicalFile }.getOrDefault(root.absoluteFile) }

    /** 解析 root 下的相对路径；越界（绝对路径 / ../ / 符号链接逃逸）返回 null。 */
    private fun resolveSafe(relative: String): File? {
        if (relative.isBlank()) return null
        val target = runCatching { File(rootCanonical, relative).canonicalFile }.getOrNull() ?: return null
        return target.takeIf { insideRoot(it) }
    }

    private fun insideRoot(f: File): Boolean {
        val p = f.path
        val base = rootCanonical.path
        return p == base || p.startsWith(base + File.separator)
    }

    override suspend fun save(recordingId: String, bytes: ByteArray, ext: String, metadata: Map<String, String>): String {
        // 文件名做白名单清洗，杜绝分隔符 / 盘符 / 控制字符
        val safeName = recordingId.replace(Regex("[^A-Za-z0-9_\\-.]"), "_") + "." + ext.replace(Regex("[^A-Za-z0-9]"), "")
        val file = resolveSafe(safeName) ?: throw IllegalArgumentException("非法录音 ID")
        val metaFile = resolveSafe("${safeName}.meta.json") ?: throw IllegalArgumentException("非法录音 ID")
        withContext(Dispatchers.IO) {
            file.writeBytes(bytes)
            if (metadata.isNotEmpty()) {
                // 本地模式把元数据写成同名 sidecar json，开发时对着音频文件就能看
                val json = metadata.entries.joinToString(",", "{", "}") { (k, v) ->
                    "\"${k.replace("\"", "'")}\":\"${v.replace("\"", "'")}\""
                }
                metaFile.writeText(json)
            }
        }
        return "local://${file.absolutePath}"
    }

    override suspend fun saveObject(key: String, bytes: ByteArray, contentType: String): String {
        // key 可能含子目录（如 submissions/abc.pdf），逐段校验，拒绝任何 .. / 绝对路径逃逸
        val file = resolveSafe(key) ?: throw IllegalArgumentException("非法存储 key")
        withContext(Dispatchers.IO) {
            file.parentFile?.mkdirs()
            file.writeBytes(bytes)
        }
        return "local://${file.absolutePath}"
    }

    override suspend fun get(url: String): ByteArray? {
        val path = url.removePrefix("local://")
        val file = runCatching { File(path).canonicalFile }.getOrNull() ?: return null
        if (!insideRoot(file)) return null
        if (!file.exists() || !file.isFile) return null
        return withContext(Dispatchers.IO) { file.readBytes() }
    }
}
