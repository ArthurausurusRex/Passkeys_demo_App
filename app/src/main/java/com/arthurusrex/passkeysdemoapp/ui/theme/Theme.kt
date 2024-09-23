package com.arthurusrex.passkeysdemoapp.ui.theme

import androidx.compose.foundation.BorderStroke
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp

val WavestonePurple = Color(0xFF6D2077)
val WhiteBorder = BorderStroke(1.dp, Color.White)
val BoldWhite = TextStyle(color = Color.White, fontWeight = FontWeight.Bold)

@Composable
fun PasskeysDemoAppTheme(
    content: @Composable () -> Unit
) {
    MaterialTheme(
        colorScheme = lightColorScheme(
            background = WavestonePurple,
            surface = WavestonePurple,
            onBackground = WavestonePurple,
            onSurface = WavestonePurple,
            primary = WavestonePurple,

        ),
        typography = Typography,
        content = content
    )
}