package com.example.bluehive.homeScreenSectionRules

// ─────────────────────────────────────────────────────────────────────────────
//  Shared carousel timing rules for Trailer and Trending sections.
//  Edit here to change both sections simultaneously, or override per-section
//  by copying the constant locally and ignoring this file for that section.
// ─────────────────────────────────────────────────────────────────────────────


import androidx.compose.runtime.staticCompositionLocalOf

const val CAROUSEL_AUTO_CYCLE_DELAY_MS            = 6000L
const val CAROUSEL_AUTO_CYCLE_ANIMATION_MS        = 1000
const val CAROUSEL_MANUAL_NAVIGATION_ANIMATION_MS = 250
const val CAROUSEL_NAVIGATION_RATE_MS             = 150L
const val CAROUSEL_PRELOAD_RADIUS                 = 3

// Single shared clock — both carousels subscribe to this instead of
// running independent delay loops. When the parent increments this,
// both sections advance on the exact same frame.
val LocalCarouselCycleTick = staticCompositionLocalOf { 0L }