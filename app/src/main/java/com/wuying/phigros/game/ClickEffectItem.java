package com.wuying.phigros.game;

import java.util.Random;

public class ClickEffectItem {
    public final Note note;
    public final double timeSec;

    public final int numOfParts;
    public final float[] effectRotateDeg;
    public final float[] effectRBase;

    /** Cached screen position — computed once on first render and reused. */
    public transient boolean positionCached = false;
    public transient float cachedScreenX;
    public transient float cachedScreenY;
    public transient boolean animStartCached = false;
    public transient float animStartSec;

    public ClickEffectItem(Note note, double timeSec, Random rnd) {
        this(note, timeSec, rnd, 4);
    }

    public ClickEffectItem(Note note, double timeSec, Random rnd, int numOfParts) {
        this.note = note;
        this.timeSec = timeSec;
        this.numOfParts = numOfParts;

        if (rnd == null) rnd = new Random();
        effectRotateDeg = new float[4];
        effectRBase = new float[4];
        for (int i = 0; i < 4; i++) {
            effectRotateDeg[i] = rnd.nextFloat() * 360f;
            effectRBase[i] = 185f + rnd.nextFloat() * (265f - 185f);
        }
    }
}
