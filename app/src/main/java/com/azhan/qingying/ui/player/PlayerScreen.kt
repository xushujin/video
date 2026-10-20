package com.azhan.qingying.ui.player

import android.content.pm.ActivityInfo
import android.content.res.Configuration
import android.view.SurfaceView
import android.view.WindowManager
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.automirrored.rounded.VolumeOff
import androidx.compose.material.icons.rounded.FastForward
import androidx.compose.material.icons.rounded.FastRewind
import androidx.compose.material.icons.rounded.Fullscreen
import androidx.compose.material.icons.rounded.FullscreenExit
import androidx.compose.material.icons.rounded.Pause
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.Repeat
import androidx.compose.material.icons.rounded.SkipNext
import androidx.compose.material.icons.rounded.SkipPrevious
import androidx.compose.material.icons.rounded.Timer
import androidx.compose.material.icons.rounded.TimerOff
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.azhan.qingying.AppContainer
import com.azhan.qingying.data.SleepMode
import com.azhan.qingying.player.PlaybackController
import com.azhan.qingying.ui.neu.NeuSlider
import com.azhan.qingying.ui.theme.LocalNeuPalette
import com.azhan.qingying.util.findActivity
import com.azhan.qingying.util.formatCountdown
import com.azhan.qingying.util.formatDuration
import kotlinx.coroutines.delay
import kotlin.math.abs

/**
 * 播放页。
 *
 * 视频画面由跑在 [com.azhan.qingying.player.PlaybackService] 里的 ExoPlayer 渲染，
 * 这里只把 SurfaceView 递过去。所以：
 * - 退出本页 / 切后台 / 息屏 → 摘掉 Surface 并关掉视频轨道，**音频继续播**（需求 2）；
 * - 回到本页 → 重新挂上 Surface、开视频轨道，画面立刻回来（播放位置不会断）。
 *
 * 横竖屏都支持：竖屏是常规竖排控件，横屏切沉浸式（隐藏系统栏）+ 单行紧凑控件。
 */
@Composable
fun PlayerScreen(
    container: AppContainer,
    onBack: () -> Unit,
) {
    val player = container.player
    val timer = container.sleepTimer
    val palette = LocalNeuPalette.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val view = LocalView.current
    val context = LocalContext.current

    val isLandscape =
        LocalConfiguration.current.orientation == Configuration.ORIENTATION_LANDSCAPE

    val folders by container.library.folders.collectAsState()
    val currentVideoId = player.currentVideoId
    val currentVideo = remember(folders, currentVideoId) {
        folders.asSequence()
            .flatMap { folder -> folder.videos.asSequence().map { folder to it } }
            .firstOrNull { it.second.id == currentVideoId }
    }

    var surfaceView by remember { mutableStateOf<SurfaceView?>(null) }
    var controlsVisible by remember { mutableStateOf(true) }
    var showSleepPanel by remember { mutableStateOf(false) }
    var showLoopPanel by remember { mutableStateOf(false) }
    var dragging by remember { mutableStateOf(false) }
    var dragValue by remember { mutableFloatStateOf(0f) }

    // 手动方向：0 = 交还系统，1 = 钉住横屏，2 = 钉住竖屏。
    // 用 rememberSaveable：Activity 万一被回收重建，用户刚点的横屏不该自己弹回去。
    var orientationMode by rememberSaveable { mutableStateOf(ORIENTATION_SYSTEM) }

    // manifest 里声明了 configChanges=orientation|screenSize，切方向不重建 Activity，
    // 所以播放位置、面板开关、A/B 点都不会丢 —— 这里只负责把请求交给系统。
    DisposableEffect(orientationMode) {
        context.findActivity()?.requestedOrientation = when (orientationMode) {
            ORIENTATION_LANDSCAPE -> ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE
            ORIENTATION_PORTRAIT -> ActivityInfo.SCREEN_ORIENTATION_SENSOR_PORTRAIT
            else -> ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
        }
        onDispose { }
    }

    // 离开播放页时把方向交还系统，否则回到列表页还卡在横屏里
    DisposableEffect(Unit) {
        onDispose {
            context.findActivity()?.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
        }
    }

    val toggleOrientation: () -> Unit = {
        // 当前是横屏就回竖屏，否则去横屏 —— 这样无论系统自动旋转开没开，
        // 这个键都一定有效果，不会出现「点了没反应」。
        orientationMode = if (isLandscape) ORIENTATION_PORTRAIT else ORIENTATION_LANDSCAPE
    }

    // 控件自动隐藏：播放中 4 秒无操作就收起来；暂停时、拖进度条时、开着任一面板时不收
    LaunchedEffect(controlsVisible, player.isPlaying, showSleepPanel, showLoopPanel, dragging) {
        if (controlsVisible && player.isPlaying && !showSleepPanel && !showLoopPanel && !dragging) {
            delay(4_000)
            controlsVisible = false
        }
    }

    // 进入播放页：开视频轨道 + 挂画面
    LaunchedEffect(player.connected) {
        if (player.connected) {
            player.enableVideoTrack(true)
            surfaceView?.let { player.attachSurface(it) }
        }
    }

    // ---------------- 防熄屏
    // 播放页在前台 → 屏幕常亮；切后台/离开本页 → 立刻交还系统，允许正常熄屏。
    // 这正是「看视频别灭屏、只听声音随便灭屏」的分界。
    // 用 Window 的 FLAG_KEEP_SCREEN_ON，不需要 WAKE_LOCK 权限。
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_START -> {
                    player.enableVideoTrack(true)
                    surfaceView?.let { player.attachSurface(it) }
                    setKeepScreenOn(context, true)
                }

                Lifecycle.Event.ON_STOP -> {
                    // 摘掉画面 + 关视频轨道：只解音频，省电且不会因为 Surface 被销毁而崩
                    surfaceView?.let { player.detachSurface(it) }
                    player.enableVideoTrack(false)
                    // 后台 = 音频播放，屏幕该灭就灭
                    setKeepScreenOn(context, false)
                }

                else -> Unit
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    // 离开播放页：同样是「只留音频」+ 不再拦熄屏
    DisposableEffect(Unit) {
        setKeepScreenOn(context, true)
        onDispose {
            setKeepScreenOn(context, false)
            surfaceView?.let { player.detachSurface(it) }
            player.enableVideoTrack(false)
        }
    }

    // ---------------- 横屏沉浸式
    // 横屏看视频时把状态栏/导航栏收掉，画面才是真的满屏；退出或转回竖屏立刻还原。
    DisposableEffect(isLandscape, view) {
        val window = context.findActivity()?.window
        val controller = window?.let { WindowCompat.getInsetsController(it, view) }
        if (isLandscape) {
            controller?.systemBarsBehavior =
                WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
            controller?.hide(WindowInsetsCompat.Type.systemBars())
        } else {
            controller?.show(WindowInsetsCompat.Type.systemBars())
        }
        onDispose { controller?.show(WindowInsetsCompat.Type.systemBars()) }
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black),
    ) {
        // ---------------- 画面
        BoxWithConstraints(
            modifier = Modifier
                .fillMaxSize()
                .clickable(
                    interactionSource = remember { MutableInteractionSource() },
                    indication = null,
                ) { controlsVisible = !controlsVisible },
            contentAlignment = Alignment.Center,
        ) {
            val containerAspect = maxWidth / maxHeight
            val videoAspect = player.videoAspect.coerceIn(0.3f, 4f)
            val surfaceModifier = if (videoAspect >= containerAspect) {
                Modifier.fillMaxWidth().aspectRatio(videoAspect)
            } else {
                Modifier.fillMaxHeight().aspectRatio(videoAspect)
            }

            AndroidView(
                factory = { ctx ->
                    SurfaceView(ctx).also { surfaceView = it }
                },
                // update 在每次重组后都会跑：控制器连上（connected 变 true）的那一刻
                // 正好触发一次重组，画面就在这里挂上去，不依赖时序运气。
                update = { v ->
                    if (player.connected) player.attachSurface(v)
                },
                modifier = surfaceModifier,
            )
        }

        // ---------------- 错误提示
        player.playbackError?.let { error ->
            Box(
                modifier = Modifier
                    .align(Alignment.Center)
                    .padding(24.dp)
                    .clip(RoundedCornerShape(14.dp))
                    .background(palette.surface.copy(alpha = 0.95f))
                    .padding(horizontal = 16.dp, vertical = 14.dp),
            ) {
                Column {
                    Text(
                        text = error.title,
                        color = palette.textPrimary,
                        fontSize = 14.sp,
                        fontWeight = FontWeight.SemiBold,
                    )
                    Spacer(Modifier.height(6.dp))
                    Text(
                        text = error.detail,
                        color = palette.textSecondary,
                        fontSize = 10.sp,
                    )
                    Spacer(Modifier.height(8.dp))
                    Text(
                        text = error.hint,
                        color = palette.textSecondary,
                        fontSize = 11.sp,
                    )
                    Spacer(Modifier.height(10.dp))
                    Text(
                        text = "知道了",
                        color = palette.accent,
                        fontSize = 12.sp,
                        fontWeight = FontWeight.Medium,
                        modifier = Modifier
                            .align(Alignment.End)
                            .clip(RoundedCornerShape(8.dp))
                            .clickable { player.clearError() }
                            .padding(horizontal = 8.dp, vertical = 4.dp),
                    )
                }
            }
        }

        // ---------------- 一次性提示：音轨解不了自动静音时告诉用户一声
        player.noticeMessage?.let { message ->
            Row(
                modifier = Modifier
                    .align(Alignment.TopCenter)
                    .windowInsetsPadding(WindowInsets.safeDrawing)
                    .padding(top = 64.dp, start = 16.dp, end = 16.dp)
                    .clip(RoundedCornerShape(12.dp))
                    .background(palette.surface.copy(alpha = 0.92f))
                    .clickable { player.clearNotice() }
                    .padding(horizontal = 12.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(
                    imageVector = Icons.AutoMirrored.Rounded.VolumeOff,
                    contentDescription = null,
                    tint = palette.accent,
                    modifier = Modifier.size(16.dp),
                )
                Spacer(Modifier.width(6.dp))
                Text(message, color = palette.textPrimary, fontSize = 11.sp)
            }
        }

        // 6 秒后自动消失，点一下也能关
        LaunchedEffect(player.noticeMessage) {
            if (player.noticeMessage != null) {
                delay(6_000)
                player.clearNotice()
            }
        }

        // ---------------- 控件层
        if (controlsVisible) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(Color.Black.copy(alpha = 0.34f))
                    .windowInsetsPadding(WindowInsets.safeDrawing),
            ) {
                TopBar(
                    title = currentVideo?.second?.title ?: "轻影",
                    subtitle = currentVideo?.first?.name,
                    sleepLabel = when {
                        timer.mode == SleepMode.COUNTDOWN -> formatCountdown(timer.remainingMs)
                        timer.mode == SleepMode.END_OF_VIDEO -> "本片后"
                        else -> "倒计时"
                    },
                    sleepActive = timer.active,
                    loopLabel = when {
                        player.loopActive -> "循环中"
                        player.loopReady -> "A-B 已设"
                        else -> "A-B"
                    },
                    loopActive = player.loopActive,
                    compact = isLandscape,
                    onBack = onBack,
                    onSleepClick = {
                        showSleepPanel = !showSleepPanel
                        showLoopPanel = false
                    },
                    onLoopClick = {
                        showLoopPanel = !showLoopPanel
                        showSleepPanel = false
                    },
                )

                Column(
                    modifier = Modifier.align(Alignment.BottomCenter),
                ) {
                    if (isLandscape) {
                        LandscapeControls(
                            player = player,
                            dragging = dragging,
                            dragValue = dragValue,
                            onDrag = { dragging = true; dragValue = it },
                            onDragFinished = {
                                player.seekTo(dragValue.toLong())
                                dragging = false
                            },
                            onToggleOrientation = toggleOrientation,
                        )
                    } else {
                        PortraitControls(
                            player = player,
                            dragging = dragging,
                            dragValue = dragValue,
                            onDrag = { dragging = true; dragValue = it },
                            onDragFinished = {
                                player.seekTo(dragValue.toLong())
                                dragging = false
                            },
                            onToggleOrientation = toggleOrientation,
                        )
                    }
                }

                if (showSleepPanel) {
                    Box(
                        modifier = Modifier
                            .fillMaxSize()
                            .padding(10.dp),
                        contentAlignment = Alignment.BottomCenter,
                    ) {
                        SleepTimerPanel(
                            timer = timer,
                            onClose = { showSleepPanel = false },
                            modifier = Modifier.fillMaxWidth(),
                        )
                    }
                }

                if (showLoopPanel) {
                    Box(
                        modifier = Modifier
                            .fillMaxSize()
                            .padding(10.dp),
                        contentAlignment = Alignment.BottomCenter,
                    ) {
                        SegmentLoopPanel(
                            player = player,
                            onClose = { showLoopPanel = false },
                            modifier = Modifier.fillMaxWidth(),
                        )
                    }
                }
            }
        }
    }
}

/** 开关窗口级常亮。窗口不可见时该 flag 本来就不生效，所以后台不会被它拦住熄屏。 */
private fun setKeepScreenOn(context: android.content.Context, on: Boolean) {
    val window = context.findActivity()?.window ?: return
    if (on) {
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
    } else {
        window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
    }
}

@Composable
private fun TopBar(
    title: String,
    subtitle: String?,
    sleepLabel: String,
    sleepActive: Boolean,
    loopLabel: String,
    loopActive: Boolean,
    compact: Boolean,
    onBack: () -> Unit,
    onSleepClick: () -> Unit,
    onLoopClick: () -> Unit,
) {
    val palette = LocalNeuPalette.current
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(start = 6.dp, end = 12.dp, top = if (compact) 2.dp else 6.dp, bottom = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            imageVector = Icons.AutoMirrored.Rounded.ArrowBack,
            contentDescription = "返回",
            tint = palette.textPrimary,
            modifier = Modifier
                .size(if (compact) 36.dp else 40.dp)
                .clickable(onClick = onBack)
                .padding(if (compact) 7.dp else 9.dp),
        )
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = title,
                color = palette.textPrimary,
                fontSize = 14.sp,
                fontWeight = FontWeight.SemiBold,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            if (!compact && !subtitle.isNullOrBlank()) {
                Text(
                    text = subtitle,
                    color = palette.textSecondary,
                    fontSize = 11.sp,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }

        TopCapsule(
            icon = if (sleepActive) Icons.Rounded.Timer else Icons.Rounded.TimerOff,
            label = sleepLabel,
            active = sleepActive,
            compact = compact,
            onClick = onSleepClick,
        )
        Spacer(Modifier.width(6.dp))
        TopCapsule(
            icon = Icons.Rounded.Repeat,
            label = loopLabel,
            active = loopActive,
            compact = compact,
            onClick = onLoopClick,
        )
    }
}

/** 顶栏胶囊按钮。倒计时与片段循环共用，保证两处的尺寸、配色一致。 */
@Composable
private fun TopCapsule(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    label: String,
    active: Boolean,
    compact: Boolean,
    onClick: () -> Unit,
) {
    val palette = LocalNeuPalette.current
    Row(
        modifier = Modifier
            .clip(RoundedCornerShape(14.dp))
            .background(if (active) palette.accent else palette.surface.copy(alpha = 0.85f))
            .clickable(onClick = onClick)
            .padding(horizontal = if (compact) 8.dp else 10.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            imageVector = icon,
            contentDescription = null,
            tint = if (active) Color.White else palette.textPrimary,
            modifier = Modifier.size(15.dp),
        )
        Spacer(Modifier.width(5.dp))
        Text(
            text = label,
            color = if (active) Color.White else palette.textPrimary,
            fontSize = 12.sp,
            fontWeight = if (active) FontWeight.SemiBold else FontWeight.Normal,
        )
    }
}

/** 竖屏控件：进度条 / 时间 / 五个控制键 / 倍速档位，纵向排开。 */
@Composable
private fun PortraitControls(
    player: PlaybackController,
    dragging: Boolean,
    dragValue: Float,
    onDrag: (Float) -> Unit,
    onDragFinished: () -> Unit,
    onToggleOrientation: () -> Unit,
) {
    val palette = LocalNeuPalette.current
    val duration = player.durationMs.coerceAtLeast(0L)
    val shown = if (dragging) dragValue else player.positionMs.toFloat()
    val range = 0f..(if (duration > 0L) duration.toFloat() else 1f)

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 14.dp),
    ) {
        NeuSlider(
            value = shown.coerceIn(range.start, range.endInclusive),
            onValueChange = onDrag,
            onValueChangeFinished = onDragFinished,
            valueRange = range,
            modifier = Modifier.fillMaxWidth(),
        )

        // 进度条下面标出 A-B 区间，一眼能看出在循环哪一段
        LoopRangeBar(
            player = player,
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = 6.dp),
        )

        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                text = formatDuration(shown.toLong()),
                color = palette.textPrimary,
                fontSize = 12.sp,
            )
            Text(
                text = " / ${formatDuration(duration)}",
                color = palette.textSecondary,
                fontSize = 12.sp,
            )
            Spacer(Modifier.weight(1f))
            Text(
                text = "${player.queueSize} 个视频",
                color = palette.textSecondary,
                fontSize = 11.sp,
            )
            Spacer(Modifier.width(8.dp))
            RotateButton(
                isLandscape = false,
                onClick = onToggleOrientation,
            )
        }

        Spacer(Modifier.height(6.dp))

        LoopMarkRow(player = player)

        Spacer(Modifier.height(10.dp))

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceEvenly,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            ControlIcon(
                icon = Icons.Rounded.SkipPrevious,
                contentDescription = "上一个",
                enabled = player.hasPrevious,
                onClick = { player.previous() },
            )
            ControlIcon(
                icon = Icons.Rounded.FastRewind,
                contentDescription = "快退 15 秒",
                onClick = { player.seekBy(-15_000L) },
            )
            ControlIcon(
                icon = if (player.isPlaying) Icons.Rounded.Pause else Icons.Rounded.PlayArrow,
                contentDescription = if (player.isPlaying) "暂停" else "播放",
                size = 58.dp,
                highlighted = true,
                onClick = { player.toggle() },
            )
            ControlIcon(
                icon = Icons.Rounded.FastForward,
                contentDescription = "快进 15 秒",
                onClick = { player.seekBy(15_000L) },
            )
            ControlIcon(
                icon = Icons.Rounded.SkipNext,
                contentDescription = "下一个",
                enabled = player.hasNext,
                onClick = { player.next() },
            )
        }

        Spacer(Modifier.height(12.dp))

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text("倍速", color = palette.textSecondary, fontSize = 11.sp)
            PlaybackController.SPEED_STEPS.forEach { step ->
                val active = abs(player.speed - step) < 0.001f
                Box(
                    modifier = Modifier
                        .weight(1f)
                        .clip(RoundedCornerShape(8.dp))
                        .background(if (active) palette.accent else palette.surface.copy(alpha = 0.7f))
                        .clickable { player.applySpeed(step) }
                        .padding(vertical = 6.dp),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(
                        text = speedLabel(step),
                        color = if (active) Color.White else palette.textSecondary,
                        fontSize = 11.sp,
                        fontWeight = if (active) FontWeight.SemiBold else FontWeight.Normal,
                    )
                }
            }
        }
    }
}

/**
 * A-B 区间条：贴在进度条下面，标出正在循环的那一段。
 * 没设片段时整条不占位 —— 不留一条没有意义的空轨。
 */
@Composable
private fun LoopRangeBar(player: PlaybackController, modifier: Modifier = Modifier) {
    val palette = LocalNeuPalette.current
    val duration = player.durationMs
    if (!player.loopReady || duration <= 0L) return

    val startFraction = (player.loopStartMs.toFloat() / duration.toFloat()).coerceIn(0f, 1f)
    val endFraction = (player.loopEndMs.toFloat() / duration.toFloat()).coerceIn(startFraction, 1f)

    Box(
        modifier = modifier
            .height(3.dp)
            .clip(RoundedCornerShape(2.dp))
            .background(palette.divider),
    ) {
        Row(modifier = Modifier.fillMaxSize()) {
            // weight 只接受正数，用极小值代表「贴在最左端」
            Spacer(Modifier.weight(startFraction.coerceAtLeast(0.0001f)))
            Box(
                modifier = Modifier
                    .weight((endFraction - startFraction).coerceAtLeast(0.0001f))
                    .fillMaxHeight()
                    .background(if (player.loopActive) palette.accent else palette.textSecondary),
            )
            Spacer(Modifier.weight((1f - endFraction).coerceAtLeast(0.0001f)))
        }
    }
}

/**
 * 片段标记行：播到起点点「A」、到终点点「B」，两点齐了自动开始循环。
 * 点「循环中」可以临时关掉（A/B 保留），点「清除」把两点都清掉。
 */
@Composable
private fun LoopMarkRow(player: PlaybackController) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        LoopChip(
            label = if (player.loopStartMs >= 0L) "A ${formatMark(player.loopStartMs)}" else "设 A",
            active = player.loopStartMs >= 0L,
            onClick = { player.markLoopStart() },
        )
        LoopChip(
            label = if (player.loopEndMs >= 0L) "B ${formatMark(player.loopEndMs)}" else "设 B",
            active = player.loopEndMs >= 0L,
            onClick = { player.markLoopEnd() },
        )
        if (player.loopReady) {
            LoopChip(
                label = if (player.loopActive) "循环中" else "循环关",
                active = player.loopActive,
                onClick = { player.setLooping(!player.loopActive) },
            )
            LoopChip(
                label = "清除",
                active = false,
                onClick = { player.clearLoop() },
            )
        }
        Spacer(Modifier.weight(1f))
    }
}

@Composable
private fun LoopChip(
    label: String,
    active: Boolean,
    onClick: () -> Unit,
) {
    val palette = LocalNeuPalette.current
    Box(
        modifier = Modifier
            .clip(RoundedCornerShape(8.dp))
            .background(if (active) palette.accent else palette.surface.copy(alpha = 0.7f))
            .clickable(onClick = onClick)
            .padding(horizontal = 10.dp, vertical = 6.dp),
    ) {
        Text(
            text = label,
            color = if (active) Color.White else palette.textSecondary,
            fontSize = 11.sp,
            fontWeight = if (active) FontWeight.SemiBold else FontWeight.Normal,
            maxLines = 1,
        )
    }
}

/**
 * 片段点位的时间文本。
 * 0 点要显示 `0:00` —— [formatDuration] 对 <=0 返回 `--:--`，那是给「未知时长」用的。
 */
private fun formatMark(ms: Long): String = if (ms <= 0L) "0:00" else formatDuration(ms)

/**
 * 横屏控件：压成一行。
 * 横屏时纵向空间只有 300–400dp，照竖屏那样堆四行会把画面挤掉一大半。
 */
@Composable
private fun LandscapeControls(
    player: PlaybackController,
    dragging: Boolean,
    dragValue: Float,
    onDrag: (Float) -> Unit,
    onDragFinished: () -> Unit,
    onToggleOrientation: () -> Unit,
) {
    val palette = LocalNeuPalette.current
    val duration = player.durationMs.coerceAtLeast(0L)
    val shown = if (dragging) dragValue else player.positionMs.toFloat()
    val range = 0f..(if (duration > 0L) duration.toFloat() else 1f)

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        ControlIcon(
            icon = if (player.isPlaying) Icons.Rounded.Pause else Icons.Rounded.PlayArrow,
            contentDescription = if (player.isPlaying) "暂停" else "播放",
            size = 42.dp,
            highlighted = true,
            onClick = { player.toggle() },
        )
        ControlIcon(
            icon = Icons.Rounded.SkipPrevious,
            contentDescription = "上一个",
            size = 34.dp,
            enabled = player.hasPrevious,
            onClick = { player.previous() },
        )
        ControlIcon(
            icon = Icons.Rounded.SkipNext,
            contentDescription = "下一个",
            size = 34.dp,
            enabled = player.hasNext,
            onClick = { player.next() },
        )

        Spacer(Modifier.width(10.dp))
        Text(
            text = formatDuration(shown.toLong()),
            color = palette.textPrimary,
            fontSize = 12.sp,
        )

        NeuSlider(
            value = shown.coerceIn(range.start, range.endInclusive),
            onValueChange = onDrag,
            onValueChangeFinished = onDragFinished,
            valueRange = range,
            modifier = Modifier.weight(1f).padding(horizontal = 6.dp),
        )

        Text(
            text = formatDuration(duration),
            color = palette.textSecondary,
            fontSize = 12.sp,
        )

        Spacer(Modifier.width(10.dp))

        // 横屏放不下一排倍速档位，改成点一下切下一档
        Box(
            modifier = Modifier
                .clip(RoundedCornerShape(8.dp))
                .background(palette.surface.copy(alpha = 0.75f))
                .clickable { player.applySpeed(nextSpeed(player.speed)) }
                .padding(horizontal = 10.dp, vertical = 7.dp),
            contentAlignment = Alignment.Center,
        ) {
            Text(
                text = speedLabel(player.speed),
                color = palette.textPrimary,
                fontSize = 12.sp,
                fontWeight = FontWeight.Medium,
            )
        }

        Spacer(Modifier.width(6.dp))
        RotateButton(
            isLandscape = true,
            onClick = onToggleOrientation,
            size = 36.dp,
        )
    }
}

/**
 * 横屏切换键。
 *
 * 系统自动旋转关着的时候，光靠重力是转不过去的 —— 这个键直接给 Activity 设
 * `requestedOrientation`，所以任何设备上都能一键进/出横屏。
 */
@Composable
private fun RotateButton(
    isLandscape: Boolean,
    onClick: () -> Unit,
    size: Dp = 34.dp,
) {
    val palette = LocalNeuPalette.current
    Box(
        modifier = Modifier
            .size(size)
            .clip(RoundedCornerShape(8.dp))
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            imageVector = if (isLandscape) Icons.Rounded.FullscreenExit else Icons.Rounded.Fullscreen,
            contentDescription = if (isLandscape) "退出横屏" else "横屏播放",
            tint = palette.textPrimary,
            modifier = Modifier.size(size * 0.72f),
        )
    }
}

/** 手动方向的三种取值。0 交还系统，1 钉横屏，2 钉竖屏。 */
private const val ORIENTATION_SYSTEM = 0
private const val ORIENTATION_LANDSCAPE = 1
private const val ORIENTATION_PORTRAIT = 2

private fun speedLabel(step: Float): String =
    if (step == step.toInt().toFloat()) "${step.toInt()}x" else "${step}x"

private fun nextSpeed(current: Float): Float {
    val steps = PlaybackController.SPEED_STEPS
    val index = steps.indexOfFirst { abs(it - current) < 0.001f }
    return if (index < 0) 1.0f else steps[(index + 1) % steps.size]
}

@Composable
private fun ControlIcon(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    contentDescription: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    size: Dp = 46.dp,
    enabled: Boolean = true,
    highlighted: Boolean = false,
) {
    val palette = LocalNeuPalette.current
    Box(
        modifier = modifier
            .size(size)
            .clip(RoundedCornerShape(size / 2))
            .background(if (highlighted) palette.accent else palette.surface.copy(alpha = 0.75f))
            .clickable(enabled = enabled, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            imageVector = icon,
            contentDescription = contentDescription,
            tint = when {
                !enabled -> palette.textSecondary.copy(alpha = 0.4f)
                highlighted -> Color.White
                else -> palette.textPrimary
            },
            modifier = Modifier.size(size * 0.5f),
        )
    }
}
