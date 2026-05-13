package com.wuying.phigros.util;

import android.media.AudioFormat;
import android.media.MediaCodec;
import android.media.MediaExtractor;
import android.media.MediaFormat;

import androidx.annotation.NonNull;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.Arrays;

public final class PcmDecoder {

    private PcmDecoder() {}

    public static final class DecodedAudio {
        public final float[] pcm;
        public final int length;
        public final int sampleRate;
        public final int channels;

        public DecodedAudio(float[] pcm, int length, int sampleRate, int channels) {
            this.pcm = pcm;
            this.length = Math.max(0, length);
            this.sampleRate = sampleRate;
            this.channels = channels;
        }

        public int getNumFrames() {
            if (channels <= 0) return 0;
            return length / channels;
        }
    }

    /**
     * Decode audio file to float PCM using native fast-path first, then Android MediaCodec fallback.
     */
    @NonNull
    public static DecodedAudio decodeFileToFloatPcm(@NonNull String filePath) throws IOException {
        DecodedAudio nativeResult = NativeAudioDecoder.decodeFile(filePath);
        if (nativeResult != null) {
            return nativeResult;
        }

        return decodeViaMediaCodec(filePath);
    }

    @NonNull
    private static DecodedAudio decodeViaMediaCodec(@NonNull String filePath) throws IOException {
        MediaExtractor extractor = null;
        MediaCodec codec = null;

        try {
            extractor = new MediaExtractor();
            extractor.setDataSource(filePath);

            int trackIndex = selectAudioTrack(extractor);
            if (trackIndex < 0) {
                throw new IOException("No audio track found in: " + filePath);
            }

            extractor.selectTrack(trackIndex);
            MediaFormat inputFormat = extractor.getTrackFormat(trackIndex);

            String mime = inputFormat.getString(MediaFormat.KEY_MIME);
            if (mime == null || mime.isEmpty()) {
                throw new IOException("Unknown audio MIME type in: " + filePath);
            }

            long durationUs = inputFormat.containsKey(MediaFormat.KEY_DURATION)
                    ? inputFormat.getLong(MediaFormat.KEY_DURATION) : 0L;

            codec = MediaCodec.createDecoderByType(mime);
            codec.configure(inputFormat, null, null, 0);
            codec.start();

            int sampleRate = inputFormat.containsKey(MediaFormat.KEY_SAMPLE_RATE)
                    ? inputFormat.getInteger(MediaFormat.KEY_SAMPLE_RATE) : 44100;
            int channels = inputFormat.containsKey(MediaFormat.KEY_CHANNEL_COUNT)
                    ? inputFormat.getInteger(MediaFormat.KEY_CHANNEL_COUNT) : 2;

            if (sampleRate <= 0 || sampleRate > 384000) sampleRate = 44100;
            if (channels <= 0 || channels > 16) channels = 2;

            long estimatedFrames;
            if (durationUs > 0) {
                estimatedFrames = (long) ((durationUs / 1_000_000.0) * sampleRate);
            } else {
                estimatedFrames = 600L * sampleRate;
            }
            long estimatedSamples = estimatedFrames * channels;
            long maxSamples = 600L * 48000L * 2L;
            if (estimatedSamples > maxSamples) estimatedSamples = maxSamples;

            int initialCap = (int) estimatedSamples;
            initialCap = Math.max(1024 * 1024, initialCap);
            FloatArrayBuilder out = new FloatArrayBuilder(initialCap);

            boolean inputDone = false;
            boolean outputDone = false;

            MediaCodec.BufferInfo info = new MediaCodec.BufferInfo();

            int pcmEncoding = AudioFormat.ENCODING_PCM_16BIT;

            while (!outputDone) {
                if (!inputDone) {
                    int inIdx;
                    while ((inIdx = codec.dequeueInputBuffer(0)) >= 0) {
                        ByteBuffer inputBuffer = codec.getInputBuffer(inIdx);
                        if (inputBuffer == null) continue;
                        int sampleSize = extractor.readSampleData(inputBuffer, 0);
                        if (sampleSize < 0) {
                            codec.queueInputBuffer(inIdx, 0, 0, 0,
                                    MediaCodec.BUFFER_FLAG_END_OF_STREAM);
                            inputDone = true;
                            break;
                        }
                        codec.queueInputBuffer(inIdx, 0, sampleSize,
                                extractor.getSampleTime(), 0);
                        extractor.advance();
                    }
                }

                int outIdx;
                while ((outIdx = codec.dequeueOutputBuffer(info, 0)) >= 0) {
                    ByteBuffer outputBuffer = codec.getOutputBuffer(outIdx);
                    if (outputBuffer != null && info.size > 0) {
                        outputBuffer.position(info.offset);
                        outputBuffer.limit(info.offset + info.size);
                        out.appendPcm(outputBuffer, pcmEncoding);
                    }
                    codec.releaseOutputBuffer(outIdx, false);
                    if ((info.flags & MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0) {
                        outputDone = true;
                    }
                }
                if (outIdx == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                    MediaFormat newFormat = codec.getOutputFormat();
                    if (newFormat.containsKey(MediaFormat.KEY_SAMPLE_RATE)) {
                        sampleRate = newFormat.getInteger(MediaFormat.KEY_SAMPLE_RATE);
                    }
                    if (newFormat.containsKey(MediaFormat.KEY_CHANNEL_COUNT)) {
                        channels = newFormat.getInteger(MediaFormat.KEY_CHANNEL_COUNT);
                    }
                    if (newFormat.containsKey("pcm-encoding")) {
                        pcmEncoding = newFormat.getInteger("pcm-encoding");
                    }
                } else if (outIdx == MediaCodec.INFO_TRY_AGAIN_LATER) {
                    if (inputDone) {
                        Thread.yield();
                    }
                }
            }

            if (out.size() == 0) {
                throw new IOException("Decoded audio is empty for: " + filePath);
            }

            return new DecodedAudio(out.buffer(), out.size(), sampleRate, channels);
        } catch (Exception e) {
            throw new IOException("Failed to decode audio file: " + filePath + " — " + e.getMessage(), e);
        } finally {
            if (codec != null) {
                try { codec.stop(); } catch (Exception ignored) {}
                try { codec.release(); } catch (Exception ignored) {}
            }
            if (extractor != null) {
                try { extractor.release(); } catch (Exception ignored) {}
            }
        }
    }

    private static int selectAudioTrack(MediaExtractor extractor) {
        int numTracks = extractor.getTrackCount();
        for (int i = 0; i < numTracks; i++) {
            try {
                MediaFormat format = extractor.getTrackFormat(i);
                String mime = format.getString(MediaFormat.KEY_MIME);
                if (mime != null && mime.startsWith("audio/")) {
                    return i;
                }
            } catch (Exception ignored) {
            }
        }
        return -1;
    }

    private static final class FloatArrayBuilder {
        private float[] data;
        private int size;

        FloatArrayBuilder(int initialCap) {
            data = new float[Math.max(1024, initialCap)];
            size = 0;
        }

        void appendPcm(ByteBuffer outputBuffer, int pcmEncoding) {
            if (pcmEncoding == AudioFormat.ENCODING_PCM_FLOAT) {
                outputBuffer.order(ByteOrder.LITTLE_ENDIAN);
                int numSamples = outputBuffer.remaining() / 4;
                ensureCapacity(size + numSamples);
                java.nio.FloatBuffer fb = outputBuffer.asFloatBuffer();
                int n = fb.remaining();
                fb.get(data, size, n);
                size += n;
            } else {
                outputBuffer.order(ByteOrder.LITTLE_ENDIAN);
                int numSamples = outputBuffer.remaining() / 2;
                ensureCapacity(size + numSamples);
                java.nio.ShortBuffer sb = outputBuffer.asShortBuffer();
                int n = sb.remaining();
                int off = size;
                for (int i = 0; i < n; i++) {
                    data[off + i] = sb.get(i) * (1.0f / 32768.0f);
                }
                size += n;
            }
        }

        private void ensureCapacity(int needed) {
            if (needed <= data.length) return;
            int newCap = data.length;
            while (newCap < needed) {
                if (newCap < 8 * 1024 * 1024) {
                    newCap = (int) (newCap * 1.5f) + 1;
                } else {
                    newCap = newCap * 2;
                }
            }
            data = Arrays.copyOf(data, newCap);
        }

        int size() { return size; }
        float[] buffer() { return data; }
    }
}
