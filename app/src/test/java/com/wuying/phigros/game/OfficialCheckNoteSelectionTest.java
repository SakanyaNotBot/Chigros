package com.wuying.phigros.game;

import org.junit.Test;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class OfficialCheckNoteSelectionTest {
    @Test
    public void tapHoldKeepsSelectionAgainstCloserDragOutsideOfficialOverride() {
        assertFalse(GameRenderer.officialCheckNoteCandidateReplaces(
                true,
                GameConstants.NOTE_TAP,
                0.050,
                GameConstants.NOTE_DRAG,
                -0.001,
                0.001,
                0.5,
                0.1));
    }

    @Test
    public void tapHoldCanReplaceDragFlickWhenInsideCurrentWindow() {
        assertTrue(GameRenderer.officialCheckNoteCandidateReplaces(
                true,
                GameConstants.NOTE_FLICK,
                0.020,
                GameConstants.NOTE_HOLD,
                0.025,
                0.025,
                0.1,
                0.5));
    }

    @Test
    public void tapHoldTieUsesJudgeDistanceOnlyInsideTenMilliseconds() {
        assertTrue(GameRenderer.officialCheckNoteCandidateReplaces(
                true,
                GameConstants.NOTE_TAP,
                0.050,
                GameConstants.NOTE_HOLD,
                0.045,
                0.045,
                0.7,
                0.2));

        assertFalse(GameRenderer.officialCheckNoteCandidateReplaces(
                true,
                GameConstants.NOTE_TAP,
                0.050,
                GameConstants.NOTE_HOLD,
                0.030,
                0.030,
                0.7,
                0.2));
    }
}
