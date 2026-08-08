package com.onevoice.link.core

/**
 * Which way the conversation is being translated. A 2-person conversation flips
 * this per speaker turn.
 *
 * Switching is NOT free on the NPU path: each direction needs its own cascade of
 * context binaries, and both directions together exceed what is sensible to keep
 * resident on the HTP, so [NpuEngine] releases the old contexts and loads the new
 * ones on every switch.
 */
enum class TranslationDirection {
    VI_TO_EN,
    EN_TO_VI,
}
