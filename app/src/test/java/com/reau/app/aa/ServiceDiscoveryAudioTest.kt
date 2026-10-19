package com.reau.app.aa

import com.reau.app.aa.proto.Control
import com.reau.app.aa.proto.Media
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The sound for the web server. Off, the service discovery response is byte for byte the one of before (only
 * the system sink, whose sound is thrown away, so the navigation voice stays on the phone and goes to the rider's
 * headset). On, it also announces the media sink (48 kHz stereo, channel 6), the guidance sink (16 kHz mono, channel
 * 4) and, as an experiment (Gemini's voice stayed on the phone), the speech sink (16 kHz mono, channel 14): Android
 * Auto then sends those sounds to REau, which plays them on the phone, in the browser, or both.
 */
class ServiceDiscoveryAudioTest {
    private fun response(config: AaConfig): Control.ServiceDiscoveryResponse =
        ServiceDiscoveryResponse(config).parse(Control.ServiceDiscoveryResponse.newBuilder()).build()

    /** The channels the optional switches add (the music status 9, the navigation 10, the phone 12), in the order they come. */
    private fun AaConfig.extraServices(): List<Int> =
        listOfNotNull(Channel.ID_NAV.takeIf { navigation }, Channel.ID_MPB.takeIf { media }, Channel.ID_PHONE.takeIf { phone })

    private fun sink(response: Control.ServiceDiscoveryResponse, channel: Int) =
        response.servicesList.single { it.id == channel }.mediaSinkService

    @Test
    fun `off by default, and off the response is the one of before`() {
        assertFalse(AaConfig().audio)
        // What was announced before this branch: the sensors, the video, the input, the system sink and the microphone, nothing of sound for REau.
        val before = listOf(Channel.ID_SEN, Channel.ID_VID, Channel.ID_INP, Channel.ID_AU2, Channel.ID_MIC)
        for (base in listOf(AaConfig(), AaConfig(touch = true, media = true, phone = true), AaConfig(navigation = true))) {
            assertEquals(before + base.extraServices(), response(base).servicesList.map { it.id })
            assertArrayEquals(ServiceDiscoveryResponse(base).data, ServiceDiscoveryResponse(base.copy(audio = false)).data)
        }
        val off = response(AaConfig())
        assertEquals("only the system sink", listOf(Channel.ID_AU2), off.servicesList.filter { it.hasMediaSinkService() && it.id != Channel.ID_VID }.map { it.id })
    }

    @Test
    fun `on adds the media, guidance and speech sinks, and nothing else changes`() {
        val on = response(AaConfig(audio = true))
        val off = response(AaConfig())
        assertEquals(off.servicesList.size + 3, on.servicesList.size)
        val added = setOf(Channel.ID_AUD, Channel.ID_AU1, Channel.ID_SPE)
        assertEquals("the others, in their order", off.servicesList, on.servicesList.filter { it.id !in added })
    }

    @Test
    fun `the speech sink is 16 kHz 16 bit mono PCM of the speech type on channel 14`() {
        val speech = sink(response(AaConfig(audio = true)), Channel.ID_SPE)
        assertEquals(14, Channel.ID_SPE)
        assertEquals(Media.MediaCodecType.MEDIA_CODEC_AUDIO_PCM, speech.availableType)
        assertEquals(Media.AudioStreamType.SPEECH, speech.audioType)
        val config = speech.audioConfigsList.single()
        assertEquals(16_000, config.sampleRate)
        assertEquals(16, config.numberOfBits)
        assertEquals(1, config.numberOfChannels)
    }

    @Test
    fun `no two services share a channel number`() {
        val ids = response(AaConfig(audio = true, navigation = true, media = true, phone = true)).servicesList.map { it.id }
        assertEquals(ids.size, ids.toSet().size)
    }

    @Test
    fun `the media sink is 48 kHz 16 bit stereo PCM on channel 6`() {
        val media = sink(response(AaConfig(audio = true)), Channel.ID_AUD)
        assertEquals(Media.MediaCodecType.MEDIA_CODEC_AUDIO_PCM, media.availableType)
        assertEquals(Media.AudioStreamType.MEDIA, media.audioType)
        val config = media.audioConfigsList.single()
        assertEquals(48_000, config.sampleRate)
        assertEquals(16, config.numberOfBits)
        assertEquals(2, config.numberOfChannels)
    }

    @Test
    fun `the guidance sink is 16 kHz 16 bit mono PCM on channel 4`() {
        val guidance = sink(response(AaConfig(audio = true)), Channel.ID_AU1)
        assertEquals(Media.MediaCodecType.MEDIA_CODEC_AUDIO_PCM, guidance.availableType)
        assertEquals(Media.AudioStreamType.GUIDANCE, guidance.audioType)
        val config = guidance.audioConfigsList.single()
        assertEquals(16_000, config.sampleRate)
        assertEquals(16, config.numberOfBits)
        assertEquals(1, config.numberOfChannels)
    }

    @Test
    fun `the system sink and the microphone stay as they were`() {
        val on = response(AaConfig(audio = true))
        val system = sink(on, Channel.ID_AU2)
        assertEquals(Media.AudioStreamType.SYSTEM, system.audioType)
        assertEquals(16_000, system.audioConfigsList.single().sampleRate)
        assertTrue(on.servicesList.any { it.id == Channel.ID_MIC && it.hasMediaSourceService() })
    }
}
