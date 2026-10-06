package com.example.ava.microwakeword

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.example.ava.openwakeword.OpenWakeWordDetector
import com.example.ava.openwakeword.OpenWakeWordProvider
import com.example.microfeatures.OpenWakeWordEngine
import java.nio.ByteBuffer
import java.nio.ByteOrder
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class WakeWordEngineSmokeTest {
    private val assets
        get() = InstrumentationRegistry.getInstrumentation().targetContext.assets

    @Test
    fun microWakeWordLoadsModelAndRejectsSilence() {
        val detector = WakeWordDetector(AssetWakeWordProvider(assets))
        try {
            detector.setActiveWakeWords(listOf(MICRO_MODEL_ID))
            assertEquals(MICRO_DEFAULT_CUTOFF, detector.getProbabilityCutoff(MICRO_MODEL_ID)!!, 0.0001f)

            val detections = buildList {
                repeat(MICRO_SILENCE_CHUNKS) {
                    addAll(detector.detect(silencePcm(MICRO_CHUNK_SAMPLES)))
                }
            }
            assertTrue("microWakeWord triggered on digital silence: $detections", detections.isEmpty())
            assertNotNull(privateField(detector, "frontend"))

            detector.updateProbabilityCutoff(MICRO_MODEL_ID, 0.9f)
            assertEquals(0.9f, detector.getProbabilityCutoff(MICRO_MODEL_ID)!!, 0.0001f)
            detector.reset()
            assertTrue(detector.detect(silencePcm(MICRO_CHUNK_SAMPLES)).isEmpty())
        } finally {
            detector.close()
        }
    }

    @Test
    fun openWakeWordLoadsModelAndRejectsSilence() {
        val provider = OpenWakeWordProvider(assets)
        assertTrue(provider.listModels().any { it.id == OPEN_MODEL_ID })

        val detector = OpenWakeWordDetector(provider)
        try {
            detector.setActiveWakeWords(listOf(OPEN_MODEL_ID))
            assertNotNull("openWakeWord native engine failed to initialize", privateField(detector, "engine"))

            detector.updateProbabilityCutoff(OPEN_MODEL_ID, 0.55f)
            val detections = buildList {
                repeat(OPEN_SILENCE_CHUNKS) {
                    addAll(detector.detect(silencePcm(OpenWakeWordEngine.CHUNK_SAMPLES)))
                }
            }
            assertTrue("openWakeWord triggered on digital silence: $detections", detections.isEmpty())

            detector.reset()
            assertTrue(detector.detect(silencePcm(OpenWakeWordEngine.CHUNK_SAMPLES)).isEmpty())
        } finally {
            detector.close()
        }
    }

    private fun silencePcm(sampleCount: Int): ByteBuffer =
        ByteBuffer.allocateDirect(sampleCount * Short.SIZE_BYTES)
            .order(ByteOrder.LITTLE_ENDIAN)
            .apply {
                repeat(sampleCount) { putShort(0) }
                flip()
            }

    private fun privateField(instance: Any, name: String): Any? =
        instance.javaClass.getDeclaredField(name).run {
            isAccessible = true
            get(instance)
        }

    companion object {
        private const val MICRO_MODEL_ID = "okay_nabu"
        private const val MICRO_DEFAULT_CUTOFF = 0.79f
        private const val MICRO_CHUNK_SAMPLES = 160
        private const val MICRO_SILENCE_CHUNKS = 300

        private const val OPEN_MODEL_ID = "ok_nabu"
        private const val OPEN_SILENCE_CHUNKS = 40
    }
}
