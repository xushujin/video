package com.azhan.qingying.data

import android.content.Context
import android.graphics.Bitmap
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.os.Build
import android.util.LruCache
import android.util.Size
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * 视频缩略图，带内存 LRU 缓存。
 *
 * 两条取帧通道：
 * - API 29+：`ContentResolver.loadThumbnail` —— 走系统媒体库，快，且自动降采样；
 * - API 26–28：`MediaMetadataRetriever.getFrameAtTime` —— 只能解一帧，慢一些。
 *
 * 都不落盘：SAF 目录随时可能被移除，缓存文件的生命周期反而更难管，
 * 而一屏最多十几张缩略图，内存缓存足够。
 */
class ThumbnailLoader(private val context: Context) {

    private val cache = object : LruCache<String, Bitmap>(CACHE_BYTES) {
        override fun sizeOf(key: String, value: Bitmap): Int = value.byteCount
    }

    suspend fun load(uri: String, widthPx: Int, heightPx: Int): Bitmap? {
        val key = "$uri@${widthPx}x$heightPx"
        cache.get(key)?.let { return it }
        val bitmap = withContext(Dispatchers.IO) { decode(uri, widthPx, heightPx) } ?: return null
        cache.put(key, bitmap)
        return bitmap
    }

    private fun decode(uri: String, widthPx: Int, heightPx: Int): Bitmap? = runCatching {
        val parsed = Uri.parse(uri)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            context.contentResolver.loadThumbnail(
                parsed,
                Size(widthPx.coerceAtLeast(1), heightPx.coerceAtLeast(1)),
                null,
            )
        } else {
            val retriever = MediaMetadataRetriever()
            try {
                retriever.setDataSource(context, parsed)
                // 取第 1 秒的帧：第 0 帧经常是黑场或片头 logo
                retriever.getFrameAtTime(1_000_000L, MediaMetadataRetriever.OPTION_CLOSEST_SYNC)
            } finally {
                runCatching { retriever.release() }
            }
        }
    }.getOrNull()

    fun clear() = cache.evictAll()

    companion object {
        private const val CACHE_BYTES = 16 * 1024 * 1024
    }
}
