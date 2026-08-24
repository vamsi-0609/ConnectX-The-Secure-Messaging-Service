package com.connectx.app.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.connectx.app.ui.theme.ConnectXExtendedTheme

enum class ConnectXAvatarSize(val diameter: Dp, val textSize: androidx.compose.ui.unit.TextUnit) {
    Small(32.dp, 12.sp),
    Medium(44.dp, 16.sp),
    Large(64.dp, 22.sp)
}

/**
 * ConnectX's identity avatar. Mirrors the React frontend's UserAvatar.tsx:
 * a circular, indigo-to-violet gradient surface showing the display name's
 * first initial. Deliberately initials-only for this phase -- image loading
 * (Coil/Glide) is a real new dependency with no genuine use yet (no profile
 * photo data exists until later phases), so it was not added here.
 */
@Composable
fun ConnectXAvatar(
    displayName: String,
    modifier: Modifier = Modifier,
    size: ConnectXAvatarSize = ConnectXAvatarSize.Medium
) {
    val initial = displayName.trim().firstOrNull()?.uppercaseChar()?.toString() ?: "?"
    Box(
        modifier = modifier
            .size(size.diameter)
            .clip(CircleShape)
            .background(ConnectXExtendedTheme.colors.messageSentGradient)
            .semantics { contentDescription = "$displayName's avatar" },
        contentAlignment = Alignment.Center
    ) {
        Text(
            text = initial,
            color = ConnectXExtendedTheme.colors.onMessageSent,
            fontWeight = FontWeight.SemiBold,
            fontSize = size.textSize,
            style = MaterialTheme.typography.titleMedium
        )
    }
}
