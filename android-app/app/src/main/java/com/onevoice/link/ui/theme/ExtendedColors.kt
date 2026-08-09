package com.onevoice.link.ui.theme

import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color

/**
 * Semantic status colors that Material's scheme has no roles for: the engine
 * lifecycle traffic light (off → standby → live). Provided alongside
 * MaterialTheme via [LocalExtendedColors] so composables never hardcode hex.
 */
@Immutable
data class StatusColors(
    /** Dot / accent color. */
    val indicator: Color,
    /** Tonal surface behind the hero state. */
    val container: Color,
    /** Content color on that surface. */
    val onContainer: Color,
)

@Immutable
data class ExtendedColors(
    /** NOT_INITIALIZED — quiet neutral. */
    val neutral: StatusColors,
    /** INITIALIZED — amber standby. */
    val standby: StatusColors,
    /** RUNNING — live green. */
    val live: StatusColors,
)

val LightExtendedColors = ExtendedColors(
    neutral = StatusColors(
        indicator = Color(0xFF6F797A),
        container = Color(0xFFE3E9EA),
        onContainer = Color(0xFF3F484A),
    ),
    standby = StatusColors(
        indicator = Color(0xFF7A5900),
        container = Color(0xFFFFDEA6),
        onContainer = Color(0xFF271900),
    ),
    live = StatusColors(
        indicator = Color(0xFF006E1C),
        container = Color(0xFFB7F2AF),
        onContainer = Color(0xFF002204),
    ),
)

val DarkExtendedColors = ExtendedColors(
    neutral = StatusColors(
        indicator = Color(0xFF899294),
        container = Color(0xFF252B2C),
        onContainer = Color(0xFFBFC8CA),
    ),
    standby = StatusColors(
        indicator = Color(0xFFF5BE48),
        container = Color(0xFF5C4200),
        onContainer = Color(0xFFFFDEA6),
    ),
    live = StatusColors(
        indicator = Color(0xFF78DC77),
        container = Color(0xFF005313),
        onContainer = Color(0xFF94F990),
    ),
)

val LocalExtendedColors = staticCompositionLocalOf { LightExtendedColors }

/** Theme accessor for the extended (non-Material) roles. */
object OneVoiceTheme {
    val colors: ExtendedColors
        @Composable @ReadOnlyComposable get() = LocalExtendedColors.current
}
