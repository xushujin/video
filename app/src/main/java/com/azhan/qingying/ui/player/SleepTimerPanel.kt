package com.azhan.qingying.ui.player

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.MovieFilter
import androidx.compose.material.icons.rounded.Timer
import androidx.compose.material.icons.rounded.TimerOff
import androidx.compose.material3.Icon
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.azhan.qingying.data.SleepAction
import com.azhan.qingying.data.SleepMode
import com.azhan.qingying.player.SleepTimer
import com.azhan.qingying.ui.neu.NeuButton
import com.azhan.qingying.ui.neu.NeuSegmented
import com.azhan.qingying.ui.neu.NeuSurface
import com.azhan.qingying.ui.theme.LocalNeuPalette
import com.azhan.qingying.util.formatCountdown

/**
 * 倒计时退出面板（需求 3）。
 *
 * 三种起算方式：预设档位、自定义分钟数、播完当前视频。
 * 到点后的动作可选「退出应用」或「仅暂停」—— 需求要的是退出，
 * 但「看到睡着只想停声音、不想退出」也是常见场景，所以做成可选项而不是写死。
 *
 * 内容整体可滚动并限高：横屏时屏幕只有 300–400dp 高，不限高会把整个面板顶出屏幕。
 */
@Composable
fun SleepTimerPanel(
    timer: SleepTimer,
    onClose: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val palette = LocalNeuPalette.current
    val focusManager = LocalFocusManager.current
    var customText by remember { mutableStateOf("") }

    fun startCustom() {
        val minutes = customText.toIntOrNull() ?: return
        if (minutes <= 0) return
        timer.startCountdown(minutes.coerceAtMost(MAX_CUSTOM_MINUTES))
        focusManager.clearFocus()
    }

    NeuSurface(
        modifier = modifier.fillMaxWidth().heightIn(max = 480.dp),
        cornerRadius = 20.dp,
        elevation = 8.dp,
        contentAlignment = Alignment.TopStart,
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .padding(16.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    imageVector = Icons.Rounded.Timer,
                    contentDescription = null,
                    tint = palette.accent,
                    modifier = Modifier.size(18.dp),
                )
                Spacer(Modifier.width(8.dp))
                Text(
                    text = "倒计时退出",
                    color = palette.textPrimary,
                    fontSize = 15.sp,
                    fontWeight = FontWeight.SemiBold,
                    modifier = Modifier.weight(1f),
                )
                Icon(
                    imageVector = Icons.Rounded.Close,
                    contentDescription = "关闭",
                    tint = palette.textSecondary,
                    modifier = Modifier
                        .size(22.dp)
                        .clickable(onClick = onClose)
                        .padding(2.dp),
                )
            }

            Spacer(Modifier.height(10.dp))

            if (timer.active) {
                val status = when (timer.mode) {
                    SleepMode.COUNTDOWN -> "还剩 ${formatCountdown(timer.remainingMs)}"
                    SleepMode.END_OF_VIDEO -> "播完当前视频后执行"
                    SleepMode.OFF -> ""
                }
                Text(
                    text = "$status · ${if (timer.action == SleepAction.EXIT) "退出应用" else "仅暂停播放"}",
                    color = palette.accent,
                    fontSize = 12.sp,
                )
                Spacer(Modifier.height(10.dp))
            }

            // ---------- 预设档位：两行四列，不用横向滚动
            SleepTimer.PRESETS.chunked(4).forEach { row ->
                Row(
                    modifier = Modifier.fillMaxWidth().padding(vertical = 3.dp),
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    row.forEach { minutes ->
                        SleepChip(
                            label = "$minutes 分钟",
                            selected = timer.mode == SleepMode.COUNTDOWN &&
                                timer.totalMs == minutes * 60_000L,
                            modifier = Modifier.weight(1f),
                            onClick = { timer.startCountdown(minutes) },
                        )
                    }
                }
            }

            Spacer(Modifier.height(10.dp))

            // ---------- 自定义分钟数
            Text("自定义", color = palette.textSecondary, fontSize = 12.sp)
            Spacer(Modifier.height(6.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                OutlinedTextField(
                    value = customText,
                    onValueChange = { input ->
                        // 只收数字，最多三位；超上限的部分由 startCustom 夹掉
                        customText = input.filter { it.isDigit() }.take(3)
                    },
                    modifier = Modifier.weight(1f),
                    singleLine = true,
                    placeholder = { Text("分钟数", color = palette.textSecondary, fontSize = 13.sp) },
                    suffix = { Text("分钟", color = palette.textSecondary, fontSize = 12.sp) },
                    shape = RoundedCornerShape(10.dp),
                    keyboardOptions = KeyboardOptions(
                        keyboardType = KeyboardType.Number,
                        imeAction = ImeAction.Done,
                    ),
                    keyboardActions = KeyboardActions(onDone = { startCustom() }),
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedTextColor = palette.textPrimary,
                        unfocusedTextColor = palette.textPrimary,
                        focusedBorderColor = palette.accent,
                        unfocusedBorderColor = palette.divider,
                        cursorColor = palette.accent,
                        focusedContainerColor = Color.Transparent,
                        unfocusedContainerColor = Color.Transparent,
                    ),
                )
                Spacer(Modifier.width(6.dp))
                NeuButton(
                    onClick = { startCustom() },
                    highlighted = customText.isNotBlank(),
                    enabled = customText.isNotBlank(),
                    elevation = 5.dp,
                ) {
                    Text(
                        text = "开始",
                        color = if (customText.isNotBlank()) Color.White else palette.textSecondary,
                        fontSize = 13.sp,
                        fontWeight = FontWeight.Medium,
                    )
                }
            }

            Spacer(Modifier.height(8.dp))

            // ---------- 播完当前视频
            SleepChip(
                label = "播完当前视频后执行",
                selected = timer.mode == SleepMode.END_OF_VIDEO,
                icon = Icons.Rounded.MovieFilter,
                modifier = Modifier.fillMaxWidth(),
                onClick = { timer.startEndOfVideo() },
            )

            Spacer(Modifier.height(12.dp))

            Text("到点后", color = palette.textSecondary, fontSize = 12.sp)
            Spacer(Modifier.height(4.dp))
            NeuSegmented(
                options = listOf(SleepAction.EXIT, SleepAction.PAUSE),
                selected = timer.action,
                onSelect = { timer.chooseAction(it) },
                modifier = Modifier.fillMaxWidth(),
                label = { if (it == SleepAction.EXIT) "退出应用" else "仅暂停" },
            )

            if (timer.active) {
                Spacer(Modifier.height(10.dp))
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(12.dp))
                        .clickable {
                            timer.cancel()
                            onClose()
                        }
                        .padding(vertical = 10.dp),
                    horizontalArrangement = Arrangement.Center,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Icon(
                        imageVector = Icons.Rounded.TimerOff,
                        contentDescription = null,
                        tint = palette.textSecondary,
                        modifier = Modifier.size(16.dp),
                    )
                    Spacer(Modifier.width(6.dp))
                    Text("取消倒计时", color = palette.textSecondary, fontSize = 13.sp)
                }
            }
        }
    }
}

@Composable
private fun SleepChip(
    label: String,
    selected: Boolean,
    modifier: Modifier = Modifier,
    icon: ImageVector? = null,
    onClick: () -> Unit,
) {
    val palette = LocalNeuPalette.current
    Box(
        modifier = modifier
            .clip(RoundedCornerShape(10.dp))
            .background(if (selected) palette.accent else palette.divider)
            .clickable(onClick = onClick)
            .padding(vertical = 9.dp),
        contentAlignment = Alignment.Center,
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            if (icon != null) {
                Icon(
                    imageVector = icon,
                    contentDescription = null,
                    tint = if (selected) Color.White else palette.textSecondary,
                    modifier = Modifier.size(15.dp),
                )
                Spacer(Modifier.width(6.dp))
            }
            Text(
                text = label,
                color = if (selected) Color.White else palette.textPrimary,
                fontSize = 12.sp,
                fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal,
            )
        }
    }
}

/** 自定义上限：10 小时。再长就不该用倒计时了。 */
private const val MAX_CUSTOM_MINUTES = 600
