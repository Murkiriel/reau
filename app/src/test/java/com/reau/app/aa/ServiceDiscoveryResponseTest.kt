package com.reau.app.aa

import com.reau.app.aa.proto.Control
import com.reau.app.aa.proto.Media
import com.reau.app.aa.proto.Sensors
import com.reau.app.video.AaMargins
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * What this "head unit" tells Android Auto it has. It decides
 * how Android Auto behaves: what it draws, where the voice goes, whether it shows a focus to move with keys.
 */
class ServiceDiscoveryResponseTest {

    private fun response(config: AaConfig = AaConfig()): Control.ServiceDiscoveryResponse {
        val message = ServiceDiscoveryResponse(config)
        assertEquals(Channel.ID_CTR, message.channel)
        assertEquals(Control.ControlMsgType.MESSAGE_SERVICE_DISCOVERY_RESPONSE_VALUE, message.type)
        return message.parse(Control.ServiceDiscoveryResponse.newBuilder()).build()
    }

    private fun Control.ServiceDiscoveryResponse.service(channel: Int) = servicesList.single { it.id == channel }

    @Test
    fun `it advertises exactly five services, on the channels the receiver handles`() {
        assertEquals(
            listOf(Channel.ID_SEN, Channel.ID_VID, Channel.ID_INP, Channel.ID_AU2, Channel.ID_MIC),
            response().servicesList.map { it.id },
        )
    }

    @Test
    fun `sensors are the driving status and night, nothing else`() {
        val sensors = response().service(Channel.ID_SEN).sensorSourceService.sensorsList.map { it.type }
        assertEquals(listOf(Sensors.SensorType.DRIVING_STATUS, Sensors.SensorType.NIGHT), sensors)
    }

    @Test
    fun `video is H264 baseline at 30 frames a second, 800x480 at 160 dpi with no margins unless configured`() {
        val sink = response().service(Channel.ID_VID).mediaSinkService
        assertEquals(Media.MediaCodecType.MEDIA_CODEC_VIDEO_H264_BP, sink.availableType)
        assertEquals(Media.AudioStreamType.NONE, sink.audioType)
        assertTrue(sink.availableWhileInCall)
        assertEquals(0, sink.audioConfigsCount)

        val video = sink.videoConfigsList.single()
        assertEquals(Control.Service.MediaSinkService.VideoConfiguration.VideoCodecResolutionType._800x480, video.codecResolution)
        assertEquals(Control.Service.MediaSinkService.VideoConfiguration.VideoFrameRateType._30, video.frameRate)
        assertEquals(160, video.density)
        assertEquals(0, video.marginWidth)
        assertEquals(0, video.marginHeight)
        assertEquals(Media.MediaCodecType.MEDIA_CODEC_VIDEO_H264_BP, video.videoCodecType)
    }

    @Test
    fun `density and margins come from the configuration, and the frame is always 800x480`() {
        // The larger frame sizes were deleted; the frame is AaVideo's.
        val config = AaConfig(density = 240, margins = AaMargins(26, 4))
        val video = response(config).service(Channel.ID_VID).mediaSinkService.videoConfigsList.single()
        assertEquals(Control.Service.MediaSinkService.VideoConfiguration.VideoCodecResolutionType._800x480, video.codecResolution)
        assertEquals(800 to 480, AaVideo.WIDTH to AaVideo.HEIGHT)
        assertEquals(240, video.density)
        assertEquals(26, video.marginWidth)
        assertEquals(4, video.marginHeight)
    }

    @Test
    fun `input is keys and the wheel, and no touchscreen`() {
        val input = response().service(Channel.ID_INP).inputSourceService
        // The wheel (65536), the four arrows, confirm, back; and home (3) and assistant (84) (the dash's joystick:
        // without home it cannot reach Android Auto's app list). Changed on purpose. Next and previous song (87, 88),
        // for the joystick's double tap. Changed on purpose.
        // Play/pause (85), for the double tap down. Changed on purpose.
        // Call (5) and end call (6), the car's phone keys for the joystick's answer and end. Changed on purpose.
        assertEquals(listOf(65536, 19, 20, 21, 22, 23, 4, 3, 84, 87, 88, 85, 5, 6), input.keycodesSupportedList)
        assertFalse("a dash with no touch must not advertise one", input.hasTouchscreen())
    }

    @Test
    fun `with the phone touch switched on, a touch screen the size of the video is announced and the keys stay`() {
        // Off by default (the test above: no touch screen); on, the phone's screen
        // "Tela do Android Auto" touches Android Auto, and the dash's joystick keeps its keys and wheel.
        val input = response(AaConfig(touch = true)).service(Channel.ID_INP).inputSourceService
        assertTrue(input.hasTouchscreen())
        assertEquals(800, input.touchscreen.width)
        assertEquals(480, input.touchscreen.height)
        assertEquals(ServiceDiscoveryResponse.SUPPORTED_KEYCODES, input.keycodesSupportedList)
    }

    @Test
    fun `the only audio sink is the system one, so the navigation voice stays on the phone`() {
        val r = response()
        val system = r.service(Channel.ID_AU2).mediaSinkService
        assertEquals(Media.MediaCodecType.MEDIA_CODEC_AUDIO_PCM, system.availableType)
        assertEquals(Media.AudioStreamType.SYSTEM, system.audioType)
        val format = system.audioConfigsList.single()
        assertEquals(16_000, format.sampleRate)
        assertEquals(16, format.numberOfBits)
        assertEquals(1, format.numberOfChannels)

        assertTrue("no media audio channel", r.servicesList.none { it.id == Channel.ID_AUD })
        assertTrue("no speech audio channel", r.servicesList.none { it.id == Channel.ID_AU1 })
        val audioTypes = r.servicesList.filter { it.hasMediaSinkService() }.map { it.mediaSinkService.audioType }
        assertTrue("got $audioTypes", Media.AudioStreamType.MEDIA !in audioTypes && Media.AudioStreamType.SPEECH !in audioTypes)
    }

    @Test
    fun `a microphone is advertised, 16 kHz mono`() {
        val mic = response().service(Channel.ID_MIC).mediaSourceService
        assertEquals(Media.MediaCodecType.MEDIA_CODEC_AUDIO_PCM, mic.type)
        assertEquals(16_000, mic.audioConfig.sampleRate)
        assertEquals(16, mic.audioConfig.numberOfBits)
        assertEquals(1, mic.audioConfig.numberOfChannels)
    }

    @Test
    fun `no navigation status, bluetooth, phone, notification or media playback service`() {
        val r = response()
        for (channel in listOf(Channel.ID_NAV, Channel.ID_BTH, Channel.ID_PHONE, Channel.ID_NOTI, Channel.ID_MPB, Channel.ID_WIFI)) {
            assertTrue("channel $channel must not be advertised", r.servicesList.none { it.id == channel })
        }
        assertTrue(r.servicesList.none { it.hasNavigationStatusService() || it.hasBluetoothService() })
    }

    @Test
    fun `the head unit says who it is, with the driver on the left`() {
        val r = response()
        assertEquals("REau", r.make)
        assertEquals("REau", r.displayName)
        assertEquals("REau", r.model)
        assertEquals("REau", r.headUnitMake)
        assertEquals("REau", r.headUnitModel)
        assertEquals(Control.DriverPosition.DRIVER_POSITION_LEFT, r.driverPosition)
        assertFalse(r.canPlayNativeMediaDuringVr)
        assertFalse(r.hideProjectedClock)
        assertEquals("the same identity in the newer field", r.make, r.headunitInfo.make)
        assertEquals(r.headUnitModel, r.headunitInfo.headUnitModel)
        assertTrue("every required field must be set", r.isInitialized)
    }
}
