package com.ped.mobile.ui

import androidx.compose.material3.Typography
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp
import com.ped.mobile.R

// Inter 4.1, bundled under the SIL Open Font License; no network font provider.
internal val PedFontFamily = FontFamily(
    Font(R.font.inter_regular, FontWeight.Normal),
    Font(R.font.inter_medium, FontWeight.Medium),
    Font(R.font.inter_semibold, FontWeight.SemiBold),
    Font(R.font.inter_bold, FontWeight.Bold)
)

private fun style(size: Int, line: Int, weight: FontWeight = FontWeight.Normal) = TextStyle(
    fontFamily = PedFontFamily, fontWeight = weight,
    fontSize = size.sp, lineHeight = line.sp, letterSpacing = 0.sp
)

internal val PedTypography = Typography(
    displayLarge = style(40, 48, FontWeight.SemiBold),
    displayMedium = style(36, 44, FontWeight.SemiBold),
    displaySmall = style(32, 40, FontWeight.SemiBold),
    headlineLarge = style(28, 36, FontWeight.SemiBold),
    headlineMedium = style(26, 34, FontWeight.SemiBold),
    headlineSmall = style(24, 32, FontWeight.SemiBold),
    titleLarge = style(20, 28, FontWeight.SemiBold),
    titleMedium = style(18, 26, FontWeight.SemiBold),
    titleSmall = style(16, 24, FontWeight.SemiBold),
    bodyLarge = style(16, 24, FontWeight.Normal),
    bodyMedium = style(14, 22, FontWeight.Normal),
    bodySmall = style(12, 18, FontWeight.Normal),
    labelLarge = style(16, 24, FontWeight.Medium),
    labelMedium = style(14, 20, FontWeight.Medium),
    labelSmall = style(12, 18, FontWeight.Medium)
)

// Distinct I/l/1 and a slashed zero help operators verify scanned references.
internal val PedChassisTextStyle = style(18, 26, FontWeight.SemiBold).copy(
    fontFeatureSettings = "'tnum', 'zero', 'ss02'"
)
