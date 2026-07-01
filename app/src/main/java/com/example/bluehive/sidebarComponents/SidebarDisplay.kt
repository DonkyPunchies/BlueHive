package com.example.bluehive.sidebarComponents

import android.app.Activity
import android.util.Log
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.focusable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.bluehive.BlueHiveApplication
import com.example.bluehive.R
import com.example.bluehive.searchBarComponent.SidebarSearchOverlay

// Sidebar menu item data class
data class SidebarMenuItem(
    val id: String,
    val iconRes: Int,
    val label: String,
    val onClick: () -> Unit,
    val unfocusedCustomYOffset: Dp = 0.dp,
    val focusedCustomYOffset: Dp = 0.dp,
    val customXOffset: Dp = 0.dp,
    val customIconYOffset: Dp = 0.dp,
)


// ── Sidebar profile avatar config ─────────────────────────────────────────────
private val SIDEBAR_AVATAR_WIDTH: Dp  = 20.dp
private val SIDEBAR_AVATAR_HEIGHT: Dp = 20.dp


@Composable
fun HomeScreenSidebarCompose(
    focusRequester:   FocusRequester,
    canFocus:         Boolean,
    profileAvatarRes: Int = R.drawable.avatar1,
    onFocusChanged:   (Boolean) -> Unit,
    onExitToRight:    () -> Unit,
    onProfileClick:      () -> Unit = {},
    onSearchSubmit:      (String) -> Unit = {},
    onMediaCatalogClick: () -> Unit = {},
    onSettingsClick:     () -> Unit = {},
    onFavoritesClick:    () -> Unit = {},
    onLiveTvClick:       () -> Unit = {},
    onChangelogClick:    () -> Unit = {},   // kept for caller compatibility (Movies/TV Shows removed)
    onHistoryClick:      () -> Unit = {},
) {
    val sidebarInteraction = remember { MutableInteractionSource() }
    var isSidebarFocused by remember { mutableStateOf(false) }
    var hasEverBeenFocused by remember { mutableStateOf(false) }
    var selectedIndex by remember { mutableIntStateOf(0) }

    // ── Inline search overlay state ─────────────────────────────────────────
    var isSearchActive      by remember { mutableStateOf(false) }
    var hasSearchBeenActive by remember { mutableStateOf(false) }

    // When the overlay closes, hand focus back to the sidebar Image.
    LaunchedEffect(isSearchActive) {
        if (isSearchActive) {
            hasSearchBeenActive = true
        } else if (hasSearchBeenActive) {
            kotlinx.coroutines.android.awaitFrame()
            focusRequester.requestFocus()
        }
    }

    // Get activity from context
    val context = LocalContext.current
    val activity = context as? Activity

    if (activity == null) {
        Log.e("SIDEBAR", "❌ Activity is null!")
    }

    val baseW = 25.5.dp
    val expandedW = 124.dp

    // Define menu items — NEW ORDER (Movies & TV Shows removed)
    val menuItems = remember {
        listOf(
            SidebarMenuItem(
                id = "profiles",
                iconRes = profileAvatarRes,
                label = "Profiles",
                onClick = onProfileClick,
                unfocusedCustomYOffset = 0.dp,
                focusedCustomYOffset = 0.dp,
                customXOffset = (-2).dp,
            ),
            SidebarMenuItem(
                id = "search",
                iconRes = R.drawable.magnifying_glass_raw_image,
                label = "Search Bar",
                onClick = {},  // activation handled in onKeyEvent — opens inline overlay
                unfocusedCustomYOffset = 35.dp,
                focusedCustomYOffset = 33.5.dp,
                customIconYOffset = 0.7.dp,
            ),
            SidebarMenuItem(
                id = "mediacatalog",
                iconRes = R.drawable.media_catalog,
                label = "Media Catalog",
                onClick = onMediaCatalogClick,
                unfocusedCustomYOffset = 70.dp,
                focusedCustomYOffset = 68.5.dp,
                customIconYOffset = 0.7.dp,
            ),
            SidebarMenuItem(
                id = "livetv",
                iconRes = R.drawable.livetv,
                label = "Live TV",
                onClick = onLiveTvClick,
                unfocusedCustomYOffset = 105.dp,
                focusedCustomYOffset = 103.5.dp,
            ),
            SidebarMenuItem(
                id = "favorites",
                iconRes = R.drawable.favourite,
                label = "Favorites",
                onClick = onFavoritesClick,
                unfocusedCustomYOffset = 140.dp,
                focusedCustomYOffset = 138.5.dp,
                customIconYOffset = 0.7.dp,
            ),
            SidebarMenuItem(
                id = "history",
                iconRes = R.drawable.history,
                label = "Watch History",
                onClick = onHistoryClick,
                unfocusedCustomYOffset = 175.dp,
                focusedCustomYOffset = 173.5.dp,
                customIconYOffset = 0.7.dp,
            ),
            SidebarMenuItem(
                id = "settings",
                iconRes = R.drawable.settings,
                label = "Settings",
                onClick = onSettingsClick,
                unfocusedCustomYOffset = 210.dp,
                focusedCustomYOffset = 208.5.dp,
                customIconYOffset = 0.7.dp,
            ),
            SidebarMenuItem(
                id = "reboot",
                iconRes = R.drawable.reboot,
                label = "Reboot",
                onClick = {
                    activity?.let {
                        Log.d("SIDEBAR", "🔴 Reboot button clicked")
                        SidebarReboot.performReboot(it, showConfirmation = true)
                    } ?: Log.e("SIDEBAR", "❌ Activity null on click")
                },
                unfocusedCustomYOffset = 245.dp,
                focusedCustomYOffset = 243.5.dp,
                customIconYOffset = 0.8.dp,
            )
        )
    }

    Box {
        // ========================================
        // BASE LAYER (always visible)
        // ========================================
        Image(
            painter = painterResource(id = R.drawable.sidebar),
            contentDescription = "Sidebar",
            modifier = Modifier
                .offset(x = 0.dp, y = 0.dp)
                .width(baseW)
                .fillMaxHeight()
                .focusRequester(focusRequester)
                .focusProperties { this.canFocus = canFocus }
                .onFocusChanged { state ->
                    isSidebarFocused = state.isFocused
                    if (state.isFocused || !isSearchActive) {
                        onFocusChanged(state.isFocused)
                    }

                    if (state.isFocused && !hasEverBeenFocused) {
                        hasEverBeenFocused = true
                        selectedIndex = 0
                        Log.d("SIDEBAR", "✅ First time focused - enabling panel rendering")
                    }
                }
                .onKeyEvent { event ->
                    if (event.type == KeyEventType.KeyDown) {
                        when (event.key) {
                            Key.DirectionLeft -> true

                            Key.DirectionRight -> {
                                if (isSidebarFocused) {
                                    BlueHiveApplication.playHoverSound()
                                    onExitToRight()
                                    Log.d("SIDEBAR", "➡️ RIGHT - leaving sidebar")
                                    true
                                } else false
                            }

                            Key.DirectionUp -> {
                                if (isSidebarFocused && selectedIndex > 0) {
                                    selectedIndex--
                                    BlueHiveApplication.playHoverSound()
                                    Log.d("SIDEBAR", "⬆️ UP - selected: ${menuItems[selectedIndex].label}")
                                }
                                true
                            }

                            Key.DirectionDown -> {
                                if (isSidebarFocused && selectedIndex < menuItems.lastIndex) {
                                    selectedIndex++
                                    BlueHiveApplication.playHoverSound()
                                    Log.d("SIDEBAR", "⬇️ DOWN - selected: ${menuItems[selectedIndex].label}")
                                }
                                true
                            }

                            Key.DirectionCenter, Key.Enter, Key.NumPadEnter -> {
                                if (isSidebarFocused) {
                                    BlueHiveApplication.playClickSound()
                                    if (menuItems[selectedIndex].id == "search") {
                                        isSearchActive = true
                                    } else {
                                        menuItems[selectedIndex].onClick()
                                    }
                                    Log.d("SIDEBAR", "✅ Selected: ${menuItems[selectedIndex].label}")
                                }
                                true
                            }

                            else -> false
                        }
                    } else false
                }
                .focusable(interactionSource = sidebarInteraction),
            contentScale = ContentScale.FillBounds
        )


        // ========================================
        // EXPANDED PANEL (visible when focused OR searching)
        // ========================================
        if ((isSidebarFocused || isSearchActive) && hasEverBeenFocused) {
            Log.d("SIDEBAR", "✅ Sidebar focused - showing expanded panel")

            Box(
                modifier = Modifier
                    .offset(x = 0.dp, y = 0.dp)
                    .width(expandedW)
                    .fillMaxHeight()
                    .clip(RoundedCornerShape(topEnd = 10.dp, bottomEnd = 10.dp))
                    .background(
                        brush = Brush.verticalGradient(
                            colors = listOf(Color(0xFF1A1A1A), Color(0xFF0D0D0D))
                        )
                    )
                    .border(
                        width = 1.dp,
                        color = Color(0xFF333333),
                        shape = RoundedCornerShape(topEnd = 10.dp, bottomEnd = 10.dp)
                    )
            )


            // ========================================
            // BLUE HIGHLIGHT BACKGROUNDS (hidden while searching)
            // ========================================
            menuItems.forEachIndexed { index, item ->
                if (index == selectedIndex && !isSearchActive) {
                    val yOffset = (index * 35).dp

                    Box(
                        modifier = Modifier
                            .offset(x = 0.dp, y = 34.3.dp + yOffset)
                            .width(expandedW)
                            .size(width = expandedW, height = 28.dp)
                            .background(
                                brush = Brush.horizontalGradient(
                                    colors = listOf(
                                        Color(0xFF1E3A8A),
                                        Color(0xFF2563EB)
                                    )
                                )
                            )
                    )
                }
            }


            // ========================================
            // MENU LABELS (visible when expanded)
            // ========================================
            Box(
                modifier = Modifier
                    .offset(x = baseW + 5.dp, y = 39.dp)
                    .width(expandedW - baseW - 10.dp)
                    .fillMaxHeight()
            ) {
                menuItems.forEachIndexed { index, item ->
                    val isSelected = index == selectedIndex

                    val yOffset = if (isSelected) {
                        item.focusedCustomYOffset
                    } else {
                        item.unfocusedCustomYOffset
                    }

                    Row(
                        modifier = Modifier
                            .offset(y = yOffset)
                            .padding(vertical = 4.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = item.label,
                            color = if (isSelected) Color.White else Color(0xFF888888),
                            fontSize = if (isSelected) 13.sp else 11.sp,
                            fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Normal,
                            modifier = Modifier.graphicsLayer {
                                scaleX = if (isSelected) 1.05f else 1f
                                scaleY = if (isSelected) 1.05f else 1f
                            }
                        )
                    }
                }
            }

        }


        // ========================================
        // MENU ICONS (always visible, on top of base layer)
        // ========================================
        Box(
            modifier = Modifier
                .offset(x = 0.dp, y = 40.dp)
                .width(baseW)
                .fillMaxHeight()
        ) {
            menuItems.forEachIndexed { index, item ->
                val yOffset = (index * 35).dp

                Image(
                    painter = painterResource(id = item.iconRes),
                    contentDescription = item.label,
                    modifier = Modifier
                        .offset(x = 5.dp + item.customXOffset, y = yOffset + item.customIconYOffset)
                        .size(
                            width  = if (item.id == "profiles") SIDEBAR_AVATAR_WIDTH else 15.dp,
                            height = if (item.id == "profiles") SIDEBAR_AVATAR_HEIGHT else 15.dp,
                        )
                        .graphicsLayer {
                            alpha = if (isSidebarFocused && index != selectedIndex) 0.4f else 1f
                        },
                    contentScale = ContentScale.Fit,
                )
            }
        }


        // ========================================
        // COVER LAYER (visible when NOT focused AND not searching)
        // ========================================
        if (!isSidebarFocused && !isSearchActive) {
            Image(
                painter = painterResource(id = R.drawable.sidebar_cover),
                contentDescription = "Sidebar Cover",
                modifier = Modifier
                    .offset(x = 0.dp, y = 0.dp)
                    .width(baseW)
                    .fillMaxHeight(),
                contentScale = ContentScale.FillBounds
            )
        }

        // ========================================
        // INLINE SEARCH OVERLAY (centered, system keyboard)
        // ========================================
        if (isSearchActive && hasEverBeenFocused) {
            SidebarSearchOverlay(
                onSubmit = { query ->
                    val q = query.trim()
                    isSearchActive = false
                    if (q.isNotEmpty()) onSearchSubmit(q)
                },
                onDismiss = { isSearchActive = false },
            )
        }
    }
}