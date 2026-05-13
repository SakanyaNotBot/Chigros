package com.wuying.phigros.game;

/**
 * 2D interpolated event (x and y simultaneously) with easing.
 */
public class MoveEvent {
    public double startTime;
    public double endTime;
    public double start;
    public double end;
    public double start2;
    public double end2;

    /** 0-based easing type. 0 = linear. */
    public int easingType = 0;

    public float easingLeft = 0f;
    public float easingRight = 1f;

    public boolean bezier = false;

    /** Cubic bezier control points: [x1, y1, x2, y2]. */
    public float[] bezierPoints = null;

    /** Pre-computed bezier LUT (128 samples). */
    public transient float[] bezierLut = null;
}
