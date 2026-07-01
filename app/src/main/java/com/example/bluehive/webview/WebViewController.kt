package com.example.bluehive.webview

import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import android.view.KeyEvent
import android.view.MotionEvent
import org.mozilla.geckoview.GeckoSession
import org.mozilla.geckoview.GeckoView
import kotlin.math.abs

/**
 * WebViewController (Compose-friendly)
 *
 * Key fixes vs old version:
 * - NO hardcoded 4K dimensions
 * - Cursor position stored in *full-screen overlay pixels*
 * - Touch/hover converted to GeckoView-local coordinates using getLocationOnScreen()
 * - Single guarded movement loop (no DPAD repeat spam)
 * - JS seeking uses evaluateJS (not loadUri("javascript:..."))
 */
class WebViewController(
    private val geckoView: GeckoView,
    private val cursorSizePx: Float,
    private val setCursorPosPx: (x: Float, y: Float) -> Unit,
    private val setCursorVisible: (visible: Boolean) -> Unit,
    private val pulseClick: () -> Unit
) {

    companion object {
        private const val TAG = "WebViewController"
        private const val CLICK_DELAY_MS = 350L
        private const val CURSOR_FADE_DELAY_MS = 3500L
        private const val FRAME_MS = 16L
    }

    // Screen/overlay bounds (in PX) — set by Compose container
    private var overlayWidthPx: Float = 0f
    private var overlayHeightPx: Float = 0f

    // Cursor state (in overlay PX space)
    private var cursorX = 0f
    private var cursorY = 0f

    private var hotspotOffsetX = 0f
    private var hotspotOffsetY = 0f


    // Physics
    private var isUpPressed = false
    private var isDownPressed = false
    private var isLeftPressed = false
    private var isRightPressed = false

    private val minSpeed = 0.35f
    private val maxSpeed = 5.0f
    private val acceleration = 0.22f

    private var speedX = 0f
    private var speedY = 0f

    // Loop guards
    private val handler = Handler(Looper.getMainLooper())
    private var movementLoopRunning = false

    // Fade
    private val fadeHandler = Handler(Looper.getMainLooper())
    private var cursorVisible = false

    // Click timing
    private var lastClickTime = 0L

    // Site-specific
    private var currentSourceName: String? = null
    private var isVideoPlaying = false

    // Session
    private var currentSession: GeckoSession? = null

    private val fadeRunnable = Runnable {
        cursorVisible = false
        setCursorVisible(false)
    }

    private val movementRunnable = object : Runnable {
        override fun run() {
            val moving = isUpPressed || isDownPressed || isLeftPressed || isRightPressed

            // accelerate
            if (isUpPressed) {
                if (speedY > 0f) speedY = -minSpeed
                speedY -= acceleration
            }
            if (isDownPressed) {
                if (speedY < 0f) speedY = minSpeed
                speedY += acceleration
            }
            if (isLeftPressed) {
                if (speedX > 0f) speedX = -minSpeed
                speedX -= acceleration
            }
            if (isRightPressed) {
                if (speedX < 0f) speedX = minSpeed
                speedX += acceleration
            }

            // clamp
            speedX = speedX.coerceIn(-maxSpeed, maxSpeed)
            speedY = speedY.coerceIn(-maxSpeed, maxSpeed)

            // decelerate when not pressed
            if (!moving) {
                speedX *= 0.88f
                speedY *= 0.88f
            }

            // apply position (overlay bounds)
            if (overlayWidthPx > 0f && overlayHeightPx > 0f) {
                cursorX = (cursorX + speedX).coerceIn(0f, overlayWidthPx - cursorSizePx)
                cursorY = (cursorY + speedY).coerceIn(0f, overlayHeightPx - cursorSizePx)
                setCursorPosPx(cursorX, cursorY)
            }

            // hover when moving or still sliding
            if (moving || abs(speedX) > 0.5f || abs(speedY) > 0.5f) {
                dispatchHoverAtCursorCenter()
            }

            // stop loop if fully stopped
            val stillSliding = abs(speedX) > 0.08f || abs(speedY) > 0.08f
            if (moving || stillSliding) {
                handler.postDelayed(this, FRAME_MS)
            } else {
                movementLoopRunning = false
            }
        }
    }

    /** Call this from Compose when container size is known/changes (PX). */
    fun setOverlaySizePx(widthPx: Int, heightPx: Int) {
        overlayWidthPx = widthPx.toFloat()
        overlayHeightPx = heightPx.toFloat()

        // If this is the first time, center cursor
        if (!didApplyInitialPosition && overlayWidthPx > 0f && overlayHeightPx > 0f) {
            cursorX = (overlayWidthPx / 2f) - (cursorSizePx / 2f) + initialOffsetX
            cursorY = (overlayHeightPx / 2f) - (cursorSizePx / 2f) + initialOffsetY

            // clamp to bounds
            cursorX = cursorX.coerceIn(0f, overlayWidthPx - cursorSizePx)
            cursorY = cursorY.coerceIn(0f, overlayHeightPx - cursorSizePx)

            setCursorPosPx(cursorX, cursorY)
            didApplyInitialPosition = true

            Log.d(TAG, "Initial cursor pos applied: ($cursorX,$cursorY) offset=($initialOffsetX,$initialOffsetY)")
        }
    }

    fun setSession(session: GeckoSession?) {
        currentSession = session
    }

    fun setSourceName(sourceName: String?) {
        currentSourceName = sourceName
        isVideoPlaying = false
    }

    fun cleanup() {
        fadeHandler.removeCallbacks(fadeRunnable)
        handler.removeCallbacks(movementRunnable)
        movementLoopRunning = false
    }


    private fun showSiteControlsByHover() {
        // Use cursor center so it feels consistent with where you are “pointing”
        val (cx, cy) = cursorCenterOverlayPx()
        val (x, y) = overlayToGeckoLocal(cx, cy)

        val t = SystemClock.uptimeMillis()
        val hover = MotionEvent.obtain(t, t, MotionEvent.ACTION_HOVER_MOVE, x, y, 0).apply {
            source = android.view.InputDevice.SOURCE_MOUSE
        }
        try {
            geckoView.dispatchGenericMotionEvent(hover)
        } finally {
            hover.recycle()
        }
    }



    fun handleKeyEvent(event: KeyEvent): Boolean {
        when (event.action) {
            KeyEvent.ACTION_DOWN -> {
                // prevent DPAD repeat from re-triggering spam
                val isRepeat = event.repeatCount > 0

                when (event.keyCode) {
                    KeyEvent.KEYCODE_CHANNEL_UP -> {
                        showSiteControlsByHover()    // ✅ wake the player UI, does not work on vid easy sites
                        handleChannelSeek(+10)
                        return true
                    }
                    KeyEvent.KEYCODE_CHANNEL_DOWN -> {
                        showSiteControlsByHover() // ✅ wake the player UI, does not work on vid easy sites
                        handleChannelSeek(-10)
                        return true
                    }

                    // Fire TV remote: dedicated transport keys. The onn remote uses
                    // CHANNEL_UP/DOWN (above) and has no transport buttons, so these
                    // never collide. ⏪/⏩ reuse the channel seek; ▶❚❚ drives the page's
                    // <video> element directly via JS, the same mechanism as seekVideo.
                    KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE -> {
                        videoCommand("if (v.paused) { v.play(); } else { v.pause(); }")
                        Log.d(TAG, "media key: play/pause toggle")
                        return true
                    }
                    KeyEvent.KEYCODE_MEDIA_PLAY -> {
                        videoCommand("v.play();")
                        return true
                    }
                    KeyEvent.KEYCODE_MEDIA_PAUSE -> {
                        videoCommand("v.pause();")
                        return true
                    }
                    KeyEvent.KEYCODE_MEDIA_FAST_FORWARD -> {
                        showSiteControlsByHover()
                        handleChannelSeek(+10)
                        return true
                    }
                    KeyEvent.KEYCODE_MEDIA_REWIND -> {
                        showSiteControlsByHover()
                        handleChannelSeek(-10)
                        return true
                    }

                    KeyEvent.KEYCODE_DPAD_UP -> {
                        isUpPressed = true
                        showCursor()
                        if (!isRepeat) startMovementLoop()
                        return true
                    }
                    KeyEvent.KEYCODE_DPAD_DOWN -> {
                        isDownPressed = true
                        showCursor()
                        if (!isRepeat) startMovementLoop()
                        return true
                    }
                    KeyEvent.KEYCODE_DPAD_LEFT -> {
                        isLeftPressed = true
                        showCursor()
                        if (!isRepeat) startMovementLoop()
                        return true
                    }
                    KeyEvent.KEYCODE_DPAD_RIGHT -> {
                        isRightPressed = true
                        showCursor()
                        if (!isRepeat) startMovementLoop()
                        return true
                    }

                    KeyEvent.KEYCODE_DPAD_CENTER,
                    KeyEvent.KEYCODE_ENTER -> {
                        handleCenterClick()
                        resetFadeTimer()
                        return true
                    }
                }
            }

            KeyEvent.ACTION_UP -> {
                when (event.keyCode) {
                    KeyEvent.KEYCODE_DPAD_UP -> { isUpPressed = false; resetFadeTimer(); return true }
                    KeyEvent.KEYCODE_DPAD_DOWN -> { isDownPressed = false; resetFadeTimer(); return true }
                    KeyEvent.KEYCODE_DPAD_LEFT -> { isLeftPressed = false; resetFadeTimer(); return true }
                    KeyEvent.KEYCODE_DPAD_RIGHT -> { isRightPressed = false; resetFadeTimer(); return true }

                    // Eat the key-up for the Fire TV transport keys we handled on the
                    // way down, so the system doesn't double-handle them.
                    KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE,
                    KeyEvent.KEYCODE_MEDIA_PLAY,
                    KeyEvent.KEYCODE_MEDIA_PAUSE,
                    KeyEvent.KEYCODE_MEDIA_FAST_FORWARD,
                    KeyEvent.KEYCODE_MEDIA_REWIND -> return true
                }
            }
        }
        return false
    }

    private fun startMovementLoop() {
        if (movementLoopRunning) return
        movementLoopRunning = true
        handler.post(movementRunnable)
    }

    private fun showCursor() {
        if (!cursorVisible) {
            cursorVisible = true
            setCursorVisible(true)
        }
        fadeHandler.removeCallbacks(fadeRunnable)
    }

    private fun resetFadeTimer() {
        fadeHandler.removeCallbacks(fadeRunnable)
        fadeHandler.postDelayed(fadeRunnable, CURSOR_FADE_DELAY_MS)
    }

    // ------------------------------------------------------------
    // Coordinate conversion (THIS is the big fix)
    // ------------------------------------------------------------

    fun setHotspotOffsetPx(x: Float, y: Float) {
        hotspotOffsetX = x
        hotspotOffsetY = y
    }

    private fun cursorCenterOverlayPx(): Pair<Float, Float> {
        val cx = cursorX + cursorSizePx / 2f + hotspotOffsetX
        val cy = cursorY + cursorSizePx / 2f + hotspotOffsetY
        return cx to cy
    }




    private var initialOffsetX = 0f
    private var initialOffsetY = 0f
    private var didApplyInitialPosition = false

    fun setInitialCursorOffsetPx(x: Float, y: Float) {
        initialOffsetX = x
        initialOffsetY = y
    }



    /**
     * Convert overlay/screen-space point (Compose overlay px) into GeckoView-local px
     * using GeckoView.getLocationOnScreen().
     */
    private fun overlayToGeckoLocal(xOverlay: Float, yOverlay: Float): Pair<Float, Float> {
        val loc = IntArray(2)
        geckoView.getLocationOnScreen(loc)

        // Compose overlay (Box) is effectively screen-space; GeckoView-local starts at its own top-left.
        val localX = (xOverlay - loc[0]).coerceIn(0f, geckoView.width.toFloat())
        val localY = (yOverlay - loc[1]).coerceIn(0f, geckoView.height.toFloat())
        return localX to localY
    }

    private fun dispatchHoverAtCursorCenter() {
        val (cx, cy) = cursorCenterOverlayPx()
        val (x, y) = overlayToGeckoLocal(cx, cy)

        val t = SystemClock.uptimeMillis()
        val hover = MotionEvent.obtain(t, t, MotionEvent.ACTION_HOVER_MOVE, x, y, 0)
        try {
            geckoView.dispatchGenericMotionEvent(hover)
        } finally {
            hover.recycle()
        }
    }

    private fun dispatchClickAtScreenCenter() {
        val now = System.currentTimeMillis()
        if (now - lastClickTime < CLICK_DELAY_MS) return
        lastClickTime = now

        com.example.bluehive.BlueHiveApplication.playWebViewerClickSound()
        pulseClick()

        // TRUE screen center in overlay space
        val centerX = overlayWidthPx / 2f
        val centerY = overlayHeightPx / 2f

        val (x, y) = overlayToGeckoLocal(centerX, centerY)

        val downTime = SystemClock.uptimeMillis()
        val down = MotionEvent.obtain(downTime, downTime, MotionEvent.ACTION_DOWN, x, y, 0)
        val up = MotionEvent.obtain(downTime, downTime + 50, MotionEvent.ACTION_UP, x, y, 0)

        try {
            geckoView.dispatchTouchEvent(down)
            geckoView.dispatchTouchEvent(up)
            Log.d(TAG, "Center click @ screen=($centerX,$centerY) gecko=($x,$y)")
        } finally {
            down.recycle()
            up.recycle()
        }
    }


    private fun dispatchClickAtCursorPosition() {
        val now = System.currentTimeMillis()
        if (now - lastClickTime < CLICK_DELAY_MS) return
        lastClickTime = now

        com.example.bluehive.BlueHiveApplication.playWebViewerClickSound()
        pulseClick()

        // Click at CURSOR position (with hotspot offset applied)
        val (cx, cy) = cursorCenterOverlayPx()
        val (x, y) = overlayToGeckoLocal(cx, cy)

        val downTime = SystemClock.uptimeMillis()
        val down = MotionEvent.obtain(downTime, downTime, MotionEvent.ACTION_DOWN, x, y, 0)
        val up = MotionEvent.obtain(downTime, downTime + 50, MotionEvent.ACTION_UP, x, y, 0)

        try {
            geckoView.dispatchTouchEvent(down)
            geckoView.dispatchTouchEvent(up)
            Log.d(TAG, "Cursor click @ overlay=($cx,$cy) gecko=($x,$y)")
        } finally {
            down.recycle()
            up.recycle()
        }
    }


    private fun handleCenterClick() {
        if (!cursorVisible) {
            // Cursor faded → click screen center
            if (currentSourceName == "VidEasy") {
                vidEasyCenterClick()  // VidEasy special behavior
            } else {
                dispatchClickAtScreenCenter()  // Normal center click
            }
        } else {
            // Cursor visible → click at cursor position
            dispatchClickAtCursorPosition()
        }
    }

    // ------------------------------------------------------------
    // Seeking / JS
    // ------------------------------------------------------------

    private fun handleChannelSeek(seconds: Int) {
        // Hover doesn’t help JS; we keep it minimal and reliable.
        seekVideo(seconds)
        Log.d(TAG, "Channel seek: $seconds")
    }

    private fun seekVideo(seconds: Int) {
        currentSession?.let { session ->
            val js = "javascript:(function() { var v = document.querySelector('video'); if(v) v.currentTime += $seconds; })();"
            session.loadUri(js)
            Log.d(TAG, "Seeking video by $seconds seconds")
        }
    }

    // Run a small JS snippet against the page's <video> element. Used by the Fire
    // TV transport keys to play/pause directly. Mirrors seekVideo's
    // loadUri("javascript:…") approach so it works on the same sites seeking does.
    private fun videoCommand(jsBody: String) {
        currentSession?.let { session ->
            session.loadUri("javascript:(function(){ var v=document.querySelector('video'); if(v){ $jsBody } })();")
        }
    }

    // ------------------------------------------------------------
    // VidEasy special-case (kept, but now uses correct conversion)
    // ------------------------------------------------------------

    private fun vidEasyCenterClick() {
        val now = System.currentTimeMillis()
        if (now - lastClickTime < CLICK_DELAY_MS) return
        lastClickTime = now
        pulseClick()

        // Choose a target in GeckoView-local space (percent-based of GeckoView size).
        val targetX: Float
        val targetY: Float

        if (!isVideoPlaying) {
            targetX = geckoView.width * 0.5f
            targetY = geckoView.height * 0.5f
            Log.d(TAG, "VidEasy: initial play @ center")
        } else {
            targetX = geckoView.width * 0.243f
            targetY = geckoView.height * 0.369f
            Log.d(TAG, "VidEasy: pause/play control click")
        }

        val downTime = SystemClock.uptimeMillis()
        val down = MotionEvent.obtain(downTime, downTime, MotionEvent.ACTION_DOWN, targetX, targetY, 0)
        val up = MotionEvent.obtain(downTime, downTime + 50, MotionEvent.ACTION_UP, targetX, targetY, 0)

        try {
            geckoView.dispatchTouchEvent(down)
            geckoView.dispatchTouchEvent(up)

            if (!isVideoPlaying) {
                Handler(Looper.getMainLooper()).postDelayed({ isVideoPlaying = true }, 1000)
            }
        } finally {
            down.recycle()
            up.recycle()
        }
    }
}
