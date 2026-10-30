package com.azhan.qingying.player

import android.annotation.SuppressLint
import android.app.PendingIntent
import android.content.Intent
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.exoplayer.DefaultRenderersFactory
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.session.MediaSession
import androidx.media3.session.MediaSessionService
import com.azhan.qingying.MainActivity

/**
 * 后台播放服务 —— 需求 2 的实现基础。
 *
 * 播放器（ExoPlayer）活在这个 Service 里，而不是 Activity 里。
 * 于是切后台、息屏、甚至 Activity 被销毁，解码都不会断；
 * media3 还会自动挂一条带播放/暂停/上一集/下一集的前台通知，不需要手写 Notification。
 *
 * UI 侧通过 [PlaybackController] 连上来遥控，包括把 SurfaceView 交给播放器渲染画面。
 * 画面不需要时（切后台）就把 Surface 摘掉并禁用视频轨道，只留音频解码 —— 省电。
 *
 * 注：media3 的 @UnstableApi 只表示「不保证跨版本兼容」，不是错误。
 */
@SuppressLint("UnsafeOptInUsageError", "UnstableApiUsage")
class PlaybackService : MediaSessionService() {

    private var mediaSession: MediaSession? = null

    override fun onCreate() {
        super.onCreate()

        // 首选解码器失败时自动换下一个候选（enableDecoderFallback 默认是关的）。
        // 配合 SwitchableCodecSelector，还能在运行时整体切成「音频软解优先」。
        val renderersFactory = DefaultRenderersFactory(this)
            .setEnableDecoderFallback(true)
            .setMediaCodecSelector(SwitchableCodecSelector())

        val player = ExoPlayer.Builder(this)
            .setRenderersFactory(renderersFactory)
            .setAudioAttributes(
                AudioAttributes.Builder()
                    .setContentType(C.AUDIO_CONTENT_TYPE_MOVIE)
                    .setUsage(C.USAGE_MEDIA)
                    .build(),
                /* handleAudioFocus = */ true,
            )
            // 拔耳机自动暂停，不打扰旁人
            .setHandleAudioBecomingNoisy(true)
            .setWakeMode(C.WAKE_MODE_LOCAL)
            .setSeekBackIncrementMs(15_000L)
            .setSeekForwardIncrementMs(15_000L)
            .build()

        val sessionActivity = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )

        mediaSession = MediaSession.Builder(this, player)
            .setSessionActivity(sessionActivity)
            .build()
    }

    override fun onGetSession(controllerInfo: MediaSession.ControllerInfo): MediaSession? = mediaSession

    /**
     * 用户从最近任务里划掉 App 时：
     * 正在播就继续播（后台播放本来就是这个意思），没在播才收工。
     */
    override fun onTaskRemoved(rootIntent: Intent?) {
        val player = mediaSession?.player
        if (player == null || !player.playWhenReady || player.mediaItemCount == 0) {
            stopSelf()
        }
    }

    override fun onDestroy() {
        mediaSession?.run {
            player.release()
            release()
        }
        mediaSession = null
        super.onDestroy()
    }
}
