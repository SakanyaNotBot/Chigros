package com.wuying.phigros.game;

import androidx.annotation.NonNull;

import java.util.Collections;
import java.util.List;
import java.util.Map;

/**
 * prpr storyboard shader effect loaded from extra.json.
 *
 * <p>The shader key is either a preset name (e.g. "glitch") or a file path (e.g. "/shader.glsl").
 * Variables may be constant values or animated event streams.
 */
public final class PrprEffect {

    public final double startTimeSec;
    public final double endTimeSec;

    /** If true, the effect is applied to the entire stage (UI + game). */
    public final boolean isGlobal;

    @NonNull
    public final String shader;

    @NonNull
    public final Map<String, PrprVar> vars;

    public PrprEffect(double startTimeSec, double endTimeSec, boolean isGlobal, @NonNull String shader, @NonNull Map<String, PrprVar> vars) {
        this.startTimeSec = startTimeSec;
        this.endTimeSec = endTimeSec;
        this.isGlobal = isGlobal;
        this.shader = shader;
        this.vars = vars;
    }

    public boolean isActive(double tSec) {
        return tSec >= startTimeSec && tSec <= endTimeSec;
    }

    public abstract static class PrprVar {
        private PrprVar() {}
    }

    /** Constant float uniform value. */
    public static final class ConstFloat extends PrprVar {
        public final float value;
        public ConstFloat(float value) {
            this.value = value;
        }
    }

    /** Constant vector (vec2/vec4) uniform value. */
    public static final class ConstVec extends PrprVar {
        public final float[] value;
        public ConstVec(@NonNull float[] value) {
            this.value = value;
        }
    }

    /** Animated float uniform driven by time-based events. */
    public static final class FloatEvents extends PrprVar {
        @NonNull
        public final List<LineEvent> events;
        public FloatEvents(@NonNull List<LineEvent> events) {
            this.events = events;
        }
    }

    @NonNull
    public static List<LineEvent> safeEvents(PrprVar v) {
        if (v instanceof FloatEvents) return ((FloatEvents) v).events;
        return Collections.emptyList();
    }
}
