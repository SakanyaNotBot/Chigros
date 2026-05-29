package com.wuying.phigros.game;

final class HitOffsetIndicator {
    static final float MAX_ANGLE_DEG = 60f;
    static final float MOVE_DURATION_SEC = 0.20f;
    static final float RETURN_DELAY_SEC = 0.45f;
    static final float RETURN_DURATION_SEC = 0.35f;
    static final float MARK_LIFE_SEC = 0.90f;

    static final int JUDGE_PERFECT = 0;
    static final int JUDGE_GOOD = 1;
    static final int JUDGE_BAD = 2;
    static final int JUDGE_MISS = 3;

    final Mark[] marks;
    int nextMarkIndex = 0;
    float pointerAngleDeg = 0f;
    float pointerMoveStartAngleDeg = 0f;
    float pointerMoveTargetAngleDeg = 0f;
    double pointerMoveStartSec = 0.0;
    float pointerMoveDurationSec = MOVE_DURATION_SEC;
    double lastHitSec = Double.NEGATIVE_INFINITY;
    boolean returningToCenter = false;
    String latestText = null;
    int latestJudgement = JUDGE_PERFECT;
    double latestTextStartSec = Double.NEGATIVE_INFINITY;

    HitOffsetIndicator(int markCount) {
        int count = Math.max(1, markCount);
        marks = new Mark[count];
        for (int i = 0; i < count; i++) {
            marks[i] = new Mark();
        }
    }

    void reset() {
        nextMarkIndex = 0;
        pointerAngleDeg = 0f;
        pointerMoveStartAngleDeg = 0f;
        pointerMoveTargetAngleDeg = 0f;
        pointerMoveStartSec = 0.0;
        pointerMoveDurationSec = MOVE_DURATION_SEC;
        lastHitSec = Double.NEGATIVE_INFINITY;
        returningToCenter = false;
        latestText = null;
        latestJudgement = JUDGE_PERFECT;
        latestTextStartSec = Double.NEGATIVE_INFINITY;
        for (Mark mark : marks) {
            mark.active = false;
        }
    }

    void addHit(double diffSec, int judgement, double nowSec, double badWindowSec) {
        update(nowSec);
        float angle = angleForDiff(diffSec, badWindowSec);
        Mark mark = marks[nextMarkIndex];
        nextMarkIndex = (nextMarkIndex + 1) % marks.length;
        mark.active = true;
        mark.angleDeg = angle;
        mark.judgement = judgement;
        mark.startSec = nowSec;
        mark.diffMs = diffSec * 1000.0;

        pointerMoveStartAngleDeg = pointerAngleDeg;
        pointerMoveTargetAngleDeg = angle;
        pointerMoveStartSec = nowSec;
        pointerMoveDurationSec = MOVE_DURATION_SEC;
        lastHitSec = nowSec;
        returningToCenter = false;
        latestJudgement = judgement;
        latestText = formatOffsetMs(diffSec);
        latestTextStartSec = nowSec;
    }

    void update(double nowSec) {
        if (!returningToCenter
                && nowSec - lastHitSec >= RETURN_DELAY_SEC
                && Math.abs(pointerMoveTargetAngleDeg) > 0.01f) {
            applyPointerMove(nowSec);
            pointerMoveStartAngleDeg = pointerAngleDeg;
            pointerMoveTargetAngleDeg = 0f;
            pointerMoveStartSec = nowSec;
            pointerMoveDurationSec = RETURN_DURATION_SEC;
            returningToCenter = true;
        }
        applyPointerMove(nowSec);
        for (Mark mark : marks) {
            if (mark.active && markAge(nowSec, mark) >= 1f) {
                mark.active = false;
            }
        }
    }

    float latestTextAlpha(double nowSec) {
        if (latestText == null) return 0f;
        float age = (float) ((nowSec - latestTextStartSec) / MARK_LIFE_SEC);
        if (age < 0f || age >= 1f) return 0f;
        return fadeOutAlpha(age);
    }

    static float markAge(double nowSec, Mark mark) {
        return (float) ((nowSec - mark.startSec) / MARK_LIFE_SEC);
    }

    static float angleForDiff(double diffSec, double badWindowSec) {
        double denom = Math.max(0.001, Math.abs(badWindowSec));
        return clamp((float) (diffSec / denom), -1f, 1f) * MAX_ANGLE_DEG;
    }

    static int judgementForOffset(double absDiffSec, double perfectWindowSec, double goodWindowSec, double badWindowSec) {
        if (absDiffSec <= perfectWindowSec) return JUDGE_PERFECT;
        if (absDiffSec <= goodWindowSec) return JUDGE_GOOD;
        if (absDiffSec <= badWindowSec) return JUDGE_BAD;
        return JUDGE_MISS;
    }

    static boolean shouldShowCommitMark(int noteType, boolean holdHeadAlreadyShown, int judgement) {
        if (judgement == JUDGE_MISS) return false;
        if (noteType == GameConstants.NOTE_HOLD) return false;
        return true;
    }

    static float fadeOutAlpha(float t) {
        float p = clamp(t, 0f, 1f);
        float inv = 1f - p;
        return inv * inv;
    }

    static String formatOffsetMs(double diffSec) {
        int ms = Math.round((float) (diffSec * 1000.0));
        if (ms > 999) ms = 999;
        if (ms < -999) ms = -999;
        return String.format(java.util.Locale.US, "%+04dms", ms);
    }

    private void applyPointerMove(double nowSec) {
        float duration = Math.max(0.001f, pointerMoveDurationSec);
        float p = (float) ((nowSec - pointerMoveStartSec) / duration);
        if (p >= 1f) {
            pointerAngleDeg = pointerMoveTargetAngleDeg;
            return;
        }
        if (p <= 0f) {
            pointerAngleDeg = pointerMoveStartAngleDeg;
            return;
        }
        float eased = easeOutCubic(p);
        pointerAngleDeg = pointerMoveStartAngleDeg
                + (pointerMoveTargetAngleDeg - pointerMoveStartAngleDeg) * eased;
    }

    private static float easeOutCubic(float t) {
        float p = clamp(t, 0f, 1f);
        float inv = 1f - p;
        return 1f - inv * inv * inv;
    }

    private static float clamp(float v, float lo, float hi) {
        if (v < lo) return lo;
        if (v > hi) return hi;
        return v;
    }

    static final class Mark {
        boolean active;
        float angleDeg;
        int judgement;
        double startSec;
        double diffMs;
    }
}
