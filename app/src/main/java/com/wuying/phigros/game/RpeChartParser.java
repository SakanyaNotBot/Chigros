package com.wuying.phigros.game;

import com.fasterxml.jackson.core.JsonFactory;
import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.core.JsonToken;
import com.fasterxml.jackson.core.json.JsonReadFeature;
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
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

/**
 * Parser for RePhiEdit charts (.rpe format).
 *
 * <p>Converts RPE JSON into the project's Chart/JudgeLine model. Supports BPMList, per-line
 * bpmFactor, event layers, speed events with easing, note controls, and storyboard extensions.
 */
public final class RpeChartParser {

    private static final boolean ENABLE_STORYBOARD = false;

    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();
    private static final JsonFactory JSON_FACTORY;

    static {
        JSON_FACTORY = JsonFactory.builder()
                .enable(JsonReadFeature.ALLOW_UNESCAPED_CONTROL_CHARS)
                .enable(JsonReadFeature.ALLOW_BACKSLASH_ESCAPING_ANY_CHARACTER)
                .build()
                .enable(JsonParser.Feature.ALLOW_UNQUOTED_FIELD_NAMES)
                .enable(JsonParser.Feature.ALLOW_SINGLE_QUOTES);
    }

    private RpeChartParser() {}

    /**
     * Parse an RPE chart file using a low-memory streaming strategy.
     * Pass 1 reads only BPMList/META(offset); Pass 2 streams judgeLineList nodes, then parses in parallel.
     */
    public static Chart parseFile(File jsonFile) throws IOException {
        if (jsonFile == null) throw new IllegalArgumentException("jsonFile == null");

        ObjectNode headerRoot = readHeaderRoot(jsonFile);

        List<BpmPoint> bpmPoints = parseBpmList(headerRoot);
        if (bpmPoints.isEmpty()) {
            bpmPoints.add(new BpmPoint(0.0, 120.0));
        }
        bpmPoints.sort(Comparator.comparingDouble(a -> a.beat));
        if (bpmPoints.get(0).beat > 0.0) {
            bpmPoints.add(0, new BpmPoint(0.0, bpmPoints.get(0).bpm));
        }

        BpmConverter bpmConv = new BpmConverter(bpmPoints);

        Chart chart = new Chart();
        chart.offset = parseOffsetSeconds(headerRoot);
        chart.bpmTimeline = bpmConv.toTimeline();
        chart.judgeLineList = new ArrayList<>();

        final List<JsonNode> lineNodes = new ArrayList<>();
        try (Reader r = openUtf8ReaderStripBom(jsonFile)) {
            JsonParser jp = JSON_FACTORY.createParser(r);

            if (jp.nextToken() != JsonToken.START_OBJECT) {
                return chart;
            }

            String name;
            while ((name = jp.nextFieldName()) != null) {
                jp.nextToken();

                if (equalsAnyIgnoreCase(name, "judgeLineList", "JudgeLineList")) {
                    if (jp.currentToken() != JsonToken.START_ARRAY) {
                        jp.skipChildren();
                        continue;
                    }
                    while (jp.nextToken() != JsonToken.END_ARRAY) {
                        JsonNode lineEl = OBJECT_MAPPER.readTree(jp);
                        if (lineEl != null && lineEl.isObject()) {
                            lineNodes.add(lineEl);
                        }
                    }
                } else {
                    jp.skipChildren();
                }
            }
        }

        final int lineCount = lineNodes.size();
        final File parentDir = jsonFile.getParentFile();
        final BpmTimeline timeline = chart.bpmTimeline;

        if (lineCount >= 4) {
            int threads = Math.max(2, Math.min(Runtime.getRuntime().availableProcessors(),
                    Math.max(2, lineCount / 2)));
            ExecutorService pool = Executors.newFixedThreadPool(threads);
            List<Future<JudgeLine>> futures = new ArrayList<>(lineCount);
            for (final JsonNode node : lineNodes) {
                futures.add(pool.submit(new Callable<JudgeLine>() {
                    @Override
                    public JudgeLine call() {
                        return parseJudgeLine(node, bpmConv, timeline, parentDir);
                    }
                }));
            }
            pool.shutdown();
            chart.judgeLineList = new ArrayList<>(lineCount);
            for (int i = 0; i < futures.size(); i++) {
                try {
                    JudgeLine line = futures.get(i).get();
                    if (line != null) {
                        chart.judgeLineList.add(line);
                        int ui = line.attachUiElementId;
                        if (ui >= 1 && ui <= 7) {
                            chart.attachUiLineIndex[ui] = chart.judgeLineList.size() - 1;
                        }
                    }
                } catch (InterruptedException | ExecutionException e) {
                    JudgeLine line = parseJudgeLine(lineNodes.get(i), bpmConv, timeline, parentDir);
                    if (line != null) {
                        chart.judgeLineList.add(line);
                        int ui = line.attachUiElementId;
                        if (ui >= 1 && ui <= 7) {
                            chart.attachUiLineIndex[ui] = chart.judgeLineList.size() - 1;
                        }
                    }
                }
            }
        } else {
            chart.judgeLineList = new ArrayList<>(lineCount);
            for (JsonNode node : lineNodes) {
                JudgeLine line = parseJudgeLine(node, bpmConv, timeline, parentDir);
                if (line != null) {
                    chart.judgeLineList.add(line);
                    int ui = line.attachUiElementId;
                    if (ui >= 1 && ui <= 7) {
                        chart.attachUiLineIndex[ui] = chart.judgeLineList.size() - 1;
                    }
                }
            }
        }

        buildBezierLuts(chart);
        return chart;
    }

    /**
     * Build bezier LUTs for all events in the chart using a shared cache.
     */
    private static void buildBezierLuts(Chart chart) {
        if (chart == null || chart.judgeLineList == null) return;
        Map<String, float[]> cache = new HashMap<>();
        for (JudgeLine line : chart.judgeLineList) {
            if (line == null) continue;
            buildBezierLutsForLine(line, cache);
        }
    }

    private static void buildBezierLutsForLine(JudgeLine line, Map<String, float[]> cache) {
        if (line.speedEvents != null) {
            for (SpeedEvent e : line.speedEvents) {
                if (e != null && e.bezier && e.bezierPoints != null && e.bezierPoints.length >= 4) {
                    String key = Easing.bezierCacheKey(e.bezierPoints);
                    if (key != null) {
                        float[] lut = cache.get(key);
                        if (lut == null) {
                            lut = Easing.buildBezierLut(e.bezierPoints);
                            cache.put(key, lut);
                        }
                        e.bezierLut = lut;
                    }
                }
            }
        }
        assignBezierLuts(line.judgeLineRotateEvents, cache);
        assignBezierLuts(line.judgeLineDisappearEvents, cache);
        assignBezierLutsL(line.judgeLineScaleEvents, cache);
        assignBezierLutsL(line.judgeLineScaleXEvents, cache);
        assignBezierLutsL(line.judgeLineScaleYEvents, cache);
        assignBezierLutsL(line.judgeLineInclineEvents, cache);
        assignBezierLutsL(line.judgeLineColorEvents, cache);
        assignBezierLutsL(line.judgeLineTextEvents, cache);
        if (line.judgeLineMoveEvents != null) {
            for (MoveEvent e : line.judgeLineMoveEvents) {
                if (e != null && e.bezier && e.bezierPoints != null && e.bezierPoints.length >= 4) {
                    String key = Easing.bezierCacheKey(e.bezierPoints);
                    if (key != null) {
                        float[] lut = cache.get(key);
                        if (lut == null) {
                            lut = Easing.buildBezierLut(e.bezierPoints);
                            cache.put(key, lut);
                        }
                        e.bezierLut = lut;
                    }
                }
            }
        }
        if (line.eventLayers != null) {
            for (JudgeLine.EventLayer layer : line.eventLayers) {
                if (layer == null) continue;
                assignBezierLuts(layer.judgeLineRotateEvents, cache);
                assignBezierLuts(layer.judgeLineDisappearEvents, cache);
                assignBezierLutsM(layer.judgeLineMoveEvents, cache);
                if (layer.judgeLineMoveXEvents != null) assignBezierLuts(layer.judgeLineMoveXEvents, cache);
                if (layer.judgeLineMoveYEvents != null) assignBezierLuts(layer.judgeLineMoveYEvents, cache);
            }
        }
    }

    private static void assignBezierLuts(List<LineEvent> events, Map<String, float[]> cache) {
        if (events == null) return;
        for (LineEvent e : events) {
            if (e != null && e.bezier && e.bezierPoints != null && e.bezierPoints.length >= 4) {
                String key = Easing.bezierCacheKey(e.bezierPoints);
                if (key != null) {
                    float[] lut = cache.get(key);
                    if (lut == null) {
                        lut = Easing.buildBezierLut(e.bezierPoints);
                        cache.put(key, lut);
                    }
                    e.bezierLut = lut;
                }
            }
        }
    }

    private static void assignBezierLutsM(List<MoveEvent> events, Map<String, float[]> cache) {
        if (events == null) return;
        for (MoveEvent e : events) {
            if (e != null && e.bezier && e.bezierPoints != null && e.bezierPoints.length >= 4) {
                String key = Easing.bezierCacheKey(e.bezierPoints);
                if (key != null) {
                    float[] lut = cache.get(key);
                    if (lut == null) {
                        lut = Easing.buildBezierLut(e.bezierPoints);
                        cache.put(key, lut);
                    }
                    e.bezierLut = lut;
                }
            }
        }
    }

    private static void assignBezierLutsL(List<?> events, Map<String, float[]> cache) {
        if (events == null) return;
        for (Object obj : events) {
            if (obj instanceof MoveEvent) {
                MoveEvent e = (MoveEvent) obj;
                if (e.bezier && e.bezierPoints != null && e.bezierPoints.length >= 4) {
                    String key = Easing.bezierCacheKey(e.bezierPoints);
                    if (key != null) {
                        float[] lut = cache.get(key);
                        if (lut == null) {
                            lut = Easing.buildBezierLut(e.bezierPoints);
                            cache.put(key, lut);
                        }
                        e.bezierLut = lut;
                    }
                }
            } else if (obj instanceof LineEvent) {
                LineEvent e = (LineEvent) obj;
                if (e.bezier && e.bezierPoints != null && e.bezierPoints.length >= 4) {
                    String key = Easing.bezierCacheKey(e.bezierPoints);
                    if (key != null) {
                        float[] lut = cache.get(key);
                        if (lut == null) {
                            lut = Easing.buildBezierLut(e.bezierPoints);
                            cache.put(key, lut);
                        }
                        e.bezierLut = lut;
                    }
                }
            }
        }
    }

    private static boolean equalsAnyIgnoreCase(String s, String... keys) {
        if (s == null || keys == null) return false;
        for (String k : keys) {
            if (k != null && k.equalsIgnoreCase(s)) return true;
        }
        return false;
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

    private static ObjectNode readHeaderRoot(File jsonFile) throws IOException {
        ObjectNode root = OBJECT_MAPPER.createObjectNode();
        boolean gotBpm = false;
        boolean gotMeta = false;
        boolean gotOffset = false;

        try (Reader r = openUtf8ReaderStripBom(jsonFile)) {
            JsonParser jp = JSON_FACTORY.createParser(r);

            if (jp.nextToken() != JsonToken.START_OBJECT) {
                return root;
            }

            String name;
            while ((name = jp.nextFieldName()) != null) {
                if (name == null) {
                    jp.nextToken();
                    jp.skipChildren();
                    continue;
                }

                jp.nextToken();

                if (!gotBpm && equalsAnyIgnoreCase(name, "BPMList", "bpmList", "BpmList")) {
                    JsonNode el = OBJECT_MAPPER.readTree(jp);
                    root.set("BPMList", el);
                    gotBpm = true;
                } else if (!gotMeta && equalsAnyIgnoreCase(name, "META", "meta", "Meta")) {
                    JsonNode el = OBJECT_MAPPER.readTree(jp);
                    root.set("META", el);
                    gotMeta = true;
                } else if (!gotOffset && equalsAnyIgnoreCase(name, "offset", "Offset", "chartOffset")) {
                    JsonNode el = OBJECT_MAPPER.readTree(jp);
                    root.set("offset", el);
                    gotOffset = true;
                } else if (equalsAnyIgnoreCase(name, "judgeLineList", "JudgeLineList")) {
                    break;
                } else {
                    jp.skipChildren();
                }

                if (gotBpm && (gotMeta || gotOffset)) {
                    break;
                }
            }
        } catch (Exception ignored) {
        }

        return root;
    }

    private static int parseAttachUiElementId(String attach) {
        if (attach == null) return 0;
        String s = attach.trim().toLowerCase(Locale.US);
        switch (s) {
            case "pause": return 1;
            case "combonumber": case "combo_number": return 2;
            case "combo": return 3;
            case "score": return 4;
            case "bar": return 5;
            case "name": return 6;
            case "level": return 7;
            default:
                try {
                    return Integer.parseInt(s);
                } catch (Exception e) {
                    return 0;
                }
        }
    }

    private static JudgeLine parseJudgeLine(JsonNode lineObj, BpmConverter bpmConv, BpmTimeline timeline, File baseDir) {
        if (lineObj == null || bpmConv == null) return null;

        double bpmFactor = getAsDouble(lineObj, 1.0, "bpmfactor", "bpmFactor", "BPMFactor");
        if (!Double.isFinite(bpmFactor) || bpmFactor <= 0.0) bpmFactor = 1.0;
        double lineBpm = bpmConv.baseBpm();

        JudgeLine line = new JudgeLine();
        line.bpm = lineBpm / bpmFactor;
        line.bpmfactor = bpmFactor;
        line.bpmTimeline = timeline;
        line.invertRotation = false;

        line.zOrder = getAsInt(lineObj, 0, "zOrder", "z", "order");
        line.isCover = getAsBool(lineObj, false, "isCover", "cover", "isCoverLine", "coverLine");
        line.father = getAsInt(lineObj, -1, "father", "parent", "fatherLine", "parentLine");
        line.rotateWithFather = getAsBool(lineObj, false, "rotateWithFather", "rotWithFather", "rotateWithParent");

        String attach = getAsString(lineObj, null, "attachUI", "attachUi", "attach_ui");
        if (attach != null) {
            line.attachUiElementId = parseAttachUiElementId(attach);
        }

        line.noteAlphaControls = parseNoteControls(lineObj, "alphaControl", "alpha", 1f);
        line.noteScaleControls = parseNoteControls(lineObj, "sizeControl", "size", 1f);
        line.noteXControls = parseNoteControls(lineObj, "posControl", "pos", 1f);
        line.noteYControls = parseNoteControls(lineObj, "yControl", "y", 1f);

        String texture = getAsString(lineObj, null, "Texture", "texture");
        if (texture != null && !texture.isEmpty() && !"line.png".equalsIgnoreCase(texture)) {
            line.texture = resolvePath(baseDir, texture);
        }

        JsonNode ext = getAsObject(lineObj, null, "extended", "Extended");

        ArrayNode layers = getAsArray(lineObj, "eventLayers", "EventLayers", "layers");
        List<JudgeLine.EventLayer> parsedLayers = new ArrayList<>();
        List<List<SpeedEvent>> speedLayers = new ArrayList<>();

        if (layers != null && layers.size() > 0) {
            for (int li = 0; li < layers.size(); li++) {
                JsonNode layerObj = asObj(layers.get(li));
                if (layerObj == null) continue;

                JudgeLine.EventLayer layer = new JudgeLine.EventLayer();

                layer.judgeLineRotateEvents = parseLineEvents(layerObj, bpmConv, lineBpm,
                        "rotateEvents", "judgeLineRotateEvents", "rotationEvents", "rotEvents");
                layer.judgeLineDisappearEvents = parseAlphaEvents(layerObj, bpmConv, lineBpm);

                layer.judgeLineMoveXEvents = parseLineEvents(layerObj, bpmConv, lineBpm,
                        "moveXEvents", "judgeLineMoveXEvents");
                for (LineEvent e : layer.judgeLineMoveXEvents) {
                    e.start = toXNorm(e.start);
                    e.end = toXNorm(e.end);
                }

                layer.judgeLineMoveYEvents = parseLineEvents(layerObj, bpmConv, lineBpm,
                        "moveYEvents", "judgeLineMoveYEvents");
                for (LineEvent e : layer.judgeLineMoveYEvents) {
                    e.start = toYRaw(e.start);
                    e.end = toYRaw(e.end);
                }

                if (layer.judgeLineMoveXEvents.isEmpty() && layer.judgeLineMoveYEvents.isEmpty()) {
                    layer.judgeLineMoveEvents = parseMoveEvents(layerObj, bpmConv, lineBpm);
                }

                ensureCoverageRotate(layer.judgeLineRotateEvents);
                ensureCoverageAlpha(layer.judgeLineDisappearEvents);
                ensureCoveragePos(layer.judgeLineMoveXEvents, 0.0);
                ensureCoveragePos(layer.judgeLineMoveYEvents, 0.0);
                if (layer.judgeLineMoveEvents != null) {
                    ensureCoverageMove(layer.judgeLineMoveEvents);
                }

                parsedLayers.add(layer);

                List<SpeedEvent> spd = parseSpeedEvents(layerObj, bpmConv, lineBpm);
                if (spd != null && !spd.isEmpty()) {
                    ensureCoverageSpeed(spd);
                    speedLayers.add(spd);
                }
            }
        }

        if (parsedLayers.isEmpty()) {
            JudgeLine.EventLayer layer = new JudgeLine.EventLayer();
            layer.judgeLineRotateEvents = parseLineEvents(lineObj, bpmConv, lineBpm,
                    "judgeLineRotateEvents", "rotateEvents", "rotationEvents");
            layer.judgeLineDisappearEvents = parseAlphaEvents(lineObj, bpmConv, lineBpm);
            layer.judgeLineMoveXEvents = parseLineEvents(lineObj, bpmConv, lineBpm,
                    "moveXEvents", "judgeLineMoveXEvents");
            for (LineEvent e : layer.judgeLineMoveXEvents) {
                e.start = toXNorm(e.start);
                e.end = toXNorm(e.end);
            }
            layer.judgeLineMoveYEvents = parseLineEvents(lineObj, bpmConv, lineBpm,
                    "moveYEvents", "judgeLineMoveYEvents");
            for (LineEvent e : layer.judgeLineMoveYEvents) {
                e.start = toYRaw(e.start);
                e.end = toYRaw(e.end);
            }
            if (layer.judgeLineMoveXEvents.isEmpty() && layer.judgeLineMoveYEvents.isEmpty()) {
                layer.judgeLineMoveEvents = parseMoveEvents(lineObj, bpmConv, lineBpm);
            }

            ensureCoverageRotate(layer.judgeLineRotateEvents);
            ensureCoverageAlpha(layer.judgeLineDisappearEvents);
            ensureCoveragePos(layer.judgeLineMoveXEvents, 0.0);
            ensureCoveragePos(layer.judgeLineMoveYEvents, 0.0);
            if (layer.judgeLineMoveEvents != null) {
                ensureCoverageMove(layer.judgeLineMoveEvents);
            }

            parsedLayers.add(layer);

            List<SpeedEvent> spd = parseSpeedEvents(lineObj, bpmConv, lineBpm);
            if (spd != null && !spd.isEmpty()) {
                ensureCoverageSpeed(spd);
                speedLayers.add(spd);
            }
        }

        line.eventLayers = parsedLayers;

        line.speedEvents = mergeSpeedLayers(speedLayers);
        if (line.speedEvents == null || line.speedEvents.isEmpty()) {
            line.speedEvents = defaultSpeed();
        }
        if (line.speedEvents != null) {
            line.speedEvents.sort(Comparator.comparingDouble(s -> s.startTime));
            EventUtils.initSpeedEvents(line.speedEvents);
        }

        if (ext != null) {
            parseScaleIntoLine(ext, bpmConv, lineBpm, line, lineObj);
            line.judgeLineColorEvents = parseColorEvents(ext, bpmConv, lineBpm);
            line.judgeLineTextEvents = parseTextEvents(ext, bpmConv, lineBpm);
            line.judgeLineInclineEvents = parseInclineEvents(ext, bpmConv, lineBpm);
            line.gifEvents = parseGifEvents(ext, bpmConv, lineBpm);
        }

        if (line.texture != null && line.texture.toLowerCase(Locale.ROOT).endsWith(".gif")) {
            line.textureIsGif = true;
        }

        parseNotesIntoLine(lineObj, bpmConv, lineBpm, line);

        return line;
    }

    public static Chart parse(String json, File baseDir) {
        if (json == null) throw new IllegalArgumentException("json == null");

        if (json.startsWith("\uFEFF")) {
            json = json.substring(1);
        }

        JsonNode rootEl;
        try {
            rootEl = OBJECT_MAPPER.readTree(json);
        } catch (IOException e) {
            throw new IllegalArgumentException("Invalid RPE json: " + e.getMessage(), e);
        }
        if (!rootEl.isObject()) {
            throw new IllegalArgumentException("Invalid RPE json: root is not object");
        }

        List<BpmPoint> bpmPoints = parseBpmList(rootEl);
        if (bpmPoints.isEmpty()) {
            bpmPoints.add(new BpmPoint(0.0, 120.0));
        }
        bpmPoints.sort(Comparator.comparingDouble(a -> a.beat));
        if (bpmPoints.get(0).beat > 0.0) {
            bpmPoints.add(0, new BpmPoint(0.0, bpmPoints.get(0).bpm));
        }

        BpmConverter bpmConv = new BpmConverter(bpmPoints);

        Chart chart = new Chart();
        chart.offset = parseOffsetSeconds(rootEl);
        chart.bpmTimeline = bpmConv.toTimeline();
        chart.judgeLineList = new ArrayList<>();

        ArrayNode lines = selectJudgeLineArray(rootEl);
        if (lines == null) {
            return chart;
        }

        for (int i = 0; i < lines.size(); i++) {
            JsonNode lineObj = asObj(lines.get(i));
            if (lineObj == null) continue;

            JudgeLine line = parseJudgeLine(lineObj, bpmConv, chart.bpmTimeline, baseDir);
            if (line != null) {
                chart.judgeLineList.add(line);
                int ui = line.attachUiElementId;
                if (ui >= 1 && ui <= 7) {
                    chart.attachUiLineIndex[ui] = chart.judgeLineList.size() - 1;
                }
            }
        }

        buildBezierLuts(chart);
        return chart;
    }

    private static JsonNode getEventTime(JsonNode e, boolean start) {
        if (e == null) return null;
        JsonNode t = start
                ? getFirst(e, "startTime", "StartTime", "start_time")
                : getFirst(e, "endTime", "EndTime", "end_time");
        if (t != null && !t.isNull()) return t;
        JsonNode fb = start ? getFirst(e, "start") : getFirst(e, "end");
        if (fb != null && fb.isArray()) return fb;
        return null;
    }

    private static double parseOffsetSeconds(JsonNode root) {
        JsonNode meta = getAsObject(root, "META", "meta", "Meta");
        double off = Double.NaN;
        if (meta != null) {
            off = getAsDouble(meta, Double.NaN, "offset", "Offset", "chartOffset");
        }
        if (!Double.isFinite(off)) {
            off = getAsDouble(root, 0.0, "offset", "Offset", "chartOffset");
        }
        if (!Double.isFinite(off)) off = 0.0;
        return off / 1000.0;
    }

    private static List<BpmPoint> parseBpmList(JsonNode root) {
        ArrayNode arr = getAsArray(root, "BPMList", "bpmList", "BpmList");
        if (arr == null) return new ArrayList<>();
        List<BpmPoint> out = new ArrayList<>();
        for (int i = 0; i < arr.size(); i++) {
            JsonNode o = asObj(arr.get(i));
            if (o == null) continue;
            double beat = parseBeat(getFirst(o, "startTime", "time", "beat"));
            double bpm = getAsDouble(o, Double.NaN, "bpm", "BPM");
            if (!Double.isFinite(bpm) || bpm <= 0.0) continue;
            if (!Double.isFinite(beat)) beat = 0.0;
            out.add(new BpmPoint(beat, bpm));
        }
        return out;
    }

    private static List<LineEvent> parseAlphaEvents(JsonNode obj, BpmConverter bpmConv, double lineBpm) {
        List<LineEvent> ev = parseLineEvents(obj, bpmConv, lineBpm, "alphaEvents", "opacityEvents");
        if (!ev.isEmpty()) {
            return ev;
        }
        ev = parseLineEvents(obj, bpmConv, lineBpm, "judgeLineDisappearEvents", "disappearEvents");
        if (!ev.isEmpty()) {
            for (LineEvent e : ev) {
                e.start = 1.0 - e.start;
                e.end = 1.0 - e.end;
            }
            return ev;
        }
        return new ArrayList<>();
    }

    private static List<LineEvent> parseLineEvents(JsonNode obj,
                                                   BpmConverter bpmConv,
                                                   double lineBpm,
                                                   String... keys) {
        boolean isAlpha = false;
        for (String k : keys) {
            if (k == null) continue;
            String lk = k.toLowerCase();
            if (lk.contains("alpha") || lk.contains("disappear") || lk.contains("opacity")) {
                isAlpha = true;
                break;
            }
        }

        ArrayNode arr = null;
        for (String k : keys) {
            arr = getAsArray(obj, k);
            if (arr != null) break;
        }
        if (arr == null) return new ArrayList<>();
        List<LineEvent> out = new ArrayList<>();
        for (int i = 0; i < arr.size(); i++) {
            JsonNode e = asObj(arr.get(i));
            if (e == null) continue;

            double sb = parseBeat(getEventTime(e, true));
            double eb = parseBeat(getEventTime(e, false));
            double st = bpmConv.beatToNormBeat(sb, lineBpm);
            double et = bpmConv.beatToNormBeat(eb, lineBpm);
            if (!Double.isFinite(st)) st = 0.0;
            if (!Double.isFinite(et)) et = st;
            if (et < st) et = st;

            double v0 = getAsDouble(e, 0.0, "start", "startValue", "value", "v0");
            double v1 = getAsDouble(e, v0, "end", "endValue", "v1");

            if (isAlpha) {
                if (v0 == -1.0) v0 = -255.0;
                else if (v0 == -2.0) v0 = -510.0;
                if (v1 == -1.0) v1 = -255.0;
                else if (v1 == -2.0) v1 = -510.0;

                if (v0 > 1.5 || v0 < -2.5) v0 /= 255.0;
                if (v1 > 1.5 || v1 < -2.5) v1 /= 255.0;
            }

            LineEvent le = new LineEvent();
            le.startTime = st;
            le.endTime = et;
            le.start = v0;
            le.end = v1;

            int easeType = getAsInt(e, 1, "easingType", "easing", "ease");
            le.easingType = Math.max(0, easeType - 1);
            le.easingLeft = (float) getAsDouble(e, 0.0, "easingLeft", "left", "clipStart");
            le.easingRight = (float) getAsDouble(e, 1.0, "easingRight", "right", "clipEnd");
            le.bezier = getAsBool(e, false, "bezier", "useBezier");
            le.bezierPoints = parseBezierPoints(e);

            out.add(le);
        }
        out.sort(Comparator.comparingDouble(a -> a.startTime));
        return out;
    }

    private static List<MoveEvent> parseMoveEvents(JsonNode layerObj,
                                                   BpmConverter bpmConv,
                                                   double lineBpm) {
        ArrayNode direct = getAsArray(layerObj, "moveEvents", "judgeLineMoveEvents");
        if (direct != null && direct.size() > 0) {
            List<MoveEvent> out = new ArrayList<>();
            for (int i = 0; i < direct.size(); i++) {
                JsonNode e = asObj(direct.get(i));
                if (e == null) continue;

                double sb = parseBeat(getEventTime(e, true));
                double eb = parseBeat(getEventTime(e, false));
                double st = bpmConv.beatToNormBeat(sb, lineBpm);
                double et = bpmConv.beatToNormBeat(eb, lineBpm);
                if (!Double.isFinite(st)) st = 0.0;
                if (!Double.isFinite(et)) et = st;
                if (et < st) et = st;

                double x0;
                double x1;
                double y0;
                double y1;

                double[] sArr = parseVec2(getFirst(e, "start", "startValue"));
                double[] eArr = parseVec2(getFirst(e, "end", "endValue"));
                if (sArr != null && eArr != null) {
                    x0 = sArr[0];
                    y0 = sArr[1];
                    x1 = eArr[0];
                    y1 = eArr[1];
                } else {
                    x0 = getAsDouble(e, 0.5, "start", "startX", "x0");
                    x1 = getAsDouble(e, x0, "end", "endX", "x1");
                    y0 = getAsDouble(e, 0.5, "start2", "startY", "y0");
                    y1 = getAsDouble(e, y0, "end2", "endY", "y1");
                }

                x0 = toXNorm(x0);
                x1 = toXNorm(x1);
                y0 = toYRaw(y0);
                y1 = toYRaw(y1);

                MoveEvent me = new MoveEvent();
                me.startTime = st;
                me.endTime = et;
                me.start = x0;
                me.end = x1;
                me.start2 = y0;
                me.end2 = y1;

                int easeType = getAsInt(e, 1, "easingType", "easing", "ease");
                me.easingType = Math.max(0, easeType - 1);
                me.easingLeft = (float) getAsDouble(e, 0.0, "easingLeft", "left", "clipStart");
                me.easingRight = (float) getAsDouble(e, 1.0, "easingRight", "right", "clipEnd");
                me.bezier = getAsBool(e, false, "bezier", "useBezier");
                me.bezierPoints = parseBezierPoints(e);

                out.add(me);
            }
            out.sort(Comparator.comparingDouble(a -> a.startTime));
            return out;
        }

        List<LineEvent> xs = parseLineEvents(layerObj, bpmConv, lineBpm,
                "moveXEvents", "judgeLineMoveXEvents", "xEvents", "moveX");
        List<LineEvent> ys = parseLineEvents(layerObj, bpmConv, lineBpm,
                "moveYEvents", "judgeLineMoveYEvents", "yEvents", "moveY");

        if (xs == null) xs = new ArrayList<>();
        if (ys == null) ys = new ArrayList<>();

        ensureCoveragePos(xs, 0.0);
        ensureCoveragePos(ys, 0.0);

        if (canPair(xs, ys)) {
            List<MoveEvent> out = new ArrayList<>();
            for (int i = 0; i < xs.size(); i++) {
                LineEvent xe = xs.get(i);
                LineEvent ye = ys.get(i);

                MoveEvent me = new MoveEvent();
                me.startTime = xe.startTime;
                me.endTime = xe.endTime;
                me.start = toXNorm(xe.start);
                me.end = toXNorm(xe.end);
                me.start2 = toYRaw(ye.start);
                me.end2 = toYRaw(ye.end);

                me.easingType = xe.easingType;
                me.easingLeft = xe.easingLeft;
                me.easingRight = xe.easingRight;
                me.bezier = xe.bezier;
                me.bezierPoints = xe.bezierPoints;
                out.add(me);
            }
            return out;
        }

        Set<Double> times = new HashSet<>();
        for (LineEvent e : xs) {
            times.add(e.startTime);
            times.add(e.endTime);
        }
        for (LineEvent e : ys) {
            times.add(e.startTime);
            times.add(e.endTime);
        }
        List<Double> tList = new ArrayList<>(times);
        Collections.sort(tList);
        if (tList.isEmpty()) {
            return defaultMove();
        }
        if (tList.get(0) > 0.0) tList.add(0, 0.0);
        double max = tList.get(tList.size() - 1);
        if (max < 1e8) {
            tList.add(1e9);
        }
        List<MoveEvent> out = new ArrayList<>();
        for (int i = 0; i < tList.size() - 1; i++) {
            double t0 = tList.get(i);
            double t1 = tList.get(i + 1);
            if (t1 <= t0) continue;
            double x0 = toXNorm(EventUtils.getEventVal(t0, xs));
            double x1 = toXNorm(EventUtils.getEventVal(t1, xs));
            double y0 = toYRaw(EventUtils.getEventVal(t0, ys));
            double y1 = toYRaw(EventUtils.getEventVal(t1, ys));
            MoveEvent me = new MoveEvent();
            me.startTime = t0;
            me.endTime = t1;
            me.start = x0;
            me.end = x1;
            me.start2 = y0;
            me.end2 = y1;
            out.add(me);
        }
        return out;
    }

    /**
     * Parse speed events with easing, splitting into 0.125-beat (4 tick) segments.
     * Speed values divided by 4.5 for RPE normalization.
     */
    private static List<SpeedEvent> parseSpeedEvents(JsonNode obj,
                                                     BpmConverter bpmConv,
                                                     double lineBpm) {
        ArrayNode arr = getAsArray(obj, "speedEvents", "judgeLineSpeedEvents", "speed");
        if (arr == null) return null;

        final double stepBeat = 4.0;

        class Raw {
            double sb;
            double eb;
            double v0;
            double v1;
            int easingType;
            float easingLeft;
            float easingRight;
            boolean bezier;
            float[] bezierPoints;
        }

        List<Raw> raw = new ArrayList<>();
        for (int i = 0; i < arr.size(); i++) {
            JsonNode e = asObj(arr.get(i));
            if (e == null) continue;

            double sb = parseBeat(getEventTime(e, true));
            double eb = Double.NaN;
            JsonNode ebEl = getEventTime(e, false);
            if (ebEl != null && !ebEl.isNull()) {
                eb = parseBeat(ebEl);
            }

            double v0 = getAsDouble(e, Double.NaN, "start", "startValue", "value", "speed");
            if (!Double.isFinite(v0)) v0 = getAsDouble(e, 1.0, "v0", "s");
            double v1 = getAsDouble(e, Double.NaN, "end", "endValue", "endSpeed", "v1");
            if (!Double.isFinite(v0)) v0 = 1.0;
            if (!Double.isFinite(v1)) v1 = v0;

            v0 /= 4.5;
            v1 /= 4.5;

            int easeTypeRaw = getAsInt(e, 1, "easingType", "easing", "ease");
            int easeType = Math.max(0, easeTypeRaw - 1);

            Raw r = new Raw();
            r.sb = Double.isFinite(sb) ? sb : 0.0;
            r.eb = eb;
            r.v0 = v0;
            r.v1 = v1;
            r.easingType = easeType;
            r.easingLeft = (float) getAsDouble(e, 0.0, "easingLeft", "left", "clipStart");
            r.easingRight = (float) getAsDouble(e, 1.0, "easingRight", "right", "clipEnd");
            r.bezier = getAsBool(e, false, "bezier", "useBezier");
            r.bezierPoints = parseBezierPoints(e);
            raw.add(r);
        }

        raw.sort(Comparator.comparingDouble(a -> a.sb));

        for (int i = 0; i < raw.size(); i++) {
            Raw r = raw.get(i);
            if (!Double.isFinite(r.eb)) {
                double next = (i + 1 < raw.size()) ? raw.get(i + 1).sb : 1e9;
                r.eb = next;
            }
            if (r.eb < r.sb) r.eb = r.sb;
        }

        List<SpeedEvent> out = new ArrayList<>();
        for (Raw r : raw) {
            double sb = r.sb;
            double eb = r.eb;
            if (!(eb > sb + 1e-9)) continue;

            if (Math.abs(r.v1 - r.v0) < 1e-9) {
                SpeedEvent se = new SpeedEvent();
                se.startTime = bpmConv.beatToNormBeat(sb, lineBpm);
                se.endTime = bpmConv.beatToNormBeat(eb, lineBpm);
                se.value = r.v0;
                se.endValue = r.v0;
                out.add(se);
                continue;
            }

            double dur = eb - sb;
            int timeCount = (int) Math.ceil(dur / stepBeat);
            for (int i = 0; i < timeCount; i++) {
                double curBeat = sb + i * stepBeat;
                double nextBeat = Math.min(eb, curBeat + stepBeat);
                if (nextBeat <= curBeat + 1e-9) continue;

                double p = (nextBeat - sb) / dur;
                p = Easing.clamp01(p);

                double val = rpeValueAt(r.v0, r.v1, p, r.easingType, r.easingLeft, r.easingRight,
                        r.bezier, r.bezierPoints);

                SpeedEvent se = new SpeedEvent();
                se.startTime = bpmConv.beatToNormBeat(curBeat, lineBpm);
                se.endTime = bpmConv.beatToNormBeat(nextBeat, lineBpm);
                se.value = val;
                se.endValue = val;
                out.add(se);
            }
        }

        out.sort(Comparator.comparingDouble(a -> a.startTime));
        return out;
    }

    private static double rpeValueAt(double start, double end, double p,
                                     int easingType, float easingLeft, float easingRight,
                                     boolean bezier, float[] bezierPoints) {
        p = Easing.clamp01(p);
        if (bezier && bezierPoints != null && bezierPoints.length >= 4) {
            double bStart = Easing.cubicBezierYforX(1.0 - p, bezierPoints[0], bezierPoints[1], bezierPoints[2], bezierPoints[3]);
            double bEnd = Easing.cubicBezierYforX(p, bezierPoints[0], bezierPoints[1], bezierPoints[2], bezierPoints[3]);
            return start * bStart + end * bEnd;
        }

        double l = Float.isFinite(easingLeft) ? easingLeft : 0f;
        double r = Float.isFinite(easingRight) ? easingRight : 1f;
        l = Easing.clamp01(l);
        r = Easing.clamp01(r);

        double timePercentEnd = p;
        double timePercentStart = 1.0 - p;

        double arg = l * timePercentStart + r * timePercentEnd;
        double eArg = Easing.apply(easingType, arg);
        double eL = Easing.apply(easingType, l);
        double eR = Easing.apply(easingType, r);
        double denom = eR - eL;
        double eased = (Math.abs(denom) < 1e-9) ? 0.0 : Easing.clamp01((eArg - eL) / denom);
        return start * (1.0 - eased) + end * eased;
    }

    private static void parseScaleIntoLine(JsonNode ext,
                                                    BpmConverter bpmConv,
                                                    double lineBpm,
                                                    JudgeLine line,
                                                    JsonNode lineObj) {
        List<LineEvent> sx = parseLineEvents(ext, bpmConv, lineBpm, "scaleXEvents", "sxEvents");
        List<LineEvent> sy = parseLineEvents(ext, bpmConv, lineBpm, "scaleYEvents", "syEvents");

        line.rpeTextureScaleInPixels = false;

        if ((sx != null && !sx.isEmpty()) || (sy != null && !sy.isEmpty())) {
            ensureCoveragePos(sx, 1.0);
            ensureCoveragePos(sy, 1.0);
            line.judgeLineScaleXEvents = sx;
            line.judgeLineScaleYEvents = sy;
            return;
        }

        ArrayNode arr = getAsArray(ext, "scaleEvents");
        if (arr == null) return;
        List<MoveEvent> out = new ArrayList<>();
        for (int i = 0; i < arr.size(); i++) {
            JsonNode e = asObj(arr.get(i));
            if (e == null) continue;
            double sb = parseBeat(getEventTime(e, true));
            double eb = parseBeat(getEventTime(e, false));
            double st = bpmConv.beatToNormBeat(sb, lineBpm);
            double et = bpmConv.beatToNormBeat(eb, lineBpm);

            double[] sArr = parseVec2(getFirst(e, "start", "startValue"));
            double[] eArr = parseVec2(getFirst(e, "end", "endValue"));
            if (sArr == null || eArr == null) continue;

            MoveEvent me = new MoveEvent();
            me.startTime = st;
            me.endTime = et;
            me.start = sArr[0];
            me.end = eArr[0];
            me.start2 = sArr[1];
            me.end2 = eArr[1];

            out.add(me);
        }
        out.sort(Comparator.comparingDouble(a -> a.startTime));
        ensureCoverageMove(out, 1.0, 1.0);
        line.judgeLineScaleEvents = out;
    }

    private static List<LineEvent> parseInclineEvents(JsonNode ext,
                                                     BpmConverter bpmConv,
                                                     double lineBpm) {
        List<LineEvent> raw = parseLineEvents(ext, bpmConv, lineBpm,
                "inclineEvents", "tiltEvents", "skewEvents");
        if (raw == null || raw.isEmpty()) return null;
        for (LineEvent e : raw) {
            e.start = Math.toRadians(e.start);
            e.end = Math.toRadians(e.end);
        }
        ensureCoveragePos(raw, 0.0);
        raw.sort(Comparator.comparingDouble(a -> a.startTime));
        return raw;
    }

    private static List<GifEvent> parseGifEvents(JsonNode ext,
                                                  BpmConverter bpmConv,
                                                  double lineBpm) {
        ArrayNode arr = getAsArray(ext, "gifEvents");
        if (arr == null || arr.size() == 0) return null;

        List<GifEvent> out = new ArrayList<>();
        for (int i = 0; i < arr.size(); i++) {
            JsonNode e = asObj(arr.get(i));
            if (e == null) continue;

            double sbBeat = parseBeat(getEventTime(e, true));
            double ebBeat = parseBeat(getEventTime(e, false));
            if (!Double.isFinite(sbBeat) || !Double.isFinite(ebBeat)) continue;

            double start = getAsDouble(e, 0.0, "start", "startValue");
            double end = getAsDouble(e, 1.0, "end", "endValue");
            int easingType = Math.max(0, getAsInt(e, 1, "easingType", "easing", "ease") - 1);
            double easingLeft = getAsDouble(e, 0.0, "easingLeft", "left");
            double easingRight = getAsDouble(e, 1.0, "easingRight", "right");

            double st = bpmConv.beatToNormBeat(sbBeat, lineBpm);
            double et = bpmConv.beatToNormBeat(ebBeat, lineBpm);
            if (!(et > st)) et = st + 1e-6;

            GifEvent ge = new GifEvent();
            ge.startTime = st;
            ge.endTime = et;
            ge.start = start;
            ge.end = end;
            ge.easingType = easingType;
            ge.easingLeft = (float) easingLeft;
            ge.easingRight = (float) easingRight;
            out.add(ge);
        }

        if (out.isEmpty()) return null;
        out.sort(Comparator.comparingDouble(a -> a.startTime));
        return out;
    }

    private static double calcEasePercent(double currentBeat,
                                          double startBeat,
                                          double endBeat,
                                          int easingType,
                                          double easingLeft,
                                          double easingRight) {
        double dur = endBeat - startBeat;
        if (!(dur > 0)) return 0.0;
        double t = (currentBeat - startBeat) / dur;
        if (t < 0.0) t = 0.0;
        if (t > 1.0) t = 1.0;
        double timePercentEnd = t;
        double timePercentStart = 1.0 - t;
        double p = easingLeft * timePercentStart + easingRight * timePercentEnd;

        double easeStart = Easing.apply(easingType, easingLeft);
        double easeEnd = Easing.apply(easingType, easingRight);
        double easeP = Easing.apply(easingType, p);
        double denom = easeEnd - easeStart;
        if (Math.abs(denom) < 1e-12) return timePercentEnd;
        double out = (easeP - easeStart) / denom;
        if (Double.isNaN(out) || Double.isInfinite(out)) return timePercentEnd;
        return out;
    }

    private static float quantize255(double v01) {
        int iv = (int) Math.round(v01 * 255.0);
        if (iv < 0) iv = 0;
        if (iv > 255) iv = 255;
        return iv / 255f;
    }

    private static boolean sameColor(ColorEvent last, float r, float g, float b) {
        if (last == null) return false;
        return Math.abs(last.endR - r) < 1e-6f
                && Math.abs(last.endG - g) < 1e-6f
                && Math.abs(last.endB - b) < 1e-6f;
    }

    private static List<ColorEvent> parseColorEvents(JsonNode ext,
                                                    BpmConverter bpmConv,
                                                    double lineBpm) {
        ArrayNode arr = getAsArray(ext, "colorEvents", "colourEvents", "tintEvents");
        if (arr == null) return null;

        final double stepBeat = 4.0;
        ArrayList<ColorEvent> out = new ArrayList<>();

        for (int i = 0; i < arr.size(); i++) {
            JsonNode e = asObj(arr.get(i));
            if (e == null) continue;
            double sbBeat = parseBeat(getEventTime(e, true));
            double ebBeat = parseBeat(getEventTime(e, false));
            if (!Double.isFinite(sbBeat) || !Double.isFinite(ebBeat)) continue;

            float[] c0 = parseRgb(getFirst(e, "start", "startValue", "rgb", "color"));
            float[] c1 = parseRgb(getFirst(e, "end", "endValue"));
            if (c0 == null) continue;
            if (c1 == null) c1 = c0;

            int easingType = Math.max(0, getAsInt(e, 1, "easingType", "easing", "ease") - 1);
            double easingLeft = getAsDouble(e, 0.0, "easingLeft", "left");
            double easingRight = getAsDouble(e, 1.0, "easingRight", "right");

            if (ebBeat <= sbBeat) {
                double tNorm = bpmConv.beatToNormBeat(sbBeat, lineBpm);
                float r = quantize255(c0[0]);
                float g = quantize255(c0[1]);
                float b = quantize255(c0[2]);
                ColorEvent ce = new ColorEvent();
                ce.startTime = tNorm;
                ce.endTime = tNorm;
                ce.startR = r;
                ce.startG = g;
                ce.startB = b;
                ce.endR = r;
                ce.endG = g;
                ce.endB = b;
                out.add(ce);
                continue;
            }

            for (double cur = sbBeat; cur < ebBeat; cur += stepBeat) {
                double next = Math.min(ebBeat, cur + stepBeat);
                double tp = calcEasePercent(cur, sbBeat, ebBeat, easingType, easingLeft, easingRight);

                float r = quantize255(c0[0] + (c1[0] - c0[0]) * tp);
                float g = quantize255(c0[1] + (c1[1] - c0[1]) * tp);
                float b = quantize255(c0[2] + (c1[2] - c0[2]) * tp);

                double st = bpmConv.beatToNormBeat(cur, lineBpm);
                double et = bpmConv.beatToNormBeat(next, lineBpm);
                if (!(et > st)) continue;

                ColorEvent last = out.isEmpty() ? null : out.get(out.size() - 1);
                if (last != null && Math.abs(last.endTime - st) < 1e-6 && sameColor(last, r, g, b)) {
                    last.endTime = et;
                    last.startR = last.endR = r;
                    last.startG = last.endG = g;
                    last.startB = last.endB = b;
                } else {
                    ColorEvent ce = new ColorEvent();
                    ce.startTime = st;
                    ce.endTime = et;
                    ce.startR = r;
                    ce.startG = g;
                    ce.startB = b;
                    ce.endR = r;
                    ce.endG = g;
                    ce.endB = b;
                    out.add(ce);
                }
            }

            double endNorm = bpmConv.beatToNormBeat(ebBeat, lineBpm);
            float er = quantize255(c1[0]);
            float eg = quantize255(c1[1]);
            float eb = quantize255(c1[2]);
            ColorEvent ceEnd = new ColorEvent();
            ceEnd.startTime = endNorm;
            ceEnd.endTime = endNorm;
            ceEnd.startR = er;
            ceEnd.startG = eg;
            ceEnd.startB = eb;
            ceEnd.endR = er;
            ceEnd.endG = eg;
            ceEnd.endB = eb;
            out.add(ceEnd);
        }

        if (out.isEmpty()) return null;
        out.sort(Comparator.comparingDouble(a -> a.startTime));
        return out;
    }

    private static List<TextEvent> parseTextEvents(JsonNode ext,
                                                   BpmConverter bpmConv,
                                                   double lineBpm) {
        ArrayNode arr = getAsArray(ext, "textEvents", "labelEvents");
        if (arr == null) return null;

        ArrayList<TextEvent> out = new ArrayList<>();

        for (int i = 0; i < arr.size(); i++) {
            JsonNode e = asObj(arr.get(i));
            if (e == null) continue;

            double sbBeat = parseBeat(getEventTime(e, true));
            double ebBeat = parseBeat(getEventTime(e, false));
            if (!Double.isFinite(sbBeat) || !Double.isFinite(ebBeat)) continue;

            String startText = getAsString(e, null,
                    "start", "startValue", "startText",
                    "text", "value", "label");
            if (startText == null) continue;
            String endText = getAsString(e, startText, "end", "endValue", "endText");
            if (endText == null) endText = startText;

            int easingType = Math.max(0, getAsInt(e, 1, "easingType", "easing", "ease") - 1);
            double easingLeft = getAsDouble(e, 0.0, "easingLeft", "left");
            double easingRight = getAsDouble(e, 1.0, "easingRight", "right");

            double st = bpmConv.beatToNormBeat(sbBeat, lineBpm);
            double et = bpmConv.beatToNormBeat(ebBeat, lineBpm);
            if (!(et > st)) et = st + 1e-6;

            TextEvent te = new TextEvent();
            te.startTime = st;
            te.endTime = et;
            te.text = startText;
            te.easingType = easingType;
            te.easingLeft = (float) easingLeft;
            te.easingRight = (float) easingRight;

            if (!endText.equals(startText)) {
                te.endText = endText;
            }
            out.add(te);
        }

        if (out.isEmpty()) return null;
        out.sort(Comparator.comparingDouble(a -> a.startTime));
        return out;
    }

    private static void parseNotesIntoLine(JsonNode lineObj,
                                           BpmConverter bpmConv,
                                           double lineBpm,
                                           JudgeLine line) {
        line.notesAbove = new ArrayList<>();
        line.notesBelow = new ArrayList<>();

        ArrayNode above = getAsArray(lineObj, "notesAbove", "NotesAbove");
        ArrayNode below = getAsArray(lineObj, "notesBelow", "NotesBelow");
        if (above != null || below != null) {
            if (above != null) {
                for (int i = 0; i < above.size(); i++) {
                    Note n = parseNote(asObj(above.get(i)), bpmConv, lineBpm);
                    if (n != null) {
                        n.floorPosition = EventUtils.getFloorPosition(n.time, line.speedEvents);
                        line.notesAbove.add(n);
                    }
                }
            }
            if (below != null) {
                for (int i = 0; i < below.size(); i++) {
                    Note n = parseNote(asObj(below.get(i)), bpmConv, lineBpm);
                    if (n != null) {
                        n.floorPosition = EventUtils.getFloorPosition(n.time, line.speedEvents);
                        line.notesBelow.add(n);
                    }
                }
            }
            return;
        }

        ArrayNode notes = getAsArray(lineObj, "notes", "noteList", "Notes");
        if (notes == null) return;
        for (int i = 0; i < notes.size(); i++) {
            JsonNode no = asObj(notes.get(i));
            if (no == null) continue;
            boolean isAbove = parseRpeNoteAbove(no, true);
            Note n = parseNote(no, bpmConv, lineBpm);
            if (n == null) continue;
            n.floorPosition = EventUtils.getFloorPosition(n.time, line.speedEvents);
            if (isAbove) line.notesAbove.add(n); else line.notesBelow.add(n);
        }
    }

    private static boolean parseRpeNoteAbove(JsonNode noteObj, boolean def) {
        if (noteObj == null) return def;
        if (noteObj.has("isAbove")) {
            return getAsBool(noteObj, def, "isAbove");
        }
        if (noteObj.has("above")) {
            return parseRpeAboveFlag(noteObj.get("above"), def);
        }
        if (noteObj.has("aboveLine")) {
            return getAsBool(noteObj, def, "aboveLine");
        }
        return def;
    }

    private static boolean parseRpeAboveFlag(JsonNode e, boolean def) {
        if (e == null || e.isNull()) return def;
        try {
            if (e.isBoolean()) {
                return e.asBoolean();
            }
            if (e.isNumber()) {
                int v;
                try {
                    v = e.asInt();
                } catch (Exception ex) {
                    v = (int) Math.round(e.asDouble());
                }
                if (v == 1) return true;
                if (v == 2) return false;
                return v != 0;
            }
            if (e.isTextual()) {
                String s = e.asText();
                if (s == null) return def;
                s = s.trim();
                if (s.isEmpty()) return def;
                if ("true".equalsIgnoreCase(s)) return true;
                if ("false".equalsIgnoreCase(s)) return false;
                try {
                    int v = Integer.parseInt(s);
                    if (v == 1) return true;
                    if (v == 2) return false;
                    return v != 0;
                } catch (Exception ignore) {
                }
            }
        } catch (Exception ignore) {
        }
        return def;
    }

    private static Note parseNote(JsonNode o, BpmConverter bpmConv, double lineBpm) {
        if (o == null) return null;
        int type = getAsInt(o, 0, "type", "noteType", "note");
        if (type < 1 || type > 4) {
            if (type >= 0 && type <= 3) type = type + 1;
        }
        if (type < 1 || type > 4) return null;

        switch (type) {
            case 1: type = GameConstants.NOTE_TAP; break;
            case 2: type = GameConstants.NOTE_HOLD; break;
            case 3: type = GameConstants.NOTE_FLICK; break;
            case 4: type = GameConstants.NOTE_DRAG; break;
            default: break;
        }

        double timeBeat = parseBeat(getFirst(o, "time", "startTime", "t"));
        double time = bpmConv.beatToNormBeat(timeBeat, lineBpm);

        double holdTime = 0.0;
        if (type == GameConstants.NOTE_HOLD) {
            if (o.has("holdTime")) {
                double durBeat = parseBeat(o.get("holdTime"));
                double secStart = bpmConv.beatToSec(timeBeat);
                double secEnd = bpmConv.beatToSec(timeBeat + durBeat);
                double durSec = Math.max(0.0, secEnd - secStart);
                holdTime = durSec / (GameConstants.PGRBEAT / lineBpm);
            } else if (o.has("endTime")) {
                double endBeat = parseBeat(o.get("endTime"));
                double secStart = bpmConv.beatToSec(timeBeat);
                double secEnd = bpmConv.beatToSec(endBeat);
                double durSec = Math.max(0.0, secEnd - secStart);
                holdTime = durSec / (GameConstants.PGRBEAT / lineBpm);
            } else if (o.has("hold") || o.has("duration")) {
                double durBeatRaw = getAsDouble(o, 0.0, "hold", "duration");
                double durBeat = durBeatRaw * 32.0;
                double secStart = bpmConv.beatToSec(timeBeat);
                double secEnd = bpmConv.beatToSec(timeBeat + durBeat);
                double durSec = Math.max(0.0, secEnd - secStart);
                holdTime = durSec / (GameConstants.PGRBEAT / lineBpm);
            }
        }

        Note n = new Note();
        n.type = type;
        n.time = time;
        n.holdTime = holdTime;
        n.speed = getAsDouble(o, 1.0, "speed", "noteSpeed");
        if (!Double.isFinite(n.speed)) n.speed = 1.0;
        n.rpeGlobalBeat = timeBeat;
        n.positionX = toNotePosX(getAsDouble(o, 0.0, "positionX", "x", "posX"));

        n.isFake = getAsBool(o, false, "isFake", "fake");

        double size = getAsDouble(o, 1.0, "size", "width", "xScale");
        if (!Double.isFinite(size) || size <= 0.0) size = 1.0;
        n.size = 1f;
        n.xScale = (float) size;

        double a = getAsDouble(o, 1.0, "alpha", "opacity");
        if (!Double.isFinite(a)) a = 1.0;
        if (a > 1.5 || a < -2.5) a /= 255.0;
        if (a < 0.0) a = 0.0;
        if (a > 1.0) a = 1.0;
        n.alpha = (float) a;

        n.visibleTime = (float) getAsDouble(o, -1.0, "visibleTime");
        if (Float.isFinite(n.visibleTime) && n.visibleTime >= 999999f) {
            n.visibleTime = -1f;
        }

        double yo = getAsDouble(o, 0.0, "yOffset", "offsetY");
        if (!Double.isFinite(yo)) yo = 0.0;
        yo /= 900.0;
        n.yOffset = (float) yo;

        double ja = getAsDouble(o, 1.0, "judgeArea");
        if (!Double.isFinite(ja) || ja <= 0.0) ja = 1.0;
        n.judgeArea = (float) ja;

        if (!Float.isFinite(n.xScale) || n.xScale <= 0f) n.xScale = 1f;
        if (!Float.isFinite(n.alpha)) n.alpha = 1f;
        if (n.alpha < 0f) n.alpha = 0f;
        if (n.alpha > 1f) n.alpha = 1f;
        if (!Float.isFinite(n.visibleTime)) n.visibleTime = -1f;
        if (!Float.isFinite(n.yOffset)) n.yOffset = 0f;

        return n;
    }

    private static final class RpeNoteControlRaw {
        final float x;
        final float value;
        final int easing;

        RpeNoteControlRaw(float x, float value, int easing) {
            this.x = x;
            this.value = value;
            this.easing = easing;
        }
    }

    /**
     * Parse RePhiEdit note controls (alphaControl/sizeControl/posControl) into compact step functions.
     */
    private static List<JudgeLine.NoteControlPoint> parseNoteControls(
            JsonNode lineObj,
            String key,
            String valueKey,
            float defaultValue
    ) {
        ArrayNode arr = getAsArray(lineObj, key);
        if (arr == null || arr.size() <= 0) return null;

        List<RpeNoteControlRaw> raw = new ArrayList<>();
        for (int i = 0; i < arr.size(); i++) {
            JsonNode o = asObj(arr.get(i));
            if (o == null) continue;
            float x = (float) getAsDouble(o, 0.0, "x", "X");
            float v = (float) getAsDouble(o, defaultValue, valueKey, "value", "v");
            int easing = getAsInt(o, 1, "easing", "ease", "easingType");
            int easeIdx = Math.max(0, Math.min(28, easing - 1));
            raw.add(new RpeNoteControlRaw(x, v, easeIdx));
        }
        if (raw.isEmpty()) return null;

        if (raw.size() == 2
                && Math.abs(raw.get(0).x) < 1e-6f
                && raw.get(1).x >= 10000f
                && Math.abs(raw.get(0).value - defaultValue) < 1e-6f
                && Math.abs(raw.get(1).value - defaultValue) < 1e-6f) {
            return null;
        }

        raw.sort((a, b) -> Float.compare(a.x, b.x));

        List<JudgeLine.NoteControlPoint> out = new ArrayList<>(raw.size());
        for (int i = 0; i < raw.size(); i++) {
            RpeNoteControlRaw c = raw.get(i);
            out.add(new JudgeLine.NoteControlPoint(c.x, c.value, c.easing));
        }
        return out.isEmpty() ? null : out;
    }

    private static List<SpeedEvent> mergeSpeedLayers(List<List<SpeedEvent>> layers) {
        if (layers == null || layers.isEmpty()) return null;
        if (layers.size() == 1) return layers.get(0);

        Set<Double> t = new HashSet<>();
        t.add(0.0);
        double maxEnd = 0.0;
        for (List<SpeedEvent> l : layers) {
            if (l == null) continue;
            for (SpeedEvent e : l) {
                if (e == null) continue;
                t.add(e.startTime);
                t.add(e.endTime);
                if (e.endTime > maxEnd) maxEnd = e.endTime;
            }
        }
        if (maxEnd < 1e8) maxEnd = 1e9;
        t.add(maxEnd);

        List<Double> times = new ArrayList<>(t);
        Collections.sort(times);
        if (times.size() < 2) return layers.get(0);

        List<SpeedEvent> out = new ArrayList<>();
        for (int i = 0; i < times.size() - 1; i++) {
            double t0 = times.get(i);
            double t1 = times.get(i + 1);
            if (t1 <= t0) continue;

            double v = 0.0;
            for (List<SpeedEvent> l : layers) {
                v += speedAt(l, t0);
            }

            if (!out.isEmpty()) {
                SpeedEvent last = out.get(out.size() - 1);
                if (Math.abs(last.value - v) < 1e-9 && Math.abs(last.endTime - t0) < 1e-6) {
                    last.endTime = t1;
                    last.endValue = v;
                    continue;
                }
            }

            SpeedEvent se = new SpeedEvent();
            se.startTime = t0;
            se.endTime = t1;
            se.value = v;
            se.endValue = v;
            out.add(se);
        }

        out.sort(Comparator.comparingDouble(a -> a.startTime));
        return out;
    }

    private static double speedAt(List<SpeedEvent> events, double t) {
        if (events == null || events.isEmpty()) return 0.0;
        int i = EventUtils.findEvent(t, events, new EventUtils.TimeAccessor<SpeedEvent>() {
            @Override public double start(SpeedEvent e) { return e.startTime; }
            @Override public double end(SpeedEvent e) { return e.endTime; }
        });
        if (i < 0) {
            if (t < events.get(0).startTime) {
                SpeedEvent e0 = events.get(0);
                return e0.value;
            }
            SpeedEvent last = events.get(events.size() - 1);
            return Double.isFinite(last.endValue) ? last.endValue : last.value;
        }
        SpeedEvent e = events.get(i);
        double dur = e.endTime - e.startTime;
        if (dur <= 1e-9) {
            return Double.isFinite(e.endValue) ? e.endValue : e.value;
        }
        double p = (t - e.startTime) / dur;
        if (p < 0.0) p = 0.0;
        if (p > 1.0) p = 1.0;
        double v0 = e.value;
        double v1 = Double.isFinite(e.endValue) ? e.endValue : e.value;
        return v0 + (v1 - v0) * p;
    }

    private static void ensureCoverageRotate(List<LineEvent> list) {
        ensureCoverage1D(list, 0.0, true);
    }

    private static void ensureCoverageAlpha(List<LineEvent> list) {
        ensureCoverage1D(list, 0.0, false);
    }

    private static void ensureCoveragePos(List<LineEvent> list, double def) {
        ensureCoverage1D(list, def, true);
    }

    private static void ensureCoverage1D(List<LineEvent> list, double defValue, boolean extrapolate) {
        if (list == null) return;
        if (list.isEmpty()) {
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
            double val = extrapolate ? first.start : defValue;
            pre.start = val;
            pre.end = val;
            list.add(0, pre);
        }
    }

    private static void ensureCoverageMove(List<MoveEvent> list) {
        ensureCoverageMove(list, 0.0, 0.0);
    }

    private static void ensureCoverageMove(List<MoveEvent> list, double def1, double def2) {
        if (list == null) return;
        if (list.isEmpty()) {
            MoveEvent e = new MoveEvent();
            e.startTime = 0.0;
            e.endTime = 1e9;
            e.start = def1;
            e.end = def1;
            e.start2 = def2;
            e.end2 = def2;
            list.add(e);
            return;
        }
        list.sort(Comparator.comparingDouble(a -> a.startTime));
        MoveEvent first = list.get(0);
        if (first.startTime > 0.0) {
            MoveEvent pre = new MoveEvent();
            pre.startTime = 0.0;
            pre.endTime = first.startTime;
            pre.start = first.start;
            pre.end = first.start;
            pre.start2 = first.start2;
            pre.end2 = first.start2;
            list.add(0, pre);
        }
    }

    private static void ensureCoverageSpeed(List<SpeedEvent> list) {
        if (list == null) return;
        if (list.isEmpty()) {
            list.addAll(defaultSpeed());
            return;
        }
        list.sort(Comparator.comparingDouble(a -> a.startTime));
        SpeedEvent first = list.get(0);
        if (first.startTime > 0.0) {
            SpeedEvent pre = new SpeedEvent();
            pre.startTime = 0.0;
            pre.endTime = first.startTime;
            pre.value = first.value;
            pre.endValue = first.value;
            list.add(0, pre);
        }
        for (int i = 0; i + 1 < list.size(); i++) {
            SpeedEvent cur = list.get(i);
            SpeedEvent next = list.get(i + 1);
            cur.endTime = next.startTime;
            cur.endValue = cur.value;
        }
        SpeedEvent last = list.get(list.size() - 1);
        last.endTime = 1e9;
        last.endValue = last.value;
    }

    private static List<MoveEvent> defaultMove() {
        MoveEvent me = new MoveEvent();
        me.startTime = 0.0;
        me.endTime = 1e9;
        me.start = 0.0;
        me.end = 0.0;
        me.start2 = 0.0;
        me.end2 = 0.0;
        List<MoveEvent> out = new ArrayList<>();
        out.add(me);
        return out;
    }

    private static List<SpeedEvent> defaultSpeed() {
        SpeedEvent se = new SpeedEvent();
        se.startTime = 0.0;
        se.endTime = 1e9;
        se.value = 1.0;
        se.endValue = 1.0;
        List<SpeedEvent> out = new ArrayList<>();
        out.add(se);
        return out;
    }

    private static boolean canPair(List<LineEvent> a, List<LineEvent> b) {
        if (a == null || b == null) return false;
        if (a.size() != b.size()) return false;
        for (int i = 0; i < a.size(); i++) {
            LineEvent x = a.get(i);
            LineEvent y = b.get(i);
            if (x == null || y == null) return false;
            if (Math.abs(x.startTime - y.startTime) > 1e-6) return false;
            if (Math.abs(x.endTime - y.endTime) > 1e-6) return false;
            if (x.easingType != y.easingType) return false;
            if (Math.abs(x.easingLeft - y.easingLeft) > 1e-6) return false;
            if (Math.abs(x.easingRight - y.easingRight) > 1e-6) return false;
            if (x.bezier != y.bezier) return false;
        }
        return true;
    }

    private static ArrayNode selectJudgeLineArray(JsonNode root) {
        ArrayNode arr = getAsArray(root, "judgeLineList", "JudgeLineList", "judgeLines", "judgeLinesList");
        if (looksLikeJudgeLineArray(arr)) return arr;

        ArrayNode maybeLines = getAsArray(root, "lines");
        if (looksLikeJudgeLineArray(maybeLines)) return maybeLines;

        return arr != null ? arr : maybeLines;
    }

    private static boolean looksLikeJudgeLineArray(ArrayNode arr) {
        if (arr == null || arr.size() == 0) return false;
        JsonNode first = asObj(arr.get(0));
        if (first == null) return false;
        return first.has("eventLayers")
                || first.has("notesAbove") || first.has("notesBelow") || first.has("notes")
                || first.has("judgeLineMoveEvents") || first.has("judgeLineRotateEvents")
                || first.has("judgeLineDisappearEvents") || first.has("speedEvents")
                || first.has("moveXEvents") || first.has("moveYEvents")
                || first.has("alphaEvents") || first.has("opacityEvents")
                || first.has("rotateEvents")
                || first.has("bpmfactor") || first.has("bpmFactor") || first.has("BPMFactor");
    }

    private static JsonNode asObj(JsonNode el) {
        if (el == null || el.isNull()) return null;
        if (!el.isObject()) return null;
        return el;
    }

    private static ArrayNode getAsArray(JsonNode obj, String... keys) {
        if (obj == null || keys == null) return null;
        for (String k : keys) {
            if (k == null) continue;
            JsonNode e = obj.get(k);
            if (e != null && e.isArray()) return (ArrayNode) e;
        }
        return null;
    }

    private static JsonNode getAsObject(JsonNode obj, String... keys) {
        if (obj == null || keys == null) return null;
        for (String k : keys) {
            if (k == null) continue;
            JsonNode e = obj.get(k);
            if (e != null && e.isObject()) return e;
        }
        return null;
    }

    private static JsonNode getFirst(JsonNode obj, String... keys) {
        if (obj == null || keys == null) return null;
        for (String k : keys) {
            if (k == null) continue;
            if (obj.has(k)) return obj.get(k);
        }
        return null;
    }

    private static double getAsDouble(JsonNode obj, double def, String... keys) {
        JsonNode e = getFirst(obj, keys);
        if (e == null || e.isNull()) return def;
        try {
            if (e.isValueNode() && !e.isNull()) {
                return e.asDouble();
            }
        } catch (Exception ignored) {
        }
        return def;
    }

    private static int getAsInt(JsonNode obj, int def, String... keys) {
        JsonNode e = getFirst(obj, keys);
        if (e == null || e.isNull()) return def;
        try {
            if (e.isValueNode() && !e.isNull()) {
                return e.asInt();
            }
        } catch (Exception ignored) {
        }
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
                if ("true".equalsIgnoreCase(s)) return true;
                if ("false".equalsIgnoreCase(s)) return false;
                try {
                    return Integer.parseInt(s) != 0;
                } catch (Exception ignored) {}
            }
        } catch (Exception ignored) {
        }
        return def;
    }

    private static String getAsString(JsonNode obj, String def, String... keys) {
        JsonNode e = getFirst(obj, keys);
        if (e == null || e.isNull()) return def;
        try {
            if (e.isTextual()) return e.asText();
        } catch (Exception ignored) {
        }
        return def;
    }

    private static String resolvePath(File baseDir, String path) {
        if (path == null) return null;
        String p = path.replace('\\', '/');
        File f = new File(p);
        if (f.isAbsolute()) return f.getAbsolutePath();
        if (baseDir != null) {
            return new File(baseDir, p).getAbsolutePath();
        }
        return f.getAbsolutePath();
    }

    private static double toXNorm(double x) {
        if (!Double.isFinite(x)) return 0.0;
        return x / 1350.0;
    }

    private static double toYRaw(double y) {
        if (!Double.isFinite(y)) return 0.0;
        return y / 900.0;
    }

    private static double toNotePosX(double x) {
        if (!Double.isFinite(x)) return 0.0;
        return x / (670.0 * (9.0 / 80.0));
    }

    /**
     * Parse an RPE beat value: number, or array [a,b,c] meaning a + b/c.
     * RPE uses musical beat units; this project uses tick units (1 beat = 32 ticks), so multiply by 32.
     */
    private static double parseBeat(JsonNode el) {
        if (el == null || el.isNull()) return 0.0;

        double beat = 0.0;
        try {
            if (el.isValueNode() && !el.isNull()) {
                beat = el.asDouble();
            } else if (el.isArray()) {
                ArrayNode a = (ArrayNode) el;
                if (a.size() >= 3) {
                    double A = a.get(0).asDouble();
                    double B = a.get(1).asDouble();
                    double C = a.get(2).asDouble();
                    if (C == 0.0) {
                        beat = A;
                    } else {
                        beat = A + B / C;
                    }
                } else if (a.size() == 2) {
                    double A = a.get(0).asDouble();
                    double B = a.get(1).asDouble();
                    beat = A + B;
                } else if (a.size() == 1) {
                    beat = a.get(0).asDouble();
                }
            }
        } catch (Exception ignored) {
            beat = 0.0;
        }

        if (!Double.isFinite(beat)) beat = 0.0;
        beat = Math.round(beat * 1000.0) / 1000.0;
        return beat * 32.0;
    }

    private static float[] parseBezierPoints(JsonNode e) {
        JsonNode bp = getFirst(e, "bezierPoints", "BezierPoints", "points");
        if (bp == null || bp.isNull()) return null;
        try {
            if (bp.isArray()) {
                ArrayNode a = (ArrayNode) bp;
                if (a.size() >= 4) {
                    return new float[]{
                            (float) a.get(0).asDouble(),
                            (float) a.get(1).asDouble(),
                            (float) a.get(2).asDouble(),
                            (float) a.get(3).asDouble()
                    };
                }
                if (a.size() >= 2 && a.get(0).isArray() && a.get(1).isArray()) {
                    ArrayNode p0 = (ArrayNode) a.get(0);
                    ArrayNode p1 = (ArrayNode) a.get(1);
                    if (p0.size() >= 2 && p1.size() >= 2) {
                        return new float[]{
                                (float) p0.get(0).asDouble(),
                                (float) p0.get(1).asDouble(),
                                (float) p1.get(0).asDouble(),
                                (float) p1.get(1).asDouble()
                        };
                    }
                }
            }
        } catch (Exception ignored) {
        }
        return null;
    }

    private static double[] parseVec2(JsonNode el) {
        if (el == null || el.isNull()) return null;
        try {
            if (el.isArray()) {
                ArrayNode a = (ArrayNode) el;
                if (a.size() >= 2) {
                    return new double[]{a.get(0).asDouble(), a.get(1).asDouble()};
                }
            }
        } catch (Exception ignored) {
        }
        return null;
    }

    private static float[] parseRgb(JsonNode el) {
        if (el == null || el.isNull()) return null;
        try {
            if (el.isArray()) {
                ArrayNode a = (ArrayNode) el;
                if (a.size() >= 3) {
                    float r = (float) a.get(0).asDouble();
                    float g = (float) a.get(1).asDouble();
                    float b = (float) a.get(2).asDouble();
                    if (r > 1.0f || g > 1.0f || b > 1.0f) {
                        r /= 255f;
                        g /= 255f;
                        b /= 255f;
                    }
                    r = clamp01f(r);
                    g = clamp01f(g);
                    b = clamp01f(b);
                    return new float[]{r, g, b};
                }
            }
        } catch (Exception ignored) {
        }
        return null;
    }

    private static float clamp01f(float v) {
        if (v < 0f) return 0f;
        if (v > 1f) return 1f;
        return v;
    }

    private static final class BpmPoint {
        final double beat;
        final double bpm;
        BpmPoint(double beat, double bpm) {
            this.beat = beat;
            this.bpm = bpm;
        }
    }

    /**
     * Converts between raw beat ticks, seconds, and per-line normalized beats using BPM list.
     */
    private static final class BpmConverter {
        private final List<BpmPoint> points;
        private final List<Double> startSec;

        BpmConverter(List<BpmPoint> points) {
            this.points = new ArrayList<>(points);
            this.points.sort(Comparator.comparingDouble(a -> a.beat));
            this.startSec = new ArrayList<>(this.points.size());
            double sec = 0.0;
            for (int i = 0; i < this.points.size(); i++) {
                if (i == 0) {
                    this.startSec.add(0.0);
                    continue;
                }
                BpmPoint prev = this.points.get(i - 1);
                BpmPoint cur = this.points.get(i);
                double db = cur.beat - prev.beat;
                if (db < 0) db = 0;
                sec += db * (GameConstants.PGRBEAT / prev.bpm);
                this.startSec.add(sec);
            }
        }

        double baseBpm() {
            if (points.isEmpty()) return 120.0;
            double bpm = points.get(0).bpm;
            if (!Double.isFinite(bpm) || bpm <= 0.0) return 120.0;
            return bpm;
        }

        double beatToSec(double beat) {
            if (points.isEmpty()) return beat * (GameConstants.PGRBEAT / 120.0);
            int idx = findSegment(beat);
            BpmPoint p = points.get(idx);
            double sec0 = startSec.get(idx);
            double db = beat - p.beat;
            if (db < 0) db = 0;
            return sec0 + db * (GameConstants.PGRBEAT / p.bpm);
        }

        double beatToNormBeat(double beat, double lineBpm) {
            double sec = beatToSec(beat);
            double beatLen = (GameConstants.PGRBEAT / lineBpm);
            if (beatLen <= 0) beatLen = (GameConstants.PGRBEAT / baseBpm());
            return sec / beatLen;
        }

        private int findSegment(double beat) {
            int lo = 0;
            int hi = points.size() - 1;
            int ans = 0;
            while (lo <= hi) {
                int mid = (lo + hi) >>> 1;
                double b = points.get(mid).beat;
                if (beat >= b) {
                    ans = mid;
                    lo = mid + 1;
                } else {
                    hi = mid - 1;
                }
            }
            return ans;
        }

        BpmTimeline toTimeline() {
            int n = points.size();
            if (n <= 0) {
                return new BpmTimeline(
                        new double[]{0.0},
                        new double[]{120.0},
                        new double[]{0.0}
                );
            }
            double[] beats = new double[n];
            double[] bpms = new double[n];
            double[] secs = new double[n];
            for (int i = 0; i < n; i++) {
                beats[i] = points.get(i).beat;
                bpms[i] = points.get(i).bpm;
                secs[i] = startSec.get(i);
            }
            return new BpmTimeline(beats, bpms, secs);
        }
    }
}
