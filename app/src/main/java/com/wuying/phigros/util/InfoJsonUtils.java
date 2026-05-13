package com.wuying.phigros.util;

import androidx.annotation.Nullable;

import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.File;
import java.io.FileInputStream;
import java.nio.charset.StandardCharsets;

public final class InfoJsonUtils {

    private InfoJsonUtils() {}

    private static final ObjectMapper MAPPER = new ObjectMapper()
            .configure(JsonParser.Feature.ALLOW_UNQUOTED_FIELD_NAMES, true);

    public static final class Info {
        public String name;
        public String difficulty;
    }

    @Nullable
    public static Info tryReadInfoJson(File file) {
        try {
            byte[] data;
            try (FileInputStream fis = new FileInputStream(file)) {
                data = new byte[(int) file.length()];
                int read = fis.read(data);
                if (read <= 0) return null;
            }
            String json = new String(data, StandardCharsets.UTF_8);
            JsonNode obj = MAPPER.readTree(json);
            if (obj == null || !obj.isObject()) return null;

            Info info = new Info();
            info.name = firstString(obj,
                    "name", "songName", "title",
                    "Name", "SongName", "Title");
            info.difficulty = firstString(obj,
                    "difficulty", "level", "diff",
                    "Difficulty", "Level", "Diff");
            return info;
        } catch (Exception ignored) {
            return null;
        }
    }

    @Nullable
    private static String firstString(JsonNode obj, String... keys) {
        for (String k : keys) {
            JsonNode e = obj.get(k);
            if (e != null && !e.isNull()) {
                String s = null;
                try {
                    s = e.asText();
                } catch (Exception ignored) {
                }
                if (s != null) {
                    s = s.trim();
                    if (!s.isEmpty()) return s;
                }
            }
        }
        return null;
    }
}
