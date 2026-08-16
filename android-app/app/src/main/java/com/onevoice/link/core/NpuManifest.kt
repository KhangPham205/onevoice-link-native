package com.onevoice.link.core

import android.content.Context
import org.json.JSONObject
import java.io.File

/**
 * The deploy contract for the NPU cascade, shipped as the `npu_manifest.json`
 * asset and generated from the context binaries themselves with
 * `qnn-context-binary-utility` (see docs/HANDOFF_FORM.md).
 *
 * Graph names are AI Hub-generated (`graph_oa00nu3e`, `graph_shbblv7u`,
 * `opus_mt_encoder`, …) and
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
        private const val SCHEMA_VERSION = 1
        private val REQUIRED_DIRECTIONS = setOf("vi-en", "en-vi")
        private val REQUIRED_STAGES = setOf("asr", "mt_encoder", "mt_decoder")

        fun load(context: Context): NpuManifest {
            val text = context.assets.open(ASSET).bufferedReader().use { it.readText() }
            val o = JSONObject(text)
            require(o.getInt("schemaVersion") == SCHEMA_VERSION) {
                "Unsupported NPU manifest schema ${o.optInt("schemaVersion", -1)}"
            }
            val soc = o.getJSONObject("targetSoc")
            val dirs = o.getJSONArray("directions")
            return NpuManifest(
                targetSocModel = soc.getInt("qnnSocModel"),
                targetSocName = soc.getString("socModel"),
                targetMarketing = soc.getString("marketing"),
                hexagon = soc.getString("hexagon"),
                modelRoot = o.getString("modelRoot"),
                directions = (0 until dirs.length()).map { DirectionSpec.parse(dirs.getJSONObject(it)) },
            ).also(::validateProductionContract)
        }

        private fun validateProductionContract(manifest: NpuManifest) {
            require(manifest.targetSocModel > 0 && manifest.targetSocName.isNotBlank() &&
                manifest.hexagon.isNotBlank()) {
                "NPU manifest has an invalid target SoC contract"
            }
            require(isSafeRelativePath(manifest.modelRoot)) {
                "Unsafe NPU modelRoot: ${manifest.modelRoot}"
            }
            val directionIds = manifest.directions.map(DirectionSpec::id)
            require(directionIds.size == REQUIRED_DIRECTIONS.size &&
                directionIds.toSet() == REQUIRED_DIRECTIONS) {
                "NPU manifest directions must be exactly $REQUIRED_DIRECTIONS, got $directionIds"
            }
            manifest.directions.forEach { direction ->
                require(direction.available) {
                    "Hybrid direction ${direction.id} is unavailable: " +
                        (direction.unavailableReason ?: "no reason supplied")
                }
                val stageIds = direction.stages.map(StageSpec::stage)
                require(stageIds.size == REQUIRED_STAGES.size &&
                    stageIds.toSet() == REQUIRED_STAGES) {
                    "${direction.id} stages must be exactly $REQUIRED_STAGES, got $stageIds"
                }
                direction.stages.forEach { stage ->
                    require(stage.file.startsWith("${direction.id}/") &&
                        isSafeRelativePath(stage.file)) {
                        "Unsafe/cross-direction model path for ${direction.id}/${stage.stage}: " +
                            stage.file
                    }
                    require(stage.graph.isNotBlank() && stage.sizeBytes > 0L) {
                        "Invalid graph/file contract for ${direction.id}/${stage.stage}"
                    }
                    require(stage.inputs.isNotEmpty() && stage.outputs.isNotEmpty()) {
                        "Missing tensor contract for ${direction.id}/${stage.stage}"
                    }
                    (stage.inputs + stage.outputs).forEach { tensor ->
                        require(tensor.name.isNotBlank() && tensor.dtype.isNotBlank() &&
                            tensor.dims.isNotEmpty() && tensor.dims.all { it > 0 }) {
                            "Invalid tensor contract for ${direction.id}/${stage.stage}: $tensor"
                        }
                    }
                }
            }
        }

        private fun isSafeRelativePath(path: String): Boolean =
            path.isNotBlank() && !File(path).isAbsolute &&
                path.split('/', '\\').none { it.isBlank() || it == "." || it == ".." }
    }
}

/** One translation direction and the cascade stages it needs. */
data class DirectionSpec(
    val id: String,
    val label: String,
    /**
     * False only when a complete direction bundle is unavailable. Production
     * currently ships both VI→EN and EN→VI, including their QNN ASR encoders.
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
    /** Path relative to the model root, e.g. `vi-en/asr_gipformer_encoder.bin`. */
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
