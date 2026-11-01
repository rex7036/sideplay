#ifndef TIMELINE_BUFFER_H
#define TIMELINE_BUFFER_H

#include <algorithm>
#include <atomic>
#include <cstdint>
#include <cstdio>
#include <cstdlib>
#include <cstring>
#include <memory>
#include <string>

#include "audio_time.h"

/*
 * lock-free SPSC ring of int16 samples (interleaved frames); positions are
 * non-wrapping 64-bit counters so empty/full are unambiguous without spare slot
 */
class SpscRing {
public:
    explicit SpscRing(size_t capacitySamples)
        : mCapacity(capacitySamples), mBuf(new int16_t[capacitySamples]) {}

    size_t available() const {  // samples readable
        return mWrite.load(std::memory_order_acquire) - mRead.load(std::memory_order_acquire);
    }

    // producer: push up to n samples, drops what doesn't fit
    size_t write(const int16_t *src, size_t n) {
        const size_t w = mWrite.load(std::memory_order_relaxed);
        const size_t r = mRead.load(std::memory_order_acquire);
        const size_t freeSpace = mCapacity - (w - r);
        if (n > freeSpace) n = freeSpace;
        const size_t wi = w % mCapacity;
        const size_t first = (n < mCapacity - wi) ? n : (mCapacity - wi);
        memcpy(&mBuf[wi], src, first * sizeof(int16_t));
        if (n > first) memcpy(&mBuf[0], src + first, (n - first) * sizeof(int16_t));
        mWrite.store(w + n, std::memory_order_release);
        return n;
    }

    // consumer: pop up to n samples
    size_t read(int16_t *dst, size_t n) {
        const size_t r = mRead.load(std::memory_order_relaxed);
        const size_t w = mWrite.load(std::memory_order_acquire);
        const size_t avail = w - r;
        if (n > avail) n = avail;
        const size_t ri = r % mCapacity;
        const size_t first = (n < mCapacity - ri) ? n : (mCapacity - ri);
        memcpy(dst, &mBuf[ri], first * sizeof(int16_t));
        if (n > first) memcpy(dst + first, &mBuf[0], (n - first) * sizeof(int16_t));
        mRead.store(r + n, std::memory_order_release);
        return n;
    }

    // consumer: discard up to n oldest samples
    void skip(size_t n) {
        const size_t r = mRead.load(std::memory_order_relaxed);
        const size_t w = mWrite.load(std::memory_order_acquire);
        const size_t avail = w - r;
        if (n > avail) n = avail;
        mRead.store(r + n, std::memory_order_release);
    }

private:
    const size_t mCapacity;
    std::unique_ptr<int16_t[]> mBuf;
    std::atomic<size_t> mWrite{0};
    std::atomic<size_t> mRead{0};
};

/*
 * adaptive jitter cushion tuner, NetEQ-inspired but simplified: decaying histogram of
 * packet jitter, cushion = high percentile of it + 1 packet of margin.
 * observe() is producer-thread only; result published via atomic, consumer reads it live
 */
class DelayTracker {
public:
    // staticCushionMs > 0: fixed cushion, tuner disabled
    // staticCushionMs <= 0: tune between MIN_CUSHION_MS and MAX_CUSHION_MS
    DelayTracker(int sampleRate, int channels, int staticCushionMs, int percentilePct)
        : mSampleRate(sampleRate), mChannels(channels),
          mAdaptive(staticCushionMs <= 0),
          mFloor(msToSamples(sampleRate, channels, mAdaptive ? MIN_CUSHION_MS : staticCushionMs)),
          mCeil(msToSamples(sampleRate, channels, mAdaptive ? MAX_CUSHION_MS : staticCushionMs)),
          mPercentilePct(percentilePct),
          mTuned(mFloor) {}

    // ptsNs: packet presentation time; arrivalNs: local arrival time; durNs: packet duration
    void observe(int64_t ptsNs, int64_t arrivalNs, int64_t durNs) {
        if (!mAdaptive) return;

        const int64_t raw = arrivalNs - ptsNs; // arrival latency
        // jitter = latency relative to min latency seen so far (mBaseNs)
        if (!mHaveBase) { mBaseNs = raw; mHaveBase = true; }
        else if (raw < mBaseNs) mBaseNs = raw;
        // creep mBaseNs up slowly: counters clock drift and one freak fast packet
        else mBaseNs += (raw - mBaseNs) >> BASE_CREEP_SHIFT;
        int64_t rel = raw - mBaseNs;
        if (rel < 0) rel = 0;

        int bin = (int)(rel / BUCKET_NS);
        if (bin >= NBUCKETS) bin = NBUCKETS - 1;

        // decay: each bin bleeds 1/2^FORGET_SHIFT per packet, current bin topped up by
        // ONE/2^FORGET_SHIFT, so total converges to ONE
        int64_t sum = 0;
        for (int i = 0; i < NBUCKETS; i++) {
            mHist[i] -= (mHist[i] + (1 << FORGET_SHIFT) - 1) >> FORGET_SHIFT;
            sum += mHist[i];
        }
        mHist[bin] += ONE >> FORGET_SHIFT;
        sum += ONE >> FORGET_SHIFT;

        // cushion = smallest delay whose cumulative histogram share reaches percentile
        const int64_t thresh = sum * mPercentilePct / 100;
        int64_t cum = 0; int b = 0;
        for (; b < NBUCKETS - 1; b++) { cum += mHist[b]; if (cum >= thresh) break; }
        // bin is lower bound on delay: take top edge + margin. margin must keep output
        // fed for one data callback and tide us over until next packet
        const int64_t marginNs = std::max(durNs, mOutputBufferNs.load(std::memory_order_relaxed));
        const int64_t targetNs = (int64_t)(b + 1) * BUCKET_NS + marginNs;
        size_t samples = (size_t)(targetNs * mSampleRate / NS_PER_SEC) * mChannels;
        if (samples < mFloor) samples = mFloor;
        if (samples > mCeil) samples = mCeil;
        mTuned.store(samples, std::memory_order_relaxed);
    }

    // reset clock base after discontinuity or clock shift/resync so it doesn't poison
    // jitter calculations; producer-thread only (same as observe())
    void reanchor() { mHaveBase = false; }

    // latest tuned cushion in samples; any thread
    size_t target() const { return mTuned.load(std::memory_order_relaxed); }

    void noteOutputBufferFrames(int frames) {
        mOutputBufferNs.store((int64_t)frames * NS_PER_SEC / mSampleRate, std::memory_order_relaxed);
    }

    // largest cushion tuner can reach, in samples
    size_t ceil() const { return mCeil; }

private:
    static size_t msToSamples(int sampleRate, int channels, int ms) {
        return (size_t)sampleRate * ms / 1000 * channels;
    }

    static constexpr int MIN_CUSHION_MS = 0;             // algorithm already enforces effective floor
    static constexpr int MAX_CUSHION_MS = 1000;          // ceiling only: tuner stays near the jitter percentile
    static constexpr int BUCKET_MS = 5;                  // histogram granularity
    static constexpr int NBUCKETS = MAX_CUSHION_MS / BUCKET_MS;
    static constexpr int64_t BUCKET_NS = (int64_t)BUCKET_MS * 1000000LL;
    static constexpr int FORGET_SHIFT = 8;               // ~1/256 decay per packet (~6s @ 23ms)
    static constexpr int BASE_CREEP_SHIFT = 10;          // mBaseNs creep speed
    static constexpr int64_t ONE = 1 << 20;              // histogram fixed-point unit

    const int mSampleRate;
    const int mChannels;
    const bool mAdaptive;                // false = fixed cushion (tuner disabled)
    const size_t mFloor;
    const size_t mCeil;
    const int mPercentilePct;            // arrival-delay percentile cushion targets
    std::atomic<size_t> mTuned;
    std::atomic<int64_t> mOutputBufferNs{0};  // output->producer: oboe output buffer duration
    int64_t mHist[NBUCKETS] = {};
    int64_t mBaseNs = 0;
    bool mHaveBase = false;
};

// cumulative diagnostic counters, bumped from producer/consumer, read from stats thread
class TimelineMetrics {
public:
    // packed: nests into AudioDebugData with fixed layout
    struct __attribute__((packed)) Debug {
        uint32_t trims, drops, silences, underruns;  // cumulative; 32-bit to avoid wrap
    };

    void countTrim()     { mTrims.fetch_add(1, std::memory_order_relaxed); }
    void countDrop()     { mDrops.fetch_add(1, std::memory_order_relaxed); }
    void countSilence()  { mSilences.fetch_add(1, std::memory_order_relaxed); }
    void countUnderrun() { mUnderruns.fetch_add(1, std::memory_order_relaxed); }

    // any thread
    Debug debugInfo() const {
        Debug d{};
        d.trims = mTrims.load(std::memory_order_relaxed);
        d.drops = mDrops.load(std::memory_order_relaxed);
        d.silences = mSilences.load(std::memory_order_relaxed);
        d.underruns = mUnderruns.load(std::memory_order_relaxed);
        return d;
    }

private:
    std::atomic<uint32_t> mTrims{0};      // backlog exceeded cap and was trimmed
    std::atomic<uint32_t> mDrops{0};      // late packets dropped, pts slot already passed
    std::atomic<uint32_t> mSilences{0};   // gaps filled with silence
    std::atomic<uint32_t> mUnderruns{0};  // ring ran dry, output padded with silence
};

/*
 * Timeline-synchronized playout buffer: absorbs jitter, clock drift and clock resyncs,
 * syncs audio to its presentation-time tag.
 *
 * One concurrent reader + one concurrent writer OK; multiple readers or writers NOT safe.
 * samples = frames * channels
 */
class TimelineBuffer {
public:
    // cushion policy lives in mTracker; timeline reads mTracker.target() live
    TimelineBuffer(int sampleRate, int channels, int staticCushionMs, int percentilePct)
        : mSampleRate(sampleRate),
          mChannels(channels),
          mTracker(sampleRate, channels, staticCushionMs, percentilePct),
          // ring can't be reallocated mid-stream: size for worst-case backlog, i.e. cap
          // of largest reachable cushion + one throttle window of overshoot (trims are
          // throttled), 2x slack
          mRing(2 * capOf(mTracker.ceil()) + (size_t)sampleRate * TRIM_THROTTLE_MS / 1000 * channels) {}

    void noteOutputBufferFrames(int frames) {
        mTracker.noteOutputBufferFrames(frames);
        mOutputBufferNs.store((int64_t)frames * NS_PER_SEC / mSampleRate, std::memory_order_relaxed);
    }

    // output: measured device latency (oboe timestamps), 0 = unknown -> buffer size estimate
    void noteOutputLatencyNs(int64_t ns) { mOutputLatencyNs.store(ns, std::memory_order_relaxed); }

    // any thread: playout delay floor relative to pts (0 = none). video sync uses it so audio
    // never plays ahead of what the video decoder can show
    void setMinDelayNs(int64_t ns) { mMinDelayNs.store(ns > 0 ? ns : 0, std::memory_order_relaxed); }

    // any thread: smoothed delay from pts (CLOCK_REALTIME) to audible output, -1 if unknown;
    // updatedMonoNs = monoNs() of last update
    int64_t syncDelayNs(int64_t *updatedMonoNs) const {
        if (updatedMonoNs) *updatedMonoNs = mSyncAtNs.load(std::memory_order_acquire);
        return mSyncDelayNs.load(std::memory_order_relaxed);
    }

    // producer: push samples
    void write(const int16_t *pcm, size_t samples, int64_t ptsNs) {
        if (samples == 0) return;
        // pts 0 = sender clock not NTP synced yet
        if (ptsNs == 0) {
            mRing.write(pcm, samples);
            return;
        }
        const int64_t durNs = samples * NS_PER_SEC / mChannels / mSampleRate;

        // consumer underran since last write: it already played the gap as real-time
        // silence, re-anchor so we don't also insert it here
        if (mUnderran.exchange(false, std::memory_order_relaxed)) {
            mExpectedPtsNs = ptsNs;
        }
        const int64_t gapNs = ptsNs - mExpectedPtsNs;

        // large gap: possible discontinuity or clock shift. reanchor() must precede
        // observe() so the reset applies to this observation
        if (gapNs > MAX_GAP_NS || gapNs < -MAX_GAP_NS) mTracker.reanchor();

        // no-op in static-cushion mode
        mTracker.observe(ptsNs, monoNs(), durNs);

        if (gapNs > MAX_GAP_NS || gapNs < -MAX_GAP_NS) {
            // discontinuity: write immediately. new sound after long silence resets
            // backlog to ideal size (less latency); on clock resync we keep the sound
        } else if (gapNs > SLACK_NS) {
            // sender left gap: reproduce as silence so timing is exact
            const size_t silenceFrames = (size_t)(gapNs * mSampleRate / NS_PER_SEC);
            writeSilenceFrames(silenceFrames);
            mMetrics.countSilence();
        } else if (gapNs < -SLACK_NS) {
            // slot already passed (late/overlap): drop
            mMetrics.countDrop();
            return;
        }
        updateSyncDelay(ptsNs);
        mRing.write(pcm, samples);
        mExpectedPtsNs = ptsNs + durNs;
    }

    // consumer: pop frames*channels samples into out, silence-padded on underrun
    void read(int16_t *out, int32_t frames) {
        const size_t need = (size_t)frames * mChannels;
        const size_t tuned = mTracker.target();
        const int64_t now = monoNs();

        // bound latency: cap tracks tuned cushion live so a calmed link sheds latency
        // without an underrun. each trim costs a glitch, so be conservative: trim only
        // after backlog stayed above cap for TRIM_SUSTAIN (skip transient spikes), at
        // most once per TRIM_THROTTLE, and not within that window of an underrun
        // (trimming while rebuilding cushion is counterproductive)
        // backlog needed to honor the video sync floor sits on top of the jitter cushion
        const size_t floorBacklog = mFloorBacklog.load(std::memory_order_relaxed);
        const size_t avail = mRing.available();
        if (avail > capOf(tuned) + floorBacklog) {
            if (mAboveCapSinceNs == 0) mAboveCapSinceNs = now;
        } else {
            mAboveCapSinceNs = 0;
        }
        if (mAboveCapSinceNs != 0 && now - mAboveCapSinceNs >= TRIM_SUSTAIN_NS
                && now - mLastTrimBlockNs >= TRIM_THROTTLE_NS) {
            mRing.skip(avail - (tuned + floorBacklog));
            mMetrics.countTrim();
            mLastTrimBlockNs = now;
            mAboveCapSinceNs = 0;
        }

        // prebuffer: hold output until cushion fills, builds jitter headroom
        if (mPriming.load(std::memory_order_relaxed)) {
            const size_t buffered = mRing.available();
            // time the wait only while holding partial data; reset on empty ring so the
            // producer gets the full window once a sound starts, otherwise we could time
            // out on pre-sound silence and underrun the instant the first frame lands
            if (buffered == 0) mPrimeSilenceFrames = 0;
            else mPrimeSilenceFrames += (uint32_t)frames;
            // full cushion before starting: a half-filled one underruns on the first spike.
            // with a video sync floor, hold until audio is late enough to match the picture
            const size_t startThreshold = std::max(tuned, floorBacklog);
            // short sound can end before filling cushion; after holding partial data
            // through 2x cushion of silence the sound is complete: play it
            const uint32_t starveFrames = (uint32_t)(startThreshold * 2 / mChannels);
            const bool starved = buffered > 0 && mPrimeSilenceFrames >= starveFrames;
            // also keep priming on empty ring: with 0 cushion `buffered < tuned` is
            // never true, so we'd otherwise underrun on every empty read between sounds
            if (buffered == 0 || (buffered < startThreshold && !starved)) {
                memset(out, 0, need * sizeof(int16_t));
                return;
            }
            mPriming.store(false, std::memory_order_relaxed);
            mPrimeSilenceFrames = 0;
        }

        const size_t got = mRing.read(out, need);
        if (got < need) {
            memset(out + got, 0, (need - got) * sizeof(int16_t));
            mPriming.store(true, std::memory_order_relaxed);
            mLastTrimBlockNs = now;  // hold off trims while rebuilding
            mUnderran.store(true, std::memory_order_relaxed);  // producer re-anchors
            mMetrics.countUnderrun();
        }
    }

    // call after discontinuity or clock shift/resync; producer-side, must not run
    // concurrently with write()
    void reanchorTracker() { mTracker.reanchor(); }

    // rebuild prebuffer cushion before resuming, e.g. after output stream restart;
    // must not run concurrently with read()
    void reprime() { mPriming.store(true, std::memory_order_relaxed); }

    // drop buffered audio + rebuild cushion, so resume after pause doesn't play stale
    // tail; only while output callback is stopped (skip() is consumer-side)
    void flushAndReprime() {
        mRing.skip(mRing.available());
        mPriming.store(true, std::memory_order_relaxed);
    }

    // debug snapshot: backlog + tuned cushion (ms) + counters; reads only atomics, any thread
    struct __attribute__((packed)) Debug {
        uint16_t backlogMs;         // bounded by ring (few seconds)
        uint16_t tunedCushionMs;    // bounded by MAX_CUSHION_MS
        TimelineMetrics::Debug metrics;
    };
    Debug debugInfo() const {
        Debug d{};
        d.backlogMs = (uint16_t)(mRing.available() / mChannels * 1000 / mSampleRate);
        d.tunedCushionMs = (uint16_t)(mTracker.target() / mChannels * 1000 / mSampleRate);
        d.metrics = mMetrics.debugInfo();
        return d;
    }

private:

    // latency ceiling for cushion: 2x, but never below TRIM_FLOOR_MS so tiny cushion
    // still gets slack before a trim (trim costs a glitch)
    size_t capOf(size_t cushionSamples) const {
        return std::max(cushionSamples * 2, (size_t)mSampleRate * TRIM_FLOOR_MS / 1000 * mChannels);
    }

    int64_t samplesToNs(size_t samples) const {
        return (int64_t)(samples / mChannels) * NS_PER_SEC / mSampleRate;
    }

    size_t nsToSamples(int64_t ns) const {
        return (size_t)(ns * mSampleRate / NS_PER_SEC) * mChannels;
    }

    int64_t outputLatencyNs() const {
        const int64_t measured = mOutputLatencyNs.load(std::memory_order_relaxed);
        return measured > 0 ? measured : mOutputBufferNs.load(std::memory_order_relaxed);
    }

    // producer, right before a packet enters the ring: its first sample plays once the
    // backlog ahead of it has drained and it has passed the device, so
    // delay = (now - pts) + backlog + device latency. publishes the smoothed value for
    // video sync and pads silence when the video sync floor asks for more delay
    void updateSyncDelay(int64_t ptsNs) {
        const int64_t now = realNs();
        const int64_t outNs = outputLatencyNs();
        const int64_t transitNs = now - ptsNs + outNs;
        int64_t delay = transitNs + samplesToNs(mRing.available());

        const int64_t minDelay = mMinDelayNs.load(std::memory_order_relaxed);
        const int64_t needNs = minDelay - transitNs;
        mFloorBacklog.store(minDelay > 0 && needNs > 0 ? nsToSamples(needNs) : 0,
                            std::memory_order_relaxed);
        // floor raised mid-stream: add the missing delay at once rather than drifting.
        // not while priming, the consumer is already holding output to reach the floor
        const int64_t mono = monoNs();
        if (minDelay > 0 && delay < minDelay - FLOOR_SLACK_NS
                && !mPriming.load(std::memory_order_relaxed)
                && mono - mLastFloorPadNs >= FLOOR_PAD_THROTTLE_NS) {
            const int64_t padNs = std::min(minDelay - delay, MAX_FLOOR_PAD_NS);
            writeSilenceFrames((size_t)(padNs * mSampleRate / NS_PER_SEC));
            delay += padNs;
            mLastFloorPadNs = mono;
        }

        // smooth packet-level noise, but follow real steps (trim, underrun, pad) at once
        if (mDelayEmaNs < 0 || std::abs(delay - mDelayEmaNs) > SYNC_SNAP_NS) mDelayEmaNs = delay;
        else mDelayEmaNs += (delay - mDelayEmaNs) / 16;
        mSyncDelayNs.store(mDelayEmaNs, std::memory_order_relaxed);
        mSyncAtNs.store(mono, std::memory_order_release);
    }

    void writeSilenceFrames(size_t frames) {
        int16_t zeros[512] = {0};  // 256 stereo frames per chunk
        size_t samples = frames * mChannels;
        while (samples > 0) {
            const size_t n = std::min<size_t>(samples, 512);
            mRing.write(zeros, n);
            samples -= n;
        }
    }

    static constexpr int64_t SLACK_NS = 10'000'000LL;    // ignore <10ms gaps (NTP shift, rounding)
    static constexpr int64_t MAX_GAP_NS = 750'000'000LL; // >750ms = discontinuity, re-anchor
    static constexpr int TRIM_FLOOR_MS = 30;
    static constexpr int TRIM_THROTTLE_MS = 2000;        // each trim is an audible glitch
    static constexpr int64_t TRIM_THROTTLE_NS = (int64_t)TRIM_THROTTLE_MS * 1'000'000LL;
    static constexpr int TRIM_SUSTAIN_MS = 2000;         // ignore transient backlog spikes
    static constexpr int64_t TRIM_SUSTAIN_NS = (int64_t)TRIM_SUSTAIN_MS * 1'000'000LL;
    static constexpr int64_t FLOOR_SLACK_NS = 20'000'000LL;           // tolerated shortfall vs floor
    static constexpr int64_t MAX_FLOOR_PAD_NS = 500'000'000LL;        // largest single silence pad
    static constexpr int64_t FLOOR_PAD_THROTTLE_NS = 500'000'000LL;   // min spacing between pads
    static constexpr int64_t SYNC_SNAP_NS = 40'000'000LL;             // bigger delay steps skip smoothing

    const int mSampleRate;
    const int mChannels;
    DelayTracker mTracker;               // owns cushion policy; read live each read()
    SpscRing mRing;
    TimelineMetrics mMetrics;
    std::atomic<bool> mPriming{true};    // stream start: buffer output to build cushion; consumer writes
    uint32_t mPrimeSilenceFrames = 0;    // consumer-only: silence frames output while priming with partial data
    int64_t mLastTrimBlockNs = 0;        // consumer-only: last trim/underrun time (trim throttle)
    int64_t mAboveCapSinceNs = 0;        // consumer-only: when backlog first exceeded cap (0 = under)
    std::atomic<bool> mUnderran{false};  // consumer->producer: underrun happened

    int64_t mExpectedPtsNs = 0;          // presentation time expected at write head

    // a/v sync
    std::atomic<int64_t> mOutputBufferNs{0};   // output buffer duration (latency fallback)
    std::atomic<int64_t> mOutputLatencyNs{0};  // measured device latency, 0 = unknown
    std::atomic<int64_t> mMinDelayNs{0};       // video sync floor for pts->audible delay
    std::atomic<size_t> mFloorBacklog{0};      // producer->consumer: backlog samples the floor needs
    std::atomic<int64_t> mSyncDelayNs{-1};     // producer->any: smoothed pts->audible delay
    std::atomic<int64_t> mSyncAtNs{0};         // producer->any: monoNs() of last mSyncDelayNs update
    int64_t mDelayEmaNs = -1;                  // producer-only
    int64_t mLastFloorPadNs = 0;               // producer-only
};

#endif  // TIMELINE_BUFFER_H
