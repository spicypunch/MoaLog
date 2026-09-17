package kr.jm.moalog.core.designsystem

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp
import moalog.core.designsystem.generated.resources.Res
import moalog.core.designsystem.generated.resources.pretendard_bold
import moalog.core.designsystem.generated.resources.pretendard_medium
import moalog.core.designsystem.generated.resources.pretendard_regular
import moalog.core.designsystem.generated.resources.pretendard_semibold
import org.jetbrains.compose.resources.Font

object MoaLogColors {
    val DeepTeal = Color(0xFF1E4E4A)
    val PressedTeal = Color(0xFF133E3A)
    val TealInk = Color(0xFF003733)
    val DeepTealContainer = Color(0xFFD9E5E3)
    val Canvas = Color(0xFFFBF9F5)
    val Linen = Color(0xFFF5F3ED)
    val WarmIvory = Canvas
    val WarmSurface = Color.White
    val CardBorder = Color(0xFFEAE6DF)
    val Ink = Color(0xFF191C1A)
    val MutedInk = Color(0xFF55615F)
    val Outline = Color(0xFF707977)
    val Overspend = Color(0xFFD9383A)
    val OverspendInk = Color(0xFF872022)
    val OverspendSurface = Color(0xFFFDECEE)
}

private val WarmLedgerColors = lightColorScheme(
    primary = MoaLogColors.DeepTeal,
    onPrimary = Color.White,
    primaryContainer = MoaLogColors.DeepTealContainer,
    onPrimaryContainer = MoaLogColors.Ink,
    secondary = MoaLogColors.MutedInk,
    onSecondary = Color.White,
    secondaryContainer = MoaLogColors.DeepTealContainer,
    onSecondaryContainer = Color(0xFF3E4948),
    background = MoaLogColors.WarmIvory,
    onBackground = MoaLogColors.Ink,
    surface = MoaLogColors.WarmSurface,
    onSurface = MoaLogColors.Ink,
    onSurfaceVariant = MoaLogColors.MutedInk,
    outline = MoaLogColors.Outline,
    outlineVariant = MoaLogColors.CardBorder,
    error = MoaLogColors.Overspend,
    errorContainer = MoaLogColors.OverspendSurface,
    onErrorContainer = MoaLogColors.OverspendInk,
)

private fun warmLedgerTypography(fontFamily: FontFamily) = Typography(
    displaySmall = TextStyle(fontFamily = fontFamily, fontWeight = FontWeight.Bold, fontSize = 32.sp, lineHeight = 40.sp, letterSpacing = (-0.64).sp),
    headlineLarge = TextStyle(fontFamily = fontFamily, fontWeight = FontWeight.Bold, fontSize = 24.sp, lineHeight = 32.sp, letterSpacing = (-0.36).sp),
    headlineMedium = TextStyle(fontFamily = fontFamily, fontWeight = FontWeight.SemiBold, fontSize = 20.sp, lineHeight = 28.sp, letterSpacing = (-0.2).sp),
    headlineSmall = TextStyle(fontFamily = fontFamily, fontWeight = FontWeight.SemiBold, fontSize = 18.sp, lineHeight = 24.sp, letterSpacing = (-0.09).sp),
    titleLarge = TextStyle(fontFamily = fontFamily, fontWeight = FontWeight.SemiBold, fontSize = 20.sp, lineHeight = 28.sp),
    titleMedium = TextStyle(fontFamily = fontFamily, fontWeight = FontWeight.SemiBold, fontSize = 16.sp, lineHeight = 24.sp),
    titleSmall = TextStyle(fontFamily = fontFamily, fontWeight = FontWeight.SemiBold, fontSize = 13.sp, lineHeight = 18.sp, letterSpacing = 0.13.sp),
    bodyLarge = TextStyle(fontFamily = fontFamily, fontWeight = FontWeight.Medium, fontSize = 16.sp, lineHeight = 24.sp),
    bodyMedium = TextStyle(fontFamily = fontFamily, fontWeight = FontWeight.Normal, fontSize = 15.sp, lineHeight = 22.sp),
    bodySmall = TextStyle(fontFamily = fontFamily, fontWeight = FontWeight.Normal, fontSize = 14.sp, lineHeight = 20.sp),
    labelLarge = TextStyle(fontFamily = fontFamily, fontWeight = FontWeight.SemiBold, fontSize = 15.sp, lineHeight = 22.sp),
    labelMedium = TextStyle(fontFamily = fontFamily, fontWeight = FontWeight.SemiBold, fontSize = 13.sp, lineHeight = 18.sp, letterSpacing = 0.13.sp),
    labelSmall = TextStyle(fontFamily = fontFamily, fontWeight = FontWeight.Medium, fontSize = 12.sp, lineHeight = 16.sp, letterSpacing = 0.24.sp),
)

@Composable
fun MoaLogTheme(content: @Composable () -> Unit) {
    val pretendard = FontFamily(
        Font(Res.font.pretendard_regular, FontWeight.Normal),
        Font(Res.font.pretendard_medium, FontWeight.Medium),
        Font(Res.font.pretendard_semibold, FontWeight.SemiBold),
        Font(Res.font.pretendard_bold, FontWeight.Bold),
        Font(Res.font.pretendard_bold, FontWeight.ExtraBold),
    )
    MaterialTheme(
        colorScheme = WarmLedgerColors,
        typography = warmLedgerTypography(pretendard),
        content = content,
    )
}
