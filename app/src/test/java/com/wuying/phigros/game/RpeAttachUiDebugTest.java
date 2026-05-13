package com.wuying.phigros.game;

import org.junit.Test;

import java.io.File;

public class RpeAttachUiDebugTest {
    @Test
    public void dumpAttachUiLevelMoveYEvents() throws Exception {
        File f = resolveRepoFile("Demiurge/1343131349.json");
        if (f == null) return;

        Chart c = RpeChartParser.parseFile(f);
        if (c == null || c.judgeLineList == null) {
            throw new AssertionError("Parsed chart is null");
        }

        int idx = -1;
        for (int i = 0; i < c.judgeLineList.size(); i++) {
            JudgeLine l = c.judgeLineList.get(i);
            if (l != null && l.attachUiElementId == 7) {
                idx = i;
                break;
            }
        }
        if (idx < 0) {
            throw new AssertionError("No attachUI=level line found");
        }

        JudgeLine line = c.judgeLineList.get(idx);
        JudgeLine.EventLayer layer = (line.eventLayers != null && !line.eventLayers.isEmpty()) ? line.eventLayers.get(0) : null;
        if (layer == null || layer.judgeLineMoveYEvents == null) {
            throw new AssertionError("Missing moveY events");
        }

        System.out.println("attachUI=level parsedIndex=" + idx + " bpm=" + line.bpm);
        int n = layer.judgeLineMoveYEvents.size();
        System.out.println("moveY events=" + n);
        for (int i = 0; i < n; i++) {
            LineEvent e = layer.judgeLineMoveYEvents.get(i);
            if (e == null) continue;
            System.out.println(
                    i + " st=" + e.startTime + " ed=" + e.endTime + " v0=" + e.start + " v1=" + e.end
            );
        }

        double beatTick = 86.0 * 32.0;
        double y = EventUtils.getEventVal(beatTick, layer.judgeLineMoveYEvents);
        if (Math.abs(y) > 1e-6) {
            throw new AssertionError("Expected moveY=0 at beat=86, got " + y);
        }
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
