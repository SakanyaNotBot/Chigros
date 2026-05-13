package com.wuying.phigros.game;

import org.junit.Test;

import java.io.File;

import static org.junit.Assert.*;

public class OfficialInvisibleDragRangeTest {

    @Test
    public void chartExampleDragsAreBehindLineBeforeHit() throws Exception {
        File f = resolveRepoFile("ChartExample/4790408105874394.json");
        if (f == null) return;

        Chart chart = ChartLoader.loadFromFile(f.getPath());
        assertNotNull(chart);
        assertTrue(chart.formatVersion > 0);
        assertNotNull(chart.judgeLineList);

        Note target = null;
        JudgeLine targetLine = null;
        for (JudgeLine line : chart.judgeLineList) {
            if (line == null || line.notes == null) continue;
            for (Note n : line.notes) {
                if (n != null && n.type == GameConstants.NOTE_DRAG && n.time == 11104.0) {
                    target = n;
                    targetLine = line;
                    break;
                }
            }
            if (target != null) break;
        }

        assertNotNull(target);
        assertNotNull(targetLine);

        double t = target.sect - 0.25;
        assertTrue(t >= 0.0);

        double beatt = targetLine.sec2beat(t);
        double lineFp = EventUtils.getFloorPosition(beatt, targetLine.speedEvents);

        double nFpD = (target.floorPosition - lineFp) * GameConstants.PGRH * (GameConstants.PGRBEAT / targetLine.bpm);
        nFpD *= target.speed;
        float nFp = (float) nFpD;
        float yCtrl = targetLine.calcNoteControl(nFp, JudgeLine.NOTE_CTRL_Y, 1f);
        float visualFp = Float.isFinite(yCtrl) ? (nFp * yCtrl) : nFp;

        assertTrue(visualFp < -0.001f);
    }

    private static File resolveRepoFile(String relativePath) {
        String ud = System.getProperty("user.dir");
        File direct = new File(relativePath);
        if (direct.exists()) return direct;

        File base = new File(ud);
        for (int i = 0; i < 12 && base != null; i++) {
            File f = new File(base, relativePath);
            if (f.exists()) return f;
            base = base.getParentFile();
        }
        return null;
    }
}
