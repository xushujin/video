package com.azhan.qingying.data

import android.app.DownloadManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.Settings
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.core.content.FileProvider
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL

/** 一次「发现新版本」的信息。 */
data class UpdateInfo(
    val versionName: String,
    val notes: String,
    val apkUrl: String,
    val pageUrl: String,
)

/** 检查更新的状态机。UI 按它渲染，不用自己拼一堆布尔量。 */
sealed interface UpdateState {
    data object Idle : UpdateState
    data object Checking : UpdateState
    data object UpToDate : UpdateState
    data class Available(val info: UpdateInfo) : UpdateState
    /** [progress] 为 0..100；-1 表示总大小未知（服务端没给 Content-Length）。 */
    data class Downloading(val progress: Int) : UpdateState
    data object Installing : UpdateState
    data class Failed(val message: String) : UpdateState
    /** [pending] 非空表示「有包要装，但被系统拦住了」，用户可以点继续安装。 */
    data class Blocked(val message: String, val pending: UpdateInfo? = null) : UpdateState
}

/**
 * 应用内更新。
 *
 * 只做两件事：读 GitHub Releases 的最新 tag 判断有没有新版；把 APK 下下来交给系统安装器。
 * 刻意不引 OkHttp / Retrofit —— 一个 GET 请求不值得多背几百 KB。
 *
 * **联网只发生在用户点「检查更新」之后**，启动时不检查、后台不轮询。
 */
class UpdateController(
    private val context: Context,
    private val scope: CoroutineScope,
) {

    var state by mutableStateOf<UpdateState>(UpdateState.Idle)
        private set

    fun check(currentVersion: String) {
        if (state is UpdateState.Checking) return
        scope.launch {
            state = UpdateState.Checking
            state = runCatching { fetchLatest(currentVersion) }.fold(
                onSuccess = { info ->
                    if (info != null) UpdateState.Available(info) else UpdateState.UpToDate
                },
                onFailure = { UpdateState.Failed(it.message ?: "网络请求失败") },
            )
        }
    }

    fun reset() {
        state = UpdateState.Idle
    }

    fun downloadAndInstall(info: UpdateInfo) {
        // F-Droid 版是另一把钥匙签的，装上必然 INSTALL_FAILED_UPDATE_INCOMPATIBLE，提前拦住
        if (installerPackage() == F_DROID_PACKAGE) {
            state = UpdateState.Blocked("这是 F-Droid 版本，请通过 F-Droid 更新（两边签名不同，直接装会失败）")
            return
        }
        // Android 8+ 装 APK 需要用户授予「安装未知应用」，没给就先引导过去
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O &&
            !context.packageManager.canRequestPackageInstalls()
        ) {
            state = UpdateState.Blocked(
                "需要先允许「安装未知应用」，已为你打开设置，回来点「继续安装」",
                pending = info,
            )
            openUnknownSourcesSettings()
            return
        }
        scope.launch {
            state = UpdateState.Downloading(progress = -1)
            runCatching {
                val file = apkFile(info.versionName)
                if (file.exists()) file.delete()
                val id = enqueue(info, file)
                awaitDownload(id, file)
            }.onFailure { state = UpdateState.Failed(it.message ?: "下载失败") }
        }
    }

    // ------------------------------------------------------------ 网络

    private suspend fun fetchLatest(currentVersion: String): UpdateInfo? =
        withContext(Dispatchers.IO) {
            val connection = (URL(API_URL).openConnection() as HttpURLConnection).apply {
                connectTimeout = 8_000
                readTimeout = 8_000
                setRequestProperty("Accept", "application/vnd.github+json")
                // GitHub API 强制要求 User-Agent，缺了会直接 403
                setRequestProperty("User-Agent", "QingYing/$currentVersion")
            }
            try {
                if (connection.responseCode !in 200..299) {
                    error("GitHub 返回 ${connection.responseCode}")
                }
                val json = JSONObject(connection.inputStream.bufferedReader().use { it.readText() })
                val tag = json.optString("tag_name").removePrefix("v")
                if (tag.isBlank() || !isNewer(tag, currentVersion)) return@withContext null

                val apkUrl = findApkUrl(json)
                if (apkUrl.isBlank()) error("这个版本没有附带 APK")

                UpdateInfo(
                    versionName = tag,
                    notes = json.optString("body").take(1200),
                    apkUrl = apkUrl,
                    pageUrl = json.optString("html_url"),
                )
            } finally {
                connection.disconnect()
            }
        }

    private fun findApkUrl(json: JSONObject): String {
        val assets = json.optJSONArray("assets") ?: return ""
        for (i in 0 until assets.length()) {
            val asset = assets.getJSONObject(i)
            if (asset.optString("name").endsWith(".apk", ignoreCase = true)) {
                return asset.optString("browser_download_url")
            }
        }
        return ""
    }

    // ------------------------------------------------------------ 下载与安装

    private fun enqueue(info: UpdateInfo, file: File): Long {
        val request = DownloadManager.Request(Uri.parse(info.apkUrl))
            .setTitle("轻影 v${info.versionName}")
            .setDescription("正在下载更新")
            .setMimeType(APK_MIME)
            .setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED)
            .setDestinationInExternalFilesDir(context, Environment.DIRECTORY_DOWNLOADS, file.name)
        return context.getSystemService(DownloadManager::class.java).enqueue(request)
    }

    private suspend fun awaitDownload(id: Long, file: File) {
        val manager = context.getSystemService(DownloadManager::class.java)
        val deadline = System.currentTimeMillis() + DOWNLOAD_TIMEOUT_MS
        var lastProgress = Int.MIN_VALUE
        while (System.currentTimeMillis() < deadline) {
            // 400ms 轮询一次：进度条够顺滑，又不至于把主线程刷爆
            delay(400)
            manager.query(DownloadManager.Query().setFilterById(id))?.use { cursor ->
                if (!cursor.moveToFirst()) return@use
                val status = cursor.getInt(cursor.getColumnIndexOrThrow(DownloadManager.COLUMN_STATUS))
                when (status) {
                    DownloadManager.STATUS_SUCCESSFUL -> {
                        install(file)
                        return
                    }

                    DownloadManager.STATUS_FAILED -> {
                        state = UpdateState.Failed("下载失败，请检查网络后重试")
                        return
                    }
                }

                // 进度：DownloadManager 会同时给已下载字节和总字节，总大小未知时返回 -1
                val downloaded = cursor.getLong(
                    cursor.getColumnIndexOrThrow(DownloadManager.COLUMN_BYTES_DOWNLOADED_SO_FAR),
                )
                val total = cursor.getLong(
                    cursor.getColumnIndexOrThrow(DownloadManager.COLUMN_TOTAL_SIZE_BYTES),
                )
                val progress = if (total > 0L) {
                    ((downloaded * 100) / total).toInt().coerceIn(0, 100)
                } else {
                    -1
                }
                if (progress != lastProgress) {
                    lastProgress = progress
                    state = UpdateState.Downloading(progress)
                }
            }
        }
        state = UpdateState.Failed("下载超时，请稍后重试")
    }

    private fun install(file: File) {
        if (!file.exists()) {
            state = UpdateState.Failed("安装包不完整，请重新下载")
            return
        }
        state = UpdateState.Installing
        runCatching {
            val uri = FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file)
            val intent = Intent(Intent.ACTION_VIEW).apply {
                setDataAndType(uri, APK_MIME)
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            context.startActivity(intent)
        }.onFailure {
            state = UpdateState.Failed("调起安装器失败：${it.message}")
        }
    }

    private fun openUnknownSourcesSettings() {
        val intent = Intent(
            Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
            Uri.parse("package:${context.packageName}"),
        ).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        runCatching { context.startActivity(intent) }
    }

    private fun apkFile(versionName: String): File {
        val dir = context.getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS) ?: context.cacheDir
        return File(dir, "QingYing-$versionName.apk")
    }

    /** 安装来源：用来识别 F-Droid 版（它有自己的更新通道，且签名不同）。 */
    @Suppress("DEPRECATION")
    private fun installerPackage(): String? = runCatching {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            context.packageManager.getInstallSourceInfo(context.packageName).installingPackageName
        } else {
            context.packageManager.getInstallerPackageName(context.packageName)
        }
    }.getOrNull()

    /** 逐段比较版本号；`1.10.0` 要大于 `1.9.0`，所以不能直接比字符串。 */
    private fun isNewer(remote: String, current: String): Boolean {
        val a = remote.split('.').map { it.toIntOrNull() ?: 0 }
        val b = current.split('.').map { it.toIntOrNull() ?: 0 }
        for (i in 0 until maxOf(a.size, b.size)) {
            val x = a.getOrElse(i) { 0 }
            val y = b.getOrElse(i) { 0 }
            if (x != y) return x > y
        }
        return false
    }

    private companion object {
        const val API_URL = "https://api.github.com/repos/xushujin/video/releases/latest"
        const val APK_MIME = "application/vnd.android.package-archive"
        const val F_DROID_PACKAGE = "org.fdroid.fdroid"
        const val DOWNLOAD_TIMEOUT_MS = 10 * 60_000L
    }
}
