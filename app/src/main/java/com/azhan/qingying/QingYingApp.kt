package com.azhan.qingying

import android.app.Application
import android.content.Context
import com.azhan.qingying.data.LibraryRepository
import com.azhan.qingying.data.ThumbnailLoader
import com.azhan.qingying.data.UpdateController
import com.azhan.qingying.data.VideoScanner
import com.azhan.qingying.data.VideoStore
import com.azhan.qingying.player.PlaybackController
import com.azhan.qingying.player.SleepTimer
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

/**
 * 极简依赖容器。整个 App 就这么几个单例，上 Hilt/Koin 反而是负担。
 */
class AppContainer(context: Context) {

    private val appContext: Context = context.applicationContext

    /** Application 级作用域：目录扫描、时长探测、进度防抖落盘、倒计时都挂在它上面。 */
    val appScope = CoroutineScope(SupervisorJob() + Dispatchers.Main)

    val store: VideoStore by lazy { VideoStore(appContext) }
    val scanner: VideoScanner by lazy { VideoScanner(appContext) }
    val thumbnails: ThumbnailLoader by lazy { ThumbnailLoader(appContext) }

    val library: LibraryRepository by lazy {
        LibraryRepository(appContext, store, scanner, appScope)
    }

    val player: PlaybackController by lazy { PlaybackController(appContext) }

    val sleepTimer: SleepTimer by lazy { SleepTimer(player, appScope) }

    val updater: UpdateController by lazy { UpdateController(appContext, appScope) }

    init {
        // 播放进度回写（内部有防抖，见 LibraryRepository.saveProgress）
        player.onProgressTick = { videoId, position, duration ->
            library.saveProgress(videoId, position, duration)
        }
        // 「本片结束后」的倒计时：靠播放器自然播完的事件驱动
        player.onItemFinished = { sleepTimer.onCurrentVideoFinished() }

        // 让倒计时的默认动作、默认倍速跟随设置
        appScope.launch {
            library.prefs.collect { prefs ->
                if (!sleepTimer.active) sleepTimer.chooseAction(prefs.sleepAction)
                player.applyDefaultSpeed(prefs.playbackSpeed)
            }
        }

        // 启动即连上播放服务：连的是同进程的 MediaSessionService，
        // 不播放时不会挂前台通知，开销可以忽略。
        player.connect()
    }
}

class QingYingApp : Application() {

    lateinit var container: AppContainer
        private set

    override fun onCreate() {
        super.onCreate()
        container = AppContainer(this)
    }
}
