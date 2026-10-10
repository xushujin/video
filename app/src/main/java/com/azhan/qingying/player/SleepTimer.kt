package com.azhan.qingying.player

import android.os.SystemClock
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.azhan.qingying.data.SleepAction
import com.azhan.qingying.data.SleepMode
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/**
 * 倒计时退出 —— 需求 3。
 *
 * 两种模式：
 * - [SleepMode.COUNTDOWN]   固定时长倒计时（5/10/15/30/45/60/90/120 分钟）
 * - [SleepMode.END_OF_VIDEO] 播完当前这个视频就执行
 *
 * 到点后的动作由 [SleepAction] 决定：
 * - [SleepAction.EXIT]  暂停播放并退出应用（默认，就是「倒计时退出」）
 * - [SleepAction.PAUSE] 只暂停播放，不退出
 *
 * 计时器挂在 Application 级作用域上（见 [com.azhan.qingying.QingYingApp]），
 * 所以切后台、息屏、甚至离开播放页，倒计时都照常走。
 */
class SleepTimer(
    private val player: PlaybackController,
    private val scope: CoroutineScope,
) {

    var mode by mutableStateOf(SleepMode.OFF)
        private set

    var action by mutableStateOf(SleepAction.EXIT)
        private set

    /** 本次倒计时的总时长；[SleepMode.END_OF_VIDEO] 下为 0。 */
    var totalMs by mutableLongStateOf(0L)
        private set

    /** 剩余毫秒。 */
    var remainingMs by mutableLongStateOf(0L)
        private set

    /**
     * 退出信号。倒计时到点且动作为 [SleepAction.EXIT] 时发一个事件，
     * Activity 收到后 finishAndRemoveTask()。
     *
     * 用 Channel 而不是布尔状态：布尔状态在 Activity 重建后会被重复消费，
     * 导致「重开 App 又立刻退出」。
     */
    private val exitChannel = Channel<Unit>(Channel.CONFLATED)
    val exitEvents: Flow<Unit> = exitChannel.receiveAsFlow()

    val active: Boolean get() = mode != SleepMode.OFF

    private var job: Job? = null

    fun chooseAction(value: SleepAction) {
        action = value
    }

    /** 按分钟启动倒计时。 */
    fun startCountdown(minutes: Int) = startCountdownMillis(minutes * 60_000L)

    fun startCountdownMillis(durationMs: Long) {
        val total = durationMs.coerceAtLeast(MIN_DURATION_MS)
        job?.cancel()
        mode = SleepMode.COUNTDOWN
        totalMs = total
        remainingMs = total
        job = scope.launch {
            // 按「起点 + 已流逝时间」算剩余，而不是每次减 1 秒：
            // delay 本身有误差，累减会让 60 分钟档实际跑成 60 分零几秒。
            val startedAt = SystemClock.elapsedRealtime()
            while (isActive) {
                val left = (total - (SystemClock.elapsedRealtime() - startedAt)).coerceAtLeast(0L)
                remainingMs = left
                if (left <= 0L) break
                delay(TICK_MS)
            }
            if (isActive) expire()
        }
    }

    /** 播完当前视频就执行。 */
    fun startEndOfVideo() {
        job?.cancel()
        job = null
        mode = SleepMode.END_OF_VIDEO
        totalMs = 0L
        remainingMs = 0L
    }

    fun cancel() {
        job?.cancel()
        job = null
        mode = SleepMode.OFF
        totalMs = 0L
        remainingMs = 0L
    }

    /** 由 [PlaybackController.onItemFinished] 驱动。 */
    fun onCurrentVideoFinished() {
        if (mode == SleepMode.END_OF_VIDEO) expire()
    }

    private fun expire() {
        job?.cancel()
        job = null
        mode = SleepMode.OFF
        totalMs = 0L
        remainingMs = 0L

        // 先停声音再退出：哪怕 Activity 的销毁流程慢半拍，也不会出现「退了还在响」。
        player.pause()
        if (action == SleepAction.EXIT) exitChannel.trySend(Unit)
    }

    companion object {
        /** 倒计时刷新间隔。1 秒足够 —— 界面只显示到秒。 */
        private const val TICK_MS = 1_000L

        private const val MIN_DURATION_MS = 60_000L

        /** 预设档位（分钟）。 */
        val PRESETS = listOf(5, 10, 15, 30, 45, 60, 90, 120)
    }
}
