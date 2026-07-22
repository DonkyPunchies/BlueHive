package com.example.bluehive.utilities

// ─────────────────────────────────────────────────────────────────────────────
// CloseApplication.kt
//
// Owns the entire "close the app gracefully" flow.
// Drop CloseApplicationHandler() into any screen's content tree and it
// handles everything — back interception, overlay UI, API call, task close.
//
// Responsibilities
// ────────────────
//  1. Intercepts the hardware back button via BackHandler.
//  2. Manages the show/hide state of the exit confirmation overlay.
//  3. Renders the overlay using ConfirmationOverlay from Overlays.kt.
//  4. On confirm:
//       a. Stops the SSE stream via stopAndMarkExited() so the server's
//          SSE finally block sets is_device_active = false immediately.
//       b. Calls end-session DIRECTLY via the API — this is the primary
//          mechanism and works regardless of SSE stream state, so the
//          overlay works correctly from the Profile screen (where the stream
//          may not yet have set is_device_active = true) AND from the Home
//          screen (where it has).
//       c. Calls finishAffinity() to clear the entire task stack.
//  5. On cancel — dismisses the overlay, no API calls made.
//
// Back-button routing (HomeScreenCompose only — other screens unaffected):
//  ┌─────────────────────────────────────────────────────────┐
//  │  State                   │  Back press result           │
//  ├─────────────────────────────────────────────────────────┤
//  │  Overlay showing          │  Dismiss overlay             │
//  │  Sidebar open (focused)   │  Show "close app?" overlay   │
//  │  Home screen (no sidebar) │  Open the sidebar            │
//  │  Any other screen         │  Show "close app?" overlay   │
//  └─────────────────────────────────────────────────────────┘
//
//  ProfileScreenActivity and any future screen that calls
//  CloseApplicationHandler() with no arguments keeps the original
//  "back → show overlay" behaviour (isSidebarOpen defaults false,
//  onOpenSidebar defaults null → always shows overlay).
// ─────────────────────────────────────────────────────────────────────────────

import androidx.activity.compose.BackHandler
import androidx.activity.compose.LocalActivity
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import android.util.Log
import com.example.bluehive.api.ApiClient
import com.example.bluehive.auth.DeviceEventStream
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout


/**
 * CloseApplicationHandler
 *
 * Drop this composable anywhere inside a screen's content tree.
 * It self-contains all close-app logic and draws its UI from Overlays.kt.
 *
 * Works correctly from:
 *  - ProfileScreenActivity (user hasn't entered the home screen yet)
 *  - HomeScreenCompose     (user is actively streaming / slot is occupied)
 *
 * In both cases, pressing "Yes, Close App" guarantees is_device_active is
 * set to false on the backend via a direct end-session API call.
 *
 * Parameters (all optional — zero-arg call keeps original behaviour):
 *
 *   isSidebarOpen  — pass isSidebarFocused from HomeScreenCompose so the
 *                    handler knows whether the sidebar is currently open.
 *                    When true, back shows the exit overlay.
 *                    Defaults to false (original behaviour for every screen
 *                    that doesn't care about the sidebar).
 *
 *   onOpenSidebar  — callback invoked when back is pressed on the home
 *                    screen and the sidebar is NOT open. Pass a lambda
 *                    that calls openSidebar(lastFocusedTarget).
 *                    When null (default), back always shows the exit overlay
 *                    regardless of isSidebarOpen — preserving the original
 *                    behaviour for ProfileScreenActivity etc.
 *
 * Usage (ProfileScreenActivity — no change needed):
 *
 *     Box(modifier = Modifier.fillMaxSize()) {
 *         // ... all screen content ...
 *         CloseApplicationHandler()   // ← back → overlay, same as before
 *     }
 *
 * Usage (HomeScreenCompose):
 *
 *     CloseApplicationHandler(
 *         isSidebarOpen = isSidebarFocused,
 *         onOpenSidebar = { openSidebar(lastFocusedTarget) }
 *     )
 */
@Composable
fun CloseApplicationHandler(
    isSidebarOpen: Boolean = false,
    onOpenSidebar: (() -> Unit)? = null,
) {

    // Controls whether the exit confirmation overlay is visible.
    // Internal state — no parent screen touches this.
    var showConfirm by remember { mutableStateOf(false) }

    // Activity reference needed for finishAffinity().
    val activity = LocalActivity.current ?: return

    // ── Back button interception ───────────────────────────────────────────────
    // Priority order evaluated top-to-bottom on every back press:
    //
    //  1. Overlay is showing         → dismiss it (user changed their mind).
    //  2. Sidebar is open            → show "close app?" overlay.
    //     OR no sidebar callback     → show "close app?" overlay
    //     (covers ProfileScreen and any screen without a sidebar).
    //  3. Home screen, sidebar closed → open the sidebar instead.
    BackHandler {
        when {
            showConfirm -> {
                // Case 1: back while overlay is visible → cancel / dismiss
                showConfirm = false
            }
            isSidebarOpen || onOpenSidebar == null -> {
                // Case 2: sidebar is open (user is on the sidebar) OR this screen
                // has no sidebar at all → show the exit confirmation overlay.
                showConfirm = true
            }
            else -> {
                // Case 3: on the home screen and sidebar is closed → open sidebar.
                onOpenSidebar()
            }
        }
    }

    // ── Overlay ────────────────────────────────────────────────────────────────
    // Only rendered when showConfirm is true. Sits on top of all other content
    // because CloseApplicationHandler() is placed last in the Box stack.
    if (showConfirm) {
        ConfirmationOverlay(
            message     = "Are you sure you want to close the app?",
            confirmText = "Yes, Close App",
            cancelText  = "No, Stay",
            onConfirm   = {
                showConfirm = false
                performClose(activity)
            },
            onCancel    = {
                // Dismiss overlay — no API calls, stream continues untouched.
                showConfirm = false
            },
        )
    }
}


/**
 * performClose
 *
 * Executes the graceful app-close sequence in order:
 *
 *  Step 1 — stopAndMarkExited()
 *    Cancels the SSE stream coroutine and sets a permanent flag that blocks
 *    DeviceEventStream.start() for the rest of this process lifetime.
 *    When the stream closes, the server's SSE finally block fires and sets
 *    is_device_active = false as an additional signal.
 *    Safe to call even if the stream is not currently running (no-op on stop).
 *
 *  Step 2 — endSession() API call (PRIMARY MECHANISM)
 *    Directly tells the backend to set is_device_active = false and push a
 *    slot_freed event to the website dashboard. This is the guaranteed path
 *    that works from ANY screen — Profile screen, Home screen, or any future
 *    screen that uses CloseApplicationHandler(). Has a 2-second timeout so
 *    a slow or failed network call never traps the user inside the app.
 *
 *  Step 3 — finishAffinity()
 *    Closes every Activity in the task stack. Placed in the finally block so
 *    it always runs even if endSession() times out or throws. This prevents
 *    LoadingScreenActivity from surfacing, seeing isAuthenticated = true, and
 *    re-routing back to ProfileScreenActivity (which would restart the SSE
 *    stream and re-occupy the device slot).
 */
private fun performClose(activity: android.app.Activity) {
    // Step 1 — stop stream and block any future start() calls this process.
    DeviceEventStream.stopAndMarkExited()

    // Step 2 (PHASE 3.1, RESTORED) — end-session ping, now to BLUEHIVE-API. The
    // endpoint PHASE 2 removed targeted the platform backend, which stopped
    // accepting BlueHive's token; bluehive-api owns device_sessions now. This is
    // the PRIMARY "instant inactive" mechanism for a proper Back-button close.
    // The SSE finally no longer writes is_device_active (it raced request
    // cancellation), so WITHOUT this ping a clean close would wait ~15 min on the
    // reaper. Step 3 (finishAffinity) runs in the finally so a slow/failed ping
    // never traps the user inside the app; the 2s timeout caps the wait, and if
    // the ping is lost the reaper is still the backstop.
    CoroutineScope(Dispatchers.IO).launch {
        try {
            withTimeout(2_000) {
                ApiClient.bluehiveApi.endSession()
            }
        } catch (e: Exception) {
            Log.w("CloseApplication", "end-session ping failed (reaper backstop): ${e.message}")
        } finally {
            withContext(Dispatchers.Main) {
                activity.finishAffinity()
            }
        }
    }
}
