package com.wuying.phigros.game;

/**
 * Bidirectional beat-tick to seconds converter using piecewise-constant BPM segments.
 *
 * <p>For RPE charts, beats are stored in tick units where 1 tick = 1/32 beat.
 */
public final class BpmTimeline {

    private final double[] beats;
    private final double[] bpms;
    private final double[] secs;

    public BpmTimeline(double[] beats, double[] bpms, double[] secs) {
        if (beats == null || bpms == null || secs == null) {
            throw new IllegalArgumentException("null arrays");
        }
        if (beats.length != bpms.length || beats.length != secs.length) {
            throw new IllegalArgumentException("array length mismatch");
        }
        if (beats.length == 0) {
            throw new IllegalArgumentException("empty timeline");
        }
        this.beats = beats;
        this.bpms = bpms;
        this.secs = secs;
    }

    public int size() {
        return beats.length;
    }

    /** Convert beat ticks to seconds. Beats <= base beat clamp to the timeline base time. */
    public double beatToSec(double beat) {
        if (beat <= beats[0]) {
            return secs[0];
        }
        int last = beats.length - 1;
        if (beat >= beats[last]) {
            return secs[last] + (beat - beats[last]) * (GameConstants.PGRBEAT / bpms[last]);
        }
        int i = findSegmentByBeat(beat);
        return secs[i] + (beat - beats[i]) * (GameConstants.PGRBEAT / bpms[i]);
    }

    /** Convert seconds to beat ticks. Seconds <= base time clamp to base beat. */
    public double secToBeat(double sec) {
        if (sec <= secs[0]) {
            return beats[0];
        }
        int last = secs.length - 1;
        if (sec >= secs[last]) {
            return beats[last] + (sec - secs[last]) * (bpms[last] / GameConstants.PGRBEAT);
        }
        int i = findSegmentBySec(sec);
        return beats[i] + (sec - secs[i]) * (bpms[i] / GameConstants.PGRBEAT);
    }

    private int findSegmentByBeat(double beat) {
        int lo = 0;
        int hi = beats.length - 2;
        while (lo <= hi) {
            int mid = (lo + hi) >>> 1;
            double b0 = beats[mid];
            double b1 = beats[mid + 1];
            if (beat < b0) {
                hi = mid - 1;
            } else if (beat >= b1) {
                lo = mid + 1;
            } else {
                return mid;
            }
        }
        return Math.max(0, Math.min(beats.length - 2, lo));
    }

    private int findSegmentBySec(double sec) {
        int lo = 0;
        int hi = secs.length - 2;
        while (lo <= hi) {
            int mid = (lo + hi) >>> 1;
            double s0 = secs[mid];
            double s1 = secs[mid + 1];
            if (sec < s0) {
                hi = mid - 1;
            } else if (sec >= s1) {
                lo = mid + 1;
            } else {
                return mid;
            }
        }
        return Math.max(0, Math.min(secs.length - 2, lo));
    }
}
