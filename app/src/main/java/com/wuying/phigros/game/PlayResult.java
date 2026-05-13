package com.wuying.phigros.game;

import java.io.Serializable;

public class PlayResult implements Serializable {
    public final int score;
    public final double accuracy;
    public final int maxCombo;
    public final int totalNotes;
    public final int perfect;
    public final int good;
    public final int bad;
    public final int miss;
    public final int goodEarly;
    public final int goodLate;
    public final double stdDevMs;

    public PlayResult(int score,
                      double accuracy,
                      int maxCombo,
                      int totalNotes,
                      int perfect,
                      int good,
                      int bad,
                      int miss,
                      int goodEarly,
                      int goodLate,
                      double stdDevMs) {
        this.score = score;
        this.accuracy = accuracy;
        this.maxCombo = maxCombo;
        this.totalNotes = totalNotes;
        this.perfect = perfect;
        this.good = good;
        this.bad = bad;
        this.miss = miss;
        this.goodEarly = goodEarly;
        this.goodLate = goodLate;
        this.stdDevMs = stdDevMs;
    }

    public boolean isFullCombo() {
        return bad == 0 && miss == 0 && totalNotes > 0;
    }

    public boolean isAllPerfect() {
        return good == 0 && bad == 0 && miss == 0 && totalNotes > 0;
    }
}
