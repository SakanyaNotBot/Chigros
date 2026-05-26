package com.wuying.phigros.game;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;

/**
 * A judge line with notes, event layers, and visual properties.
 */
public class JudgeLine {

    public transient BpmTimeline bpmTimeline;
    public double bpm;

    public double bpmfactor = 1.0;

    /** Optional RPE event layers. Move/rotate/alpha are summed across layers. */
    public List<EventLayer> eventLayers;

    public List<SpeedEvent> speedEvents;
    public List<LineEvent> judgeLineRotateEvents;
    public List<MoveEvent> judgeLineMoveEvents;
    public List<LineEvent> judgeLineDisappearEvents;

    public List<Note> notesAbove;
    public List<Note> notesBelow;

    // --- RePhiEdit / storyboard extensions ---

    /** Drawing order. Smaller = earlier (behind). Default 0. */
    public int zOrder = 0;

    public int attachUiElementId = 0;
    public boolean isCover = false;

    /** Father line index (RePhiEdit). -1 = none. */
    public int father = -1;

    public boolean rotateWithFather = false;

    public boolean invertRotation = true;

    public String texture = null;

    /** If true, RPE scaleX/scaleY are in pixels (1350x900 RPE space). */
    public boolean rpeTextureScaleInPixels = false;

    public boolean useOfficialScale = false;
    public boolean textureIsGif = false;

    public List<GifEvent> gifEvents;

    public String text = null;

    public List<MoveEvent> judgeLineScaleEvents;
    public List<LineEvent> judgeLineScaleXEvents;
    public List<LineEvent> judgeLineScaleYEvents;
    public List<LineEvent> judgeLineInclineEvents;
    public List<ColorEvent> judgeLineColorEvents;
    public List<TextEvent> judgeLineTextEvents;

    /** Pivot anchor for storyboard visuals (0..1). Default center. */
    public float anchorX = 0.5f;
    public float anchorY = 0.5f;

    // --- Runtime ---

    public transient List<Note> notes;

    public transient int index = -1;
    public transient JudgeLine fatherLine = null;

    /** Cached last frame state, avoids re-computing for multiple render passes. */
    public transient StateHolder lastState = null;
    public transient double lastStateTimeSec = Double.NaN;
    private transient final float[] tmpColor = new float[3];

    // Event cursors for O(1) amortized per-frame lookup
    public transient EventCursor curRotate = new EventCursor();
    public transient EventCursor curMove1 = new EventCursor();
    public transient EventCursor curMove2 = new EventCursor();
    public transient EventCursor curDisappear = new EventCursor();
    public transient EventCursor curScaleX = new EventCursor();
    public transient EventCursor curScaleY = new EventCursor();
    public transient EventCursor curScale1 = new EventCursor();
    public transient EventCursor curScale2 = new EventCursor();
    public transient EventCursor curIncline = new EventCursor();
    public transient EventCursor curColor = new EventCursor();
    public transient EventCursor curText = new EventCursor();
    public transient EventCursor curGif = new EventCursor();

    public transient float gifPosition = Float.NaN;
    public transient int loopStartIndex = 0;

    public void resolveFather(List<JudgeLine> all) {
        if (father >= 0 && all != null && father < all.size()) {
            fatherLine = all.get(father);
        } else {
            fatherLine = null;
        }
    }

    /** Skip notes that have completely finished (ended before t). */
    public void updateLoopStartIndex(double t) {
        if (notes == null) return;
        int n = notes.size();
        double limit = t - 1.0;
        while (loopStartIndex < n) {
            Note note = notes.get(loopStartIndex);
            if (note.holdEndTime < limit) {
                loopStartIndex++;
            } else {
                break;
            }
        }
    }

    /** Convert seconds to per-line normalized beat ticks. */
    public double sec2beat(double sec) {
        return sec / (GameConstants.PGRBEAT / bpm);
    }

    /** Convert seconds to original global beat tick using chart BPM timeline. */
    public double globalSec2beat(double sec) {
        if (bpmTimeline != null) return bpmTimeline.secToBeat(sec);
        return sec2beat(sec);
    }

    /** Convert per-line normalized beat ticks to seconds. */
    public double beat2sec(double beat) {
        return beat * (GameConstants.PGRBEAT / bpm);
    }

    /** Convert original global beat tick to seconds using chart BPM timeline. */
    public double globalBeat2sec(double beat) {
        if (bpmTimeline != null) return bpmTimeline.beatToSec(beat);
        return beat2sec(beat);
    }

    /**
     * RPE event layer. Movement/rotation/alpha are layered; speed is merged during parsing.
     */
    public static class EventLayer {
        public List<LineEvent> judgeLineRotateEvents;
        public List<LineEvent> judgeLineMoveXEvents;
        public List<LineEvent> judgeLineMoveYEvents;
        public List<MoveEvent> judgeLineMoveEvents;
        public List<LineEvent> judgeLineDisappearEvents;

        public transient EventCursor curRotate = new EventCursor();
        public transient EventCursor curMoveX = new EventCursor();
        public transient EventCursor curMoveY = new EventCursor();
        public transient EventCursor curMove1 = new EventCursor();
        public transient EventCursor curMove2 = new EventCursor();
        public transient EventCursor curDisappear = new EventCursor();
    }

    // --- RPE note controls ---

    public static final int NOTE_CTRL_ALPHA = 0;
    public static final int NOTE_CTRL_SCALE = 1;
    public static final int NOTE_CTRL_X = 2;
    public static final int NOTE_CTRL_Y = 3;

    /**
     * Note control point: maps note originY (pixels) to a control value with easing.
     */
    public static final class NoteControlPoint {
        /** Threshold in RPE pixel units (0..900+). */
        public float y;
        /** Control value at this threshold. */
        public float value;
        /** Easing id for the interval [this, next] (0-based, matches RPE 1..29). */
        public int easing;

        public NoteControlPoint() {
        }

        public NoteControlPoint(float y, float value) {
            this(y, value, 0);
        }

        public NoteControlPoint(float y, float value, int easing) {
            this.y = y;
            this.value = value;
            this.easing = easing;
        }
    }

    public List<NoteControlPoint> noteAlphaControls;
    public List<NoteControlPoint> noteScaleControls;
    public List<NoteControlPoint> noteXControls;
    public List<NoteControlPoint> noteYControls;

    /**
     * Compute a note control value by binary-searching the control point list and applying easing.
     */
    public float calcNoteControl(float originYpx, int type, float def) {
        List<NoteControlPoint> list;
        switch (type) {
            case NOTE_CTRL_ALPHA: list = noteAlphaControls; break;
            case NOTE_CTRL_SCALE: list = noteScaleControls; break;
            case NOTE_CTRL_X: list = noteXControls; break;
            case NOTE_CTRL_Y: list = noteYControls; break;
            default: return def;
        }
        if (list == null || list.isEmpty()) return def;

        int n = list.size();
        NoteControlPoint first = list.get(0);
        if (originYpx <= first.y) return first.value;
        NoteControlPoint last = list.get(n - 1);
        if (originYpx >= last.y) return last.value;

        int lo = 1;
        int hi = n - 1;
        int idx = -1;
        while (lo <= hi) {
            int mid = (lo + hi) >>> 1;
            NoteControlPoint midP = list.get(mid);
            if (midP.y >= originYpx) {
                idx = mid;
                hi = mid - 1;
            } else {
                lo = mid + 1;
            }
        }

        if (idx > 0) {
            NoteControlPoint b = list.get(idx);
            NoteControlPoint a = list.get(idx - 1);

            float dy = b.y - a.y;
            if (dy <= 1e-6f) return b.value;

            float p = (originYpx - a.y) / dy;
            if (p < 0f) p = 0f;
            if (p > 1f) p = 1f;

            int easeIdx = a.easing;
            if (easeIdx < 0) easeIdx = 0;
            if (easeIdx > 28) easeIdx = 28;

            float eased = (float) Easing.apply(easeIdx, p);
            if (!Float.isFinite(eased)) eased = p;
            return a.value + (b.value - a.value) * eased;
        }

        return last.value;
    }

    /**
     * Immutable computed state for a judge line at a given time.
     */
    public static final class State {
        public final float rotateDeg;
        public final float xNorm;
        public final float yNorm;
        public final float alpha;
        public final float scaleX;
        public final float scaleY;
        public final float inclineSinr;
        public final float colorR;
        public final float colorG;
        public final float colorB;
        public final float anchorX;
        public final float anchorY;
        public final boolean hideLine;
        public final boolean hideNotes;
        public final String text;
        public final boolean hasColorEvent;
        public final float gifPosition;

        public State(float rotateDeg, float xNorm, float yNorm, float alpha,
                     float scaleX, float scaleY, float inclineSinr,
                     float colorR, float colorG, float colorB,
                     float anchorX, float anchorY,
                     boolean hideLine, boolean hideNotes,
                     String text, boolean hasColorEvent,
                     float gifPosition) {
            this.rotateDeg = rotateDeg;
            this.xNorm = xNorm;
            this.yNorm = yNorm;
            this.alpha = alpha;
            this.scaleX = scaleX;
            this.scaleY = scaleY;
            this.inclineSinr = inclineSinr;
            this.colorR = colorR;
            this.colorG = colorG;
            this.colorB = colorB;
            this.anchorX = anchorX;
            this.anchorY = anchorY;
            this.hideLine = hideLine;
            this.hideNotes = hideNotes;
            this.text = text;
            this.hasColorEvent = hasColorEvent;
            this.gifPosition = gifPosition;
        }
    }

    /**
     * Mutable state holder reused across frames to avoid per-frame allocations.
     */
    public static final class StateHolder {
        public float rotateDeg;
        public float xNorm;
        public float yNorm;
        public float alpha;
        public float scaleX;
        public float scaleY;
        public float inclineSinr;
        public float colorR;
        public float colorG;
        public float colorB;
        public float anchorX;
        public float anchorY;
        public boolean hideLine;
        public boolean hideNotes;
        public String text;
        public boolean hasColorEvent;
        public float gifPosition = Float.NaN;
    }

    /**
     * Fill a StateHolder with the computed judge-line state at the given time, reusing the holder.
     */
    public StateHolder getStateInto(StateHolder h, double sec, float stageAspectRatio) {
        if (h == null) h = new StateHolder();
        double beat = sec2beat(sec);

        float rotate;
        float x;
        float y;
        float alpha;

        final boolean hasRpeLayers = (eventLayers != null && !eventLayers.isEmpty());

        double cx;
        double cy;

        if (hasRpeLayers) {
            float rSum = 0f;
            float xSum = 0f;
            float ySum = 0f;
            float aSum = 0f;

            for (EventLayer layer : eventLayers) {
                if (layer == null) continue;
                rSum += (float) EventUtils.getEventVal(beat, layer.judgeLineRotateEvents, layer.curRotate);

                float lx;
                float ly;
                if (layer.judgeLineMoveXEvents != null || layer.judgeLineMoveYEvents != null) {
                    lx = (float) EventUtils.getEventVal(beat, layer.judgeLineMoveXEvents, layer.curMoveX);
                    ly = (float) EventUtils.getEventVal(beat, layer.judgeLineMoveYEvents, layer.curMoveY);
                } else {
                    lx = (float) EventUtils.getMoveEventVal1(beat, layer.judgeLineMoveEvents, layer.curMove1);
                    ly = (float) EventUtils.getMoveEventVal2(beat, layer.judgeLineMoveEvents, layer.curMove2);
                }
                if (!Float.isFinite(lx)) lx = 0f;
                if (!Float.isFinite(ly)) ly = 0f;
                xSum += lx;
                ySum += ly;

                float a = (float) EventUtils.getEventVal(beat, layer.judgeLineDisappearEvents, layer.curDisappear);
                if (!Float.isFinite(a)) a = 0f;
                aSum += a;
            }

            rotate = invertRotation ? -rSum : rSum;
            alpha = aSum;

            cx = xSum;
            cy = ySum;
        } else {
            rotate = (float) EventUtils.getEventVal(beat, judgeLineRotateEvents, curRotate);
            if (invertRotation) rotate = -rotate;
            x = (float) EventUtils.getMoveEventVal1(beat, judgeLineMoveEvents, curMove1);
            y = (float) EventUtils.getMoveEventVal2(beat, judgeLineMoveEvents, curMove2);
            alpha = (float) EventUtils.getEventVal(beat, judgeLineDisappearEvents, curDisappear);

            if (!Float.isFinite(x)) x = 0.5f;
            if (!Float.isFinite(y)) y = 0.5f;
            cx = x - 0.5;
            cy = y - 0.5;
        }

        if (!Float.isFinite(rotate)) rotate = 0f;
        if (!Float.isFinite(alpha)) alpha = 0f;

        float sx = 1f;
        float sy = 1f;
        if (judgeLineScaleXEvents != null || judgeLineScaleYEvents != null) {
            sx = (float) EventUtils.getEventVal(beat, judgeLineScaleXEvents, curScaleX);
            sy = (float) EventUtils.getEventVal(beat, judgeLineScaleYEvents, curScaleY);
        } else if (judgeLineScaleEvents != null && !judgeLineScaleEvents.isEmpty()) {
            sx = (float) EventUtils.getMoveEventVal1(beat, judgeLineScaleEvents, curScale1);
            sy = (float) EventUtils.getMoveEventVal2(beat, judgeLineScaleEvents, curScale2);
        }
        if (!Float.isFinite(sx)) sx = 1f;
        if (!Float.isFinite(sy)) sy = 1f;

        float inclineSinr = Float.NaN;
        if (judgeLineInclineEvents != null && !judgeLineInclineEvents.isEmpty()) {
            double ang = EventUtils.getEventVal(beat, judgeLineInclineEvents, curIncline);
            if (Double.isFinite(ang)) {
                inclineSinr = (float) Math.sin(ang);
            }
        }

        float r = GameConstants.PCOLOR[0];
        float g = GameConstants.PCOLOR[1];
        float b2 = GameConstants.PCOLOR[2];
        boolean hasColorEvent = false;

        if ((texture != null && !texture.isEmpty()) ||
            (text != null && !text.isEmpty()) ||
            (judgeLineTextEvents != null && !judgeLineTextEvents.isEmpty()) ||
            attachUiElementId != 0) {
            r = 1f;
            g = 1f;
            b2 = 1f;
        }

        if (judgeLineColorEvents != null && !judgeLineColorEvents.isEmpty()) {
            float[] c = EventUtils.getColorValInto(beat, judgeLineColorEvents, curColor, tmpColor);
            if (c != null && c.length >= 3) {
                r = c[0];
                g = c[1];
                b2 = c[2];
                hasColorEvent = true;
            }
        }

        String resolvedText = this.text;
        if (judgeLineTextEvents != null && !judgeLineTextEvents.isEmpty()) {
            resolvedText = EventUtils.getTextVal(beat, judgeLineTextEvents, curText);
        }

        float resolvedGifPos = Float.NaN;
        if (gifEvents != null && !gifEvents.isEmpty()) {
            resolvedGifPos = EventUtils.getGifVal(beat, gifEvents, curGif);
        }

        if (fatherLine != null) {
            StateHolder fs = null;
            if (fatherLine.lastState != null && fatherLine.lastStateTimeSec == sec) {
                fs = fatherLine.lastState;
            } else {
                fs = fatherLine.fillState(sec, stageAspectRatio);
            }
            if (fs != null) {
                double fx = fs.xNorm - 0.5;
                double fy = 0.5 - fs.yNorm;

                double rad = Math.toRadians(fs.rotateDeg);
                double cos = Math.cos(rad);
                double sin = Math.sin(rad);

                double aspect = stageAspectRatio;
                if (!Double.isFinite(aspect) || aspect <= 1e-6) aspect = 16.0 / 9.0;

                double nx = cx * cos + (cy * sin) / aspect + fx;
                double ny = cy * cos - (cx * sin) * aspect + fy;
                cx = nx;
                cy = ny;
            }
        }

        h.rotateDeg = rotate;
        h.xNorm = (float) (cx + 0.5);
        h.yNorm = (float) (0.5 - cy);
        h.alpha = alpha;
        h.scaleX = sx;
        h.scaleY = sy;
        h.inclineSinr = inclineSinr;
        h.colorR = r;
        h.colorG = g;
        h.colorB = b2;
        h.anchorX = anchorX;
        h.anchorY = anchorY;

        boolean hideLine = false;
        boolean hideNotes = false;
        if (alpha < 0f) {
            int w = (int) Math.floor(-alpha);
            if (w == 1) {
                hideLine = true;
                hideNotes = true;
            }
        }
        h.hideLine = hideLine;
        h.hideNotes = hideNotes;
        h.text = resolvedText;
        h.hasColorEvent = hasColorEvent;
        h.gifPosition = resolvedGifPos;

        lastState = h;
        lastStateTimeSec = sec;
        return h;
    }

    /** Compute and return a fresh state holder. */
    public StateHolder fillState(double sec, float stageAspectRatio) {
        if (lastState != null && lastStateTimeSec == sec) {
            return lastState;
        }
        return getStateInto(lastState != null ? lastState : new StateHolder(), sec, stageAspectRatio);
    }

    public State getState(double sec, float stageAspectRatio) {
        StateHolder h = new StateHolder();
        getStateInto(h, sec, stageAspectRatio);
        lastState = h;
        return new State(
                h.rotateDeg, h.xNorm, h.yNorm, h.alpha,
                h.scaleX, h.scaleY, h.inclineSinr,
                h.colorR, h.colorG, h.colorB,
                h.anchorX, h.anchorY,
                h.hideLine, h.hideNotes,
                h.text, h.hasColorEvent,
                h.gifPosition
        );
    }

    /** Reset all event cursors (call on seek/restart). */
    public void resetCursors() {
        curRotate.reset();
        curMove1.reset();
        curMove2.reset();
        curDisappear.reset();
        curScaleX.reset();
        curScaleY.reset();
        curScale1.reset();
        curScale2.reset();
        curIncline.reset();
        curColor.reset();
        curText.reset();
        curGif.reset();
        gifPosition = Float.NaN;
        lastState = null;
        lastStateTimeSec = Double.NaN;

        loopStartIndex = 0;

        if (eventLayers != null) {
            for (EventLayer layer : eventLayers) {
                if (layer == null) continue;
                layer.curRotate.reset();
                layer.curMoveX.reset();
                layer.curMoveY.reset();
                layer.curMove1.reset();
                layer.curMove2.reset();
                layer.curDisappear.reset();
            }
        }
    }

    /** Merge notesAbove and notesBelow, sorting by time ascending. */
    public void mergeNotes() {
        List<Note> merged = new ArrayList<>();
        if (notesAbove != null) {
            for (Note n : notesAbove) {
                n.isAbove = true;
                merged.add(n);
            }
        }
        if (notesBelow != null) {
            for (Note n : notesBelow) {
                n.isAbove = false;
                merged.add(n);
            }
        }
        Collections.sort(merged, new Comparator<Note>() {
            @Override
            public int compare(Note a, Note b) {
                return Double.compare(a.time, b.time);
            }
        });
        notes = merged;
    }

    private static final Comparator<LineEvent> LINE_EVENT_COMP = new Comparator<LineEvent>() {
        @Override
        public int compare(LineEvent a, LineEvent b) {
            return Double.compare(a.startTime, b.startTime);
        }
    };
    private static final Comparator<MoveEvent> MOVE_EVENT_COMP = new Comparator<MoveEvent>() {
        @Override
        public int compare(MoveEvent a, MoveEvent b) {
            return Double.compare(a.startTime, b.startTime);
        }
    };
    private static final Comparator<SpeedEvent> SPEED_EVENT_COMP = new Comparator<SpeedEvent>() {
        @Override
        public int compare(SpeedEvent a, SpeedEvent b) {
            return Double.compare(a.startTime, b.startTime);
        }
    };
    private static final Comparator<ColorEvent> COLOR_EVENT_COMP = new Comparator<ColorEvent>() {
        @Override
        public int compare(ColorEvent a, ColorEvent b) {
            return Double.compare(a.startTime, b.startTime);
        }
    };
    private static final Comparator<TextEvent> TEXT_EVENT_COMP = new Comparator<TextEvent>() {
        @Override
        public int compare(TextEvent a, TextEvent b) {
            return Double.compare(a.startTime, b.startTime);
        }
    };
    private static final Comparator<GifEvent> GIF_EVENT_COMP = new Comparator<GifEvent>() {
        @Override
        public int compare(GifEvent a, GifEvent b) {
            return Double.compare(a.startTime, b.startTime);
        }
    };

    private static final Comparator<NoteControlPoint> CP_COMP = new Comparator<NoteControlPoint>() {
        @Override
        public int compare(NoteControlPoint a, NoteControlPoint b) {
            return Float.compare(a.y, b.y);
        }
    };

    /** Sort all event lists and initialize speed event floor positions. */
    public void normalize() {
        if (eventLayers != null) {
            for (EventLayer layer : eventLayers) {
                if (layer == null) continue;
                if (layer.judgeLineRotateEvents != null) Collections.sort(layer.judgeLineRotateEvents, LINE_EVENT_COMP);
                if (layer.judgeLineMoveXEvents != null) Collections.sort(layer.judgeLineMoveXEvents, LINE_EVENT_COMP);
                if (layer.judgeLineMoveYEvents != null) Collections.sort(layer.judgeLineMoveYEvents, LINE_EVENT_COMP);
                if (layer.judgeLineMoveEvents != null) Collections.sort(layer.judgeLineMoveEvents, MOVE_EVENT_COMP);
                if (layer.judgeLineDisappearEvents != null) Collections.sort(layer.judgeLineDisappearEvents, LINE_EVENT_COMP);
            }
        }

        if (speedEvents != null) Collections.sort(speedEvents, SPEED_EVENT_COMP);
        if (judgeLineRotateEvents != null) Collections.sort(judgeLineRotateEvents, LINE_EVENT_COMP);
        if (judgeLineMoveEvents != null) Collections.sort(judgeLineMoveEvents, MOVE_EVENT_COMP);
        if (judgeLineDisappearEvents != null) Collections.sort(judgeLineDisappearEvents, LINE_EVENT_COMP);

        if (judgeLineScaleEvents != null) Collections.sort(judgeLineScaleEvents, MOVE_EVENT_COMP);
        if (judgeLineColorEvents != null) Collections.sort(judgeLineColorEvents, COLOR_EVENT_COMP);
        if (judgeLineTextEvents != null) Collections.sort(judgeLineTextEvents, TEXT_EVENT_COMP);
        if (gifEvents != null) Collections.sort(gifEvents, GIF_EVENT_COMP);

        if (judgeLineScaleXEvents != null) Collections.sort(judgeLineScaleXEvents, LINE_EVENT_COMP);
        if (judgeLineScaleYEvents != null) Collections.sort(judgeLineScaleYEvents, LINE_EVENT_COMP);

        if (noteAlphaControls != null) {
            noteAlphaControls = new ArrayList<>(noteAlphaControls);
            Collections.sort(noteAlphaControls, CP_COMP);
        }
        if (noteScaleControls != null) {
            noteScaleControls = new ArrayList<>(noteScaleControls);
            Collections.sort(noteScaleControls, CP_COMP);
        }
        if (noteXControls != null) {
            noteXControls = new ArrayList<>(noteXControls);
            Collections.sort(noteXControls, CP_COMP);
        }
        if (noteYControls != null) {
            noteYControls = new ArrayList<>(noteYControls);
            Collections.sort(noteYControls, CP_COMP);
        }

        if (judgeLineInclineEvents != null) {
            Collections.sort(judgeLineInclineEvents, LINE_EVENT_COMP);
        }

        EventUtils.initSpeedEvents(speedEvents);
    }

    /**
     * Compute speed event floor positions using sequential float32-precision accumulation
     * matching official Phigros behavior.
     */
    public void initSpeedEventsPhi() {
        if (speedEvents == null || speedEvents.isEmpty()) return;
        if (bpm <= 0) bpm = 120.0;

        float y = (float) (speedEvents.get(0).startTime / bpm * 1.875);
        for (SpeedEvent e : speedEvents) {
            if (e == null) continue;
            e.floorPosition = y;
            float dy = (float) ((e.endTime - e.startTime) / bpm * 1.875);
            y += (float) (dy * e.value);
        }
    }
}
