#define MINIMP3_IMPLEMENTATION
#include "minimp3.h"

#define STB_VORBIS_IMPLEMENTATION
#define STB_VORBIS_NO_STDIO
#include "stb_vorbis.c"

#include <cstdio>
#include <cstdlib>
#include <cstring>
#include <vector>

struct DecodedAudio {
    float *pcm;
    int numSamples;
    int sampleRate;
    int channels;

    DecodedAudio() : pcm(nullptr), numSamples(0), sampleRate(44100), channels(2) {}
    ~DecodedAudio() { delete[] pcm; }
};

static bool readFileToMemory(const char *path, std::vector<uint8_t> &out) {
    FILE *f = fopen(path, "rb");
    if (!f) return false;
    fseek(f, 0, SEEK_END);
    long size = ftell(f);
    fseek(f, 0, SEEK_SET);
    if (size <= 0 || size > 512L * 1024 * 1024) { fclose(f); return false; }
    out.resize((size_t) size);
    size_t rd = fread(out.data(), 1, (size_t) size, f);
    fclose(f);
    return rd == (size_t) size;
}

enum AudioFormat { FMT_UNKNOWN, FMT_WAV, FMT_MP3, FMT_OGG };

static AudioFormat detectFormat(const uint8_t *data, size_t size) {
    if (size < 4) return FMT_UNKNOWN;
    if (data[0] == 'R' && data[1] == 'I' && data[2] == 'F' && data[3] == 'F') return FMT_WAV;
    if ((data[0] == 'I' && data[1] == 'D' && data[2] == '3') ||
        (data[0] == 0xFF && ((data[1] & 0xFE) == 0xFA || (data[1] & 0xFE) == 0xF2)))
        return FMT_MP3;
    if (data[0] == 'O' && data[1] == 'g' && data[2] == 'g' && data[3] == 'S') return FMT_OGG;
    return FMT_UNKNOWN;
}

static inline uint16_t readU16LE(const uint8_t *b) {
    uint16_t v;
    memcpy(&v, b, 2);
    return v;
}
static inline uint32_t readU32LE(const uint8_t *b) {
    uint32_t v;
    memcpy(&v, b, 4);
    return v;
}
static inline int32_t readI32LE(const uint8_t *b) {
    int32_t v;
    memcpy(&v, b, 4);
    return v;
}
static inline float readFloatLE(const uint8_t *b) {
    float v;
    memcpy(&v, b, 4);
    return v;
}

static DecodedAudio *decodeWAV(const uint8_t *data, size_t size) {
    if (size < 44) return nullptr;
    const uint8_t *p = data + 12; // skip RIFF header
    int audioFormat = 0, ch = 0, sr = 0, bps = 0;
    const uint8_t *dptr = nullptr;
    int dsize = 0;

    while (p + 8 <= data + size) {
        uint32_t cs = readU32LE(p + 4);
        if (memcmp(p, "fmt ", 4) == 0 && cs >= 16) {
            audioFormat = readU16LE(p + 8);
            ch = readU16LE(p + 10);
            sr = (int) readU32LE(p + 12);
            bps = readU16LE(p + 22);
        } else if (memcmp(p, "data", 4) == 0) {
            dptr = p + 8; dsize = (int) cs; break;
        }
        p += 8 + cs + (cs & 1);
    }

    if (!dptr || dsize <= 0 || ch <= 0 || sr <= 0) return nullptr;
    int frameSize = (bps / 8) * ch;
    if (frameSize <= 0) return nullptr;
    int frames = dsize / frameSize;
    int total = frames * ch;

    auto *out = new DecodedAudio();
    out->pcm = new float[total];
    out->numSamples = total;
    out->sampleRate = sr;
    out->channels = ch;

    if (audioFormat == 3 && bps == 32) {
        for (int i = 0; i < total; i++) {
            float v = readFloatLE(dptr + i * 4);
            if (v < -1.f) v = -1.f;
            if (v > 1.f) v = 1.f;
            out->pcm[i] = v;
        }
    } else if (audioFormat == 1) {
        switch (bps) {
            case 8:
                for (int i = 0; i < total; i++) out->pcm[i] = ((int)dptr[i] - 128) / 128.f;
                break;
            case 16:
                for (int i = 0; i < total; i++) {
                    out->pcm[i] = (short) readU16LE(dptr + i * 2) / 32768.f;
                }
                break;
            case 24:
                for (int i = 0; i < total; i++) {
                    const uint8_t *b = dptr + i * 3;
                    int v = b[0] | (b[1] << 8) | (b[2] << 16);
                    if (v & 0x800000) v |= 0xFF000000;
                    out->pcm[i] = v / 8388608.f;
                }
                break;
            case 32:
                for (int i = 0; i < total; i++) {
                    out->pcm[i] = (float)(readI32LE(dptr + i * 4) / 2147483648.0);
                }
                break;
            default: delete out; return nullptr;
        }
    } else { delete out; return nullptr; }

    return out;
}

static DecodedAudio *decodeMP3(const uint8_t *data, size_t size) {
    mp3dec_t mp3d;
    mp3dec_init(&mp3d);
    mp3dec_frame_info_t info;
    short pcm[MINIMP3_MAX_SAMPLES_PER_FRAME];

    std::vector<float> all;
    const uint8_t *buf = data;
    int rem = (int) size;
    int sampleRate = 0, channels = 0;

    while (rem > 0) {
        int samples = mp3dec_decode_frame(&mp3d, buf, rem, pcm, &info);
        if (samples <= 0 || info.frame_bytes <= 0) break;
        if (sampleRate == 0) { sampleRate = info.hz; channels = info.channels; }
        for (int i = 0; i < samples * info.channels; i++)
            all.push_back(pcm[i] * (1.f / 32768.f));
        buf += info.frame_bytes;
        rem -= info.frame_bytes;
    }

    if (all.empty()) return nullptr;
    auto *out = new DecodedAudio();
    out->numSamples = (int) all.size();
    out->pcm = new float[all.size()];
    memcpy(out->pcm, all.data(), all.size() * sizeof(float));
    out->sampleRate = sampleRate > 0 ? sampleRate : 44100;
    out->channels = channels > 0 ? channels : 2;
    return out;
}

static DecodedAudio *decodeOGG(const uint8_t *data, size_t size) {
    int err = 0;
    stb_vorbis *v = stb_vorbis_open_memory(data, (int) size, &err, nullptr);
    if (!v) return nullptr;

    stb_vorbis_info info = stb_vorbis_get_info(v);

    int totalFrames = stb_vorbis_stream_length_in_samples(v);
    if (totalFrames <= 0) {
        // Streaming decode
        std::vector<float> all;
        float buf[4096];
        int n;
        while ((n = stb_vorbis_get_samples_float_interleaved(v, info.channels, buf, 4096)) > 0) {
            int nc = n * info.channels;
            for (int i = 0; i < nc; i++) all.push_back(buf[i]);
        }
        stb_vorbis_close(v);
        if (all.empty()) return nullptr;
        auto *out = new DecodedAudio();
        out->numSamples = (int) all.size();
        out->pcm = new float[all.size()];
        memcpy(out->pcm, all.data(), all.size() * sizeof(float));
        out->sampleRate = (int) info.sample_rate;
        out->channels = info.channels;
        return out;
    }

    int totalSamples = totalFrames * info.channels;
    auto *out = new DecodedAudio();
    out->pcm = new float[totalSamples];
    out->sampleRate = (int) info.sample_rate;
    out->channels = info.channels;

    int decoded = stb_vorbis_get_samples_float_interleaved(v, info.channels, out->pcm, totalSamples);
    stb_vorbis_close(v);

    if (decoded <= 0) { delete out; return nullptr; }
    out->numSamples = decoded * info.channels;
    return out;
}

extern "C" {

float *audioDecoder_decodeFile(const char *filePath, int *outNumSamples,
                               int *outSampleRate, int *outChannels) {
    std::vector<uint8_t> raw;
    if (!readFileToMemory(filePath, raw) || raw.empty()) return nullptr;

    DecodedAudio *dec = nullptr;
    switch (detectFormat(raw.data(), raw.size())) {
        case FMT_WAV:  dec = decodeWAV(raw.data(), raw.size()); break;
        case FMT_MP3:  dec = decodeMP3(raw.data(), raw.size()); break;
        case FMT_OGG:  dec = decodeOGG(raw.data(), raw.size()); break;
        default:       return nullptr;
    }

    if (!dec) return nullptr;
    *outNumSamples = dec->numSamples;
    *outSampleRate = dec->sampleRate;
    *outChannels = dec->channels;

    // Transfer ownership of the float buffer to the caller
    float *pcm = dec->pcm;
    dec->pcm = nullptr;
    delete dec;
    return pcm;
}

void audioDecoder_freePcm(float *pcm) {
    delete[] pcm;
}

} // extern "C"
