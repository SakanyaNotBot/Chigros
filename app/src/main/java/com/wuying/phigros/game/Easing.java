package com.wuying.phigros.game;

/**
 * Easing functions aligned with prpr tween.rs.
 *
 * <p>33-entry TWEEN_FUNCTIONS table with In/Out/InOut variants:
 * 0: zero, 1: one, 2: linear, 3..32: sine/quad/cubic/quart/quint/expo/circ/back/elastic/bounce.
 *
 * <p>RPE easing IDs (1..29) are mapped to tween IDs via RPE_TWEEN_MAP.
 * Includes integral easing functions for speed-to-height conversion and bezier support.
 */
public final class Easing {

    private Easing() {}

    public static double clamp01(double p) {
        if (!Double.isFinite(p)) return 0.0;
        if (p < 0.0) return 0.0;
        if (p > 1.0) return 1.0;
        return p;
    }

    /** Apply easing by tween ID (33 functions, 0..32). */
    public static double applyTween(int tweenId, double x) {
        x = clamp01(x);
        if (tweenId <= 0) return 0.0;
        if (tweenId == 1) return 1.0;
        if (tweenId == 2) return x;
        switch (tweenId) {
            case 3:  return sineIn(x);
            case 4:  return sineOut(x);
            case 5:  return sineInOut(x);
            case 6:  return quadIn(x);
            case 7:  return quadOut(x);
            case 8:  return quadInOut(x);
            case 9:  return cubicIn(x);
            case 10: return cubicOut(x);
            case 11: return cubicInOut(x);
            case 12: return quartIn(x);
            case 13: return quartOut(x);
            case 14: return quartInOut(x);
            case 15: return quintIn(x);
            case 16: return quintOut(x);
            case 17: return quintInOut(x);
            case 18: return expoIn(x);
            case 19: return expoOut(x);
            case 20: return expoInOut(x);
            case 21: return circIn(x);
            case 22: return circOut(x);
            case 23: return circInOut(x);
            case 24: return backIn(x);
            case 25: return backOut(x);
            case 26: return backInOut(x);
            case 27: return elasticIn(x);
            case 28: return elasticOut(x);
            case 29: return elasticInOut(x);
            case 30: return bounceIn(x);
            case 31: return bounceOut(x);
            case 32: return bounceInOut(x);
            default: return x;
        }
    }

    private static double sineIn(double x)       { return 1.0 - Math.cos(x * Math.PI / 2.0); }
    private static double quadIn(double x)       { return x * x; }
    private static double cubicIn(double x)      { return x * x * x; }
    private static double quartIn(double x)      { return x * x * x * x; }
    private static double quintIn(double x)      { return x * x * x * x * x; }
    private static double expoIn(double x)       { return Math.pow(2.0, 10.0 * (x - 1.0)); }
    private static double circIn(double x)       { return 1.0 - Math.sqrt(1.0 - x * x); }
    private static double backIn(double x)       { final double C1 = 1.70158; final double C3 = C1 + 1.0; return (C3 * x - C1) * x * x; }
    private static double elasticIn(double x)    { final double C4 = (2.0 * Math.PI) / 3.0; return -(Math.pow(2.0, 10.0 * x - 10.0) * Math.sin((x * 10.0 - 10.75) * C4)); }
    private static double bounceIn(double x)     { return 1.0 - bounceOut(1.0 - x); }

    private static double sineOut(double x)      { return 1.0 - sineIn(1.0 - x); }
    private static double quadOut(double x)      { return 1.0 - quadIn(1.0 - x); }
    private static double cubicOut(double x)     { return 1.0 - cubicIn(1.0 - x); }
    private static double quartOut(double x)     { return 1.0 - quartIn(1.0 - x); }
    private static double quintOut(double x)     { return 1.0 - quintIn(1.0 - x); }
    private static double expoOut(double x)      { return 1.0 - expoIn(1.0 - x); }
    private static double circOut(double x)      { return 1.0 - circIn(1.0 - x); }
    private static double backOut(double x)      { return 1.0 - backIn(1.0 - x); }
    private static double elasticOut(double x)   { return 1.0 - elasticIn(1.0 - x); }

    private static double bounceOut(double x) {
        final double N1 = 7.5625;
        final double D1 = 2.75;
        double xm = 1.0 - x;
        double result;
        if (xm < 1.0 / D1) {
            result = N1 * xm * xm;
        } else if (xm < 2.0 / D1) {
            double d = xm - 1.5 / D1;
            result = N1 * d * d + 0.75;
        } else if (xm < 2.5 / D1) {
            double d = xm - 2.25 / D1;
            result = N1 * d * d + 0.9375;
        } else {
            double d = xm - 2.625 / D1;
            result = N1 * d * d + 0.984375;
        }
        return 1.0 - result;
    }

    private static double sineInOut(double x)    { double x2 = x * 2.0; if (x2 < 1.0) return sineIn(x2) / 2.0; return 1.0 - sineIn(2.0 - x2) / 2.0; }
    private static double quadInOut(double x)    { double x2 = x * 2.0; if (x2 < 1.0) return quadIn(x2) / 2.0; return 1.0 - quadIn(2.0 - x2) / 2.0; }
    private static double cubicInOut(double x)   { double x2 = x * 2.0; if (x2 < 1.0) return cubicIn(x2) / 2.0; return 1.0 - cubicIn(2.0 - x2) / 2.0; }
    private static double quartInOut(double x)   { double x2 = x * 2.0; if (x2 < 1.0) return quartIn(x2) / 2.0; return 1.0 - quartIn(2.0 - x2) / 2.0; }
    private static double quintInOut(double x)   { double x2 = x * 2.0; if (x2 < 1.0) return quintIn(x2) / 2.0; return 1.0 - quintIn(2.0 - x2) / 2.0; }
    private static double expoInOut(double x)    { double x2 = x * 2.0; if (x2 < 1.0) return expoIn(x2) / 2.0; return 1.0 - expoIn(2.0 - x2) / 2.0; }
    private static double circInOut(double x)    { double x2 = x * 2.0; if (x2 < 1.0) return circIn(x2) / 2.0; return 1.0 - circIn(2.0 - x2) / 2.0; }
    private static double backInOut(double x)    { double x2 = x * 2.0; if (x2 < 1.0) return backIn(x2) / 2.0; return 1.0 - backIn(2.0 - x2) / 2.0; }
    private static double elasticInOut(double x) { double x2 = x * 2.0; if (x2 < 1.0) return elasticIn(x2) / 2.0; return 1.0 - elasticIn(2.0 - x2) / 2.0; }
    private static double bounceInOut(double x)  { double x2 = x * 2.0; if (x2 < 1.0) return bounceIn(x2) / 2.0; return 1.0 - bounceIn(2.0 - x2) / 2.0; }

    /** Maps RPE easing type (1..29, 1-based) to tween ID. */
    public static final int[] RPE_TWEEN_MAP = new int[]{
        2, 2, 4, 3, 7, 6, 5, 8, 10, 9, 13, 12, 11, 14, 16, 15, 19, 18, 22, 21, 25, 24, 23, 26, 28, 27, 31, 30, 32, 29,
    };

    public static int rpeEasingToTweenId(int rpeEasingType) {
        int idx = rpeEasingType + 1;
        if (idx < 0) idx = 0;
        if (idx >= RPE_TWEEN_MAP.length) idx = 0;
        return RPE_TWEEN_MAP[idx];
    }

    /** Apply 0-based easing (0..28). Maps to tween ID via rpeEasingToTweenId. */
    public static double apply(int easingType, double p) {
        int tweenId = rpeEasingToTweenId(easingType);
        return applyTween(tweenId, p);
    }

    /** Apply the analytical integral of the easing function at x. */
    public static double applyTweenInt(int tweenId, double x) {
        x = clamp01(x);
        if (tweenId <= 0) return 0.0;
        if (tweenId == 1) return x;
        if (tweenId == 2) return x * x / 2.0;
        switch (tweenId) {
            case 3:  return intSineIn(x);
            case 4:  return intSineOut(x);
            case 5:  return intSineInOut(x);
            case 6:  return intQuadIn(x);
            case 7:  return intQuadOut(x);
            case 8:  return intQuadInOut(x);
            case 9:  return intCubicIn(x);
            case 10: return intCubicOut(x);
            case 11: return intCubicInOut(x);
            case 12: return intQuartIn(x);
            case 13: return intQuartOut(x);
            case 14: return intQuartInOut(x);
            case 15: return intQuintIn(x);
            case 16: return intQuintOut(x);
            case 17: return intQuintInOut(x);
            case 18: return intExpoIn(x);
            case 19: return intExpoOut(x);
            case 20: return intExpoInOut(x);
            case 21: return intCircIn(x);
            case 22: return intCircOut(x);
            case 23: return intCircInOut(x);
            case 24: return intBackIn(x);
            case 25: return intBackOut(x);
            case 26: return intBackInOut(x);
            case 27: return intElasticIn(x);
            case 28: return intElasticOut(x);
            case 29: return intElasticInOut(x);
            case 30: return intBounceIn(x);
            case 31: return intBounceOut(x);
            case 32: return intBounceInOut(x);
            default: return x * x / 2.0;
        }
    }

    private static double intSineIn(double x)    { return x - Math.sin(x * Math.PI / 2.0) * (2.0 / Math.PI); }
    private static double intQuadIn(double x)    { return x * x * x / 3.0; }
    private static double intCubicIn(double x)   { return x * x * x * x / 4.0; }
    private static double intQuartIn(double x)   { return x * x * x * x * x / 5.0; }
    private static double intQuintIn(double x)   { return x * x * x * x * x * x / 6.0; }
    private static double intExpoIn(double x)    { double ln2 = Math.log(2); return (Math.pow(2.0, 10.0 * x - 10.0) - Math.pow(2.0, -10.0)) / (10.0 * ln2); }
    private static double intCircIn(double x)    { return x - 0.5 * (x * Math.sqrt(1.0 - x * x) + Math.asin(x)); }
    private static double intBackIn(double x)    { final double C1 = 1.70158; final double C3 = C1 + 1.0; return (C3 * x / 4.0 - C1 / 3.0) * x * x * x; }
    private static double intElasticIn(double x)  { return elasticFAntideriv(x) - elasticFAntideriv(0.0); }

    private static double elasticFAntideriv(double x) {
        final double C4 = (2.0 * Math.PI) / 3.0;
        double a = Math.log(2);
        double b = C4;
        double u = 10.0 * x - 10.0;
        double v = (x * 10.0 - 10.75) * b;
        return -(Math.pow(2.0, u) / (10.0 * (a * a + b * b))) * (a * Math.sin(v) - b * Math.cos(v));
    }

    private static double intBounceIn(double x)  { return x - bounceHAntideriv(1.0) + bounceHAntideriv(1.0 - x); }

    private static double bounceHAntideriv(double u) {
        final double N1 = 7.5625;
        final double D1 = 2.75;
        double end1 = 1.0 / D1;
        double val1 = N1 / 3.0 * end1 * end1 * end1;

        double end2 = 2.0 / D1;
        double c2 = val1 - (N1 / 3.0 * Math.pow(end1 - 1.5 / D1, 3) + 0.75 * end1);
        double val2 = N1 / 3.0 * Math.pow(end2 - 1.5 / D1, 3) + 0.75 * end2 + c2;

        double end3 = 2.5 / D1;
        double c3 = val2 - (N1 / 3.0 * Math.pow(end2 - 2.25 / D1, 3) + 0.9375 * end2);
        double val3 = N1 / 3.0 * Math.pow(end3 - 2.25 / D1, 3) + 0.9375 * end3 + c3;

        if (u < end1) {
            return N1 / 3.0 * u * u * u;
        } else if (u < end2) {
            return N1 / 3.0 * Math.pow(u - 1.5 / D1, 3) + 0.75 * u + c2;
        } else if (u < end3) {
            return N1 / 3.0 * Math.pow(u - 2.25 / D1, 3) + 0.9375 * u + c3;
        } else {
            double c4 = val3 - (N1 / 3.0 * Math.pow(end3 - 2.625 / D1, 3) + 0.984375 * end3);
            return N1 / 3.0 * Math.pow(u - 2.625 / D1, 3) + 0.984375 * u + c4;
        }
    }

    private static double intSineOut(double x)      { return x + intSineIn(1.0 - x) - intSineIn(1.0); }
    private static double intQuadOut(double x)      { return x + intQuadIn(1.0 - x) - intQuadIn(1.0); }
    private static double intCubicOut(double x)     { return x + intCubicIn(1.0 - x) - intCubicIn(1.0); }
    private static double intQuartOut(double x)     { return x + intQuartIn(1.0 - x) - intQuartIn(1.0); }
    private static double intQuintOut(double x)     { return x + intQuintIn(1.0 - x) - intQuintIn(1.0); }
    private static double intExpoOut(double x)      { return x + intExpoIn(1.0 - x) - intExpoIn(1.0); }
    private static double intCircOut(double x)      { return x + intCircIn(1.0 - x) - intCircIn(1.0); }
    private static double intBackOut(double x)      { return x + intBackIn(1.0 - x) - intBackIn(1.0); }
    private static double intElasticOut(double x)   { return x + intElasticIn(1.0 - x) - intElasticIn(1.0); }
    private static double intBounceOut(double x)    { return x + intBounceIn(1.0 - x) - intBounceIn(1.0); }

    private static double intSineInOut(double x)    { double x2 = x * 2.0; if (x2 < 1.0) return intSineIn(x2) / 4.0; return x - 0.5 + intSineIn(2.0 - x2) / 4.0; }
    private static double intQuadInOut(double x)    { double x2 = x * 2.0; if (x2 < 1.0) return intQuadIn(x2) / 4.0; return x - 0.5 + intQuadIn(2.0 - x2) / 4.0; }
    private static double intCubicInOut(double x)   { double x2 = x * 2.0; if (x2 < 1.0) return intCubicIn(x2) / 4.0; return x - 0.5 + intCubicIn(2.0 - x2) / 4.0; }
    private static double intQuartInOut(double x)   { double x2 = x * 2.0; if (x2 < 1.0) return intQuartIn(x2) / 4.0; return x - 0.5 + intQuartIn(2.0 - x2) / 4.0; }
    private static double intQuintInOut(double x)   { double x2 = x * 2.0; if (x2 < 1.0) return intQuintIn(x2) / 4.0; return x - 0.5 + intQuintIn(2.0 - x2) / 4.0; }
    private static double intExpoInOut(double x)    { double x2 = x * 2.0; if (x2 < 1.0) return intExpoIn(x2) / 4.0; return x - 0.5 + intExpoIn(2.0 - x2) / 4.0; }
    private static double intCircInOut(double x)    { double x2 = x * 2.0; if (x2 < 1.0) return intCircIn(x2) / 4.0; return x - 0.5 + intCircIn(2.0 - x2) / 4.0; }
    private static double intBackInOut(double x)    { double x2 = x * 2.0; if (x2 < 1.0) return intBackIn(x2) / 4.0; return x - 0.5 + intBackIn(2.0 - x2) / 4.0; }
    private static double intElasticInOut(double x) { double x2 = x * 2.0; if (x2 < 1.0) return intElasticIn(x2) / 4.0; return x - 0.5 + intElasticIn(2.0 - x2) / 4.0; }
    private static double intBounceInOut(double x)  { double x2 = x * 2.0; if (x2 < 1.0) return intBounceIn(x2) / 4.0; return x - 0.5 + intBounceIn(2.0 - x2) / 4.0; }

    /**
     * Remaps easing to a sub-range [easingLeft, easingRight].
     * arg = lerp(left, right, p); y = (E(arg) - E(left)) / (E(right) - E(left)).
     */
    public static double clampedTween(int tweenId, double p, double left, double right) {
        p = clamp01(p);
        left = clamp01(left);
        right = clamp01(right);

        if (tweenId <= 2 || (Math.abs(left) < 1e-5 && Math.abs(right - 1.0) < 1e-5) || left >= right) {
            return applyTween(tweenId, p);
        }

        double arg = left + (right - left) * p;
        double easeArg = applyTween(tweenId, arg);
        double easeL = applyTween(tweenId, left);
        double easeR = applyTween(tweenId, right);
        double denom = easeR - easeL;
        if (Math.abs(denom) < 1e-9) return p;
        return clamp01((easeArg - easeL) / denom);
    }

    /** Integral of clampedTween. */
    public static double intClampedTween(int tweenId, double p, double left, double right) {
        p = clamp01(p);
        left = clamp01(left);
        right = clamp01(right);

        double easeL = applyTween(tweenId, left);
        double easeR = applyTween(tweenId, right);
        double base = applyTweenInt(tweenId, left);

        double yRangeStart = easeL;
        double yRangeEnd = easeR;
        double denom = yRangeEnd - yRangeStart;
        if (!Double.isFinite(denom) || Math.abs(denom) < 1e-8) {
            return p * p / 2.0;
        }

        double arg = left + (right - left) * p;
        double intVal = applyTweenInt(tweenId, arg) - base - yRangeStart * (arg - left);
        double scale = (right - left) * denom;
        if (Math.abs(scale) < 1e-12) return p * p / 2.0;
        return intVal / scale;
    }

    public static final int BEZIER_LUT_SIZE = 128;

    /** Build a 128-sample LUT for cubic bezier evaluation. */
    public static float[] buildBezierLut(float[] bezierPoints) {
        if (bezierPoints == null || bezierPoints.length < 4) return null;
        float x1 = bezierPoints[0];
        float y1 = bezierPoints[1];
        float x2 = bezierPoints[2];
        float y2 = bezierPoints[3];
        return buildBezierLut(x1, y1, x2, y2, BEZIER_LUT_SIZE);
    }

    public static float[] buildBezierLut(float x1, float y1, float x2, float y2, int lutSize) {
        if (lutSize < 2) lutSize = BEZIER_LUT_SIZE;
        float[] lut = new float[lutSize];
        double step = 1.0 / (lutSize - 1);
        for (int i = 0; i < lutSize; i++) {
            double x = i * step;
            lut[i] = (float) cubicBezierYforX(x, x1, y1, x2, y2);
        }
        return lut;
    }

    /** O(1) bezier evaluation via pre-computed LUT with linear interpolation. */
    public static double lookupBezierLut(float[] lut, double p) {
        if (lut == null || lut.length < 2) return p;
        p = clamp01(p);
        double idx = p * (lut.length - 1);
        int lo = (int) Math.floor(idx);
        int hi = lo + 1;
        if (lo < 0) { lo = 0; hi = 1; }
        if (hi >= lut.length) { hi = lut.length - 1; lo = hi - 1; }
        double frac = idx - lo;
        return lut[lo] + (lut[hi] - lut[lo]) * frac;
    }

    public static String bezierCacheKey(float[] bezierPoints) {
        if (bezierPoints == null || bezierPoints.length < 4) return null;
        int a = Math.round(bezierPoints[0] * 1000f);
        int b = Math.round(bezierPoints[1] * 1000f);
        int c = Math.round(bezierPoints[2] * 1000f);
        int d = Math.round(bezierPoints[3] * 1000f);
        return a + "," + b + "," + c + "," + d;
    }

    /**
     * Evaluate cubic bezier at x using 21-sample LUT + Newton-Raphson refinement.
     */
    public static double cubicBezierYforX(double x, double x1, double y1, double x2, double y2) {
        x = clamp01(x);

        if (!Double.isFinite(x1)) x1 = 0.0;
        if (!Double.isFinite(y1)) y1 = 0.0;
        if (!Double.isFinite(x2)) x2 = 1.0;
        if (!Double.isFinite(y2)) y2 = 1.0;

        if (Math.abs(x1 - y1) < 1e-9 && Math.abs(x2 - y2) < 1e-9) {
            return x;
        }

        final int TABLE_SIZE = 21;
        final double STEP = 1.0 / (TABLE_SIZE - 1);
        double[] sampleTable = new double[TABLE_SIZE];
        for (int i = 0; i < TABLE_SIZE; i++) {
            sampleTable[i] = bezierSampleX(x1, x2, i * STEP);
        }

        int id = (int) (x / STEP);
        if (id >= TABLE_SIZE - 1) id = TABLE_SIZE - 2;
        double dist = (x - sampleTable[id]) / (sampleTable[id + 1] - sampleTable[id]);
        if (!Double.isFinite(dist)) dist = 0.0;
        double initT = STEP * (id + dist);

        double slope = bezierSlopeX(x1, x2, initT);
        double t;
        final double SLOPE_EPS = 1e-7;
        final double NEWTON_MIN_STEP = 1e-3;

        if (slope <= SLOPE_EPS) {
            t = initT;
        } else if (slope >= NEWTON_MIN_STEP) {
            t = initT;
            for (int i = 0; i < 4; i++) {
                double s = bezierSlopeX(x1, x2, t);
                if (s <= SLOPE_EPS) break;
                double diff = bezierSampleX(x1, x2, t) - x;
                t -= diff / s;
            }
        } else {
            double lo = STEP * id;
            double hi = STEP * (id + 1);
            t = (lo + hi) / 2.0;
            for (int i = 0; i < 10; i++) {
                double diff = bezierSampleX(x1, x2, t) - x;
                if (Math.abs(diff) <= 1e-7) break;
                if (diff > 0.0) hi = t;
                else lo = t;
                t = (lo + hi) / 2.0;
            }
        }

        return bezierSampleY(y1, y2, t);
    }

    private static double bezierSampleX(double a1, double a2, double t) {
        double u = 1.0 - t;
        return 3.0 * u * u * t * a1 + 3.0 * u * t * t * a2 + t * t * t;
    }
    private static double bezierSampleY(double a1, double a2, double t) {
        double u = 1.0 - t;
        return 3.0 * u * u * t * a1 + 3.0 * u * t * t * a2 + t * t * t;
    }
    private static double bezierSlopeX(double a1, double a2, double t) {
        double u = 1.0 - t;
        return 3.0 * u * u * a1 + 6.0 * u * t * (a2 - a1) + 3.0 * t * t * (1.0 - a2);
    }
}
