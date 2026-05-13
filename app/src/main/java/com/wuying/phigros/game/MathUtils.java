package com.wuying.phigros.game;

public final class MathUtils {

    private MathUtils() {}

    public static float[] rotatePoint(float x, float y, float r, float deg) {
        double rad = deg * Math.PI / 180.0;
        float nx = x + (float) (r * Math.cos(rad));
        float ny = y + (float) (r * Math.sin(rad));
        return new float[]{nx, ny};
    }

    public static float clamp(float v, float min, float max) {
        return Math.max(min, Math.min(max, v));
    }

    public static double clamp(double v, double min, double max) {
        return Math.max(min, Math.min(max, v));
    }
}
