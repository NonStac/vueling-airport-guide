package com.nonstac.airportguide.ui.theme

import androidx.compose.ui.graphics.Color

// Vueling Brand Colors (Approximations - get official values if possible)
val VuelingYellow = Color(0xFFFFEC00)
val VuelingGray = Color(0xFF666666)
val VuelingDarkGray = Color(0xFF333333)
val VuelingLightGray = Color(0xFFF5F5F5)
val VuelingBlue = Color(0xFF0073AE) // Accent color (optional)

// Semantic Colors (Material 3) - Light Theme
val PrimaryLight = VuelingGray
val OnPrimaryLight = Color.White
val PrimaryContainerLight = VuelingYellow
val OnPrimaryContainerLight = VuelingDarkGray
val SecondaryLight = VuelingDarkGray
val OnSecondaryLight = Color.White
val SecondaryContainerLight = VuelingLightGray
val OnSecondaryContainerLight = VuelingDarkGray
val TertiaryLight = VuelingBlue
val OnTertiaryLight = Color.White
val TertiaryContainerLight = Color(0xFFDCEEFF) // Light blue accent container
val OnTertiaryContainerLight = VuelingDarkGray
val ErrorLight = Color(0xFFB00020)
val OnErrorLight = Color.White
val ErrorContainerLight = Color(0xFFFCD8DF)
val OnErrorContainerLight = Color(0xFFB00020)
val BackgroundLight = Color.White
val OnBackgroundLight = VuelingDarkGray
val SurfaceLight = VuelingLightGray // Cards, Sheets background
val OnSurfaceLight = VuelingDarkGray
val SurfaceVariantLight = Color(0xFFE0E0E0) // Outlined components, dividers
val OnSurfaceVariantLight = VuelingGray
val OutlineLight = VuelingGray

// Semantic Colors (Material 3) - Dark Theme (Example, adjust as needed)
val PrimaryDark = VuelingLightGray
val OnPrimaryDark = VuelingDarkGray
val PrimaryContainerDark = VuelingGray
val OnPrimaryContainerDark = VuelingYellow
val SecondaryDark = VuelingLightGray
val OnSecondaryDark = VuelingDarkGray
val SecondaryContainerDark = VuelingDarkGray
val OnSecondaryContainerDark = VuelingLightGray
val TertiaryDark = VuelingYellow
val OnTertiaryDark = VuelingDarkGray
val TertiaryContainerDark = Color(0xFF403A00) // Darker yellow container
val OnTertiaryContainerDark = VuelingYellow
val ErrorDark = Color(0xFFCF6679)
val OnErrorDark = VuelingDarkGray
val ErrorContainerDark = Color(0xFFB00020)
val OnErrorContainerDark = Color(0xFFFCD8DF)
val BackgroundDark = VuelingDarkGray
val OnBackgroundDark = VuelingLightGray
val SurfaceDark = Color(0xFF212121) // Dark Cards, Sheets background
val OnSurfaceDark = VuelingLightGray
val SurfaceVariantDark = Color(0xFF424242) // Darker Outlined components, dividers
val OnSurfaceVariantDark = VuelingLightGray
val OutlineDark = VuelingGray

// Map Specific Colors
val NodeColorDefault = VuelingGray
val NodeColorEntrance = Color(0xFF4CAF50) // Green
val NodeColorGate = Color(0xFF2196F3) // Blue
val NodeColorBathroom = Color(0xFFFF9800) // Orange
val NodeColorExit = Color(0xFFE53935) // Red
val NodeColorStair = Color(0xFF607D8B) // Grey
val NodeColorConnection = Color(0xFF607D8B) // Grey
val NodeColorCurrentLocation = VuelingBlue
val NodeColorDestination = VuelingYellow
val EdgeColorDefault = VuelingGray.copy(alpha = 0.5f)
val PathColor = VuelingBlue
val StairsEdgeColor = VuelingGray.copy(alpha = 0.5f) // Could be dashed later
val BlackoutOverlay = Color.Black.copy(alpha = 0.6f)