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
