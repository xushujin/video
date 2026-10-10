package com.azhan.qingying.ui.player

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
import androidx.compose.material.icons.rounded.FastForward
import androidx.compose.material.icons.rounded.FastRewind
import androidx.compose.material.icons.rounded.Pause
import androidx.compose.material.icons.rounded.PlayArrow
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
    var dragging by remember { mutableStateOf(false) }
    var dragValue by remember { mutableFloatStateOf(0f) }

    // 控件自动隐藏：播放中 4 秒无操作就收起来；暂停时、拖进度条时、开着倒计时面板时不收
    LaunchedEffect(controlsVisible, player.isPlaying, showSleepPanel, dragging) {
        if (controlsVisible && player.isPlaying && !showSleepPanel && !dragging) {
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
        player.errorMessage?.let { message ->
            Box(
                modifier = Modifier
                    .align(Alignment.Center)
                    .padding(24.dp)
                    .clip(RoundedCornerShape(12.dp))
                    .background(palette.surface.copy(alpha = 0.92f))
                    .padding(horizontal = 16.dp, vertical = 12.dp),
            ) {
                Column {
                    Text("这个文件播不了", color = palette.textPrimary, fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
                    Spacer(Modifier.height(4.dp))
                    Text(message, color = palette.textSecondary, fontSize = 11.sp)
                    Spacer(Modifier.height(8.dp))
                    Text(
                        text = "常见原因是编码不被设备支持（如部分 RMVB / 带 DRM 的文件）。",
                        color = palette.textSecondary,
                        fontSize = 11.sp,
                    )
                }
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
                    compact = isLandscape,
                    onBack = onBack,
                    onSleepClick = { showSleepPanel = !showSleepPanel },
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
    compact: Boolean,
    onBack: () -> Unit,
    onSleepClick: () -> Unit,
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

        Row(
            modifier = Modifier
                .clip(RoundedCornerShape(14.dp))
                .background(if (sleepActive) palette.accent else palette.surface.copy(alpha = 0.85f))
                .clickable(onClick = onSleepClick)
                .padding(horizontal = 10.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(
                imageVector = if (sleepActive) Icons.Rounded.Timer else Icons.Rounded.TimerOff,
                contentDescription = null,
                tint = if (sleepActive) Color.White else palette.textPrimary,
                modifier = Modifier.size(15.dp),
            )
            Spacer(Modifier.width(5.dp))
            Text(
                text = sleepLabel,
                color = if (sleepActive) Color.White else palette.textPrimary,
                fontSize = 12.sp,
                fontWeight = if (sleepActive) FontWeight.SemiBold else FontWeight.Normal,
            )
        }
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
        }

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
    }
}

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
