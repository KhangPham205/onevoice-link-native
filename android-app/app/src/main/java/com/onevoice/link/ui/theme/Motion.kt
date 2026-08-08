package com.onevoice.link.ui.theme

import androidx.compose.animation.core.CubicBezierEasing

/** Motion constants — subtle by design; nothing outside these values animates. */
object OneVoiceMotion {
    /** M3 standard easing. */
    val StandardEasing = CubicBezierEasing(0.2f, 0f, 0f, 1f)

    /** Spinner fades, exits. */
    const val DurationShort = 150

    /** Content / size changes. */
    const val DurationMedium = 250

    /** Color transitions (status card wash, stop button). */
    const val DurationColor = 300

    /** Half-cycle of the RUNNING status-dot alpha pulse. */
    const val PulsePeriod = 1100
}
