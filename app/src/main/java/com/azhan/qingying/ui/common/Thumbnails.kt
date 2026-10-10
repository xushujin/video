package com.azhan.qingying.ui.common

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Movie
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.produceState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.azhan.qingying.data.ThumbnailLoader
import com.azhan.qingying.ui.theme.LocalNeuPalette

/**
 * 异步取缩略图。用 `produceState` 而不是引入图片加载库 ——
 * 缩略图只有一种来源（本地 content URI），Coil/Glide 的抽象层在这里是纯负担。
 */
@Composable
fun rememberThumbnail(
    loader: ThumbnailLoader,
    uri: String,
    widthPx: Int = 320,
    heightPx: Int = 180,
): ImageBitmap? {
    val state = produceState<ImageBitmap?>(initialValue = null, uri, widthPx, heightPx) {
        value = loader.load(uri, widthPx, heightPx)?.asImageBitmap()
    }
    return state.value
}

/** 16:9 缩略图占位块。取不到帧时退化成图标底，不留空白。 */
@Composable
fun VideoThumbnail(
    loader: ThumbnailLoader,
    uri: String,
    modifier: Modifier = Modifier,
    cornerRadius: androidx.compose.ui.unit.Dp = 10.dp,
    badge: String? = null,
) {
    val palette = LocalNeuPalette.current
    val bitmap = rememberThumbnail(loader, uri)

    Box(
        modifier = modifier
            .aspectRatio(16f / 9f)
            .clip(RoundedCornerShape(cornerRadius))
            .background(palette.divider),
        contentAlignment = Alignment.Center,
    ) {
        if (bitmap != null) {
            androidx.compose.foundation.Image(
                bitmap = bitmap,
                contentDescription = null,
                modifier = Modifier.fillMaxSize(),
                contentScale = ContentScale.Crop,
            )
        } else {
            Icon(
                imageVector = Icons.Rounded.Movie,
                contentDescription = null,
                tint = palette.textSecondary,
                modifier = Modifier.fillMaxSize(0.34f),
            )
        }

        if (badge != null) {
            Box(
                modifier = Modifier
                    .align(Alignment.BottomEnd)
                    .padding(4.dp)
                    .clip(RoundedCornerShape(4.dp))
                    .background(palette.background.copy(alpha = 0.78f))
                    .padding(horizontal = 4.dp, vertical = 1.dp),
            ) {
                Text(
                    text = badge,
                    color = palette.textPrimary,
                    fontSize = 10.sp,
                )
            }
        }
    }
}
