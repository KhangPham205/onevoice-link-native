package com.onevoice.link.core

import java.io.Closeable
import java.io.File

/**
 * sea-g2p: VieNeu-TTS's Vietnamese text normaliser and grapheme-to-phoneme
 * converter (Rust, Apache-2.0), reached through native-core's sea_g2p_jni.
 *
 * Normalisation is part of the call: digits, dates, units and abbreviations are
 * spelled out before G2P, exactly as the model saw its training text. The
 * dictionary file is memory-mapped by the library, so it costs page cache
 * rather than heap. A handle is not thread-safe; [phonemize] is synchronized.
 */
internal class SeaG2p private constructor(private var handle: Long) : Closeable {

    /**
     * [puncNorm] applies the trailing-punctuation rule VieNeu always enables:
     * a sentence under five words ends in exactly one ".", a longer one gains
     * a "." when it lacks terminal punctuation.
     */
    @Synchronized
    fun phonemize(text: String, puncNorm: Boolean = true): String {
        check(handle != 0L) { "sea-g2p handle is closed" }
        val phones = nativePhonemize(handle, text.toByteArray(Charsets.UTF_8), puncNorm)
        return String(phones, Charsets.UTF_8)
    }

    @Synchronized
    override fun close() {
        if (handle != 0L) nativeClose(handle)
        handle = 0L
    }

    companion object {
        /** Loads both libraries; throws if the Rust artifact is not packaged. */
        fun open(dictionary: File): SeaG2p {
            require(dictionary.isFile) { "sea-g2p dictionary missing: ${dictionary.absolutePath}" }
            // The Rust library first, so the shim's dlopen resolves an already
            // loaded soname inside the app's linker namespace.
            System.loadLibrary("sea_g2p_rs")
            System.loadLibrary("sea_g2p_jni")
            return SeaG2p(nativeOpen(dictionary.absolutePath.toByteArray(Charsets.UTF_8)))
        }

        @JvmStatic private external fun nativeOpen(dictPath: ByteArray): Long
        @JvmStatic private external fun nativePhonemize(
            handle: Long,
            text: ByteArray,
            puncNorm: Boolean,
        ): ByteArray
        @JvmStatic private external fun nativeClose(handle: Long)
    }
}
