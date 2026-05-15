package com.wuying.phigros.util;

import android.content.Context;

import androidx.annotation.NonNull;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;

/**
 * WAV decoder for small SFX files from res/raw.
 *
 * <p>Supports PCM Integer (8/16/24/32 bit) and IEEE Float (32 bit).
 */
public final class WavDecoder {

    private WavDecoder() {}

    @NonNull
    public static PcmDecoder.DecodedAudio decodeRawWavToFloatPcm(@NonNull Context ctx, int resId) throws IOException {
        byte[] bytes = readAll(ctx.getResources().openRawResource(resId));
        return decodeWavBytes(bytes);
    }

    @NonNull
    public static PcmDecoder.DecodedAudio decodeWavFileToFloatPcm(@NonNull File file) throws IOException {
        byte[] bytes;
        try (FileInputStream fis = new FileInputStream(file)) {
            bytes = readAll(fis);
        }
        return decodeWavBytes(bytes);
    }

    @NonNull
    private static PcmDecoder.DecodedAudio decodeWavBytes(@NonNull byte[] bytes) throws IOException {
        if (bytes.length < 12) {
            throw new IOException("WAV too small");
        }

        ByteBuffer bb = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN);
        String riff = readFourCC(bb);
        if (!"RIFF".equals(riff) && !"RIFX".equals(riff)) {
            throw new IOException("Not a RIFF WAV: " + riff);
        }
        skip(bb, 4);
        String wave = readFourCC(bb);
        if (!"WAVE".equals(wave)) {
            throw new IOException("Not a WAVE file: " + wave);
        }

        int audioFormat = 0;
        int channels = 0;
        int sampleRate = 0;
        int bitsPerSample = 0;
        int dataOffset = -1;
        int dataSize = 0;

        while (bb.remaining() >= 8) {
            String chunkId = readFourCC(bb);
            int chunkSize = bb.getInt();
            if (chunkSize < 0) {
                throw new IOException("Invalid chunk size");
            }

            int chunkStart = bb.position();
            int chunkEnd = chunkStart + chunkSize;
            if (chunkEnd < chunkStart || chunkEnd > bytes.length) {
                throw new IOException("Chunk out of range: " + chunkId);
            }

            if ("fmt ".equals(chunkId)) {
                if (chunkSize < 16) {
                    throw new IOException("fmt chunk too small");
                }
                audioFormat = u16(bb.getShort());
                channels = u16(bb.getShort());
                sampleRate = bb.getInt();
                skip(bb, 4);
                skip(bb, 2);
                bitsPerSample = u16(bb.getShort());
                bb.position(chunkEnd);
            } else if ("data".equals(chunkId)) {
                dataOffset = chunkStart;
                dataSize = chunkSize;
                bb.position(chunkEnd);
                break;
            } else {
                bb.position(chunkEnd);
            }

            if ((chunkSize & 1) == 1 && bb.remaining() > 0) {
                bb.position(bb.position() + 1);
            }
        }

        if (channels <= 0 || sampleRate <= 0) {
            throw new IOException("Invalid fmt chunk (channels/sampleRate)");
        }
        if (dataOffset < 0 || dataSize <= 0) {
            throw new IOException("Missing data chunk");
        }
        if (dataOffset + dataSize > bytes.length) {
            throw new IOException("data chunk out of range");
        }

        int bytesPerSample;
        boolean isFloat = false;

        if (audioFormat == 1) {
            if (bitsPerSample != 8 && bitsPerSample != 16 && bitsPerSample != 24 && bitsPerSample != 32) {
                throw new IOException("Unsupported PCM bits: " + bitsPerSample);
            }
            bytesPerSample = bitsPerSample / 8;
        } else if (audioFormat == 3) {
            if (bitsPerSample != 32) {
                throw new IOException("Unsupported float bits: " + bitsPerSample);
            }
            isFloat = true;
            bytesPerSample = 4;
        } else {
            throw new IOException("Unsupported WAV format: " + audioFormat);
        }

        int frameSize = bytesPerSample * channels;
        if (frameSize <= 0) {
            throw new IOException("Invalid frame size");
        }

        int frames = dataSize / frameSize;
        int samples = frames * channels;
        float[] pcm = new float[samples];

        if (isFloat) {
            ByteBuffer db = ByteBuffer.wrap(bytes, dataOffset, frames * frameSize).order(ByteOrder.LITTLE_ENDIAN);
            for (int i = 0; i < samples; i++) {
                float v = db.getFloat();
                if (v < -1f) v = -1f;
                if (v > 1f) v = 1f;
                pcm[i] = v;
            }
        } else {
            switch (bitsPerSample) {
                case 8: {
                    int p = dataOffset;
                    for (int i = 0; i < samples; i++) {
                        int u = bytes[p++] & 0xFF;
                        pcm[i] = (u - 128) / 128f;
                    }
                    break;
                }
                case 16: {
                    ByteBuffer db = ByteBuffer.wrap(bytes, dataOffset, frames * frameSize).order(ByteOrder.LITTLE_ENDIAN);
                    for (int i = 0; i < samples; i++) {
                        short s = db.getShort();
                        pcm[i] = s / 32768f;
                    }
                    break;
                }
                case 24: {
                    int p = dataOffset;
                    for (int i = 0; i < samples; i++) {
                        int b0 = bytes[p++] & 0xFF;
                        int b1 = bytes[p++] & 0xFF;
                        int b2 = bytes[p++] & 0xFF;
                        int v = (b2 << 16) | (b1 << 8) | b0;
                        if ((v & 0x00800000) != 0) v |= 0xFF000000;
                        pcm[i] = v / 8388608f;
                    }
                    break;
                }
                case 32: {
                    ByteBuffer db = ByteBuffer.wrap(bytes, dataOffset, frames * frameSize).order(ByteOrder.LITTLE_ENDIAN);
                    for (int i = 0; i < samples; i++) {
                        int v = db.getInt();
                        pcm[i] = (float) (v / 2147483648.0);
                    }
                    break;
                }
                default:
                    throw new IOException("Unsupported PCM bits: " + bitsPerSample);
            }
        }

        return new PcmDecoder.DecodedAudio(pcm, pcm.length, sampleRate, channels);
    }

    @NonNull
    private static String readFourCC(@NonNull ByteBuffer bb) throws IOException {
        if (bb.remaining() < 4) throw new IOException("Unexpected EOF");
        byte[] c = new byte[4];
        bb.get(c);
        return new String(c, StandardCharsets.US_ASCII);
    }

    private static void skip(@NonNull ByteBuffer bb, int n) throws IOException {
        if (n < 0 || bb.remaining() < n) throw new IOException("Unexpected EOF");
        bb.position(bb.position() + n);
    }

    private static int u16(short s) {
        return s & 0xFFFF;
    }

    @NonNull
    private static byte[] readAll(@NonNull InputStream is) throws IOException {
        try {
            ByteArrayOutputStream baos = new ByteArrayOutputStream(64 * 1024);
            byte[] buf = new byte[16 * 1024];
            int n;
            while ((n = is.read(buf)) >= 0) {
                if (n > 0) baos.write(buf, 0, n);
            }
            return baos.toByteArray();
        } finally {
            try {
                is.close();
            } catch (Exception ignored) {
            }
        }
    }
}
