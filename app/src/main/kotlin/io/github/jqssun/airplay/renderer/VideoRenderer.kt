package io.github.jqssun.airplay.renderer

import android.content.Context
import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaFormat
import android.util.Log
import android.view.Surface
import io.github.jqssun.airplay.bridge.NativeBridge
import io.github.jqssun.airplay.renderer.DecoderSelector.Companion.videoCaps

/*
 * mirror video decoder. the raop thread only queues compressed frames (never blocks on the
 * codec, a stalled tcp reader makes the sender drop the session); a decoder thread feeds
 * each frame so it is shown at pts + target delay, where the target delay is the audio
 * path's measured delay, so picture and sound line up. video's own minimum delay (network
 * lateness + decode time) goes back to audio as a floor, so audio never runs ahead
 */
class VideoRenderer(ctx: Context) {

    private class Frame(
        val data: ByteArray, val ptsNs: Long, val h265: Boolean,
        val arrivalNs: Long, val width: Int, val height: Int,
    )

    private val lock = Object()       // codec state
    private val queueLock = Object()  // frame queue
    private val queue = ArrayDeque<Frame>()
    private var needKeyframe = false  // queueLock: queue overflowed, resume at next keyframe
    private var thread: Thread? = null
    @Volatile private var running = false

    private val pipeline = VideoPipeline()
    val selector = DecoderSelector(ctx)
    private var avcDecoder: MediaCodecInfo? = null
    private var hevcDecoder: MediaCodecInfo? = null
    private var maxFps = 0
    private var codec: MediaCodec? = null
    private var displaySurface: Surface? = null
    private var currentH265 = false
    private var adaptiveCodec = false
    @Volatile private var videoWidth = 0
    @Volatile private var videoHeight = 0
    private var codecWidth = 0
    private var codecHeight = 0
    private var codecMaxWidth = 0
    private var codecMaxHeight = 0

    // a/v sync, decoder thread only
    var avSync: AvSync? = null
    private var _realtimeOffsetNs = 0L
    private var _realtimeOffsetAtNs = 0L
    private var _arrivalLatePeakNs = -1L
    private var _decodeLatencyNs = 0L
    private var _targetDelayNs = -1L
    private var _audioFloorSentNs = 0L
    private var _audioFloorSentAtNs = 0L
    private var _audioDelayCheckedAtNs = 0L
    private var _audioDelayNs = -1L
    private val _inPtsUs = LongArray(64)
    private val _inQueuedNs = LongArray(64)
    private var _inIdx = 0
    @Volatile var avOffsetMs = 0L; private set  // video target delay vs audio, for the overlay

    // stats
    @Volatile var fps = 0; private set
    @Volatile var bitrateBps = 0L; private set
    @Volatile var frameCount = 0L; private set
    @Volatile var codecName = ""; private set
    @Volatile var droppedFrames = 0L; private set
    @Volatile var framePacingJitterUs = 0L; private set

    var enforceSdr = true
    var keyAllowFrameDrop = true
    var scheduledOutputBufferRelease = true
    var benchmarkLog = false
    var benchmarkLogCallback: ((String) -> Unit)? = null
    private var _framesThisSec = 0
    private var _bytesThisSec = 0L
    private var _lastStatReset = 0L
    private val _frameIntervalsNs = LongArray(120)
    private var _frameIntervalIdx = 0
    private var _frameIntervalCount = 0
    private var _lastOutputFrameNs = 0L

    fun setResolution(w: Int, h: Int) {
        videoWidth = w
        videoHeight = h
        pipeline.setVideoSize(w, h)
    }

    // doesn't restart codec; decoder renders into pipeline's own persistent surface
    fun setSurface(surface: Surface) = synchronized(lock) {
        displaySurface = surface
        pipeline.setDisplaySurface(surface)
    }

    fun clearSurface(surface: Surface) = synchronized(lock) {
        if (displaySurface !== surface) return@synchronized
        displaySurface = null
        pipeline.setDisplaySurface(null)
    }

    fun selectDecoders(w: Int, h: Int, fps: Int, h265: Boolean): Boolean = synchronized(lock) {
        avcDecoder = selector.avc()
        hevcDecoder = if (h265) selector.hevc(avcDecoder, w, h, fps) else null
        maxFps = fps
        Log.i(TAG, "decoders: avc=${avcDecoder?.name} hevc=${hevcDecoder?.name}")
        hevcDecoder != null
    }

    // unknown limits default to 1080p
    fun maxResolution(): Pair<Int, Int> =
        listOfNotNull(avcDecoder?.let { it to DecoderSelector.AVC }, hevcDecoder?.let { it to DecoderSelector.HEVC })
            .map { (info, mime) ->
                runCatching { info.videoCaps(mime).let { it.supportedWidths.upper to it.supportedHeights.upper } }
                    .getOrDefault(1920 to 1080)
            }
            .reduceOrNull { (w1, h1), (w2, h2) -> minOf(w1, w2) to minOf(h1, h2) } ?: (1920 to 1080)

    // codec per mirror session; pipeline persists across sessions
    fun startSession() {
        synchronized(lock) { _resetStats() }
        _startThread()
    }

    fun stopSession() {
        _stopThread()
        synchronized(lock) { stopCodec() }
    }

    private fun _resetStats() {
        fps = 0; bitrateBps = 0; frameCount = 0; codecName = ""
        droppedFrames = 0; framePacingJitterUs = 0
        _framesThisSec = 0; _bytesThisSec = 0
    }

    private fun _updateStats(size: Int) {
        val now = System.currentTimeMillis()
        if (now - _lastStatReset >= 1000) {
            fps = _framesThisSec
            bitrateBps = _bytesThisSec * 8
            framePacingJitterUs = _computeFramePacingJitterUs()
            _framesThisSec = 0
            _bytesThisSec = 0
            _lastStatReset = now
            if (benchmarkLog) _emitBenchmarkLine()
        }
        _framesThisSec++
        _bytesThisSec += size
        frameCount++
    }

    private fun _emitBenchmarkLine() {
        val msg = "fps=$fps bitrate=${bitrateBps / 1000}kbps " +
            "jitter=${framePacingJitterUs}us frames=$frameCount " +
            "dropped=$droppedFrames codec=$codecName " +
            "res=${videoWidth}x${videoHeight} av=${avOffsetMs}ms"
        Log.i(BENCH_TAG, msg)
        benchmarkLogCallback?.invoke(msg)
    }

    // raop mirror thread: queue and return, never touches the codec
    fun feedFrame(data: ByteArray, ntpTimeNs: Long, isH265: Boolean) {
        val arrival = System.nanoTime()
        val w = videoWidth
        val h = videoHeight
        if (w == 0 || h == 0) return
        synchronized(queueLock) {
            _updateStats(data.size)
            if (queue.size >= MAX_QUEUED_FRAMES) {
                // decoder wedged: frames past this point would only add latency
                Log.w(TAG, "Decoder backlog ${queue.size} frames; flushing until next keyframe")
                droppedFrames += queue.size
                queue.clear()
                needKeyframe = true
            }
            if (needKeyframe) {
                if (!_isKeyframe(data, isH265)) { droppedFrames++; return }
                needKeyframe = false
            }
            queue.addLast(Frame(data, ntpTimeNs, isH265, arrival, w, h))
            queueLock.notifyAll()
        }
        if (!running) _startThread()
    }

    private fun _startThread() = synchronized(queueLock) {
        if (running) return@synchronized
        running = true
        _targetDelayNs = -1L
        _arrivalLatePeakNs = -1L
        _decodeLatencyNs = 0L
        _audioDelayNs = -1L
        _audioDelayCheckedAtNs = 0L
        thread = Thread({ _decodeLoop() }, "VideoDecoder").also {
            it.priority = Thread.MAX_PRIORITY
            it.start()
        }
    }

    private fun _stopThread() {
        var t: Thread? = null
        synchronized(queueLock) {
            running = false
            queue.clear()
            needKeyframe = false
            queueLock.notifyAll()
            t = thread
            thread = null
        }
        if (t != null && t !== Thread.currentThread()) t.join(THREAD_JOIN_MS)
        avSync?.setAudioFloorNs(0)
        _audioFloorSentNs = 0L
        avOffsetMs = 0L
    }

    private fun _decodeLoop() {
        while (running) {
            var frame: Frame? = null
            synchronized(queueLock) {
                val head = queue.firstOrNull()
                if (head == null) {
                    queueLock.wait(POLL_MS)
                } else {
                    val waitNs = _feedAtNs(head) - System.nanoTime()
                    if (waitNs <= 0) frame = queue.removeFirst()
                    else queueLock.wait((waitNs / 1_000_000L).coerceIn(1L, POLL_MS))
                }
            }
            if (!running) break
            synchronized(lock) {
                try {
                    frame?.let { _decode(it) }
                    drainOutput()
                } catch (e: Exception) {
                    Log.w(TAG, "Codec error, resetting", e)
                    stopCodec()
                }
            }
        }
    }

    private fun _ptsMonoNs(f: Frame): Long {
        val now = System.nanoTime()
        if (now - _realtimeOffsetAtNs > OFFSET_REFRESH_NS) {
            _realtimeOffsetNs = NativeBridge.nativeRealtimeOffsetNs()
            _realtimeOffsetAtNs = now
        }
        if (f.ptsNs <= 0) return f.arrivalNs  // sender clock not synced yet
        val mono = f.ptsNs - _realtimeOffsetNs
        // implausible pts (clock step, bogus offset): fall back to arrival timing
        return if (f.arrivalNs - mono in -MAX_PLAUSIBLE_NS..MAX_PLAUSIBLE_NS) mono else f.arrivalNs
    }

    // when to hand the frame to the decoder so it is ready at pts + target delay
    private fun _feedAtNs(f: Frame): Long {
        val ptsMono = _ptsMonoNs(f)
        val target = _targetDelay()
        if (target < 0) return 0L  // nothing to sync against yet: as soon as possible
        return ptsMono + target - _decodeLatencyNs
    }

    // video's own minimum: worst recent network lateness + decode time + one frame margin
    private fun _videoMinDelayNs(): Long {
        if (_arrivalLatePeakNs < 0) return -1L
        val frameNs = 1_000_000_000L / maxOf(maxFps, 30)
        return (_arrivalLatePeakNs + _decodeLatencyNs + frameNs + RENDER_MARGIN_NS)
            .coerceIn(0L, MAX_VIDEO_DELAY_NS)
    }

    private fun _targetDelay(): Long {
        val now = System.nanoTime()
        if (now - _audioDelayCheckedAtNs > AUDIO_POLL_NS) {
            _audioDelayCheckedAtNs = now
            _audioDelayNs = avSync?.audioDelayNs() ?: -1L
        }
        val audio = _audioDelayNs
        val video = _videoMinDelayNs()
        // audio is the clock while it plays; frames it hasn't waited for are fed immediately,
        // the floor below makes audio wait for them shortly after
        val want = when {
            audio in 0..MAX_AUDIO_DELAY_NS -> audio
            video >= 0 -> video
            else -> -1L
        }
        if (want < 0) return _targetDelayNs
        val cur = _targetDelayNs
        // follow small drifts gently (no visible speed change), jump on real steps
        _targetDelayNs = if (cur < 0 || kotlin.math.abs(want - cur) > TARGET_SNAP_NS) want
            else cur + (want - cur) / 16
        avOffsetMs = if (audio >= 0) (_targetDelayNs - audio) / 1_000_000L else 0L
        return _targetDelayNs
    }

    private fun _noteArrival(f: Frame) {
        val late = f.arrivalNs - _ptsMonoNs(f)
        val peak = _arrivalLatePeakNs
        // peak hold with slow decay: absorbs wifi bursts without carrying them forever
        _arrivalLatePeakNs = if (peak < 0 || late > peak) late
            else maxOf(late, peak - LATE_DECAY_PER_FRAME_NS)

        val floor = _videoMinDelayNs()
        val now = System.nanoTime()
        if (floor >= 0 && (kotlin.math.abs(floor - _audioFloorSentNs) > FLOOR_STEP_NS ||
                now - _audioFloorSentAtNs > FLOOR_REFRESH_NS)) {
            avSync?.setAudioFloorNs(floor)
            _audioFloorSentNs = floor
            _audioFloorSentAtNs = now
        }
    }

    private fun _decode(f: Frame) {
        _noteArrival(f)
        val keyframe = _isKeyframe(f.data, f.h265)
        val mustRestart = codec == null || f.h265 != currentH265
        // adaptive decoders take new sizes in-stream up to their configured max; others need
        // a fresh codec. either way only at a keyframe: a stale reference decodes to garbage
        val sizeChanged = f.width != codecWidth || f.height != codecHeight
        val wantsRestart = sizeChanged &&
            (!adaptiveCodec || f.width > codecMaxWidth || f.height > codecMaxHeight)
        if (mustRestart || (wantsRestart && keyframe)) {
            if (!keyframe) {
                // no usable reference until the sender's next keyframe
                droppedFrames++
                return
            }
            if (codec != null) {
                Log.i(TAG, "Restarting codec: ${codecWidth}x${codecHeight} -> ${f.width}x${f.height}, H265: ${f.h265}")
            }
            stopCodec()
            startCodec(f.h265, f.width, f.height)
        } else if (sizeChanged && adaptiveCodec) {
            codecWidth = f.width
            codecHeight = f.height
        }
        _feedToCodec(f.data, f.ptsNs)
    }

    private fun _feedToCodec(data: ByteArray, ntpTimeNs: Long) {
        val c = codec ?: return
        // own thread: waiting here is harmless, and dropping a frame would corrupt video
        // until the next keyframe, which the sender only sends on (re)connect or resize
        val deadline = System.nanoTime() + FEED_TIMEOUT_NS
        while (running) {
            val idx = c.dequeueInputBuffer(FEED_WAIT_US)
            if (idx >= 0) {
                val buf = c.getInputBuffer(idx) ?: return
                buf.clear()
                buf.put(data)
                val ptsUs = ntpTimeNs / 1000
                c.queueInputBuffer(idx, 0, data.size, ptsUs, 0)
                _inPtsUs[_inIdx] = ptsUs
                _inQueuedNs[_inIdx] = System.nanoTime()
                _inIdx = (_inIdx + 1) % _inPtsUs.size
                return
            }
            drainOutput()
            if (System.nanoTime() > deadline) {
                // decoder wedged; a fresh one restarts at the next keyframe
                throw IllegalStateException("decoder accepted no input for ${FEED_TIMEOUT_NS / 1_000_000}ms")
            }
        }
    }

    private fun _isKeyframe(data: ByteArray, isH265: Boolean): Boolean {
        if (data.size < 5) return false
        var i = 0
        while (i <= data.size - 5) {
            if (data[i] == 0.toByte() && data[i + 1] == 0.toByte() &&
                data[i + 2] == 0.toByte() && data[i + 3] == 1.toByte()) {
                val key = if (isH265) {
                    val type = (data[i + 4].toInt() shr 1) and 0x3F
                    type == 19 || type == 20 || type == 21 || type == 32 || type == 33
                } else {
                    val type = data[i + 4].toInt() and 0x1F
                    type == 5 || type == 7
                }
                if (key) return true
            }
            i++
        }
        return false
    }

    private fun startCodec(h265: Boolean, w: Int, h: Int) {
        pipeline.start()
        pipeline.setVideoSize(w, h)
        val s = pipeline.inputSurface ?: return
        currentH265 = h265
        val mime = if (h265) DecoderSelector.HEVC else DecoderSelector.AVC
        val info = (if (h265) hevcDecoder else avcDecoder) ?: error("no decoder selected for $mime")

        // configure at the real stream size: an oversized config can exceed what the hw
        // decoder really supports and silently land on a slow software decoder
        codecWidth = w
        codecHeight = h
        adaptiveCodec = selector.adaptive(info, mime)
        // adaptive decoders get room for a rotation so turning the phone needs no restart
        val (maxW, maxH) = if (adaptiveCodec) _adaptiveMax(info, mime, w, h) else w to h
        try {
            _startWithMax(info, mime, s, h265, maxW, maxH, w, h)
        } catch (e: Exception) {
            // strict hw decoders reject configs beyond their real limits
            val sw = selector.software(mime, w, h) ?: throw e
            Log.w(TAG, "Hardware decoder failed, trying software fallback", e)
            adaptiveCodec = selector.adaptive(sw, mime)
            _startWithMax(sw, mime, s, h265, w, h, w, h)
        }
        Log.i(TAG, "Video codec started: $mime ${w}x${h} max=${codecMaxWidth}x${codecMaxHeight} " +
            "adaptive=$adaptiveCodec ($codecName)")
    }

    private fun _adaptiveMax(info: MediaCodecInfo, mime: String, w: Int, h: Int): Pair<Int, Int> {
        val side = maxOf(w, h)
        return runCatching {
            val caps = info.videoCaps(mime)
            val mw = minOf(side, caps.supportedWidths.upper)
            val mh = minOf(side, caps.supportedHeights.upper)
            if (caps.isSizeSupported(mw, mh)) mw to mh else w to h
        }.getOrDefault(w to h)
    }

    private fun _startWithMax(info: MediaCodecInfo, mime: String, s: Surface, h265: Boolean,
                              maxW: Int, maxH: Int, w: Int, h: Int) {
        try {
            _startWithLadder(info, mime, s, h265, w, h, maxW, maxH)
        } catch (e: Exception) {
            if (maxW == w && maxH == h) throw e
            Log.w(TAG, "Rotation headroom ${maxW}x${maxH} rejected, using exact size", e)
            _startWithLadder(info, mime, s, h265, w, h, w, h)
        }
    }

    private fun _startWithLadder(info: MediaCodecInfo, mime: String, s: Surface, h265: Boolean,
                                 w: Int, h: Int, maxW: Int, maxH: Int) {
        var tryNum = 0
        while (true) {
            val format = _format(mime, info, w, h, maxW, maxH)
            val more = selector.lowLatencyOptions(format, info, mime, tryNum)
            try {
                _startDecoder(MediaCodec.createByCodecName(info.name), format, s, h265)
                codecMaxWidth = maxW
                codecMaxHeight = maxH
                return
            } catch (e: Exception) {
                if (!more) throw e
                Log.w(TAG, "configure try $tryNum failed: $format", e)
                tryNum++
            }
        }
    }

    private fun _format(mime: String, info: MediaCodecInfo, w: Int, h: Int, maxW: Int, maxH: Int) =
        MediaFormat.createVideoFormat(mime, w, h).apply {
            setInteger(MediaFormat.KEY_FRAME_RATE, maxFps)
            if (selector.adaptive(info, mime)) {
                setInteger(MediaFormat.KEY_MAX_WIDTH, maxW)
                setInteger(MediaFormat.KEY_MAX_HEIGHT, maxH)
            }
            setInteger(MediaFormat.KEY_MAX_INPUT_SIZE, maxOf(maxW * maxH * 3 / 4, 1024 * 1024))
            if (enforceSdr) {
                setInteger(MediaFormat.KEY_COLOR_STANDARD, MediaFormat.COLOR_STANDARD_BT709)
                setInteger(MediaFormat.KEY_COLOR_RANGE, MediaFormat.COLOR_RANGE_LIMITED)
                setInteger(MediaFormat.KEY_COLOR_TRANSFER, MediaFormat.COLOR_TRANSFER_SDR_VIDEO)
            }
            if (android.os.Build.VERSION.SDK_INT >= 29) {
                setInteger(MediaFormat.KEY_ALLOW_FRAME_DROP, if (keyAllowFrameDrop) 1 else 0)
            }
        }

    private fun _startDecoder(c: MediaCodec, format: MediaFormat, surface: Surface, h265: Boolean) {
        try {
            c.configure(format, surface, null, 0)
            c.start()
        } catch (e: Exception) {
            try { c.release() } catch (_: Exception) {}
            throw e
        }
        codec = c
        codecName = (if (h265) "H.265" else "H.264") + " (${c.name})"
    }

    private fun stopCodec() {
        _frameIntervalIdx = 0
        _frameIntervalCount = 0
        _lastOutputFrameNs = 0L
        codecWidth = 0
        codecHeight = 0
        codecMaxWidth = 0
        codecMaxHeight = 0
        _inPtsUs.fill(Long.MIN_VALUE)
        codec?.let {
            try {
                it.stop()
                it.release()
            } catch (_: Exception) {}
        }
        codec = null
    }

    private fun drainOutput() {
        val c = codec ?: return
        val info = MediaCodec.BufferInfo()
        while (true) {
            val idx = c.dequeueOutputBuffer(info, 0)
            if (idx < 0) break
            _recordOutputFrameTime()
            val now = System.nanoTime()
            _noteDecodeLatency(info.presentationTimeUs, now)
            val target = _targetDelayNs
            if (scheduledOutputBufferRelease && target >= 0 && info.presentationTimeUs > 0) {
                val presentNs = info.presentationTimeUs * 1000L - _realtimeOffsetNs + target
                // only meaningful near now; far off means bogus pts
                if (presentNs in now..now + MAX_SCHEDULE_AHEAD_NS) c.releaseOutputBuffer(idx, presentNs)
                else c.releaseOutputBuffer(idx, true)
            } else {
                c.releaseOutputBuffer(idx, true)
            }
        }
    }

    private fun _noteDecodeLatency(ptsUs: Long, now: Long) {
        for (i in _inPtsUs.indices) {
            if (_inPtsUs[i] != ptsUs) continue
            val lat = (now - _inQueuedNs[i]).coerceIn(0L, MAX_DECODE_LATENCY_NS)
            _inPtsUs[i] = Long.MIN_VALUE
            _decodeLatencyNs = if (_decodeLatencyNs == 0L) lat else _decodeLatencyNs + (lat - _decodeLatencyNs) / 8
            return
        }
    }

    fun release() {
        _stopThread()
        synchronized(lock) {
            stopCodec()
            pipeline.release()
            _resetStats()
        }
    }

    private fun _recordOutputFrameTime() {
        val now = System.nanoTime()
        if (_lastOutputFrameNs > 0) {
            _frameIntervalsNs[_frameIntervalIdx % _frameIntervalsNs.size] = now - _lastOutputFrameNs
            _frameIntervalIdx++
            _frameIntervalCount++
        }
        _lastOutputFrameNs = now
    }

    private fun _computeFramePacingJitterUs(): Long {
        val count = _frameIntervalCount.coerceAtMost(_frameIntervalsNs.size)
        if (count < 2) return 0

        var sum = 0.0
        var sumSq = 0.0
        for (i in 0 until count) {
            val interval = _frameIntervalsNs[i].toDouble()
            sum += interval
            sumSq += interval * interval
        }
        val mean = sum / count
        val variance = (sumSq / count) - (mean * mean)
        return (kotlin.math.sqrt(variance.coerceAtLeast(0.0)) / 1000.0).toLong()
    }

    companion object {
        private const val TAG = "VideoRenderer"
        private const val BENCH_TAG = "BENCHMARK"
        private const val FEED_WAIT_US = 10_000L
        private const val FEED_TIMEOUT_NS = 2_000_000_000L
        private const val POLL_MS = 5L
        private const val THREAD_JOIN_MS = 3_000L
        private const val MAX_QUEUED_FRAMES = 600            // ~10s at 60fps: decoder is wedged
        private const val OFFSET_REFRESH_NS = 1_000_000_000L
        private const val MAX_PLAUSIBLE_NS = 3_000_000_000L
        private const val AUDIO_POLL_NS = 20_000_000L
        private const val MAX_AUDIO_DELAY_NS = 2_000_000_000L
        private const val MAX_VIDEO_DELAY_NS = 500_000_000L
        private const val MAX_DECODE_LATENCY_NS = 300_000_000L
        private const val RENDER_MARGIN_NS = 10_000_000L
        private const val TARGET_SNAP_NS = 80_000_000L
        private const val LATE_DECAY_PER_FRAME_NS = 500_000L  // ~30ms/s at 60fps
        private const val FLOOR_STEP_NS = 10_000_000L
        private const val FLOOR_REFRESH_NS = 1_000_000_000L
        private const val MAX_SCHEDULE_AHEAD_NS = 1_000_000_000L
    }
}
