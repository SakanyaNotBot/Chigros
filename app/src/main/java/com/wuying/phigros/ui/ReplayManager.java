package com.wuying.phigros.ui;

import android.content.Context;
import android.content.SharedPreferences;
import android.os.Environment;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.wuying.phigros.R;
import com.wuying.phigros.game.PlayResult;
import com.wuying.phigros.game.ReplayData;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.UUID;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;
import java.util.zip.ZipOutputStream;

public final class ReplayManager {

    private static final String PREFS_NAME = "replay_settings";
    private static final String PREF_REPLAY_ENABLED = "replay_enabled";
    private static final String PREF_REPLAY_IDS = "replay_ids";
    private static final String REPLAYS_DIR = "replays";
    private static final String CACHE_REPLAY_DIR = "replay_cache";
    private static final String CACHE_REPLAY_FILE = "replay.json";
    private static final String EXPORT_DIR = "Chigros";

    private static final ObjectMapper JSON_MAPPER = new ObjectMapper()
            .configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);

    public static class ReplayInfo {
        public final String uuid;
        public final String songName;
        public final String difficulty;
        public final int score;
        public final long timestamp;
        public boolean favorite;

        ReplayInfo(String uuid, String songName, String difficulty, int score, long timestamp, boolean favorite) {
            this.uuid = uuid;
            this.songName = songName;
            this.difficulty = difficulty;
            this.score = score;
            this.timestamp = timestamp;
            this.favorite = favorite;
        }
    }

    private ReplayManager() {}

    // ---- Preferences ----

    private static SharedPreferences getPrefs(Context context) {
        return context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
    }

    public static boolean isReplayEnabled(Context context) {
        return getPrefs(context).getBoolean(PREF_REPLAY_ENABLED, false);
    }

    public static void setReplayEnabled(Context context, boolean enabled) {
        getPrefs(context).edit().putBoolean(PREF_REPLAY_ENABLED, enabled).apply();
    }

    // ---- Recording ----

    public static ReplayData startRecording() {
        return new ReplayData();
    }

    public static void finishRecording(ReplayData data, PlayResult result, String songName, String difficulty) {
        data.meta = ReplayData.ReplayMeta.fromPlayResult(result, songName, difficulty);
    }

    public static File saveReplayToCache(Context context, ReplayData data) throws IOException {
        File cacheDir = new File(context.getCacheDir(), CACHE_REPLAY_DIR);
        if (!cacheDir.exists()) cacheDir.mkdirs();
        File jsonFile = new File(cacheDir, CACHE_REPLAY_FILE);
        JSON_MAPPER.writeValue(jsonFile, data);
        return jsonFile;
    }

    public static ReplayData loadReplayJson(File file) throws IOException {
        return JSON_MAPPER.readValue(file, ReplayData.class);
    }

    public static void clearCachedReplay(Context context) {
        File cacheDir = new File(context.getCacheDir(), CACHE_REPLAY_DIR);
        deleteRecursive(cacheDir);
    }

    public static File getCachedReplayFile(Context context) {
        return new File(new File(context.getCacheDir(), CACHE_REPLAY_DIR), CACHE_REPLAY_FILE);
    }

    // ---- Persistent Storage ----

    private static File getReplaysRoot(Context context) {
        return new File(context.getFilesDir(), REPLAYS_DIR);
    }

    public static File getReplayDir(Context context, String uuid) {
        return new File(getReplaysRoot(context), uuid);
    }

    public static File getChartFile(Context context, String uuid) {
        File infoFile = new File(getReplayDir(context, uuid), "info.json");
        if (!infoFile.isFile()) return null;
        try {
            InfoJson info = JSON_MAPPER.readValue(infoFile, InfoJson.class);
            if (info.chartRelPath != null && !info.chartRelPath.isEmpty()) {
                return new File(getReplayDir(context, uuid), info.chartRelPath);
            }
        } catch (Exception ignored) {}
        return null;
    }

    public static File getMusicFile(Context context, String uuid) {
        File infoFile = new File(getReplayDir(context, uuid), "info.json");
        if (!infoFile.isFile()) return null;
        try {
            InfoJson info = JSON_MAPPER.readValue(infoFile, InfoJson.class);
            if (info.musicRelPath != null && !info.musicRelPath.isEmpty()) {
                return new File(getReplayDir(context, uuid), info.musicRelPath);
            }
        } catch (Exception ignored) {}
        return null;
    }

    public static File getBgFile(Context context, String uuid) {
        File infoFile = new File(getReplayDir(context, uuid), "info.json");
        if (!infoFile.isFile()) return null;
        try {
            InfoJson info = JSON_MAPPER.readValue(infoFile, InfoJson.class);
            if (info.bgRelPath != null && !info.bgRelPath.isEmpty()) {
                return new File(getReplayDir(context, uuid), info.bgRelPath);
            }
        } catch (Exception ignored) {}
        return null;
    }

    /**
     * Check if a replay with the given metadata is already in the saved list.
     */
    public static boolean isReplayAlreadySaved(Context context, ReplayData data) {
        if (data == null || data.meta == null) return false;
        List<ReplayInfo> existing = getReplayList(context);
        for (ReplayInfo ri : existing) {
            if (ri.songName.equals(data.meta.songName)
                    && ri.score == data.meta.score
                    && Math.abs(ri.timestamp - data.meta.timestamp) < 5000) {
                return true;
            }
        }
        return false;
    }

    public static ReplayData getReplayData(Context context, String uuid) {
        File replayJson = new File(getReplayDir(context, uuid), "replay.json");
        if (!replayJson.isFile()) return null;
        try {
            return loadReplayJson(replayJson);
        } catch (Exception e) {
            return null;
        }
    }

    public static InfoJson getReplayInfo(Context context, String uuid) {
        File infoFile = new File(getReplayDir(context, uuid), "info.json");
        if (!infoFile.isFile()) return null;
        try {
            return JSON_MAPPER.readValue(infoFile, InfoJson.class);
        } catch (Exception e) {
            return null;
        }
    }

    /**
     * Persist a replay to internal storage. Copies the entire chart folder,
     * music, and background into the replay directory.
     */
    public static String savePersistedReplay(Context context, ReplayData data,
                                              File chartFile, File musicFile, File bgFile,
                                              InfoJson settings) throws IOException {
        // Check for duplicates
        if (data.meta != null) {
            List<ReplayInfo> existing = getReplayList(context);
            for (ReplayInfo ri : existing) {
                if (ri.songName.equals(data.meta.songName)
                        && ri.score == data.meta.score
                        && Math.abs(ri.timestamp - data.meta.timestamp) < 5000) {
                    return null; // duplicate
                }
            }
        }

        String uuid = UUID.randomUUID().toString().replace("-", "");
        File replayDir = new File(getReplaysRoot(context), uuid);
        if (!replayDir.mkdirs()) {
            throw new IOException(context.getString(R.string.error_create_replay_dir));
        }

        // Save replay.json
        File replayJsonFile = new File(replayDir, "replay.json");
        JSON_MAPPER.writeValue(replayJsonFile, data);

        // Copy entire chart folder (preserving external textures/shaders etc.)
        String chartRelPath = null;
        File chartParent = (chartFile != null && chartFile.exists()) ? chartFile.getParentFile() : null;
        if (chartFile != null && chartFile.exists()) {
            if (chartParent != null) {
                String chartDirName = chartParent.getName();
                if (chartDirName.isEmpty()) chartDirName = "chart";
                File destChartDir = new File(replayDir, chartDirName);
                copyDirectory(chartParent, destChartDir);
                chartRelPath = chartDirName + "/" + chartFile.getName();
            } else {
                File destChart = new File(replayDir, chartFile.getName());
                copyFile(chartFile, destChart);
                chartRelPath = chartFile.getName();
            }
        }

        // Copy music — skip if already inside the chart folder to avoid duplication
        String musicRelPath = null;
        if (musicFile != null && musicFile.exists()) {
            if (chartParent != null && isInsideDir(musicFile, chartParent)) {
                musicRelPath = chartRelPath.substring(0, chartRelPath.lastIndexOf('/') + 1) + musicFile.getName();
            } else {
                File destMusic = new File(replayDir, musicFile.getName());
                copyFile(musicFile, destMusic);
                musicRelPath = musicFile.getName();
            }
        }

        // Copy background — skip if already inside the chart folder
        String bgRelPath = null;
        if (bgFile != null && bgFile.exists()) {
            if (chartParent != null && isInsideDir(bgFile, chartParent)) {
                bgRelPath = chartRelPath.substring(0, chartRelPath.lastIndexOf('/') + 1) + bgFile.getName();
            } else {
                File destBg = new File(replayDir, bgFile.getName());
                copyFile(bgFile, destBg);
                bgRelPath = bgFile.getName();
            }
        }

        // Write info.json
        InfoJson info = new InfoJson();
        info.songName = data.meta != null ? data.meta.songName : "";
        info.difficulty = data.meta != null ? data.meta.difficulty : "";
        info.score = data.meta != null ? data.meta.score : 0;
        info.timestamp = data.meta != null ? data.meta.timestamp : System.currentTimeMillis();
        info.favorite = false;
        info.chartRelPath = chartRelPath;
        info.musicRelPath = musicRelPath;
        info.bgRelPath = bgRelPath;
        // Copy settings
        if (settings != null) {
            info.aspectRatio = settings.aspectRatio;
            info.keyScale = settings.keyScale;
            info.scrollSpeed = settings.scrollSpeed;
            info.mirrorX = settings.mirrorX;
            info.bgDim = settings.bgDim;
            info.lowRes = settings.lowRes;
            info.antialias = settings.antialias;
            info.showFps = settings.showFps;
            info.showDebug = settings.showDebug;
            info.multiHighlight = settings.multiHighlight;
            info.apfc = settings.apfc;
            info.challenge = settings.challenge;
            info.musicSpeed = settings.musicSpeed;
            info.musicVolPct = settings.musicVolPct;
            info.sfxVolPct = settings.sfxVolPct;
            info.chartOffsetMs = settings.chartOffsetMs;
            info.audioOffsetMs = settings.audioOffsetMs;
        }
        JSON_MAPPER.writeValue(new File(replayDir, "info.json"), info);

        // Register in prefs
        SharedPreferences prefs = getPrefs(context);
        String idsStr = prefs.getString(PREF_REPLAY_IDS, "");
        List<String> ids = new ArrayList<>();
        if (!idsStr.isEmpty()) {
            ids.addAll(Arrays.asList(idsStr.split(",")));
        }
        ids.add(uuid);
        prefs.edit().putString(PREF_REPLAY_IDS, String.join(",", ids)).apply();

        return uuid;
    }

    public static List<ReplayInfo> getReplayList(Context context) {
        List<ReplayInfo> list = new ArrayList<>();
        SharedPreferences prefs = getPrefs(context);
        String idsStr = prefs.getString(PREF_REPLAY_IDS, "");
        if (idsStr.isEmpty()) return list;

        List<String> ids = Arrays.asList(idsStr.split(","));
        for (String id : ids) {
            if (id.isEmpty()) continue;
            File replayDir = getReplayDir(context, id);
            if (!replayDir.isDirectory()) continue;

            File infoFile = new File(replayDir, "info.json");
            if (!infoFile.isFile()) continue;

            try {
                InfoJson info = JSON_MAPPER.readValue(infoFile, InfoJson.class);
                ReplayInfo ri = new ReplayInfo(id,
                        info.songName != null ? info.songName : "",
                        info.difficulty != null ? info.difficulty : "",
                        info.score,
                        info.timestamp,
                        info.favorite);
                list.add(ri);
            } catch (Exception ignored) {}
        }

        // Sort: favorites first, then by timestamp descending
        Collections.sort(list, (a, b) -> {
            if (a.favorite != b.favorite) return a.favorite ? -1 : 1;
            return Long.compare(b.timestamp, a.timestamp);
        });

        return list;
    }

    public static void setFavorite(Context context, String uuid, boolean favorite) {
        File infoFile = new File(getReplayDir(context, uuid), "info.json");
        if (!infoFile.isFile()) return;
        try {
            InfoJson info = JSON_MAPPER.readValue(infoFile, InfoJson.class);
            info.favorite = favorite;
            JSON_MAPPER.writeValue(infoFile, info);
        } catch (Exception ignored) {}
    }

    public static void deleteReplay(Context context, String uuid) {
        SharedPreferences prefs = getPrefs(context);
        String idsStr = prefs.getString(PREF_REPLAY_IDS, "");
        List<String> ids = new ArrayList<>();
        if (!idsStr.isEmpty()) {
            ids.addAll(Arrays.asList(idsStr.split(",")));
        }
        ids.remove(uuid);
        prefs.edit().putString(PREF_REPLAY_IDS, String.join(",", ids)).apply();

        File replayDir = getReplayDir(context, uuid);
        if (replayDir.isDirectory()) {
            deleteRecursive(replayDir);
        }
    }

    // ---- Import ----

    /**
     * Validates and imports a .cgrp file.
     * A valid .cgrp must contain: info.txt, an audio file, an image file, a json chart file, and replay.json.
     */
    public static String importReplayFromCgrp(Context context, File zipFile) throws IOException {
        // Extract to temp dir for validation
        File tempDir = new File(context.getCacheDir(), "cgrp_import_" + System.currentTimeMillis());
        if (!tempDir.mkdirs()) throw new IOException(context.getString(R.string.error_create_temp_dir));

        boolean hasReplayJson = false;
        boolean hasInfoTxt = false;
        boolean hasAudio = false;
        boolean hasImage = false;
        boolean hasJson = false;

        try (FileInputStream fis = new FileInputStream(zipFile);
             ZipInputStream zis = new ZipInputStream(fis)) {

            byte[] buf = new byte[8192];
            ZipEntry entry;
            while ((entry = zis.getNextEntry()) != null) {
                String name = entry.getName();
                if (name.contains("..") || name.startsWith("/")) continue;
                if (entry.isDirectory()) {
                    File dir = new File(tempDir, name);
                    dir.mkdirs();
                    continue;
                }

                File outFile = new File(tempDir, name);
                File parent = outFile.getParentFile();
                if (parent != null && !parent.exists()) parent.mkdirs();

                try (FileOutputStream fos = new FileOutputStream(outFile)) {
                    int n;
                    while ((n = zis.read(buf)) > 0) fos.write(buf, 0, n);
                }

                String lower = name.toLowerCase();
                if (lower.endsWith("replay.json")) hasReplayJson = true;
                else if (lower.endsWith("info.txt")) hasInfoTxt = true;
                else if (lower.endsWith(".mp3") || lower.endsWith(".ogg") || lower.endsWith(".wav")
                        || lower.endsWith(".m4a") || lower.endsWith(".aac") || lower.endsWith(".flac"))
                    hasAudio = true;
                else if (lower.endsWith(".png") || lower.endsWith(".jpg") || lower.endsWith(".jpeg")
                        || lower.endsWith(".webp"))
                    hasImage = true;
                else if (lower.endsWith(".json")) hasJson = true;

                zis.closeEntry();
            }
        }

        if (!hasReplayJson) {
            deleteRecursive(tempDir);
            throw new IOException(context.getString(R.string.error_replay_missing_json));
        }
        if (!hasInfoTxt && !hasJson) {
            deleteRecursive(tempDir);
            throw new IOException(context.getString(R.string.error_replay_missing_chart));
        }
        if (!hasAudio) {
            deleteRecursive(tempDir);
            throw new IOException(context.getString(R.string.error_replay_missing_audio));
        }
        if (!hasImage) {
            deleteRecursive(tempDir);
            throw new IOException(context.getString(R.string.error_replay_missing_image));
        }

        // Valid — move to persistent storage
        String uuid = UUID.randomUUID().toString().replace("-", "");
        File replayDir = new File(getReplaysRoot(context), uuid);
        if (!replayDir.mkdirs()) {
            deleteRecursive(tempDir);
            throw new IOException(context.getString(R.string.error_create_replay_dir));
        }

        // Find replay.json and move tempDir contents to replayDir
        File replayJsonSrc = findFile(tempDir, "replay.json");
        if (replayJsonSrc == null) {
            deleteRecursive(tempDir);
            deleteRecursive(replayDir);
            throw new IOException(context.getString(R.string.error_replay_json_not_found));
        }

        // Move all files from tempDir to replayDir
        moveDirectoryContents(tempDir, replayDir);
        deleteRecursive(tempDir);

        // Preserve settings from the original info.json if present,
        // only recomputing paths that may differ between devices.
        InfoJson info = null;
        File existingInfoFile = new File(replayDir, "info.json");
        if (existingInfoFile.isFile()) {
            try {
                info = JSON_MAPPER.readValue(existingInfoFile, InfoJson.class);
            } catch (Exception ignored) {}
        }
        if (info == null) info = new InfoJson();

        // Always update metadata from replay.json
        try {
            ReplayData rd = loadReplayJson(new File(replayDir, "replay.json"));
            if (rd.meta != null) {
                info.songName = rd.meta.songName != null ? rd.meta.songName : "";
                info.difficulty = rd.meta.difficulty != null ? rd.meta.difficulty : "";
                info.score = rd.meta.score;
                info.timestamp = rd.meta.timestamp;
            }
        } catch (Exception ignored) {}

        info.favorite = false;

        // Recompute paths (they may differ after extraction)
        File chartFile = findChartFileInDir(replayDir);
        if (chartFile != null) {
            info.chartRelPath = relativizePath(replayDir, chartFile);
        }
        File musicFile = findAudioFileInDir(replayDir);
        if (musicFile != null) {
            info.musicRelPath = relativizePath(replayDir, musicFile);
        }
        File bgFile = findImageFileInDir(replayDir);
        if (bgFile != null) {
            info.bgRelPath = relativizePath(replayDir, bgFile);
        }

        JSON_MAPPER.writeValue(existingInfoFile, info);

        // Register
        SharedPreferences prefs = getPrefs(context);
        String idsStr = prefs.getString(PREF_REPLAY_IDS, "");
        List<String> ids = new ArrayList<>();
        if (!idsStr.isEmpty()) ids.addAll(Arrays.asList(idsStr.split(",")));
        ids.add(uuid);
        prefs.edit().putString(PREF_REPLAY_IDS, String.join(",", ids)).apply();

        return uuid;
    }

    // ---- Export ----

    /**
     * Export selected replays as .cgrp files to /内部存储/Download/Chigros/
     * Returns the number of successfully exported replays.
     */
    public static int exportReplaysToCgrp(Context context, List<String> uuids) throws IOException {
        File downloadDir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS);
        File exportDir = new File(downloadDir, EXPORT_DIR);
        if (!exportDir.exists()) exportDir.mkdirs();

        int count = 0;
        for (String uuid : uuids) {
            File replayDir = getReplayDir(context, uuid);
            if (!replayDir.isDirectory()) continue;

            InfoJson info = null;
            File infoFile = new File(replayDir, "info.json");
            if (infoFile.isFile()) {
                try {
                    info = JSON_MAPPER.readValue(infoFile, InfoJson.class);
                } catch (Exception ignored) {}
            }

            String safeFileName = (info != null && info.songName != null && !info.songName.isEmpty())
                    ? info.songName.replaceAll("[\\\\/:*?\"<>|]", "_")
                    : "replay_" + uuid.substring(0, 8);
            File outFile = new File(exportDir, safeFileName + ".cgrp");

            try (FileOutputStream fos = new FileOutputStream(outFile);
                 ZipOutputStream zos = new ZipOutputStream(fos)) {

                addDirToZip(replayDir, replayDir, zos);
            }
            count++;
        }
        return count;
    }

    // ---- Internal helpers ----

    private static void copyFile(File src, File dst) throws IOException {
        try (FileInputStream fis = new FileInputStream(src);
             FileOutputStream fos = new FileOutputStream(dst)) {
            byte[] buf = new byte[8192];
            int n;
            while ((n = fis.read(buf)) > 0) fos.write(buf, 0, n);
        }
    }

    private static void copyDirectory(File srcDir, File dstDir) throws IOException {
        if (!dstDir.exists()) dstDir.mkdirs();
        File[] files = srcDir.listFiles();
        if (files == null) return;
        for (File f : files) {
            File dest = new File(dstDir, f.getName());
            if (f.isDirectory()) {
                copyDirectory(f, dest);
            } else {
                copyFile(f, dest);
            }
        }
    }

    private static void moveDirectoryContents(File srcDir, File dstDir) throws IOException {
        if (!dstDir.exists()) dstDir.mkdirs();
        File[] files = srcDir.listFiles();
        if (files == null) return;
        for (File f : files) {
            File dest = new File(dstDir, f.getName());
            if (f.isDirectory()) {
                moveDirectoryContents(f, dest);
            } else {
                copyFile(f, dest);
            }
            f.delete();
        }
        if (srcDir != dstDir) srcDir.delete();
    }

    private static void deleteRecursive(File f) {
        if (f.isDirectory()) {
            File[] children = f.listFiles();
            if (children != null) {
                for (File child : children) deleteRecursive(child);
            }
        }
        f.delete();
    }

    private static void addDirToZip(File rootDir, File currentDir, ZipOutputStream zos) throws IOException {
        File[] files = currentDir.listFiles();
        if (files == null) return;
        byte[] buf = new byte[8192];
        for (File f : files) {
            String relPath = relativizePath(rootDir, f);
            if (f.isDirectory()) {
                zos.putNextEntry(new ZipEntry(relPath + "/"));
                zos.closeEntry();
                addDirToZip(rootDir, f, zos);
            } else {
                zos.putNextEntry(new ZipEntry(relPath));
                try (FileInputStream fis = new FileInputStream(f)) {
                    int n;
                    while ((n = fis.read(buf)) > 0) zos.write(buf, 0, n);
                }
                zos.closeEntry();
            }
        }
    }

    private static boolean isInsideDir(File file, File dir) {
        try {
            String fp = file.getCanonicalPath();
            String dp = dir.getCanonicalPath();
            return fp.startsWith(dp + File.separator) || fp.startsWith(dp + "/");
        } catch (IOException e) {
            return false;
        }
    }

    private static String relativizePath(File base, File file) {
        String basePath = base.getAbsolutePath();
        String filePath = file.getAbsolutePath();
        if (filePath.startsWith(basePath)) {
            String rel = filePath.substring(basePath.length());
            while (rel.startsWith("/") || rel.startsWith("\\")) rel = rel.substring(1);
            return rel;
        }
        return file.getName();
    }

    private static File findFile(File dir, String name) {
        File[] files = dir.listFiles();
        if (files == null) return null;
        for (File f : files) {
            if (f.isDirectory()) {
                File found = findFile(f, name);
                if (found != null) return found;
            } else if (f.getName().equalsIgnoreCase(name)) {
                return f;
            }
        }
        return null;
    }

    private static File findChartFileInDir(File dir) {
        File[] files = dir.listFiles();
        if (files == null) return null;
        for (File f : files) {
            if (f.isDirectory()) {
                File found = findChartFileInDir(f);
                if (found != null) return found;
            }
            String lower = f.getName().toLowerCase();
            if (lower.endsWith(".json") && !lower.equals("info.json") && !lower.equals("replay.json")) {
                return f;
            }
        }
        return null;
    }

    private static File findAudioFileInDir(File dir) {
        File[] files = dir.listFiles();
        if (files == null) return null;
        for (File f : files) {
            if (f.isDirectory()) {
                File found = findAudioFileInDir(f);
                if (found != null) return found;
            }
            String lower = f.getName().toLowerCase();
            if (lower.endsWith(".mp3") || lower.endsWith(".ogg") || lower.endsWith(".wav")
                    || lower.endsWith(".m4a") || lower.endsWith(".aac") || lower.endsWith(".flac")) {
                return f;
            }
        }
        return null;
    }

    private static File findImageFileInDir(File dir) {
        File[] files = dir.listFiles();
        if (files == null) return null;
        for (File f : files) {
            if (f.isDirectory()) {
                File found = findImageFileInDir(f);
                if (found != null) return found;
            }
            String lower = f.getName().toLowerCase();
            if (lower.endsWith(".png") || lower.endsWith(".jpg") || lower.endsWith(".jpeg")
                    || lower.endsWith(".webp")) {
                return f;
            }
        }
        return null;
    }

    // ---- Internal info.json model ----

    static class InfoJson {
        public String songName;
        public String difficulty;
        public int score;
        public long timestamp;
        public boolean favorite;
        public String chartRelPath;
        public String musicRelPath;
        public String bgRelPath;
        // Video settings
        public float aspectRatio;
        public float keyScale = 1.0f;
        public float scrollSpeed = 1.0f;
        public boolean mirrorX;
        public float bgDim = 0.6f;
        public boolean lowRes;
        public boolean antialias;
        public boolean showFps;
        public boolean showDebug;
        public boolean multiHighlight = true;
        public boolean apfc;
        public boolean challenge;
        // Audio settings
        public float musicSpeed = 1.0f;
        public int musicVolPct = 100;
        public int sfxVolPct = 100;
        public int chartOffsetMs;
        public int audioOffsetMs;
    }
}
