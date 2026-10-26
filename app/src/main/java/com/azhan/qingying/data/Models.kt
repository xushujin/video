package com.azhan.qingying.data

import kotlinx.serialization.Serializable

/**
 * 一个视频。所有路径都用 SAF 的 content URI 字符串表示，避免申请任何存储权限。
 *
 * @param id        稳定标识，等于视频文件的 document uri
 * @param folderId  所属目录（= 目录树 uri），用于反查分组
 * @param relPath   相对目录树根的路径，形如 "第一季/01.mp4"
 */
@Serializable
data class VideoItem(
    val id: String,
    val uri: String,
    val folderId: String,
    val fileName: String,
    val relPath: String = "",
    val size: Long = 0L,
    val modified: Long = 0L,
    /** 扫描时不探测，列表展开后在后台补齐；0 表示未知。 */
    val durationMs: Long = 0L,
    val width: Int = 0,
    val height: Int = 0,
) {
    /** 去掉扩展名的显示名。 */
    val title: String get() = fileName.substringBeforeLast('.').trim().ifBlank { fileName }

    val ext: String get() = fileName.substringAfterLast('.', "").lowercase()

    /**
     * 搜索匹配：按**文件名 / 相对路径**做忽略大小写的子串匹配。
     *
     * 只比对路径就够了 —— `relPath` 形如 "第一季/01.mp4"，末尾就是文件名，
     * 所以「按文件名搜」和「按子目录搜」是同一条规则。
     * 不匹配文件夹名：搜的是**列表里的文件**，不是分组。
     */
    fun matches(query: String): Boolean {
        val q = query.trim()
        if (q.isEmpty()) return true
        return relPath.contains(q, ignoreCase = true) || fileName.contains(q, ignoreCase = true)
    }

    /** 形如 "1080P" 的清晰度标签；未知时返回 null。 */
    val qualityLabel: String?
        get() = when {
            height >= 2160 -> "4K"
            height >= 1440 -> "2K"
            height >= 1080 -> "1080P"
            height >= 720 -> "720P"
            height >= 480 -> "480P"
            height > 0 -> "${height}P"
            else -> null
        }
}

/**
 * 一个分组 = 用户添加的一个本地文件夹。列表名就是文件夹名（需求 1）。
 */
@Serializable
data class VideoFolder(
    val id: String,
    val name: String,
    val treeUri: String,
    val addedAt: Long = 0L,
    val updatedAt: Long = 0L,
    val videos: List<VideoItem> = emptyList(),
) {
    val count: Int get() = videos.size

    val totalSize: Long get() = videos.sumOf { it.size }

    val totalDurationMs: Long get() = videos.sumOf { it.durationMs }
}

/** 单个视频的播放进度，用于「继续观看」。 */
@Serializable
data class PlayProgress(
    val positionMs: Long = 0L,
    val durationMs: Long = 0L,
    val updatedAt: Long = 0L,
) {
    /** 播放比例。时长未知时返回 0。 */
    val fraction: Float
        get() = if (durationMs > 0L) (positionMs.toFloat() / durationMs).coerceIn(0f, 1f) else 0f

    /** 算不算「看过一点」：超过 5 秒且没看完（末尾 15 秒内视为看完）。 */
    val isResumable: Boolean
        get() = positionMs > 5_000L && (durationMs <= 0L || positionMs < durationMs - 15_000L)
}

/** 倒计时到点后的动作。 */
enum class SleepAction { PAUSE, EXIT }

/** 倒计时模式。 */
enum class SleepMode { OFF, COUNTDOWN, END_OF_VIDEO }

/** 应用偏好。 */
@Serializable
data class AppPrefs(
    /**
     * 外壳（列表 / 设置页）是否用深色。**默认浅色**，播放页不受此项影响（恒为近黑）。
     *
     * 注意：这是**默认值**。老版本装过一次之后，DataStore 里已经存了显式的
     * `darkShell = true`，所以升级上来仍是深色 —— 尊重用户已经落盘的选择，不强制迁移。
     */
    val darkShell: Boolean = false,
    /** 打开视频时是否自动续播上次位置。 */
    val autoResume: Boolean = true,
    /** 默认倍速。 */
    val playbackSpeed: Float = 1.0f,
    /** 横向滑动整屏对应的时长（毫秒）：决定快进/快退的幅度。 */
    val seekSensitivityMs: Long = 120_000L,
    /** 倒计时结束后的默认动作。 */
    val sleepAction: SleepAction = SleepAction.EXIT,
)
