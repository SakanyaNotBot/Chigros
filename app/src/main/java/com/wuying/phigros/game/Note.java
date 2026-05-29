package com.wuying.phigros.game;

/**
 * A single note within a chart's judge line.
 */
public class Note {
    public int type;
    public double time;
    public double holdTime;
    public double speed = 1.0;
    public double positionX;

    /** Original global beat tick (1 tick = 1/32 beat) before per-line BPM normalization. */
    public transient double rpeGlobalBeat = Double.NaN;

    public boolean isFake = false;

    /** Width multiplier. Default 1. */
    public float size = 1f;

    /** X-axis width multiplier (PhiEdit). Default 1. */
    public float xScale = 1f;

    /** Alpha multiplier (0..1). Default 1. */
    public float alpha = 1f;

    /** Spatial hit detection width scale. Default 1. Values > 1 make the note easier to hit. */
    public float judgeArea = 1.0f;

    /** Visibility window in seconds before hit time. < 0 means always visible. */
    public float visibleTime = -1f;

    /** Extra offset along the note direction in normalized stage height units. Default 0. */
    public float yOffset = 0f;

    public boolean useOfficialSpeed = false;

    // --- Runtime fields ---

    public transient boolean isAbove;
    public transient boolean isHold;
    public transient double sect;
    public transient double secht;
    public transient double holdEndTime;
    public transient double holdLength;
    public transient double floorPosition;
    public transient double holdEndFloorPosition;
    public transient boolean clicked;
    public transient int judgeResult = -1;
    public transient double judgeDiffSec = 0.0;
    public transient double judgeTimeSec = Double.NaN;
    public transient boolean preJudge = false;
    public transient boolean dragPrimed = false;
    public transient boolean holdActive = false;
    public transient boolean holdPerfect = false;
    public transient boolean holdPreJudge = false;
    public transient boolean holdFxActive = false;
    public transient double holdUpTimeSec = Double.POSITIVE_INFINITY;
    public transient double holdDiffSec = 0.0;
    public transient double holdFxAtSec = Double.NaN;
    public transient int morebets;
    public transient JudgeLine master;

    static final Note[] EMPTY_NOTES = new Note[0];

    /** Same-type notes within 0.01s, used for nearest-note competition in Flick/TapHold judgment. */
    public transient Note[] nearNotes = EMPTY_NOTES;

    public transient long holdTapTimeMs = 0;
    public transient int holdStatus = 0;
    public transient boolean holdBroken = false;
    public transient int safeFrame = 0;
    public transient boolean isJudged = false;
    public transient long badTimeMs = 0;
    public transient boolean scored = false;
    public transient double statOffset = 0.0;
    public transient boolean offsetIndicatorShown = false;
    public transient int frameCount = 0;
    public transient int sortedIndex = -1;

    /** Pre-computed appear time (seconds) for prpr-compatible pe-alpha extension. */
    public transient double cachedAppearSec = Double.NaN;
    public transient double lastAppearBeforeBeats = Double.NaN;
}
