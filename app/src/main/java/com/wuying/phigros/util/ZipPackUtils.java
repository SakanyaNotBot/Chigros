package com.wuying.phigros.util;

import androidx.annotation.NonNull;

import android.util.Log;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Enumeration;
import java.util.List;
import java.util.Locale;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

public final class ZipPackUtils {

    private static final String TAG = "ZipPackUtils";

    private ZipPackUtils() {}

    public static final class DetectedPack {
        public File musicFile;
        public File chartFile;
        public File bgFile;
        public File infoFile;
        public File rootDir;
    }

    @NonNull
    public static DetectedPack extractAndDetect(@NonNull File zipFile, @NonNull File baseOutDir) throws IOException {
        File outDir = new File(baseOutDir, "pack_" + System.currentTimeMillis());
        //noinspection ResultOfMethodCallIgnored
        outDir.mkdirs();

        List<File> extracted = new ArrayList<>();

        try {
            extracted = extractZip(zipFile, outDir, Charset.forName("GBK"));
        } catch (IOException e) {
            Log.w(TAG, "GBK extraction failed, retrying with UTF-8", e);
            deleteRecursive(outDir);
            //noinspection ResultOfMethodCallIgnored
            outDir.mkdirs();
            extracted = extractZip(zipFile, outDir, StandardCharsets.UTF_8);
        }

        DetectedPack pack = new DetectedPack();
        pack.rootDir = outDir;

        for (File f : extracted) {
            String lower = f.getName().toLowerCase(Locale.US);
            if (pack.infoFile == null && isInfoFile(lower)) {
                pack.infoFile = f;
            }
            if (pack.chartFile == null && (lower.equals("chart.json") || lower.equals("chart.pec") || lower.equals("chart.rpe"))) {
                pack.chartFile = f;
            }
            if (pack.bgFile == null && (lower.equals("image.png") || lower.equals("bg.png") || lower.contains("image") || lower.contains("bg"))) {
                if (isImageFile(lower)) pack.bgFile = f;
            }
            if (pack.musicFile == null && (lower.contains("music") || lower.contains("song"))) {
                if (isAudioFile(lower)) pack.musicFile = f;
            }
        }

        for (File f : extracted) {
            String lower = f.getName().toLowerCase(Locale.US);
            if (pack.musicFile == null && isAudioFile(lower)) {
                pack.musicFile = f;
            }
            if (pack.bgFile == null && isImageFile(lower)) {
                pack.bgFile = f;
            }
            if (pack.infoFile == null && isInfoFile(lower)) {
                pack.infoFile = f;
            }
        }

        if (pack.chartFile == null || !isLikelyChart(pack.chartFile)) {
            pack.chartFile = null;
            for (File f : extracted) {
                String lower = f.getName().toLowerCase(Locale.US);
                if (lower.equals("info.json") || lower.equals("respack.json")) continue;
                if (looksLikeOfficialChartJson(f) || looksLikeRpeChartJson(f) || looksLikeAnyChartJson(f) || looksLikePhiEditText(f)) {
                    pack.chartFile = f;
                    break;
                }
            }
        }

        boolean infoHasBg = false;
        if (pack.infoFile != null) {
            try {
                InfoFileUtils.Info info = InfoFileUtils.readInfoFile(pack.infoFile);
                File baseDir = pack.infoFile.getParentFile();
                if (baseDir == null) baseDir = outDir;

                File fChart = resolveInfoPath(baseDir, info.chart);
                if (fChart != null && fChart.exists() && isLikelyChart(fChart)) {
                    pack.chartFile = fChart;
                }
                File fMusic = resolveInfoPath(baseDir, info.music);
                if (fMusic != null && fMusic.exists()) {
                    pack.musicFile = fMusic;
                }
                File fBg = resolveInfoPath(baseDir, info.illustration);
                if (fBg != null && fBg.exists()) {
                    pack.bgFile = fBg;
                    infoHasBg = true;
                }
            } catch (Exception ignored) {
            }
        }

        if (!infoHasBg && pack.chartFile != null) {
            try {
                InfoFileUtils.Info meta = InfoFileUtils.readInfoFromChartFile(pack.chartFile);
                File baseDir = pack.chartFile.getParentFile();
                if (baseDir == null) baseDir = outDir;

                File fBg = resolveInfoPath(baseDir, meta.illustration);
                if (fBg != null && fBg.exists()) {
                    pack.bgFile = fBg;
                }
            } catch (Exception ignored) {
            }
        }

        return pack;
    }

    private static List<File> extractZip(File zipFile, File outDir, Charset charset) throws IOException {
        List<File> extracted = new ArrayList<>();
        try (ZipFile zf = new ZipFile(zipFile, charset)) {
            byte[] buf = new byte[64 * 1024];
            Enumeration<? extends ZipEntry> entries = zf.entries();
            while (entries.hasMoreElements()) {
                ZipEntry entry = entries.nextElement();
                if (entry.isDirectory()) continue;

                String name = entry.getName();
                File outFile = new File(outDir, name);
                String outPath = outFile.getCanonicalPath();
                String basePath = outDir.getCanonicalPath();
                if (!outPath.startsWith(basePath + File.separator)) continue;

                File parent = outFile.getParentFile();
                if (parent != null) {
                    //noinspection ResultOfMethodCallIgnored
                    parent.mkdirs();
                }

                try (InputStream is = zf.getInputStream(entry);
                     FileOutputStream fos = new FileOutputStream(outFile)) {
                    int n;
                    while ((n = is.read(buf)) > 0) {
                        fos.write(buf, 0, n);
                    }
                    fos.flush();
                }

                extracted.add(outFile);
            }
        }
        return extracted;
    }

    private static void deleteRecursive(File fileOrDir) {
        if (fileOrDir.isDirectory()) {
            File[] children = fileOrDir.listFiles();
            if (children != null) {
                for (File child : children) {
                    deleteRecursive(child);
                }
            }
        }
        //noinspection ResultOfMethodCallIgnored
        fileOrDir.delete();
    }

    private static boolean isInfoFile(String lowerName) {
        return lowerName.equals("info.json")
                || lowerName.equals("info.yml")
                || lowerName.equals("info.yaml")
                || lowerName.equals("info.txt")
                || lowerName.equals("info.csv");
    }

    private static File resolveInfoPath(File rootDir, String relPath) {
        if (rootDir == null || relPath == null) return null;
        String p = relPath.trim();
        if (p.isEmpty()) return null;
        p = p.replace("\\", "/");
        while (p.startsWith("/")) p = p.substring(1);
        if (p.startsWith("./")) p = p.substring(2);
        return new File(rootDir, p);
    }

    private static boolean isAudioFile(String lowerName) {
        return lowerName.endsWith(".ogg")
                || lowerName.endsWith(".mp3")
                || lowerName.endsWith(".wav")
                || lowerName.endsWith(".m4a")
                || lowerName.endsWith(".aac")
                || lowerName.endsWith(".flac");
    }

    private static boolean isImageFile(String lowerName) {
        return lowerName.endsWith(".png")
                || lowerName.endsWith(".jpg")
                || lowerName.endsWith(".jpeg")
                || lowerName.endsWith(".webp");
    }

    private static String readHead(File f, int maxBytes) {
        try (InputStream in = new FileInputStream(f)) {
            byte[] buf = new byte[Math.max(1024, maxBytes)];
            int n = in.read(buf);
            if (n <= 0) return null;
            return new String(buf, 0, n, StandardCharsets.UTF_8);
        } catch (Exception ignored) {
            return null;
        }
    }

    private static boolean looksLikeOfficialChartJson(File f) {
        String head = readHead(f, 64 * 1024);
        if (head == null) return false;
        return head.contains("\"formatVersion\"") && head.contains("judgeLineList");
    }

    private static boolean looksLikeRpeChartJson(File f) {
        String head = readHead(f, 64 * 1024);
        if (head == null) return false;
        return head.contains("\"RPEVersion\"") && head.contains("judgeLineList");
    }

    private static boolean looksLikeAnyChartJson(File f) {
        String head = readHead(f, 64 * 1024);
        if (head == null) return false;
        return head.contains("judgeLineList");
    }

    private static boolean looksLikePhiEditText(File f) {
        String head = readHead(f, 32 * 1024);
        if (head == null) return false;
        String h = head.toLowerCase(Locale.US);
        return h.contains("\nbp ") || h.startsWith("bp ")
                || h.contains("\ncv ") || h.contains("\ncp ") || h.contains("\ncm ")
                || h.contains("\nn1 ") || h.contains("\nn2 ") || h.contains("\nn3 ") || h.contains("\nn4 ");
    }

    private static boolean isLikelyChart(File f) {
        if (f == null || !f.isFile()) return false;
        return looksLikeOfficialChartJson(f)
                || looksLikeRpeChartJson(f)
                || looksLikePhiEditText(f)
                || looksLikeAnyChartJson(f);
    }
}
