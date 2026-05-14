package com.wuying.phigros.util;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import android.util.Log;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
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
        } catch (Exception e) {
            Log.w(TAG, "GBK extraction failed, retrying with UTF-8", e);
            deleteRecursive(outDir);
            //noinspection ResultOfMethodCallIgnored
            outDir.mkdirs();
            extracted = extractZip(zipFile, outDir, StandardCharsets.UTF_8);
        }

        DetectedPack pack = new DetectedPack();
        pack.rootDir = outDir;

        // First pass: match by common name patterns
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

        // Second pass: any file with matching extension
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

        // Third pass: chart content detection
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

        // Resolve from info file, with fallback scanning when paths don't match
        boolean infoHasBg = false;
        if (pack.infoFile != null) {
            try {
                InfoFileUtils.Info info = readInfoFileWithEncodingFallback(pack.infoFile);
                File infoDir = pack.infoFile.getParentFile();
                if (infoDir == null) infoDir = outDir;

                // Chart from info
                File fChart = resolveInfoPath(infoDir, info.chart);
                if (fChart != null && fChart.exists() && isLikelyChart(fChart)) {
                    pack.chartFile = fChart;
                } else if (fChart != null && !fChart.exists()) {
                    File found = findChartFileInExtracted(extracted, infoDir);
                    if (found != null) pack.chartFile = found;
                }

                // Music from info
                File fMusic = resolveInfoPath(infoDir, info.music);
                if (fMusic != null && fMusic.exists()) {
                    pack.musicFile = fMusic;
                } else if (fMusic != null && !fMusic.exists()) {
                    File found = findAudioFileInExtracted(extracted, infoDir);
                    if (found != null) pack.musicFile = found;
                }

                // Background from info
                File fBg = resolveInfoPath(infoDir, info.illustration);
                if (fBg != null && fBg.exists()) {
                    pack.bgFile = fBg;
                    infoHasBg = true;
                } else if (fBg != null && !fBg.exists()) {
                    File found = findImageFileInExtracted(extracted, infoDir);
                    if (found != null) {
                        pack.bgFile = found;
                        infoHasBg = true;
                    }
                }
            } catch (Exception ignored) {
            }
        }

        // Fallback from chart file's internal metadata for bg and music
        if (pack.chartFile != null) {
            try {
                InfoFileUtils.Info meta = InfoFileUtils.readInfoFromChartFile(pack.chartFile);
                File chartDir = pack.chartFile.getParentFile();
                if (chartDir == null) chartDir = outDir;

                if (!infoHasBg) {
                    File fBg = resolveInfoPath(chartDir, meta.illustration);
                    if (fBg != null && fBg.exists()) {
                        pack.bgFile = fBg;
                    } else if (fBg != null && !fBg.exists()) {
                        File found = findImageFileInExtracted(extracted, chartDir);
                        if (found != null) pack.bgFile = found;
                    }
                }

                if (pack.musicFile == null || !pack.musicFile.exists()) {
                    File fMusic = resolveInfoPath(chartDir, meta.music);
                    if (fMusic != null && fMusic.exists()) {
                        pack.musicFile = fMusic;
                    } else if (fMusic != null && !fMusic.exists()) {
                        File found = findAudioFileInExtracted(extracted, chartDir);
                        if (found != null) pack.musicFile = found;
                    }
                }
            } catch (Exception ignored) {
            }
        }

        // Final safety net: scan all extracted files for any still-missing asset
        if (pack.chartFile == null || !isLikelyChart(pack.chartFile)) {
            pack.chartFile = findChartFileInExtracted(extracted, outDir);
        }
        if (pack.musicFile == null || !pack.musicFile.exists()) {
            pack.musicFile = findAudioFileInExtracted(extracted, outDir);
        }
        if (pack.bgFile == null || !pack.bgFile.exists()) {
            pack.bgFile = findImageFileInExtracted(extracted, outDir);
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

    @Nullable
    private static File findChartFileInExtracted(@NonNull List<File> extracted, @Nullable File preferDir) {
        File fallback = null;
        for (File f : extracted) {
            String lower = f.getName().toLowerCase(Locale.US);
            if (lower.equals("info.json") || lower.equals("respack.json")) continue;
            if (!isLikelyChart(f)) continue;
            if (preferDir != null && preferDir.equals(f.getParentFile())) {
                return f;
            }
            if (fallback == null) fallback = f;
        }
        return fallback;
    }

    @Nullable
    private static File findAudioFileInExtracted(@NonNull List<File> extracted, @Nullable File preferDir) {
        File fallback = null;
        for (File f : extracted) {
            String lower = f.getName().toLowerCase(Locale.US);
            if (!isAudioFile(lower)) continue;
            if (preferDir != null && preferDir.equals(f.getParentFile())) {
                return f;
            }
            if (fallback == null) fallback = f;
        }
        return fallback;
    }

    @Nullable
    private static File findImageFileInExtracted(@NonNull List<File> extracted, @Nullable File preferDir) {
        File fallback = null;
        for (File f : extracted) {
            String lower = f.getName().toLowerCase(Locale.US);
            if (!isImageFile(lower)) continue;
            if (preferDir != null && preferDir.equals(f.getParentFile())) {
                return f;
            }
            if (fallback == null) fallback = f;
        }
        return fallback;
    }

    @NonNull
    private static InfoFileUtils.Info readInfoFileWithEncodingFallback(@NonNull File infoFile) {
        InfoFileUtils.Info info = InfoFileUtils.readInfoFile(infoFile);
        if (info == null) info = new InfoFileUtils.Info();

        File infoDir = infoFile.getParentFile();
        boolean needFallback = false;

        if (!info.chart.isEmpty()) {
            File resolved = resolveInfoPath(infoDir, info.chart);
            if (resolved != null && !resolved.exists()) needFallback = true;
        }
        if (!needFallback && !info.music.isEmpty()) {
            File resolved = resolveInfoPath(infoDir, info.music);
            if (resolved != null && !resolved.exists()) needFallback = true;
        }
        if (!needFallback && !info.illustration.isEmpty()) {
            File resolved = resolveInfoPath(infoDir, info.illustration);
            if (resolved != null && !resolved.exists()) needFallback = true;
        }

        if (!needFallback) return info;

        InfoFileUtils.Info gbkInfo = readInfoFileWithCharset(infoFile, Charset.forName("GBK"));
        if (gbkInfo == null) return info;

        boolean gbkBetter = false;
        if (!gbkInfo.chart.isEmpty()) {
            File resolved = resolveInfoPath(infoDir, gbkInfo.chart);
            if (resolved != null && resolved.exists()) gbkBetter = true;
        }
        if (!gbkBetter && !gbkInfo.music.isEmpty()) {
            File resolved = resolveInfoPath(infoDir, gbkInfo.music);
            if (resolved != null && resolved.exists()) gbkBetter = true;
        }
        if (!gbkBetter && !gbkInfo.illustration.isEmpty()) {
            File resolved = resolveInfoPath(infoDir, gbkInfo.illustration);
            if (resolved != null && resolved.exists()) gbkBetter = true;
        }

        if (gbkBetter) {
            if (gbkInfo.name.isEmpty() && !info.name.isEmpty()) gbkInfo.name = info.name;
            return gbkInfo;
        }
        return info;
    }

    @Nullable
    private static InfoFileUtils.Info readInfoFileWithCharset(@NonNull File file, @NonNull Charset charset) {
        if (!file.exists() || !file.isFile()) return null;
        String lowerName = file.getName().toLowerCase(Locale.ROOT);
        try {
            if (lowerName.endsWith(".json")) {
                return readJsonWithCharset(file, charset);
            } else if (lowerName.endsWith(".yml") || lowerName.endsWith(".yaml")) {
                return readYamlLikeWithCharset(file, charset);
            } else if (lowerName.endsWith(".txt")) {
                return readKeyValueTxtWithCharset(file, charset);
            } else if (lowerName.endsWith(".csv")) {
                return readCsvWithCharset(file, charset);
            } else {
                return readYamlLikeWithCharset(file, charset);
            }
        } catch (Exception ignored) {
            return null;
        }
    }

    @Nullable
    private static InfoFileUtils.Info readJsonWithCharset(@NonNull File file, @NonNull Charset charset) {
        try {
            String raw = readAllWithCharset(file, charset);
            if (raw == null) return null;
            InfoFileUtils.Info info = new InfoFileUtils.Info();
            com.fasterxml.jackson.databind.JsonNode root = new com.fasterxml.jackson.databind.ObjectMapper()
                    .configure(com.fasterxml.jackson.core.JsonParser.Feature.ALLOW_UNQUOTED_FIELD_NAMES, true)
                    .readTree(raw);
            if (!root.isObject()) return info;

            info.name = getFirstJsonStr(root, "name", "Name", "songName", "title");
            info.chart = getFirstJsonStr(root, "chart", "Chart", "chartPath");
            info.music = getFirstJsonStr(root, "music", "Music", "song", "Song", "audio");
            info.illustration = getFirstJsonStr(root, "illustration", "Illustration", "image", "Image", "picture", "Picture", "bg", "background");
            info.level = getFirstJsonStr(root, "level", "Level");
            info.difficulty = getFirstJsonStr(root, "difficulty", "Difficulty", "diff", "Diff");
            return info;
        } catch (Exception ignored) {
            return null;
        }
    }

    @Nullable
    private static InfoFileUtils.Info readYamlLikeWithCharset(@NonNull File file, @NonNull Charset charset) {
        java.util.Map<String, String> kv = new java.util.HashMap<>();
        try (BufferedReader br = new BufferedReader(new InputStreamReader(new FileInputStream(file), charset))) {
            String line;
            while ((line = br.readLine()) != null) {
                String s = line.trim();
                if (s.isEmpty() || s.startsWith("#")) continue;
                int idx = s.indexOf(':');
                if (idx <= 0) continue;
                String key = s.substring(0, idx).trim();
                String val = s.substring(idx + 1).trim();
                val = stripYamlVal(val);
                kv.put(key, val);
            }
        } catch (Exception ignored) {
            return null;
        }
        return infoFromKvMap(kv);
    }

    @Nullable
    private static InfoFileUtils.Info readKeyValueTxtWithCharset(@NonNull File file, @NonNull Charset charset) {
        java.util.Map<String, String> kv = new java.util.HashMap<>();
        try (BufferedReader br = new BufferedReader(new InputStreamReader(new FileInputStream(file), charset))) {
            String line;
            boolean first = true;
            while ((line = br.readLine()) != null) {
                String s = line.trim();
                if (first) {
                    first = false;
                    if (s.startsWith("#")) continue;
                }
                if (s.isEmpty()) continue;
                int idx = s.indexOf(':');
                if (idx <= 0) continue;
                String key = s.substring(0, idx).trim();
                String val = s.substring(idx + 1).trim();
                val = stripYamlVal(val);
                kv.put(key, val);
            }
        } catch (Exception ignored) {
            return null;
        }
        return infoFromKvMap(kv);
    }

    @Nullable
    private static InfoFileUtils.Info readCsvWithCharset(@NonNull File file, @NonNull Charset charset) {
        try (BufferedReader br = new BufferedReader(new InputStreamReader(new FileInputStream(file), charset))) {
            String headerLine = null;
            String dataLine = null;
            String line;
            while ((line = br.readLine()) != null) {
                if (line.trim().isEmpty()) continue;
                if (headerLine == null) headerLine = line;
                else { dataLine = line; break; }
            }
            if (headerLine == null || dataLine == null) return null;
            java.util.List<String> headers = parseCsvLineSimple(headerLine);
            java.util.List<String> values = parseCsvLineSimple(dataLine);
            java.util.Map<String, String> kv = new java.util.HashMap<>();
            int n = Math.min(headers.size(), values.size());
            for (int i = 0; i < n; i++) {
                kv.put(headers.get(i), values.get(i));
            }
            return infoFromKvMap(kv);
        } catch (Exception ignored) {
            return null;
        }
    }

    @Nullable
    private static InfoFileUtils.Info infoFromKvMap(@NonNull java.util.Map<String, String> kv) {
        InfoFileUtils.Info info = new InfoFileUtils.Info();
        java.util.Map<String, String> lower = new java.util.HashMap<>();
        for (java.util.Map.Entry<String, String> e : kv.entrySet()) {
            if (e.getKey() == null) continue;
            lower.put(e.getKey().trim().toLowerCase(Locale.ROOT), e.getValue());
        }
        info.name = firstNonEmptyStr(lower.get("name"), kv.get("Name"));
        info.music = firstNonEmptyStr(lower.get("music"), lower.get("song"), kv.get("Music"), kv.get("Song"));
        info.chart = firstNonEmptyStr(lower.get("chart"), kv.get("Chart"));
        info.illustration = firstNonEmptyStr(lower.get("illustration"), lower.get("image"), lower.get("picture"),
                kv.get("Illustration"), kv.get("Image"), kv.get("Picture"));
        info.level = firstNonEmptyStr(lower.get("level"), kv.get("Level"));
        info.difficulty = firstNonEmptyStr(lower.get("difficulty"), lower.get("diff"), kv.get("Difficulty"), kv.get("Diff"));
        return info;
    }

    @NonNull
    private static String getFirstJsonStr(@NonNull com.fasterxml.jackson.databind.JsonNode root, String... keys) {
        for (String k : keys) {
            if (k == null) continue;
            com.fasterxml.jackson.databind.JsonNode e = root.get(k);
            if (e == null || e.isNull()) continue;
            try {
                if (e.isValueNode()) {
                    String s = e.asText();
                    if (s != null && !s.isEmpty()) return s;
                }
            } catch (Exception ignored) { }
        }
        return "";
    }

    @NonNull
    private static String firstNonEmptyStr(String... vals) {
        if (vals == null) return "";
        for (String v : vals) {
            if (v != null && !v.isEmpty()) return v;
        }
        return "";
    }

    @Nullable
    private static String readAllWithCharset(@NonNull File file, @NonNull Charset charset) {
        try {
            StringBuilder sb = new StringBuilder();
            try (BufferedReader br = new BufferedReader(new InputStreamReader(new FileInputStream(file), charset))) {
                char[] buf = new char[4096];
                int n;
                while ((n = br.read(buf)) >= 0) {
                    sb.append(buf, 0, n);
                }
            }
            return sb.toString();
        } catch (Exception ignored) {
            return null;
        }
    }

    @NonNull
    private static String stripYamlVal(@NonNull String s) {
        String t = s.trim();
        if (t.length() >= 2) {
            char a = t.charAt(0);
            char b = t.charAt(t.length() - 1);
            if ((a == '"' && b == '"') || (a == '\'' && b == '\'')) {
                return t.substring(1, t.length() - 1);
            }
        }
        return t;
    }

    @NonNull
    private static java.util.List<String> parseCsvLineSimple(@NonNull String line) {
        java.util.List<String> out = new ArrayList<>();
        StringBuilder cur = new StringBuilder();
        boolean inQuote = false;
        for (int i = 0; i < line.length(); i++) {
            char c = line.charAt(i);
            if (c == '"') {
                if (inQuote && i + 1 < line.length() && line.charAt(i + 1) == '"') {
                    cur.append('"');
                    i++;
                } else {
                    inQuote = !inQuote;
                }
            } else if (c == ',' && !inQuote) {
                out.add(stripYamlVal(cur.toString().trim()));
                cur.setLength(0);
            } else {
                cur.append(c);
            }
        }
        out.add(stripYamlVal(cur.toString().trim()));
        return out;
    }
}
