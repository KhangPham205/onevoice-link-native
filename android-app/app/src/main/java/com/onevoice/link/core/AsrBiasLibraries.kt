package com.onevoice.link.core

import android.content.Context
import java.io.File

/**
 * One compiled term library ("Xưởng may", later "Xưởng gia dụng", ...): a
 * workplace's vocabulary for the VI ASR bias. Libraries are files staged beside
 * the model, `vi-en/<asr prefix>_bias/<id>.txt`, so adding a domain needs a new
 * TSV and a compile, not an app release.
 */
internal data class AsrBiasLibrary(
    /** File stem, e.g. `xuong_may`; persisted as the Dashboard selection. */
    val id: String,
    /** Display name from the compiled header, e.g. "Xưởng may". */
    val name: String,
    /** Distinct terms in the source TSV; 0 when the header does not say. */
    val terms: Int,
    val file: File,
)

internal object AsrBiasLibraries {
    /** The library selected when the person has never chosen one. */
    const val DEFAULT_ID = "xuong_may"

    private val ID = Regex("[a-z0-9_]{1,64}")

    /** `<directionRoot>/<prefix>_bias/`, where the term libraries are staged. */
    fun directory(directionRoot: File, asrArtifactPrefix: String): File =
        File(directionRoot, "${asrArtifactPrefix}_bias")

    /**
     * Libraries found in [dir], sorted by name. Only the header is read here;
     * a library whose pieces do not match the model fails when it is selected.
     * A file without the v1 header, or with an unsafe id, is skipped.
     */
    fun discover(dir: File): List<AsrBiasLibrary> =
        dir.listFiles { file -> file.isFile && file.name.endsWith(".txt") }
            .orEmpty()
            .mapNotNull(::readHeader)
            .sortedBy { it.name.lowercase() }

    /** Parses the `# key: value` lines that compile_bias_list.py writes first. */
    fun readHeader(file: File): AsrBiasLibrary? {
        val id = file.name.removeSuffix(".txt")
        if (!ID.matches(id)) return null
        val header = file.bufferedReader(Charsets.UTF_8).useLines { lines ->
            lines.takeWhile { it.startsWith("#") }.take(16).toList()
        }
        if (header.firstOrNull()?.startsWith("# onevoice-asr-bias v1") != true) return null
        val fields = header.drop(1).mapNotNull { line ->
            val body = line.removePrefix("#").trim()
            val colon = body.indexOf(':')
            if (colon <= 0) null else body.substring(0, colon).trim() to body.substring(colon + 1).trim()
        }.toMap()
        if (fields["id"]?.let { it != id } == true) return null
        return AsrBiasLibrary(
            id = id,
            name = fields["name"]?.takeIf(String::isNotBlank) ?: id,
            terms = fields["terms"]?.toIntOrNull() ?: 0,
            file = file,
        )
    }

    /**
     * The library to use: the persisted id when it is still staged; "off"
     * selects none; with nothing persisted, [DEFAULT_ID] or else the first one.
     */
    fun resolve(libraries: List<AsrBiasLibrary>, persisted: String?): AsrBiasLibrary? = when (persisted) {
        AsrBiasPreferences.OFF -> null
        null -> libraries.firstOrNull { it.id == DEFAULT_ID } ?: libraries.firstOrNull()
        else -> libraries.firstOrNull { it.id == persisted }
            ?: libraries.firstOrNull { it.id == DEFAULT_ID }
            ?: libraries.firstOrNull()
    }
}

/** Persists the Dashboard's term-library choice across launches. */
internal class AsrBiasPreferences(context: Context) {
    private val preferences = context.getSharedPreferences(FILE_NAME, Context.MODE_PRIVATE)

    /** A library id, [OFF], or null when the person has never chosen. */
    fun get(): String? = preferences.getString(KEY, null)

    fun set(libraryId: String?) {
        preferences.edit().putString(KEY, libraryId ?: OFF).apply()
    }

    companion object {
        const val OFF = "off"
        private const val FILE_NAME = "asr_bias_v1"
        private const val KEY = "library"
    }
}
