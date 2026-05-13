package com.wuying.phigros.game;

import org.junit.Test;

import static org.junit.Assert.*;

public class HoldNegativeSpeedGeometryTest {

    @Test
    public void holdTailDoesNotUseSquaredYControl() {
        float noteFp = 0f;
        float holdLengthPx = -100f;
        float yCtrl = 2f;

        float visualFp = GameRenderer.applyYControlScale(noteFp, yCtrl);
        float signedLength = GameRenderer.applyYControlScale(holdLengthPx, yCtrl);
        float tailFp = visualFp + signedLength;

        assertEquals(-200f, tailFp, 1e-6f);
    }
}

