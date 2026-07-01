package com.example.bluehive.sidebarComponents

import android.content.Context
import android.content.Intent
import com.example.bluehive.ProfileScreenActivity

fun openProfileScreen(context: Context) {
    context.startActivity(
        Intent(context, ProfileScreenActivity::class.java)
    )
}