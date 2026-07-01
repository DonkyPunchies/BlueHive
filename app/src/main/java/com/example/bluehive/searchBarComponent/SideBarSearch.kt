package com.example.bluehive.searchBarComponent

import android.content.Context
import android.os.Build
import android.text.InputType
import android.util.TypedValue
import android.view.Gravity
import android.view.KeyEvent
import android.view.View
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.window.Popup
import androidx.compose.ui.window.PopupProperties
import com.example.bluehive.R
import androidx.compose.runtime.remember
import androidx.compose.ui.unit.IntRect
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.window.PopupPositionProvider

// ══════════════════════════════════════════════════════════════════════════════
//  CUSTOMIZE THE SEARCH BAR HERE
//  X is auto-centered on screen. Everything else is a knob below.
// ══════════════════════════════════════════════════════════════════════════════
private val OVERLAY_OFFSET_Y      = 230.dp              // ← vertical position (distance from top)
val         OVERLAY_WIDTH         = 425.dp             // ← search bar width
val         OVERLAY_HEIGHT        = 56.dp              // ← search bar height
private val OVERLAY_CORNER_RADIUS = 7.dp              // ← rounded corner radius
private val OVERLAY_BORDER_WIDTH  = 1.5.dp             // ← border thickness
private val OVERLAY_BORDER_COLOR  = Color(0xFF464646)  // ← border color (blue accent)
private val OVERLAY_BG_TOP        = Color(0xFF222222)  // ← background gradient top
private val OVERLAY_BG_BOTTOM     = Color(0xFF141414)  // ← background gradient bottom
private const val OVERLAY_TEXT_SP = 16f                // ← input text size (sp)
private const val OVERLAY_HINT    = "Search…"          // ← placeholder text
// ══════════════════════════════════════════════════════════════════════════════

// Fire TV's system keyboard is a full-screen overlay with its own input row, so
// our styled bar only ever sits behind it. Used to drop the bar there while
// keeping it on Android TV (onn box), where the keyboard is a bottom strip.
private fun isFireTvDevice(ctx: Context): Boolean =
    ctx.packageManager.hasSystemFeature("amazon.hardware.fire_tv") ||
            Build.MANUFACTURER.equals("Amazon", ignoreCase = true)

@Composable
fun SidebarSearchOverlay(
    onSubmit:  (String) -> Unit,
    onDismiss: () -> Unit,
) {
    // AFTER
    val density = LocalDensity.current
    val offsetY = with(density) { OVERLAY_OFFSET_Y.roundToPx() }
    val shape   = RoundedCornerShape(OVERLAY_CORNER_RADIUS)

    val context  = LocalContext.current
    val isFireTv = remember { isFireTvDevice(context) }

    // Center on X against the FULL window, not the sidebar's narrow parent bounds
    // (that's why TopCenter was landing on the left). windowSize.width = whole
    // screen, so this stays centered on any TV size.
    val positionProvider = remember(offsetY) {
        object : PopupPositionProvider {
            override fun calculatePosition(
                anchorBounds: IntRect,
                windowSize: IntSize,
                layoutDirection: LayoutDirection,
                popupContentSize: IntSize,
            ): IntOffset = IntOffset(
                x = (windowSize.width - popupContentSize.width) / 2,  // ← true horizontal center
                y = offsetY,                                          // ← your OVERLAY_OFFSET_Y
            )
        }
    }

    Popup(
        popupPositionProvider = positionProvider,
        onDismissRequest = onDismiss,                    // BACK (IME down) lands here
        properties = PopupProperties(
            focusable = true,
            dismissOnBackPress = true,
            dismissOnClickOutside = false,
        ),
    ) {
        if (isFireTv) {
            // Fire TV: no visible bar. Just a 1dp invisible EditText for the
            // Amazon full-screen keyboard to type into — it's the only visible UI.
            AndroidView(
                modifier = Modifier.size(1.dp),
                factory = { ctx -> buildSearchField(ctx, onSubmit, onDismiss) },
            )
        } else {
            // onn box (Android TV): styled bar above the bottom-strip keyboard.
            Box(
                modifier = Modifier
                    .width(OVERLAY_WIDTH)
                    .height(OVERLAY_HEIGHT)
                    .background(
                        brush = Brush.verticalGradient(listOf(OVERLAY_BG_TOP, OVERLAY_BG_BOTTOM)),
                        shape = shape,
                    )
                    .border(OVERLAY_BORDER_WIDTH, OVERLAY_BORDER_COLOR, shape)
                    .padding(horizontal = 14.dp),
                contentAlignment = Alignment.CenterStart,
            ) {
                Row(
                    modifier = Modifier.fillMaxSize(),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Image(
                        painter = painterResource(R.drawable.magnifying_glass_raw_image),
                        contentDescription = null,
                        modifier = Modifier.size(18.dp),
                    )
                    Spacer(Modifier.width(10.dp))
                    AndroidView(
                        modifier = Modifier.weight(1f).fillMaxHeight(),
                        factory = { ctx -> buildSearchField(ctx, onSubmit, onDismiss) },
                    )
                }
            }
        }
    }
}

// Builds the system-keyboard input field. The keyboard is raised by RETRYING
// showSoftInput until the IMM actually serves the field — a single early call
// gets dropped ("not served"), which is why it only opened after a manual click.
private fun buildSearchField(
    ctx:       Context,
    onSubmit:  (String) -> Unit,
    onDismiss: () -> Unit,
): SearchEditText {
    val et = SearchEditText(ctx)

    et.imeOptions = EditorInfo.IME_ACTION_SEARCH or
            EditorInfo.IME_FLAG_NO_FULLSCREEN or
            EditorInfo.IME_FLAG_NO_EXTRACT_UI
    et.inputType = InputType.TYPE_CLASS_TEXT
    et.isSingleLine = true
    et.gravity = Gravity.CENTER_VERTICAL
    et.background = null                          // panel draws the surface
    et.setTextColor(0xFFFFFFFF.toInt())
    et.setHintTextColor(0xFF777777.toInt())
    et.hint = OVERLAY_HINT
    et.setTextSize(TypedValue.COMPLEX_UNIT_SP, OVERLAY_TEXT_SP)
    et.isFocusableInTouchMode = true
    et.isFocusable = true

    et.setOnEditorActionListener { v, actionId, _ ->
        if (actionId == EditorInfo.IME_ACTION_SEARCH) {
            // Same sticky-keyboard reason as the BACK handler: SHOW_FORCED won't
            // come down on its own when we navigate away, so pull the IME down
            // explicitly before submitting — otherwise the Amazon keyboard lingers
            // as an overlay on top of the search screen.
            val imm = ctx.getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager
            imm.hideSoftInputFromWindow(v.windowToken, 0)
            onSubmit(v.text.toString())
            true
        } else false
    }
    et.onImeBack = onDismiss                       // BACK while IME is up → close + (sidebar) refocus

    // Fire TV's IME ignores SHOW_IMPLICIT — the remote registers as a hardware
    // keyboard, so the system holds the on-screen keyboard back until you click
    // the field a second time. SHOW_FORCED overrides that and raises the Amazon
    // keyboard on the first attach. The onn box keeps SHOW_IMPLICIT, which already
    // works there and is the better-behaved (non-deprecated) flag.
    @Suppress("DEPRECATION")  // SHOW_FORCED is deprecated but is the only flag Fire TV honors
    val showFlag = if (isFireTvDevice(ctx)) InputMethodManager.SHOW_FORCED
    else InputMethodManager.SHOW_IMPLICIT

    et.addOnAttachStateChangeListener(object : View.OnAttachStateChangeListener {
        override fun onViewAttachedToWindow(v: View) {
            val imm = ctx.getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager
            // Retry loop: keep nudging until the IMM serves the field, then stop.
            et.post(object : Runnable {
                var tries = 0
                override fun run() {
                    if (!et.isAttachedToWindow) return
                    et.requestFocus()
                    imm.showSoftInput(et, showFlag)
                    if (!imm.isActive(et) && tries++ < 12) {
                        et.postDelayed(this, 50)   // ~600ms of retries, more than enough
                    }
                }
            })
        }
        override fun onViewDetachedFromWindow(v: View) {}
    })
    return et
}

// EditText that surfaces the BACK the IME normally eats — onKeyPreIme is the only
// hook that sees it first, so it's how we close the overlay while the keyboard is up.
private class SearchEditText(context: Context) :
    androidx.appcompat.widget.AppCompatEditText(context) {
    var onImeBack: (() -> Unit)? = null
    override fun onKeyPreIme(keyCode: Int, event: KeyEvent): Boolean {
        if (keyCode == KeyEvent.KEYCODE_BACK) {
            if (event.action == KeyEvent.ACTION_UP) {
                // SHOW_FORCED leaves the Fire TV keyboard sticky — removing the
                // popup alone doesn't pull it down, so hide it explicitly here
                // before dismissing, or it lingers over the home screen (the glitch).
                val imm = context.getSystemService(Context.INPUT_METHOD_SERVICE)
                        as InputMethodManager
                imm.hideSoftInputFromWindow(windowToken, 0)
                onImeBack?.invoke()
            }
            // Consume the whole BACK press (down + up) so the IME's own handler
            // and the Popup's dismissOnBackPress don't also fire and race the
            // teardown — that double-handling is the other half of the glitch.
            return true
        }
        return super.onKeyPreIme(keyCode, event)
    }
}