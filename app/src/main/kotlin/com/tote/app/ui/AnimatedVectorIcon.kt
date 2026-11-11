package com.tote.app.ui

import android.graphics.drawable.AnimatedVectorDrawable
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import android.widget.ImageView

/**
 * Render an [AnimatedVectorDrawable] as a Compose composable.
 *
 * The AVD is started on first composition (and re-started if the
 * `drawableRes` changes). It loops infinitely until the composable
 * is removed from the tree.
 *
 * Per ADR-019: replaces the static Material icons used in v1.0.0's
 * onboarding flow with animated vectors. No external Lottie dependency —
 * uses the platform AnimatedVectorDrawable API (available since API 24,
 * our minSdk is 26).
 *
 * @param drawableRes The R.drawable.avd_* resource ID to render
 * @param sizeDp The size in dp (default 96)
 */
@Composable
fun AnimatedVectorIcon(
    drawableRes: Int,
    sizeDp: Int = 96,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val avd = remember(drawableRes) {
        context.resources.getDrawable(drawableRes, context.theme) as? AnimatedVectorDrawable
    }
    AndroidView(
        factory = { ctx ->
            ImageView(ctx).apply {
                setImageDrawable(avd)
                scaleType = ImageView.ScaleType.FIT_CENTER
            }
        },
        modifier = modifier.size(sizeDp.dp),
        update = { imageView ->
            avd?.let {
                imageView.setImageDrawable(it)
                it.start()
            }
        },
    )
    // Restart the AVD whenever it changes (e.g., onboarding page switch)
    LaunchedEffect(drawableRes) {
        avd?.start()
    }
}

private fun Int.dp(): Dp = this.dp

