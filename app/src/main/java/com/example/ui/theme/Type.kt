package com.example.ui.theme

import androidx.compose.material3.Typography
import androidx.compose.ui.text.ExperimentalTextApi
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontVariation
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp
import com.example.R

// ─── Brand fonts (bundled variable TTFs) ─────────────────────────────────────
// One variable file per family; weights are driven through the `wght` axis so a
// single .ttf covers the whole range. variationSettings is honoured on API 26+.
@OptIn(ExperimentalTextApi::class)
private fun montserrat(weight: FontWeight) =
    Font(R.font.montserrat, weight = weight,
        variationSettings = FontVariation.Settings(FontVariation.weight(weight.weight)))

@OptIn(ExperimentalTextApi::class)
private fun wixDisplay(weight: FontWeight) =
    Font(R.font.wixmadefordisplay, weight = weight,
        variationSettings = FontVariation.Settings(FontVariation.weight(weight.weight)))

/** Body / control text. */
val MontserratFamily = FontFamily(
    montserrat(FontWeight.Normal),
    montserrat(FontWeight.Medium),
    montserrat(FontWeight.SemiBold),
    montserrat(FontWeight.Bold),
)

/** Headings / titles. */
val WixDisplayFamily = FontFamily(
    wixDisplay(FontWeight.Medium),
    wixDisplay(FontWeight.SemiBold),
    wixDisplay(FontWeight.Bold),
    wixDisplay(FontWeight.Black),
)

// Set of Material typography styles to start with
val Typography =
  Typography(
    bodyLarge =
      TextStyle(
        fontFamily = FontFamily.Default,
        fontWeight = FontWeight.Normal,
        fontSize = 16.sp,
        lineHeight = 24.sp,
        letterSpacing = 0.5.sp,
      )
    /* Other default text styles to override
    titleLarge = TextStyle(
        fontFamily = FontFamily.Default,
        fontWeight = FontWeight.Normal,
        fontSize = 22.sp,
        lineHeight = 28.sp,
        letterSpacing = 0.sp
    ),
    labelSmall = TextStyle(
        fontFamily = FontFamily.Default,
        fontWeight = FontWeight.Medium,
        fontSize = 11.sp,
        lineHeight = 16.sp,
        letterSpacing = 0.5.sp
    )
    */
  )
