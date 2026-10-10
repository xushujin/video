package com.azhan.qingying.data

import android.content.Context
import android.media.MediaMetadataRetriever
import android.net.Uri
import androidx.documentfile.provider.DocumentFile
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * 用 SAF 递归遍历用户选中的目录，收集视频文件。
 *
 * 只在「添加目录 / 手动重扫」时跑，结果落盘，所以冷启动不扫描。
 */
class VideoScanner(private val context: Context) {

    data class RawVideo(
        val uri: Uri,
        val fileName: String,
        val relPath: String,
        val size: Long,
        val modified: Long,
    )

    suspend fun scan(treeUri: Uri, onProgress: (Int) -> Unit = {}): List<RawVideo> =
        withContext(Dispatchers.IO) {
            val root = DocumentFile.fromTreeUri(context, treeUri) ?: return@withContext emptyList()
            val out = ArrayList<RawVideo>(64)
            walk(root, "", 0, out)
            onProgress(out.size)
            out
        }

    private fun walk(dir: DocumentFile, path: String, depth: Int, out: MutableList<RawVideo>) {
        if (depth > MAX_DEPTH || out.size >= MAX_FILES) return
        val children = runCatching { dir.listFiles() }.getOrNull() ?: return
        for (child in children) {
            val name = child.name ?: continue
            // 跳过隐藏文件与隐藏目录（.thumbnails / .nomedia 之类）
            if (name.startsWith('.')) continue
            if (child.isDirectory) {
                walk(child, if (path.isEmpty()) name else "$path/$name", depth + 1, out)
                if (out.size >= MAX_FILES) return
                continue
            }
            if (!isVideoFile(name)) continue
            out += RawVideo(
                uri = child.uri,
                fileName = name,
                relPath = if (path.isEmpty()) name else "$path/$name",
                size = runCatching { child.length() }.getOrDefault(0L),
                modified = runCatching { child.lastModified() }.getOrDefault(0L),
            )
            if (out.size >= MAX_FILES) return
        }
    }

    /**
     * 探测单个视频的时长与分辨率。列表展开后在后台补齐，
     * 不放在扫描阶段 —— 上千个视频逐个开 MediaMetadataRetriever 会让添加目录卡住。
     */
    suspend fun probe(uri: Uri): ProbeResult? = withContext(Dispatchers.IO) {
        val retriever = MediaMetadataRetriever()
        try {
            retriever.setDataSource(context, uri)
            val duration = retriever
                .extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)
                ?.toLongOrNull() ?: 0L
            val width = retriever
                .extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_WIDTH)
                ?.toIntOrNull() ?: 0
            val height = retriever
                .extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_HEIGHT)
                ?.toIntOrNull() ?: 0
            // 竖屏视频的旋转信息写在单独的元数据里，长宽要跟着换过来，
            // 否则竖屏视频会被当成横屏，列表里显示的比例是错的。
            val rotation = retriever
                .extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_ROTATION)
                ?.toIntOrNull() ?: 0
            val swapped = rotation == 90 || rotation == 270
            ProbeResult(
                durationMs = duration,
                width = if (swapped) height else width,
                height = if (swapped) width else height,
            )
        } catch (t: Throwable) {
            null
        } finally {
            runCatching { retriever.release() }
        }
    }

    data class ProbeResult(val durationMs: Long, val width: Int, val height: Int)

    companion object {
        private const val MAX_DEPTH = 8
        private const val MAX_FILES = 3000

        /**
         * 支持格式。以 ExoPlayer 内置软/硬解码覆盖为准：
         * mp4 / mkv / webm 是主力，其余走设备解码器，解不了会明确报错而不是静默黑屏。
         */
        val VIDEO_EXTENSIONS = setOf(
            "mp4", "m4v", "mkv", "webm", "avi", "mov", "flv", "ts", "m2ts", "mts",
            "3gp", "3g2", "wmv", "asf", "rmvb", "rm", "mpg", "mpeg", "vob", "ogv", "f4v",
        )

        fun extensionOf(fileName: String): String =
            fileName.substringAfterLast('.', "").lowercase()

        fun isVideoFile(fileName: String): Boolean = extensionOf(fileName) in VIDEO_EXTENSIONS
    }
}
