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
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.Repeat
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.azhan.qingying.player.PlaybackController
import com.azhan.qingying.ui.neu.NeuButton
import com.azhan.qingying.ui.neu.NeuSurface
import com.azhan.qingying.ui.neu.NeuSwitch
import com.azhan.qingying.ui.theme.LocalNeuPalette
import com.azhan.qingying.util.formatDuration

/**
 * 片段循环面板：选一段反复播。
 *
 * 两种用法都支持：
 * - 播到起点点「设 A」、播到终点点「设 B」，两点齐了立刻开始循环；
 * - 只点「设 B」，默认从开头循环到这里。
 *
 * 面板不遮挡画面（贴底浮层），所以可以边看边标点；点 A/B 的时间还能跳回去。
 * 内容限高并可滚动 —— 横屏时纵向只有 300–400dp，不限高会把面板顶出屏幕。
 */
@Composable
fun SegmentLoopPanel(
    player: PlaybackController,
    onClose: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val palette = LocalNeuPalette.current

    NeuSurface(
        modifier = modifier.fillMaxWidth().heightIn(max = 420.dp),
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
                    imageVector = Icons.Rounded.Repeat,
                    contentDescription = null,
                    tint = palette.accent,
                    modifier = Modifier.size(18.dp),
                )
                Spacer(Modifier.width(8.dp))
                Text(
                    text = "片段循环",
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

            if (player.loopReady) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    LoopValue(
                        label = "A",
                        timeMs = player.loopStartMs,
                        modifier = Modifier.weight(1f),
                        onClick = { player.seekTo(player.loopStartMs) },
                    )
                    Text("→", color = palette.textSecondary, fontSize = 13.sp)
                    LoopValue(
                        label = "B",
                        timeMs = player.loopEndMs,
                        modifier = Modifier.weight(1f),
                        onClick = { player.seekTo(player.loopEndMs) },
                    )
                }
                Spacer(Modifier.height(6.dp))
                Text(
                    text = "片段长度 ${formatDuration(player.loopEndMs - player.loopStartMs)}" +
                        " · 点上面的时间可以跳过去",
                    color = palette.textSecondary,
                    fontSize = 11.sp,
                )
            } else {
                Text(
                    text = "播到起点点「设 A」，播到终点点「设 B」，两点齐了就开始循环。" +
                        "只设 B 也可以 —— 默认从开头循环到这里。",
                    color = palette.textSecondary,
                    fontSize = 12.sp,
                )
            }

            Spacer(Modifier.height(12.dp))

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                NeuButton(
                    onClick = { player.markLoopStart() },
                    highlighted = true,
                    elevation = 5.dp,
                    modifier = Modifier.weight(1f),
                ) {
                    Text("设 A 点", color = Color.White, fontSize = 13.sp, fontWeight = FontWeight.Medium)
                }
                NeuButton(
                    onClick = { player.markLoopEnd() },
                    highlighted = true,
                    elevation = 5.dp,
                    modifier = Modifier.weight(1f),
                ) {
                    Text("设 B 点", color = Color.White, fontSize = 13.sp, fontWeight = FontWeight.Medium)
                }
            }

            Spacer(Modifier.height(8.dp))

            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text("循环播放", color = palette.textPrimary, fontSize = 13.sp)
                    Text(
                        text = if (player.loopReady) {
                            "关掉后 A/B 点保留，随时可以再打开"
                        } else {
                            "设好 A、B 两点后可用"
                        },
                        color = palette.textSecondary,
                        fontSize = 11.sp,
                    )
                }
                NeuSwitch(
                    checked = player.loopActive,
                    onCheckedChange = { player.setLooping(it) },
                    enabled = player.loopReady,
                )
            }

            if (player.loopReady) {
                Spacer(Modifier.height(8.dp))
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(12.dp))
                        .clickable {
                            player.clearLoop()
                            onClose()
                        }
                        .padding(vertical = 9.dp),
                    contentAlignment = Alignment.Center,
                ) {
                    Text("清除 A/B 点", color = palette.textSecondary, fontSize = 13.sp)
                }
            }

            Spacer(Modifier.height(4.dp))

            Text(
                text = "片段只对当前视频有效，切换视频会自动清除。" +
                    "循环期间视频永远播不完，所以「播完当前视频后」的倒计时不会触发。",
                color = palette.textSecondary,
                fontSize = 11.sp,
            )
        }
    }
}

/** A 点 / B 点的时间块。点一下跳到那个位置，方便微调。 */
@Composable
private fun LoopValue(
    label: String,
    timeMs: Long,
    modifier: Modifier = Modifier,
    onClick: () -> Unit,
) {
    val palette = LocalNeuPalette.current
    Column(
        modifier = modifier
            .clip(RoundedCornerShape(10.dp))
            .background(palette.divider)
            .clickable(onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 8.dp),
    ) {
        Text(label, color = palette.textSecondary, fontSize = 10.sp)
        Text(
            text = if (timeMs <= 0L) "0:00" else formatDuration(timeMs),
            color = palette.textPrimary,
            fontSize = 15.sp,
            fontWeight = FontWeight.SemiBold,
        )
    }
}
