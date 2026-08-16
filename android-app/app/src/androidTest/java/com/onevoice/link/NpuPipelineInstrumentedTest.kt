package com.onevoice.link

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.onevoice.link.core.NpuEngine
import com.onevoice.link.core.NpuTtsMode
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/** Runs the VI branch while both auto-direction ASR/MT context sets are resident. */
@RunWith(AndroidJUnit4::class)
class NpuPipelineInstrumentedTest {
    @Test
    fun autoHybridKeepsBothDirectionsAndUsesContinuousCpuTts() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val engine = NpuEngine(context, NpuTtsMode.CPU_WHOLE_UTTERANCE)
        try {
            assertTrue(engine.initialize())
            // VI ASR + VI MT enc/dec + EN ASR + EN MT enc/dec.
            assertEquals(6, engine.loadedModelCount())
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
}
