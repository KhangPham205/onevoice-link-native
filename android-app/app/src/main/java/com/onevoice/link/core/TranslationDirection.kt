package com.onevoice.link.core

/**
 * The immutable route selected for one completed utterance.
 *
 * Both direction-qualified ASR/MT context sets stay resident in the production
 * Hybrid engine. Selecting a direction therefore never unloads or swaps models.
 * The default router compares length-normalized VI/EN RNN-T confidence for the
 * bounded utterance. SpeechFlow remains available behind an A/B build flag.
 */
enum class TranslationDirection {
    VI_TO_EN,
    EN_TO_VI,
}
