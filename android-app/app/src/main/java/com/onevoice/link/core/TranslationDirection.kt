package com.onevoice.link.core

/**
 * The immutable route selected for one completed utterance.
 *
 * Both direction-qualified ASR/MT context sets stay resident in the production
 * Hybrid engine. Selecting a direction therefore never unloads or swaps models.
 * Today the route comes from acoustic LID; a future authenticated headset-floor
 * controller can supply the same enum without changing the ASR/MT/TTS branches.
 */
enum class TranslationDirection {
    VI_TO_EN,
    EN_TO_VI,
}
