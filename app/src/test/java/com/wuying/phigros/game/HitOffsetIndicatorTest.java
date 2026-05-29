package com.wuying.phigros.game;

import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class HitOffsetIndicatorTest {
    @Test
    public void mapsOffsetsToLeftCenterRightAngles() {
        assertEquals(-HitOffsetIndicator.MAX_ANGLE_DEG,
                HitOffsetIndicator.angleForDiff(-0.220, 0.220), 0.001f);
        assertEquals(0f, HitOffsetIndicator.angleForDiff(0.0, 0.220), 0.001f);
        assertEquals(HitOffsetIndicator.MAX_ANGLE_DEG,
                HitOffsetIndicator.angleForDiff(0.220, 0.220), 0.001f);
    }

    @Test
    public void mapsNormalWindowJudgementColors() {
        assertEquals(HitOffsetIndicator.JUDGE_PERFECT,
                HitOffsetIndicator.judgementForOffset(0.080, 0.080, 0.180, 0.220));
        assertEquals(HitOffsetIndicator.JUDGE_GOOD,
                HitOffsetIndicator.judgementForOffset(0.120, 0.080, 0.180, 0.220));
        assertEquals(HitOffsetIndicator.JUDGE_BAD,
                HitOffsetIndicator.judgementForOffset(0.200, 0.080, 0.180, 0.220));
        assertEquals(HitOffsetIndicator.JUDGE_MISS,
                HitOffsetIndicator.judgementForOffset(0.221, 0.080, 0.180, 0.220));
    }

    @Test
    public void mapsChallengeWindowAngles() {
        assertEquals(-HitOffsetIndicator.MAX_ANGLE_DEG,
                HitOffsetIndicator.angleForDiff(-0.140, 0.140), 0.001f);
        assertEquals(HitOffsetIndicator.MAX_ANGLE_DEG * 0.040f / 0.140f,
                HitOffsetIndicator.angleForDiff(0.040, 0.140), 0.001f);
    }

    @Test
    public void holdTailCommitDoesNotDuplicateHeadMark() {
        assertFalse(HitOffsetIndicator.shouldShowCommitMark(
                GameConstants.NOTE_HOLD, false, HitOffsetIndicator.JUDGE_PERFECT));
        assertFalse(HitOffsetIndicator.shouldShowCommitMark(
                GameConstants.NOTE_HOLD, true, HitOffsetIndicator.JUDGE_GOOD));
        assertTrue(HitOffsetIndicator.shouldShowCommitMark(
                GameConstants.NOTE_TAP, false, HitOffsetIndicator.JUDGE_PERFECT));
        assertFalse(HitOffsetIndicator.shouldShowCommitMark(
                GameConstants.NOTE_TAP, false, HitOffsetIndicator.JUDGE_MISS));
    }
}
