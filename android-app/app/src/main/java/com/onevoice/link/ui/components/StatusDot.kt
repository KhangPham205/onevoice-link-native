package com.onevoice.link.ui.components

import android.provider.Settings
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.onevoice.link.ui.theme.OneVoiceMotion

/**
 * Circular status indicator. State is shape-coded so it never relies on color
 * alone: hollow ring = inactive, solid = ready, solid + pulse = live.
 * Purely decorative — adjacent text always carries the state for TalkBack.
 */
@Composable
fun StatusDot(
    color: Color,
    modifier: Modifier = Modifier,
    size: Dp = 12.dp,
    filled: Boolean = true,
    pulsing: Boolean = false,
) {
    val context = LocalContext.current
    // Respect the system "remove animations" setting.
    val reduceMotion = remember(context) {
        Settings.Global.getFloat(
            context.contentResolver, Settings.Global.ANIMATOR_DURATION_SCALE, 1f,
        ) == 0f
    }

    val dotModifier = if (pulsing && !reduceMotion) {
        val pulse by rememberInfiniteTransition(label = "statusDotPulse").animateFloat(
            initialValue = 1f,
            targetValue = 0.45f,
            animationSpec = infiniteRepeatable(
                animation = tween(OneVoiceMotion.PulsePeriod, easing = FastOutSlowInEasing),
                repeatMode = RepeatMode.Reverse,
            ),
            label = "statusDotAlpha",
        )
        modifier.graphicsLayer { alpha = pulse }
    } else {
        modifier
    }

    Box(
        modifier = dotModifier
            .size(size)
            .clip(CircleShape)
            .then(
                if (filled) Modifier.background(color)
                else Modifier.border(2.dp, color, CircleShape),
            ),
    )
}
