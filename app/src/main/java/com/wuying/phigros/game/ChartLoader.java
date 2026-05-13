package com.wuying.phigros.game;

import androidx.annotation.NonNull;

import com.fasterxml.jackson.annotation.JsonAutoDetect;
import com.fasterxml.jackson.annotation.PropertyAccessor;
import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.core.JsonToken;
import com.fasterxml.jackson.databind.DeserializationContext;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonDeserializer;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.module.SimpleModule;
import com.fasterxml.jackson.databind.node.ArrayNode;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.PushbackReader;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * Chart file loader with format detection and post-parse best-effort asset loading.
 *
 * <p>Detects Official / RPE / PhiEdit format from the first 64KiB of the file, then delegates
 * to the appropriate parser. After parsing, applies line.csv, extra.json, and info.yml.
 */
public final class ChartLoader {

    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper()
            .configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false)
            .setVisibility(PropertyAccessor.FIELD, JsonAutoDetect.Visibility.ANY)
            .setVisibility(PropertyAccessor.GETTER, JsonAutoDetect.Visibility.NONE)
            .setVisibility(PropertyAccessor.SETTER, JsonAutoDetect.Visibility.NONE)
            .setVisibility(PropertyAccessor.CREATOR, JsonAutoDetect.Visibility.NONE);
    static {
        SimpleModule module = new SimpleModule();
        module.addDeserializer(Boolean.class, new LenientBooleanDeserializer());
        module.addDeserializer(boolean.class, new LenientBooleanDeserializer());
        OBJECT_MAPPER.registerModule(module);
    }

    private ChartLoader() {}

    /**
     * Lenient boolean deserializer accepting true/false, 0/1, and "true"/"false"/"1" strings.
     */
    private static class LenientBooleanDeserializer extends JsonDeserializer<Boolean> {
        @Override
        public Boolean deserialize(JsonParser p, DeserializationContext ctxt) throws IOException {
            JsonToken token = p.currentToken();
            switch (token) {
                case VALUE_TRUE:
                    return Boolean.TRUE;
                case VALUE_FALSE:
                    return Boolean.FALSE;
                case VALUE_NUMBER_INT:
                    return p.getIntValue() != 0;
                case VALUE_STRING:
                    String s = p.getText();
                    return Boolean.parseBoolean(s) || "1".equals(s);
                case VALUE_NULL:
                    return null;
                default:
                    p.skipChildren();
                    return Boolean.FALSE;
            }
        }
    }

    private enum ChartFormat {
        OFFICIAL,
        RPE,
        PHIEDIT
    }

    @NonNull
    public static Chart loadFromFile(@NonNull String path) throws IOException {
        File f = new File(path);
        if (!f.exists()) {
            throw new IOException("chart file not found: " + path);
        }

        ChartFormat fmt = detectFormatFast(f);

        System.out.println("ChartLoader: detected format " + fmt + " for " + f.getName());

        Chart chart;
        try {
            switch (fmt) {
                case OFFICIAL: {
                    chart = OfficialChartParser.parse(f);
                    break;
                }
                case RPE: {
                    chart = RpeChartParser.parseFile(f);
                    break;
                }
                case PHIEDIT:
                default: {
                    if (looksLikeJsonObject(f)) {
                        chart = parseChartJson(f);
                    } else {
                        chart = PecChartParser.parse(readFileToString(f));
                    }
                    break;
                }
            }
        } catch (OutOfMemoryError oom) {
            throw new IOException("OutOfMemory while parsing chart: " + oom.getMessage(), oom);
        } catch (Exception e) {
            e.printStackTrace();
            throw new IOException("failed to parse chart: " + e.getMessage(), e);
        }

        if (chart == null) {
            throw new IOException("failed to parse chart");
        }

        if (fmt == ChartFormat.OFFICIAL || fmt == ChartFormat.PHIEDIT) {
            tryApplyLineCsvTextures(chart, f);
        }

        tryLoadPrprExtra(chart, f);
        tryApplyAttachUiFixFlag(chart, f);

        chart.initRuntime();
        return chart;
    }

    private static void tryApplyLineCsvTextures(@NonNull Chart chart, @NonNull File chartFile) {
        if (chart.judgeLineList == null || chart.judgeLineList.isEmpty()) return;

        File csv = findUpwards(chartFile.getParentFile(), 6, "line.csv", "Line.csv");
        if (csv == null || !csv.exists()) return;

        try {
            LineCsvTable table = LineCsvTable.parse(csv);
            if (table.rows.isEmpty()) return;

            final String chartName = chartFile.getName();
            final String chartNameNoExt = stripExt(chartName);

            HashSet<String> chartNamesInCsv = new HashSet<>();
            ArrayList<LineCsvRow> candidates = new ArrayList<>();

            for (LineCsvRow r : table.rows) {
                if (r.chart != null && !r.chart.isEmpty()) chartNamesInCsv.add(r.chart);
                if (r.chart == null || r.chart.isEmpty()) {
                    candidates.add(r);
                    continue;
                }
                if (equalsIgnoreCase(r.chart, chartName) || equalsIgnoreCase(stripExt(r.chart), chartNameNoExt)) {
                    candidates.add(r);
                }
            }

            if (candidates.isEmpty() && chartNamesInCsv.size() == 1) {
                candidates.addAll(table.rows);
            }
            if (candidates.isEmpty()) return;

            File csvDir = csv.getParentFile();
            File chartDir = chartFile.getParentFile();

            for (LineCsvRow r : candidates) {
                int lineId = r.lineId;
                if (lineId < 0 || lineId >= chart.judgeLineList.size()) continue;
                JudgeLine line = chart.judgeLineList.get(lineId);
                if (line == null) continue;

                String imageRel = r.image;
                if (imageRel != null && !imageRel.isEmpty()) {
                    File tex = resolveResource(csvDir, chartDir, imageRel);
                    if (tex != null) {
                        line.texture = tex.getAbsolutePath();
                        line.useOfficialScale = true;
                    }
                }

                float sx = (Float.isFinite(r.horz) && r.horz != 0f) ? r.horz : 1f;
                float sy = (Float.isFinite(r.vert) && r.vert != 0f) ? r.vert : 1f;

                MoveEvent me = new MoveEvent();
                me.startTime = -1e9;
                me.endTime = 1e9;
                me.start = sx;
                me.end = sx;
                me.start2 = sy;
                me.end2 = sy;
                line.judgeLineScaleEvents = new ArrayList<>();
                line.judgeLineScaleEvents.add(me);
            }
        } catch (Throwable ignored) {
        }
    }

    private static String stripExt(String name) {
        int idx = name.lastIndexOf('.');
        return idx >= 0 ? name.substring(0, idx) : name;
    }

    private static boolean equalsIgnoreCase(String a, String b) {
        return a != null && b != null && a.equalsIgnoreCase(b);
    }

    private static File findUpwards(File startDir, int maxDepth, String... names) {
        File dir = startDir;
        for (int depth = 0; dir != null && depth <= maxDepth; depth++) {
            for (String n : names) {
                File f = new File(dir, n);
                if (f.exists()) return f;
            }
            dir = dir.getParentFile();
        }
        return null;
    }

    private static File resolveResource(File primaryBase, File secondaryBase, String relPath) {
        if (relPath == null || relPath.isEmpty()) return null;
        File f0 = new File(relPath);
        if (f0.isAbsolute() && f0.exists()) return f0;

        File r = resolveUp(primaryBase, relPath, 4);
        if (r != null) return r;
        return resolveUp(secondaryBase, relPath, 4);
    }

    private static File resolveUp(File baseDir, String relPath, int maxDepth) {
        File dir = baseDir;
        for (int depth = 0; dir != null && depth <= maxDepth; depth++) {
            File f = new File(dir, relPath);
            if (f.exists()) return f;
            dir = dir.getParentFile();
        }
        return null;
    }

    private static final class LineCsvRow {
        String chart;
        int lineId;
        String image;
        float horz;
        float vert;
    }

    private static final class LineCsvTable {
        final ArrayList<LineCsvRow> rows = new ArrayList<>();

        static LineCsvTable parse(File csvFile) throws IOException {
            LineCsvTable table = new LineCsvTable();
            try (BufferedReader br = new BufferedReader(new InputStreamReader(new FileInputStream(csvFile), StandardCharsets.UTF_8))) {
                String headerLine = br.readLine();
                if (headerLine == null) return table;
                if (headerLine.startsWith("\uFEFF")) headerLine = headerLine.substring(1);
                List<String> header = parseCsvLine(headerLine);
                Map<String, Integer> col = new HashMap<>();
                for (int i = 0; i < header.size(); i++) {
                    col.put(header.get(i).trim(), i);
                }

                String line;
                while ((line = br.readLine()) != null) {
                    if (line.trim().isEmpty()) continue;
                    List<String> cells = parseCsvLine(line);
                    LineCsvRow row = new LineCsvRow();
                    row.chart = getCell(cells, col, "Chart");
                    row.image = getCell(cells, col, "Image");
                    row.lineId = parseIntSafe(getCell(cells, col, "LineId"), -1);
                    row.horz = parseFloatSafe(getCell(cells, col, "Horz"), 1f);
                    row.vert = parseFloatSafe(getCell(cells, col, "Vert"), 1f);
                    table.rows.add(row);
                }
            }
            return table;
        }
    }

    private static String getCell(List<String> cells, Map<String, Integer> col, String key) {
        Integer idx = col.get(key);
        if (idx == null || idx < 0 || idx >= cells.size()) return null;
        String v = cells.get(idx);
        return v != null ? v.trim() : null;
    }

    private static int parseIntSafe(String s, int def) {
        try {
            return Integer.parseInt(s.trim());
        } catch (Throwable ignored) {
            return def;
        }
    }

    private static float parseFloatSafe(String s, float def) {
        try {
            return Float.parseFloat(s.trim());
        } catch (Throwable ignored) {
            return def;
        }
    }

    private static List<String> parseCsvLine(String line) {
        ArrayList<String> out = new ArrayList<>();
        if (line == null) return out;
        StringBuilder sb = new StringBuilder();
        boolean inQuotes = false;
        for (int i = 0; i < line.length(); i++) {
            char c = line.charAt(i);
            if (inQuotes) {
                if (c == '"') {
                    if (i + 1 < line.length() && line.charAt(i + 1) == '"') {
                        sb.append('"');
                        i++;
                    } else {
                        inQuotes = false;
                    }
                } else {
                    sb.append(c);
                }
            } else {
                if (c == '"') {
                    inQuotes = true;
                } else if (c == ',') {
                    out.add(sb.toString());
                    sb.setLength(0);
                } else {
                    sb.append(c);
                }
            }
        }
        out.add(sb.toString());
        return out;
    }

    private static final class PrprBpmSeg {
        double startBeat;
        double bpm;
        double startTime;
        double beatTime;
    }

    private static void tryLoadPrprExtra(@NonNull Chart chart, @NonNull File chartFile) {
        try {
            File extra = findUpwards(chartFile.getParentFile(), 6, "extra.json", "Extra.json");
            if (extra == null || !extra.exists()) return;

            chart.packDir = extra.getParentFile();

            String extraText = readFileToString(extra);
            JsonNode rootEl = OBJECT_MAPPER.readTree(extraText);
            if (!rootEl.isObject()) return;

            List<PrprBpmSeg> bpmSegsAsc = new ArrayList<>();
            ArrayNode bpmArr = rootEl.has("bpm") ? (ArrayNode) rootEl.get("bpm") : null;
            if (bpmArr != null) {
                for (JsonNode e : bpmArr) {
                    if (!e.isObject()) continue;
                    double bpm = getAsDouble(e.get("bpm"), 120.0);
                    ArrayNode timeArr = null;
                    if (e.has("time") && e.get("time").isArray()) {
                        timeArr = (ArrayNode) e.get("time");
                    } else if (e.has("startTime") && e.get("startTime").isArray()) {
                        timeArr = (ArrayNode) e.get("startTime");
                    }
                    double beat = parseBeatTriple(timeArr);
                    PrprBpmSeg seg = new PrprBpmSeg();
                    seg.startBeat = beat;
                    seg.bpm = bpm;
                    bpmSegsAsc.add(seg);
                }
            }

            bpmSegsAsc.sort((a, b) -> Double.compare(a.startBeat, b.startBeat));

            double lastBeat = 0.0;
            double lastTime = 0.0;
            double lastBeatTime = 0.5;
            for (PrprBpmSeg seg : bpmSegsAsc) {
                double deltaBeat = seg.startBeat - lastBeat;
                if (deltaBeat < 0) deltaBeat = 0;
                seg.startTime = lastTime + lastBeatTime * deltaBeat;
                seg.beatTime = 60.0 / (seg.bpm == 0.0 ? 120.0 : seg.bpm);
                lastBeat = seg.startBeat;
                lastTime = seg.startTime;
                lastBeatTime = seg.beatTime;
            }

            List<PrprBpmSeg> bpmSegsDesc = new ArrayList<>(bpmSegsAsc);
            bpmSegsDesc.sort((a, b) -> Double.compare(b.startBeat, a.startBeat));

            List<PrprEffect> effects = new ArrayList<>();
            ArrayNode effArr = rootEl.has("effects") ? (ArrayNode) rootEl.get("effects") : null;
            if (effArr != null) {
                for (JsonNode e : effArr) {
                    if (!e.isObject()) continue;

                    ArrayNode stArr = getAsJsonArray(e, "start", "startTime");
                    ArrayNode etArr = getAsJsonArray(e, "end", "endTime");
                    double stBeat = parseBeatTriple(stArr);
                    double etBeat = parseBeatTriple(etArr);
                    double stSec = beatToSec(bpmSegsDesc, stBeat);
                    double etSec = beatToSec(bpmSegsDesc, etBeat);

                    String shader = getAsString(e.get("shader"), null);
                    if (shader == null || shader.trim().isEmpty()) continue;

                    boolean global = false;
                    if (e.has("global")) global = getAsBoolean(e.get("global"), false);
                    else if (e.has("isGlobal")) global = getAsBoolean(e.get("isGlobal"), false);

                    Map<String, PrprEffect.PrprVar> vars = new HashMap<>();
                    if (e.has("vars") && e.get("vars").isObject()) {
                        JsonNode varsObj = e.get("vars");
                        Iterator<Map.Entry<String, JsonNode>> fields = varsObj.fields();
                        while (fields.hasNext()) {
                            Map.Entry<String, JsonNode> ve = fields.next();
                            String name = ve.getKey();
                            JsonNode val = ve.getValue();
                            if (val == null || val.isNull()) continue;
                            if (val.isValueNode() && !val.isNull() && !val.isArray()) {
                                vars.put(name, new PrprEffect.ConstFloat((float) getAsDouble(val, 0.0)));
                            } else if (val.isArray()) {
                                ArrayNode arr = (ArrayNode) val;
                                if (isNumberArray(arr)) {
                                    float[] vec = new float[arr.size()];
                                    for (int i = 0; i < arr.size(); i++) vec[i] = (float) getAsDouble(arr.get(i), 0.0);
                                    if (vec.length == 1) {
                                        vars.put(name, new PrprEffect.ConstFloat(vec[0]));
                                    } else {
                                        vars.put(name, new PrprEffect.ConstVec(vec));
                                    }
                                } else {
                                    ArrayList<LineEvent> events = new ArrayList<>();
                                    for (JsonNode evEl : arr) {
                                        if (!evEl.isObject()) continue;
                                        ArrayNode evStArr = getAsJsonArray(evEl, "startTime", "start");
                                        ArrayNode evEtArr = getAsJsonArray(evEl, "endTime", "end");
                                        if (evStArr == null || evEtArr == null) continue;
                                        double evStBeat = parseBeatTriple(evStArr);
                                        double evEtBeat = parseBeatTriple(evEtArr);

                                        LineEvent le = new LineEvent();
                                        le.startTime = beatToSec(bpmSegsDesc, evStBeat);
                                        le.endTime = beatToSec(bpmSegsDesc, evEtBeat);
                                        le.start = getAsDouble(evEl.get("start"), 0.0);
                                        le.end = getAsDouble(evEl.get("end"), le.start);

                                        int et = (int) getAsDouble(evEl.get("easingType"), 1.0);
                                        le.easingType = Math.max(0, et - 1);
                                        le.easingLeft = (float) getAsDouble(evEl.get("easingLeft"), 0.0);
                                        le.easingRight = (float) getAsDouble(evEl.get("easingRight"), 1.0);

                                        int bez = (int) getAsDouble(evEl.get("bezier"), 0.0);
                                        le.bezier = (bez == 1);
                                        if (le.bezier && evEl.has("bezierPoints") && evEl.get("bezierPoints").isArray()) {
                                            ArrayNode bp = (ArrayNode) evEl.get("bezierPoints");
                                            if (bp.size() >= 4) {
                                                le.bezierPoints = new float[]{
                                                    (float) getAsDouble(bp.get(0), 0.0),
                                                    (float) getAsDouble(bp.get(1), 0.0),
                                                    (float) getAsDouble(bp.get(2), 1.0),
                                                    (float) getAsDouble(bp.get(3), 1.0)
                                                };
                                            }
                                        }
                                        events.add(le);
                                    }
                                    events.sort(Comparator.comparingDouble(a -> a.startTime));
                                    vars.put(name, new PrprEffect.FloatEvents(events));
                                }
                            }
                        }
                    }

                    effects.add(new PrprEffect(stSec, etSec, global, shader, vars));
                }
            }

            if (!effects.isEmpty()) {
                chart.prprEffects = effects;

                Map<String, String> shaderSources = new HashMap<>();
                for (PrprEffect fx : effects) {
                    String shader = fx.shader;
                    String rel;
                    if (shader.startsWith("/")) {
                        rel = shader.substring(1);
                    } else if (shader.toLowerCase(Locale.US).endsWith(".glsl")) {
                        rel = shader;
                    } else {
                        continue;
                    }

                    if (shaderSources.containsKey(shader)) continue;
                    File shaderFile = resolveResource(chart.packDir, chartFile.getParentFile(), rel);
                    if (shaderFile == null || !shaderFile.exists()) continue;
                    try {
                        shaderSources.put(shader, readFileToString(shaderFile));
                    } catch (Throwable ignored) {
                    }
                }
                chart.prprShaderSources = shaderSources;
            }
        } catch (Throwable ignored) {
        }
    }

    private static ArrayNode getAsJsonArray(JsonNode o, String... keys) {
        if (o == null || keys == null) return null;
        for (String k : keys) {
            if (k == null) continue;
            if (o.has(k) && o.get(k).isArray()) return (ArrayNode) o.get(k);
        }
        return null;
    }

    private static boolean isNumberArray(ArrayNode arr) {
        if (arr == null) return false;
        if (arr.size() == 0) return false;
        for (JsonNode e : arr) {
            if (e == null || !e.isNumber()) return false;
        }
        return true;
    }

    private static double parseBeatTriple(ArrayNode arr) {
        if (arr == null || arr.size() < 3) return 0.0;
        double a = getAsDouble(arr.get(0), 0.0);
        double b = getAsDouble(arr.get(1), 0.0);
        double c = getAsDouble(arr.get(2), 1.0);
        if (c == 0.0) c = 1.0;
        return a + b / c;
    }

    private static double beatToSec(List<PrprBpmSeg> bpmSegsDesc, double beat) {
        if (bpmSegsDesc != null) {
            for (PrprBpmSeg seg : bpmSegsDesc) {
                if (seg == null) continue;
                if (seg.startBeat > beat) continue;
                return seg.startTime + (beat - seg.startBeat) * seg.beatTime;
            }
        }
        return beat * 0.5;
    }

    private static String getAsString(JsonNode e, String def) {
        try {
            if (e == null || e.isNull()) return def;
            if (e.isTextual()) return e.asText();
            return def;
        } catch (Throwable ignored) {
            return def;
        }
    }

    private static double getAsDouble(JsonNode e, double def) {
        try {
            if (e == null || e.isNull()) return def;
            if (e.isNumber()) return e.asDouble();
            if (e.isBoolean()) return e.asBoolean() ? 1.0 : 0.0;
            if (e.isTextual()) {
                String s = e.asText();
                if (s == null) return def;
                return Double.parseDouble(s.trim());
            }
        } catch (Throwable ignored) {
        }
        return def;
    }

    private static boolean getAsBoolean(JsonNode e, boolean def) {
        try {
            if (e == null || e.isNull()) return def;
            if (e.isBoolean()) return e.asBoolean();
            if (e.isNumber()) return e.asInt() != 0;
            if (e.isTextual()) {
                String s = e.asText();
                if (s == null) return def;
                s = s.trim().toLowerCase(Locale.US);
                return ("true".equals(s) || "1".equals(s));
            }
        } catch (Throwable ignored) {
        }
        return def;
    }

    /**
     * Quick format detection from a 64KiB prefix.
     * Official: contains "formatVersion". RPE: contains RPEVersion / BPMList / META / JudgeLineList.
     * Otherwise: PhiEdit.
     */
    private static ChartFormat detectFormatFast(@NonNull File f) {
        final int MAX = 64 * 1024;
        try (InputStream is = new FileInputStream(f)) {
            int want = (int) Math.min(f.length(), (long) MAX);
            if (want <= 0) return ChartFormat.PHIEDIT;

            byte[] buf = new byte[want];
            int n = is.read(buf);
            if (n <= 0) return ChartFormat.PHIEDIT;

            String prefix = new String(buf, 0, n, StandardCharsets.UTF_8);
            if (prefix.startsWith("\uFEFF")) prefix = prefix.substring(1);

            int i = 0;
            while (i < prefix.length()) {
                char c = prefix.charAt(i);
                if (c == '\uFEFF' || Character.isWhitespace(c)) {
                    i++;
                    continue;
                }
                break;
            }
            if (i >= prefix.length()) return ChartFormat.PHIEDIT;
            if (prefix.charAt(i) != '{') return ChartFormat.PHIEDIT;

            if (prefix.contains("\"formatVersion\"")) return ChartFormat.OFFICIAL;

            if (prefix.contains("\"RPEVersion\"")
                    || prefix.contains("\"BPMList\"")
                    || prefix.contains("\"META\"")
                    || prefix.contains("\"JudgeLineList\"")) {
                return ChartFormat.RPE;
            }

            return ChartFormat.PHIEDIT;
        } catch (Exception ignored) {
            return looksLikeJsonObject(f) ? ChartFormat.PHIEDIT : ChartFormat.PHIEDIT;
        }
    }

    private static boolean looksLikeJsonObject(@NonNull File f) {
        try (Reader r = openUtf8ReaderStripBom(f)) {
            int ch;
            while ((ch = r.read()) != -1) {
                if (ch == '\uFEFF') continue;
                if (!Character.isWhitespace(ch)) {
                    return ch == '{';
                }
            }
        } catch (Exception ignored) {
        }
        return false;
    }

    private static Chart parseChartJson(@NonNull File f) throws IOException {
        try (Reader r = openUtf8ReaderStripBom(f)) {
            return OBJECT_MAPPER.readValue(r, Chart.class);
        }
    }

    private static Reader openUtf8ReaderStripBom(@NonNull File f) throws IOException {
        InputStream is = new FileInputStream(f);
        InputStreamReader isr = new InputStreamReader(is, StandardCharsets.UTF_8);
        PushbackReader pr = new PushbackReader(isr, 1);
        int ch = pr.read();
        if (ch != -1 && ch != '\uFEFF') {
            pr.unread(ch);
        }
        return new BufferedReader(pr, 32 * 1024);
    }

    private static String readFileToString(@NonNull File f) throws IOException {
        StringBuilder sb = new StringBuilder((int) Math.min(f.length(), 1024 * 1024));
        try (Reader r = openUtf8ReaderStripBom(f)) {
            char[] buf = new char[16 * 1024];
            int n;
            while ((n = r.read(buf)) != -1) {
                sb.append(buf, 0, n);
            }
        }
        return sb.toString();
    }

    private static void tryApplyAttachUiFixFlag(@NonNull Chart chart, @NonNull File chartFile) {
        try {
            File infoFile = findUpwards(chartFile.getParentFile(), 6,
                    "info.yml", "info.yaml", "info.json", "Info.yml", "Info.json");
            if (infoFile == null || !infoFile.exists()) return;
            com.wuying.phigros.util.InfoFileUtils.Info info =
                    com.wuying.phigros.util.InfoFileUtils.readInfoFile(infoFile);
            if (info != null && info.useAttachUiFix != null) {
                chart.useAttachUiFix = info.useAttachUiFix;
            }
        } catch (Throwable ignored) {
        }
    }
}
