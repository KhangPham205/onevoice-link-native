package com.onevoice.link

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.onevoice.link.core.NpuEngine
import com.onevoice.link.core.NpuTtsMode
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/** Runs every learned VI -> EN stage through QnnGraph_execute on the phone. */
@RunWith(AndroidJUnit4::class)
class NpuPipelineInstrumentedTest {
    @Test
    fun hybridUsesThreeQnnStagesAndContinuousCpuTts() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val engine = NpuEngine(context, NpuTtsMode.CPU_WHOLE_UTTERANCE)
        try {
            assertTrue(engine.initialize())
            assertEquals(3, engine.loadedModelCount())
            val result = engine.runGraphSmokeTest(
                "Xin chào, hôm nay bạn như thế nào?",
                playAudio = true,
            )
            assertTrue(result.translation.isNotBlank())
            assertTrue(result.audioSamples > 24_000)
        } finally {
            engine.destroy()
        }
    }

    @Test
    fun allQnnStagesExecute() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val engine = NpuEngine(context)
        try {
            assertTrue(engine.initialize())
            assertEquals(6, engine.loadedModelCount())
            val result = engine.runGraphSmokeTest(
                "Xin chào, tôi muốn kiểm tra toàn bộ hệ thống dịch giọng nói ngoại tuyến " +
                    "trên điện thoại để chắc chắn rằng câu dài không bị ngắt giữa chừng.",
                playAudio = true,
            )
            assertTrue(result.translation.isNotBlank())
            // Regression guard for the former 200-frame TTS cap (~5 seconds).
            assertTrue(result.audioSamples > 24_000 * 5)
        } finally {
            engine.destroy()
        }
    }
}
