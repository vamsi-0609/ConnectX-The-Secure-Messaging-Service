package com.connectx.app.ui.components

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedCard
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.connectx.app.ui.theme.ConnectXSpacing

/**
 * ConnectX's standard content surface. The React frontend consistently prefers
 * a subtle border over heavy elevation/shadow for cards and panels (see
 * src/index.css's minimal shadow usage and the border-color tokens applied
 * throughout components/) -- this component follows that language: a bordered,
 * flat surface rather than a drop-shadowed one.
 */
@Composable
fun ConnectXCard(
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit
) {
    OutlinedCard(
        modifier = modifier,
        shape = MaterialTheme.shapes.medium,
        colors = CardDefaults.outlinedCardColors(
            containerColor = MaterialTheme.colorScheme.surface
        ),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline)
    ) {
        Column(modifier = Modifier.padding(ConnectXSpacing.lg)) {
            content()
        }
    }
}
