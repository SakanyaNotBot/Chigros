package com.wuying.phigros.game;

/**
 * Text event with optional render-time string interpolation.
 */
public class TextEvent {
    public double startTime;
    public double endTime;
    public String text;
    /** End text for interpolation; null or equal to text means no interpolation. */
    public String endText;
    public int easingType = 0;
    public float easingLeft = 0f;
    public float easingRight = 1f;
}
