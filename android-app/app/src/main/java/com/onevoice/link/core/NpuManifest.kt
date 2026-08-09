package com.onevoice.link.core

import android.content.Context
import org.json.JSONObject
import java.io.File

/**
 * The deploy contract for the NPU cascade, shipped as the `npu_manifest.json`
 * asset and generated from the context binaries themselves with
 * `qnn-context-binary-utility` (see onevoice-npu-bins/HANDOFF.md §7-B2).
 *
 * Graph names are AI Hub-generated (`graph_08xlh5ri`, `opus_mt_encoder`, …) and
 * are NOT guessable — `graphRetrieve` fails on the slightest mismatch, so the
 * manifest is the single source of truth and must be regenerated whenever the
 * model team ships new binaries (`tools/models/gen_npu_manifest.py`).
 */
data class NpuManifest(
    val targetSocModel: Int,
    val targetSocName: String,
    val targetMarketing: String,
    val hexagon: String,
    val modelRoot: String,
    val directions: List<DirectionSpec>,
) {
    fun forDirection(direction: TranslationDirection): DirectionSpec? =
        directions.firstOrNull { it.id == direction.manifestId }

    companion object {
        private const val ASSET = "npu_manifest.json"

        fun load(context: Context): NpuManifest {
            val text = context.assets.open(ASSET).bufferedReader().use { it.readText() }
            val o = JSONObject(text)
            val soc = o.getJSONObject("targetSoc")
            val dirs = o.getJSONArray("directions")
            return NpuManifest(
                targetSocModel = soc.getInt("qnnSocModel"),
                targetSocName = soc.getString("socModel"),
                targetMarketing = soc.getString("marketing"),
                hexagon = soc.getString("hexagon"),
                modelRoot = o.getString("modelRoot"),
                directions = (0 until dirs.length()).map { DirectionSpec.parse(dirs.getJSONObject(it)) },
            )
        }
    }
}

/** One translation direction and the cascade stages it needs. */
data class DirectionSpec(
    val id: String,
    val label: String,
    /**
     * False when the package cannot drive this direction at all. EN→VI is
     * currently false: the model team has shipped Vietnamese ASR only, so there
     * is no English `.bin` to start the cascade with (HANDOFF.md §4).
     */
    val available: Boolean,
    val unavailableReason: String?,
    val stages: List<StageSpec>,
) {
    val totalBytes: Long get() = stages.sumOf { it.sizeBytes }

    companion object {
        fun parse(o: JSONObject): DirectionSpec {
            val arr = o.getJSONArray("stages")
            return DirectionSpec(
                id = o.getString("id"),
                label = o.getString("label"),
                available = o.optBoolean("available", true),
                unavailableReason = o.optString("unavailableReason").ifBlank { null },
                stages = (0 until arr.length()).map { StageSpec.parse(arr.getJSONObject(it)) },
            )
        }
    }
}

/** One cascade stage: which `.bin` to load and which graph to retrieve from it. */
data class StageSpec(
    val stage: String,
    val label: String,
    /** Path relative to the model root, e.g. `vi-en/asr_parakeet_vi.bin`. */
    val file: String,
    val graph: String,
    val sizeBytes: Long,
    val inputs: List<TensorSpec> = emptyList(),
    val outputs: List<TensorSpec> = emptyList(),
) {
    fun resolve(root: File): File = File(root, file)

    companion object {
        fun parse(o: JSONObject) = StageSpec(
            stage = o.getString("stage"),
            label = o.getString("label"),
            file = o.getString("file"),
            graph = o.getString("graph"),
            sizeBytes = o.optLong("sizeBytes"),
            inputs = tensors(o, "inputs"),
            outputs = tensors(o, "outputs"),
        )

        private fun tensors(o: JSONObject, key: String): List<TensorSpec> {
            val array = o.optJSONArray(key) ?: return emptyList()
            return (0 until array.length()).map { index ->
                val tensor = array.getJSONObject(index)
                val dims = tensor.getJSONArray("dims")
                TensorSpec(
                    name = tensor.getString("name"),
                    dtype = tensor.getString("dtype"),
                    dims = (0 until dims.length()).map(dims::getInt),
                )
            }
        }
    }
}

/** Manifest id for a direction — keeps the enum free of string literals. */
val TranslationDirection.manifestId: String
    get() = when (this) {
        TranslationDirection.VI_TO_EN -> "vi-en"
        TranslationDirection.EN_TO_VI -> "en-vi"
    }
