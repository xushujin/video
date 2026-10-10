package com.azhan.qingying.ui.neu

import android.graphics.Paint
import android.graphics.Path
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.azhan.qingying.ui.theme.LocalNeuPalette
import com.azhan.qingying.ui.theme.NeuPalette

/**
 * 拟态（Neumorphism）绘制。
 *
 * 原理：元素与背景同色，靠两个方向的柔和阴影塑造体积 ——
 * 右下投 [NeuPalette.shadow]，左上投 [NeuPalette.highlight]。
 *
 * 实现用 `Paint.setShadowLayer` + 透明填充色，让矩形本体不显示、只留阴影。
 * 注意：硬件加速下 setShadowLayer 对非文本绘制从 **API 28** 起才生效，
 * 更低的系统上会退化成扁平卡片（功能不受影响）。
 */

private const val OUTER_DARK_ALPHA = 0.62f
private const val OUTER_LIGHT_ALPHA = 0.95f
private const val INNER_DARK_ALPHA = 0.55f
private const val INNER_LIGHT_ALPHA = 0.90f

private fun DrawScope.drawOuterNeumorphic(
    palette: NeuPalette,
    cornerRadiusPx: Float,
    elevationPx: Float,
) {
    if (size.width <= 0f || size.height <= 0f) return
    val blur = elevationPx * 2.0f
    val offset = elevationPx * 0.55f

    drawIntoCanvas { canvas ->
        val native = canvas.nativeCanvas
        val paint = Paint().apply {
            isAntiAlias = true
            color = android.graphics.Color.TRANSPARENT
        }

        paint.setShadowLayer(
            blur, offset, offset,
            palette.shadow.copy(alpha = OUTER_DARK_ALPHA).toArgb(),
        )
        native.drawRoundRect(0f, 0f, size.width, size.height, cornerRadiusPx, cornerRadiusPx, paint)

        paint.setShadowLayer(
            blur, -offset, -offset,
            palette.highlight.copy(alpha = OUTER_LIGHT_ALPHA).toArgb(),
        )
        native.drawRoundRect(0f, 0f, size.width, size.height, cornerRadiusPx, cornerRadiusPx, paint)
    }
}

/**
 * 内阴影（凹槽）。做法是把「内缩一圈的圆角矩形」的阴影裁剪到边缘环里，
 * 这样阴影只落在边框内侧，看起来就是凹进去的。
 */
private fun DrawScope.drawInnerNeumorphic(
    palette: NeuPalette,
    cornerRadiusPx: Float,
    depthPx: Float,
) {
    if (size.width <= 0f || size.height <= 0f) return
    val blur = depthPx * 1.6f
    val offset = depthPx * 0.8f

    drawIntoCanvas { canvas ->
        val native = canvas.nativeCanvas
        val paint = Paint().apply {
            isAntiAlias = true
            color = android.graphics.Color.TRANSPARENT
        }

        val outer = Path().apply {
            addRoundRect(
                0f, 0f, size.width, size.height,
                cornerRadiusPx, cornerRadiusPx, Path.Direction.CW,
            )
        }
        val inner = Path().apply {
            addRoundRect(
                offset, offset, size.width - offset, size.height - offset,
                cornerRadiusPx, cornerRadiusPx, Path.Direction.CW,
            )
        }
        val ring = Path().apply { op(outer, inner, Path.Op.DIFFERENCE) }

        native.save()
        native.clipPath(ring)

        paint.setShadowLayer(
            blur, offset, offset,
            palette.shadow.copy(alpha = INNER_DARK_ALPHA).toArgb(),
        )
        native.drawPath(inner, paint)

        paint.setShadowLayer(
            blur, -offset, -offset,
            palette.highlight.copy(alpha = INNER_LIGHT_ALPHA).toArgb(),
        )
        native.drawPath(inner, paint)

        native.restore()
    }
}

/** 外凸阴影。必须画在背景色之下。 */
fun Modifier.neuOuter(
    cornerRadius: Dp,
    palette: NeuPalette,
    elevation: Dp,
): Modifier = drawBehind {
    drawOuterNeumorphic(palette, cornerRadius.toPx(), elevation.toPx())
}

/** 内凹阴影。必须画在背景色之上。 */
fun Modifier.neuInner(
    cornerRadius: Dp,
    palette: NeuPalette,
    depth: Dp,
): Modifier = drawBehind {
    drawInnerNeumorphic(palette, cornerRadius.toPx(), depth.toPx())
}

// ------------------------------------------------------------------ 组件

/**
 * 拟态容器。
 *
 * 它自带 [elevation] 的外边距 —— 拟态阴影需要溢出的空间，这是这套视觉风格的
 * 固有代价：元素的占位比视觉面积大一圈。调用方不必再手动留白。
 */
@Composable
fun NeuSurface(
    modifier: Modifier = Modifier,
    cornerRadius: Dp = 20.dp,
    elevation: Dp = 8.dp,
    pressed: Boolean = false,
    onClick: (() -> Unit)? = null,
    contentAlignment: Alignment = Alignment.TopStart,
    content: @Composable BoxScope.() -> Unit,
) {
    val palette = LocalNeuPalette.current
    val shape = RoundedCornerShape(cornerRadius)
    val interaction = remember { MutableInteractionSource() }

    Box(
        modifier = modifier
            .padding(elevation)
            .then(
                if (pressed) {
                    Modifier
                        .clip(shape)
                        .background(palette.surface)
                        .neuInner(cornerRadius, palette, elevation * 0.5f)
                } else {
                    Modifier
                        .neuOuter(cornerRadius, palette, elevation)
                        .clip(shape)
                        .background(palette.surface)
                }
            )
            .then(
                if (onClick != null) {
                    Modifier.clickable(
                        interactionSource = interaction,
                        indication = null,
                        onClick = onClick,
                    )
                } else {
                    Modifier
                }
            ),
        contentAlignment = contentAlignment,
        content = content,
    )
}

/** 拟态按钮：按下时凹陷，用阴影方向反转 + 内阴影实现。 */
@Composable
fun NeuButton(
    modifier: Modifier = Modifier,
    cornerRadius: Dp = 18.dp,
    elevation: Dp = 6.dp,
    highlighted: Boolean = false,
    enabled: Boolean = true,
    onClick: () -> Unit,
    content: @Composable RowScope.() -> Unit,
) {
    val palette = LocalNeuPalette.current
    val shape = RoundedCornerShape(cornerRadius)
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    val background = if (highlighted) palette.accent else palette.surface

    Row(
        modifier = modifier
            .padding(elevation)
            .then(
                if (pressed) {
                    Modifier
                        .clip(shape)
                        .background(background)
                        .neuInner(cornerRadius, palette, elevation * 0.55f)
                } else {
                    Modifier
                        .neuOuter(cornerRadius, palette, elevation)
                        .clip(shape)
                        .background(background)
                }
            )
            .clickable(
                interactionSource = interaction,
                indication = null,
                enabled = enabled,
                onClick = onClick,
            )
            .padding(horizontal = 16.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.Center,
        content = content,
    )
}

/** 圆形拟态图标按钮。 */
@Composable
fun NeuIconButton(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    size: Dp = 44.dp,
    elevation: Dp = 6.dp,
    enabled: Boolean = true,
    content: @Composable () -> Unit,
) {
    val palette = LocalNeuPalette.current
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()

    Box(
        modifier = modifier
            .padding(elevation)
            .then(
                if (pressed) {
                    Modifier
                        .clip(CircleShape)
                        .background(palette.surface)
                        .neuInner(size / 2, palette, elevation * 0.5f)
                } else {
                    Modifier
                        .neuOuter(size / 2, palette, elevation)
                        .clip(CircleShape)
                        .background(palette.surface)
                }
            )
            .size(size)
            .clickable(
                interactionSource = interaction,
                indication = null,
                enabled = enabled,
                onClick = onClick,
            ),
        contentAlignment = Alignment.Center,
        content = { content() },
    )
}

/** 分段选择：凹槽轨道 + 凸起选中块。用于主题、倒计时动作这类互斥选项。 */
@Composable
fun <T> NeuSegmented(
    options: List<T>,
    selected: T,
    onSelect: (T) -> Unit,
    modifier: Modifier = Modifier,
    cornerRadius: Dp = 12.dp,
    label: (T) -> String,
) {
    val palette = LocalNeuPalette.current
    val trackShape = RoundedCornerShape(cornerRadius + 6.dp)

    Row(
        modifier = modifier
            .padding(6.dp)
            .background(palette.surface, trackShape)
            .neuInner(cornerRadius + 6.dp, palette, 3.dp)
            .padding(5.dp),
        horizontalArrangement = Arrangement.spacedBy(5.dp),
    ) {
        options.forEach { option ->
            val active = option == selected
            Box(
                modifier = Modifier
                    .weight(1f)
                    .then(
                        if (active) {
                            Modifier
                                .neuOuter(cornerRadius, palette, 4.dp)
                                .background(palette.surface, RoundedCornerShape(cornerRadius))
                        } else {
                            Modifier
                        }
                    )
                    .clickable(
                        interactionSource = remember { MutableInteractionSource() },
                        indication = null,
                    ) { onSelect(option) }
                    .padding(vertical = 9.dp),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    text = label(option),
                    color = if (active) palette.accent else palette.textSecondary,
                    fontSize = 13.sp,
                    fontWeight = if (active) FontWeight.SemiBold else FontWeight.Normal,
                )
            }
        }
    }
}

/** 拟态开关。 */
@Composable
fun NeuSwitch(
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
) {
    val palette = LocalNeuPalette.current
    val trackWidth = 52.dp
    val trackHeight = 30.dp
    val thumbSize = 24.dp

    val thumbOffset by animateDpAsState(
        targetValue = if (checked) trackWidth - thumbSize - 6.dp else 0.dp,
        animationSpec = tween(durationMillis = 180),
        label = "neuSwitchThumb",
    )

    Box(
        modifier = modifier
            .size(trackWidth, trackHeight)
            .background(palette.surface, RoundedCornerShape(trackHeight / 2))
            .neuInner(trackHeight / 2, palette, 3.dp)
            .clickable(enabled = enabled) { onCheckedChange(!checked) }
            .padding(3.dp),
        contentAlignment = Alignment.CenterStart,
    ) {
        Box(
            modifier = Modifier
                .offset(x = thumbOffset)
                .size(thumbSize)
                .neuOuter(thumbSize / 2, palette, 3.dp)
                .background(
                    color = if (checked) palette.accent else palette.surface,
                    shape = CircleShape,
                ),
        )
    }
}

/** 细进度条。轨道用凹陷感的分隔色，填充用强调色。 */
@Composable
fun NeuProgress(
    progress: Float,
    modifier: Modifier = Modifier,
    height: Dp = 5.dp,
) {
    val palette = LocalNeuPalette.current
    val shape = RoundedCornerShape(height / 2)
    Box(
        modifier = modifier
            .fillMaxWidth()
            .height(height)
            .background(palette.divider, shape),
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth(progress.coerceIn(0f, 1f))
                .fillMaxHeight()
                .background(palette.accent, shape),
        )
    }
}

/**
 * 滑块。用 Material3 的 Slider 换色，不做拟态化 —— 拖拽手感比造型重要。
 *
 * [onValueChangeFinished] 必须接：没有它，调用方无从知道「手指抬起来了」，
 * 就没法把「拖动中」的临时值提交出去 —— 表现就是拖了半天松开毫无反应。
 */
@Composable
fun NeuSlider(
    value: Float,
    onValueChange: (Float) -> Unit,
    valueRange: ClosedFloatingPointRange<Float>,
    modifier: Modifier = Modifier,
    steps: Int = 0,
    onValueChangeFinished: (() -> Unit)? = null,
) {
    val palette = LocalNeuPalette.current
    Slider(
        value = value,
        onValueChange = onValueChange,
        valueRange = valueRange,
        steps = steps,
        modifier = modifier,
        onValueChangeFinished = onValueChangeFinished,
        colors = SliderDefaults.colors(
            thumbColor = palette.accent,
            activeTrackColor = palette.accent,
            inactiveTrackColor = palette.divider,
        ),
    )
}
