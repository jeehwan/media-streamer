package com.github.jeehwan

import android.media.*
import android.os.Process
import android.util.Log
import android.view.Surface
import net.ossrs.rtmp.ConnectCheckerRtmp
import net.ossrs.rtmp.SrsFlvMuxer
import java.util.concurrent.atomic.AtomicLong
import kotlin.concurrent.thread

/**
 *
 */
class MediaStreamer {

    companion object {
        const val TAG = "MediaStreamer"

        private const val FRAME_SIZE = 2048
    }

    private var surface: Surface? = null

    private var videoEncThread: Thread? = null
    private var videoEncoder: MediaCodec? = null

    private var audioRecThread: Thread? = null
    private var audioRecord: AudioRecord? = null

    private var audioEncThread: Thread? = null
    private var audioEncoder: MediaCodec? = null

    private var srsFlvMuxer: SrsFlvMuxer? = null

    private var url = ""
    private var videoWidth = 0
    private var videoHeight = 0

    // default parameters for video encoder
    private var fps = 30
    private var videoBitrate = 0
    private var videoRotation = 0

    // default parameters for audio record & encoder
    private var sampleRate = 32000 // hz
    private var audioFormat = AudioFormat.ENCODING_PCM_16BIT
    private var channelConfig = AudioFormat.CHANNEL_IN_STEREO
    private var channelCount = 2
    private var audioBitrate = 64 * 1024 // 64 K

    private enum class State {
        UNINITIALIZED,
        INITIALIZED,
        STARTED,
        STOPPED,
        RELEASED,
    }

    private var state = State.UNINITIALIZED

    fun setUrl(url: String) {
        if (state != State.UNINITIALIZED) {
            throw IllegalStateException()
        }

        this.url = url
    }

    fun setVideoSize(width: Int, height: Int) {
        if (state != State.UNINITIALIZED) {
            throw IllegalStateException()
        }

        this.videoWidth = width
        this.videoHeight = height
    }

    fun setVideoFrameRate(fps: Int) {
        if (state != State.UNINITIALIZED) {
            throw IllegalStateException()
        }

        this.fps = fps
    }

    fun setVideoEncodingBitrate(bitrateInBps: Int) {
        if (state != State.UNINITIALIZED) {
            throw IllegalStateException()
        }

        this.videoBitrate = bitrateInBps
    }

    fun setOrientationHint(rotation: Int) {
        if (state != State.UNINITIALIZED) {
            throw IllegalStateException()
        }

        Log.d(TAG, "setOrientationHint($rotation)")
        videoRotation = rotation
    }

    fun setAudioSampleRate(sampleRate: Int) {
        if (state != State.UNINITIALIZED) {
            throw IllegalStateException()
        }

        this.sampleRate = sampleRate
    }

    fun setAudioStereo(stereo: Boolean) {
        if (state != State.UNINITIALIZED) {
            throw IllegalStateException()
        }

        if (stereo) {
            channelConfig = AudioFormat.CHANNEL_IN_STEREO
            channelCount = 2
        } else {
            channelConfig = AudioFormat.CHANNEL_IN_MONO
            channelCount = 1
        }
    }

    fun setAudioBitrate(bitrate: Int) {
        if (state != State.UNINITIALIZED) {
            throw IllegalStateException()
        }

        audioBitrate = bitrate
    }

    private fun MediaCodecList.findEncoderForMime(mime: String): String =
            findEncoderForFormat(MediaFormat().apply {
                setString(MediaFormat.KEY_MIME, mime)
            }) ?: throw IllegalArgumentException("$mime is not supported")

    fun prepare() {
        if (state != State.UNINITIALIZED) {
            throw IllegalStateException("Can't prepare due to wrong state.")
        }

        val allCodecs = MediaCodecList(MediaCodecList.ALL_CODECS)

        // prepare video
        val videoCodecName = allCodecs.findEncoderForMime(MediaFormat.MIMETYPE_VIDEO_AVC)

        videoEncoder = MediaCodec.createByCodecName(videoCodecName).apply {
            val videoFormat = MediaFormat.createVideoFormat(
                    MediaFormat.MIMETYPE_VIDEO_AVC,
                    videoWidth,
                    videoHeight
            ).apply {
                setInteger(MediaFormat.KEY_BIT_RATE, videoBitrate)
                setInteger(MediaFormat.KEY_BITRATE_MODE, MediaCodecInfo.EncoderCapabilities.BITRATE_MODE_VBR)
                setInteger(MediaFormat.KEY_COLOR_FORMAT, MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface)
                setInteger(MediaFormat.KEY_FRAME_RATE, fps)
                setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, 1)
                setInteger(MediaFormat.KEY_ROTATION, videoRotation)
            }

            Log.d(TAG, "video enc format: $videoFormat")

            configure(videoFormat, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
        }

        surface = videoEncoder?.createInputSurface()

        // prepare audio
        audioRecord = AudioRecord(MediaRecorder.AudioSource.DEFAULT,
                sampleRate,
                channelConfig,
                audioFormat,
                AudioRecord.getMinBufferSize(sampleRate, channelConfig, audioFormat) * 5)

        val audioCodecName = allCodecs.findEncoderForMime(MediaFormat.MIMETYPE_AUDIO_AAC)

        audioEncoder = MediaCodec.createByCodecName(audioCodecName).apply {
            val audioFormat = MediaFormat.createAudioFormat(
                    MediaFormat.MIMETYPE_AUDIO_AAC,
                    sampleRate,
                    channelCount
            ).apply {
                setInteger(MediaFormat.KEY_BIT_RATE, audioBitrate)
                setInteger(MediaFormat.KEY_MAX_INPUT_SIZE, FRAME_SIZE * channelCount)
                setInteger(MediaFormat.KEY_AAC_PROFILE, MediaCodecInfo.CodecProfileLevel.AACObjectLC)
            }

            configure(audioFormat, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
        }

        srsFlvMuxer = SrsFlvMuxer(object : ConnectCheckerRtmp {
            override fun onConnectionSuccessRtmp() {
                Log.d(TAG, "onConnectionSuccessRtmp")
            }

            override fun onConnectionFailedRtmp(reason: String) {
                Log.d(TAG, "onConnectionFailedRtmp $reason")
            }

            override fun onDisconnectRtmp() {
                Log.d(TAG, "onDisconnectRtmp")
            }

            override fun onAuthErrorRtmp() {
                Log.d(TAG, "onAuthErrorRtmp")
            }

            override fun onAuthSuccessRtmp() {
                Log.d(TAG, "onAuthSuccessRtmp")
            }
        }).apply {
            setVideoResolution(videoWidth, videoHeight)
            setSampleRate(sampleRate)
            setIsStereo(channelConfig == AudioFormat.CHANNEL_IN_STEREO)
            start(url)
        }

        state = State.INITIALIZED
    }

    fun start() {
        if (state != State.INITIALIZED && state != State.STOPPED) {
            throw IllegalStateException("Can't start due to wrong state.");
        }

        val mediaStartTimestamp = AtomicLong(-1L)

        audioRecord!!.startRecording()
        audioEncoder!!.start()
        videoEncoder!!.start()

        videoEncThread = thread(name="VideoThread") {
            val videoEnc = videoEncoder!!

            val bufferInfo = MediaCodec.BufferInfo()
            while (!Thread.interrupted()) {
                val outputIndex = videoEnc.dequeueOutputBuffer(bufferInfo, 10_000L)
                when (outputIndex) {
                    in 0..Int.MAX_VALUE -> {
                        val outputBuffer = videoEnc.getOutputBuffer(outputIndex)!!.apply {
                            position(bufferInfo.offset)
                            limit(bufferInfo.offset + bufferInfo.size)
                        }

                        if (bufferInfo.presentationTimeUs > 0) {
                            mediaStartTimestamp.compareAndSet(-1L, bufferInfo.presentationTimeUs)
                        }

                        //bufferInfo.presentationTimeUs = System.nanoTime() / 1000 - startPtsUs
                        bufferInfo.presentationTimeUs -= mediaStartTimestamp.get()

                        srsFlvMuxer?.sendVideo(outputBuffer, bufferInfo)

                        videoEnc.releaseOutputBuffer(outputIndex, false)
                    }
                    MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                        val outputFormat = videoEnc.outputFormat
                        //outputFormat.setInteger(MediaFormat.KEY_ROTATION, videoRotation)
                        //rtmpMuxer?.setAVCFormat(outputFormat)
                        val sps = outputFormat.getByteBuffer("csd-0")
                        val pps = outputFormat.getByteBuffer("csd-1")
                        srsFlvMuxer?.setSpsPPs(sps, pps)
                        Log.d(TAG, "video output format changed $outputFormat")
                    }
                    MediaCodec.INFO_OUTPUT_BUFFERS_CHANGED -> {}
                    MediaCodec.INFO_TRY_AGAIN_LATER -> {}
                    else -> throw RuntimeException()
                }
            }
        }

        audioRecThread = thread(name="AudioRecThread") {
            Process.setThreadPriority(Process.THREAD_PRIORITY_AUDIO)

            val audioRec = audioRecord!!
            val audioEnc = audioEncoder!!

            val startPtsUs = System.nanoTime() / 1000
            var outSamples = 0L

            fun bytesPerSample(audioFormat: Int): Int = when (audioFormat) {
                AudioFormat.ENCODING_PCM_FLOAT -> 4
                AudioFormat.ENCODING_PCM_16BIT -> 2
                AudioFormat.ENCODING_PCM_8BIT -> 1
                else -> throw IllegalArgumentException()
            }

            while (!Thread.interrupted()) {
                val inputIndex = audioEnc.dequeueInputBuffer(10_000L)
                if (inputIndex < 0) {
                    Log.w(TAG, "audio buffer is not available")
                    continue
                }

                val inputBuffer = audioEnc.getInputBuffer(inputIndex)!!

                val size = audioRec.read(inputBuffer, inputBuffer.remaining())
                if (size < 0) {
                    break
                }

                //val ptsUs = System.nanoTime() / 1000 - startPtsUs
                val ptsUs = startPtsUs + (1_000_000L * outSamples / sampleRate)
                outSamples += size / channelCount / bytesPerSample(audioFormat)

                audioEnc.queueInputBuffer(inputIndex, 0, size, ptsUs, 0)
            }
        }

        audioEncThread = thread(name="AudioEncThread") {
            val audioEnc = audioEncoder!!

            val bufferInfo = MediaCodec.BufferInfo()
            while (!Thread.interrupted()) {
                val outputIndex = audioEnc.dequeueOutputBuffer(bufferInfo, 10_000L)
                when (outputIndex) {
                    in 0..Int.MAX_VALUE -> {
                        val outputBuffer = audioEnc.getOutputBuffer(outputIndex)!!.apply {
                            position(bufferInfo.offset)
                            limit(bufferInfo.offset + bufferInfo.size)
                        }

                        if (bufferInfo.presentationTimeUs > 0) {
                            mediaStartTimestamp.compareAndSet(-1L, bufferInfo.presentationTimeUs)
                        }

                        //bufferInfo.presentationTimeUs = System.nanoTime() / 1000 - startPtsUs
                        bufferInfo.presentationTimeUs -= mediaStartTimestamp.get()

                        srsFlvMuxer?.sendAudio(outputBuffer, bufferInfo)

                        audioEnc.releaseOutputBuffer(outputIndex, false)
                    }
                    MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                        Log.d(TAG, "audio output format changed ${audioEnc.outputFormat}")
                    }
                    MediaCodec.INFO_OUTPUT_BUFFERS_CHANGED -> {}
                    MediaCodec.INFO_TRY_AGAIN_LATER -> {}
                    else -> throw RuntimeException()
                }
            }
        }

        state = State.STARTED
    }

    fun getSurface(): Surface {
        return surface ?: throw IllegalStateException()
    }

    fun stop() {
        if (state != State.STARTED || state == State.RELEASED) {
            throw IllegalStateException("Can't stop due to wrong state.")
        }

        audioRecThread?.interrupt()
        audioEncThread?.interrupt()
        videoEncThread?.interrupt()

        audioRecThread?.join()
        audioEncThread?.join()
        videoEncThread?.join()

        audioRecThread = null
        audioEncThread = null
        videoEncThread = null

        srsFlvMuxer?.stop()

        audioRecord?.stop()
        audioEncoder?.stop()
        videoEncoder?.stop()

        state = State.STOPPED
    }

    fun reset() {
        if (state == State.RELEASED) {
            throw IllegalStateException("Can't reset due to wrong state.")
        }

        if (state == State.STARTED) {
            stop()
        }

        videoEncoder?.reset()
        surface?.release()
        surface = null

        audioRecord?.release()
        audioRecord = null

        audioEncoder?.reset()

        srsFlvMuxer = null

        state = State.UNINITIALIZED
    }

    fun release() {
        if (state == State.STARTED) {
            stop()
        }

        videoEncoder?.release()
        videoEncoder = null

        surface?.release()
        surface = null

        audioRecord?.release()
        audioRecord = null

        audioEncoder?.release()
        audioEncoder = null

        state = State.RELEASED
    }

}