package com.wuying.phigros.game;

import org.junit.Test;

import java.util.Collections;

import static org.junit.Assert.*;

public class JudgeLineAlphaExtensionTest {

    private static JudgeLine buildLineWithConstantAlpha(double alpha) {
        JudgeLine line = new JudgeLine();
        line.bpm = 120.0;
        line.invertRotation = false;

        LineEvent e = new LineEvent();
        e.startTime = 0.0;
        e.endTime = 999999.0;
        e.start = alpha;
        e.end = alpha;
        e.easingType = 0;
        e.easingLeft = 0f;
        e.easingRight = 1f;
        e.bezier = false;
        e.bezierPoints = null;

        line.judgeLineDisappearEvents = Collections.singletonList(e);
        return line;
    }

    @Test
    public void negativeAlphaIsPreservedForRenderStageExtensions() {
        JudgeLine line = buildLineWithConstantAlpha(-1.0);
        JudgeLine.State st = line.getState(0.0, 16f / 9f);
        assertNotNull(st);
        assertEquals(-1f, st.alpha, 0f);
        assertTrue(st.hideLine);
        assertTrue(st.hideNotes);
    }

    @Test
    public void alphaMinusTwoDoesNotHideLineButKeepsNegativeValue() {
        JudgeLine line = buildLineWithConstantAlpha(-2.0);
        JudgeLine.State st = line.getState(0.0, 16f / 9f);
        assertNotNull(st);
        assertEquals(-2f, st.alpha, 0f);
        assertFalse(st.hideLine);
        assertFalse(st.hideNotes);
    }

    @Test
    public void appearBeforeEncodingKeepsIntegerPart() {
        JudgeLine line = buildLineWithConstantAlpha(-200.0);
        JudgeLine.State st = line.getState(0.0, 16f / 9f);
        assertNotNull(st);
        assertEquals(-200f, st.alpha, 0f);
        assertFalse(st.hideLine);
        assertFalse(st.hideNotes);
        assertEquals(200, (int) Math.floor(-st.alpha));
    }
}

