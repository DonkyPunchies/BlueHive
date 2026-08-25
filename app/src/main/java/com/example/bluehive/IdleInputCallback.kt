package com.example.bluehive

import android.view.KeyEvent
import android.view.MotionEvent
import android.view.Window

/**
 * Reports every key and touch event to [IdleWatcher], then gets out of the way.
 *
 * Installed on each Activity's Window from BlueHiveApplication.onActivityCreated,
 * so one hook covers every screen in the app — including Compose screens, both
 * ExoPlayer activities and GeckoView, none of which would be reached by an
 * `onUserInteraction()` override without editing them individually.
 *
 * PURE OBSERVER. Every method delegates to the wrapped callback and returns its
 * answer unchanged; nothing is consumed, reordered or swallowed. Focus traversal,
 * D-pad routing and back handling therefore behave exactly as they did before this
 * existed, which is the property that makes it safe to install app-wide.
 *
 * WRAPS WHATEVER IS ALREADY THERE rather than replacing it. By onActivityCreated
 * the framework (and, for AppCompat activities, AppCompatDelegate) has already
 * installed its own callback, and that one does real work — panel handling, action
 * modes, the options menu. Dropping it would break those in ways that surface
 * nowhere near this file.
 *
 * Extends [Window.Callback] by delegation via an explicit field rather than Kotlin
 * class delegation, because the interface gains methods across API levels and the
 * `by` syntax would bind them at compile time against whichever SDK this module is
 * built with.
 */
class IdleInputCallback(private val delegate: Window.Callback) : Window.Callback {

    // ── The two that matter ───────────────────────────────────────────────────

    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        // ACTION_DOWN only. A press produces a down and an up, and long-presses
        // produce a stream of repeats — counting all of them would be identical in
        // effect and three times the volume. The write is two volatile stores, so
        // this is about honesty in the logs rather than cost.
        if (event.action == KeyEvent.ACTION_DOWN) IdleWatcher.noteInteraction()
        return delegate.dispatchKeyEvent(event)
    }

    override fun dispatchTouchEvent(event: MotionEvent): Boolean {
        if (event.action == MotionEvent.ACTION_DOWN) IdleWatcher.noteInteraction()
        return delegate.dispatchTouchEvent(event)
    }

    // ── Everything else: straight through ─────────────────────────────────────
    //
    // Deliberately NOT reporting interaction from these. dispatchTrackballEvent and
    // dispatchGenericMotionEvent can fire from sources a person is not driving
    // (a connected controller settling, a mouse jiggle from an HDMI-CEC device),
    // and treating those as presence is how the timer quietly stops working.

    override fun dispatchKeyShortcutEvent(event: KeyEvent): Boolean =
        delegate.dispatchKeyShortcutEvent(event)

    override fun dispatchTrackballEvent(event: MotionEvent): Boolean =
        delegate.dispatchTrackballEvent(event)

    override fun dispatchGenericMotionEvent(event: MotionEvent): Boolean =
        delegate.dispatchGenericMotionEvent(event)

    override fun dispatchPopulateAccessibilityEvent(event: android.view.accessibility.AccessibilityEvent): Boolean =
        delegate.dispatchPopulateAccessibilityEvent(event)

    override fun onCreatePanelView(featureId: Int): android.view.View? =
        delegate.onCreatePanelView(featureId)

    override fun onCreatePanelMenu(featureId: Int, menu: android.view.Menu): Boolean =
        delegate.onCreatePanelMenu(featureId, menu)

    override fun onPreparePanel(featureId: Int, view: android.view.View?, menu: android.view.Menu): Boolean =
        delegate.onPreparePanel(featureId, view, menu)

    override fun onMenuOpened(featureId: Int, menu: android.view.Menu): Boolean =
        delegate.onMenuOpened(featureId, menu)

    override fun onMenuItemSelected(featureId: Int, item: android.view.MenuItem): Boolean =
        delegate.onMenuItemSelected(featureId, item)

    override fun onWindowAttributesChanged(attrs: android.view.WindowManager.LayoutParams) =
        delegate.onWindowAttributesChanged(attrs)

    override fun onContentChanged() = delegate.onContentChanged()

    override fun onWindowFocusChanged(hasFocus: Boolean) = delegate.onWindowFocusChanged(hasFocus)

    override fun onAttachedToWindow() = delegate.onAttachedToWindow()

    override fun onDetachedFromWindow() = delegate.onDetachedFromWindow()

    override fun onPanelClosed(featureId: Int, menu: android.view.Menu) =
        delegate.onPanelClosed(featureId, menu)

    @Deprecated("Deprecated in Window.Callback", ReplaceWith(""))
    @Suppress("DEPRECATION")
    override fun onSearchRequested(): Boolean = delegate.onSearchRequested()

    override fun onSearchRequested(searchEvent: android.view.SearchEvent?): Boolean =
        delegate.onSearchRequested(searchEvent)

    override fun onWindowStartingActionMode(callback: android.view.ActionMode.Callback?): android.view.ActionMode? =
        delegate.onWindowStartingActionMode(callback)

    override fun onWindowStartingActionMode(
        callback: android.view.ActionMode.Callback?,
        type: Int,
    ): android.view.ActionMode? = delegate.onWindowStartingActionMode(callback, type)

    override fun onActionModeStarted(mode: android.view.ActionMode?) =
        delegate.onActionModeStarted(mode)

    override fun onActionModeFinished(mode: android.view.ActionMode?) =
        delegate.onActionModeFinished(mode)
}
