package com.azhan.qingying.ui.player

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
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
import androidx.compose.material.icons.rounded.Audiotrack
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.media3.common.Format
import com.azhan.qingying.player.PlaybackController
import com.azhan.qingying.ui.neu.NeuSurface
import com.azhan.qingying.ui.theme.LocalNeuPalette

/**
 * 音轨选择面板。
 *
 * 多音轨常见于 MKV（国配 / 原声 / 导演评论）和部分 AVI。
 * 只列出音频轨；视频轨和字幕轨这里不碰。
 */
@Composable
fun AudioTrackPanel(
    player: PlaybackController,
    onClose: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val palette = LocalNeuPalette.current
    val tracks = player.audioGroups.flatMap { group ->
        (0 until group.length).map { index -> group to index }
    }

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
                    imageVector = Icons.Rounded.Audiotrack,
                    contentDescription = null,
                    tint = palette.accent,
                    modifier = Modifier.size(18.dp),
                )
                Spacer(Modifier.width(8.dp))
                Text(
                    text = "音轨",
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

            if (tracks.isEmpty()) {
                Text(
                    text = "这个文件没有可切换的音轨",
                    color = palette.textSecondary,
                    fontSize = 12.sp,
                )
                return@Column
            }

            // 手动指定过音轨时，「自动」就不再是高亮项
            AudioTrackRow(
                label = "自动（默认）",
                detail = "交给播放器按文件默认顺序选择",
                selected = !player.audioTrackOverridden,
                onClick = {
                    player.selectAutomaticAudioTrack()
                    onClose()
                },
            )

            tracks.forEachIndexed { flatIndex, (group, index) ->
                val format = group.getTrackFormat(index)
                Spacer(Modifier.height(6.dp))
                AudioTrackRow(
                    label = trackLabel(format, flatIndex),
                    detail = trackDetail(format),
                    selected = group.isTrackSelected(index),
                    onClick = {
                        player.selectAudioTrack(group, index)
                        onClose()
                    },
                )
            }
        }
    }
}

@Composable
private fun AudioTrackRow(
    label: String,
    detail: String,
    selected: Boolean,
    onClick: () -> Unit,
) {
    val palette = LocalNeuPalette.current
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(10.dp))
            .background(if (selected) palette.accent.copy(alpha = 0.14f) else palette.surface)
            .clickable(onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Icon(
            imageVector = if (selected) Icons.Rounded.Check else Icons.Rounded.Audiotrack,
            contentDescription = null,
            tint = if (selected) palette.accent else palette.textSecondary,
            modifier = Modifier.size(16.dp),
        )
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = label,
                color = palette.textPrimary,
                fontSize = 13.sp,
                fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            if (detail.isNotBlank()) {
                Text(
                    text = detail,
                    color = palette.textSecondary,
                    fontSize = 11.sp,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
        if (selected) {
            Text("当前", color = palette.accent, fontSize = 11.sp)
        }
    }
}

/** 优先用轨道自带的 label，其次语言码，最后退回序号。 */
private fun trackLabel(format: Format, index: Int): String {
    val label = format.label?.trim().orEmpty()
    if (label.isNotEmpty()) return label
    val language = format.language?.trim().orEmpty()
    if (language.isNotEmpty() && language != "und") return language
    return "音轨 ${index + 1}"
}

/** 编码 + 声道数，够判断选哪条了。 */
private fun trackDetail(format: Format): String = buildString {
    val mime = format.sampleMimeType
    if (!mime.isNullOrBlank()) {
        append(mime.substringAfter('/').uppercase())
    }
    if (format.channelCount > 0) {
        if (isNotEmpty()) append(" · ")
        append("${format.channelCount} 声道")
    }
    if (format.bitrate > 0) {
        if (isNotEmpty()) append(" · ")
        append("${format.bitrate / 1000} kbps")
    }
}
