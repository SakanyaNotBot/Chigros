package com.wuying.phigros.game;

import java.util.ArrayList;
import java.util.List;

public class ReplayData {

    public static final int VERSION = 1;
    public static final int TYPE_TAP = 1;
    public static final int TYPE_DRAG = 2;
    public static final int TYPE_HOLD_PRESS = 3;
    public static final int TYPE_HOLD_RELEASE = 4;
    public static final int TYPE_FLICK = 5;

    public int v;
    public ReplayMeta meta;
    public List<ReplayEntry> entries;

    public ReplayData() {
        this.v = VERSION;
        this.entries = new ArrayList<>();
    }

    public static class ReplayMeta {
        public String songName;
        public String difficulty;
        public int score;
        public double accuracy;
        public int maxCombo;
        public int totalNotes;
        public int perfect;
        public int good;
        public int bad;
        public int miss;
        public int goodEarly;
        public int goodLate;
        public double stdDevMs;
        public long timestamp;

        public PlayResult toPlayResult() {
            return new PlayResult(score, accuracy, maxCombo, totalNotes,
                    perfect, good, bad, miss, goodEarly, goodLate, stdDevMs);
        }

        public static ReplayMeta fromPlayResult(PlayResult r, String songName, String difficulty) {
            ReplayMeta m = new ReplayMeta();
            m.songName = songName;
            m.difficulty = difficulty;
            m.score = r.score;
            m.accuracy = r.accuracy;
            m.maxCombo = r.maxCombo;
            m.totalNotes = r.totalNotes;
            m.perfect = r.perfect;
            m.good = r.good;
            m.bad = r.bad;
            m.miss = r.miss;
            m.goodEarly = r.goodEarly;
            m.goodLate = r.goodLate;
            m.stdDevMs = r.stdDevMs;
            m.timestamp = System.currentTimeMillis();
            return m;
        }
    }

    public static class ReplayEntry {
        public int t;    // type
        public int ni;   // note index in chart.allNotesSorted
        public int j;    // judgment (0=PERFECT, 1=GOOD, 2=BAD, 3=MISS)
        public double ts; // time seconds (music playback time, microsecond precision)
    }
}
