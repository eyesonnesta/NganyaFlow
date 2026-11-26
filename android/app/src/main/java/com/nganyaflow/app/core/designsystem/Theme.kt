package com.nganyaflow.app.core.designsystem

import android.os.Build
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

private val LightColors = lightColorScheme(
    primary = Color(0xFF1B4965), onPrimary = Color.White,
    primaryContainer = Color(0xFFCAE9F5), onPrimaryContainer = Color(0xFF092E42),
    secondary = Color(0xFF526471), onSecondary = Color.White,
    secondaryContainer = Color(0xFFD6E5F1), onSecondaryContainer = Color(0xFF10202B),
    tertiary = Color(0xFF9B431E), onTertiary = Color.White,
    tertiaryContainer = Color(0xFFFFDBCD), onTertiaryContainer = Color(0xFF351000),
    background = Color(0xFFF7FAFC), onBackground = Color(0xFF172127),
    surface = Color(0xFFF7FAFC), onSurface = Color(0xFF172127),
    surfaceContainerLow = Color(0xFFF0F5F8), surfaceContainer = Color(0xFFEBF1F5),
    surfaceContainerHigh = Color(0xFFE2EAF0), onSurfaceVariant = Color(0xFF465660),
    outline = Color(0xFF6C7D88), outlineVariant = Color(0xFFBBC9D3),
    error = Color(0xFFBA1A1A), onError = Color.White,
    errorContainer = Color(0xFFFFDAD6), onErrorContainer = Color(0xFF410002),
)
private val DarkColors = darkColorScheme(
    primary = Color(0xFF9ACCEB), onPrimary = Color(0xFF00344D),
    primaryContainer = Color(0xFF204C63), onPrimaryContainer = Color(0xFFCAE9F5),
    secondary = Color(0xFFB9C9D5), onSecondary = Color(0xFF243440),
    secondaryContainer = Color(0xFF3B4B57), onSecondaryContainer = Color(0xFFD6E5F1),
    tertiary = Color(0xFFFFB596), onTertiary = Color(0xFF572008),
    tertiaryContainer = Color(0xFF783016), onTertiaryContainer = Color(0xFFFFDBCD),
    background = Color(0xFF10181D), onBackground = Color(0xFFDFE7ED),
    surface = Color(0xFF10181D), onSurface = Color(0xFFDFE7ED),
    surfaceContainerLow = Color(0xFF162027), surfaceContainer = Color(0xFF1C262D),
    surfaceContainerHigh = Color(0xFF26313A), onSurfaceVariant = Color(0xFFBCC8D1),
    outline = Color(0xFF8797A2), outlineVariant = Color(0xFF3D4C56),
    error = Color(0xFFFFB4AB), onError = Color(0xFF690005),
    errorContainer = Color(0xFF93000A), onErrorContainer = Color(0xFFFFDAD6),
)

@Immutable
data class StatusColors(
    val successContainer: Color, val onSuccessContainer: Color,
    val warningContainer: Color, val onWarningContainer: Color,
)
val LocalStatusColors = staticCompositionLocalOf {
    StatusColors(Color(0xFFD2E8D2), Color(0xFF153B1E), Color(0xFFFFDEA8), Color(0xFF392B0C))
}

object Spacing {
    val tiny = 4.dp
    val small = 8.dp
    val medium = 12.dp
    val screen = 16.dp
    val large = 24.dp
    val hero = 28.dp
    val extraLarge = 32.dp
}

private val AppTypography = Typography(
    displaySmall = TextStyle(fontSize = 36.sp, lineHeight = 44.sp),
    headlineLarge = TextStyle(fontSize = 32.sp, lineHeight = 40.sp),
    headlineSmall = TextStyle(fontSize = 24.sp, lineHeight = 32.sp),
    titleLarge = TextStyle(fontSize = 22.sp, lineHeight = 28.sp, fontWeight = FontWeight.Medium),
    titleMedium = TextStyle(fontSize = 16.sp, lineHeight = 24.sp, fontWeight = FontWeight.Medium),
    bodyLarge = TextStyle(fontSize = 16.sp, lineHeight = 24.sp),
    bodyMedium = TextStyle(fontSize = 14.sp, lineHeight = 20.sp),
    labelLarge = TextStyle(fontSize = 14.sp, lineHeight = 20.sp, fontWeight = FontWeight.Medium),
    labelMedium = TextStyle(fontSize = 12.sp, lineHeight = 16.sp, fontWeight = FontWeight.Medium),
)

@Composable
fun NganyaFlowTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    dynamicColor: Boolean = false,
    content: @Composable () -> Unit,
) {
    val colors = when {
        dynamicColor && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S -> {
            if (darkTheme) dynamicDarkColorScheme(LocalContext.current)
            else dynamicLightColorScheme(LocalContext.current)
        }
        darkTheme -> DarkColors
        else -> LightColors
    }
    val status = if (darkTheme) {
        StatusColors(Color(0xFF193820), Color(0xFFB6DDBB), Color(0xFF49320A), Color(0xFFFFDEA8))
    } else {
        StatusColors(Color(0xFFD2E8D2), Color(0xFF153B1E), Color(0xFFFFDEA8), Color(0xFF392B0C))
    }
    CompositionLocalProvider(LocalStatusColors provides status) {
        MaterialTheme(
            colorScheme = colors, typography = AppTypography,
            shapes = Shapes(
                extraSmall = RoundedCornerShape(8.dp), small = RoundedCornerShape(12.dp),
                medium = RoundedCornerShape(16.dp), large = RoundedCornerShape(24.dp),
                extraLarge = RoundedCornerShape(28.dp),
            ), content = content,
        )
    }
}
