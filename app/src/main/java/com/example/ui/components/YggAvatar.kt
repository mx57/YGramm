// File: app/src/main/java/com/example/ui/components/YggAvatar.kt
package com.example.ui.components

import android.net.Uri
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImage

/**
 * The one place a face is drawn: a picture that came over the overlay if we have it,
 * initials if we do not.
 *
 * Every list and header used to render `title.take(2)` in its own copy of the same Box,
 * which meant an avatar had to be wired in N places and would drift out of sync the next
 * time one of them was edited. A missing, unreadable or half-written file falls back to
 * the initials instead of leaving a blank circle that reads as a bug.
 */
@Composable
fun YggAvatar(
    avatarUri: String,
    name: String,
    modifier: Modifier = Modifier,
    size: Dp = 42.dp,
    // A chat with no human on the other side (the self-chat) has no initials to show.
    placeholderIcon: ImageVector? = null
) {
    var loadFailed by remember(avatarUri) { mutableStateOf(false) }
    Box(
        modifier = modifier
            .size(size)
            .clip(CircleShape)
            .background(MaterialTheme.colorScheme.primaryContainer),
        contentAlignment = Alignment.Center
    ) {
        if (avatarUri.isNotBlank() && !loadFailed) {
            AsyncImage(
                model = remember(avatarUri) { Uri.parse(avatarUri) },
                contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize(),
                onError = { loadFailed = true }
            )
        } else if (placeholderIcon != null) {
            Icon(
                imageVector = placeholderIcon,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onPrimaryContainer
            )
        } else {
            Text(
                text = name.take(2).uppercase(),
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onPrimaryContainer
            )
        }
    }
}
