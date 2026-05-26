package com.wuying.phigros.game;

import java.util.List;
import java.util.Locale;

/**
 * Event evaluation utilities: interpolation, speed integration, and easing application.
 */
public final class EventUtils {

    private EventUtils() {}

    public interface TimeAccessor<T> {
        double start(T e);
        double end(T e);
    }

    /** Linear search for the event containing time t. */
    public static <T> int findEvent(double t, List<? extends Object> events, TimeAccessor<T> acc) {
        if (events == null || events.isEmpty()) return -1;
        for (int i = 0; i < events.size(); i++) {
            @SuppressWarnings("unchecked")
            T e = (T) events.get(i);
            if (e == null) continue;
            double st = acc.start(e);
            if (Double.isFinite(st) && t < st) break;
            double ed = acc.end(e);
            if (!Double.isFinite(ed)) ed = st;
            if (t <= ed) return i;
        }
        return -1;
    }

    public static double lerp(double a, double b, double p) {
        return a + (b - a) * p;
    }

    /**
     * Evaluate a 1D line event stream at time t, holding the previous end value between events.
     * Returns 0 before the first event.
     */
    public static double getEventVal(double t, List<LineEvent> events) {
        if (events == null || events.isEmpty()) return 0.0;
        double last = 0.0;
        boolean hasLast = false;
        for (int i = 0; i < events.size(); i++) {
            LineEvent e = events.get(i);
            if (e == null) continue;
            double st = e.startTime;
            if (Double.isFinite(st) && t < st) break;
            double ed = e.endTime;
            if (!Double.isFinite(ed)) ed = st;

            if (t <= ed) {
                double a = Double.isFinite(e.start) ? e.start : last;
                double b = Double.isFinite(e.end) ? e.end : a;
                double dur = ed - st;
                if (dur <= 1e-9) return b;
                double p0 = (t - st) / dur;
                double p = applyEasing(p0, e.easingType, e.easingLeft, e.easingRight, e.bezier, e.bezierPoints, e.bezierLut);
                return lerp(a, b, p);
            }

            double b = Double.isFinite(e.end) ? e.end : e.start;
            if (Double.isFinite(b)) {
                last = b;
                hasLast = true;
            }
        }
        return hasLast ? last : 0.0;
    }

    public static double getMoveEventVal1(double t, List<MoveEvent> events) {
        if (events == null || events.isEmpty()) return Double.NaN;
        double last = Double.NaN;
        for (int i = 0; i < events.size(); i++) {
            MoveEvent e = events.get(i);
            if (e == null) continue;
            double st = e.startTime;
            if (Double.isFinite(st) && t < st) break;
            double ed = e.endTime;
            if (!Double.isFinite(ed)) ed = st;
            if (t <= ed) {
                double a = Double.isFinite(e.start) ? e.start : last;
                double b = Double.isFinite(e.end) ? e.end : a;
                double dur = ed - st;
                if (dur <= 1e-9) return b;
                double p0 = (t - st) / dur;
                double p = applyEasing(p0, e.easingType, e.easingLeft, e.easingRight, e.bezier, e.bezierPoints, e.bezierLut);
                return lerp(a, b, p);
            }
            double b = Double.isFinite(e.end) ? e.end : e.start;
            if (Double.isFinite(b)) last = b;
        }
        return last;
    }

    public static double getMoveEventVal2(double t, List<MoveEvent> events) {
        if (events == null || events.isEmpty()) return Double.NaN;
        double last = Double.NaN;
        for (int i = 0; i < events.size(); i++) {
            MoveEvent e = events.get(i);
            if (e == null) continue;
            double st = e.startTime;
            if (Double.isFinite(st) && t < st) break;
            double ed = e.endTime;
            if (!Double.isFinite(ed)) ed = st;
            if (t <= ed) {
                double a = Double.isFinite(e.start2) ? e.start2 : last;
                double b = Double.isFinite(e.end2) ? e.end2 : a;
                double dur = ed - st;
                if (dur <= 1e-9) return b;
                double p0 = (t - st) / dur;
                double p = applyEasing(p0, e.easingType, e.easingLeft, e.easingRight, e.bezier, e.bezierPoints, e.bezierLut);
                return lerp(a, b, p);
            }
            double b = Double.isFinite(e.end2) ? e.end2 : e.start2;
            if (Double.isFinite(b)) last = b;
        }
        return last;
    }

    public static double speedAt(List<SpeedEvent> events, double t) {
        if (events == null || events.isEmpty()) return 1.0;

        int i = findEvent(t, events, new TimeAccessor<SpeedEvent>() {
            @Override public double start(SpeedEvent e) { return e.startTime; }
            @Override public double end(SpeedEvent e) { return e.endTime; }
        });

        if (i < 0) {
            if (t < events.get(0).startTime) return events.get(0).value;
            SpeedEvent last = events.get(events.size() - 1);
            return Double.isFinite(last.endValue) ? last.endValue : last.value;
        }

        SpeedEvent e = events.get(i);
        double dur = e.endTime - e.startTime;
        if (dur <= 1e-9) return Double.isFinite(e.endValue) ? e.endValue : e.value;

        double p = (t - e.startTime) / dur;
        if (p < 0.0) p = 0.0;
        if (p > 1.0) p = 1.0;

        double v0 = e.value;
        double v1 = Double.isFinite(e.endValue) ? e.endValue : e.value;
        return v0 + (v1 - v0) * p;
    }

    /**
     * Initialize speed events with integral-based floorPosition.
     *
     * <p>For easingType == 0: constant speed (trapezoidal).
     * For easingType <= 1: linear speed ramp with quadIn/quadOut tween.
     * For easingType > 1: integral-based SpeedIntegralTween.
     */
    public static void initSpeedEvents(List<SpeedEvent> events) {
        if (events == null) return;
        double fp = 0;
        for (SpeedEvent e : events) {
            e.floorPosition = fp;
            double dt = (e.endTime - e.startTime);
            if (dt < 0) dt = 0;
            double v0 = e.value;
            double v1 = Double.isFinite(e.endValue) ? e.endValue : e.value;

            e.tweenId = Easing.rpeEasingToTweenId(e.easingType);
            e.isClamped = (e.easingLeft != 0f || e.easingRight != 1f) && (e.easingLeft < e.easingRight);
            e.isBezier = e.bezier && e.bezierPoints != null && e.bezierPoints.length >= 4;

            if (dt <= 1e-9) {
                e.speedK = 0.0;
                e.speedB = v0;
                e.totalHeight = 0.0;
                continue;
            }

            if (e.easingType == 0) {
                e.speedK = 0.0;
                e.speedB = v0;
                e.totalHeight = v0;
                fp += v0 * dt;
            } else if (e.easingType <= 1) {
                e.speedK = 0.0;
                e.speedB = 0.0;

                if (Math.abs(v0 - v1) < 1e-6) {
                    e.tweenId = 2;
                    e.isClamped = false;
                    e.totalHeight = (v0 + v1) / 2.0;
                } else if (Math.abs(v0) > Math.abs(v1)) {
                    double clampedRight = 1.0 - v1 / v0;
                    e.tweenId = 7;
                    e.isClamped = true;
                    e.easingLeft = 0f;
                    e.easingRight = (float) clampedRight;
                    e.totalHeight = (v0 + v1) / 2.0;
                } else {
                    double clampedLeft = v0 / v1;
                    e.tweenId = 6;
                    e.isClamped = true;
                    e.easingLeft = (float) clampedLeft;
                    e.easingRight = 1f;
                    e.totalHeight = (v0 + v1) / 2.0;
                }
                fp += (v0 + v1) * 0.5 * dt;
            } else {
                e.speedK = v1 - v0;
                e.speedB = v0;

                double intY1;
                if (e.isBezier) {
                    intY1 = 0.5;
                } else if (e.isClamped) {
                    intY1 = Easing.intClampedTween(e.tweenId, 1.0, e.easingLeft, e.easingRight);
                } else {
                    intY1 = Easing.applyTweenInt(e.tweenId, 1.0);
                }

                double total = intY1 * e.speedK + e.speedB;
                if (!Double.isFinite(total) || Math.abs(total) < 1e-8) {
                    e.speedK = 0.0;
                    e.speedB = 0.0;
                    e.totalHeight = (v0 + v1) / 2.0;
                    fp += (v0 + v1) * 0.5 * dt;
                } else {
                    e.totalHeight = total;
                    fp += total * dt;
                }
            }
        }
    }

    /**
     * Get floor position at time t using integral-based computation with binary search.
     */
    public static double getFloorPosition(double t, List<SpeedEvent> events) {
        if (events == null || events.isEmpty()) return 0.0;

        int lo = 0;
        int hi = events.size() - 1;
        int idx = -1;
        while (lo <= hi) {
            int mid = (lo + hi) >>> 1;
            SpeedEvent e = events.get(mid);
            if (e == null) {
                idx = -1;
                break;
            }
            double st = e.startTime;
            if (!Double.isFinite(st) || t >= st) {
                idx = mid;
                lo = mid + 1;
            } else {
                hi = mid - 1;
            }
        }

        if (idx < 0) {
            SpeedEvent last = null;
            for (int i = 0; i < events.size(); i++) {
                SpeedEvent e = events.get(i);
                if (e == null) continue;
                double st = e.startTime;
                if (Double.isFinite(st) && t < st) break;
                last = e;
                double ed = e.endTime;
                if (!Double.isFinite(ed)) ed = st;
                if (t <= ed) {
                    return computeFpInEvent(e, t);
                }
            }
            if (last != null) {
                double ed = last.endTime;
                if (!Double.isFinite(ed)) ed = last.startTime;
                double v1 = Double.isFinite(last.endValue) ? last.endValue : last.value;
                double fpEnd = last.floorPosition + last.totalHeight * (ed - last.startTime);
                double dt = t - ed;
                if (dt < 0) dt = 0;
                return fpEnd + v1 * dt;
            }
            SpeedEvent first = events.get(0);
            if (first != null && Double.isFinite(first.startTime)) {
                return first.floorPosition + first.value * (t - first.startTime);
            }
            return 0.0;
        }

        SpeedEvent e = events.get(idx);
        if (e == null) return 0.0;
        double st = e.startTime;
        if (Double.isFinite(st) && t < st) {
            return e.floorPosition + e.value * (t - st);
        }

        double ed = e.endTime;
        if (!Double.isFinite(ed)) ed = st;

        if (t <= ed) {
            return computeFpInEvent(e, t);
        }

        double v1 = Double.isFinite(e.endValue) ? e.endValue : e.value;
        double fpEnd = e.floorPosition + e.totalHeight * (ed - st);
        double dt = t - ed;
        if (dt < 0) dt = 0;
        return fpEnd + v1 * dt;
    }

    private static double computeFpInEvent(SpeedEvent e, double t) {
        double st = e.startTime;
        double ed = e.endTime;
        if (!Double.isFinite(ed)) ed = st;
        double dur = ed - st;
        double dt = t - st;
        if (dt < 0) dt = 0;

        if (dur <= 1e-9) {
            return e.floorPosition;
        }

        double p = dt / dur;
        if (p < 0.0) p = 0.0;
        if (p > 1.0) p = 1.0;

        double v0 = e.value;
        double v1 = Double.isFinite(e.endValue) ? e.endValue : e.value;

        if (e.easingType == 0) {
            return e.floorPosition + v0 * dt;
        }

        if (e.easingType <= 1 && e.speedK == 0.0 && e.speedB == 0.0) {
            double intY;
            if (e.isClamped) {
                intY = Easing.intClampedTween(e.tweenId, p, e.easingLeft, e.easingRight);
            } else {
                intY = Easing.applyTweenInt(e.tweenId, p);
            }
            double total = e.totalHeight;
            if (Math.abs(total) < 1e-9) total = (v0 + v1) / 2.0;
            return e.floorPosition + intY * total * dur;
        }

        if (e.speedK == 0.0 && e.speedB == 0.0) {
            double a = (v1 - v0) / dur;
            return e.floorPosition + v0 * dt + 0.5 * a * dt * dt;
        }

        double intY;
        if (e.isBezier) {
            double a = (v1 - v0) / dur;
            return e.floorPosition + v0 * dt + 0.5 * a * dt * dt;
        } else if (e.isClamped) {
            intY = Easing.intClampedTween(e.tweenId, p, e.easingLeft, e.easingRight);
        } else {
            intY = Easing.applyTweenInt(e.tweenId, p);
        }

        double height = (intY * e.speedK + e.speedB * p) * dur;
        double result = e.floorPosition + height;
        if (!Double.isFinite(result)) {
            double a = (v1 - v0) / dur;
            result = e.floorPosition + v0 * dt + 0.5 * a * dt * dt;
        }
        return result;
    }

    public static double applyEasing(double p, LineEvent e) {
        return applyEasing(p, e.easingType, e.easingLeft, e.easingRight, e.bezier, e.bezierPoints, e.bezierLut);
    }

    private static double applyEasing(double p0,
                                  int easingType,
                                  float easingLeft,
                                  float easingRight,
                                  boolean bezier,
                                  float[] bezierPoints,
                                  float[] bezierLut) {
        double p = p0;
        if (!Double.isFinite(p)) return 0.0;
        p = Easing.clamp01(p);

        if (bezier && bezierPoints != null && bezierPoints.length >= 4) {
            if (bezierLut != null && bezierLut.length >= 2) {
                return Easing.lookupBezierLut(bezierLut, p);
            }
            double x1 = bezierPoints[0];
            double y1 = bezierPoints[1];
            double x2 = bezierPoints[2];
            double y2 = bezierPoints[3];
            return Easing.cubicBezierYforX(p, x1, y1, x2, y2);
        }

        int tweenId = Easing.rpeEasingToTweenId(easingType);

        double l = Double.isFinite(easingLeft) ? easingLeft : 0.0;
        double r = Double.isFinite(easingRight) ? easingRight : 1.0;
        l = Easing.clamp01(l);
        r = Easing.clamp01(r);

        if (tweenId <= 2 || (Math.abs(l) < 1e-5 && Math.abs(r - 1.0) < 1e-5) || l >= r) {
            return Easing.applyTween(tweenId, p);
        }

        return Easing.clampedTween(tweenId, p, l, r);
    }

    // --- Cursor-based overloads (O(1) amortized) ---

    public static double getEventVal(double t, List<LineEvent> events, EventCursor cur) {
        if (events == null || events.isEmpty()) return 0.0;
        final int n = events.size();

        int ci = cur.index;
        if (ci < 0 || ci >= n) ci = 0;

        while (ci < n - 1) {
            LineEvent e = events.get(ci);
            if (e == null) { ci++; continue; }
            double ed = e.endTime;
            if (!Double.isFinite(ed)) ed = e.startTime;
            if (t > ed) { ci++; } else break;
        }
        while (ci > 0) {
            LineEvent e = events.get(ci);
            if (e == null) { ci--; continue; }
            double st = e.startTime;
            if (Double.isFinite(st) && t < st) { ci--; } else break;
        }
        cur.index = ci;

        double last = 0.0;
        boolean hasLast = false;

        if (ci > 0) {
            LineEvent prev = events.get(ci - 1);
            if (prev != null) {
                double b = Double.isFinite(prev.end) ? prev.end : prev.start;
                if (Double.isFinite(b)) { last = b; hasLast = true; }
            }
        }

        for (int i = ci; i < n; i++) {
            LineEvent e = events.get(i);
            if (e == null) continue;
            double st = e.startTime;
            if (Double.isFinite(st) && t < st) break;
            double ed = e.endTime;
            if (!Double.isFinite(ed)) ed = st;
            if (t <= ed) {
                double a = Double.isFinite(e.start) ? e.start : last;
                double b = Double.isFinite(e.end) ? e.end : a;
                double dur = ed - st;
                if (dur <= 1e-9) return b;
                double p0 = (t - st) / dur;
                double p = applyEasing(p0, e.easingType, e.easingLeft, e.easingRight, e.bezier, e.bezierPoints, e.bezierLut);
                return lerp(a, b, p);
            }
            double b = Double.isFinite(e.end) ? e.end : e.start;
            if (Double.isFinite(b)) { last = b; hasLast = true; }
        }
        return hasLast ? last : 0.0;
    }

    public static double getMoveEventVal1(double t, List<MoveEvent> events, EventCursor cur) {
        if (events == null || events.isEmpty()) return Double.NaN;
        final int n = events.size();
        int ci = cur.index;
        if (ci < 0 || ci >= n) ci = 0;
        while (ci < n - 1) {
            MoveEvent e = events.get(ci);
            if (e == null) { ci++; continue; }
            double ed = e.endTime; if (!Double.isFinite(ed)) ed = e.startTime;
            if (t > ed) { ci++; } else break;
        }
        while (ci > 0) {
            MoveEvent e = events.get(ci);
            if (e == null) { ci--; continue; }
            double st = e.startTime;
            if (Double.isFinite(st) && t < st) { ci--; } else break;
        }
        cur.index = ci;

        double last = Double.NaN;
        if (ci > 0) {
            MoveEvent prev = events.get(ci - 1);
            if (prev != null) {
                double b = Double.isFinite(prev.end) ? prev.end : prev.start;
                if (Double.isFinite(b)) last = b;
            }
        }
        for (int i = ci; i < n; i++) {
            MoveEvent e = events.get(i);
            if (e == null) continue;
            double st = e.startTime;
            if (Double.isFinite(st) && t < st) break;
            double ed = e.endTime; if (!Double.isFinite(ed)) ed = st;
            if (t <= ed) {
                double a = Double.isFinite(e.start) ? e.start : last;
                double b = Double.isFinite(e.end) ? e.end : a;
                double dur = ed - st;
                if (dur <= 1e-9) return b;
                double p0 = (t - st) / dur;
                double p = applyEasing(p0, e.easingType, e.easingLeft, e.easingRight, e.bezier, e.bezierPoints, e.bezierLut);
                return lerp(a, b, p);
            }
            double b = Double.isFinite(e.end) ? e.end : e.start;
            if (Double.isFinite(b)) last = b;
        }
        return last;
    }

    public static double getMoveEventVal2(double t, List<MoveEvent> events, EventCursor cur) {
        if (events == null || events.isEmpty()) return Double.NaN;
        final int n = events.size();
        int ci = cur.index;
        if (ci < 0 || ci >= n) ci = 0;
        while (ci < n - 1) {
            MoveEvent e = events.get(ci);
            if (e == null) { ci++; continue; }
            double ed = e.endTime; if (!Double.isFinite(ed)) ed = e.startTime;
            if (t > ed) { ci++; } else break;
        }
        while (ci > 0) {
            MoveEvent e = events.get(ci);
            if (e == null) { ci--; continue; }
            double st = e.startTime;
            if (Double.isFinite(st) && t < st) { ci--; } else break;
        }
        cur.index = ci;

        double last = Double.NaN;
        if (ci > 0) {
            MoveEvent prev = events.get(ci - 1);
            if (prev != null) {
                double b = Double.isFinite(prev.end2) ? prev.end2 : prev.start2;
                if (Double.isFinite(b)) last = b;
            }
        }
        for (int i = ci; i < n; i++) {
            MoveEvent e = events.get(i);
            if (e == null) continue;
            double st = e.startTime;
            if (Double.isFinite(st) && t < st) break;
            double ed = e.endTime; if (!Double.isFinite(ed)) ed = st;
            if (t <= ed) {
                double a = Double.isFinite(e.start2) ? e.start2 : last;
                double b = Double.isFinite(e.end2) ? e.end2 : a;
                double dur = ed - st;
                if (dur <= 1e-9) return b;
                double p0 = (t - st) / dur;
                double p = applyEasing(p0, e.easingType, e.easingLeft, e.easingRight, e.bezier, e.bezierPoints, e.bezierLut);
                return lerp(a, b, p);
            }
            double b = Double.isFinite(e.end2) ? e.end2 : e.start2;
            if (Double.isFinite(b)) last = b;
        }
        return last;
    }

    public static float[] getColorVal(double t, List<ColorEvent> events, EventCursor cur) {
        return getColorValInto(t, events, cur, null);
    }

    public static float[] getColorValInto(double t, List<ColorEvent> events, EventCursor cur, float[] out) {
        if (events == null || events.isEmpty()) return null;
        final int n = events.size();
        int ci = cur.index;
        if (ci < 0 || ci >= n) ci = 0;
        while (ci < n - 1) {
            ColorEvent e = events.get(ci);
            if (e == null) { ci++; continue; }
            double ed = e.endTime; if (!Double.isFinite(ed)) ed = e.startTime;
            if (t > ed) { ci++; } else break;
        }
        while (ci > 0) {
            ColorEvent e = events.get(ci);
            if (e == null) { ci--; continue; }
            double st = e.startTime;
            if (Double.isFinite(st) && t < st) { ci--; } else break;
        }
        cur.index = ci;

        boolean hasLast = false;
        float lastR = 0f;
        float lastG = 0f;
        float lastB = 0f;
        if (ci > 0) {
            ColorEvent prev = events.get(ci - 1);
            if (prev != null) {
                lastR = prev.endR;
                lastG = prev.endG;
                lastB = prev.endB;
                hasLast = true;
            }
        }
        for (int i = ci; i < n; i++) {
            ColorEvent e = events.get(i);
            if (e == null) continue;
            double st = e.startTime;
            if (Double.isFinite(st) && t < st) break;
            double ed = e.endTime; if (!Double.isFinite(ed)) ed = st;
            if (t <= ed) {
                double dur = ed - st;
                if (out == null || out.length < 3) out = new float[3];
                if (dur <= 1e-9) {
                    out[0] = e.endR;
                    out[1] = e.endG;
                    out[2] = e.endB;
                } else {
                    double p = (t - st) / dur;
                    out[0] = (float) lerp(e.startR, e.endR, p);
                    out[1] = (float) lerp(e.startG, e.endG, p);
                    out[2] = (float) lerp(e.startB, e.endB, p);
                }
                return out;
            }
            lastR = e.endR;
            lastG = e.endG;
            lastB = e.endB;
            hasLast = true;
        }
        if (!hasLast) return null;
        if (out == null || out.length < 3) out = new float[3];
        out[0] = lastR;
        out[1] = lastG;
        out[2] = lastB;
        return out;
    }

    public static String getTextVal(double t, List<TextEvent> events, EventCursor cur) {
        if (events == null || events.isEmpty()) return null;
        final int n = events.size();
        int ci = cur.index;
        if (ci < 0 || ci >= n) ci = 0;
        while (ci < n - 1) {
            TextEvent e = events.get(ci);
            if (e == null) { ci++; continue; }
            double ed = e.endTime; if (!Double.isFinite(ed)) ed = e.startTime;
            if (t > ed) { ci++; } else break;
        }
        while (ci > 0) {
            TextEvent e = events.get(ci);
            if (e == null) { ci--; continue; }
            double st = e.startTime;
            if (Double.isFinite(st) && t < st) { ci--; } else break;
        }
        cur.index = ci;

        String last = null;
        if (ci > 0) {
            TextEvent prev = events.get(ci - 1);
            if (prev != null) last = prev.endText != null ? prev.endText : prev.text;
        }
        for (int i = ci; i < n; i++) {
            TextEvent e = events.get(i);
            if (e == null) continue;
            double st = e.startTime;
            if (Double.isFinite(st) && t < st) break;
            double ed = e.endTime; if (!Double.isFinite(ed)) ed = st;
            if (t <= ed) {
                String startText = e.text;
                String endText = e.endText;
                if (endText == null || endText.equals(startText)) {
                    return stripStaticTextMarkers(startText);
                }
                double dur = ed - st;
                if (dur <= 1e-9) return stripStaticTextMarkers(endText);
                double p0 = (t - st) / dur;
                double p = applyEasing(p0, e.easingType, e.easingLeft, e.easingRight, false, null, null);
                return calculateTextValue(startText, endText, p);
            }
            last = e.endText != null ? e.endText : e.text;
        }
        return last != null ? stripStaticTextMarkers(last) : null;
    }

    // --- Non-cursor overloads ---

    public static float[] getColorVal(double t, List<ColorEvent> events) {
        if (events == null || events.isEmpty()) return null;

        float[] last = null;
        for (int i = 0; i < events.size(); i++) {
            ColorEvent e = events.get(i);
            if (e == null) continue;
            double st = e.startTime;
            if (Double.isFinite(st) && t < st) break;
            double ed = e.endTime;
            if (!Double.isFinite(ed)) ed = st;
            if (t <= ed) {
                double dur = ed - st;
                if (dur <= 1e-9) return new float[]{e.endR, e.endG, e.endB};
                double p = (t - st) / dur;
                return new float[]{
                        (float) lerp(e.startR, e.endR, p),
                        (float) lerp(e.startG, e.endG, p),
                        (float) lerp(e.startB, e.endB, p)
                };
            }
            last = new float[]{e.endR, e.endG, e.endB};
        }
        return last;
    }

    public static String getTextVal(double t, List<TextEvent> events) {
        if (events == null || events.isEmpty()) return null;

        String last = null;
        for (int i = 0; i < events.size(); i++) {
            TextEvent e = events.get(i);
            if (e == null) continue;
            double st = e.startTime;
            if (Double.isFinite(st) && t < st) break;
            double ed = e.endTime;
            if (!Double.isFinite(ed)) ed = st;
            if (t <= ed) {
                String startText = e.text;
                String endText = e.endText;
                if (endText == null || endText.equals(startText)) {
                    return stripStaticTextMarkers(startText);
                }
                double dur = ed - st;
                if (dur <= 1e-9) return stripStaticTextMarkers(endText);
                double p0 = (t - st) / dur;
                double p = applyEasing(p0, e.easingType, e.easingLeft, e.easingRight, false, null, null);
                return calculateTextValue(startText, endText, p);
            }
            last = e.endText != null ? e.endText : e.text;
        }
        return last != null ? stripStaticTextMarkers(last) : null;
    }

    /**
     * Removes %P% and %T% markers from static text. For %P%, formats the numeric value.
     */
    private static String stripStaticTextMarkers(String text) {
        if (text == null) return null;
        if (text.contains("%P%")) {
            try {
                String numStr = text.replace("%P%", "");
                double num = Double.parseDouble(numStr);
                if (Math.rint(num) == num) {
                    return String.valueOf((int) num);
                } else {
                    return String.format(Locale.US, "%.3f", num);
                }
            } catch (NumberFormatException e) {
                return text.replace("%P%", "");
            }
        }
        if (text.contains("%T%")) {
            return text.replace("%T%", "");
        }
        return text;
    }

    /**
     * Interpolate between two text strings.
     *   {@code %P%} suffix: numeric interpolation ("50%P%" → "75%P%" at 50% = "62")
     *   Start is a prefix of end: progressively reveal characters from end
     *   End is a prefix of start: progressively remove characters from start
     *   Otherwise: snap to end at progress >= 1
     */
    public static String calculateTextValue(String start, String end, double progress) {
        if (start.contains("%P%") && end.contains("%P%")) {
            try {
                String startNumStr = start.replace("%P%", "");
                String endNumStr = end.replace("%P%", "");
                double startNumeric = Double.parseDouble(startNumStr);
                double endNumeric = Double.parseDouble(endNumStr);
                double v = startNumeric + (endNumeric - startNumeric) * progress;
                if (Math.rint(startNumeric) == startNumeric && Math.rint(endNumeric) == endNumeric) {
                    return String.valueOf((int) Math.floor(v));
                } else {
                    return String.format(Locale.US, "%.3f", v);
                }
            } catch (NumberFormatException ignored) {
            }
        }

        if (start.contains("%T%") && end.contains("%T%")) {
            String sBase = start.replace("%T%", "");
            String eBase = end.replace("%T%", "");
            if (!sBase.isEmpty() || !eBase.isEmpty()) {
                int maxLen = Math.max(sBase.length(), eBase.length());
                int between = (int) Math.floor(maxLen * progress);
                if (between < 0) between = 0;
                if (between > maxLen) between = maxLen;
                StringBuilder sb = new StringBuilder();
                for (int i = 0; i < maxLen; i++) {
                    if (i < between) {
                        sb.append(i < eBase.length() ? eBase.charAt(i) : ' ');
                    } else {
                        sb.append(i < sBase.length() ? sBase.charAt(i) : ' ');
                    }
                }
                return sb.toString();
            }
        }

        if (start.startsWith(end)) {
            return end + start.substring(
                end.length(),
                (int) Math.floor((start.length() - end.length()) * (1.0 - progress)) + end.length()
            );
        }

        if (end.startsWith(start)) {
            return start + end.substring(
                start.length(),
                (int) Math.floor((end.length() - start.length()) * progress) + start.length()
            );
        }

        return progress >= 1.0 ? end : start;
    }

    /**
     * Get GIF playback position at time t for storyboard animated textures.
     * Returns NaN when no GIF event is active (normal playback).
     */
    public static float getGifVal(double t, List<GifEvent> events, EventCursor cur) {
        if (events == null || events.isEmpty()) return Float.NaN;
        final int n = events.size();
        int ci = cur.index;
        if (ci < 0 || ci >= n) ci = 0;
        while (ci < n - 1) {
            GifEvent e = events.get(ci);
            if (e == null) { ci++; continue; }
            double ed = e.endTime; if (!Double.isFinite(ed)) ed = e.startTime;
            if (t > ed) { ci++; } else break;
        }
        while (ci > 0) {
            GifEvent e = events.get(ci);
            if (e == null) { ci--; continue; }
            double st = e.startTime;
            if (Double.isFinite(st) && t < st) { ci--; } else break;
        }
        cur.index = ci;

        for (int i = ci; i < n; i++) {
            GifEvent e = events.get(i);
            if (e == null) continue;
            double st = e.startTime;
            if (Double.isFinite(st) && t < st) break;
            double ed = e.endTime; if (!Double.isFinite(ed)) ed = st;
            if (t <= ed) {
                double dur = ed - st;
                if (dur <= 1e-9) return (float) e.end;
                double p0 = (t - st) / dur;
                double p = applyEasing(p0, e.easingType, e.easingLeft, e.easingRight, false, null, null);
                return (float) lerp(e.start, e.end, p);
            }
        }
        return Float.NaN;
    }
}
