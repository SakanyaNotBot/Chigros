package com.wuying.phigros.game;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * PhiEdit / PEC text chart parser.
 *   First non-empty line is offset (ms). offsetSec = round4(ms/1000) - 0.175
 *   Beat unit: 1 beat = 32 ticks (internal time base)
 *   Notes: n1 tap, n2 hold, n3 flick, n4 drag
 *   Events: cv speed, cm/cp move, cr/cd rotate, cf/ca alpha
 *   Alpha special values: -1/-2 and < -100 visibleTime encoding
 */
public final class PecChartParser {

    private PecChartParser() {}

    private static final double BEAT_MULT = 32.0;
    private static final double VISIBLE_STEP_TICK = 4.0;

    public static Chart parse(String content) {
        if (content == null) return null;

        String[] rawLines = content.replace("\r\n", "\n").replace("\r", "\n").split("\n");
        List<String> lines = new ArrayList<>();
        for (String l : rawLines) {
            if (l == null) continue;
            String s = stripComments(l).trim();
            if (s.isEmpty()) continue;
            lines.add(s);
        }
        if (lines.isEmpty()) return null;

        double offsetMs = parseDouble(lines.get(0), Double.NaN);
        if (!Double.isFinite(offsetMs)) return null;

        Chart chart = new Chart();
        chart.offset = round4((offsetMs - 150.0) / 1000.0);

        List<BpmPoint> bpmPoints = new ArrayList<>();
        Map<Integer, LineBuilder> lineMap = new HashMap<>();

        Note lastNote = null;

        for (int i = 1; i < lines.size(); i++) {
            String line = lines.get(i);
            if (line.isEmpty()) continue;

            if (line.startsWith("#")) {
                if (lastNote != null) {
                    String v = line.substring(1).trim();
                    lastNote.speed = parseDouble(v, 1.0);
                    if (!Double.isFinite(lastNote.speed)) lastNote.speed = 1.0;
                }
                continue;
            }
            if (line.startsWith("&")) {
                if (lastNote != null) {
                    String v = line.substring(1).trim();
                    float xs = (float) parseDouble(v, 1.0);
                    if (!Float.isFinite(xs) || xs == 0f) xs = 1f;
                    lastNote.xScale = xs;
                }
                continue;
            }

            String[] tok = line.split("\\s+");
            if (tok.length == 0) continue;
            String cmd = tok[0].trim();

            switch (cmd) {
                case "bp": {
                    if (tok.length < 3) break;
                    double beat = parseDouble(tok[1], 0.0) * BEAT_MULT;
                    double bpm = parseDouble(tok[2], 120.0);
                    if (!Double.isFinite(bpm) || bpm <= 0.0) bpm = 120.0;
                    bpmPoints.add(new BpmPoint(beat, bpm));
                    break;
                }
                case "n1":
                case "n2":
                case "n3":
                case "n4": {
                    Note n = parseNote(tok);
                    if (n == null) break;
                    int lineId = (int) parseDouble(tok[1], 0.0);
                    LineBuilder b = lb(lineMap, lineId);
                    if (n.isAbove) b.notesAbove.add(n);
                    else b.notesBelow.add(n);
                    lastNote = n;
                    break;
                }
                case "cv": {
                    if (tok.length < 4) break;
                    int lineId = (int) parseDouble(tok[1], 0.0);
                    double beat = parseDouble(tok[2], 0.0) * BEAT_MULT;
                    double v = parseDouble(tok[3], 1.0);
                    if (!Double.isFinite(v)) v = 1.0;
                    v /= 7.0;
                    lb(lineMap, lineId).speedPoints.add(new SpeedPoint(beat, v));
                    break;
                }
                case "cm": {
                    if (tok.length < 6) break;
                    int lineId = (int) parseDouble(tok[1], 0.0);
                    double st = parseDouble(tok[2], 0.0) * BEAT_MULT;
                    double et = parseDouble(tok[3], 0.0) * BEAT_MULT;
                    double x = parseDouble(tok[4], 1024.0) / 2048.0;
                    double y = parseDouble(tok[5], 700.0) / 1400.0;
                    int ease = (tok.length >= 7) ? (int) parseDouble(tok[6], 1.0) : 1;
                    MoveEvent me = new MoveEvent();
                    me.startTime = st;
                    me.endTime = et;
                    me.start = Double.NaN;
                    me.start2 = Double.NaN;
                    me.end = x;
                    me.end2 = y;
                    me.easingType = clampEase(ease - 1);
                    lb(lineMap, lineId).moveEvents.add(me);
                    break;
                }
                case "cp": {
                    if (tok.length < 5) break;
                    int lineId = (int) parseDouble(tok[1], 0.0);
                    double t = parseDouble(tok[2], 0.0) * BEAT_MULT;
                    double x = parseDouble(tok[3], 1024.0) / 2048.0;
                    double y = parseDouble(tok[4], 700.0) / 1400.0;
                    MoveEvent me = new MoveEvent();
                    me.startTime = t;
                    me.endTime = Double.NaN;
                    me.start = x;
                    me.end = x;
                    me.start2 = y;
                    me.end2 = y;
                    me.easingType = 0;
                    lb(lineMap, lineId).moveEvents.add(me);
                    break;
                }
                case "cr": {
                    if (tok.length < 5) break;
                    int lineId = (int) parseDouble(tok[1], 0.0);
                    double st = parseDouble(tok[2], 0.0) * BEAT_MULT;
                    double et = parseDouble(tok[3], 0.0) * BEAT_MULT;
                    double deg = parseDouble(tok[4], 0.0);
                    int ease = (tok.length >= 6) ? (int) parseDouble(tok[5], 1.0) : 1;
                    LineEvent le = new LineEvent();
                    le.startTime = st;
                    le.endTime = et;
                    le.start = Double.NaN;
                    le.end = deg;
                    le.easingType = clampEase(ease - 1);
                    lb(lineMap, lineId).rotateEvents.add(le);
                    break;
                }
                case "cd": {
                    if (tok.length < 4) break;
                    int lineId = (int) parseDouble(tok[1], 0.0);
                    double t = parseDouble(tok[2], 0.0) * BEAT_MULT;
                    double deg = parseDouble(tok[3], 0.0);
                    LineEvent le = new LineEvent();
                    le.startTime = t;
                    le.endTime = Double.NaN;
                    le.start = deg;
                    le.end = deg;
                    le.easingType = 0;
                    lb(lineMap, lineId).rotateEvents.add(le);
                    break;
                }
                case "cf": {
                    if (tok.length < 5) break;
                    int lineId = (int) parseDouble(tok[1], 0.0);
                    double st = parseDouble(tok[2], 0.0) * BEAT_MULT;
                    double et = parseDouble(tok[3], 0.0) * BEAT_MULT;
                    double a = parseDouble(tok[4], 255.0);
                    LineEvent le = new LineEvent();
                    le.startTime = st;
                    le.endTime = et;
                    le.start = Double.NaN;
                    le.end = a;
                    le.easingType = 0;
                    lb(lineMap, lineId).alphaEvents.add(le);
                    break;
                }
                case "ca": {
                    if (tok.length < 4) break;
                    int lineId = (int) parseDouble(tok[1], 0.0);
                    double t = parseDouble(tok[2], 0.0) * BEAT_MULT;
                    double a = parseDouble(tok[3], 255.0);
                    LineEvent le = new LineEvent();
                    le.startTime = t;
                    le.endTime = Double.NaN;
                    le.start = a;
                    le.end = a;
                    le.easingType = 0;
                    lb(lineMap, lineId).alphaEvents.add(le);
                    break;
                }
                default:
                    break;
            }
        }

        if (bpmPoints.isEmpty()) {
            bpmPoints.add(new BpmPoint(0.0, 120.0));
        } else {
            bpmPoints.sort(Comparator.comparingDouble(a -> a.beat));
            if (bpmPoints.get(0).beat > 0.0) {
                bpmPoints.add(0, new BpmPoint(0.0, bpmPoints.get(0).bpm));
            }
        }

        BpmConverter bpmConv = new BpmConverter(bpmPoints);
        double baseBpm = bpmConv.baseBpm();

        List<Integer> keys = new ArrayList<>(lineMap.keySet());
        Collections.sort(keys);
        List<JudgeLine> judgeLines = new ArrayList<>();
        for (int idx : keys) {
            LineBuilder b = lineMap.get(idx);
            if (b == null) continue;

            b.notesAbove.sort(Comparator.comparingDouble(a -> a.time));
            b.notesBelow.sort(Comparator.comparingDouble(a -> a.time));

            List<MoveEvent> moveRaw = normalizeMoveEvents(b.moveEvents, 0.5, 0.5);
            List<LineEvent> rotRaw = normalizeLineEvents(b.rotateEvents, 0.0);
            List<LineEvent> alphaRaw = normalizeLineEvents(b.alphaEvents, 0.0);

            applyAlphaSpecials(alphaRaw, b, bpmConv);

            JudgeLine jl = new JudgeLine();
            jl.bpm = baseBpm;
            jl.bpmfactor = 1.0;
            jl.invertRotation = false;

            if (b.speedPoints.isEmpty()) {
                jl.speedEvents = defaultSpeed();
            } else {
                jl.speedEvents = buildSpeedEvents(b.speedPoints, bpmConv, baseBpm);
            }

            jl.judgeLineMoveEvents = moveRaw == null || moveRaw.isEmpty() ? defaultMove() : convertMoveEventsBeats(moveRaw, bpmConv, baseBpm);
            jl.judgeLineRotateEvents = rotRaw == null || rotRaw.isEmpty() ? defaultLine(0.0) : convertLineEventsBeats(rotRaw, bpmConv, baseBpm);
            jl.judgeLineDisappearEvents = alphaRaw == null || alphaRaw.isEmpty() ? defaultLine(0.0) : convertLineEventsBeats(alphaRaw, bpmConv, baseBpm);

            jl.notesAbove = convertNotesBeats(b.notesAbove, bpmConv, baseBpm);
            jl.notesBelow = convertNotesBeats(b.notesBelow, bpmConv, baseBpm);

            judgeLines.add(jl);
        }

        chart.judgeLineList = judgeLines;
        return chart;
    }

    private static int clampEase(int t) {
        if (t < 0) return 0;
        if (t > 28) return 28;
        return t;
    }

    private static Note parseNote(String[] tok) {
        if (tok == null || tok.length < 6) return null;

        String cmd = tok[0];
        int type;
        switch (cmd) {
            case "n1": type = GameConstants.NOTE_TAP; break;
            case "n2": type = GameConstants.NOTE_HOLD; break;
            case "n3": type = GameConstants.NOTE_FLICK; break;
            case "n4": type = GameConstants.NOTE_DRAG; break;
            default: return null;
        }

        double timeBeat = parseDouble(tok[2], 0.0) * BEAT_MULT;
        double holdDur = 0.0;
        if (type == GameConstants.NOTE_HOLD) {
            if (tok.length < 7) return null;
            double endBeat = parseDouble(tok[3], 0.0) * BEAT_MULT;
            holdDur = Math.max(0.0, endBeat - timeBeat);
        }

        int xIndex = (type == GameConstants.NOTE_HOLD) ? 4 : 3;
        int aboveIndex = (type == GameConstants.NOTE_HOLD) ? 5 : 4;
        int fakeIndex = (type == GameConstants.NOTE_HOLD) ? 6 : 5;

        double posXRaw = parseDouble(tok[xIndex], 0.0);
        boolean isAbove = parseDouble(tok[aboveIndex], 1.0) == 1.0;
        boolean isFake = parseDouble(tok[fakeIndex], 0.0) == 1.0;

        Note n = new Note();
        n.type = type;
        n.time = timeBeat;
        n.holdTime = holdDur;
        n.positionX = toNotePosX(posXRaw);
        n.speed = 1.0;
        n.isFake = isFake;

        n.xScale = 1f;
        n.size = 1f;
        n.isAbove = isAbove;
        return n;
    }

    private static double toNotePosX(double raw) {
        return raw * (9.0 / 1024.0);
    }

    private static String stripComments(String s) {
        int p = s.indexOf("//");
        if (p >= 0) return s.substring(0, p);
        return s;
    }

    private static double parseDouble(String s, double def) {
        if (s == null) return def;
        try {
            return Double.parseDouble(s.trim());
        } catch (Exception ignored) {
            return def;
        }
    }

    private static double round4(double v) {
        return Math.round(v * 10000.0) / 10000.0;
    }

    private static final class LineBuilder {
        final List<SpeedPoint> speedPoints = new ArrayList<>();
        final List<MoveEvent> moveEvents = new ArrayList<>();
        final List<LineEvent> rotateEvents = new ArrayList<>();
        final List<LineEvent> alphaEvents = new ArrayList<>();
        final List<Note> notesAbove = new ArrayList<>();
        final List<Note> notesBelow = new ArrayList<>();
    }

    private static LineBuilder lb(Map<Integer, LineBuilder> map, int idx) {
        LineBuilder b = map.get(idx);
        if (b == null) {
            b = new LineBuilder();
            map.put(idx, b);
        }
        return b;
    }

    private static final class SpeedPoint {
        final double beat;
        final double speed;
        SpeedPoint(double beat, double speed) {
            this.beat = beat;
            this.speed = speed;
        }
    }

    private static final class BpmPoint {
        final double beat;
        final double bpm;
        BpmPoint(double beat, double bpm) {
            this.beat = beat;
            this.bpm = bpm;
        }
    }

    private static List<SpeedEvent> buildSpeedEvents(List<SpeedPoint> points, BpmConverter bpmConv, double lineBpm) {
        if (points == null || points.isEmpty()) return defaultSpeed();
        points.sort(Comparator.comparingDouble(a -> a.beat));
        List<SpeedEvent> out = new ArrayList<>();

        if (points.get(0).beat > 0.0) {
            points.add(0, new SpeedPoint(0.0, points.get(0).speed));
        }

        for (int i = 0; i < points.size(); i++) {
            SpeedPoint p0 = points.get(i);
            double b0 = bpmConv.beatToNormBeat(p0.beat, lineBpm);
            double b1 = (i + 1 < points.size())
                    ? bpmConv.beatToNormBeat(points.get(i + 1).beat, lineBpm)
                    : 1e9;
            if (b1 < b0) {
                double tmp = b0;
                b0 = b1;
                b1 = tmp;
            }
            SpeedEvent se = new SpeedEvent();
            se.startTime = b0;
            se.endTime = b1;
            se.value = p0.speed;
            se.endValue = p0.speed;
            out.add(se);
        }
        if (!out.isEmpty()) {
            SpeedEvent last = out.get(out.size() - 1);
            if (last.endTime < 1e8) last.endTime = 1e9;
        }
        return out;
    }

    private static List<MoveEvent> normalizeMoveEvents(List<MoveEvent> in, double defX, double defY) {
        if (in == null || in.isEmpty()) return null;
        in.sort(Comparator.comparingDouble(a -> a.startTime));

        for (int i = 0; i < in.size(); i++) {
            MoveEvent e = in.get(i);
            if (e == null) continue;
            if (!Double.isFinite(e.endTime)) {
                e.endTime = (i + 1 < in.size()) ? in.get(i + 1).startTime : 1e9;
            }
            if (e.endTime < e.startTime) e.endTime = e.startTime;
        }

        double lastX = defX;
        double lastY = defY;
        for (MoveEvent e : in) {
            if (e == null) continue;
            if (!Double.isFinite(e.start)) e.start = lastX;
            if (!Double.isFinite(e.start2)) e.start2 = lastY;
            lastX = e.end;
            lastY = e.end2;
        }
        return in;
    }

    private static List<LineEvent> normalizeLineEvents(List<LineEvent> in, double defStart) {
        if (in == null || in.isEmpty()) return null;
        in.sort(Comparator.comparingDouble(a -> a.startTime));

        for (int i = 0; i < in.size(); i++) {
            LineEvent e = in.get(i);
            if (e == null) continue;
            if (!Double.isFinite(e.endTime)) {
                e.endTime = (i + 1 < in.size()) ? in.get(i + 1).startTime : 1e9;
            }
            if (e.endTime < e.startTime) e.endTime = e.startTime;
        }

        double last = defStart;
        for (LineEvent e : in) {
            if (e == null) continue;
            if (!Double.isFinite(e.start)) e.start = last;
            last = e.end;
        }
        return in;
    }

    private static void applyAlphaSpecials(List<LineEvent> alphaEventsRaw,
                                          LineBuilder b,
                                          BpmConverter bpmConv) {
        if (alphaEventsRaw == null || alphaEventsRaw.isEmpty()) return;

        List<Note> notes = new ArrayList<>();
        notes.addAll(b.notesAbove);
        notes.addAll(b.notesBelow);
        notes.sort(Comparator.comparingDouble(a -> a.time));

        for (LineEvent e : alphaEventsRaw) {
            if (e == null) continue;

            double start = e.start;
            double end = e.end;

            if (start == -1.0) start = -255.0;
            else if (start == -2.0) start = -510.0;
            if (end == -1.0) end = -255.0;
            else if (end == -2.0) end = -510.0;

            boolean startSetsVisible = false;
            boolean endSetsVisible = false;

            if (start < -100.0 && start >= -1000.0) {
                startSetsVisible = assignVisibleTime(e, notes, bpmConv);
                start = startSetsVisible ? 0.0 : -255.0;
            }
            if (end < -100.0 && end >= -1000.0) {
                endSetsVisible = assignVisibleTime(e, notes, bpmConv);
                end = endSetsVisible ? 0.0 : -255.0;
            }

            start /= 255.0;
            end /= 255.0;

            e.start = start;
            e.end = end;
        }
    }

    /**
     * Apply PEC alpha-visibleTime encoding: value = -100 - visibleBeat*10,
     * so visibleBeat = ((value + 100) * -1) / 10.
     * Samples the event linearly in 0.125 beat steps.
     */
    private static boolean assignVisibleTime(LineEvent alphaEvent,
                                             List<Note> notes,
                                             BpmConverter bpmConv) {
        if (alphaEvent == null || notes == null || notes.isEmpty()) return false;
        double st = alphaEvent.startTime;
        double et = alphaEvent.endTime;
        if (!Double.isFinite(st) || !Double.isFinite(et) || et <= st) return false;

        boolean any = false;

        double a0 = alphaEvent.start;
        double a1 = alphaEvent.end;

        double dur = et - st;
        double t = st;
        int noteIdx = 0;

        while (t <= et + 1e-9) {
            double p = (t - st) / dur;
            if (p < 0.0) p = 0.0;
            if (p > 1.0) p = 1.0;
            double curA = a0 + (a1 - a0) * p;

            if (curA < -100.0 && curA >= -1000.0) {
                double visibleBeat = ((curA + 100.0) * -1.0) / 10.0;
                if (visibleBeat < 0.0) visibleBeat = 0.0;

                while (noteIdx < notes.size() && notes.get(noteIdx).time < t - 1e-6) noteIdx++;

                int j = noteIdx;
                while (j < notes.size()) {
                    Note n = notes.get(j);
                    if (Math.abs(n.time - t) < 1e-6) {
                        double bpm = bpmConv.bpmAtBeat(n.time);
                        if (!Double.isFinite(bpm) || bpm <= 0.0) bpm = bpmConv.baseBpm();
                        float sec = (float) (visibleBeat * (60.0 / bpm));
                        n.visibleTime = sec;
                        any = true;
                        j++;
                        continue;
                    }
                    if (n.time > t + 1e-6) break;
                    j++;
                }
            }

            t += VISIBLE_STEP_TICK;
        }

        return any;
    }

    private static List<Note> convertNotesBeats(List<Note> in, BpmConverter bpmConv, double lineBpm) {
        if (in == null) return null;
        List<Note> out = new ArrayList<>();
        for (Note src : in) {
            if (src == null) continue;
            Note n = src;

            double rawBeat = n.time;
            double t = bpmConv.beatToNormBeat(rawBeat, lineBpm);
            n.time = t;

            if (n.holdTime > 0.0) {
                double secStart = bpmConv.beatToSec(rawBeat);
                double secEnd = bpmConv.beatToSec(rawBeat + n.holdTime);
                double durSec = Math.max(0.0, secEnd - secStart);
                n.holdTime = durSec / (GameConstants.PGRBEAT / lineBpm);
            }

            if (!Double.isFinite(n.speed)) n.speed = 1.0;
            if (!Float.isFinite(n.xScale) || n.xScale == 0f) n.xScale = 1f;
            if (!Float.isFinite(n.size) || n.size == 0f) n.size = 1f;

            out.add(n);
        }
        out.sort(Comparator.comparingDouble(a -> a.time));
        return out;
    }

    private static List<MoveEvent> convertMoveEventsBeats(List<MoveEvent> in, BpmConverter bpmConv, double lineBpm) {
        if (in == null) return null;
        List<MoveEvent> out = new ArrayList<>();
        for (MoveEvent src : in) {
            if (src == null) continue;
            MoveEvent me = new MoveEvent();
            me.startTime = bpmConv.beatToNormBeat(src.startTime, lineBpm);
            me.endTime = bpmConv.beatToNormBeat(src.endTime, lineBpm);
            me.start = src.start;
            me.end = src.end;
            me.start2 = src.start2;
            me.end2 = src.end2;
            me.easingType = src.easingType;
            out.add(me);
        }
        out.sort(Comparator.comparingDouble(a -> a.startTime));
        return out;
    }

    private static List<LineEvent> convertLineEventsBeats(List<LineEvent> in, BpmConverter bpmConv, double lineBpm) {
        if (in == null) return null;
        List<LineEvent> out = new ArrayList<>();
        for (LineEvent src : in) {
            if (src == null) continue;
            LineEvent le = new LineEvent();
            le.startTime = bpmConv.beatToNormBeat(src.startTime, lineBpm);
            le.endTime = bpmConv.beatToNormBeat(src.endTime, lineBpm);
            le.start = src.start;
            le.end = src.end;
            le.easingType = src.easingType;
            out.add(le);
        }
        out.sort(Comparator.comparingDouble(a -> a.startTime));
        return out;
    }

    private static List<MoveEvent> defaultMove() {
        MoveEvent me = new MoveEvent();
        me.startTime = 0.0;
        me.endTime = 1e9;
        me.start = 0.5;
        me.end = 0.5;
        me.start2 = 0.5;
        me.end2 = 0.5;
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

    private static List<LineEvent> defaultLine(double v) {
        LineEvent le = new LineEvent();
        le.startTime = -1e9;
        le.endTime = 1e9;
        le.start = v;
        le.end = v;
        List<LineEvent> out = new ArrayList<>();
        out.add(le);
        return out;
    }

    /**
     * BPM converter: raw ticks -> seconds -> normalized ticks.
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

        double bpmAtBeat(double beat) {
            if (points.isEmpty()) return baseBpm();
            int idx = findSegment(beat);
            double bpm = points.get(idx).bpm;
            if (!Double.isFinite(bpm) || bpm <= 0.0) return baseBpm();
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
    }
}
