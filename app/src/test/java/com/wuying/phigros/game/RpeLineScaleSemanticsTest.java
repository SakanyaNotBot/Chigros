package com.wuying.phigros.game;

import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

public class RpeLineScaleSemanticsTest {

    @Test
    public void defaultLineScaleXHasPrprHalfFactor() throws Exception {
        String json = "{"
                + "\"META\":{\"offset\":0},"
                + "\"BPMList\":[{\"bpm\":120,\"startTime\":[0,0,1]}],"
                + "\"judgeLineList\":[{"
                + "\"Name\":\"L0\","
                + "\"Texture\":\"line.png\","
                + "\"bpmfactor\":1.0,"
                + "\"father\":-1,"
                + "\"isCover\":1,"
                + "\"eventLayers\":[{"
                + "\"alphaEvents\":[],"
                + "\"moveXEvents\":[],"
                + "\"moveYEvents\":[],"
                + "\"rotateEvents\":[],"
                + "\"speedEvents\":[]"
                + "}],"
                + "\"extended\":{"
                + "\"scaleXEvents\":[{"
                + "\"bezier\":0,"
                + "\"bezierPoints\":[0,0,0,0],"
                + "\"easingLeft\":0.0,"
                + "\"easingRight\":1.0,"
                + "\"easingType\":1,"
                + "\"start\":0.04,"
                + "\"end\":0.045,"
                + "\"startTime\":[531,0,1],"
                + "\"endTime\":[531,1,4]"
                + "}],"
                + "\"scaleYEvents\":[]"
                + "},"
                + "\"notes\":[],"
                + "\"numOfNotes\":0"
                + "}]"
                + "}";
        Chart chart = RpeChartParser.parse(json, null);
        assertNotNull(chart);

        assertNotNull(chart.judgeLineList);
        assertTrue(chart.judgeLineList.size() > 0);

        JudgeLine line = chart.judgeLineList.get(0);
        assertNotNull(line);
        assertNotNull(line.judgeLineScaleXEvents);
        assertTrue(line.judgeLineScaleXEvents.size() > 0);

        LineEvent firstReal = null;
        for (LineEvent e : line.judgeLineScaleXEvents) {
            if (e == null) continue;
            if (e.startTime > 1e-6) {
                if (firstReal == null || e.startTime < firstReal.startTime) firstReal = e;
            }
        }
        assertNotNull(firstReal);

        assertEquals(0.04, firstReal.start, 1e-6);
        assertEquals(0.045, firstReal.end, 1e-6);
    }
}
