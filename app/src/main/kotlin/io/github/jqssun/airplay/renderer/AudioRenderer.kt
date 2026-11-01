package io.github.jqssun.airplay.renderer

import io.github.jqssun.airplay.bridge.NativeBridge

// wrapper around native audio engine
class AudioRenderer : AvSync {

    @Volatile var config = AudioConfig(); private set

    @Volatile var codecLabel = ""; private set

    @Volatile private var serverHandle = 0L

    // a/v sync calls come from the video decoder thread every frame: they must not wait
    // behind start() opening an audio stream, only behind the handle going away
    private val syncLock = Object()

    @Synchronized
    fun attachEngine(server: Long) {
        synchronized(syncLock) { serverHandle = server }
        pushConfig()
    }

    @Synchronized
    fun detachEngine() {
        synchronized(syncLock) { serverHandle = 0L }
        codecLabel = ""
    }

    override fun audioDelayNs(): Long = synchronized(syncLock) {
        val h = serverHandle
        if (h == 0L) -1L else NativeBridge.nativeServerAudioSyncDelayNs(h, SYNC_MAX_AGE_NS)
    }

    override fun setAudioFloorNs(ns: Long) {
        synchronized(syncLock) {
            val h = serverHandle
            if (h != 0L) NativeBridge.nativeServerAudioSetMinDelayNs(h, ns)
        }
    }

    // open playout (first onAudioFormat, or resume after pause); idempotent natively
    @Synchronized
    fun start() {
        if (serverHandle == 0L) return
        NativeBridge.nativeServerAudioStart(serverHandle)
    }

    // releases oboe stream + audio device while idle; engine stays alive, freed on server destroy
    @Synchronized
    fun stop() {
        if (serverHandle == 0L) return
        NativeBridge.nativeServerAudioStop(serverHandle)
        codecLabel = ""
    }

    @Synchronized
    fun updateConfig(newConfig: AudioConfig) {
        config = newConfig
        pushConfig()
    }

    private fun pushConfig() {
        if (serverHandle == 0L) return
        NativeBridge.nativeServerAudioConfigure(
            serverHandle, config.cushionMs, config.percentilePct, config.oboeBufferFrames,
            config.forceSwAlac, config.realtimePriority, config.lowLatency, config.benchmarkLog)
    }

    @Synchronized
    fun setFormat(ct: Int, spf: Int) {
        codecLabel = when (ct) {
            CT_ALAC -> "ALAC"; CT_AAC_LC -> "AAC-LC"; CT_AAC_ELD -> "AAC-ELD"; else -> "?"
        }
        if (serverHandle != 0L) NativeBridge.nativeServerAudioFormat(serverHandle, ct, spf)
    }

    companion object {
        // audio silent for longer than this no longer anchors video timing
        private const val SYNC_MAX_AGE_NS = 1_000_000_000L
        const val CT_ALAC = 2
        const val CT_AAC_LC = 4
        const val CT_AAC_ELD = 8
    }
}
