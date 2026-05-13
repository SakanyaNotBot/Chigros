package com.wuying.phigros.game;

import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

public class RpeParentTransformTest {

    @Test
    public void parentRotationUsesStageAspect() throws Exception {
        float stageAspect = 16f / 9f;

        JudgeLine father = new JudgeLine();
        father.bpm = 120.0;
        father.invertRotation = false;
        father.eventLayers = new java.util.ArrayList<>();
        JudgeLine.EventLayer fl = new JudgeLine.EventLayer();
        fl.judgeLineRotateEvents = new java.util.ArrayList<>();
        fl.judgeLineMoveXEvents = new java.util.ArrayList<>();
        fl.judgeLineMoveYEvents = new java.util.ArrayList<>();
        fl.judgeLineDisappearEvents = new java.util.ArrayList<>();
        {
            LineEvent rot = new LineEvent();
            rot.startTime = 0.0;
            rot.endTime = 9999999.0;
            rot.start = 45.0;
            rot.end = 45.0;
            fl.judgeLineRotateEvents.add(rot);
        }
        {
            LineEvent mx = new LineEvent();
            mx.startTime = 0.0;
            mx.endTime = 9999999.0;
            mx.start = 0.0;
            mx.end = 0.0;
            fl.judgeLineMoveXEvents.add(mx);
        }
        {
            LineEvent my = new LineEvent();
            my.startTime = 0.0;
            my.endTime = 9999999.0;
            my.start = 0.0;
            my.end = 0.0;
            fl.judgeLineMoveYEvents.add(my);
        }
        {
            LineEvent a = new LineEvent();
            a.startTime = 0.0;
            a.endTime = 9999999.0;
            a.start = 1.0;
            a.end = 1.0;
            fl.judgeLineDisappearEvents.add(a);
        }
        father.eventLayers.add(fl);

        JudgeLine childA = new JudgeLine();
        childA.bpm = 120.0;
        childA.invertRotation = false;
        childA.fatherLine = father;
        childA.eventLayers = new java.util.ArrayList<>();
        JudgeLine.EventLayer ca = new JudgeLine.EventLayer();
        ca.judgeLineRotateEvents = new java.util.ArrayList<>();
        ca.judgeLineMoveXEvents = new java.util.ArrayList<>();
        ca.judgeLineMoveYEvents = new java.util.ArrayList<>();
        ca.judgeLineDisappearEvents = new java.util.ArrayList<>();
        {
            LineEvent rot = new LineEvent();
            rot.startTime = 0.0;
            rot.endTime = 9999999.0;
            rot.start = 0.0;
            rot.end = 0.0;
            ca.judgeLineRotateEvents.add(rot);
        }
        {
            LineEvent mx = new LineEvent();
            mx.startTime = 0.0;
            mx.endTime = 9999999.0;
            mx.start = 0.0;
            mx.end = 0.0;
            ca.judgeLineMoveXEvents.add(mx);
        }
        {
            LineEvent my = new LineEvent();
            my.startTime = 0.0;
            my.endTime = 9999999.0;
            my.start = -130.0 / 900.0;
            my.end = -130.0 / 900.0;
            ca.judgeLineMoveYEvents.add(my);
        }
        {
            LineEvent a = new LineEvent();
            a.startTime = 0.0;
            a.endTime = 9999999.0;
            a.start = 1.0;
            a.end = 1.0;
            ca.judgeLineDisappearEvents.add(a);
        }
        childA.eventLayers.add(ca);

        JudgeLine childB = new JudgeLine();
        childB.bpm = 120.0;
        childB.invertRotation = false;
        childB.fatherLine = father;
        childB.eventLayers = new java.util.ArrayList<>();
        JudgeLine.EventLayer cb = new JudgeLine.EventLayer();
        cb.judgeLineRotateEvents = new java.util.ArrayList<>();
        cb.judgeLineMoveXEvents = new java.util.ArrayList<>();
        cb.judgeLineMoveYEvents = new java.util.ArrayList<>();
        cb.judgeLineDisappearEvents = new java.util.ArrayList<>();
        {
            LineEvent rot = new LineEvent();
            rot.startTime = 0.0;
            rot.endTime = 9999999.0;
            rot.start = 0.0;
            rot.end = 0.0;
            cb.judgeLineRotateEvents.add(rot);
        }
        {
            LineEvent mx = new LineEvent();
            mx.startTime = 0.0;
            mx.endTime = 9999999.0;
            mx.start = 130.0 / 1350.0;
            mx.end = 130.0 / 1350.0;
            cb.judgeLineMoveXEvents.add(mx);
        }
        {
            LineEvent my = new LineEvent();
            my.startTime = 0.0;
            my.endTime = 9999999.0;
            my.start = -130.0 / 900.0;
            my.end = -130.0 / 900.0;
            cb.judgeLineMoveYEvents.add(my);
        }
        {
            LineEvent a = new LineEvent();
            a.startTime = 0.0;
            a.endTime = 9999999.0;
            a.start = 1.0;
            a.end = 1.0;
            cb.judgeLineDisappearEvents.add(a);
        }
        childB.eventLayers.add(cb);

        double sec = father.beat2sec(1.0);

        JudgeLine.State sf = father.getState(sec, stageAspect);
        JudgeLine.State sa = childA.getState(sec, stageAspect);
        JudgeLine.State sb = childB.getState(sec, stageAspect);

        double dx = (sb.xNorm - sa.xNorm) * stageAspect;
        double dy = (sb.yNorm - sa.yNorm);

        double ratio = Math.abs(dy / dx);
        double expected = Math.abs(Math.tan(Math.toRadians(sf.rotateDeg)));
        assertEquals(expected, ratio, 1e-3);
    }
}
