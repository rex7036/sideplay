package io.github.jqssun.airplay.renderer

// audio side of mirroring a/v sync; both streams carry raop pts on the same local clock
interface AvSync {
    // smoothed delay from pts to audible output (ns), or -1 while audio isn't playing
    fun audioDelayNs(): Long

    // ask audio to play no earlier than pts + ns (0 = no constraint)
    fun setAudioFloorNs(ns: Long)
}
