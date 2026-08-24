package com.connectx.app.ui.showcase

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.unit.dp
import com.connectx.app.ui.components.ConnectXAvatar
import com.connectx.app.ui.components.ConnectXAvatarSize
import com.connectx.app.ui.components.ConnectXButton
import com.connectx.app.ui.components.ConnectXCard
import com.connectx.app.ui.components.ConnectXEmptyState
import com.connectx.app.ui.components.ConnectXErrorState
import com.connectx.app.ui.components.ConnectXLoadingIndicator
import com.connectx.app.ui.components.ConnectXTextField
import com.connectx.app.ui.theme.ConnectXExtendedTheme
import com.connectx.app.ui.theme.ConnectXSpacing
import com.connectx.app.ui.theme.ConnectXTheme

/**
 * TEMPORARY, N1.8-only design-system showcase. This is NOT a ConnectX
 * application screen -- it exists purely to visually validate the design
 * system (colors, typography, spacing, components, light/dark theme) before
 * any real screen is built. Reachable only via the temporary navigation_test
 * route established in N1.7. Expected to be removed once N4 (Core App Shell)
 * replaces this temporary navigation scaffolding with the real app shell.
 *
 * The screen manages its own light/dark override (independent of the system
 * setting) purely so both themes can be inspected in one running session --
 * this toggle is showcase-only infrastructure, not a real app setting.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DesignShowcaseScreen(onBack: () -> Unit) {
    var isDark by remember { mutableStateOf(false) }

    ConnectXTheme(darkTheme = isDark) {
        Scaffold(
            topBar = {
                TopAppBar(
                    title = { Text("Design System Showcase") },
                    navigationIcon = {
                        IconButton(onClick = onBack) {
                            Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                        }
                    },
                    actions = {
                        TextButton(onClick = { isDark = !isDark }) {
                            Text(if (isDark) "Light theme" else "Dark theme")
                        }
                    },
                    colors = TopAppBarDefaults.topAppBarColors(
                        containerColor = MaterialTheme.colorScheme.surface
                    )
                )
            },
            containerColor = MaterialTheme.colorScheme.background
        ) { innerPadding ->
            LazyColumn(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(innerPadding)
                    .padding(horizontal = ConnectXSpacing.screenMargin),
                verticalArrangement = Arrangement.spacedBy(ConnectXSpacing.xl)
            ) {
                item { SectionSpacer() }
                item { ColorsSection() }
                item { TypographySection() }
                item { ButtonsSection() }
                item { TextFieldsSection() }
                item { CardsSection() }
                item { AvatarsSection() }
                item { DividerSection() }
                item { StatesSection() }
                item { SpacingSection() }
                item { SectionSpacer() }
            }
        }
    }
}

@Composable
private fun SectionSpacer() {
    Box(modifier = Modifier.size(1.dp))
}

@Composable
private fun SectionTitle(text: String) {
    Text(text = text, style = MaterialTheme.typography.titleMedium)
}

@Composable
private fun ColorsSection() {
    Column(verticalArrangement = Arrangement.spacedBy(ConnectXSpacing.sm)) {
        SectionTitle("Colors")
        val swatches = listOf(
            "Primary" to MaterialTheme.colorScheme.primary,
            "Secondary" to MaterialTheme.colorScheme.secondary,
            "Success" to ConnectXExtendedTheme.colors.success,
            "Warning" to ConnectXExtendedTheme.colors.warning,
            "Error" to MaterialTheme.colorScheme.error,
            "Surface" to MaterialTheme.colorScheme.surface,
            "Surface Variant" to MaterialTheme.colorScheme.surfaceVariant,
            "Background" to MaterialTheme.colorScheme.background
        )
        swatches.chunked(2).forEach { row ->
            Row(horizontalArrangement = Arrangement.spacedBy(ConnectXSpacing.sm)) {
                row.forEach { (label, color) ->
                    Column(
                        modifier = Modifier.weight(1f),
                        horizontalAlignment = Alignment.CenterHorizontally
                    ) {
                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .size(48.dp)
                                .clip(RoundedCornerShape(12.dp))
                                .background(color)
                        )
                        Text(
                            text = label,
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onBackground
                        )
                    }
                }
                if (row.size == 1) {
                    Box(modifier = Modifier.weight(1f))
                }
            }
        }
    }
}

@Composable
private fun TypographySection() {
    Column(verticalArrangement = Arrangement.spacedBy(ConnectXSpacing.xs)) {
        SectionTitle("Typography")
        Text("Title Large", style = MaterialTheme.typography.titleLarge)
        Text("Title Medium", style = MaterialTheme.typography.titleMedium)
        Text("Title Small", style = MaterialTheme.typography.titleSmall)
        Text("Body Large -- primary reading text", style = MaterialTheme.typography.bodyLarge)
        Text("Body Medium -- default body text", style = MaterialTheme.typography.bodyMedium)
        Text(
            "Body Small -- secondary/meta text",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Text("Label Large -- buttons", style = MaterialTheme.typography.labelLarge)
        Text("Label Small -- captions", style = MaterialTheme.typography.labelSmall)
    }
}

@Composable
private fun ButtonsSection() {
    var loading by remember { mutableStateOf(false) }
    Column(verticalArrangement = Arrangement.spacedBy(ConnectXSpacing.sm)) {
        SectionTitle("Buttons")
        ConnectXButton(text = "Enabled", onClick = { }, modifier = Modifier.fillMaxWidth())
        ConnectXButton(
            text = "Disabled",
            onClick = { },
            enabled = false,
            modifier = Modifier.fillMaxWidth()
        )
        ConnectXButton(
            text = "Tap to toggle loading",
            onClick = { loading = !loading },
            isLoading = loading,
            modifier = Modifier.fillMaxWidth()
        )
    }
}

@Composable
private fun TextFieldsSection() {
    var value by remember { mutableStateOf("") }
    var errorValue by remember { mutableStateOf("invalid input") }
    Column(verticalArrangement = Arrangement.spacedBy(ConnectXSpacing.sm)) {
        SectionTitle("Text Fields")
        ConnectXTextField(
            value = value,
            onValueChange = { value = it },
            label = "Username",
            placeholder = "you@example.com or username",
            modifier = Modifier.fillMaxWidth()
        )
        ConnectXTextField(
            value = errorValue,
            onValueChange = { errorValue = it },
            label = "Email",
            isError = true,
            supportingText = "Enter a valid email address",
            modifier = Modifier.fillMaxWidth()
        )
        ConnectXTextField(
            value = "Disabled field",
            onValueChange = { },
            label = "Disabled",
            enabled = false,
            modifier = Modifier.fillMaxWidth()
        )
    }
}

@Composable
private fun CardsSection() {
    Column(verticalArrangement = Arrangement.spacedBy(ConnectXSpacing.sm)) {
        SectionTitle("Cards")
        ConnectXCard(modifier = Modifier.fillMaxWidth()) {
            Text("ConnectXCard", style = MaterialTheme.typography.titleSmall)
            Text(
                "A bordered, flat content surface -- ConnectX prefers a subtle outline over heavy shadow.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

@Composable
private fun AvatarsSection() {
    Column(verticalArrangement = Arrangement.spacedBy(ConnectXSpacing.sm)) {
        SectionTitle("Avatars")
        Row(
            horizontalArrangement = Arrangement.spacedBy(ConnectXSpacing.md),
            verticalAlignment = Alignment.CenterVertically
        ) {
            ConnectXAvatar(displayName = "Vamsi", size = ConnectXAvatarSize.Small)
            ConnectXAvatar(displayName = "ConnectX", size = ConnectXAvatarSize.Medium)
            ConnectXAvatar(displayName = "Ananya", size = ConnectXAvatarSize.Large)
        }
    }
}

@Composable
private fun DividerSection() {
    Column(verticalArrangement = Arrangement.spacedBy(ConnectXSpacing.sm)) {
        SectionTitle("Divider")
        HorizontalDivider(color = MaterialTheme.colorScheme.outline)
    }
}

@Composable
private fun StatesSection() {
    Column(verticalArrangement = Arrangement.spacedBy(ConnectXSpacing.sm)) {
        SectionTitle("States")
        ConnectXCard(modifier = Modifier.fillMaxWidth()) {
            Text("Loading", style = MaterialTheme.typography.labelMedium)
            ConnectXLoadingIndicator(modifier = Modifier.fillMaxWidth())
        }
        ConnectXCard(modifier = Modifier.fillMaxWidth()) {
            Text("Empty", style = MaterialTheme.typography.labelMedium)
            ConnectXEmptyState(
                title = "No conversations yet",
                message = "Once you connect with someone, your chats will appear here.",
                modifier = Modifier.fillMaxWidth()
            )
        }
        ConnectXCard(modifier = Modifier.fillMaxWidth()) {
            Text("Error", style = MaterialTheme.typography.labelMedium)
            ConnectXErrorState(
                message = "Something went wrong. Please try again.",
                modifier = Modifier.fillMaxWidth()
            )
        }
    }
}

@Composable
private fun SpacingSection() {
    val spacings = listOf(
        "xs (4dp)" to ConnectXSpacing.xs,
        "sm (8dp)" to ConnectXSpacing.sm,
        "md (12dp)" to ConnectXSpacing.md,
        "lg (16dp)" to ConnectXSpacing.lg,
        "xl (24dp)" to ConnectXSpacing.xl,
        "xxl (32dp)" to ConnectXSpacing.xxl
    )
    Column(verticalArrangement = Arrangement.spacedBy(ConnectXSpacing.sm)) {
        SectionTitle("Spacing Scale")
        spacings.forEach { (label, value) ->
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(
                    modifier = Modifier
                        .size(value)
                        .background(MaterialTheme.colorScheme.primary, RoundedCornerShape(2.dp))
                )
                Text(
                    text = "  $label",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
}
