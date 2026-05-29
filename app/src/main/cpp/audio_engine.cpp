#include <oboe/Oboe.h>
#include <android/log.h>

#include <atomic>
#include <cmath>
#include <cstdint>
#include <mutex>
#include <memory>
#include <vector>

#define LOG_TAG "AudioEngine"
#define ALOGE(...) __android_log_print(ANDROID_LOG_ERROR, LOG_TAG, __VA_ARGS__)
#define ALOGI(...) __android_log_print(ANDROID_LOG_INFO, LOG_TAG, __VA_ARGS__)

namespace {

constexpr int kOutputChannels = 2;

struct SfxEventQueue {
    static constexpr int kCapacity = 1024;
    struct Event {
        int type = 1;
        int64_t targetOutputFrame = 0;
    };

    std::atomic<int> w{0};
    std::atomic<int> r{0};
    Event events[kCapacity];

    void push(int type, int64_t targetOutputFrame) {
        int w0 = w.load(std::memory_order_relaxed);
        int r0 = r.load(std::memory_order_acquire);
        int w1 = (w0 + 1) % kCapacity;
        if (w1 == r0) {
            // full, drop
            return;
        }
        events[w0].type = type;
        events[w0].targetOutputFrame = targetOutputFrame;
        w.store(w1, std::memory_order_release);
    }

    bool pop(Event &outEvent) {
        int r0 = r.load(std::memory_order_relaxed);
        int w0 = w.load(std::memory_order_acquire);
        if (r0 == w0) return false;
        outEvent = events[r0];
        int r1 = (r0 + 1) % kCapacity;
        r.store(r1, std::memory_order_release);
        return true;
    }
};

static inline float clampf(float v, float lo, float hi) {
    return v < lo ? lo : (v > hi ? hi : v);
}

static std::vector<float> resampleToStereo(const float *in, int inFrames, int inCh,
                                          int inRate, int outRate) {
    if (in == nullptr || inFrames <= 0 || inRate <= 0 || outRate <= 0) {
        return {};
    }

    if (inCh <= 0) inCh = 1;

    // If sample rate and channels already match, just copy (and/or upmix).
    if (inRate == outRate && inCh == kOutputChannels) {
        return std::vector<float>(in, in + (int64_t) inFrames * kOutputChannels);
    }

    // output frames (rounded)
    const double ratio = (double) outRate / (double) inRate;
    int outFrames = (int) std::llround((double) inFrames * ratio);
    if (outFrames <= 0) outFrames = 0;

    std::vector<float> out;
    out.resize((int64_t) outFrames * kOutputChannels, 0.0f);

    auto sampleAt = [&](int frame, int ch) -> float {
        if (frame < 0) frame = 0;
        if (frame >= inFrames) frame = inFrames - 1;
        if (inFrames <= 0) return 0.0f;
        if (inCh == 1) {
            return in[frame];
        } else {
            int c = ch;
            if (c < 0) c = 0;
            if (c >= inCh) c = inCh - 1;
            return in[(int64_t) frame * inCh + c];
        }
    };

    for (int i = 0; i < outFrames; i++) {
        double inPos = (double) i / ratio; // i * inRate / outRate
        int idx = (int) std::floor(inPos);
        double frac = inPos - (double) idx;

        for (int ch = 0; ch < kOutputChannels; ch++) {
            float s0 = sampleAt(idx, ch);
            float s1 = sampleAt(idx + 1, ch);
            float s = (float) ((1.0 - frac) * s0 + frac * s1);
            out[(int64_t) i * kOutputChannels + ch] = s;
        }
    }

    return out;
}

static std::vector<float> generateBeep(float freqHz, float durSec, int sampleRate, float amp) {
    if (sampleRate <= 0 || durSec <= 0) return {};

    int frames = (int) std::llround((double) sampleRate * durSec);
    if (frames <= 0) return {};

    std::vector<float> out;
    out.resize((int64_t) frames * kOutputChannels, 0.0f);

    const double twoPi = 6.283185307179586;
    const double w = twoPi * (double) freqHz;

    // Simple fade in/out (in seconds)
    const float fadeIn = 0.002f;
    const float fadeOut = 0.010f;

    for (int i = 0; i < frames; i++) {
        float t = (float) i / (float) sampleRate;
        float env = 1.0f;
        if (t < fadeIn) {
            env = t / fadeIn;
        } else if (t > durSec - fadeOut) {
            float p = (durSec - t) / fadeOut;
            env = p < 0 ? 0 : p;
        }
        float s = (float) std::sin(w * (double) t) * amp * env;
        out[(int64_t) i * kOutputChannels + 0] = s;
        out[(int64_t) i * kOutputChannels + 1] = s;
    }

    return out;
}

struct Voice {
    const std::vector<float> *buf = nullptr; // interleaved stereo
    int64_t frameIndex = 0; // in frames
    int64_t startOffsetFrames = 0; // output frames from current callback start

    inline int64_t totalFrames() const {
        if (!buf) return 0;
        return (int64_t) buf->size() / kOutputChannels;
    }
};

class AudioEngine : public oboe::AudioStreamCallback {
public:
    bool create() {
        std::lock_guard<std::mutex> lock(mStreamMutex);

        if (mStream && !mStreamNeedsReopen.load(std::memory_order_acquire)) {
            return true;
        }

        // (re)open stream
        if (mStream) {
            mStream->close();
            mStream.reset();
        }

        const int32_t oldSampleRate = mSampleRate;

        // Try combinations for robustness across devices / lifecycle.
        oboe::AudioStreamBuilder builder;
        builder.setDirection(oboe::Direction::Output);
        builder.setPerformanceMode(oboe::PerformanceMode::LowLatency);
        builder.setChannelCount(kOutputChannels);
        builder.setUsage(oboe::Usage::Game);
        builder.setContentType(oboe::ContentType::Music);
        builder.setCallback(this);

        if (oldSampleRate > 0) {
            builder.setSampleRate(oldSampleRate);
        }

        auto tryOpen = [&](oboe::SharingMode mode, oboe::AudioFormat fmt) -> oboe::Result {
            builder.setSharingMode(mode);
            builder.setFormat(fmt);
            mStream.reset();
            return builder.openStream(mStream);
        };

        oboe::Result r = tryOpen(oboe::SharingMode::Exclusive, oboe::AudioFormat::Float);
        if (r != oboe::Result::OK || !mStream) {
            ALOGE("openStream(Exclusive,Float) failed: %s", oboe::convertToText(r));
            r = tryOpen(oboe::SharingMode::Exclusive, oboe::AudioFormat::I16);
        }
        if (r != oboe::Result::OK || !mStream) {
            ALOGE("openStream(Exclusive,I16) failed: %s", oboe::convertToText(r));
            r = tryOpen(oboe::SharingMode::Shared, oboe::AudioFormat::Float);
        }
        if (r != oboe::Result::OK || !mStream) {
            ALOGE("openStream(Shared,Float) failed: %s", oboe::convertToText(r));
            r = tryOpen(oboe::SharingMode::Shared, oboe::AudioFormat::I16);
        }
        if (r != oboe::Result::OK || !mStream) {
            ALOGE("openStream(Shared,I16) failed: %s", oboe::convertToText(r));
            mStream.reset();
            return false;
        }

        mFormat = mStream->getFormat();
        mSampleRate = mStream->getSampleRate();
        if (mSampleRate <= 0) mSampleRate = 48000;

        // buffer size
        int32_t burst = mStream->getFramesPerBurst();
        if (burst > 0) {
            mStream->setBufferSizeInFrames(burst * 2);
        }

        // If the output sample rate changed after a reopen, resample existing buffers
        // (rare, but can happen across device routes / lifecycle).
        if (oldSampleRate > 0 && oldSampleRate != mSampleRate) {
            ALOGI("Output sample rate changed: %d -> %d, resampling buffers", oldSampleRate, mSampleRate);

            auto resampleBuf = [&](std::vector<float> &buf) {
                if (buf.empty()) return;
                int inFrames = (int) ((int64_t) buf.size() / kOutputChannels);
                std::vector<float> res = resampleToStereo(buf.data(), inFrames, kOutputChannels,
                                                        oldSampleRate, mSampleRate);
                buf = std::move(res);
            };

            resampleBuf(mMusic);
            resampleBuf(mSfxTap);
            resampleBuf(mSfxDrag);
            resampleBuf(mSfxFlick);

            // Update duration because mMusic frame count changed.
            double dur = 0.0;
            if (!mMusic.empty() && mSampleRate > 0) {
                dur = (double) ((int64_t) mMusic.size() / kOutputChannels) / (double) mSampleRate;
            }
            mMusicDurationSec.store(dur);
        }

        mStreamNeedsReopen.store(false, std::memory_order_release);

        ALOGI("AudioEngine created: sr=%d, framesPerBurst=%d", mSampleRate, burst);
        return true;
    }

    void destroy() {
        stop();
        {
            std::lock_guard<std::mutex> lock(mStreamMutex);
            if (mStream) {
                mStream->close();
                mStream.reset();
            }
            mStreamNeedsReopen.store(false, std::memory_order_release);
        }
        mMusic.clear();
        mSfxTap.clear();
        mSfxDrag.clear();
        mSfxFlick.clear();
        mVoices.clear();
        mPlayheadFrames.store(0);
        mCbStartFrames.store(0.0);
        mCbStartNanos.store(0);
        mCbNumFrames.store(0);
        mCbSpeed.store(1.0f);
        mMusicDurationSec.store(0.0);
        mMusicLooping.store(false);
    }

    void setMusicData(const float *pcm, int length, int inRate, int inCh) {
        if (!mStream && !create()) return;
        if (!pcm || length <= 0 || inRate <= 0) {
            mMusic.clear();
            mMusicDurationSec.store(0.0);
            mMusicLooping.store(false);
            return;
        }
        int inFrames = length / (inCh > 0 ? inCh : 1);
        std::vector<float> res = resampleToStereo(pcm, inFrames, inCh, inRate, mSampleRate);
        mMusic = std::move(res);

        double dur = 0.0;
        if (!mMusic.empty()) {
            dur = (double) ((int64_t) mMusic.size() / kOutputChannels) / (double) mSampleRate;
        }
        mMusicDurationSec.store(dur);

        // Reset playhead (make it visible immediately to renderer)
        mPlayheadFrames.store(0, std::memory_order_release);
        mMusicLooping.store(false, std::memory_order_release);
        mRestartRequested.store(true);

        ALOGI("Music loaded: inRate=%d inCh=%d inFrames=%d => outFrames=%lld dur=%.3f", inRate, inCh,
              inFrames, (long long) (mMusic.size() / kOutputChannels), dur);
    }

    void setSfxData(int noteType, const float *pcm, int length, int inRate, int inCh) {
        if (!mStream && !create()) return;

        // noteType: 1=tap, 2=drag, 4=flick (hold 复用 tap 在 Java 层处理)
        if (noteType != 1 && noteType != 2 && noteType != 4) {
            // ignore unknown types
            return;
        }

        if (!pcm || length <= 0 || inRate <= 0) {
            // clear
            switch (noteType) {
                case 1: mSfxTap.clear(); break;
                case 2: mSfxDrag.clear(); break;
                case 4: mSfxFlick.clear(); break;
                default: break;
            }
            return;
        }

        int ch = inCh > 0 ? inCh : 1;
        int inFrames = length / ch;
        std::vector<float> res = resampleToStereo(pcm, inFrames, ch, inRate, mSampleRate);

        switch (noteType) {
            case 1: mSfxTap = std::move(res); break;
            case 2: mSfxDrag = std::move(res); break;
            case 4: mSfxFlick = std::move(res); break;
            default: break;
        }
    }

    void prepareDefaultSfxIfMissing() {
        if (!mStream && !create()) return;
        if (mSfxTap.empty()) mSfxTap = generateBeep(880.0f, 0.030f, mSampleRate, 0.35f);
        if (mSfxDrag.empty()) mSfxDrag = generateBeep(660.0f, 0.035f, mSampleRate, 0.30f);
        if (mSfxFlick.empty()) mSfxFlick = generateBeep(1100.0f, 0.025f, mSampleRate, 0.35f);
    }

    void triggerSfx(int noteType) {
        mSfxQueue.push(noteType, estimateGeneratedOutputFrameNow());
    }

    void setPlaybackSpeed(float speed) {
        // Keep it in a sane range to avoid extreme CPU usage / glitches.
        if (speed < 0.05f) speed = 0.05f;
        if (speed > 4.0f) speed = 4.0f;
        mPlaybackSpeed.store(speed, std::memory_order_release);
    }

    void setSfxVolume(float volume) {
        if (volume < 0.0f) volume = 0.0f;
        if (volume > 5.0f) volume = 5.0f;
        mSfxVolume.store(volume, std::memory_order_release);
    }

    void setMusicVolume(float volume) {
        if (volume < 0.0f) volume = 0.0f;
        if (volume > 5.0f) volume = 5.0f;
        mMusicVolume.store(volume, std::memory_order_release);
    }

    void setMusicLooping(bool looping) {
        mMusicLooping.store(looping, std::memory_order_release);
    }

    void start() {
        if (!mStream && !create()) return;
        mPaused.store(false);
        mPlaying.store(true);
        requestStartStream();
    }

    void pause(bool pause) {
        mPaused.store(pause);

        // Stop/pause the actual stream so it survives long background pauses better.
        std::shared_ptr<oboe::AudioStream> stream;
        {
            std::lock_guard<std::mutex> lock(mStreamMutex);
            stream = mStream;
        }

        if (pause) {
            if (stream) {
                oboe::Result r = stream->requestPause();
                if (r != oboe::Result::OK) {
                    // Some backends may not support pause; fall back to stop.
                    stream->requestStop();
                }
            }
        } else {
            // Resume only if we are in playing state.
            if (mPlaying.load(std::memory_order_relaxed)) {
                requestStartStream();
            }
        }
    }

    void restart() {
        // Make restart visible immediately (important for renderer state reset).
        mPlayheadFrames.store(0.0, std::memory_order_release);
        mCbStartFrames.store(0.0, std::memory_order_release);
        mCbStartNanos.store(0, std::memory_order_release);
        mCbNumFrames.store(0, std::memory_order_release);
        mCbSpeed.store(1.0f, std::memory_order_release);
        mCbStartOutputFrames.store(0, std::memory_order_release);
        mRestartRequested.store(true);
        mPaused.store(false);
        mPlaying.store(true);

        // If the stream was paused/stopped by the system (or by us), ensure it's started.
        requestStartStream();
    }

    void stop() {
        mPlaying.store(false);
        mPaused.store(true);
        std::shared_ptr<oboe::AudioStream> stream;
        {
            std::lock_guard<std::mutex> lock(mStreamMutex);
            stream = mStream;
        }
        if (stream) stream->requestStop();
    }

    void onErrorAfterClose(oboe::AudioStream *stream, oboe::Result error) override {
        (void) stream;
        ALOGE("AudioStream error after close: %s", oboe::convertToText(error));
        // Mark for reopen on next start/resume.
        {
            std::lock_guard<std::mutex> lock(mStreamMutex);
            mStream.reset();
        }
        mStreamNeedsReopen.store(true, std::memory_order_release);
    }

    double getPlayheadSeconds() const {
        // IMPORTANT (high refresh smoothness)
        // -----------------------------------
        // The renderer queries the playhead every frame. If we return only the value updated
        // at the end of each audio callback, the playhead becomes a step function and can
        // visibly stutter on high-refresh screens (e.g. 144/165Hz) when the audio callback
        // interval is larger than the render interval.
        //
        // To make animation time smooth, we interpolate the playhead within the most recent
        // callback window using a monotonic clock. We also clamp the interpolation to the
        // frames generated in that callback to avoid extrapolation drift.
        if (mSampleRate <= 0) return 0.0;

        const bool playing = mPlaying.load(std::memory_order_relaxed);
        const bool paused = mPaused.load(std::memory_order_relaxed);

        const double endFrames = mPlayheadFrames.load(std::memory_order_acquire);
        if (!playing || paused) {
            return endFrames / (double) mSampleRate;
        }

        const int64_t startNs = mCbStartNanos.load(std::memory_order_acquire);
        const double startFrames = mCbStartFrames.load(std::memory_order_acquire);
        const int64_t startOutputFrames = mCbStartOutputFrames.load(std::memory_order_acquire);
        const int64_t endOutputFrames = mGeneratedOutputFrames.load(std::memory_order_acquire);
        const int32_t cbFrames = mCbNumFrames.load(std::memory_order_acquire);
        float cbSpeed = mCbSpeed.load(std::memory_order_acquire);

        if (startNs <= 0 || cbFrames <= 0) {
            return endFrames / (double) mSampleRate;
        }

        // Clamp speed to the same sane range as the audio thread.
        if (cbSpeed < 0.05f) cbSpeed = 0.05f;
        if (cbSpeed > 4.0f) cbSpeed = 4.0f;

        const int64_t nowNs = oboe::AudioClock::getNanoseconds();
        const double generatedOutputNow = estimateGeneratedOutputFrameNow(startNs, startOutputFrames,
                                                                          endOutputFrames, cbFrames);
        const double outputDelta = generatedOutputNow - (double) startOutputFrames;

        // Estimated generated music position within this callback window.
        double est = startFrames + outputDelta * (double) cbSpeed;

        // Upper bound for this window (and also clamp to the latest generated end frame).
        double maxFrames = startFrames + (double) cbFrames * (double) cbSpeed;
        if (endFrames < maxFrames) maxFrames = endFrames;

        if (est > maxFrames) est = maxFrames;
        if (est < 0.0) est = 0.0;

        return est / (double) mSampleRate;
    }

    double getMusicDurationSeconds() const {
        return mMusicDurationSec.load(std::memory_order_acquire);
    }

    oboe::DataCallbackResult onAudioReady(oboe::AudioStream *oboeStream,
                                         void *audioData,
                                         int32_t numFrames) override {
        (void) oboeStream;

        const int32_t numSamples = numFrames * kOutputChannels;

        const bool outIsFloat = (mFormat == oboe::AudioFormat::Float);
        float *outF = outIsFloat ? static_cast<float *>(audioData) : nullptr;
        int16_t *outI16 = outIsFloat ? nullptr : static_cast<int16_t *>(audioData);

        // Control: restart
        if (mRestartRequested.exchange(false)) {
            mPlayheadFrames.store(0.0);
            mCbStartFrames.store(0.0);
            mCbStartNanos.store(0);
            mCbNumFrames.store(0);
            mCbSpeed.store(1.0f);
            mCbStartOutputFrames.store(0);
            mVoices.clear();
            // 清空队列（简单做法：读到空）
            SfxEventQueue::Event dummy;
            while (mSfxQueue.pop(dummy)) {}
        }

        // Drain SFX events into voices
        const int64_t callbackStartOutputFrames = mGeneratedOutputFrames.load(std::memory_order_relaxed);
        SfxEventQueue::Event event;
        while (mSfxQueue.pop(event)) {
            const std::vector<float> *buf = nullptr;
            switch (event.type) {
                case 1: buf = &mSfxTap; break;
                case 2: buf = &mSfxDrag; break;
                case 4: buf = &mSfxFlick; break;
                default: buf = &mSfxTap; break;
            }
            if (buf && !buf->empty()) {
                Voice v;
                v.buf = buf;
                const int64_t target = event.targetOutputFrame;
                if (target > callbackStartOutputFrames) {
                    v.frameIndex = 0;
                    v.startOffsetFrames = target - callbackStartOutputFrames;
                } else {
                    v.frameIndex = 0;
                    v.startOffsetFrames = 0;
                }
                mVoices.push_back(v);
            }
        }

        const bool playing = mPlaying.load(std::memory_order_relaxed);
        const bool paused = mPaused.load(std::memory_order_relaxed);

        if (!playing || paused || mSampleRate <= 0) {
            if (outIsFloat) {
                for (int i = 0; i < numSamples; i++) outF[i] = 0.0f;
            } else {
                for (int i = 0; i < numSamples; i++) outI16[i] = 0;
            }
            return oboe::DataCallbackResult::Continue;
        }

        const int64_t musicFrames = (int64_t) mMusic.size() / kOutputChannels;
        double pos = mPlayheadFrames.load(std::memory_order_relaxed);

        float speed = mPlaybackSpeed.load(std::memory_order_relaxed);
        // sanity clamp
        if (speed < 0.05f) speed = 0.05f;
        if (speed > 4.0f) speed = 4.0f;

        float sfxVol = mSfxVolume.load(std::memory_order_relaxed);
        if (sfxVol < 0.0f) sfxVol = 0.0f;
        if (sfxVol > 5.0f) sfxVol = 5.0f;

        float musicVol = mMusicVolume.load(std::memory_order_relaxed);
        if (musicVol < 0.0f) musicVol = 0.0f;
        if (musicVol > 5.0f) musicVol = 5.0f;

        // Capture callback start information for smooth playhead interpolation on other threads.
        const double cbStartFrames = pos;
        const int64_t cbStartNs = oboe::AudioClock::getNanoseconds();
        mCbStartFrames.store(cbStartFrames, std::memory_order_release);
        mCbStartNanos.store(cbStartNs, std::memory_order_release);
        mCbNumFrames.store(numFrames, std::memory_order_release);
        mCbSpeed.store(speed, std::memory_order_release);
        mCbStartOutputFrames.store(callbackStartOutputFrames, std::memory_order_release);

        for (int32_t f = 0; f < numFrames; f++) {
            float l = 0.0f;
            float r = 0.0f;

            // Music (linear interpolation when speed is fractional)
            const bool looping = mMusicLooping.load(std::memory_order_relaxed);
            if (musicFrames > 0 && pos >= 0.0 && (looping || pos < (double) musicFrames)) {
                double samplePos = pos;
                if (looping) {
                    samplePos = std::fmod(samplePos, (double) musicFrames);
                    if (samplePos < 0.0) samplePos += (double) musicFrames;
                }
                int64_t i0 = (int64_t) samplePos;
                double frac = samplePos - (double) i0;
                int64_t i1 = i0 + 1;
                if (i1 >= musicFrames) i1 = looping ? 0 : i0;

                int64_t idx0 = i0 * kOutputChannels;
                int64_t idx1 = i1 * kOutputChannels;

                float l0 = mMusic[idx0 + 0];
                float r0 = mMusic[idx0 + 1];
                float l1 = mMusic[idx1 + 0];
                float r1 = mMusic[idx1 + 1];

                float w1 = (float) frac;
                float w0 = 1.0f - w1;
                l += (l0 * w0 + l1 * w1) * musicVol;
                r += (r0 * w0 + r1 * w1) * musicVol;
            }

            // mix voices
            for (auto &v : mVoices) {
                if (!v.buf) continue;
                if (v.startOffsetFrames > 0) {
                    v.startOffsetFrames--;
                    continue;
                }
                int64_t vf = v.frameIndex;
                int64_t vTotal = v.totalFrames();
                if (vf >= 0 && vf < vTotal) {
                    int64_t idx = vf * kOutputChannels;
                    l += (*v.buf)[idx + 0] * sfxVol;
                    r += (*v.buf)[idx + 1] * sfxVol;
                }
                v.frameIndex++;
            }

            pos += (double) speed;

            // clamp
            l = clampf(l, -1.0f, 1.0f);
            r = clampf(r, -1.0f, 1.0f);

            const int outIdx = f * kOutputChannels;
            if (outIsFloat) {
                outF[outIdx + 0] = l;
                outF[outIdx + 1] = r;
            } else {
                // float -> int16 with saturation
                int32_t sL = static_cast<int32_t>(lrintf(l * 32767.0f));
                int32_t sR = static_cast<int32_t>(lrintf(r * 32767.0f));
                if (sL < -32768) sL = -32768;
                if (sL > 32767) sL = 32767;
                if (sR < -32768) sR = -32768;
                if (sR > 32767) sR = 32767;
                outI16[outIdx + 0] = static_cast<int16_t>(sL);
                outI16[outIdx + 1] = static_cast<int16_t>(sR);
            }
        }

        // remove ended voices
        if (!mVoices.empty()) {
            for (size_t i = 0; i < mVoices.size();) {
                if (!mVoices[i].buf || mVoices[i].frameIndex >= mVoices[i].totalFrames()) {
                    mVoices[i] = mVoices.back();
                    mVoices.pop_back();
                } else {
                    ++i;
                }
            }
        }

        mPlayheadFrames.store(pos, std::memory_order_release);
        mGeneratedOutputFrames.store(callbackStartOutputFrames + (int64_t) numFrames,
                                     std::memory_order_release);
        return oboe::DataCallbackResult::Continue;
    }

private:
    int64_t estimateGeneratedOutputFrameNow() const {
        const int64_t startNs = mCbStartNanos.load(std::memory_order_acquire);
        const int64_t startOutputFrames = mCbStartOutputFrames.load(std::memory_order_acquire);
        const int64_t endOutputFrames = mGeneratedOutputFrames.load(std::memory_order_acquire);
        const int32_t cbFrames = mCbNumFrames.load(std::memory_order_acquire);
        return (int64_t) std::llround(estimateGeneratedOutputFrameNow(startNs, startOutputFrames,
                                                                      endOutputFrames, cbFrames));
    }

    double estimateGeneratedOutputFrameNow(int64_t startNs,
                                           int64_t startOutputFrames,
                                           int64_t endOutputFrames,
                                           int32_t cbFrames) const {
        if (mSampleRate <= 0 || startNs <= 0 || cbFrames <= 0) {
            return (double) endOutputFrames;
        }
        const int64_t nowNs = oboe::AudioClock::getNanoseconds();
        double dt = (double) (nowNs - startNs) * 1e-9;
        if (!std::isfinite(dt) || dt < 0.0) dt = 0.0;
        double out = (double) startOutputFrames + dt * (double) mSampleRate;
        const double maxOutput = (double) startOutputFrames + (double) cbFrames;
        if (out > maxOutput) out = maxOutput;
        if (out > (double) endOutputFrames) out = (double) endOutputFrames;
        if (out < (double) startOutputFrames) out = (double) startOutputFrames;
        return out;
    }

    void requestStartStream() {
        // Ensure a valid stream.
        if (!mStream && !create()) return;

        std::shared_ptr<oboe::AudioStream> stream;
        {
            std::lock_guard<std::mutex> lock(mStreamMutex);
            stream = mStream;
        }
        if (!stream) return;

        oboe::Result r = stream->requestStart();
        if (r == oboe::Result::OK) return;

        ALOGE("requestStart failed: %s", oboe::convertToText(r));

        // Try to reopen once.
        mStreamNeedsReopen.store(true, std::memory_order_release);
        if (!create()) return;
        {
            std::lock_guard<std::mutex> lock(mStreamMutex);
            stream = mStream;
        }
        if (!stream) return;
        r = stream->requestStart();
        if (r != oboe::Result::OK) {
            ALOGE("requestStart (after reopen) failed: %s", oboe::convertToText(r));
        }
    }

    std::shared_ptr<oboe::AudioStream> mStream;
    mutable std::mutex mStreamMutex;
    std::atomic<bool> mStreamNeedsReopen{false};

    oboe::AudioFormat mFormat = oboe::AudioFormat::Float;
    int32_t mSampleRate = 0;

    std::vector<float> mMusic;

    std::vector<float> mSfxTap;
    std::vector<float> mSfxDrag;
    std::vector<float> mSfxFlick;

    SfxEventQueue mSfxQueue;
    std::vector<Voice> mVoices; // audio-thread owned

    // Music read position in frames (can be fractional when playback speed != 1).
    std::atomic<double> mPlayheadFrames{0.0};

    // For smooth playhead interpolation between audio callbacks.
    // These are written by the audio callback thread and read by the render thread.
    std::atomic<double> mCbStartFrames{0.0};
    std::atomic<int64_t> mCbStartNanos{0};
    std::atomic<int32_t> mCbNumFrames{0};
    std::atomic<float> mCbSpeed{1.0f};
    std::atomic<int64_t> mCbStartOutputFrames{0};
    std::atomic<int64_t> mGeneratedOutputFrames{0};

    std::atomic<double> mMusicDurationSec{0.0};

    std::atomic<float> mPlaybackSpeed{1.0f};
    std::atomic<float> mSfxVolume{1.0f};
    std::atomic<float> mMusicVolume{1.0f};
    std::atomic<bool> mMusicLooping{false};

    std::atomic<bool> mPaused{true};
    std::atomic<bool> mPlaying{false};
    std::atomic<bool> mRestartRequested{false};
};

static AudioEngine *gEngine = nullptr;

} // namespace

extern "C" {

void audioEngine_create() {
    if (!gEngine) {
        gEngine = new AudioEngine();
    }
    gEngine->create();
}

void audioEngine_destroy() {
    if (gEngine) {
        gEngine->destroy();
        delete gEngine;
        gEngine = nullptr;
    }
}

void audioEngine_setMusicData(const float *pcm, int length, int inRate, int inCh) {
    if (!gEngine) return;
    gEngine->setMusicData(pcm, length, inRate, inCh);
}

void audioEngine_setSfxData(int noteType, const float *pcm, int length, int inRate, int inCh) {
    if (!gEngine) return;
    gEngine->setSfxData(noteType, pcm, length, inRate, inCh);
}

void audioEngine_prepareDefaultSfxIfMissing() {
    if (!gEngine) return;
    gEngine->prepareDefaultSfxIfMissing();
}

void audioEngine_triggerSfx(int noteType) {
    if (!gEngine) return;
    gEngine->triggerSfx(noteType);
}

void audioEngine_setPlaybackSpeed(float speed) {
    if (!gEngine) return;
    gEngine->setPlaybackSpeed(speed);
}

void audioEngine_setSfxVolume(float volume) {
    if (!gEngine) return;
    gEngine->setSfxVolume(volume);
}

void audioEngine_setMusicVolume(float volume) {
    if (!gEngine) return;
    gEngine->setMusicVolume(volume);
}

void audioEngine_setMusicLooping(bool looping) {
    if (!gEngine) return;
    gEngine->setMusicLooping(looping);
}

void audioEngine_start() {
    if (!gEngine) return;
    gEngine->start();
}

void audioEngine_pause(bool pause) {
    if (!gEngine) return;
    gEngine->pause(pause);
}

void audioEngine_restart() {
    if (!gEngine) return;
    gEngine->restart();
}

void audioEngine_stop() {
    if (!gEngine) return;
    gEngine->stop();
}

double audioEngine_getPlayheadSeconds() {
    if (!gEngine) return 0.0;
    return gEngine->getPlayheadSeconds();
}

double audioEngine_getMusicDurationSeconds() {
    if (!gEngine) return 0.0;
    return gEngine->getMusicDurationSeconds();
}

} // extern "C"
