package com.wuying.phigros.game;

import java.io.File;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

/**
 * A parsed rhythm game chart containing judge lines, notes, and runtime state.
 */
public class Chart {
    public double offset;
    public int formatVersion = 0;

    /** Global BPM timeline for charts with BPM changes (beat = tick, 1 tick = 1/32 beat). */
    public transient BpmTimeline bpmTimeline;

    /** Chart pack root directory, used to resolve storyboard shader files. */
    public transient File packDir;

    /** prpr storyboard effects from extra.json. */
    public transient List<PrprEffect> prprEffects = new ArrayList<>();

    /** attachUI: elementId (1..7) -> judgeLine index, -1 if none. */
    public int[] attachUiLineIndex = new int[]{-1,-1,-1,-1,-1,-1,-1,-8};

    public boolean useAttachUiFix = true;

    /** Per-frame UI transform data updated by GameRenderer. Key: attachUiElementId (1..7), Value: float[6]{x, y, rotDeg, scaleX, scaleY, alpha}. */
    public transient Map<Integer, float[]> uiTransforms = new HashMap<>();

    public void updateUiTransform(int uiId, float x, float y, float rot, float sx, float sy, float alpha) {
        if (uiTransforms == null) uiTransforms = new HashMap<>();
        float[] t = uiTransforms.get(uiId);
        if (t == null) {
            t = new float[6];
            uiTransforms.put(uiId, t);
        }
        t[0] = x;
        t[1] = y;
        t[2] = rot;
        t[3] = sx;
        t[4] = sy;
        t[5] = alpha;
    }

    /** Custom shader sources keyed by shader name (only for file-based shaders). */
    public transient Map<String, String> prprShaderSources = new HashMap<>();
    public List<JudgeLine> judgeLineList;

    public transient List<Note> allNotesSorted;
    public transient List<ClickEffectItem> clickEffectsSorted;
    public transient double chartEndTimeSec;

    private static final class LineRuntimeResult {
        final List<Note> allNotes;
        final List<Note> autoplayNotes;
        final Map<Long, Integer> sectCnt;
        final double maxEndSec;

        LineRuntimeResult(List<Note> allNotes,
                          List<Note> autoplayNotes,
                          Map<Long, Integer> sectCnt,
                          double maxEndSec) {
            this.allNotes = allNotes;
            this.autoplayNotes = autoplayNotes;
            this.sectCnt = sectCnt;
            this.maxEndSec = maxEndSec;
        }
    }

    private int estimateNoteCount() {
        if (judgeLineList == null) return 0;
        int c = 0;
        for (JudgeLine line : judgeLineList) {
            if (line == null) continue;
            if (line.notes != null) {
                c += line.notes.size();
            } else {
                if (line.notesAbove != null) c += line.notesAbove.size();
                if (line.notesBelow != null) c += line.notesBelow.size();
            }
        }
        return c;
    }

    /**
     * Floor position lookup optimized for monotonically non-decreasing note times.
     * Uses a moving index into speedEvents instead of scanning from the start each time.
     */
    private static double floorPositionMonotonic(double t, List<SpeedEvent> events, int[] idxHolder) {
        if (events == null || events.isEmpty()) return 0.0;
        int i = (idxHolder != null && idxHolder.length > 0) ? idxHolder[0] : 0;
        if (i < 0) i = 0;
        if (i >= events.size()) i = events.size() - 1;

        while (i + 1 < events.size()) {
            SpeedEvent nxt = events.get(i + 1);
            if (nxt == null) break;
            double st = nxt.startTime;
            if (!Double.isFinite(st) || t >= st) {
                i++;
            } else {
                break;
            }
        }
        if (idxHolder != null && idxHolder.length > 0) idxHolder[0] = i;

        SpeedEvent e = events.get(i);
        if (e == null) return 0.0;
        double st = e.startTime;
        if (Double.isFinite(st) && t < st) {
            return e.floorPosition + e.value * (t - st);
        }

        double ed = e.endTime;
        if (!Double.isFinite(ed)) ed = st;
        double v0 = e.value;
        double v1 = Double.isFinite(e.endValue) ? e.endValue : e.value;

        if (t <= ed) {
            double dt = t - st;
            if (dt < 0) dt = 0;
            double dur = ed - st;
            if (dur <= 1e-9) return e.floorPosition + dt * v0;
            double a = (v1 - v0) / dur;
            return e.floorPosition + v0 * dt + 0.5 * a * dt * dt;
        }

        double dur = ed - st;
        if (dur < 0) dur = 0;
        double area = (v0 + v1) * 0.5 * dur;
        double fpEnd = e.floorPosition + area;
        double dt = t - ed;
        if (dt < 0) dt = 0;
        return fpEnd + v1 * dt;
    }

    /**
     * Initialize all runtime state: event floor positions, merged notes, sect/floorPosition/holdLength per note.
     * Uses parallel processing when the chart has >= 800 notes and 2+ lines on 2+ cores.
     */
    public void initRuntime() {
        if (judgeLineList == null) judgeLineList = new ArrayList<>();

        for (int i = 0; i < judgeLineList.size(); i++) {
            JudgeLine l = judgeLineList.get(i);
            if (l != null) l.index = i;
        }
        for (JudgeLine l : judgeLineList) {
            if (l != null) l.resolveFather(judgeLineList);
        }

        final int noteEstimate = estimateNoteCount();
        final int cores = Math.max(1, Runtime.getRuntime().availableProcessors());
        final boolean useParallel = (cores >= 2 && judgeLineList.size() >= 2 && noteEstimate >= 800);

        if (useParallel) {
            initRuntimeParallel(cores);
        } else {
            initRuntimeSequential();
        }
    }

    private void initRuntimeSequential() {
        Random rnd = new Random();
        List<Note> allNotes = new ArrayList<>();
        List<Note> autoplayNotes = new ArrayList<>();
        Map<Long, Integer> sectCnt = new HashMap<>();
        chartEndTimeSec = 0;

        final boolean official = (this.formatVersion > 0);

        for (JudgeLine line : judgeLineList) {
            if (line == null) continue;
            line.normalize();
            line.mergeNotes();

            if (line.notes == null) line.notes = new ArrayList<>();
            line.notes.sort(Comparator.comparingDouble(a -> a.time));

            int[] speedIdx = new int[]{0};
            int[] holdEndIdx = new int[]{0};
            for (Note note : line.notes) {
                if (note == null) continue;
                note.master = line;
                note.isHold = (note.type == GameConstants.NOTE_HOLD);
                note.clicked = false;

                note.floorPosition = floorPositionMonotonic(note.time, line.speedEvents, speedIdx);

                note.sect = line.beat2sec(note.time);
                note.secht = line.beat2sec(note.holdTime);
                note.holdEndTime = note.sect + note.secht;
                note.useOfficialSpeed = official;

                if (note.isHold) {
                    if (official) {
                        note.holdEndFloorPosition = note.floorPosition
                                + note.holdTime * note.speed * GameConstants.PGRH;
                        note.holdLength = note.holdTime
                                * note.speed
                                * GameConstants.PGRH
                                * (GameConstants.PGRBEAT / line.bpm);
                    } else {
                        double endBeat = note.time + note.holdTime;
                        double endFp = floorPositionMonotonic(endBeat, line.speedEvents, holdEndIdx);
                        note.holdEndFloorPosition = endFp;
                        double fpDiff = endFp - note.floorPosition;
                        note.holdLength = fpDiff
                                * note.speed
                                * GameConstants.PGRH
                                * (GameConstants.PGRBEAT / line.bpm);
                    }
                } else {
                    note.holdLength = 0.0;
                    note.holdEndFloorPosition = note.floorPosition;
                }

                long key = (long) Math.round(note.sect * 1_000_000.0);
                sectCnt.put(key, (sectCnt.get(key) == null ? 1 : (sectCnt.get(key) + 1)));

                allNotes.add(note);
                if (!note.isFake) autoplayNotes.add(note);

                double end = note.isHold ? note.holdEndTime : note.sect;
                if (end > chartEndTimeSec) chartEndTimeSec = end;
            }
        }

        finalizeRuntime(rnd, allNotes, autoplayNotes, sectCnt);
    }

    private void initRuntimeParallel(int cores) {
        Random rnd = new Random();
        chartEndTimeSec = 0;
        final boolean official = (this.formatVersion > 0);

        int threads = Math.max(2, cores);
        threads = Math.min(threads, Math.max(2, judgeLineList.size()));

        ExecutorService pool = Executors.newFixedThreadPool(threads);
        List<Future<LineRuntimeResult>> futures = new ArrayList<>(judgeLineList.size());

        for (JudgeLine line : judgeLineList) {
            if (line == null) continue;
            futures.add(pool.submit(new Callable<LineRuntimeResult>() {
                @Override
                public LineRuntimeResult call() {
                    line.normalize();
                    line.mergeNotes();
                    if (line.notes == null) line.notes = new ArrayList<>();

                    List<Note> autoplay = new ArrayList<>();
                    Map<Long, Integer> localCnt = new HashMap<>();
                    double maxEnd = 0;

                    line.notes.sort(Comparator.comparingDouble(a -> a.time));

                    int[] speedIdx = new int[]{0};
                    int[] holdEndIdx = new int[]{0};
                    for (Note note : line.notes) {
                        if (note == null) continue;
                        note.master = line;
                        note.isHold = (note.type == GameConstants.NOTE_HOLD);
                        note.clicked = false;

                        note.floorPosition = floorPositionMonotonic(note.time, line.speedEvents, speedIdx);
                        note.sect = line.beat2sec(note.time);
                        note.secht = line.beat2sec(note.holdTime);
                        note.holdEndTime = note.sect + note.secht;
                        note.useOfficialSpeed = official;

                        if (note.isHold) {
                            if (official) {
                                note.holdEndFloorPosition = note.floorPosition
                                        + note.holdTime * note.speed * GameConstants.PGRH;
                                note.holdLength = note.holdTime
                                        * note.speed
                                        * GameConstants.PGRH
                                        * (GameConstants.PGRBEAT / line.bpm);
                            } else {
                                double endBeat = note.time + note.holdTime;
                                double endFp = floorPositionMonotonic(endBeat, line.speedEvents, holdEndIdx);
                                note.holdEndFloorPosition = endFp;
                                double fpDiff = endFp - note.floorPosition;
                                note.holdLength = fpDiff
                                        * note.speed
                                        * GameConstants.PGRH
                                        * (GameConstants.PGRBEAT / line.bpm);
                            }
                        } else {
                            note.holdLength = 0.0;
                            note.holdEndFloorPosition = note.floorPosition;
                        }

                        long key = (long) Math.round(note.sect * 1_000_000.0);
                        localCnt.put(key, (localCnt.get(key) == null ? 1 : (localCnt.get(key) + 1)));

                        if (!note.isFake) autoplay.add(note);
                        double end = note.isHold ? note.holdEndTime : note.sect;
                        if (end > maxEnd) maxEnd = end;
                    }

                    return new LineRuntimeResult(line.notes, autoplay, localCnt, maxEnd);
                }
            }));
        }

        List<Note> allNotes = new ArrayList<>();
        List<Note> autoplayNotes = new ArrayList<>();
        Map<Long, Integer> sectCnt = new HashMap<>();

        try {
            for (Future<LineRuntimeResult> f : futures) {
                LineRuntimeResult r = f.get();
                if (r == null) continue;
                if (r.allNotes != null) allNotes.addAll(r.allNotes);
                if (r.autoplayNotes != null) autoplayNotes.addAll(r.autoplayNotes);
                if (r.sectCnt != null) {
                    for (Map.Entry<Long, Integer> e : r.sectCnt.entrySet()) {
                        Long k = e.getKey();
                        Integer v = e.getValue();
                        if (k == null || v == null) continue;
                        sectCnt.put(k, (sectCnt.get(k) == null ? v : (sectCnt.get(k) + v)));
                    }
                }
                if (r.maxEndSec > chartEndTimeSec) chartEndTimeSec = r.maxEndSec;
            }
        } catch (InterruptedException | ExecutionException e) {
            pool.shutdownNow();
            initRuntimeSequential();
            return;
        } finally {
            pool.shutdown();
        }

        finalizeRuntime(rnd, allNotes, autoplayNotes, sectCnt);
    }

    private void finalizeRuntime(Random rnd,
                                 List<Note> allNotes,
                                 List<Note> autoplayNotes,
                                 Map<Long, Integer> sectCnt) {
        for (Note n : allNotes) {
            long key = (long) Math.round(n.sect * 1_000_000.0);
            Integer c = sectCnt.get(key);
            n.morebets = (c != null && c > 1) ? 1 : 0;
        }

        autoplayNotes.sort(Comparator.comparingDouble(a -> a.sect));
        allNotesSorted = autoplayNotes;

        List<ClickEffectItem> eff = new ArrayList<>();
        for (JudgeLine line : judgeLineList) {
            if (line == null || line.notes == null) continue;
            for (Note note : line.notes) {
                if (note == null) continue;
                if (note.isFake) continue;

                eff.add(new ClickEffectItem(note, note.sect, rnd));
                if (note.isHold) {
                    double dt = 30.0 / line.bpm;
                    double st = note.sect + dt;
                    while (st < note.holdEndTime) {
                        eff.add(new ClickEffectItem(note, st, rnd));
                        st += dt;
                    }
                }
            }
        }
        eff.sort(Comparator.comparingDouble(a -> a.timeSec));
        clickEffectsSorted = eff;

        List<Note> flicks = new ArrayList<>();
        List<Note> tapholds = new ArrayList<>();
        for (Note n : allNotes) {
            if (n.isFake) continue;
            if (n.type == GameConstants.NOTE_FLICK) flicks.add(n);
            else if (n.type == GameConstants.NOTE_TAP || n.type == GameConstants.NOTE_HOLD) tapholds.add(n);
        }
        flicks.sort(Comparator.comparingDouble(a -> a.sect));
        tapholds.sort(Comparator.comparingDouble(a -> a.sect));
        for (int i = 0; i < flicks.size(); i++) {
            Note note = flicks.get(i);
            List<Note> near = null;
            for (int j = i + 1; j < flicks.size(); j++) {
                Note note2 = flicks.get(j);
                if (note2.sect - note.sect > 0.01) break;
                if (near == null) near = new ArrayList<>();
                near.add(note2);
            }
            note.nearNotes = (near != null && !near.isEmpty()) ? near.toArray(new Note[0]) : Note.EMPTY_NOTES;
        }
        for (int i = 0; i < tapholds.size(); i++) {
            Note note = tapholds.get(i);
            List<Note> near = null;
            for (int j = i + 1; j < tapholds.size(); j++) {
                Note note2 = tapholds.get(j);
                if (note2.sect - note.sect > 0.01) break;
                if (near == null) near = new ArrayList<>();
                near.add(note2);
            }
            note.nearNotes = (near != null && !near.isEmpty()) ? near.toArray(new Note[0]) : Note.EMPTY_NOTES;
        }
    }
}
