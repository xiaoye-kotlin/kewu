package com.kewu.app

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** 本机文件条目（存储管理扫描用）。 */
data class LocalFile(val path: String, val size: Long)

/** 递归列出 [dir] 下的全部文件（目录不存在返回空）。 */
expect fun platformListFiles(dir: String): List<LocalFile>

/** 崩溃日志目录（仅 Android 有；其它平台 null）。 */
expect fun platformCrashDir(): String?

/**
 * 「我的 → 内存管理」的数据层（2026-10-26）：统计本软件在本机占用的空间，并按类别清理。
 *
 * 类别口径：
 * - [Category.CHAT]：小悟聊天记录（kv 里按账号存的 JSON，按正文 UTF-8 字节估算）；细粒度删除走 [ChatHistory]。
 * - [Category.IMAGE]：图片缓存（[ImageLoader] 的内存缓存：查看过的作业图片 / 附件缩略图）。
 * - [Category.ATTACH]：附件临时文件（选择器拷进 `cacheDir/attach/` 的文件、作业里录的语音）。
 * - [Category.REC_TEMP]：录音残留（`kewu_rec_*.pcm` 里既不是正在录 / 上传、也不在待重传列表里的文件）。
 * - [Category.OTHER]：缓存目录里的其它文件 + 崩溃日志。
 * - 待重传录音（[StorageReport.pendingRecBytes]）**受保护**：那是还没传上去的课堂录音，只在「录音管理」里处理。
 */
object StorageManager {

    enum class Category { CHAT, IMAGE, ATTACH, REC_TEMP, OTHER }

    data class Item(val category: Category, val bytes: Long, val count: Int)

    data class StorageReport(
        val items: Map<Category, Item>,
        val pendingRecBytes: Long,
        val pendingRecCount: Int,
    ) {
        val total: Long get() = items.values.sumOf { it.bytes } + pendingRecBytes
        operator fun get(c: Category): Item = items[c] ?: Item(c, 0, 0)
    }

    /** 当前角色的聊天 key（学生 / 教师各一份，均按账号隔离）。 */
    fun chatKey(): String = if (currentRole() == ROLE_TEACHER) ChatHistory.KEY_TEACHER else ChatHistory.KEY_STUDENT

    private fun attachDir() = platformCacheDir() + "/attach"

    /** 正在用、不能删的录音文件：正在录 / 上传时整类跳过；失败待重传的文件也排除。 */
    private fun protectedRecPaths(): Set<String> {
        val paths = RecordingManager.history.value.mapNotNull { it.filePath }.toMutableSet()
        (RecordingManager.status.value as? RecordingStatus.Error)?.filePath?.let { paths += it }
        return paths
    }

    private fun recBusy(): Boolean = when (RecordingManager.status.value) {
        is RecordingStatus.Recording, is RecordingStatus.Uploading -> true
        else -> false
    }

    private fun isRecFile(f: LocalFile) = f.path.substringAfterLast('/').substringAfterLast('\\').startsWith("kewu_rec_")
    private fun isAttach(f: LocalFile) = f.path.replace('\\', '/').contains("/attach/")

    suspend fun scan(): StorageReport = withContext(Dispatchers.Default) {
        val cacheFiles = runCatching { platformListFiles(platformCacheDir()) }.getOrDefault(emptyList())
        val crashFiles = platformCrashDir()?.let { runCatching { platformListFiles(it) }.getOrNull() }.orEmpty()
        val protected = protectedRecPaths()
        val recFiles = cacheFiles.filter { isRecFile(it) }
        val pending = recFiles.filter { it.path in protected }
        val recTemp = recFiles.filter { it.path !in protected }
        val attach = cacheFiles.filter { isAttach(it) }
        val other = cacheFiles.filter { !isRecFile(it) && !isAttach(it) } + crashFiles
        // ChatHistory 的内存缓存只在主线程读写
        val chat = withContext(Dispatchers.Main) { ChatHistory.storedBytes(chatKey()) }
        val image = ImageLoader.cacheStats()
        StorageReport(
            items = mapOf(
                Category.CHAT to Item(Category.CHAT, chat.first, chat.second),
                Category.IMAGE to Item(Category.IMAGE, image.first, image.second),
                Category.ATTACH to Item(Category.ATTACH, attach.sumOf { it.size }, attach.size),
                Category.REC_TEMP to Item(Category.REC_TEMP, if (recBusy()) 0 else recTemp.sumOf { it.size },
                    if (recBusy()) 0 else recTemp.size),
                Category.OTHER to Item(Category.OTHER, other.sumOf { it.size }, other.size),
            ),
            pendingRecBytes = pending.sumOf { it.size },
            pendingRecCount = pending.size,
        )
    }

    /** 清理一类，返回释放的字节数（估算）。 */
    suspend fun clean(category: Category): Long = withContext(Dispatchers.Default) {
        when (category) {
            Category.CHAT -> {
                withContext(Dispatchers.Main) {
                    val freed = ChatHistory.storedBytes(chatKey()).first
                    ChatHistory.deleteWhere(chatKey()) { true }
                    AssistantHub.refreshUnread(chatKey())
                    freed
                }
            }
            Category.IMAGE -> ImageLoader.clear()
            Category.ATTACH -> deleteAll(platformListFiles(attachDir()))
            Category.REC_TEMP -> {
                if (recBusy()) 0L else {
                    val protected = protectedRecPaths()
                    deleteAll(platformListFiles(platformCacheDir()).filter { isRecFile(it) && it.path !in protected })
                }
            }
            Category.OTHER -> {
                val cache = platformListFiles(platformCacheDir()).filter { !isRecFile(it) && !isAttach(it) }
                val crash = platformCrashDir()?.let { platformListFiles(it) }.orEmpty()
                deleteAll(cache + crash)
            }
        }
    }

    private fun deleteAll(files: List<LocalFile>): Long {
        var freed = 0L
        files.forEach { f ->
            platformDeleteFile(f.path)
            if (platformFileSize(f.path) <= 0L) freed += f.size
        }
        return freed
    }
}
