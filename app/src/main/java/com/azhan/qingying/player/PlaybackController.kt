package com.azhan.qingying.player

import android.annotation.SuppressLint
import android.content.ComponentName
import android.content.Context
import android.view.SurfaceView
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.core.content.ContextCompat
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.session.MediaController
import androidx.media3.session.SessionToken
import com.azhan.qingying.data.VideoItem
import com.google.common.util.concurrent.ListenableFuture
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/**
 * 播放控制器：连到 [PlaybackService] 的 MediaController，把播放状态镜像成 Compose state，
 * UI 直接读字段即可，不需要额外的 ViewModel / Flow 中转。
 *
 * 它由 Application 持有（见 [com.azhan.qingying.QingYingApp]），生命周期跟进程走 ——
 * 这样 Activity 被销毁时进度回写仍在继续。
 */
@SuppressLint("UnsafeOptInUsageError", "UnstableApiUsage")
class PlaybackController(private val context: Context) {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var controllerFuture: ListenableFuture<MediaController>? = null
    private var ticker: Job? = null
    private var lastProgressTickAt = 0L
    private var pendingAction: (() -> Unit)? = null
    private var pendingSurface: SurfaceView? = null
    private var defaultSpeed: Float = 1.0f

    var controller: MediaController? = null
        private set

    var connected by mutableStateOf(false)
        private set

    var currentVideoId by mutableStateOf<String?>(null)
        private set

    var isPlaying by mutableStateOf(false)
        private set

    var playbackState by mutableIntStateOf(Player.STATE_IDLE)
        private set

    var positionMs by mutableLongStateOf(0L)
        private set

    var durationMs by mutableLongStateOf(0L)
        private set

    var speed by mutableFloatStateOf(1.0f)
        private set

    /** 视频轨道是否启用。切后台时置 false —— 只解音频，这就是「后台播放音频」。 */
    var videoTrackEnabled by mutableStateOf(true)
        private set

    var hasNext by mutableStateOf(false)
        private set

    var hasPrevious by mutableStateOf(false)
        private set

    var queueSize by mutableIntStateOf(0)
        private set

    /** 当前视频的宽高比，用于让 SurfaceView 按画面比例占位（默认 16:9）。 */
    var videoAspect by mutableFloatStateOf(16f / 9f)
        private set

    var errorMessage by mutableStateOf<String?>(null)
        private set

    // ------------------------------------------------------------ 片段循环
    //
    // A 点 / B 点都是「当前视频内」的毫秒位置，[NO_LOOP] 表示没设。
    // 不落盘：片段是临时意图，跟着一次观看走；换视频就作废。

    var loopStartMs by mutableLongStateOf(NO_LOOP)
        private set

    var loopEndMs by mutableLongStateOf(NO_LOOP)
        private set

    /** 两点都设好了才算「可用」；[loopEnabled] 是用户是否让它真的循环。 */
    val loopReady: Boolean
        get() = loopStartMs >= 0L && loopEndMs > loopStartMs

    var loopEnabled by mutableStateOf(false)
        private set

    /** 循环生效中。轮询与 UI 都看这个。 */
    val loopActive: Boolean
        get() = loopEnabled && loopReady

    /** 片段所属的视频 id —— 切到别的视频时据此作废，避免把 A/B 套到下一集上。 */
    private var loopVideoId: String? = null

    /** 切到另一个视频时回调（含手动选集与自动连播）。 */
    var onVideoChanged: ((String?) -> Unit)? = null

    /** 当前视频「自然播完」时回调（自动连播到下一个，或整个队列播完）。 */
    var onItemFinished: (() -> Unit)? = null

    /** 队列播完时回调。 */
    var onEnded: (() -> Unit)? = null

    /** 播放中定期回调，用于回写播放进度。 */
    var onProgressTick: ((videoId: String, positionMs: Long, durationMs: Long) -> Unit)? = null

    private val listener = object : Player.Listener {
        override fun onIsPlayingChanged(isPlaying: Boolean) {
            this@PlaybackController.isPlaying = isPlaying
            if (isPlaying) startTicker() else stopTicker()
        }

        override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) {
            // 换了片子就把上一条的错误清掉，否则「上一个文件播不了」的提示会一直挂在屏幕上
            errorMessage = null
            // 片段循环只在「设它的那个视频」里成立，切走就作废
            if (loopVideoId != null && loopVideoId != mediaItem?.mediaId) clearLoop()
            currentVideoId = mediaItem?.mediaId
            positionMs = 0L
            durationMs = controller?.duration?.takeIf { it > 0 } ?: 0L
            syncQueueFlags()
            onVideoChanged?.invoke(currentVideoId)
            // REASON_AUTO = 上一个视频放完了自动切下一个（手动选集是 REASON_SEEK）
            if (reason == Player.MEDIA_ITEM_TRANSITION_REASON_AUTO) onItemFinished?.invoke()
        }

        override fun onPlaybackStateChanged(state: Int) {
            playbackState = state
            val duration = controller?.duration ?: 0L
            if (duration > 0) durationMs = duration
            if (state == Player.STATE_READY) errorMessage = null
            if (state == Player.STATE_ENDED) {
                stopTicker()
                flushProgress()
                onEnded?.invoke()
            }
            syncQueueFlags()
        }

        override fun onPlaybackParametersChanged(playbackParameters: androidx.media3.common.PlaybackParameters) {
            speed = playbackParameters.speed
        }

        override fun onVideoSizeChanged(videoSize: androidx.media3.common.VideoSize) {
            if (videoSize.width > 0 && videoSize.height > 0) {
                // pixelWidthHeightRatio 处理非方形像素（老 DVD 转码常见），
                // 少了它画面会被拉扁。
                val ratio = if (videoSize.pixelWidthHeightRatio > 0f) {
                    videoSize.pixelWidthHeightRatio
                } else {
                    1f
                }
                videoAspect = videoSize.width * ratio / videoSize.height
            }
        }

        override fun onPlayerError(error: PlaybackException) {
            errorMessage = error.errorCodeName + "：" + (error.message ?: "播放出错")
        }
    }

    // ------------------------------------------------------------ 连接

    fun connect() {
        if (controllerFuture != null) return
        val token = SessionToken(context, ComponentName(context, PlaybackService::class.java))
        val future = MediaController.Builder(context, token).buildAsync()
        controllerFuture = future
        future.addListener(
            {
                try {
                    val mediaController = future.get()
                    mediaController.addListener(listener)
                    controller = mediaController
                    connected = true
                    syncFrom(mediaController)
                    pendingSurface?.let { view ->
                        runCatching { mediaController.setVideoSurfaceView(view) }
                    }
                    val action = pendingAction
                    pendingAction = null
                    action?.invoke()
                } catch (t: Throwable) {
                    errorMessage = t.message ?: "播放服务连接失败"
                    controllerFuture = null
                }
            },
            ContextCompat.getMainExecutor(context),
        )
    }

    fun release() {
        flushProgress()
        stopTicker()
        controller?.removeListener(listener)
        controller = null
        connected = false
        controllerFuture?.let { MediaController.releaseFuture(it) }
        controllerFuture = null
    }

    fun clearError() {
        errorMessage = null
    }

    // ------------------------------------------------------------ 播放控制

    /** 用整张列表当播放队列，从 [startIndex] 开始播，并定位到 [startPositionMs]。 */
    fun play(videos: List<VideoItem>, startIndex: Int, startPositionMs: Long = 0L) {
        if (videos.isEmpty()) return
        val mediaController = controller
        if (mediaController == null) {
            connect()
            pendingAction = { play(videos, startIndex, startPositionMs) }
            return
        }
        val items = videos.map { it.toMediaItem() }
        val index = startIndex.coerceIn(0, items.size - 1)
        mediaController.setMediaItems(items, index, startPositionMs.coerceAtLeast(0L))
        mediaController.prepare()
        mediaController.play()
    }

    fun toggle() {
        val mediaController = controller ?: return
        if (mediaController.isPlaying) {
            mediaController.pause()
            flushProgress()
        } else {
            if (mediaController.playbackState == Player.STATE_IDLE) mediaController.prepare()
            mediaController.play()
        }
    }

    fun pause() {
        controller?.pause()
        flushProgress()
    }

    fun next() {
        controller?.seekToNextMediaItem()
    }

    fun previous() {
        val mediaController = controller ?: return
        // 播了 5 秒以上，先回到本片开头，符合主流播放器习惯
        if (mediaController.currentPosition > 5_000) mediaController.seekTo(0) else mediaController.seekToPreviousMediaItem()
    }

    fun seekTo(ms: Long) {
        val target = ms.coerceAtLeast(0L)
        controller?.seekTo(target)
        positionMs = target
    }

    fun seekBy(deltaMs: Long) {
        val mediaController = controller ?: return
        val target = (mediaController.currentPosition + deltaMs)
            .coerceIn(0L, mediaController.duration.coerceAtLeast(0L).takeIf { it > 0 } ?: Long.MAX_VALUE)
        seekTo(target)
    }

    // ------------------------------------------------------------ 片段循环

    /** 以当前位置设 A 点。A 点跑到原 B 点之后时，原来的 B 点作废。 */
    fun markLoopStart() {
        val position = currentPositionMs()
        loopStartMs = position
        loopVideoId = currentVideoId
        if (loopEndMs >= 0L && loopEndMs <= position) loopEndMs = NO_LOOP
        loopEnabled = loopReady
    }

    /**
     * 以当前位置设 B 点。
     *
     * 没设 A 时默认从 0 开始 ——「从开头循环到这里」是常见用法，不必先点 A。
     * B 点贴着结尾时会往前留 [LOOP_END_GUARD_MS]：播放器一旦走到 STATE_ENDED
     * 就会自动切下一集，循环会断在半路上。
     */
    fun markLoopEnd() {
        val duration = knownDurationMs()
        val limit = if (duration > 0L) {
            (duration - LOOP_END_GUARD_MS).coerceAtLeast(0L)
        } else {
            Long.MAX_VALUE
        }
        var end = currentPositionMs().coerceAtMost(limit)
        val start = if (loopStartMs >= 0L && loopStartMs < end) loopStartMs else 0L
        // 两点挨得太近（或者 B 点落在 A 点之前）时，至少留出 MIN_LOOP_MS
        if (end - start < MIN_LOOP_MS) {
            end = (start + MIN_LOOP_MS).coerceAtMost(limit)
        }
        loopStartMs = start
        loopEndMs = end
        loopVideoId = currentVideoId
        loopEnabled = end > start
    }

    /**
     * 开关循环。
     *
     * 方法名不能叫 `setLoopEnabled` —— 那会和 `loopEnabled` 属性的 setter
     * 撞同一个 JVM 签名（`setLoopEnabled(Z)V`），编译期直接报 declaration clash。
     */
    fun setLooping(enabled: Boolean) {
        loopEnabled = enabled && loopReady
    }

    fun clearLoop() {
        loopStartMs = NO_LOOP
        loopEndMs = NO_LOOP
        loopEnabled = false
        loopVideoId = null
    }

    fun applySpeed(value: Float) {
        val clamped = value.coerceIn(MIN_SPEED, MAX_SPEED)
        speed = clamped
        controller?.setPlaybackSpeed(clamped)
    }

    /**
     * 默认倍速（来自设置）。控制器连上时会自动套用 ——
     * 否则「设置里选了 1.5x」在冷启动后会被播放器自己的 1x 覆盖掉。
     */
    fun applyDefaultSpeed(value: Float) {
        defaultSpeed = value.coerceIn(MIN_SPEED, MAX_SPEED)
        applySpeed(defaultSpeed)
    }

    fun stop() {
        flushProgress()
        controller?.stop()
        controller?.clearMediaItems()
        currentVideoId = null
        clearLoop()
        stopTicker()
    }

    // ------------------------------------------------------------ 视频画面

    /**
     * 把画面交给播放器。离开播放页 / 切后台时务必 [detachSurface]，
     * 否则 Surface 被销毁后播放器还往上面画，会抛 IllegalStateException。
     *
     * 记一份「待挂载的 Surface」：控制器还没连上时先存着，连上后自动补挂 ——
     * 否则冷启动直接进播放页会看到有声音没画面。
     */
    fun attachSurface(view: SurfaceView) {
        pendingSurface = view
        val mediaController = controller ?: return
        runCatching { mediaController.setVideoSurfaceView(view) }
    }

    fun detachSurface(view: SurfaceView) {
        if (pendingSurface === view) pendingSurface = null
        val mediaController = controller ?: return
        runCatching { mediaController.clearVideoSurfaceView(view) }
    }

    /**
     * 开关视频轨道。切后台时关掉 —— 这时候画面没人看，
     * 继续解码视频纯属烧电；关掉后 ExoPlayer 只解音频，播放不中断。
     */
    fun enableVideoTrack(enabled: Boolean) {
        videoTrackEnabled = enabled
        val mediaController = controller ?: return
        runCatching {
            mediaController.trackSelectionParameters = mediaController.trackSelectionParameters
                .buildUpon()
                .setTrackTypeDisabled(C.TRACK_TYPE_VIDEO, !enabled)
                .build()
        }
    }

    // ------------------------------------------------------------ 内部

    /** 当前位置。控制器还没连上时退回最后一次镜像的值。 */
    private fun currentPositionMs(): Long =
        (controller?.currentPosition ?: positionMs).coerceAtLeast(0L)

    /** 时长。播放器没准备好时退回列表里探测到的值。 */
    private fun knownDurationMs(): Long {
        val fromController = controller?.duration ?: 0L
        return if (fromController > 0L) fromController else durationMs
    }

    private fun syncFrom(mediaController: MediaController) {
        currentVideoId = mediaController.currentMediaItem?.mediaId
        isPlaying = mediaController.isPlaying
        playbackState = mediaController.playbackState
        runCatching { mediaController.setPlaybackSpeed(defaultSpeed) }
        speed = defaultSpeed
        positionMs = mediaController.currentPosition.coerceAtLeast(0L)
        durationMs = mediaController.duration.takeIf { it > 0 } ?: 0L
        videoTrackEnabled = !mediaController.trackSelectionParameters.disabledTrackTypes
            .contains(C.TRACK_TYPE_VIDEO)
        syncQueueFlags()
        if (mediaController.isPlaying) startTicker()
    }

    private fun syncQueueFlags() {
        val mediaController = controller ?: return
        queueSize = mediaController.mediaItemCount
        hasNext = mediaController.hasNextMediaItem()
        hasPrevious = mediaController.hasPreviousMediaItem()
    }

    private fun startTicker() {
        if (ticker?.isActive == true) return
        ticker = scope.launch {
            while (isActive) {
                val mediaController = controller ?: break
                val position = mediaController.currentPosition.coerceAtLeast(0L)
                // 片段循环：走到 B 点就跳回 A 点。
                // 这里是轮询而不是监听播放器事件 —— ExoPlayer 没有「到达某位置」的回调，
                // 循环区间又是临时状态，不值得为它重建 MediaItem 的 ClippingConfiguration。
                if (loopActive && position >= loopEndMs) {
                    mediaController.seekTo(loopStartMs)
                    positionMs = loopStartMs
                } else {
                    positionMs = position
                }
                val duration = mediaController.duration
                if (duration > 0 && duration != durationMs) durationMs = duration
                val now = android.os.SystemClock.elapsedRealtime()
                if (now - lastProgressTickAt >= PROGRESS_TICK_MS) {
                    lastProgressTickAt = now
                    flushProgress()
                }
                // 循环时轮询要更密：200ms 一圈会让每轮多播出去小半秒，几十圈后就明显跑偏。
                delay(if (loopActive) LOOP_TICK_MS else POSITION_TICK_MS)
            }
        }
    }

    private fun stopTicker() {
        ticker?.cancel()
        ticker = null
    }

    /** 把当前位置回写给上层（进度落库由上层防抖处理）。 */
    private fun flushProgress() {
        val mediaController = controller ?: return
        val id = mediaController.currentMediaItem?.mediaId ?: return
        val duration = mediaController.duration.takeIf { it > 0 } ?: durationMs
        onProgressTick?.invoke(id, mediaController.currentPosition.coerceAtLeast(0L), duration)
    }

    private fun VideoItem.toMediaItem(): MediaItem = MediaItem.Builder()
        .setUri(uri)
        .setMediaId(id)
        .setMediaMetadata(
            MediaMetadata.Builder()
                .setTitle(title)
                .setIsBrowsable(false)
                .setIsPlayable(true)
                .build(),
        )
        .build()

    companion object {
        const val MIN_SPEED = 0.25f
        const val MAX_SPEED = 4.0f

        /** 进度回写间隔：5 秒。 */
        private const val PROGRESS_TICK_MS = 5_000L

        /** 普通状态下的位置轮询间隔。 */
        private const val POSITION_TICK_MS = 200L

        /** 片段循环生效时的轮询间隔。越密越不容易「冲过头」。 */
        private const val LOOP_TICK_MS = 50L

        /** 表示「没设 A/B 点」。 */
        const val NO_LOOP = -1L

        /** 片段最短 0.5 秒 —— 再短就成了卡顿，不是循环。 */
        private const val MIN_LOOP_MS = 500L

        /** B 点距结尾的余量，避免播放器先走到 ENDED 自动切集。 */
        private const val LOOP_END_GUARD_MS = 300L

        /** 播放页提供的倍速档位。 */
        val SPEED_STEPS = listOf(0.5f, 0.75f, 1.0f, 1.25f, 1.5f, 2.0f, 3.0f)
    }
}
