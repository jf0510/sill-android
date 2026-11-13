// SPDX-License-Identifier: Apache-2.0
// Wire behavior adapted from Sill Audio.swift; Android decoder/output implementation.
package io.github.jf0510.sill

import android.media.*
import android.os.Handler
import android.os.HandlerThread
import android.util.Base64
import java.nio.ByteBuffer
import java.util.concurrent.atomic.AtomicInteger
import org.json.JSONObject

/** A bounded audio worker: a slow speaker must never stall the video reader. */
class AudioOutput(context: android.content.Context, private val problem: (String) -> Unit) {
    private val thread =
        HandlerThread("SillAudio", android.os.Process.THREAD_PRIORITY_AUDIO).apply { start() }
    private val handler = Handler(thread.looper)
    private val pending = AtomicInteger()
    private val resets = AtomicInteger()
    private val manager = context.getSystemService(AudioManager::class.java)
    private var focusHeld = false
    private var focusLost = false
    private val attributes =
        AudioAttributes.Builder()
            .setUsage(AudioAttributes.USAGE_MEDIA)
            .setContentType(AudioAttributes.CONTENT_TYPE_MOVIE)
            .build()
    private val focus =
        AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN)
            .setAudioAttributes(attributes)
            .setOnAudioFocusChangeListener(
                { value ->
                    if (value == AudioManager.AUDIOFOCUS_GAIN) {
                        focusLost = false
                    } else {
                        focusLost = true
                        release(abandonFocus = false)
                    }
                },
                handler,
            )
            .build()
    private val drainTask =
        object : Runnable {
            override fun run() {
                runCatching { drain() }
                    .onFailure {
                        failedEpoch = format?.optInt("epoch", -1) ?: -1
                        release()
                        problem("Audio decoder stopped. Toggle Mute or reconnect to retry.")
                    }
                if (codec != null) handler.postDelayed(this, 10)
            }
        }
    private var codec: MediaCodec? = null
    private var track: AudioTrack? = null
    private var format: JSONObject? = null
    private var epoch = -1
    private var failedEpoch = -1
    private var expected = -1L
    private var priming = 0
    private var channels = 2
    private var pts = 0L
    @Volatile
    var enabled = true
        set(value) {
            val changed = field != value
            field = value
            if (changed)
                handler.post {
                    focusLost = false
                    failedEpoch = -1
                }
            if (!value && changed) reset()
        }

    val supported: Boolean =
        runCatching {
                MediaCodecList(MediaCodecList.REGULAR_CODECS).codecInfos.any { info ->
                    !info.isEncoder &&
                        info.supportedTypes.any { it.equals("audio/mp4a-latm", true) } &&
                        info.getCapabilitiesForType("audio/mp4a-latm").profileLevels.any {
                            it.profile == MediaCodecInfo.CodecProfileLevel.AACObjectELD
                        }
                }
            }
            .getOrDefault(false)

    fun accept(payload: ByteArray) {
        if (payload.isEmpty()) return
        val owner = resets.get()
        if (pending.incrementAndGet() > 24) {
            pending.decrementAndGet()
            reset()
            return
        }
        handler.post {
            try {
                if (owner != resets.get()) return@post
                if (payload[0].toInt() == 1 && payload.size <= 4096) {
                    release()
                    failedEpoch = -1
                    format =
                        JSONObject(payload.copyOfRange(1, payload.size).toString(Charsets.UTF_8))
                    expected = -1
                } else if (enabled && !focusLost) {
                    val p = SoundPackets.parse(payload) ?: return@post
                    val f = format ?: return@post
                    if (failedEpoch == f.optInt("epoch", -1)) return@post
                    if (p.epoch != f.optInt("epoch", -1)) return@post
                    if (codec == null || p.segment || (expected >= 0 && expected != p.sequence))
                        configure(f)
                    if (codec == null) return@post
                    if (epoch != p.epoch) return@post
                    for (packet in p.packets) {
                        drain()
                        val c = codec ?: break
                        val i = c.dequeueInputBuffer(10000)
                        if (i < 0) {
                            release()
                            break
                        }
                        val input = c.getInputBuffer(i) ?: break
                        if (packet.size > input.capacity()) {
                            release()
                            break
                        }
                        input.clear()
                        input.put(packet)
                        c.queueInputBuffer(i, 0, packet.size, pts, 0)
                        pts +=
                            f.optInt("framesPerPacket", 480) * 1_000_000L /
                                f.optInt("sampleRate", 48000)
                        drain()
                    }
                    expected = (p.sequence + p.packets.size) and 0xffffffffL
                }
            } catch (e: Exception) {
                failedEpoch = format?.optInt("epoch", -1) ?: -1
                release()
                problem("Audio unavailable on this device: ${e.message ?: "decoder error"}")
            } finally {
                pending.decrementAndGet()
            }
        }
    }

    private fun configure(f: JSONObject) {
        release()
        if (focusLost || !enabled) return
        if (manager.requestAudioFocus(focus) != AudioManager.AUDIOFOCUS_REQUEST_GRANTED) {
            focusLost = true
            return
        }
        focusHeld = true
        require(f.optString("codec") == "aac-eld")
        val rate = f.optInt("sampleRate")
        channels = f.optInt("channels")
        require(rate in 8000..96000 && channels in 1..2)
        val cookie =
            requireNotNull(
                AacCookie.configuration(Base64.decode(f.getString("cookie"), Base64.DEFAULT))
            ) {
                "Invalid AAC-ELD configuration"
            }
        require(cookie.size in 2..1024)
        val mf =
            MediaFormat.createAudioFormat("audio/mp4a-latm", rate, channels).apply {
                setInteger(
                    MediaFormat.KEY_AAC_PROFILE,
                    MediaCodecInfo.CodecProfileLevel.AACObjectELD,
                )
                setInteger(
                    MediaFormat.KEY_PCM_ENCODING,
                    android.media.AudioFormat.ENCODING_PCM_16BIT,
                )
                setByteBuffer("csd-0", ByteBuffer.wrap(cookie))
            }
        codec = MediaCodec.createDecoderByType("audio/mp4a-latm")
        codec!!.configure(mf, null, null, 0)
        codec!!.start()
        val mask =
            if (channels == 2) android.media.AudioFormat.CHANNEL_OUT_STEREO
            else android.media.AudioFormat.CHANNEL_OUT_MONO
        track =
            AudioTrack.Builder()
                .setAudioAttributes(attributes)
                .setAudioFormat(
                    android.media.AudioFormat.Builder()
                        .setSampleRate(rate)
                        .setChannelMask(mask)
                        .setEncoding(android.media.AudioFormat.ENCODING_PCM_16BIT)
                        .build()
                )
                .setBufferSizeInBytes(
                    maxOf(
                        AudioTrack.getMinBufferSize(
                            rate,
                            mask,
                            android.media.AudioFormat.ENCODING_PCM_16BIT,
                        ),
                        rate * channels / 5,
                    )
                )
                .setTransferMode(AudioTrack.MODE_STREAM)
                .build()
        track!!.play()
        epoch = f.optInt("epoch", -1)
        priming = f.optInt("primingFrames", 0).coerceIn(0, 4096) * channels * 2
        pts = 0
        problem("")
        handler.post(drainTask)
    }

    private fun drain() {
        val c = codec ?: return
        val info = MediaCodec.BufferInfo()
        while (true) {
            val i = c.dequeueOutputBuffer(info, 0)
            if (i == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) continue
            if (i < 0) break
            val b = c.getOutputBuffer(i)
            if (b != null && info.size > 0) {
                val skip = minOf(priming, info.size)
                priming -= skip
                b.position(info.offset + skip)
                b.limit(info.offset + info.size)
                if (enabled) track?.write(b, b.remaining(), AudioTrack.WRITE_NON_BLOCKING)
            }
            c.releaseOutputBuffer(i, false)
        }
    }

    private fun release(abandonFocus: Boolean = true) {
        handler.removeCallbacks(drainTask)
        if (focusHeld && abandonFocus) {
            manager.abandonAudioFocusRequest(focus)
            focusHeld = false
        }
        runCatching { codec?.stop() }
        runCatching { codec?.release() }
        codec = null
        runCatching {
            track?.pause()
            track?.flush()
            track?.release()
        }
        track = null
        epoch = -1
    }

    fun reset() {
        resets.incrementAndGet()
        handler.post {
            release()
            expected = -1
        }
    }

    fun newSession() {
        resets.incrementAndGet()
        handler.post {
            release()
            format = null
            expected = -1
        }
    }

    fun close() {
        handler.post {
            release()
            thread.quitSafely()
        }
    }
}
