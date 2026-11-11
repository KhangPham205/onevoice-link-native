package com.onevoice.link.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class AsrBiasLibrariesTest {
    @get:Rule
    val tmp = TemporaryFolder()

    private fun library(dir: File, id: String, name: String?, terms: Int = 3): File =
        File(dir, "$id.txt").apply {
            writeText(
                buildString {
                    appendLine("# onevoice-asr-bias v1 source=$id.tsv sha256=0 bpe=bpe.model")
                    appendLine("# id: $id")
                    if (name != null) appendLine("# name: $name")
                    appendLine("# terms: $terms")
                    appendLine("-\t1\tsai ích eo\tsize XL\t▁SAI ▁ÍCH ▁E O")
                },
            )
        }

    @Test
    fun discoversLibrariesByHeaderSortedByName() {
        val dir = tmp.newFolder("asr_gipformer_bias")
        library(dir, "xuong_may", "Xưởng may", terms = 157)
        library(dir, "xuong_noi_that", "Xưởng nội thất")
        library(dir, "gia_dung", "Xưởng gia dụng")
        val found = AsrBiasLibraries.discover(dir)
        assertEquals(listOf("Xưởng gia dụng", "Xưởng may", "Xưởng nội thất"), found.map { it.name })
        assertEquals(157, found.single { it.id == "xuong_may" }.terms)
    }

    @Test
    fun skipsFilesThatAreNotCompiledLibraries() {
        val dir = tmp.newFolder("asr_gipformer_bias")
        library(dir, "xuong_may", "Xưởng may")
        File(dir, "notes.txt").writeText("not a library\n")
        File(dir, "Bad-Id.txt").writeText("# onevoice-asr-bias v1\n")
        library(dir, "other", "Mismatch").renameTo(File(dir, "renamed.txt")) // header id != file name
        assertEquals(listOf("xuong_may"), AsrBiasLibraries.discover(dir).map { it.id })
    }

    @Test
    fun nameFallsBackToIdAndMissingDirectoryIsEmpty() {
        val dir = tmp.newFolder("asr_gipformer_bias")
        library(dir, "xuong_may", name = null)
        assertEquals("xuong_may", AsrBiasLibraries.discover(dir).single().name)
        assertEquals(emptyList<AsrBiasLibrary>(), AsrBiasLibraries.discover(File(dir, "missing")))
    }

    @Test
    fun resolvesDefaultPersistedAndOff() {
        val dir = tmp.newFolder("asr_gipformer_bias")
        library(dir, "gia_dung", "Xưởng gia dụng")
        library(dir, "xuong_may", "Xưởng may")
        val libraries = AsrBiasLibraries.discover(dir)
        // Never chosen: Xưởng may, even though another sorts first.
        assertEquals("xuong_may", AsrBiasLibraries.resolve(libraries, null)?.id)
        assertEquals("gia_dung", AsrBiasLibraries.resolve(libraries, "gia_dung")?.id)
        assertNull(AsrBiasLibraries.resolve(libraries, AsrBiasPreferences.OFF))
        // A chosen library that is no longer staged falls back to the default.
        assertEquals("xuong_may", AsrBiasLibraries.resolve(libraries, "removed")?.id)
        assertNull(AsrBiasLibraries.resolve(emptyList(), null))
    }

    @Test
    fun compiledLibraryParsesAsABiasList() {
        val dir = tmp.newFolder("asr_gipformer_bias")
        val file = library(dir, "xuong_may", "Xưởng may")
        val pieces = mapOf("▁SAI" to 10, "▁ÍCH" to 11, "▁E" to 12, "O" to 13)
        val list = AsrBiasList.parse(file.readLines(), pieces, 1.5)
        assertEquals(1, list.phrases.size)
        assertEquals("áo size XL", list.rewrite("áo sai ích eo"))
    }
}
