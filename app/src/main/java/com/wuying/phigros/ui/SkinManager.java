package com.wuying.phigros.ui;

import android.content.Context;
import android.content.SharedPreferences;

import com.wuying.phigros.game.SkinConfig;

import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.UUID;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;

public final class SkinManager {

    private static final String PREFS_NAME = "skin_settings";
    private static final String PREF_SKIN_IDS = "skin_ids";
    private static final String PREF_SELECTED_INDEX = "selected_skin_index";
    private static final String SKINS_DIR = "skins";

    private static final ObjectMapper JSON_MAPPER = new ObjectMapper()
            .configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);

    public static class SkinEntry {
        public final String id;
        public final String name;

        SkinEntry(String id, String name) {
            this.id = id;
            this.name = name;
        }

        public boolean isBuiltin() {
            return "builtin".equals(id);
        }
    }

    private SkinManager() {}

    private static SharedPreferences getPrefs(Context context) {
        return context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
    }

    private static File getSkinsRoot(Context context) {
        return new File(context.getFilesDir(), SKINS_DIR);
    }

    public static File getSkinDir(Context context, String skinId) {
        if ("builtin".equals(skinId)) return null;
        return new File(getSkinsRoot(context), skinId);
    }

    public static List<SkinEntry> getSkinList(Context context) {
        List<SkinEntry> list = new ArrayList<>();
        list.add(new SkinEntry("builtin", "内置皮肤"));

        SharedPreferences prefs = getPrefs(context);
        String idsStr = prefs.getString(PREF_SKIN_IDS, "");
        if (idsStr.isEmpty()) return list;

        List<String> ids = Arrays.asList(idsStr.split(","));
        for (String id : ids) {
            if (id.isEmpty()) continue;
            File skinDir = getSkinDir(context, id);
            if (!skinDir.isDirectory()) continue;
            SkinConfig cfg = loadSkinConfig(skinDir);
            String name = (cfg != null && cfg.name != null && !cfg.name.isEmpty()) ? cfg.name : id;
            list.add(new SkinEntry(id, name));
        }
        return list;
    }

    public static int getSelectedSkinIndex(Context context) {
        return getPrefs(context).getInt(PREF_SELECTED_INDEX, 0);
    }

    public static void setSelectedSkinIndex(Context context, int index) {
        getPrefs(context).edit().putInt(PREF_SELECTED_INDEX, index).apply();
    }

    public static SkinEntry importSkin(Context context, File zipFile) throws IOException {
        String uuid = UUID.randomUUID().toString().replace("-", "");
        File skinDir = new File(getSkinsRoot(context), uuid);
        if (!skinDir.mkdirs()) {
            throw new IOException("无法创建皮肤目录");
        }

        try (FileInputStream fis = new FileInputStream(zipFile);
             ZipInputStream zis = new ZipInputStream(fis)) {

            byte[] buf = new byte[8192];
            ZipEntry entry;
            while ((entry = zis.getNextEntry()) != null) {
                String name = entry.getName();
                if (name.contains("..") || name.startsWith("/")) continue;
                if (entry.isDirectory()) continue;

                int lastSep = name.lastIndexOf('/');
                String leaf = lastSep >= 0 ? name.substring(lastSep + 1) : name;
                if (leaf.isEmpty()) continue;

                File outFile = new File(skinDir, leaf);
                java.io.FileOutputStream fos = new java.io.FileOutputStream(outFile);
                int n;
                while ((n = zis.read(buf)) > 0) {
                    fos.write(buf, 0, n);
                }
                fos.close();
                zis.closeEntry();
            }
        }

        SkinConfig cfg = loadSkinConfig(skinDir);
        String displayName = (cfg != null && cfg.name != null && !cfg.name.isEmpty()) ? cfg.name : uuid;

        SharedPreferences prefs = getPrefs(context);
        String idsStr = prefs.getString(PREF_SKIN_IDS, "");
        List<String> ids = new ArrayList<>();
        if (!idsStr.isEmpty()) {
            ids.addAll(Arrays.asList(idsStr.split(",")));
        }
        ids.add(uuid);
        prefs.edit().putString(PREF_SKIN_IDS, String.join(",", ids)).apply();

        return new SkinEntry(uuid, displayName);
    }

    public static void deleteSkin(Context context, String skinId) {
        if ("builtin".equals(skinId)) return;

        SharedPreferences prefs = getPrefs(context);
        String idsStr = prefs.getString(PREF_SKIN_IDS, "");
        List<String> ids = new ArrayList<>();
        if (!idsStr.isEmpty()) {
            ids.addAll(Arrays.asList(idsStr.split(",")));
        }
        ids.remove(skinId);
        prefs.edit().putString(PREF_SKIN_IDS, String.join(",", ids)).apply();

        int selected = getSelectedSkinIndex(context);
        List<SkinEntry> list = getSkinList(context);
        if (selected >= list.size()) {
            setSelectedSkinIndex(context, 0);
        }

        File skinDir = getSkinDir(context, skinId);
        if (skinDir != null && skinDir.isDirectory()) {
            deleteRecursive(skinDir);
        }
    }

    public static SkinConfig loadSkinConfig(File skinDir) {
        // Try info.yml first (reference format), then skin.json
        File ymlFile = new File(skinDir, "info.yml");
        if (ymlFile.isFile()) {
            SkinConfig cfg = parseYmlConfig(ymlFile);
            if (cfg != null) return cfg;
        }
        File jsonFile = new File(skinDir, "skin.json");
        if (jsonFile.isFile()) {
            try (InputStream is = new FileInputStream(jsonFile)) {
                byte[] data = new byte[(int) jsonFile.length()];
                int read = is.read(data);
                if (read > 0) return JSON_MAPPER.readValue(data, 0, read, SkinConfig.class);
            } catch (Exception e) {}
        }
        return null;
    }

    public static SkinConfig getSkinConfig(Context context, String skinId) {
        if ("builtin".equals(skinId)) return null;
        File skinDir = getSkinDir(context, skinId);
        if (skinDir == null || !skinDir.isDirectory()) return null;
        return loadSkinConfig(skinDir);
    }

    private static SkinConfig parseYmlConfig(File file) {
        try {
            byte[] data = new byte[(int) file.length()];
            FileInputStream fis = new FileInputStream(file);
            fis.read(data);
            fis.close();
            String content = new String(data, "UTF-8");
            return parseYmlContent(content);
        } catch (Exception e) {
            return null;
        }
    }

    private static SkinConfig parseYmlContent(String content) {
        SkinConfig cfg = new SkinConfig();
        String[] lines = content.split("\n");
        for (String raw : lines) {
            String line = raw.trim();
            if (line.isEmpty() || line.startsWith("#")) continue;
            int ci = line.indexOf(':');
            if (ci < 0) continue;
            String key = line.substring(0, ci).trim();
            String val = line.substring(ci + 1).trim();
            int commentIdx = val.indexOf('#');
            if (commentIdx >= 0) val = val.substring(0, commentIdx).trim();
            val = stripYmlQuotes(val);
            try {
                switch (key) {
                    case "name": cfg.name = val; break;
                    case "author": cfg.author = val; break;
                    case "description": cfg.description = val; break;
                    case "hitFx": case "hit_fx": cfg.hitFx = parseIntArray(val); break;
                    case "holdAtlas": case "hold_atlas": cfg.holdAtlas = parseIntArray(val); break;
                    case "holdAtlasMH": case "hold_atlas_mh": cfg.holdAtlasMH = parseIntArray(val); break;
                    case "hitFxScale": case "hit_fx_scale": cfg.hitFxScale = Float.parseFloat(val); break;
                    case "hitFxDuration": case "hit_fx_duration": cfg.hitFxDuration = Float.parseFloat(val); break;
                    case "hitFxRotate": case "hit_fx_rotate": cfg.hitFxRotate = parseBool(val); break;
                    case "hideParticles": case "hide_particles": cfg.hideParticles = parseBool(val); break;
                    case "hitFxTinted": case "hit_fx_tinted": cfg.hitFxTinted = parseBool(val); break;
                    case "holdKeepHead": case "hold_keep_head": cfg.holdKeepHead = parseBool(val); break;
                    case "holdRepeat": case "hold_repeat": cfg.holdRepeat = parseBool(val); break;
                    case "holdCompact": case "hold_compact": cfg.holdCompact = parseBool(val); break;
                    case "colorPerfect": case "color_perfect": cfg.colorPerfect = val; break;
                    case "colorGood": case "color_good": cfg.colorGood = val; break;
                }
            } catch (Exception ignored) {}
        }
        return cfg;
    }

    private static String stripYmlQuotes(String s) {
        if (s.length() >= 2 && ((s.startsWith("\"") && s.endsWith("\"")) || (s.startsWith("'") && s.endsWith("'")))) {
            return s.substring(1, s.length() - 1);
        }
        return s;
    }

    private static int[] parseIntArray(String s) {
        String inner = s.replace("[", "").replace("]", "").trim();
        String[] parts = inner.split(",");
        int[] arr = new int[parts.length];
        for (int i = 0; i < parts.length; i++) {
            arr[i] = Integer.parseInt(parts[i].trim());
        }
        return arr;
    }

    private static boolean parseBool(String s) {
        return "true".equalsIgnoreCase(s) || "yes".equalsIgnoreCase(s);
    }

    private static void deleteRecursive(File f) {
        if (f.isDirectory()) {
            File[] children = f.listFiles();
            if (children != null) {
                for (File child : children) {
                    deleteRecursive(child);
                }
            }
        }
        f.delete();
    }
}
