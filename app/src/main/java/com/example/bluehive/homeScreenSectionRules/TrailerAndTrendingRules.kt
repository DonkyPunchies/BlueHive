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

// Phase offset for the TRAILER carousel. Both carousels subscribe to the same
// shared clock tick; advancing them on the same tick ran two full-panel slide
// animations on the exact same frames every cycle. The trailer section now
// waits half a period after each tick — trending slides on the beat, trailers
// on the half-beat — halving the peak per-frame animation load on weak GPUs
// (2 GB onn boxes / Firesticks). Cadence per section is unchanged: still one
// advance per 6s tick.
const val CAROUSEL_TRAILER_PHASE_OFFSET_MS        = CAROUSEL_AUTO_CYCLE_DELAY_MS / 2

// ─────────────────────────────────────────────────────────────────────────────
//  Shared image specs — the EXACT Coil request parameters for both carousels.
//
//  The visible UI, the runtime ±RADIUS window preloaders, AND AppWarmup's
//  splash prefetch must all build byte-identical requests (same URL transform,
//  same pixel size, same memoryCacheKey). If any of them drift, the preloaded
//  bytes land under a different disk/memory key than what the UI asks for and
//  every image downloads TWICE — which is exactly the bug these helpers fix.
//  Never build a trending/trailer ImageRequest by hand; use these.
// ─────────────────────────────────────────────────────────────────────────────

const val TRENDING_BACKDROP_PX_W = 848
const val TRENDING_BACKDROP_PX_H = 438
const val TRAILER_THUMB_PX_W     = 488
const val TRAILER_THUMB_PX_H     = 273

/** Backend bakes w1280 into trending backdrop URLs; the carousel shows w780. */
fun trendingBackdropUrl(raw: String) = raw.replace("/w1280/", "/w780/")

fun trendingBackdropMemoryKey(trendingId: Int) = "trending_backdrop_$trendingId"

/** Trailer thumbs render small — w500 is plenty and a fraction of the bytes. */
fun trailerThumbUrl(raw: String) =
    raw.replace("/w1280/", "/w500/").replace("/original/", "/w500/")

fun trailerThumbMemoryKey(trailerId: Int) =
    "trailer_thumb_${trailerId}_${TRAILER_THUMB_PX_W}x${TRAILER_THUMB_PX_H}"

// Single shared clock — both carousels subscribe to this instead of
// running independent delay loops. When the parent increments this,
// both sections advance on the exact same frame.
val LocalCarouselCycleTick = staticCompositionLocalOf { 0L }