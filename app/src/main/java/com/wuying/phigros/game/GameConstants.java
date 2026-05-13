package com.wuying.phigros.game;

/**
 * Global constants for the rhythm game engine.
 */
public final class GameConstants {

    private GameConstants() {}

    public static final int NOTE_TAP = 1;
    public static final int NOTE_DRAG = 2;
    public static final int NOTE_HOLD = 3;
    public static final int NOTE_FLICK = 4;

    public static final float PGRW = 0.05625f;
    public static final float PGRH = 0.6f;
    /** Beat duration in seconds at 1x speed: 60/32 = 1.875 seconds per beat. */
    public static final float PGRBEAT = 60f / 32f;

    /** Judge line dimensions for official format. */
    public static final float LINEW = 0.0075f;
    public static final float LINEH = 2.88f;

    /** Judge line dimensions for RPE/PhiEdit format. */
    public static final float LINEH_RPE = 4000f / 1350f / 2f;
    public static final float LINEW_RPE = 1f / 180f;

    public static final float PCOLOR_R = 0xff / 255f;
    public static final float PCOLOR_G = 0xec / 255f;
    public static final float PCOLOR_B = 0xa0 / 255f;

    public static final float GCOLOR_R = 0xb4 / 255f;
    public static final float GCOLOR_G = 0xe1 / 255f;
    public static final float GCOLOR_B = 0xff / 255f;

    public static final float[] PCOLOR = new float[]{PCOLOR_R, PCOLOR_G, PCOLOR_B};
    public static final float[] GCOLOR = new float[]{GCOLOR_R, GCOLOR_G, GCOLOR_B};

    public static final float[] AP_INDICATOR_COLOR = new float[]{254f / 255f, 255f / 255f, 169f / 255f};
    public static final float[] FC_INDICATOR_COLOR = new float[]{162f / 255f, 238f / 255f, 255f / 255f};

    public static final float PALPHA = 0xe1 / 255f;
    public static final float GALPHA = 0xeb / 255f;

    /** RPE stage dimensions in pixels. */
    public static final float RPE_WIDTH = 1350f;
    public static final float RPE_HEIGHT = 900f;

    public static final float HEIGHT_RATIO = 0.83175f;

    /** Converts RPE speed values to internal speed units: 10 / 45 / HEIGHT_RATIO. */
    public static final float SPEED_RATIO = 10f / 45f / HEIGHT_RATIO;

    public static final float EPS = 1e-5f;
}
