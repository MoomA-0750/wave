package xyz.mooma0750.wave.ui

import androidx.compose.material3.Typography
import androidx.compose.ui.text.font.FontWeight

/**
 * 文字の役割。Expressive の強調（…Emphasized）は既定では Medium（500）で、端末の文字では普通の太さとほとんど見分けられない。
 * そこで強調を、見出し（display・headline・title）は Bold、本文・ラベルは SemiBold にする（画面では fontWeight を直書きせず、強調はこちらを使う）。
 */
val WaveTypography: Typography =
    Typography().let { base ->
        Typography(
            displayLarge = base.displayLarge,
            displayMedium = base.displayMedium,
            displaySmall = base.displaySmall,
            headlineLarge = base.headlineLarge,
            headlineMedium = base.headlineMedium,
            headlineSmall = base.headlineSmall,
            titleLarge = base.titleLarge,
            titleMedium = base.titleMedium,
            titleSmall = base.titleSmall,
            bodyLarge = base.bodyLarge,
            bodyMedium = base.bodyMedium,
            bodySmall = base.bodySmall,
            labelLarge = base.labelLarge,
            labelMedium = base.labelMedium,
            labelSmall = base.labelSmall,
            displayLargeEmphasized = base.displayLargeEmphasized.copy(fontWeight = FontWeight.Bold),
            displayMediumEmphasized = base.displayMediumEmphasized.copy(fontWeight = FontWeight.Bold),
            displaySmallEmphasized = base.displaySmallEmphasized.copy(fontWeight = FontWeight.Bold),
            headlineLargeEmphasized = base.headlineLargeEmphasized.copy(fontWeight = FontWeight.Bold),
            headlineMediumEmphasized = base.headlineMediumEmphasized.copy(fontWeight = FontWeight.Bold),
            headlineSmallEmphasized = base.headlineSmallEmphasized.copy(fontWeight = FontWeight.Bold),
            titleLargeEmphasized = base.titleLargeEmphasized.copy(fontWeight = FontWeight.Bold),
            titleMediumEmphasized = base.titleMediumEmphasized.copy(fontWeight = FontWeight.Bold),
            titleSmallEmphasized = base.titleSmallEmphasized.copy(fontWeight = FontWeight.Bold),
            bodyLargeEmphasized = base.bodyLargeEmphasized.copy(fontWeight = FontWeight.SemiBold),
            bodyMediumEmphasized = base.bodyMediumEmphasized.copy(fontWeight = FontWeight.SemiBold),
            bodySmallEmphasized = base.bodySmallEmphasized.copy(fontWeight = FontWeight.SemiBold),
            labelLargeEmphasized = base.labelLargeEmphasized.copy(fontWeight = FontWeight.SemiBold),
            labelMediumEmphasized = base.labelMediumEmphasized.copy(fontWeight = FontWeight.SemiBold),
            labelSmallEmphasized = base.labelSmallEmphasized.copy(fontWeight = FontWeight.SemiBold),
        )
    }
