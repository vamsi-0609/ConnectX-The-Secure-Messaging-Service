package com.connectx.app.ui.theme

import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Shapes
import androidx.compose.ui.unit.dp

/**
 * Centralized ConnectX corner-radius scale, derived from the React frontend's
 * consistent use of `rounded-xl` (12dp) for buttons/inputs/cards and
 * `rounded-2xl` (16dp) for message bubbles and larger surfaces.
 */
object ConnectXRadius {
    val small = 8.dp // chips, small controls
    val medium = 12.dp // buttons, text fields, cards -- React's rounded-xl
    val large = 16.dp // dialogs, larger surfaces -- React's rounded-2xl
}

val ConnectXShapes = Shapes(
    extraSmall = RoundedCornerShape(4.dp),
    small = RoundedCornerShape(ConnectXRadius.small),
    medium = RoundedCornerShape(ConnectXRadius.medium),
    large = RoundedCornerShape(ConnectXRadius.large),
    extraLarge = RoundedCornerShape(24.dp)
)
