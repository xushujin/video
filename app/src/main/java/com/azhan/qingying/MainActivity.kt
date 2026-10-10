package com.azhan.qingying

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.LaunchedEffect
import com.azhan.qingying.ui.AppRoot

/**
 * 刻意不申请任何运行时权限。
 *
 * 目录访问走 SAF 授权，防熄屏用 Window 的 FLAG_KEEP_SCREEN_ON，都不需要权限。
 * Android 13+ 的媒体通知同样不需要 POST_NOTIFICATIONS 也能正常后台播放 ——
 * 只是通知栏里不会出现播放控件。既然不影响功能，就不弹那个权限框了；
 * 想要通知控件的用户可以在系统设置里手动打开。
 */
class MainActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        val container = (application as QingYingApp).container
        setContent {
            // 倒计时到点且动作为「退出应用」时，在这里收口。
            // 先暂停播放（SleepTimer 里已做），再连最近任务一起关掉 ——
            // 这样不会留下一个「退出了但还在后台响」的进程。
            LaunchedEffect(Unit) {
                container.sleepTimer.exitEvents.collect {
                    container.player.pause()
                    finishAndRemoveTask()
                }
            }

            AppRoot(container = container)
        }
    }
}
