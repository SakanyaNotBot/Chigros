package com.wuying.phigros.game;

/**
 * Speed event segment with optional easing and integral-based floor position calculation.
 */
public class SpeedEvent {
    public double startTime;
    public double endTime;
    public double value;

    /** End speed value. NaN means constant at {@link #value}. */
    public double endValue = Double.NaN;

    public int easingType = 0;
    public float easingLeft = 0f;
    public float easingRight = 1f;
    public boolean bezier = false;
    public float[] bezierPoints = null;
    public transient float[] bezierLut = null;

    /** Pre-computed floor position at event start. */
    public transient double floorPosition;

    public transient int tweenId = 2;
    public transient boolean isClamped = false;
    public transient boolean isBezier = false;
    public transient double totalHeight = 0.0;

    /** Speed integral tween factor: endSpeed - startSpeed. */
    public transient double speedK = 0.0;

    /** Speed integral tween base: startSpeed. */
    public transient double speedB = 0.0;
}
