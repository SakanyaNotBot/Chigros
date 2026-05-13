package com.wuying.phigros.game;

/**
 * 1D interpolated event (start -> end over time) with easing.
 */
public class LineEvent {
    public double startTime;
    public double endTime;
    public double start;
    public double end;

    /** 0-based easing type. 0 = linear. */
    public int easingType = 0;

    /** Easing remap window: progress p is remapped to [(p-left)/(right-left)] within the curve. */
    public float easingLeft = 0f;
    public float easingRight = 1f;

    public boolean bezier = false;

    /** Cubic bezier control points: [x1, y1, x2, y2]. */
    public float[] bezierPoints = null;

    /** Pre-computed bezier LUT (128 samples). */
    public transient float[] bezierLut = null;
}
