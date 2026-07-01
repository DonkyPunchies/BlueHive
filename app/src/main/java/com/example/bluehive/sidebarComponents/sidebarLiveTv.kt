package com.example.bluehive.sidebarComponents

import android.content.Context
import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.OnBackPressedCallback
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import com.example.bluehive.BlueHiveApplication
import com.example.bluehive.R
import com.example.bluehive.utilities.AppTypography

// ─────────────────────────────────────────────────────────────────────────────
//  Live TV — "feature in development" overlay
//  Uses the same card/scrim pattern as the History empty-state so the visual
//  language stays consistent across the app.
// ─────────────────────────────────────────────────────────────────────────────

fun openLiveTvScreen(context: Context, profileId: Int = -1) {
    context.startActivity(
        Intent(context, LiveTvScreenActivity::class.java)
            .putExtra("PROFILE_ID", profileId)
    )
}

class LiveTvScreenActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        WindowCompat.setDecorFitsSystemWindows(window, false)
        WindowInsetsControllerCompat(window, window.decorView).apply {
            hide(WindowInsetsCompat.Type.systemBars())
            systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        }

        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                BlueHiveApplication.playBackOutSound()
                finish()
            }
        })

        setContent { LiveTvComingSoonScreen() }
    }
}

@Composable
private fun LiveTvComingSoonScreen() {
    Box(modifier = Modifier.fillMaxSize()) {

        // ── Layer 1: background image (same as history/favorites screens) ─────
        Image(
            painter            = painterResource(id = R.drawable.home_screen),
            contentDescription = null,
            contentScale       = ContentScale.Crop,
            modifier           = Modifier.fillMaxSize(),
        )

        // ── Layer 2: primary dark scrim ───────────────────────────────────────
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(Color.Black.copy(alpha = 0.45f))
        )

        // ── Layer 3: extra-dark scrim behind the card (matches history empty-state) ──
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(Color.Black.copy(alpha = 0.65f))
        )

        // ── Layer 4: centered card ────────────────────────────────────────────
        Box(
            contentAlignment = Alignment.Center,
            modifier         = Modifier.fillMaxSize(),
        ) {
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                modifier = Modifier
                    .background(
                        color = Color(0xFF121213),
                        shape = RoundedCornerShape(12.dp)
                    )
                    .border(
                        width = 1.dp,
                        color = Color(0xFF3A3737),
                        shape = RoundedCornerShape(12.dp)
                    )
                    .padding(horizontal = 40.dp, vertical = 32.dp)
                    .width(480.dp),
            ) {

                // Title
                Text(
                    text       = "Feature Still in Development",
                    color      = Color.White,
                    fontFamily = AppTypography.interBold,
                    fontWeight = FontWeight.Bold,
                    fontSize   = 20.sp,
                    textAlign  = TextAlign.Center,
                    modifier   = Modifier
                        .fillMaxWidth()
                        .padding(bottom = 12.dp),
                )

                // Body
                Text(
                    text       = "Thank you for your patience. We excel in creating the most convenient platform on the market.",
                    color      = Color(0xFFAAAAAA),
                    fontFamily = AppTypography.interSemiBold,
                    fontWeight = FontWeight.Normal,
                    fontSize   = 13.sp,
                    textAlign  = TextAlign.Center,
                    modifier   = Modifier
                        .fillMaxWidth()
                        .offset(y = 8.dp),
                )

                // Dismissal hint — red, same as history empty-state
                Text(
                    text       = "To leave just press the back button",
                    color      = Color(0xFFA01D1D),
                    fontFamily = AppTypography.interBold,
                    fontWeight = FontWeight.Normal,
                    fontSize   = 15.sp,
                    textAlign  = TextAlign.Center,
                    modifier   = Modifier
                        .fillMaxWidth()
                        .offset(y = 36.dp)
                        .padding(bottom = 28.dp),
                )
            }
        }
    }
}