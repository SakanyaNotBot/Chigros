package com.wuying.phigros.util;

import android.text.TextUtils;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

public final class InfoFileUtils {
    private InfoFileUtils() {}

    private static final ObjectMapper MAPPER = new ObjectMapper()
            .configure(JsonParser.Feature.ALLOW_UNQUOTED_FIELD_NAMES, true);

    public static class Info {
        public String name = "";
        public String chart = "";
        public String music = "";
        public String illustration = "";
        public String level = "";
        public String difficulty = "";
        public Boolean useAttachUiFix = null;
    }

    @NonNull
    public static Info readInfoFile(@Nullable File file) {
        Info info = new Info();
        if (file == null || !file.exists() || !file.isFile()) return info;
        String lowerName = file.getName().toLowerCase(Locale.ROOT);
        try {
            if (lowerName.endsWith(".json")) {
                return readJson(file);
            } else if (lowerName.endsWith(".yml") || lowerName.endsWith(".yaml")) {
                return readYamlLike(file);
            } else if (lowerName.endsWith(".txt")) {
                return readKeyValueTxt(file);
            } else if (lowerName.endsWith(".csv")) {
                return readCsv(file);
            } else {
                return readYamlLike(file);
            }
        } catch (Exception ignored) {
            return info;
        }
    }

    @NonNull
    public static Info readInfoFromChartFile(@Nullable File chartFile) {
        Info info = new Info();
        if (chartFile == null || !chartFile.exists() || !chartFile.isFile()) return info;
        String name = chartFile.getName().toLowerCase(Locale.ROOT);
        if (!name.endsWith(".json")) return info;

        try {
            String json = readAll(chartFile);
            JsonNode rootEl = MAPPER.readTree(json);
            if (!rootEl.isObject()) return info;

            JsonNode meta = getObj(rootEl, "META", "meta", "Meta");
            if (meta != null) {
                info.name = getStr(meta, "name", "Name", "title", "songName");
                info.level = getStr(meta, "level", "Level");
                info.difficulty = getStr(meta, "difficulty", "Difficulty", "diff", "Diff");
                info.illustration = getStr(meta, "background", "Background", "bg", "Bg", "illustration", "Illustration", "image", "Image", "picture", "Picture");
                info.music = getStr(meta, "music", "Music", "song", "Song", "audio", "Audio");
            }
        } catch (Exception ignored) {
        }
        return info;
    }

    @NonNull
    public static String buildDifficultyLabel(@Nullable Info info, @Nullable File chartFile) {
        if (info == null) return "";
        if (!TextUtils.isEmpty(info.level)) return info.level;

        String diff = info.difficulty;
        if (TextUtils.isEmpty(diff)) return "SP Lv.?";

        String diffTrim = diff.trim();
        if (diffTrim.matches(".*[A-Za-z].*") && diffTrim.toLowerCase(Locale.ROOT).contains("lv")) {
            return diffTrim;
        }

        String prefix = inferDifficultyPrefix(chartFile);
        if (!TextUtils.isEmpty(prefix)) {
            return prefix + " Lv." + diffTrim;
        }
        return "Lv." + diffTrim;
    }

    @NonNull
    public static String inferDifficultyPrefix(@Nullable File chartFile) {
        if (chartFile == null) return "";
        String base = chartFile.getName();
        int dot = base.lastIndexOf('.');
        if (dot > 0) base = base.substring(0, dot);
        String up = base.toUpperCase(Locale.ROOT);

        if (up.matches("(^|.*[^A-Z0-9])EZ([^A-Z0-9].*|$)")) return "EZ";
        if (up.matches("(^|.*[^A-Z0-9])HD([^A-Z0-9].*|$)")) return "HD";
        if (up.matches("(^|.*[^A-Z0-9])IN([^A-Z0-9].*|$)")) return "IN";
        if (up.matches("(^|.*[^A-Z0-9])AT([^A-Z0-9].*|$)")) return "AT";

        if (up.equals("0")) return "EZ";
        if (up.equals("1")) return "HD";
        if (up.equals("2")) return "IN";
        if (up.equals("3")) return "AT";

        return "";
    }

    @NonNull
    private static Info readJson(@NonNull File file) throws IOException {
        Info info = new Info();
        String json = readAll(file);
        JsonNode root = MAPPER.readTree(json);
        if (!root.isObject()) return info;

        info.name = getStr(root, "name", "Name", "songName", "title");
        info.chart = getStr(root, "chart", "Chart", "chartPath");
        info.music = getStr(root, "music", "Music", "song", "Song", "audio");
        info.illustration = getStr(root,
                "illustration", "Illustration",
                "image", "Image",
                "picture", "Picture",
                "bg", "background");
        info.level = getStr(root, "level", "Level");
        info.difficulty = getStr(root, "difficulty", "Difficulty", "diff", "Diff");
        info.useAttachUiFix = getBool(root, "useAttachUiFix", "use_attach_ui_fix");

        return info;
    }

    @NonNull
    private static Info readYamlLike(@NonNull File file) throws IOException {
        Map<String, String> kv = new HashMap<>();
        try (BufferedReader br = new BufferedReader(new InputStreamReader(new FileInputStream(file), StandardCharsets.UTF_8))) {
            String line;
            while ((line = br.readLine()) != null) {
                String s = line.trim();
                if (s.isEmpty()) continue;
                if (s.startsWith("#")) continue;
                int idx = s.indexOf(':');
                if (idx <= 0) continue;
                String key = s.substring(0, idx).trim();
                String val = s.substring(idx + 1).trim();
                val = stripQuotes(val);
                kv.put(key, val);
            }
        }
        return fromKv(kv);
    }

    @NonNull
    private static Info readKeyValueTxt(@NonNull File file) throws IOException {
        Map<String, String> kv = new HashMap<>();
        try (BufferedReader br = new BufferedReader(new InputStreamReader(new FileInputStream(file), StandardCharsets.UTF_8))) {
            String line;
            boolean first = true;
            while ((line = br.readLine()) != null) {
                String s = line.trim();
                if (first) {
                    first = false;
                    if (s.startsWith("#")) {
                        continue;
                    }
                }
                if (s.isEmpty()) continue;
                int idx = s.indexOf(':');
                if (idx <= 0) continue;
                String key = s.substring(0, idx).trim();
                String val = s.substring(idx + 1).trim();
                val = stripQuotes(val);
                kv.put(key, val);
            }
        }
        return fromKv(kv);
    }

    @NonNull
    private static Info readCsv(@NonNull File file) throws IOException {
        Info info = new Info();
        try (BufferedReader br = new BufferedReader(new InputStreamReader(new FileInputStream(file), StandardCharsets.UTF_8))) {
            String headerLine = null;
            String dataLine = null;
            String line;
            while ((line = br.readLine()) != null) {
                if (TextUtils.isEmpty(line.trim())) continue;
                if (headerLine == null) {
                    headerLine = line;
                } else {
                    dataLine = line;
                    break;
                }
            }
            if (headerLine == null || dataLine == null) return info;
            List<String> headers = parseCsvLine(headerLine);
            List<String> values = parseCsvLine(dataLine);
            Map<String, String> kv = new HashMap<>();
            int n = Math.min(headers.size(), values.size());
            for (int i = 0; i < n; i++) {
                kv.put(headers.get(i), values.get(i));
            }
            return fromKv(kv);
        }
    }

    @NonNull
    private static Info fromKv(@NonNull Map<String, String> kv) {
        Info info = new Info();
        Map<String, String> lower = new HashMap<>();
        for (Map.Entry<String, String> e : kv.entrySet()) {
            if (e.getKey() == null) continue;
            lower.put(e.getKey().trim().toLowerCase(Locale.ROOT), e.getValue());
        }

        info.name = firstNonEmpty(lower.get("name"), kv.get("Name"));
        info.music = firstNonEmpty(
                lower.get("music"), lower.get("song"),
                kv.get("Music"), kv.get("Song"));
        info.chart = firstNonEmpty(lower.get("chart"), kv.get("Chart"));
        info.illustration = firstNonEmpty(
                lower.get("illustration"), lower.get("image"), lower.get("picture"),
                kv.get("Illustration"), kv.get("Image"), kv.get("Picture"));
        info.level = firstNonEmpty(lower.get("level"), kv.get("Level"));
        info.difficulty = firstNonEmpty(
                lower.get("difficulty"), lower.get("diff"),
                kv.get("Difficulty"), kv.get("Diff"));

        String auiFix = firstNonEmpty(lower.get("useattachuifix"), lower.get("use_attach_ui_fix"));
        if (auiFix != null) {
            auiFix = auiFix.trim().toLowerCase(Locale.ROOT);
            if ("true".equals(auiFix) || "1".equals(auiFix)) info.useAttachUiFix = true;
            else if ("false".equals(auiFix) || "0".equals(auiFix)) info.useAttachUiFix = false;
        }

        return info;
    }

    @Nullable
    private static JsonNode getObj(@NonNull JsonNode root, String... keys) {
        for (String k : keys) {
            if (k == null) continue;
            JsonNode e = root.get(k);
            if (e != null && e.isObject()) return e;
        }
        return null;
    }

    @NonNull
    private static String getStr(@NonNull JsonNode root, String... keys) {
        for (String k : keys) {
            if (k == null) continue;
            JsonNode e = root.get(k);
            if (e == null || e.isNull()) continue;
            try {
                if (e.isValueNode()) {
                    String s = e.asText();
                    if (!TextUtils.isEmpty(s)) return s;
                }
            } catch (Exception ignored) {
            }
        }
        return "";
    }

    @Nullable
    private static Boolean getBool(@NonNull JsonNode root, String... keys) {
        for (String k : keys) {
            if (k == null) continue;
            JsonNode e = root.get(k);
            if (e == null || e.isNull()) continue;
            try {
                if (e.isBoolean()) return e.asBoolean();
                if (e.isNumber()) return e.asInt() != 0;
                if (e.isTextual()) {
                    String s = e.asText().trim().toLowerCase(Locale.ROOT);
                    if ("true".equals(s) || "1".equals(s)) return true;
                    if ("false".equals(s) || "0".equals(s)) return false;
                }
            } catch (Exception ignored) {
            }
        }
        return null;
    }

    @NonNull
    private static String firstNonEmpty(String... vals) {
        if (vals == null) return "";
        for (String v : vals) {
            if (!TextUtils.isEmpty(v)) return v;
        }
        return "";
    }

    @NonNull
    private static String stripQuotes(@NonNull String s) {
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
    private static List<String> parseCsvLine(@NonNull String line) {
        List<String> out = new ArrayList<>();
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
                out.add(stripQuotes(cur.toString().trim()));
                cur.setLength(0);
            } else {
                cur.append(c);
            }
        }
        out.add(stripQuotes(cur.toString().trim()));
        return out;
    }

    @NonNull
    private static String readAll(@NonNull File file) throws IOException {
        StringBuilder sb = new StringBuilder();
        try (BufferedReader br = new BufferedReader(new InputStreamReader(new FileInputStream(file), StandardCharsets.UTF_8))) {
            char[] buf = new char[4096];
            int n;
            while ((n = br.read(buf)) >= 0) {
                sb.append(buf, 0, n);
            }
        }
        return sb.toString();
    }
}
