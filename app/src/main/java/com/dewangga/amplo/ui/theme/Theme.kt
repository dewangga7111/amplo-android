package com.dewangga.amplo.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp
import com.dewangga.amplo.R

/** The colors of the desktop app. */
object Amplo {
    val Text = Color(0xFFF2F3F5)
    val Accent = Color(0xFFC8F25A)
    val AccentHover = Color(0xFFD8FF61)
    val OnAccent = Color(0xFF0E0F12)
    val Background = Color(0xFF121317)
    val Panel = Color(0xFF15171B)
    val Line = Color(0xFF1F2227)
    val Muted = Color(0xFF8B929D)
    val Soft = Color(0xFF9AA1AC)
    val Pressed = Color(0xFF262A31)
    val RowCurrent = Color(0xFF1A1D22)
    val Mini = Color(0xFF1C1F24)
    val MiniLine = Color(0xFF2A2E35)
    val Warning = Color(0xFFFF9A5C)
    val FullBackground = Color(0xFF0E0F12)
    val PillLine = Color(0xFF2C3038)
    /** The status of a Spotify song that is in the music folder, and of one that is not. */
    val Available = Color(0xFF5EE08C)
    val Missing = Color(0xFFFF6B6B)
}

val Inter = FontFamily(
    Font(R.font.inter_regular, FontWeight.Normal),
    Font(R.font.inter_semibold, FontWeight.SemiBold),
)

val SpaceGrotesk = FontFamily(
    Font(R.font.spacegrotesk_semibold, FontWeight.SemiBold),
    Font(R.font.spacegrotesk_bold, FontWeight.Bold),
)

val JetBrainsMono = FontFamily(
    Font(R.font.jetbrainsmono_medium, FontWeight.Medium),
    Font(R.font.jetbrainsmono_semibold, FontWeight.SemiBold),
)

private val Body = TextStyle(fontFamily = Inter, fontWeight = FontWeight.Normal, fontSize = 15.sp, color = Amplo.Text)

private val AmploTypography = Typography(
    headlineMedium = TextStyle(fontFamily = SpaceGrotesk, fontWeight = FontWeight.Bold, fontSize = 28.sp),
    titleMedium = Body.copy(fontWeight = FontWeight.SemiBold, fontSize = 16.sp),
    bodyLarge = Body,
    bodyMedium = Body.copy(fontSize = 14.sp),
    bodySmall = Body.copy(fontSize = 13.sp, color = Amplo.Muted),
    labelLarge = Body.copy(fontWeight = FontWeight.SemiBold, fontSize = 14.sp),
    labelSmall = TextStyle(fontFamily = JetBrainsMono, fontWeight = FontWeight.Medium, fontSize = 11.sp),
)

@Composable
fun AmploTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = darkColorScheme(
            primary = Amplo.Accent,
            onPrimary = Amplo.OnAccent,
            background = Amplo.Background,
            onBackground = Amplo.Text,
            surface = Amplo.Mini,
            onSurface = Amplo.Text,
            surfaceContainer = Amplo.Mini,
            onSurfaceVariant = Amplo.Muted,
            outline = Amplo.Line,
            outlineVariant = Amplo.MiniLine,
        ),
        typography = AmploTypography,
        content = content,
    )
}
