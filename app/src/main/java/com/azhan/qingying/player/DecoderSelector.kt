package com.azhan.qingying.player

import androidx.media3.exoplayer.mediacodec.MediaCodecInfo
import androidx.media3.exoplayer.mediacodec.MediaCodecSelector

/**
 * 进程内共享的「音频优先软解」开关。
 *
 * 播放器活在同一个进程的 [PlaybackService] 里，所以控制器直接改这个字段、
 * 再 prepare 一次，ExoPlayer 就会重新走一遍解码器选择 —— 不需要额外定义
 * MediaSession 的自定义命令来回传。
 */
object DecoderPreference {
    @Volatile
    var preferSoftwareAudio: Boolean = false
}

/**
 * 可切换的解码器选择器。
 *
 * 默认保持系统顺序（通常硬解在前）；开关打开后**只对音频轨**改成软解优先。
 * 不动视频轨：软解 4K 视频会明显掉帧，而这类失败几乎都出在音频上。
 *
 * media3 自带 [MediaCodecSelector.PREFER_SOFTWARE]，不用自己按 `softwareOnly` 排序。
 */
class SwitchableCodecSelector : MediaCodecSelector {

    override fun getDecoderInfos(
        mimeType: String,
        requiresSecureDecoder: Boolean,
        requiresTunnelingDecoder: Boolean,
    ): List<MediaCodecInfo> {
        val preferSoftware = DecoderPreference.preferSoftwareAudio &&
            mimeType.startsWith("audio/", ignoreCase = true)
        val delegate = if (preferSoftware) {
            MediaCodecSelector.PREFER_SOFTWARE
        } else {
            MediaCodecSelector.DEFAULT
        }
        return delegate.getDecoderInfos(mimeType, requiresSecureDecoder, requiresTunnelingDecoder)
    }
}
