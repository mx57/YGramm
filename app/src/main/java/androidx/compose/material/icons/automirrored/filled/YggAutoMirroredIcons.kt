// File: app/src/main/java/androidx/compose/material/icons/automirrored/filled/YggAutoMirroredIcons.kt
package androidx.compose.material.icons.automirrored.filled

import androidx.compose.material.icons.Icons
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.path
import androidx.compose.ui.unit.dp

val Icons.AutoMirrored.Filled.Chat: ImageVector by lazy {
    ImageVector.Builder(
        name = "AutoMirrored.Filled.Chat",
        defaultWidth = 24.dp,
        defaultHeight = 24.dp,
        viewportWidth = 24f,
        viewportHeight = 24f,
        autoMirror = true
    ).apply {
        path(fill = SolidColor(Color.White)) {
            moveTo(20f, 2f)
            horizontalLineTo(4f)
            curveTo(2.9f, 2f, 2.01f, 2.9f, 2.01f, 4f)
            lineTo(2f, 22f)
            lineTo(6f, 18f)
            horizontalLineTo(20f)
            curveTo(21.1f, 18f, 22f, 17.1f, 22f, 16f)
            verticalLineTo(4f)
            curveTo(22f, 2.9f, 21.1f, 2f, 20f, 2f)
            close()
            moveTo(6f, 9f)
            horizontalLineTo(18f)
            verticalLineTo(11f)
            horizontalLineTo(6f)
            close()
            moveTo(14f, 14f)
            horizontalLineTo(6f)
            verticalLineTo(12f)
            horizontalLineTo(14f)
            close()
            moveTo(18f, 8f)
            horizontalLineTo(6f)
            verticalLineTo(6f)
            horizontalLineTo(18f)
            close()
        }
    }.build()
}
