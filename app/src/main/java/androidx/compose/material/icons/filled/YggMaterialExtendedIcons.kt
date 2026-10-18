// File: app/src/main/java/androidx/compose/material/icons/filled/YggMaterialExtendedIcons.kt
package androidx.compose.material.icons.filled

import androidx.compose.material.icons.Icons
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.path
import androidx.compose.ui.unit.dp

private fun yggVector(name: String, block: androidx.compose.ui.graphics.vector.ImageVector.Builder.() -> Unit): ImageVector {
    return ImageVector.Builder(
        name = name,
        defaultWidth = 24.dp,
        defaultHeight = 24.dp,
        viewportWidth = 24f,
        viewportHeight = 24f
    ).apply(block).build()
}

private fun androidx.compose.ui.graphics.vector.ImageVector.Builder.solidPath(
    pathBuilder: androidx.compose.ui.graphics.vector.PathBuilder.() -> Unit
) {
    path(fill = SolidColor(Color.White), pathBuilder = pathBuilder)
}

val Icons.Filled.Shield: ImageVector by lazy {
    yggVector("Shield") {
        solidPath {
            moveTo(12f, 2f)
            lineTo(4f, 5f)
            verticalLineTo(11.09f)
            curveTo(4f, 16.14f, 7.41f, 20.85f, 12f, 22f)
            curveTo(16.59f, 20.85f, 20f, 16.14f, 20f, 11.09f)
            verticalLineTo(5f)
            close()
        }
    }
}

val Icons.Filled.Security: ImageVector by lazy {
    yggVector("Security") {
        solidPath {
            moveTo(12f, 1f)
            lineTo(3f, 5f)
            verticalLineTo(11f)
            curveTo(3f, 16.55f, 6.84f, 21.74f, 12f, 23f)
            curveTo(17.16f, 21.74f, 21f, 16.55f, 21f, 11f)
            verticalLineTo(5f)
            close()
            moveTo(12f, 11.99f)
            horizontalLineTo(19f)
            curveTo(18.47f, 16.11f, 15.72f, 19.78f, 12f, 20.93f)
            verticalLineTo(12f)
            horizontalLineTo(5f)
            verticalLineTo(6.3f)
            lineTo(12f, 3.19f)
            close()
        }
    }
}

val Icons.Filled.VerifiedUser: ImageVector by lazy {
    yggVector("VerifiedUser") {
        solidPath {
            moveTo(12f, 1f)
            lineTo(3f, 5f)
            verticalLineTo(11f)
            curveTo(3f, 16.55f, 6.84f, 21.74f, 12f, 23f)
            curveTo(17.16f, 21.74f, 21f, 16.55f, 21f, 11f)
            verticalLineTo(5f)
            close()
            moveTo(10f, 17f)
            lineTo(6f, 13f)
            lineTo(7.41f, 11.59f)
            lineTo(10f, 14.17f)
            lineTo(16.59f, 7.58f)
            lineTo(18f, 9f)
            close()
        }
    }
}

val Icons.Filled.Hub: ImageVector by lazy {
    yggVector("Hub") {
        solidPath {
            moveTo(17f, 16f)
            lineTo(13f, 12f)
            verticalLineTo(8.82f)
            curveTo(14.16f, 8.4f, 15f, 7.3f, 15f, 6f)
            curveTo(15f, 4.34f, 13.66f, 3f, 12f, 3f)
            curveTo(10.34f, 3f, 9f, 4.34f, 9f, 6f)
            curveTo(9f, 7.3f, 9.84f, 8.4f, 11f, 8.82f)
            verticalLineTo(12f)
            lineTo(7f, 16f)
            horizontalLineTo(3f)
            verticalLineTo(21f)
            horizontalLineTo(8f)
            verticalLineTo(17.95f)
            lineTo(12f, 13.95f)
            lineTo(16f, 17.95f)
            verticalLineTo(21f)
            horizontalLineTo(21f)
            verticalLineTo(16f)
            close()
        }
    }
}

val Icons.Filled.Key: ImageVector by lazy {
    yggVector("Key") {
        solidPath {
            moveTo(12.65f, 10f)
            curveTo(11.83f, 7.67f, 9.61f, 6f, 7f, 6f)
            curveTo(3.69f, 6f, 1f, 8.69f, 1f, 12f)
            curveTo(1f, 15.31f, 3.69f, 18f, 7f, 18f)
            curveTo(9.61f, 18f, 11.83f, 16.33f, 12.65f, 14f)
            horizontalLineTo(17f)
            verticalLineTo(18f)
            horizontalLineTo(21f)
            verticalLineTo(14f)
            horizontalLineTo(23f)
            verticalLineTo(10f)
            close()
            moveTo(7f, 14f)
            curveTo(5.9f, 14f, 5f, 13.1f, 5f, 12f)
            curveTo(5f, 10.9f, 5.9f, 10f, 7f, 10f)
            curveTo(8.1f, 10f, 9f, 10.9f, 9f, 12f)
            curveTo(9f, 13.1f, 8.1f, 14f, 7f, 14f)
            close()
        }
    }
}

val Icons.Filled.Pause: ImageVector by lazy {
    yggVector("Pause") {
        solidPath {
            moveTo(6f, 19f)
            horizontalLineTo(10f)
            verticalLineTo(5f)
            horizontalLineTo(6f)
            close()
            moveTo(14f, 5f)
            verticalLineTo(19f)
            horizontalLineTo(18f)
            verticalLineTo(5f)
            close()
        }
    }
}

val Icons.Filled.Groups: ImageVector by lazy {
    yggVector("Groups") {
        solidPath {
            moveTo(16f, 11f)
            curveTo(17.66f, 11f, 18.99f, 9.66f, 18.99f, 8f)
            curveTo(18.99f, 6.34f, 17.66f, 5f, 16f, 5f)
            curveTo(14.34f, 5f, 13f, 6.34f, 13f, 8f)
            curveTo(13f, 9.66f, 14.34f, 11f, 16f, 11f)
            close()
            moveTo(8f, 11f)
            curveTo(9.66f, 11f, 10.99f, 9.66f, 10.99f, 8f)
            curveTo(10.99f, 6.34f, 9.66f, 5f, 8f, 5f)
            curveTo(6.34f, 5f, 5f, 6.34f, 5f, 8f)
            curveTo(5f, 9.66f, 6.34f, 11f, 8f, 11f)
            close()
            moveTo(8f, 13f)
            curveTo(5.67f, 13f, 1f, 14.17f, 1f, 16.5f)
            verticalLineTo(19f)
            horizontalLineTo(15f)
            verticalLineTo(16.5f)
            curveTo(15f, 14.17f, 10.33f, 13f, 8f, 13f)
            close()
            moveTo(16f, 13f)
            curveTo(15.71f, 13f, 15.38f, 13.02f, 15.03f, 13.05f)
            curveTo(16.19f, 13.89f, 17f, 15.02f, 17f, 16.5f)
            verticalLineTo(19f)
            horizontalLineTo(23f)
            verticalLineTo(16.5f)
            curveTo(23f, 14.17f, 18.33f, 13f, 16f, 13f)
            close()
        }
    }
}

val Icons.Filled.AttachFile: ImageVector by lazy {
    yggVector("AttachFile") {
        solidPath {
            moveTo(16.5f, 6f)
            verticalLineTo(17.5f)
            curveTo(16.5f, 19.71f, 14.71f, 21.5f, 12.5f, 21.5f)
            curveTo(10.29f, 21.5f, 8.5f, 19.71f, 8.5f, 17.5f)
            verticalLineTo(5f)
            curveTo(8.5f, 3.62f, 9.62f, 2.5f, 11f, 2.5f)
            curveTo(12.38f, 2.5f, 13.5f, 3.62f, 13.5f, 5f)
            verticalLineTo(15.5f)
            curveTo(13.5f, 16.05f, 13.05f, 16.5f, 12.5f, 16.5f)
            curveTo(11.95f, 16.5f, 11.5f, 16.05f, 11.5f, 15.5f)
            verticalLineTo(6f)
            horizontalLineTo(10f)
            verticalLineTo(15.5f)
            curveTo(10f, 16.88f, 11.12f, 18f, 12.5f, 18f)
            curveTo(13.88f, 18f, 15f, 16.88f, 15f, 15.5f)
            verticalLineTo(5f)
            curveTo(15f, 2.79f, 13.21f, 1f, 11f, 1f)
            curveTo(8.79f, 1f, 7f, 2.79f, 7f, 5f)
            verticalLineTo(17.5f)
            curveTo(7f, 20.54f, 9.46f, 23f, 12.5f, 23f)
            curveTo(15.54f, 23f, 18f, 20.54f, 18f, 17.5f)
            verticalLineTo(6f)
            close()
        }
    }
}

val Icons.Filled.FolderZip: ImageVector by lazy {
    yggVector("FolderZip") {
        solidPath {
            moveTo(20f, 6f)
            horizontalLineTo(12f)
            lineTo(10f, 4f)
            horizontalLineTo(4f)
            curveTo(2.9f, 4f, 2.01f, 4.9f, 2.01f, 6f)
            lineTo(2f, 18f)
            curveTo(2f, 19.1f, 2.9f, 20f, 4f, 20f)
            horizontalLineTo(20f)
            curveTo(21.1f, 20f, 22f, 19.1f, 22f, 18f)
            verticalLineTo(8f)
            curveTo(22f, 6.9f, 21.1f, 6f, 20f, 6f)
            close()
            moveTo(18f, 12f)
            horizontalLineTo(16f)
            verticalLineTo(14f)
            horizontalLineTo(18f)
            verticalLineTo(16f)
            horizontalLineTo(16f)
            verticalLineTo(18f)
            horizontalLineTo(14f)
            verticalLineTo(16f)
            horizontalLineTo(16f)
            verticalLineTo(14f)
            horizontalLineTo(14f)
            verticalLineTo(12f)
            horizontalLineTo(16f)
            verticalLineTo(10f)
            horizontalLineTo(14f)
            verticalLineTo(8f)
            horizontalLineTo(16f)
            verticalLineTo(10f)
            horizontalLineTo(18f)
            close()
        }
    }
}

val Icons.Filled.Visibility: ImageVector by lazy {
    yggVector("Visibility") {
        solidPath {
            moveTo(12f, 4.5f)
            curveTo(7f, 4.5f, 2.73f, 7.61f, 1f, 12f)
            curveTo(2.73f, 16.39f, 7f, 19.5f, 12f, 19.5f)
            curveTo(17f, 19.5f, 21.27f, 16.39f, 23f, 12f)
            curveTo(21.27f, 7.61f, 17f, 4.5f, 12f, 4.5f)
            close()
            moveTo(12f, 17f)
            curveTo(9.24f, 17f, 7f, 14.76f, 7f, 12f)
            curveTo(7f, 9.24f, 9.24f, 7f, 12f, 7f)
            curveTo(14.76f, 7f, 17f, 9.24f, 17f, 12f)
            curveTo(17f, 14.76f, 14.76f, 17f, 12f, 17f)
            close()
        }
    }
}

val Icons.Filled.VisibilityOff: ImageVector by lazy {
    yggVector("VisibilityOff") {
        solidPath {
            moveTo(12f, 7f)
            curveTo(14.76f, 7f, 17f, 9.24f, 17f, 12f)
            curveTo(17f, 12.65f, 16.87f, 13.26f, 16.64f, 13.83f)
            lineTo(19.56f, 16.75f)
            curveTo(21.07f, 15.49f, 22.26f, 13.86f, 22.99f, 12f)
            curveTo(21.26f, 7.61f, 16.99f, 4.5f, 11.99f, 4.5f)
            curveTo(10.59f, 4.5f, 9.25f, 4.75f, 8.01f, 5.2f)
            lineTo(10.17f, 7.36f)
            curveTo(10.74f, 7.13f, 11.35f, 7f, 12f, 7f)
            close()
            moveTo(2f, 4.27f)
            lineTo(4.28f, 6.55f)
            lineTo(4.74f, 7.01f)
            curveTo(3.08f, 8.3f, 1.78f, 10.02f, 1f, 12f)
            curveTo(2.73f, 16.39f, 7f, 19.5f, 12f, 19.5f)
            curveTo(13.55f, 19.5f, 15.03f, 19.2f, 16.38f, 18.66f)
            lineTo(16.8f, 19.08f)
            lineTo(19.73f, 22f)
            lineTo(21f, 20.73f)
            lineTo(3.27f, 3f)
            close()
        }
    }
}

val Icons.Filled.Schedule: ImageVector by lazy {
    yggVector("Schedule") {
        solidPath {
            moveTo(11.99f, 2f)
            curveTo(6.47f, 2f, 2f, 6.48f, 2f, 12f)
            curveTo(2f, 17.52f, 6.47f, 22f, 11.99f, 22f)
            curveTo(17.52f, 22f, 22f, 17.52f, 22f, 12f)
            curveTo(22f, 6.48f, 17.52f, 2f, 11.99f, 2f)
            close()
            moveTo(12.5f, 7f)
            verticalLineTo(12.25f)
            lineTo(17f, 14.92f)
            lineTo(16.25f, 16.15f)
            lineTo(11f, 13f)
            verticalLineTo(7f)
            close()
        }
    }
}

val Icons.Filled.DoneAll: ImageVector by lazy {
    yggVector("DoneAll") {
        solidPath {
            moveTo(18f, 7f)
            lineTo(16.59f, 5.59f)
            lineTo(10.25f, 11.93f)
            lineTo(11.66f, 13.34f)
            close()
            moveTo(22.24f, 5.59f)
            lineTo(11.66f, 16.17f)
            lineTo(7.48f, 12f)
            lineTo(6.07f, 13.41f)
            lineTo(11.66f, 19f)
            lineTo(23.66f, 7f)
            close()
            moveTo(0.41f, 13.41f)
            lineTo(6f, 19f)
            lineTo(7.41f, 17.59f)
            lineTo(1.83f, 12f)
            close()
        }
    }
}

val Icons.Filled.CloudOff: ImageVector by lazy {
    yggVector("CloudOff") {
        solidPath {
            moveTo(19.35f, 10.04f)
            curveTo(18.67f, 6.59f, 15.64f, 4f, 12f, 4f)
            curveTo(10.53f, 4f, 9.15f, 4.4f, 7.96f, 5.1f)
            lineTo(9.42f, 6.56f)
            curveTo(10.21f, 6.2f, 11.08f, 6f, 12f, 6f)
            curveTo(15.04f, 6f, 17.5f, 8.46f, 17.5f, 11.5f)
            verticalLineTo(12f)
            horizontalLineTo(19f)
            curveTo(20.66f, 12f, 22f, 13.34f, 22f, 15f)
            curveTo(22f, 16.13f, 21.36f, 17.11f, 20.44f, 17.62f)
            lineTo(21.89f, 19.07f)
            curveTo(23.16f, 18.18f, 24f, 16.69f, 24f, 15f)
            curveTo(24f, 12.36f, 21.95f, 10.22f, 19.35f, 10.04f)
            close()
            moveTo(3f, 5.27f)
            lineTo(5.75f, 8.02f)
            curveTo(2.56f, 8.15f, 0f, 10.77f, 0f, 14f)
            curveTo(0f, 17.31f, 2.69f, 20f, 6f, 20f)
            horizontalLineTo(17.73f)
            lineTo(19.73f, 22f)
            lineTo(21f, 20.73f)
            lineTo(4.27f, 4f)
            close()
        }
    }
}

val Icons.Filled.CloudQueue: ImageVector by lazy {
    yggVector("CloudQueue") {
        solidPath {
            moveTo(19.35f, 10.04f)
            curveTo(18.67f, 6.59f, 15.64f, 4f, 12f, 4f)
            curveTo(9.11f, 4f, 6.6f, 5.64f, 5.35f, 8.04f)
            curveTo(2.34f, 8.36f, 0f, 10.91f, 0f, 14f)
            curveTo(0f, 17.31f, 2.69f, 20f, 6f, 20f)
            horizontalLineTo(19f)
            curveTo(21.76f, 20f, 24f, 17.76f, 24f, 15f)
            curveTo(24f, 12.36f, 21.95f, 10.22f, 19.35f, 10.04f)
            close()
        }
    }
}

val Icons.Filled.NetworkCheck: ImageVector by lazy {
    yggVector("NetworkCheck") {
        solidPath {
            moveTo(15.9f, 5f)
            curveTo(15.73f, 5f, 15.58f, 5.09f, 15.49f, 5.23f)
            lineTo(10.95f, 13.15f)
            curveTo(10.09f, 12.92f, 9.14f, 13.27f, 8.66f, 14.07f)
            curveTo(8.11f, 15.01f, 8.43f, 16.22f, 9.38f, 16.77f)
            curveTo(10.32f, 17.31f, 11.54f, 16.99f, 12.08f, 16.05f)
            curveTo(12.55f, 15.24f, 12.43f, 14.23f, 11.86f, 13.56f)
            lineTo(16.4f, 5.65f)
            curveTo(16.59f, 5.33f, 16.27f, 5f, 15.9f, 5f)
            close()
            moveTo(1f, 9f)
            lineTo(3f, 11f)
            curveTo(5.88f, 8.12f, 9.79f, 6.92f, 13.53f, 7.38f)
            lineTo(14.72f, 5.3f)
            curveTo(9.89f, 4.28f, 4.74f, 5.65f, 1f, 9f)
            close()
        }
    }
}

val Icons.Filled.SwapHoriz: ImageVector by lazy {
    yggVector("SwapHoriz") {
        solidPath {
            moveTo(6.99f, 11f)
            lineTo(3f, 15f)
            lineTo(6.99f, 19f)
            verticalLineTo(16f)
            horizontalLineTo(14f)
            verticalLineTo(14f)
            horizontalLineTo(6.99f)
            close()
            moveTo(21f, 9f)
            lineTo(17.01f, 5f)
            verticalLineTo(8f)
            horizontalLineTo(10f)
            verticalLineTo(10f)
            horizontalLineTo(17.01f)
            verticalLineTo(13f)
            close()
        }
    }
}

val Icons.Filled.Radar: ImageVector by lazy {
    yggVector("Radar") {
        solidPath {
            moveTo(12f, 2f)
            curveTo(6.48f, 2f, 2f, 6.48f, 2f, 12f)
            curveTo(2f, 17.52f, 6.48f, 22f, 12f, 22f)
            curveTo(17.52f, 22f, 22f, 17.52f, 22f, 12f)
            curveTo(22f, 6.48f, 17.52f, 2f, 12f, 2f)
            close()
            moveTo(12f, 20f)
            curveTo(7.59f, 20f, 4f, 16.41f, 4f, 12f)
            curveTo(4f, 7.59f, 7.59f, 4f, 12f, 4f)
            curveTo(16.41f, 4f, 20f, 7.59f, 20f, 12f)
            curveTo(20f, 16.41f, 16.41f, 20f, 12f, 20f)
            close()
            moveTo(12f, 8f)
            curveTo(9.79f, 8f, 8f, 9.79f, 8f, 12f)
            curveTo(8f, 14.21f, 9.79f, 16f, 12f, 16f)
            curveTo(14.21f, 16f, 16f, 14.21f, 16f, 12f)
            curveTo(16f, 9.79f, 14.21f, 8f, 12f, 8f)
            close()
        }
    }
}

val Icons.Filled.Bolt: ImageVector by lazy {
    yggVector("Bolt") {
        solidPath {
            moveTo(11f, 21f)
            horizontalLineTo(10f)
            lineTo(11f, 14f)
            horizontalLineTo(7.5f)
            curveTo(6.92f, 14f, 6.93f, 13.68f, 7.12f, 13.34f)
            lineTo(13f, 3f)
            horizontalLineTo(14f)
            lineTo(13f, 10f)
            horizontalLineTo(16.5f)
            curveTo(16.99f, 10f, 17.06f, 10.33f, 16.97f, 10.51f)
            close()
        }
    }
}

val Icons.Filled.Public: ImageVector by lazy {
    yggVector("Public") {
        solidPath {
            moveTo(12f, 2f)
            curveTo(6.48f, 2f, 2f, 6.48f, 2f, 12f)
            curveTo(2f, 17.52f, 6.48f, 22f, 12f, 22f)
            curveTo(17.52f, 22f, 22f, 17.52f, 22f, 12f)
            curveTo(22f, 6.48f, 17.52f, 2f, 12f, 2f)
            close()
            moveTo(11f, 19.93f)
            curveTo(7.05f, 19.44f, 4f, 16.08f, 4f, 12f)
            curveTo(4f, 11.38f, 4.08f, 10.79f, 4.21f, 10.21f)
            lineTo(9f, 15f)
            verticalLineTo(16f)
            curveTo(9f, 17.1f, 9.9f, 18f, 11f, 18f)
            close()
        }
    }
}

val Icons.Filled.DeleteOutline: ImageVector by lazy {
    yggVector("DeleteOutline") {
        solidPath {
            moveTo(6f, 19f)
            curveTo(6f, 20.1f, 6.9f, 21f, 8f, 21f)
            horizontalLineTo(16f)
            curveTo(17.1f, 21f, 18f, 20.1f, 18f, 19f)
            verticalLineTo(7f)
            horizontalLineTo(6f)
            close()
            moveTo(19f, 4f)
            horizontalLineTo(15.5f)
            lineTo(14.5f, 3f)
            horizontalLineTo(9.5f)
            lineTo(8.5f, 4f)
            horizontalLineTo(5f)
            verticalLineTo(6f)
            horizontalLineTo(19f)
            close()
        }
    }
}

val Icons.Filled.LockReset: ImageVector by lazy {
    yggVector("LockReset") {
        solidPath {
            moveTo(13f, 3f)
            curveTo(8.03f, 3f, 4f, 7.03f, 4f, 12f)
            horizontalLineTo(1f)
            lineTo(4.89f, 15.89f)
            lineTo(4.96f, 16.03f)
            lineTo(9f, 12f)
            horizontalLineTo(6f)
            curveTo(6f, 8.13f, 9.13f, 5f, 13f, 5f)
            curveTo(16.87f, 5f, 20f, 8.13f, 20f, 12f)
            curveTo(20f, 15.87f, 16.87f, 19f, 13f, 19f)
            curveTo(11.07f, 19f, 9.32f, 18.21f, 8.06f, 16.94f)
            lineTo(6.64f, 18.36f)
            curveTo(8.27f, 19.99f, 10.51f, 21f, 13f, 21f)
            curveTo(17.97f, 21f, 22f, 16.97f, 22f, 12f)
            curveTo(22f, 7.03f, 17.97f, 3f, 13f, 3f)
            close()
        }
    }
}

val Icons.Filled.Devices: ImageVector by lazy {
    yggVector("Devices") {
        solidPath {
            moveTo(4f, 6f)
            horizontalLineTo(22f)
            verticalLineTo(4f)
            horizontalLineTo(4f)
            curveTo(2.9f, 4f, 2f, 4.9f, 2f, 6f)
            verticalLineTo(17f)
            horizontalLineTo(0f)
            verticalLineTo(20f)
            horizontalLineTo(14f)
            verticalLineTo(17f)
            horizontalLineTo(4f)
            close()
            moveTo(23f, 8f)
            horizontalLineTo(17f)
            curveTo(16.45f, 8f, 16f, 8.45f, 16f, 9f)
            verticalLineTo(19f)
            curveTo(16f, 19.55f, 16.45f, 20f, 17f, 20f)
            horizontalLineTo(23f)
            curveTo(23.55f, 20f, 24f, 19.55f, 24f, 19f)
            verticalLineTo(9f)
            curveTo(24f, 8.45f, 23.55f, 8f, 23f, 8f)
            close()
        }
    }
}

val Icons.Filled.Sync: ImageVector by lazy {
    yggVector("Sync") {
        solidPath {
            moveTo(12f, 4f)
            verticalLineTo(1f)
            lineTo(8f, 5f)
            lineTo(12f, 9f)
            verticalLineTo(6f)
            curveTo(15.31f, 6f, 18f, 8.69f, 18f, 12f)
            curveTo(18f, 13.01f, 17.75f, 13.97f, 17.3f, 14.8f)
            lineTo(18.76f, 16.26f)
            curveTo(19.54f, 15.03f, 20f, 13.57f, 20f, 12f)
            curveTo(20f, 7.58f, 16.42f, 4f, 12f, 4f)
            close()
            moveTo(12f, 18f)
            curveTo(8.69f, 18f, 6f, 15.31f, 6f, 12f)
            curveTo(6f, 10.99f, 6.25f, 10.03f, 6.7f, 9.2f)
            lineTo(5.24f, 7.74f)
            curveTo(4.46f, 8.97f, 4f, 10.43f, 4f, 12f)
            curveTo(4f, 16.42f, 7.58f, 20f, 12f, 20f)
            verticalLineTo(23f)
            lineTo(16f, 19f)
            lineTo(12f, 15f)
            close()
        }
    }
}

val Icons.Filled.Backup: ImageVector by lazy {
    yggVector("Backup") {
        solidPath {
            moveTo(19.35f, 10.04f)
            curveTo(18.67f, 6.59f, 15.64f, 4f, 12f, 4f)
            curveTo(9.11f, 4f, 6.6f, 5.64f, 5.35f, 8.04f)
            curveTo(2.34f, 8.36f, 0f, 10.91f, 0f, 14f)
            curveTo(0f, 17.31f, 2.69f, 20f, 6f, 20f)
            horizontalLineTo(19f)
            curveTo(21.76f, 20f, 24f, 17.76f, 24f, 15f)
            curveTo(24f, 12.36f, 21.95f, 10.22f, 19.35f, 10.04f)
            close()
            moveTo(14f, 13f)
            verticalLineTo(17f)
            horizontalLineTo(10f)
            verticalLineTo(13f)
            horizontalLineTo(7f)
            lineTo(12f, 8f)
            lineTo(17f, 13f)
            close()
        }
    }
}

val Icons.Filled.Storage: ImageVector by lazy {
    yggVector("Storage") {
        solidPath {
            moveTo(2f, 20f)
            horizontalLineTo(22f)
            verticalLineTo(16f)
            horizontalLineTo(2f)
            close()
            moveTo(2f, 4f)
            verticalLineTo(8f)
            horizontalLineTo(22f)
            verticalLineTo(4f)
            close()
            moveTo(2f, 14f)
            horizontalLineTo(22f)
            verticalLineTo(10f)
            horizontalLineTo(2f)
            close()
        }
    }
}

val Icons.Filled.CloudUpload: ImageVector by lazy {
    Icons.Filled.Backup
}

val Icons.Filled.PhoneAndroid: ImageVector by lazy {
    yggVector("PhoneAndroid") {
        solidPath {
            moveTo(16f, 1f)
            horizontalLineTo(8f)
            curveTo(6.34f, 1f, 5f, 2.34f, 5f, 4f)
            verticalLineTo(20f)
            curveTo(5f, 21.66f, 6.34f, 23f, 8f, 23f)
            horizontalLineTo(16f)
            curveTo(17.66f, 23f, 19f, 21.66f, 19f, 20f)
            verticalLineTo(4f)
            curveTo(19f, 2.34f, 17.66f, 1f, 16f, 1f)
            close()
            moveTo(17f, 18f)
            horizontalLineTo(7f)
            verticalLineTo(4f)
            horizontalLineTo(17f)
            close()
        }
    }
}

val Icons.Filled.Computer: ImageVector by lazy {
    yggVector("Computer") {
        solidPath {
            moveTo(20f, 18f)
            curveTo(21.1f, 18f, 21.99f, 17.1f, 21.99f, 16f)
            lineTo(22f, 6f)
            curveTo(22f, 4.9f, 21.1f, 4f, 20f, 4f)
            horizontalLineTo(4f)
            curveTo(2.9f, 4f, 2f, 4.9f, 2f, 6f)
            verticalLineTo(16f)
            curveTo(2f, 17.1f, 2.9f, 18f, 4f, 18f)
            horizontalLineTo(0f)
            verticalLineTo(20f)
            horizontalLineTo(24f)
            verticalLineTo(18f)
            close()
            moveTo(4f, 6f)
            horizontalLineTo(20f)
            verticalLineTo(16f)
            horizontalLineTo(4f)
            close()
        }
    }
}

val Icons.Filled.Restore: ImageVector by lazy {
    Icons.Filled.LockReset
}

val Icons.Filled.QrCode2: ImageVector by lazy {
    yggVector("QrCode2") {
        solidPath {
            moveTo(3f, 3f)
            horizontalLineTo(9f)
            verticalLineTo(9f)
            horizontalLineTo(3f)
            close()
            moveTo(15f, 3f)
            horizontalLineTo(21f)
            verticalLineTo(9f)
            horizontalLineTo(15f)
            close()
            moveTo(3f, 15f)
            horizontalLineTo(9f)
            verticalLineTo(21f)
            horizontalLineTo(3f)
            close()
            moveTo(13f, 13f)
            horizontalLineTo(17f)
            verticalLineTo(17f)
            horizontalLineTo(13f)
            close()
            moveTo(17f, 17f)
            horizontalLineTo(21f)
            verticalLineTo(21f)
            horizontalLineTo(17f)
            close()
        }
    }
}

val Icons.Filled.PrivacyTip: ImageVector by lazy {
    Icons.Filled.Shield
}

val Icons.Filled.Code: ImageVector by lazy {
    yggVector("Code") {
        solidPath {
            moveTo(9.4f, 16.6f)
            lineTo(4.8f, 12f)
            lineTo(9.4f, 7.4f)
            lineTo(8f, 6f)
            lineTo(2f, 12f)
            lineTo(8f, 18f)
            close()
            moveTo(14.6f, 16.6f)
            lineTo(19.2f, 12f)
            lineTo(14.6f, 7.4f)
            lineTo(16f, 6f)
            lineTo(22f, 12f)
            lineTo(16f, 18f)
            close()
        }
    }
}

val Icons.Filled.ContentCopy: ImageVector by lazy {
    yggVector("ContentCopy") {
        solidPath {
            moveTo(16f, 1f)
            horizontalLineTo(4f)
            curveTo(2.9f, 1f, 2f, 1.9f, 2f, 3f)
            verticalLineTo(17f)
            horizontalLineTo(4f)
            verticalLineTo(3f)
            horizontalLineTo(16f)
            verticalLineTo(1f)
            close()
            moveTo(19f, 5f)
            horizontalLineTo(8f)
            curveTo(6.9f, 5f, 6f, 5.9f, 6f, 7f)
            verticalLineTo(21f)
            curveTo(6f, 22.1f, 6.9f, 23f, 8f, 23f)
            horizontalLineTo(19f)
            curveTo(20.1f, 23f, 21f, 22.1f, 21f, 21f)
            verticalLineTo(7f)
            curveTo(21f, 5.9f, 20.1f, 5f, 19f, 5f)
            close()
            moveTo(19f, 21f)
            horizontalLineTo(8f)
            verticalLineTo(7f)
            horizontalLineTo(19f)
            verticalLineTo(21f)
            close()
        }
    }
}

val Icons.Filled.ContentPaste: ImageVector by lazy {
    yggVector("ContentPaste") {
        solidPath {
            moveTo(19f, 2f)
            horizontalLineTo(14.82f)
            curveTo(14.4f, 0.84f, 13.3f, 0f, 12f, 0f)
            curveTo(10.7f, 0f, 9.6f, 0.84f, 9.18f, 2f)
            horizontalLineTo(5f)
            curveTo(3.9f, 2f, 3f, 2.9f, 3f, 4f)
            verticalLineTo(20f)
            curveTo(3f, 21.1f, 3.9f, 22f, 5f, 22f)
            horizontalLineTo(19f)
            curveTo(20.1f, 22f, 21f, 21.1f, 21f, 20f)
            verticalLineTo(4f)
            curveTo(21f, 2.9f, 20.1f, 2f, 19f, 2f)
            close()
            moveTo(12f, 2f)
            curveTo(12.55f, 2f, 13f, 2.45f, 13f, 3f)
            curveTo(13f, 3.55f, 12.55f, 4f, 12f, 4f)
            curveTo(11.45f, 4f, 11f, 3.55f, 11f, 3f)
            curveTo(11f, 2.45f, 11.45f, 2f, 12f, 2f)
            close()
            moveTo(19f, 20f)
            horizontalLineTo(5f)
            verticalLineTo(4f)
            horizontalLineTo(7f)
            verticalLineTo(7f)
            horizontalLineTo(17f)
            verticalLineTo(4f)
            horizontalLineTo(19f)
            verticalLineTo(20f)
            close()
        }
    }
}

val Icons.Filled.Bookmark: ImageVector by lazy {
    yggVector("Bookmark") {
        solidPath {
            moveTo(17f, 3f)
            horizontalLineTo(7f)
            curveTo(5.9f, 3f, 5.01f, 3.9f, 5.01f, 5f)
            lineTo(5f, 21f)
            lineTo(12f, 18f)
            lineTo(19f, 21f)
            verticalLineTo(5f)
            curveTo(19f, 3.9f, 18.1f, 3f, 17f, 3f)
            close()
        }
    }
}

val Icons.Filled.ExpandLess: ImageVector by lazy {
    yggVector("ExpandLess") {
        solidPath {
            moveTo(12f, 8f)
            lineTo(6f, 14f)
            lineTo(7.41f, 15.41f)
            lineTo(12f, 10.83f)
            lineTo(16.59f, 15.41f)
            lineTo(18f, 14f)
            close()
        }
    }
}

val Icons.Filled.ExpandMore: ImageVector by lazy {
    yggVector("ExpandMore") {
        solidPath {
            moveTo(16.59f, 8.59f)
            lineTo(12f, 13.17f)
            lineTo(7.41f, 8.59f)
            lineTo(6f, 10f)
            lineTo(12f, 16f)
            lineTo(18f, 10f)
            close()
        }
    }
}

val Icons.Filled.Speed: ImageVector by lazy {
    Icons.Filled.NetworkCheck
}

val Icons.Filled.Call: ImageVector by lazy {
    yggVector("Call") {
        solidPath {
            moveTo(20.01f, 15.38f)
            curveTo(18.78f, 15.38f, 17.59f, 15.18f, 16.48f, 14.82f)
            curveTo(16.13f, 14.7f, 15.74f, 14.79f, 15.47f, 15.06f)
            lineTo(13.9f, 17.03f)
            curveTo(11.07f, 15.68f, 8.42f, 13.13f, 7.01f, 10.2f)
            lineTo(8.96f, 8.54f)
            curveTo(9.23f, 8.26f, 9.31f, 7.87f, 9.2f, 7.52f)
            curveTo(8.83f, 6.41f, 8.64f, 5.22f, 8.64f, 3.99f)
            curveTo(8.64f, 3.45f, 8.19f, 3f, 7.65f, 3f)
            horizontalLineTo(4.19f)
            curveTo(3.65f, 3f, 3f, 3.24f, 3f, 3.99f)
            curveTo(3f, 13.28f, 10.73f, 21f, 20.01f, 21f)
            curveTo(20.72f, 21f, 21f, 20.37f, 21f, 19.82f)
            verticalLineTo(16.37f)
            curveTo(21f, 15.83f, 20.55f, 15.38f, 20.01f, 15.38f)
            close()
        }
    }
}

val Icons.Filled.CallEnd: ImageVector by lazy {
    yggVector("CallEnd") {
        solidPath {
            moveTo(12f, 9f)
            curveTo(10.4f, 9f, 8.85f, 9.25f, 7.4f, 9.72f)
            verticalLineTo(12.82f)
            curveTo(7.4f, 13.21f, 7.17f, 13.56f, 6.84f, 13.72f)
            curveTo(5.86f, 14.21f, 4.97f, 14.84f, 4.17f, 15.57f)
            curveTo(3.99f, 15.75f, 3.75f, 15.85f, 3.47f, 15.85f)
            curveTo(3.19f, 15.85f, 2.94f, 15.74f, 2.76f, 15.56f)
            lineTo(0.28f, 13.08f)
            curveTo(0.1f, 12.9f, 0f, 12.65f, 0f, 12.37f)
            curveTo(0f, 12.09f, 0.1f, 11.84f, 0.29f, 11.66f)
            curveTo(3.34f, 8.78f, 7.46f, 7f, 12f, 7f)
            curveTo(16.54f, 7f, 20.66f, 8.78f, 23.71f, 11.67f)
            curveTo(23.89f, 11.85f, 24f, 12.1f, 24f, 12.38f)
            curveTo(24f, 12.66f, 23.9f, 12.91f, 23.71f, 13.09f)
            lineTo(21.23f, 15.57f)
            curveTo(21.05f, 15.75f, 20.8f, 15.86f, 20.52f, 15.86f)
            curveTo(20.24f, 15.86f, 20f, 15.75f, 19.82f, 15.57f)
            curveTo(19.03f, 14.83f, 18.13f, 14.2f, 17.15f, 13.71f)
            curveTo(16.82f, 13.55f, 16.59f, 13.2f, 16.59f, 12.81f)
            verticalLineTo(9.71f)
            curveTo(15.15f, 9.25f, 13.6f, 9f, 12f, 9f)
            close()
        }
    }
}

val Icons.Filled.Mic: ImageVector by lazy {
    yggVector("Mic") {
        solidPath {
            moveTo(12f, 14f)
            curveTo(13.66f, 14f, 14.99f, 12.66f, 14.99f, 11f)
            lineTo(15f, 5f)
            curveTo(15f, 3.34f, 13.66f, 2f, 12f, 2f)
            curveTo(10.34f, 2f, 9f, 3.34f, 9f, 5f)
            verticalLineTo(11f)
            curveTo(9f, 12.66f, 10.34f, 14f, 12f, 14f)
            close()
            moveTo(17.3f, 11f)
            curveTo(17.3f, 14f, 14.76f, 16.1f, 12f, 16.1f)
            curveTo(9.24f, 16.1f, 6.7f, 14f, 6.7f, 11f)
            horizontalLineTo(5f)
            curveTo(5f, 14.41f, 7.72f, 17.23f, 11f, 17.72f)
            verticalLineTo(21f)
            horizontalLineTo(13f)
            verticalLineTo(17.72f)
            curveTo(16.28f, 17.23f, 19f, 14.41f, 19f, 11f)
            horizontalLineTo(17.3f)
            close()
        }
    }
}

val Icons.Filled.MicOff: ImageVector by lazy {
    yggVector("MicOff") {
        solidPath {
            moveTo(19f, 11f)
            horizontalLineTo(17.3f)
            curveTo(17.3f, 11.74f, 17.14f, 12.43f, 16.87f, 13.05f)
            lineTo(18.1f, 14.28f)
            curveTo(18.66f, 13.3f, 19f, 12.19f, 19f, 11f)
            close()
            moveTo(14.98f, 11.17f)
            curveTo(14.98f, 11.11f, 15f, 11.06f, 15f, 11f)
            verticalLineTo(5f)
            curveTo(15f, 3.34f, 13.66f, 2f, 12f, 2f)
            curveTo(10.34f, 2f, 9f, 3.34f, 9f, 5f)
            verticalLineTo(5.18f)
            lineTo(14.98f, 11.17f)
            close()
            moveTo(4.27f, 3f)
            lineTo(3f, 4.27f)
            lineTo(9.01f, 10.28f)
            verticalLineTo(11f)
            curveTo(9.01f, 12.66f, 10.34f, 14f, 12f, 14f)
            curveTo(12.22f, 14f, 12.44f, 13.97f, 12.65f, 13.92f)
            lineTo(14.31f, 15.58f)
            curveTo(13.6f, 15.91f, 12.81f, 16.1f, 12f, 16.1f)
            curveTo(9.24f, 16.1f, 6.7f, 14f, 6.7f, 11f)
            horizontalLineTo(5f)
            curveTo(5f, 14.41f, 7.72f, 17.23f, 11f, 17.72f)
            verticalLineTo(21f)
            horizontalLineTo(13f)
            verticalLineTo(17.72f)
            curveTo(13.91f, 17.59f, 14.77f, 17.27f, 15.54f, 16.82f)
            lineTo(19.73f, 21f)
            lineTo(21f, 19.73f)
            lineTo(4.27f, 3f)
            close()
        }
    }
}

val Icons.Filled.VolumeUp: ImageVector by lazy {
    yggVector("VolumeUp") {
        solidPath {
            moveTo(3f, 9f)
            verticalLineTo(15f)
            horizontalLineTo(7f)
            lineTo(12f, 20f)
            verticalLineTo(4f)
            lineTo(7f, 9f)
            horizontalLineTo(3f)
            close()
            moveTo(16.5f, 12f)
            curveTo(16.5f, 10.23f, 15.48f, 8.71f, 14f, 7.97f)
            verticalLineTo(16.02f)
            curveTo(15.48f, 15.29f, 16.5f, 13.77f, 16.5f, 12f)
            close()
            moveTo(14f, 3.23f)
            verticalLineTo(5.29f)
            curveTo(16.89f, 6.15f, 19f, 8.83f, 19f, 12f)
            curveTo(19f, 15.17f, 16.89f, 17.85f, 14f, 18.71f)
            verticalLineTo(20.77f)
            curveTo(18.01f, 19.86f, 21f, 16.28f, 21f, 12f)
            curveTo(21f, 7.72f, 18.01f, 4.14f, 14f, 3.23f)
            close()
        }
    }
}

val Icons.Filled.Image: ImageVector by lazy {
    yggVector("Image") {
        solidPath {
            moveTo(21f, 19f)
            verticalLineTo(5f)
            curveTo(21f, 3.9f, 20.1f, 3f, 19f, 3f)
            horizontalLineTo(5f)
            curveTo(3.9f, 3f, 3f, 3.9f, 3f, 5f)
            verticalLineTo(19f)
            curveTo(3f, 20.1f, 3.9f, 21f, 5f, 21f)
            horizontalLineTo(19f)
            curveTo(20.1f, 21f, 21f, 20.1f, 21f, 19f)
            close()
            moveTo(8.5f, 13.5f)
            lineTo(11f, 16.51f)
            lineTo(14.5f, 12f)
            lineTo(19f, 18f)
            horizontalLineTo(5f)
            lineTo(8.5f, 13.5f)
            close()
        }
    }
}

val Icons.Filled.CameraAlt: ImageVector by lazy {
    yggVector("CameraAlt") {
        solidPath {
            moveTo(12f, 10f)
            curveTo(10.34f, 10f, 9f, 11.34f, 9f, 13f)
            curveTo(9f, 14.66f, 10.34f, 16f, 12f, 16f)
            curveTo(13.66f, 16f, 15f, 14.66f, 15f, 13f)
            curveTo(15f, 11.34f, 13.66f, 10f, 12f, 10f)
            close()
            moveTo(20f, 4f)
            horizontalLineTo(16.83f)
            lineTo(15f, 2f)
            horizontalLineTo(9f)
            lineTo(7.17f, 4f)
            horizontalLineTo(4f)
            curveTo(2.9f, 4f, 2f, 4.9f, 2f, 6f)
            verticalLineTo(18f)
            curveTo(2f, 19.1f, 2.9f, 20f, 4f, 20f)
            horizontalLineTo(20f)
            curveTo(21.1f, 20f, 22f, 19.1f, 22f, 18f)
            verticalLineTo(6f)
            curveTo(22f, 4.9f, 21.1f, 4f, 20f, 4f)
            close()
            moveTo(12f, 18f)
            curveTo(9.24f, 18f, 7f, 15.76f, 7f, 13f)
            curveTo(7f, 10.24f, 9.24f, 8f, 12f, 8f)
            curveTo(14.76f, 8f, 17f, 10.24f, 17f, 13f)
            curveTo(17f, 15.76f, 14.76f, 18f, 12f, 18f)
            close()
        }
    }
}

val Icons.Filled.QrCodeScanner: ImageVector by lazy {
    yggVector("QrCodeScanner") {
        solidPath {
            moveTo(9.5f, 6.5f)
            verticalLineTo(9.5f)
            horizontalLineTo(6.5f)
            verticalLineTo(6.5f)
            horizontalLineTo(9.5f)
            moveTo(11f, 5f)
            horizontalLineTo(5f)
            verticalLineTo(11f)
            horizontalLineTo(11f)
            verticalLineTo(5f)
            close()
            moveTo(17.5f, 6.5f)
            verticalLineTo(9.5f)
            horizontalLineTo(14.5f)
            verticalLineTo(6.5f)
            horizontalLineTo(17.5f)
            moveTo(19f, 5f)
            horizontalLineTo(13f)
            verticalLineTo(11f)
            horizontalLineTo(19f)
            verticalLineTo(5f)
            close()
            moveTo(9.5f, 14.5f)
            verticalLineTo(17.5f)
            horizontalLineTo(6.5f)
            verticalLineTo(14.5f)
            horizontalLineTo(9.5f)
            moveTo(11f, 13f)
            horizontalLineTo(5f)
            verticalLineTo(19f)
            horizontalLineTo(11f)
            verticalLineTo(13f)
            close()
            moveTo(15f, 13f)
            horizontalLineTo(13f)
            verticalLineTo(15f)
            horizontalLineTo(15f)
            verticalLineTo(13f)
            close()
            moveTo(17f, 15f)
            horizontalLineTo(15f)
            verticalLineTo(17f)
            horizontalLineTo(17f)
            verticalLineTo(15f)
            close()
            moveTo(19f, 17f)
            horizontalLineTo(17f)
            verticalLineTo(19f)
            horizontalLineTo(19f)
            verticalLineTo(17f)
            close()
            moveTo(4f, 2f)
            horizontalLineTo(2f)
            verticalLineTo(7f)
            horizontalLineTo(4f)
            verticalLineTo(4f)
            horizontalLineTo(7f)
            verticalLineTo(2f)
            close()
            moveTo(20f, 2f)
            horizontalLineTo(17f)
            verticalLineTo(4f)
            horizontalLineTo(20f)
            verticalLineTo(7f)
            horizontalLineTo(22f)
            verticalLineTo(2f)
            close()
            moveTo(4f, 17f)
            horizontalLineTo(2f)
            verticalLineTo(22f)
            horizontalLineTo(7f)
            verticalLineTo(20f)
            horizontalLineTo(4f)
            verticalLineTo(17f)
            close()
            moveTo(20f, 20f)
            horizontalLineTo(17f)
            verticalLineTo(22f)
            horizontalLineTo(22f)
            verticalLineTo(17f)
            horizontalLineTo(20f)
            verticalLineTo(20f)
            close()
        }
    }
}

val Icons.Filled.AddReaction: ImageVector by lazy {
    yggVector("AddReaction") {
        solidPath {
            moveTo(12f, 2f)
            curveTo(6.48f, 2f, 2f, 6.48f, 2f, 12f)
            curveTo(2f, 17.52f, 6.48f, 22f, 12f, 22f)
            curveTo(17.52f, 22f, 22f, 17.52f, 22f, 12f)
            curveTo(22f, 6.48f, 17.52f, 2f, 12f, 2f)
            close()
            moveTo(12f, 20f)
            curveTo(7.59f, 20f, 4f, 16.41f, 4f, 12f)
            curveTo(4f, 7.59f, 7.59f, 4f, 12f, 4f)
            curveTo(16.41f, 4f, 20f, 7.59f, 20f, 12f)
            curveTo(20f, 16.41f, 16.41f, 20f, 12f, 20f)
            close()
            moveTo(15.5f, 11f)
            curveTo(16.33f, 11f, 17f, 10.33f, 17f, 9.5f)
            curveTo(17f, 8.67f, 16.33f, 8f, 15.5f, 8f)
            curveTo(14.67f, 8f, 14f, 8.67f, 14f, 9.5f)
            curveTo(14f, 10.33f, 14.67f, 11f, 15.5f, 11f)
            close()
            moveTo(8.5f, 11f)
            curveTo(9.33f, 11f, 10f, 10.33f, 10f, 9.5f)
            curveTo(10f, 8.67f, 9.33f, 8f, 8.5f, 8f)
            curveTo(7.67f, 8f, 7f, 8.67f, 7f, 9.5f)
            curveTo(7f, 10.33f, 7.67f, 11f, 8.5f, 11f)
            close()
            moveTo(12f, 17.5f)
            curveTo(14.33f, 17.5f, 16.31f, 16.04f, 17.11f, 14f)
            horizontalLineTo(6.89f)
            curveTo(7.69f, 16.04f, 9.67f, 17.5f, 12f, 17.5f)
            close()
        }
    }
}

val Icons.Filled.GraphicEq: ImageVector by lazy {
    yggVector("GraphicEq") {
        solidPath {
            moveTo(7f, 18f)
            horizontalLineTo(9f)
            verticalLineTo(6f)
            horizontalLineTo(7f)
            verticalLineTo(18f)
            close()
            moveTo(11f, 22f)
            horizontalLineTo(13f)
            verticalLineTo(2f)
            horizontalLineTo(11f)
            verticalLineTo(22f)
            close()
            moveTo(3f, 14f)
            horizontalLineTo(5f)
            verticalLineTo(10f)
            horizontalLineTo(3f)
            verticalLineTo(14f)
            close()
            moveTo(15f, 18f)
            horizontalLineTo(17f)
            verticalLineTo(6f)
            horizontalLineTo(15f)
            verticalLineTo(18f)
            close()
            moveTo(19f, 10f)
            verticalLineTo(14f)
            horizontalLineTo(21f)
            verticalLineTo(10f)
            horizontalLineTo(19f)
            close()
        }
    }
}

val Icons.Filled.Description: ImageVector by lazy {
    yggVector("Description") {
        solidPath {
            moveTo(14f, 2f)
            horizontalLineTo(6f)
            curveTo(4.9f, 2f, 4.01f, 2.9f, 4.01f, 4f)
            lineTo(4f, 20f)
            curveTo(4f, 21.1f, 4.89f, 22f, 5.99f, 22f)
            horizontalLineTo(18f)
            curveTo(19.1f, 22f, 20f, 21.1f, 20f, 20f)
            verticalLineTo(8f)
            lineTo(14f, 2f)
            close()
            moveTo(16f, 18f)
            horizontalLineTo(8f)
            verticalLineTo(16f)
            horizontalLineTo(16f)
            verticalLineTo(18f)
            close()
            moveTo(16f, 14f)
            horizontalLineTo(8f)
            verticalLineTo(12f)
            horizontalLineTo(16f)
            verticalLineTo(14f)
            close()
            moveTo(13f, 9f)
            verticalLineTo(3.5f)
            lineTo(18.5f, 9f)
            horizontalLineTo(13f)
            close()
        }
    }
}

val Icons.Filled.Stop: ImageVector by lazy {
    yggVector("Stop") {
        solidPath {
            moveTo(6f, 6f)
            horizontalLineTo(18f)
            verticalLineTo(18f)
            horizontalLineTo(6f)
            verticalLineTo(6f)
            close()
        }
    }
}


