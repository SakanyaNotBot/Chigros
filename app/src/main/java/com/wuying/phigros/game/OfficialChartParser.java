package com.wuying.phigros.game;

import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

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
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

/**
 * Parser for official Phigros chart.json format.
 *
 * <p>Manual JSON parsing without Jackson reflection. All coordinate conversions done at parse time.
 * Events packed into EventLayers matching the RPE structure. Move events split into X/Y 1D streams.
 */
public final class OfficialChartParser {

    private OfficialChartParser() {}

    private static final ObjectMapper MAPPER = new ObjectMapper()
            .configure(JsonParser.Feature.ALLOW_UNQUOTED_FIELD_NAMES, true);

    public static Chart parse(File jsonFile) throws IOException {
        if (jsonFile == null) throw new IllegalArgumentException("jsonFile == null");

        JsonNode root = readJsonObject(jsonFile);
        return parseRoot(root);
    }

    public static Chart parse(String json) {
        if (json == null) throw new IllegalArgumentException("json == null");
        if (json.startsWith("\uFEFF")) json = json.substring(1);

        JsonNode rootEl;
        try {
            rootEl = MAPPER.readTree(json);
        } catch (IOException e) {
            throw new IllegalArgumentException("Invalid official chart json: " + e.getMessage(), e);
        }
        if (!rootEl.isObject()) {
            throw new IllegalArgumentException("Invalid official chart json: root is not object");
        }
        return parseRoot(rootEl);
    }

    private static Chart parseRoot(JsonNode root) {
        int formatVersion = getAsInt(root, 3, "formatVersion", "FormatVersion");
        if (formatVersion != 1 && formatVersion != 3) {
            formatVersion = 3;
        }

        Chart chart = new Chart();
        chart.formatVersion = formatVersion;
        chart.offset = getAsDouble(root, 0.0, "offset", "Offset");

        JsonNode linesNode = root.get("judgeLineList");
        if (linesNode == null || !linesNode.isArray()) {
            chart.judgeLineList = new ArrayList<>();
            return chart;
        }
        ArrayNode lines = (ArrayNode) linesNode;

        final int lineCount = lines.size();
        chart.judgeLineList = new ArrayList<>(lineCount);

        if (lineCount >= 6) {
            final int fmtVer = formatVersion;
            int threads = Math.max(2, Math.min(Runtime.getRuntime().availableProcessors(),
                    Math.max(2, lineCount / 3)));
            ExecutorService pool = Executors.newFixedThreadPool(threads);
            List<Future<JudgeLine>> futures = new ArrayList<>(lineCount);

            for (int i = 0; i < lineCount; i++) {
                final int idx = i;
                final JsonNode lineObj = asObj(lines.get(i));
                if (lineObj == null) {
                    futures.add(null);
                    continue;
                }
                futures.add(pool.submit(new Callable<JudgeLine>() {
                    @Override
                    public JudgeLine call() {
                        return parseJudgeLine(lineObj, fmtVer, idx);
                    }
                }));
            }
            pool.shutdown();

            for (int i = 0; i < futures.size(); i++) {
                Future<JudgeLine> f = futures.get(i);
                if (f == null) continue;
                try {
                    JudgeLine line = f.get();
                    if (line != null) {
                        chart.judgeLineList.add(line);
                        int ui = line.attachUiElementId;
                        if (ui >= 1 && ui <= 7) {
                            chart.attachUiLineIndex[ui] = chart.judgeLineList.size() - 1;
                        }
                    }
                } catch (InterruptedException | ExecutionException e) {
                    JsonNode lineObj = asObj(lines.get(i));
                    if (lineObj != null) {
                        JudgeLine line = parseJudgeLine(lineObj, fmtVer, i);
                        if (line != null) {
                            chart.judgeLineList.add(line);
                            int ui = line.attachUiElementId;
                            if (ui >= 1 && ui <= 7) {
                                chart.attachUiLineIndex[ui] = chart.judgeLineList.size() - 1;
                            }
                        }
                    }
                }
            }
        } else {
            for (int i = 0; i < lineCount; i++) {
                JsonNode lineObj = asObj(lines.get(i));
                if (lineObj == null) continue;

                JudgeLine line = parseJudgeLine(lineObj, formatVersion, i);
                chart.judgeLineList.add(line);

                int ui = line.attachUiElementId;
                if (ui >= 1 && ui <= 7) {
                    chart.attachUiLineIndex[ui] = i;
                }
            }
        }

        return chart;
    }

    private static JudgeLine parseJudgeLine(JsonNode lineObj, int formatVersion, int index) {
        JudgeLine line = new JudgeLine();
        line.index = index;
        line.bpm = getAsDouble(lineObj, 120.0, "bpm", "BPM");
        if (line.bpm <= 0) line.bpm = 120.0;

        line.invertRotation = false;

        line.notesAbove = parseNotes(lineObj, line.bpm, true);
        line.notesBelow = parseNotes(lineObj, line.bpm, false);

        if (formatVersion == 1) {
            unpackV1MoveEvents(lineObj);
        }

        JudgeLine.EventLayer layer = new JudgeLine.EventLayer();

        layer.judgeLineDisappearEvents = parseLineEvents1D(lineObj, line.bpm,
                "judgeLineDisappearEvents", "disappearEvents", 0.0);

        layer.judgeLineMoveXEvents = parseMoveComponentEvents(lineObj, line.bpm,
                "judgeLineMoveEvents", true, formatVersion);

        layer.judgeLineMoveYEvents = parseMoveComponentEvents(lineObj, line.bpm,
                "judgeLineMoveEvents", false, formatVersion);

        List<LineEvent> rawRotate = parseLineEvents1D(lineObj, line.bpm,
                "judgeLineRotateEvents", "rotateEvents", 0.0);
        layer.judgeLineRotateEvents = new ArrayList<>(rawRotate.size());
        for (LineEvent e : rawRotate) {
            LineEvent ne = copyLineEvent(e);
            ne.start = -ne.start;
            ne.end = -ne.end;
            layer.judgeLineRotateEvents.add(ne);
        }

        ensureCoverage(layer.judgeLineDisappearEvents, 0.0);
        ensureCoverage(layer.judgeLineMoveXEvents, 0.0);
        ensureCoverage(layer.judgeLineMoveYEvents, 0.0);
        ensureCoverage(layer.judgeLineRotateEvents, 0.0);

        List<JudgeLine.EventLayer> layers = new ArrayList<>();
        layers.add(layer);
        line.eventLayers = layers;

        line.speedEvents = parseSpeedEventsOfficial(lineObj, line.bpm);
        if (line.speedEvents == null || line.speedEvents.isEmpty()) {
            line.speedEvents = defaultSpeedEvents();
        }
        if (line.speedEvents != null) {
            Collections.sort(line.speedEvents, Comparator.comparingDouble(a -> a.startTime));
            EventUtils.initSpeedEvents(line.speedEvents);
        }

        return line;
    }

    private static List<Note> parseNotes(JsonNode lineObj, double bpm, boolean isAbove) {
        String key = isAbove ? "notesAbove" : "notesBelow";
        ArrayNode arr = asArray(lineObj, key);
        if (arr == null) return new ArrayList<>();

        List<Note> out = new ArrayList<>(arr.size());
        for (int i = 0; i < arr.size(); i++) {
            JsonNode no = asObj(arr.get(i));
            if (no == null) continue;

            int rawType = getAsInt(no, 1, "type", "Type");
            int type;
            switch (rawType) {
                case 1: type = GameConstants.NOTE_TAP; break;
                case 2: type = GameConstants.NOTE_DRAG; break;
                case 3: type = GameConstants.NOTE_HOLD; break;
                case 4: type = GameConstants.NOTE_FLICK; break;
                default: type = rawType; break;
            }

            Note note = new Note();
            note.type = type;
            note.isAbove = isAbove;
            note.time = getAsDouble(no, 0.0, "time", "Time");
            note.holdTime = getAsDouble(no, 0.0, "holdTime", "HoldTime");
            note.positionX = getAsDouble(no, 0.0, "positionX", "PositionX");
            note.speed = getAsDouble(no, 1.0, "speed", "Speed");
            note.isFake = getAsBool(no, false, "isFake", "IsFake", "fake", "Fake");

            note.alpha = (float) getAsDouble(no, 1.0, "alpha", "Alpha");
            note.size = (float) getAsDouble(no, 1.0, "size", "Size");
            note.xScale = (float) getAsDouble(no, 1.0, "xScale", "XScale");
            note.yOffset = (float) getAsDouble(no, 0.0, "yOffset", "YOffset", "offsetY");
            note.visibleTime = (float) getAsDouble(no, -1.0, "visibleTime", "VisibleTime");
            note.judgeArea = (float) getAsDouble(no, 1.0, "judgeArea", "JudgeArea");

            out.add(note);
        }
        return out;
    }

    private static List<LineEvent> parseLineEvents1D(JsonNode obj, double bpm,
                                                     String key1, String key2,
                                                     double defaultValue) {
        ArrayNode arr = asArray(obj, key1);
        if (arr == null) arr = asArray(obj, key2);
        if (arr == null) return new ArrayList<>();

        List<LineEvent> out = new ArrayList<>(arr.size());
        for (int i = 0; i < arr.size(); i++) {
            JsonNode e = asObj(arr.get(i));
            if (e == null) continue;

            LineEvent le = new LineEvent();
            le.startTime = parseEventTime(getFirst(e, "startTime", "StartTime"), bpm);
            le.endTime = parseEventTime(getFirst(e, "endTime", "EndTime"), bpm);
            le.start = getAsDouble(e, defaultValue, "start", "Start");
            le.end = getAsDouble(e, le.start, "end", "End");

            if (!Double.isFinite(le.startTime)) le.startTime = 0.0;
            if (!Double.isFinite(le.endTime)) le.endTime = le.startTime;
            if (le.endTime < le.startTime) le.endTime = le.startTime;

            out.add(le);
        }
        out.sort(Comparator.comparingDouble(a -> a.startTime));
        return out;
    }

    /**
     * Parse moveX or moveY component from packed judgeLineMoveEvents.
     * For V3: values are 0..1, converted to offset space (-0.5..0.5).
     * For V1: unpacked ints, normalized by /880 (X) or /520 (Y), then to offset.
     */
    private static List<LineEvent> parseMoveComponentEvents(JsonNode lineObj, double bpm,
                                                            String key, boolean isX,
                                                            int formatVersion) {
        ArrayNode arr = asArray(lineObj, key);
        if (arr == null) return new ArrayList<>();

        List<LineEvent> out = new ArrayList<>(arr.size());
        for (int i = 0; i < arr.size(); i++) {
            JsonNode e = asObj(arr.get(i));
            if (e == null) continue;

            LineEvent le = new LineEvent();
            le.startTime = parseEventTime(getFirst(e, "startTime", "StartTime"), bpm);
            le.endTime = parseEventTime(getFirst(e, "endTime", "EndTime"), bpm);

            if (!Double.isFinite(le.startTime)) le.startTime = 0.0;
            if (!Double.isFinite(le.endTime)) le.endTime = le.startTime;
            if (le.endTime < le.startTime) le.endTime = le.startTime;

            double rawStart, rawEnd;
            if (isX) {
                rawStart = getAsDouble(e, 0.5, "start", "Start");
                rawEnd = getAsDouble(e, rawStart, "end", "End");
            } else {
                rawStart = getAsDouble(e, 0.5, "start2", "Start2");
                rawEnd = getAsDouble(e, rawStart, "end2", "End2");
            }

            if (formatVersion == 1) {
                double divisor = isX ? 880.0 : 520.0;
                rawStart /= divisor;
                rawEnd /= divisor;
            }

            le.start = rawStart - 0.5;
            le.end = rawEnd - 0.5;

            out.add(le);
        }
        out.sort(Comparator.comparingDouble(a -> a.startTime));
        return out;
    }

    private static List<SpeedEvent> parseSpeedEventsOfficial(JsonNode lineObj, double bpm) {
        ArrayNode arr = asArray(lineObj, "speedEvents", "SpeedEvents", "speed");
        if (arr == null) return null;

        List<SpeedEvent> out = new ArrayList<>(arr.size());
        for (int i = 0; i < arr.size(); i++) {
            JsonNode e = asObj(arr.get(i));
            if (e == null) continue;

            SpeedEvent se = new SpeedEvent();
            se.startTime = parseEventTime(getFirst(e, "startTime", "StartTime"), bpm);
            double endTimeRaw = parseEventTime(getFirst(e, "endTime", "EndTime"), bpm);

            if (!Double.isFinite(se.startTime)) se.startTime = 0.0;
            if (!Double.isFinite(endTimeRaw)) endTimeRaw = se.startTime + 1e9;
            if (endTimeRaw < se.startTime) endTimeRaw = se.startTime;
            se.endTime = endTimeRaw;

            double v = getAsDouble(e, 1.0, "value", "Value", "speed", "Speed");
            se.value = v;
            se.endValue = v;

            out.add(se);
        }
        out.sort(Comparator.comparingDouble(a -> a.startTime));
        return out;
    }

    /**
     * Unpack V1 format move events. Phigros formatVersion 1 packs X and Y into a single integer:
     * packed = floor(x) * 1000 + y.
     */
    private static void unpackV1MoveEvents(JsonNode lineObj) {
        ArrayNode arr = asArray(lineObj, "judgeLineMoveEvents");
        if (arr == null) return;

        for (int i = 0; i < arr.size(); i++) {
            JsonNode eNode = asObj(arr.get(i));
            if (!(eNode instanceof ObjectNode)) continue;
            ObjectNode e = (ObjectNode) eNode;

            double startPacked = getAsDouble(e, 0.0, "start", "Start");
            double endPacked = getAsDouble(e, startPacked, "end", "End");

            long s = Math.round(startPacked);
            long t = Math.round(endPacked);

            long startX = (s - (s % 1000)) / 1000;
            long startY = s % 1000;
            long endX = (t - (t % 1000)) / 1000;
            long endY = t % 1000;

            e.put("start", (double) startX);
            e.put("start2", (double) startY);
            e.put("end", (double) endX);
            e.put("end2", (double) endY);
        }
    }

    private static void ensureCoverage(List<LineEvent> list, double defValue) {
        if (list == null || list.isEmpty()) {
            if (list == null) return;
            LineEvent e = new LineEvent();
            e.startTime = 0.0;
            e.endTime = 1e9;
            e.start = defValue;
            e.end = defValue;
            list.add(e);
            return;
        }
        list.sort(Comparator.comparingDouble(a -> a.startTime));
        LineEvent first = list.get(0);
        if (first.startTime > 0.0) {
            LineEvent pre = new LineEvent();
            pre.startTime = 0.0;
            pre.endTime = first.startTime;
            pre.start = first.start;
            pre.end = first.start;
            list.add(0, pre);
        }
    }

    private static List<SpeedEvent> defaultSpeedEvents() {
        SpeedEvent se = new SpeedEvent();
        se.startTime = 0.0;
        se.endTime = 1e9;
        se.value = 1.0;
        se.endValue = 1.0;
        List<SpeedEvent> out = new ArrayList<>();
        out.add(se);
        return out;
    }

    private static JsonNode readJsonObject(File f) throws IOException {
        String text = readFileToString(f);
        if (text.startsWith("\uFEFF")) text = text.substring(1);
        JsonNode el = MAPPER.readTree(text);
        if (!el.isObject()) {
            throw new IOException("Root is not a JSON object: " + f.getAbsolutePath());
        }
        return el;
    }

    private static String readFileToString(File f) throws IOException {
        StringBuilder sb = new StringBuilder((int) Math.min(f.length(), 2 * 1024 * 1024));
        try (Reader r = openUtf8ReaderStripBom(f)) {
            char[] buf = new char[16 * 1024];
            int n;
            while ((n = r.read(buf)) != -1) {
                sb.append(buf, 0, n);
            }
        }
        return sb.toString();
    }

    private static Reader openUtf8ReaderStripBom(File f) throws IOException {
        InputStream is = new FileInputStream(f);
        InputStreamReader isr = new InputStreamReader(is, StandardCharsets.UTF_8);
        PushbackReader pr = new PushbackReader(isr, 1);
        int ch = pr.read();
        if (ch != -1 && ch != '\uFEFF') {
            pr.unread(ch);
        }
        return new BufferedReader(pr, 32 * 1024);
    }

    private static double parseEventTime(JsonNode el, double bpm) {
        if (el == null || el.isNull()) return Double.NaN;
        try {
            if (el.isValueNode()) {
                return el.asDouble();
            }
        } catch (Exception ignored) {}
        return Double.NaN;
    }

    private static double getAsDouble(JsonNode obj, double def, String... keys) {
        JsonNode e = getFirst(obj, keys);
        if (e == null || e.isNull()) return def;
        try {
            if (e.isValueNode() && !e.isNull()) return e.asDouble();
        } catch (Exception ignored) {}
        return def;
    }

    private static int getAsInt(JsonNode obj, int def, String... keys) {
        JsonNode e = getFirst(obj, keys);
        if (e == null || e.isNull()) return def;
        try {
            if (e.isValueNode() && !e.isNull()) return e.asInt();
        } catch (Exception ignored) {}
        return def;
    }

    private static boolean getAsBool(JsonNode obj, boolean def, String... keys) {
        JsonNode e = getFirst(obj, keys);
        if (e == null || e.isNull()) return def;
        try {
            if (e.isBoolean()) return e.asBoolean();
            if (e.isNumber()) return e.asInt() != 0;
            if (e.isTextual()) {
                String s = e.asText();
                return "true".equalsIgnoreCase(s) || "1".equals(s);
            }
        } catch (Exception ignored) {}
        return def;
    }

    private static JsonNode getFirst(JsonNode obj, String... keys) {
        if (obj == null || keys == null) return null;
        for (String k : keys) {
            if (k == null) continue;
            if (obj.has(k)) return obj.get(k);
        }
        return null;
    }

    private static JsonNode asObj(JsonNode el) {
        if (el == null || el.isNull() || !el.isObject()) return null;
        return el;
    }

    private static ArrayNode asArray(JsonNode obj, String... keys) {
        if (obj == null || keys == null) return null;
        for (String k : keys) {
            if (k == null) continue;
            JsonNode e = obj.get(k);
            if (e != null && e.isArray()) return (ArrayNode) e;
        }
        return null;
    }

    private static LineEvent copyLineEvent(LineEvent src) {
        LineEvent dst = new LineEvent();
        dst.startTime = src.startTime;
        dst.endTime = src.endTime;
        dst.start = src.start;
        dst.end = src.end;
        dst.easingType = src.easingType;
        dst.easingLeft = src.easingLeft;
        dst.easingRight = src.easingRight;
        dst.bezier = src.bezier;
        dst.bezierPoints = src.bezierPoints;
        dst.bezierLut = src.bezierLut;
        return dst;
    }
}
