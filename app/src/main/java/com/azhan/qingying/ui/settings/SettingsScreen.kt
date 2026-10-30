package com.azhan.qingying.ui.settings

import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.azhan.qingying.AppContainer
import com.azhan.qingying.BuildConfig
import com.azhan.qingying.data.SleepAction
import com.azhan.qingying.data.UpdateState
import com.azhan.qingying.player.PlaybackController
import com.azhan.qingying.ui.neu.NeuButton
import com.azhan.qingying.ui.neu.NeuSegmented
import com.azhan.qingying.ui.neu.NeuSurface
import com.azhan.qingying.ui.neu.NeuSwitch
import com.azhan.qingying.ui.theme.LocalNeuPalette
import kotlinx.coroutines.launch

/** 横向滑动幅度的可选值：整屏对应的毫秒数。 */
private val SEEK_SPAN_OPTIONS = listOf(30_000L, 60_000L, 120_000L, 300_000L)

@Composable
fun SettingsScreen(
    container: AppContainer,
    onBack: () -> Unit,
) {
    val palette = LocalNeuPalette.current
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val prefs by container.library.prefs.collectAsState()

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(palette.background)
            .windowInsetsPadding(WindowInsets.safeDrawing)
            .verticalScroll(rememberScrollState()),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(start = 6.dp, top = 18.dp, end = 16.dp, bottom = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(
                imageVector = Icons.AutoMirrored.Rounded.ArrowBack,
                contentDescription = "返回",
                tint = palette.textPrimary,
                modifier = Modifier
                    .size(40.dp)
                    .clickable(onClick = onBack)
                    .padding(9.dp),
            )
            Spacer(Modifier.width(4.dp))
            Text(
                text = "设置",
                color = palette.textPrimary,
                fontSize = 20.sp,
                fontWeight = FontWeight.Bold,
            )
        }

        SettingCard(title = "外观", subtitle = "播放页恒为深色，不受这里影响") {
            NeuSegmented(
                options = listOf(true, false),
                selected = prefs.darkShell,
                onSelect = { dark ->
                    scope.launch { container.library.updatePrefs { it.copy(darkShell = dark) } }
                },
                modifier = Modifier.fillMaxWidth(),
                label = { if (it) "深色" else "浅色" },
            )
        }

        SettingCard(title = "播放") {
            SwitchRow(
                title = "自动续播",
                subtitle = "打开视频时从上次的位置接着看",
                checked = prefs.autoResume,
                onCheckedChange = { value ->
                    scope.launch { container.library.updatePrefs { it.copy(autoResume = value) } }
                },
            )

            Spacer(Modifier.height(8.dp))

            Text("默认倍速", color = palette.textSecondary, fontSize = 12.sp)
            Spacer(Modifier.height(6.dp))
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                PlaybackController.SPEED_STEPS.forEach { step ->
                    val active = kotlin.math.abs(prefs.playbackSpeed - step) < 0.001f
                    Box(
                        modifier = Modifier
                            .weight(1f)
                            .clip(RoundedCornerShape(8.dp))
                            .background(if (active) palette.accent else palette.divider)
                            .clickable {
                                scope.launch {
                                    container.library.updatePrefs { it.copy(playbackSpeed = step) }
                                    container.player.applyDefaultSpeed(step)
                                }
                            }
                            .padding(vertical = 7.dp),
                        contentAlignment = Alignment.Center,
                    ) {
                        Text(
                            text = if (step == step.toInt().toFloat()) "${step.toInt()}x" else "${step}x",
                            color = if (active) androidx.compose.ui.graphics.Color.White else palette.textSecondary,
                            fontSize = 11.sp,
                        )
                    }
                }
            }

            Spacer(Modifier.height(14.dp))

            Text("滑动快进幅度", color = palette.textSecondary, fontSize = 12.sp)
            Text(
                text = "横向滑满一屏对应的时长，短了更精细、长了更省事",
                color = palette.textSecondary,
                fontSize = 11.sp,
            )
            Spacer(Modifier.height(6.dp))
            NeuSegmented(
                options = SEEK_SPAN_OPTIONS,
                selected = prefs.seekSensitivityMs,
                onSelect = { span ->
                    scope.launch {
                        container.library.updatePrefs { it.copy(seekSensitivityMs = span) }
                    }
                },
                modifier = Modifier.fillMaxWidth(),
                label = { span ->
                    when (span) {
                        30_000L -> "30 秒"
                        60_000L -> "1 分钟"
                        300_000L -> "5 分钟"
                        else -> "2 分钟"
                    }
                },
            )
        }

        SettingCard(title = "倒计时退出", subtitle = "倒计时到点后的默认动作，播放页里可临时改") {
            NeuSegmented(
                options = listOf(SleepAction.EXIT, SleepAction.PAUSE),
                selected = prefs.sleepAction,
                onSelect = { action ->
                    scope.launch {
                        container.library.updatePrefs { it.copy(sleepAction = action) }
                        container.sleepTimer.chooseAction(action)
                    }
                },
                modifier = Modifier.fillMaxWidth(),
                label = { if (it == SleepAction.EXIT) "退出应用" else "仅暂停" },
            )
        }

        SettingCard(title = "数据") {
            NeuButton(
                onClick = {
                    container.library.clearAllProgress()
                    Toast.makeText(context, "已清空播放记录", Toast.LENGTH_SHORT).show()
                },
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text("清空播放记录", color = palette.textPrimary, fontSize = 13.sp)
            }
            NeuButton(
                onClick = {
                    scope.launch {
                        val count = container.library.rescanAll()
                        Toast.makeText(context, "重新扫描完成，共 $count 个视频", Toast.LENGTH_SHORT).show()
                    }
                },
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text("重新扫描全部文件夹", color = palette.textPrimary, fontSize = 13.sp)
            }
        }

        SettingCard(title = "关于") {
            Text(
                text = "轻影 · 本地视频播放器  v${BuildConfig.VERSION_NAME}",
                color = palette.textPrimary,
                fontSize = 13.sp,
            )
            Spacer(Modifier.height(6.dp))
            Text(
                text = "只播放你手机里已有的视频，不上传、不申请存储权限。" +
                    "视频目录通过系统文件选择器授权，随时可以在列表里移除。",
                color = palette.textSecondary,
                fontSize = 11.sp,
            )

            Spacer(Modifier.height(12.dp))

            when (val update = container.updater.state) {
                is UpdateState.Idle -> CheckUpdateButton("检查更新") {
                    container.updater.check(BuildConfig.VERSION_NAME)
                }

                is UpdateState.Checking -> UpdateHint("正在检查…")

                // 每个「结束态」都要留一条回到检查的出口，否则点过一次就卡死在这里
                is UpdateState.UpToDate -> {
                    UpdateHint("已是最新版本")
                    Spacer(Modifier.height(6.dp))
                    CheckUpdateButton("再次检查") {
                        container.updater.check(BuildConfig.VERSION_NAME)
                    }
                }

                is UpdateState.Downloading -> {
                    if (update.progress >= 0) {
                        UpdateHint("正在下载 ${update.progress}%")
                        Spacer(Modifier.height(6.dp))
                        UpdateProgressBar(update.progress)
                    } else {
                        UpdateHint("正在下载…（总大小未知，完成后会自动打开安装器）")
                    }
                }

                is UpdateState.Installing -> {
                    UpdateHint("已打开安装器，按系统提示完成安装；没弹出来就再点一次")
                    Spacer(Modifier.height(6.dp))
                    CheckUpdateButton("重新检查") {
                        container.updater.check(BuildConfig.VERSION_NAME)
                    }
                }

                is UpdateState.Blocked -> {
                    UpdateHint(update.message)
                    Spacer(Modifier.height(6.dp))
                    val pending = update.pending
                    if (pending != null) {
                        CheckUpdateButton("继续安装") {
                            container.updater.downloadAndInstall(pending)
                        }
                    } else {
                        CheckUpdateButton("重新检查") {
                            container.updater.check(BuildConfig.VERSION_NAME)
                        }
                    }
                }

                is UpdateState.Failed -> {
                    UpdateHint("检查失败：${update.message}")
                    Spacer(Modifier.height(6.dp))
                    CheckUpdateButton("重试") {
                        container.updater.check(BuildConfig.VERSION_NAME)
                    }
                }

                is UpdateState.Available -> {
                    Text(
                        text = "发现新版本 v${update.info.versionName}",
                        color = palette.accent,
                        fontSize = 13.sp,
                        fontWeight = FontWeight.SemiBold,
                    )
                    if (update.info.notes.isNotBlank()) {
                        Spacer(Modifier.height(4.dp))
                        Text(
                            text = update.info.notes,
                            color = palette.textSecondary,
                            fontSize = 11.sp,
                            maxLines = 6,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                    Spacer(Modifier.height(8.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        NeuButton(
                            onClick = { container.updater.downloadAndInstall(update.info) },
                            modifier = Modifier.weight(1f),
                        ) {
                            Text("下载并安装", color = palette.textPrimary, fontSize = 13.sp)
                        }
                        NeuButton(
                            onClick = { container.updater.reset() },
                            modifier = Modifier.weight(1f),
                        ) {
                            Text("稍后", color = palette.textSecondary, fontSize = 13.sp)
                        }
                    }
                }
            }

            Spacer(Modifier.height(8.dp))
            Text(
                text = "检查更新是本应用唯一的联网行为：只在点按钮时访问 GitHub，不同步任何数据。",
                color = palette.textSecondary,
                fontSize = 10.sp,
            )
        }

        Spacer(Modifier.height(32.dp))
    }
}

@Composable
private fun SettingCard(
    title: String,
    subtitle: String? = null,
    content: @Composable () -> Unit,
) {
    val palette = LocalNeuPalette.current
    Column(modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp)) {
        Text(
            text = title,
            color = palette.textPrimary,
            fontSize = 13.sp,
            fontWeight = FontWeight.SemiBold,
            modifier = Modifier.padding(start = 8.dp, top = 14.dp, bottom = 2.dp),
        )
        if (subtitle != null) {
            Text(
                text = subtitle,
                color = palette.textSecondary,
                fontSize = 11.sp,
                modifier = Modifier.padding(start = 8.dp, bottom = 2.dp),
            )
        }
        NeuSurface(
            modifier = Modifier.fillMaxWidth(),
            cornerRadius = 16.dp,
            elevation = 6.dp,
            contentAlignment = Alignment.TopStart,
        ) {
            Column(modifier = Modifier.fillMaxWidth().padding(12.dp)) {
                content()
            }
        }
    }
}

@Composable
private fun UpdateHint(text: String) {
    val palette = LocalNeuPalette.current
    Text(text, color = palette.textSecondary, fontSize = 11.sp)
}

@Composable
private fun UpdateProgressBar(progress: Int) {
    val palette = LocalNeuPalette.current
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .height(6.dp)
            .clip(RoundedCornerShape(3.dp))
            .background(palette.divider),
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth((progress / 100f).coerceIn(0f, 1f))
                .fillMaxHeight()
                .background(palette.accent),
        )
    }
}

@Composable
private fun CheckUpdateButton(label: String, onClick: () -> Unit) {
    val palette = LocalNeuPalette.current
    NeuButton(onClick = onClick, modifier = Modifier.fillMaxWidth()) {
        Text(label, color = palette.textPrimary, fontSize = 13.sp)
    }
}

@Composable
private fun SwitchRow(
    title: String,
    subtitle: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
) {
    val palette = LocalNeuPalette.current
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(title, color = palette.textPrimary, fontSize = 13.sp)
            Text(subtitle, color = palette.textSecondary, fontSize = 11.sp)
        }
        NeuSwitch(checked = checked, onCheckedChange = onCheckedChange)
    }
}
