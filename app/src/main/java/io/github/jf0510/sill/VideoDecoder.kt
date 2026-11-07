// SPDX-License-Identifier: Apache-2.0
// Adapted from Sill by Noah Saffer; see NOTICE for provenance and modifications.

package io.github.jf0510.sill

import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaCodecList
import android.media.MediaFormat
import android.os.Handler
import android.os.HandlerThread
import android.util.Log
import android.view.Surface
import java.nio.ByteBuffer
import java.util.concurrent.atomic.AtomicInteger

/** All MediaCodec calls run on one thread, with a bounded compressed-frame backlog. */
class VideoDecoder(
    val onNeedsKeyframe: () -> Unit,
    val onFormat: (Int, Int, String) -> Unit,
    val onError: (String) -> Unit,
    val onUnsupportedSize: (Int, Int) -> Unit,
    val onRecovered: () -> Unit,
    val onPicture: () -> Unit,
) {
    private val thread =
        HandlerThread("sill-video", android.os.Process.THREAD_PRIORITY_DISPLAY).apply { start() }
    private val handler = Handler(thread.looper)
    private var codec: MediaCodec? = null
    private var surface: Surface? = null
    private var config: Hevc.Config? = null
    private var configBytes: ByteArray? = null
    private var codecConfigPending = false
    private var waiting = true
    private var suspended = false
    private val pending = AtomicInteger(0)
    private val epoch = AtomicInteger(0)
    private val session = AtomicInteger(0)
    @Volatile private var lost = false
    private var blocked = false
    private var retryAt = 0L
    private var framesShown = 0
    private var pictureReported = false
    private var recoveryPts: Long? = null
    private val received = AtomicInteger(0)
    private val overflows = AtomicInteger(0)

    private data class EncodedFrame(
        val bytes: ByteArray,
        val key: Boolean,
        val receivedAt: Long,
        val epoch: Int,
    )

    private val frames = java.util.ArrayDeque<EncodedFrame>()
    private val lastArrival = java.util.concurrent.atomic.AtomicLong(0)
    private val arrivalGap = java.util.concurrent.atomic.AtomicLong(0)
    private var queueWaitUs = 0L
    private var queueWaitMaxUs = 0L
    private var submitted = 0
    private var inputTimeouts = 0
    private var latencyTotalUs = 0L
    private var latencyMaxUs = 0L
    private var statsFrames = 0
    private var statsAt = System.nanoTime()
    private var presentedAt = 0L
    private var presentedFrames = 0
    private var lastPresented = 0L
    private var longestGapNs = 0L

    private fun reportStats() {
        val now = System.nanoTime()
        val seconds = (now - statsAt) / 1e9
        if (seconds < 5) return
        Log.i(
            "SillVideo",
            "5s stats: received=${received.getAndSet(0)}, output=$statsFrames, outputFps=${"%.1f".format(statsFrames/seconds)}, receiveToOutputAvgMs=${if(statsFrames>0)latencyTotalUs/statsFrames/1000 else 0}, maxMs=${latencyMaxUs/1000}, receiveGapMaxMs=${arrivalGap.getAndSet(0)/1000}, queueWaitAvgMs=${if(submitted>0)queueWaitUs/submitted/1000 else 0}, queueWaitMaxMs=${queueWaitMaxUs/1000}, compressedOverflows=${overflows.getAndSet(0)}, inputTimeouts=$inputTimeouts",
        )
        statsAt = now
        queueWaitUs = 0
        queueWaitMaxUs = 0
        submitted = 0
        statsFrames = 0
        latencyTotalUs = 0
        latencyMaxUs = 0
        inputTimeouts = 0
    }

    private val info = MediaCodec.BufferInfo()
    private val drain =
        object : Runnable {
            override fun run() {
                try {
                    pump()
                    reportStats()
                } catch (e: Exception) {
                    failed(e)
                }
                if (codec != null) handler.postDelayed(this, 2)
            }
        }
    // REGULAR_CODECS excludes codecs restricted to secure/tunneled playback. Query once, not per
    // frame.
    private val decoders by lazy {
        MediaCodecList(MediaCodecList.REGULAR_CODECS)
            .codecInfos
            .filter {
                !it.isEncoder &&
                    !it.isAlias &&
                    it.supportedTypes.any { t -> t.equals("video/hevc", true) }
            }
            .sortedWith(
                compareByDescending<MediaCodecInfo> { it.isHardwareAccelerated }
                    .thenByDescending {
                        it.getCapabilitiesForType("video/hevc")
                            .isFeatureSupported(MediaCodecInfo.CodecCapabilities.FEATURE_LowLatency)
                    }
            )
            .also { list ->
                list.forEach { decoder ->
                    val caps = decoder.getCapabilitiesForType("video/hevc")
                    val v = caps.videoCapabilities
                    Log.i(
                        "SillVideo",
                        "${decoder.name}: hardware=${decoder.isHardwareAccelerated}, width=${v.supportedWidths}, height=${v.supportedHeights}, 5K=${v.isSizeSupported(5120,2880)}, 1440p=${v.isSizeSupported(2560,1440)}",
                    )
                }
            }
    }

    fun attach(s: Surface?) {
        handler.post {
            if (surface !== s) {
                stop()
                surface = s
                start()
            }
        }
    }

    fun configure(bytes: ByteArray) {
        val owner = session.get()
        handler.post {
            if (owner != session.get()) return@post
            try {
                if (configBytes?.contentEquals(bytes) == true) return@post
                val c = Hevc.config(bytes)
                stop()
                config = c
                configBytes = bytes
                blocked = false
                retryAt = 0
                onFormat(c.width, c.height, "")
                start()
            } catch (e: Exception) {
                failed(e)
            }
        }
    }

    fun awaitKeyframe() {
        handler.post {
            waiting = true
            recoveryPts = null
            if (!blocked) onNeedsKeyframe()
        }
    }

    fun frame(bytes: ByteArray, key: Boolean) {
        val receivedAt = System.nanoTime() / 1000
        val previous = lastArrival.getAndSet(receivedAt)
        if (previous > 0) arrivalGap.accumulateAndGet(receivedAt - previous, ::maxOf)
        received.incrementAndGet()
        val frameEpoch = epoch.get()
        // TCP/TLS commonly delivers several frames together. Four pending frames caused
        // healthy bursts to flush the reference chain and freeze until a new keyframe.
        // This is a capacity limit, not a playout delay: frames are consumed immediately.
        if (pending.incrementAndGet() > 32) {
            pending.decrementAndGet()
            overflows.incrementAndGet()
            epoch.incrementAndGet()
            lost = true
            return
        }
        handler.post {
            if (frameEpoch != epoch.get() || blocked || suspended) {
                pending.decrementAndGet()
                return@post
            }
            frames.addLast(EncodedFrame(bytes, key, receivedAt, frameEpoch))
            try {
                pump()
            } catch (e: Exception) {
                failed(e)
            }
        }
    }

    private fun discardFrames() {
        while (frames.isNotEmpty()) {
            frames.removeFirst()
            pending.decrementAndGet()
        }
    }

    private fun pump() {
        if (blocked || suspended) {
            discardFrames()
            return
        }
        if (lost) {
            lost = false
            waiting = true
            recoveryPts = null
            codec?.let {
                it.flush()
                // A flush before the first output can discard the CSD supplied to configure().
                // Submit it again before any video access unit, including during startup recovery.
                codecConfigPending = true
            }
            onNeedsKeyframe()
        }
        if (codec == null) start()
        val c =
            codec
                ?: run {
                    discardFrames()
                    return
                }
        val cfg = config ?: return
        drainOutput()
        if (codecConfigPending) {
            val index = c.dequeueInputBuffer(0)
            if (index < 0) return
            val buffer = requireNotNull(c.getInputBuffer(index))
            require(buffer.capacity() >= cfg.csd.size) { "Video configuration is too large." }
            buffer.clear()
            buffer.put(cfg.csd)
            c.queueInputBuffer(index, 0, cfg.csd.size, 0, MediaCodec.BUFFER_FLAG_CODEC_CONFIG)
            codecConfigPending = false
            Log.i("SillVideo", "Resubmitted HEVC configuration after decoder flush")
        }
        while (frames.isNotEmpty()) {
            val next = frames.first
            if (next.epoch != epoch.get() || waiting && !next.key) {
                frames.removeFirst()
                pending.decrementAndGet()
                if (waiting) onNeedsKeyframe()
                continue
            }
            // Backpressure is not packet loss. Keep the compressed frame and retry after
            // draining output, without blocking this thread or breaking HEVC references.
            val index = c.dequeueInputBuffer(0)
            if (index < 0) return
            val frame = Hevc.annexB(next.bytes, cfg.lengthSize)
            val buffer = requireNotNull(c.getInputBuffer(index))
            require(buffer.capacity() >= frame.size) {
                "Video frame is too large for this decoder."
            }
            buffer.clear()
            buffer.put(frame)
            val waitUs = System.nanoTime() / 1000 - next.receivedAt
            queueWaitUs += waitUs
            queueWaitMaxUs = maxOf(queueWaitMaxUs, waitUs)
            submitted++
            if (waiting && next.key) recoveryPts = next.receivedAt
            c.queueInputBuffer(index, 0, frame.size, next.receivedAt, 0)
            frames.removeFirst()
            pending.decrementAndGet()
            waiting = false
            drainOutput()
        }
    }

    private fun start() {
        val s = surface ?: return
        val cfg = config ?: return
        if (
            !s.isValid ||
                suspended ||
                codec != null ||
                blocked ||
                android.os.SystemClock.elapsedRealtime() < retryAt
        )
            return
        try {
            // Keep capability queries separate from CSD, allocation sizes and runtime tuning keys.
            val query = MediaFormat.createVideoFormat("video/hevc", cfg.width, cfg.height)
            val candidates =
                decoders.filter {
                    runCatching { it.getCapabilitiesForType("video/hevc").isFormatSupported(query) }
                        .getOrDefault(false)
                }
            if (candidates.isEmpty()) {
                blocked = true
                Log.w(
                    "SillVideo",
                    "Unsupported ${cfg.width}x${cfg.height}; waiting for a smaller stream, not retrying this format",
                )
                onUnsupportedSize(cfg.width, cfg.height)
                return
            }
            var lastFailure: Exception? = null
            for (chosen in candidates) {
                var created: MediaCodec? = null
                try {
                    val format =
                        MediaFormat.createVideoFormat("video/hevc", cfg.width, cfg.height).apply {
                            setByteBuffer("csd-0", ByteBuffer.wrap(cfg.csd))
                            setInteger(MediaFormat.KEY_MAX_INPUT_SIZE, 32 * 1024 * 1024)
                            setInteger(MediaFormat.KEY_PRIORITY, 0)
                            // Supply a real-time throughput hint instead of the vendor default 30
                            // fps.
                            setFloat(MediaFormat.KEY_OPERATING_RATE, 120f)
                            setInteger(MediaFormat.KEY_FRAME_RATE, 120)
                            if (
                                chosen
                                    .getCapabilitiesForType("video/hevc")
                                    .isFeatureSupported(
                                        MediaCodecInfo.CodecCapabilities.FEATURE_LowLatency
                                    )
                            )
                                setInteger(MediaFormat.KEY_LOW_LATENCY, 1)
                        }
                    created = MediaCodec.createByCodecName(chosen.name)
                    created.configure(format, s, null, 0)
                    created.setOnFrameRenderedListener(
                        { active, _, nanoTime ->
                            if (active === codec) {
                                if (presentedAt == 0L) presentedAt = nanoTime
                                if (lastPresented > 0)
                                    longestGapNs = maxOf(longestGapNs, nanoTime - lastPresented)
                                lastPresented = nanoTime
                                presentedFrames++
                                if (nanoTime - presentedAt >= 5_000_000_000L) {
                                    Log.i(
                                        "SillVideo",
                                        "Surface presentation: fps=${"%.1f".format(presentedFrames*1e9/(nanoTime-presentedAt))}, longestGapMs=${longestGapNs/1_000_000}",
                                    )
                                    presentedAt = nanoTime
                                    presentedFrames = 0
                                    longestGapNs = 0
                                }
                            }
                        },
                        handler,
                    )
                    created.start()
                    codec = created
                    presentedAt = 0
                    presentedFrames = 0
                    lastPresented = 0
                    longestGapNs = 0
                    waiting = true
                    framesShown = 0
                    pictureReported = false
                    statsAt = System.nanoTime()
                    lastArrival.set(0)
                    arrivalGap.set(0)
                    queueWaitUs = 0
                    queueWaitMaxUs = 0
                    submitted = 0
                    statsFrames = 0
                    latencyTotalUs = 0
                    latencyMaxUs = 0
                    received.set(0)
                    overflows.set(0)
                    inputTimeouts = 0
                    Log.i("SillVideo", "Started ${chosen.name} for ${cfg.width}x${cfg.height}")
                    onFormat(cfg.width, cfg.height, chosen.name)
                    onNeedsKeyframe()
                    handler.removeCallbacks(drain)
                    handler.post(drain)
                    return
                } catch (e: Exception) {
                    runCatching { created?.release() }
                    lastFailure = e
                    Log.w("SillVideo", "${chosen.name} could not start", e)
                }
            }
            throw lastFailure ?: IllegalStateException("Could not start a video decoder.")
        } catch (e: Exception) {
            failed(e)
        }
    }

    private fun drainOutput() {
        val c = codec ?: return
        while (true) {
            val i = c.dequeueOutputBuffer(info, 0)
            if (i >= 0) {
                val latency = (System.nanoTime() / 1000 - info.presentationTimeUs).coerceAtLeast(0)
                latencyTotalUs += latency
                latencyMaxUs = maxOf(latencyMaxUs, latency)
                statsFrames++
                c.releaseOutputBuffer(i, true)
                framesShown++
                if (recoveryPts == info.presentationTimeUs) {
                    recoveryPts = null
                    onRecovered()
                }
                if (!pictureReported) {
                    pictureReported = true
                    Log.i("SillVideo", "First picture rendered by ${c.name}")
                    onPicture()
                }
                if (framesShown % 300 == 0)
                    Log.i("SillVideo", "Rendered $framesShown frames with ${c.name}")
            } else if (i == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                val f = c.outputFormat
                onFormat(
                    f.getInteger(MediaFormat.KEY_WIDTH),
                    f.getInteger(MediaFormat.KEY_HEIGHT),
                    c.name,
                )
            } else break
        }
    }

    private fun failed(e: Exception) {
        stop()
        retryAt = android.os.SystemClock.elapsedRealtime() + 2000
        onError("Picture interrupted: ${e.message ?: "decoder error"}")
        onNeedsKeyframe()
    }

    private fun stop() {
        discardFrames()
        handler.removeCallbacks(drain)
        codec?.let {
            runCatching { it.stop() }
            runCatching { it.release() }
        }
        codec = null
        codecConfigPending = false
        waiting = true
        recoveryPts = null
    }

    fun reset() {
        session.incrementAndGet()
        epoch.incrementAndGet()
        handler.post {
            stop()
            config = null
            configBytes = null
            blocked = false
            retryAt = 0
            lost = false
        }
    }

    fun suspendVideo() {
        handler.post {
            suspended = true
            stop()
        }
    }

    fun resumeVideo() {
        handler.post {
            suspended = false
            start()
        }
    }

    fun close() {
        handler.post {
            stop()
            thread.quitSafely()
        }
    }
}
