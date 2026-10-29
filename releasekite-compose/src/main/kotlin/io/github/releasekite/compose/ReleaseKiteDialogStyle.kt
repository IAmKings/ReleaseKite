package io.github.releasekite.compose

import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.unit.dp

/**
 * 更新弹窗外观（宿主可覆盖形状/底色做品牌化）。
 *
 * 默认与契约一致：24dp 圆角、沿用 MaterialTheme 的浮层底色（containerColor = null）。
 * 注意 [containerColor] 为 null 时表示「不覆盖」，由 Material3 的 AlertDialog 默认令牌决定，
 * 这样宿主切深浅色时无需自己算颜色。
 */
data class ReleaseKiteDialogStyle(
    val shape: Shape = RoundedCornerShape(24.dp),
    val containerColor: Color? = null,
)
