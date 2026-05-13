package com.wuying.phigros.game;

/**
 * GIF playback control event for storyboard animated textures.
 *
 * <p>{@code start} and {@code end} are normalized playback positions in [0, 1].
 * When no GIF event is active, the animation plays normally.
 */
public class GifEvent {
    public double startTime;
    public double endTime;
    public double start;
    public double end;
    public int easingType = 0;
    public float easingLeft = 0f;
    public float easingRight = 1f;
}
