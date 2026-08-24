package com.connectx.app.ui.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.connectx.app.ui.theme.ConnectXSpacing

/**
 * ConnectX's primary call-to-action button. Mirrors the React frontend's
 * solid-violet, semibold, rounded-xl submit buttons (see AuthModal.tsx), and
 * its recurring "disabled while loading, spinner replaces nothing else" pattern.
 *
 * Uses MaterialTheme.colorScheme.primary/shapes so it automatically follows
 * ConnectXTheme -- never define a one-off color/shape on a button directly.
 */
@Composable
fun ConnectXButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    isLoading: Boolean = false
) {
    Button(
        onClick = onClick,
        modifier = modifier.height(48.dp), // meets the 48dp minimum touch target
        enabled = enabled && !isLoading,
        shape = MaterialTheme.shapes.medium,
        colors = ButtonDefaults.buttonColors(
            containerColor = MaterialTheme.colorScheme.primary,
            contentColor = MaterialTheme.colorScheme.onPrimary
        ),
        contentPadding = PaddingValues(horizontal = ConnectXSpacing.lg)
    ) {
        Row(
            horizontalArrangement = Arrangement.spacedBy(ConnectXSpacing.sm)
        ) {
            if (isLoading) {
                CircularProgressIndicator(
                    modifier = Modifier.size(18.dp),
                    strokeWidth = 2.dp,
                    color = LocalContentColor.current
                )
            }
            Text(text = text, style = MaterialTheme.typography.labelLarge)
        }
    }
}
