package com.example.bluehive.utilities

import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import com.example.bluehive.R
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.unit.dp
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.Outline
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.LayoutDirection


// Why this is (slightly) better than remember
//                          |
//                          V
// With remember: you might have e.g. 3–4 copies of the same FontFamily if you have multiple screens using it. With a top-level val: 1 copy for the whole app.
//==========================================================================================

//   - The FontFamily is created once per composition of that composable.
//   - So every screen that uses that composable has its own instance.
//   - It’s already pretty good: no re-allocation on every recomposition.
//   - Top-level val RecommendedTitleCardFont
//   - The FontFamily is created once per app process when the class is first loaded.
//   - Every composable, on any screen, shares the same reference.
//   - No remember bookkeeping, no extra per-screen instances.

// Top-level val RecommendedTitleCardFont
//   - The FontFamily is created once per app process when the class is first loaded.
//   - Every composable, on any screen, shares the same reference.
//   - No remember bookkeeping, no extra per-screen instances.



// This creates a single static instance in memory. No allocation cost at runtime.
object AppTypography {
    val passionRegular = FontFamily(Font(R.font.passion_regular))
    val dongleRegular = FontFamily(Font(R.font.dongle_regular))
    val dongleBold = FontFamily(Font(R.font.dongle_bold))
    val pattayaRegular = FontFamily(Font(R.font.pattaya_regular))
    val interSemiBold = FontFamily(Font(R.font.inter_semibold))
    val interBold = FontFamily(Font(R.font.inter_bold))
    val lalezarRegular = FontFamily(Font(R.font.lalezar_regular))


}


object AppShapes {
    val ratingBadge = RoundedCornerShape(4.5.dp)
    val card = RoundedCornerShape(6.dp)



    // Shared bottom-left rounded shape for details backdrops
    val bottomLeftRoundedShape: Shape = object : Shape {
        override fun createOutline(
            size: Size,
            layoutDirection: LayoutDirection,
            density: Density
        ): Outline {
            val path = Path().apply {
                moveTo(0f, 0f)
                lineTo(size.width, 0f)
                lineTo(size.width, size.height)
                lineTo(0f, size.height)

                val cornerRadius = kotlin.math.min(size.width, size.height) * 0.2f
                arcTo(
                    rect = Rect(
                        offset = Offset(0f, size.height - cornerRadius * 2),
                        size = Size(cornerRadius * 2, cornerRadius * 2)
                    ),
                    startAngleDegrees = 90f,
                    sweepAngleDegrees = 90f,
                    forceMoveTo = false
                )
                close()
            }
            return Outline.Generic(path)
        }
    }

}

