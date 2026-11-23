package com.knifefire.quotaboard.nativeui

import android.os.Build
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext

// 品牌蓝紫配色（与现有图标 #3b6ff5 → #7c5cfa 一致）
private val BrandLight = lightColorScheme(
    primary = Color(0xFF3B6FF5),
    onPrimary = Color.White,
    primaryContainer = Color(0xFFDCE7FF),
    onPrimaryContainer = Color(0xFF0A327A),
    secondary = Color(0xFF7C5CFA),
    onSecondary = Color.White,
    secondaryContainer = Color(0xFFE9E1FF),
    onSecondaryContainer = Color(0xFF3200A0),
    tertiary = Color(0xFF7C5CFA),
    surface = Color(0xFFF7F8FC),
    surfaceContainer = Color(0xFFEEF1F8),
    surfaceContainerHigh = Color(0xFFE7EBF5),
    error = Color(0xFFF04438)
)
private val BrandDark = darkColorScheme(
    primary = Color(0xFF9DBBFF),
    onPrimary = Color(0xFF003087),
    primaryContainer = Color(0xFF2145A0),
    onPrimaryContainer = Color(0xFFDCE7FF),
    secondary = Color(0xFFC4B5FF),
    onSecondary = Color(0xFF3500A0),
    secondaryContainer = Color(0xFF4A2BA8),
    onSecondaryContainer = Color(0xFFE9E1FF),
    surface = Color(0xFF111318),
    surfaceContainer = Color(0xFF1C1F26),
    surfaceContainerHigh = Color(0xFF262A33),
    error = Color(0xFFFF6B65)
)

/** mode: "brand" = 蓝紫品牌色, "dynamic" = 跟随系统壁纸 */
@Composable
fun BoardTheme(mode: String, content: @Composable () -> Unit) {
    val context = LocalContext.current
    val dark = isSystemInDarkTheme()
    val scheme: ColorScheme = when {
        mode == "dynamic" && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S ->
            if (dark) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context)
        dark -> BrandDark
        else -> BrandLight
    }
    MaterialTheme(colorScheme = scheme, typography = Typography(), content = content)
}
