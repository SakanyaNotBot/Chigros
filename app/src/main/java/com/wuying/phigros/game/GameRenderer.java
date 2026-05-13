package com.wuying.phigros.game;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Rect;
import android.graphics.Typeface;
import android.opengl.GLES20;
import android.opengl.GLSurfaceView;
import android.opengl.Matrix;
import android.view.MotionEvent;

import android.util.Log;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.wuying.phigros.audio.NativeAudioEngine;

import com.fasterxml.jackson.annotation.JsonAutoDetect;
import com.fasterxml.jackson.annotation.PropertyAccessor;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.io.InputStream;
import java.io.ByteArrayOutputStream;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.FloatBuffer;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import javax.microedition.khronos.egl.EGLConfig;
import javax.microedition.khronos.opengles.GL10;

public class GameRenderer implements GLSurfaceView.Renderer {

    private static final String TAG = "GameRenderer";

    private static final ObjectMapper JSON_MAPPER = new ObjectMapper()
            .configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false)
            .setVisibility(PropertyAccessor.FIELD, JsonAutoDetect.Visibility.ANY)
            .setVisibility(PropertyAccessor.GETTER, JsonAutoDetect.Visibility.NONE)
            .setVisibility(PropertyAccessor.SETTER, JsonAutoDetect.Visibility.NONE)
            .setVisibility(PropertyAccessor.CREATOR, JsonAutoDetect.Visibility.NONE);

    public interface Callback {
        void onExit();
        void onFinished(PlayResult result);

        /**
         * Stage rect in screen space (top-left origin).
         * Used by Activity overlay UI to align with the stage.
         */
        void onStageRectChanged(float left, float top, float width, float height);
    }

    /** Exposed for Activity to decide whether to intercept BACK etc. */
    public boolean isMenuVisible() {
        return menuVisible;
    }

    /** Starts or restarts the intro animation buffer. Caller delays audio start accordingly. */
    public void beginIntro() {
        introStarted = true;
        introStartNs = System.nanoTime();
    }

    private float computeIntroLineScale() {
        if (!introStarted) {
            frameGlobalAlpha = 0f;
            frameIntroLinearT = 0f;
            return 0f;
        }
        long start = introStartNs;
        if (start < 0L) {
            start = System.nanoTime();
            introStartNs = start;
        }
        long now = System.nanoTime();
        float dur = INTRO_DUR_SEC; // 1.2s animation curve (hold is separate)
        if (dur <= 0f) {
            frameGlobalAlpha = 1f;
            frameIntroLinearT = 1f;
            return 1f;
        }
        float t = (float) ((now - start) * 1e-9 / dur);
        if (t <= 0f) {
            frameGlobalAlpha = 0f;
            frameIntroLinearT = 0f;
            return 0f;
        }
        if (t >= 1f) {
            frameGlobalAlpha = 1f;
            frameIntroLinearT = 1f;
            return 1f;
        }
        float lineT = Math.min(t * 1.8f, 1f);
        float scale = 1f - (float) Math.cos(lineT * Math.PI / 2.0);
        frameIntroLinearT = t;
        float quickFade = Math.min(t * 3.0f, 1f);
        frameGlobalAlpha = 0.75f + 0.25f * quickFade;
        return scale;
    }

    /**
     * Exit animation: triggered when music reaches totalTimeSec. Uses wall-clock quart ease-in.
     */
    private float computeOutroAlpha(double musicPosSec) {
        if (!isInOutro) {
            if (musicPosSec >= totalTimeSec) {
                isInOutro = true;
                outroStartWallNs = System.nanoTime();
                outroStartTimeSec = (float) musicPosSec;

                if (chart.clickEffectsSorted != null) {
                    for (ClickEffectItem item : chart.clickEffectsSorted) {
                        if (item != null) {
                            item.animStartCached = false;
                            item.animStartSec = 0f;
                            item.positionCached = false;
                        }
                    }
                }
                clickEffectIndex = chart.clickEffectsSorted != null ? chart.clickEffectsSorted.size() : 0;

            }
        }

        if (!isInOutro) {
            frameOutroLineScale = 1f;
            return 1f;
        }

        long elapsedNs = System.nanoTime() - outroStartWallNs;
        float elapsedSec = elapsedNs / 1_000_000_000f;
        float durSec = OUTRO_DUR_SEC;

        if (elapsedSec <= 0f) {
            frameOutroLineScale = 1f;
            return 1f;
        }
        if (elapsedSec >= durSec) {
            frameOutroLineScale = 0f;
            return 1f;
        }

        float _progress = elapsedSec / durSec;
        float progress = (float) Math.pow(1.0 - _progress, 4.0);
        frameOutroLineScale = progress;
        return 1f;
    }


    /**
     * Combo counter (autoplay version: increments once per note head hit).
     * Read from UI thread.
     */
    public int getCombo() {
        return combo;
    }

    /**
     * Current score (max 1,000,000).
     *
     * <p>Matches prpr scoring:
     * score = (0.9 * acc + 0.1 * maxCombo/totalNotes) * 1,000,000.
     */
    public int getScore() {
        final int n = totalNotes;
        if (n <= 0) return 0;
        double acc = getAccuracyInternal();
        double comboRatio = (double) maxCombo / (double) n;
        double s = (0.9 * acc + 0.1 * comboRatio) * 1_000_000.0;
        if (!Double.isFinite(s)) return 0;
        if (s < 0) s = 0;
        if (s > 1000000.0) s = 1000000.0;
        return (int) Math.round(s);
    }

    public double getAccuracy() {
        return getAccuracyInternal() * 100.0;
    }

    public int getMaxCombo() {
        return maxCombo;
    }

    public int getPerfectCount() {
        return judgeCounts[0];
    }

    public int getGoodCount() {
        return judgeCounts[1];
    }

    public int getBadCount() {
        return judgeCounts[2];
    }

    public int getMissCount() {
        return judgeCounts[3];
    }

    private double getAccuracyInternal() {
        final int n = totalNotes;
        if (n <= 0) return 0.0;
        double acc = (judgeCounts[0] + 0.65 * judgeCounts[1]) / (double) n;
        if (!Double.isFinite(acc)) return 0.0;
        if (acc < 0.0) acc = 0.0;
        if (acc > 1.0) acc = 1.0;
        return acc;
    }

    private final Context context;
    private final Chart chart;

    /** density for dp -> px conversion (used by HUD text sizing/clamps) */
    private final float density;
    private final String backgroundPath;
    private final String songName;
    private final String difficulty;
    private final double totalTimeSec;

    // Settings
    private final float targetAspectRatio; // 0 = follow screen
    private final float keyScale;          // 1.0 = default
    private final float scrollSpeed;       // 1.0 = default (chart note scroll speed multiplier)
    private final boolean mirrorX;         // X-axis mirror (demo)
    private final float musicSpeed;        // playback speed (x0.5 .. x2.0)
    private final float userOffsetSec;     // additional chart offset in seconds
    private final float backgroundDim;     // 0..1
    private final boolean lowResMode;
    private final boolean multiPressHighlight;
    private final boolean apfcIndicator;
    private final boolean autoplay;
    private final boolean challengeMode;
    private final boolean showFps;
    private final boolean showDebugInfo;

    // intro: animation (1.2s) + hold (0.2s) before music/chart starts.
    // During the animation, a fake judge line extends from center to full width.
    // During the hold, the fake line stays at full width, then real lines appear.
    public static final long INTRO_BUFFER_MS = 1400L; // total: 1200ms animation + 200ms hold
    public static final float INTRO_DUR_SEC = 1.2f;   // animation curve duration
    public static final float INTRO_HOLD_SEC = 0.2f;   // hold after animation before transition
    public static final float OUTRO_DUR_SEC = 1.5f;    // 1500ms exit animation
    public static final float OUTRO_WAIT_SEC = 0.0f;   // exit starts immediately at music end
    // HUD in/out animation (Cubic ease, 1.2s)
    private static final float UI_INTRO_DUR_SEC = 1.2f;
    private static final float UI_OUTRO_DUR_SEC = 1.2f;
    private static final float UI_OUTRO_WAIT_SEC = 0.3f;
    // Slide distance (matching 100 logical pixels in 900-tall space ≈ 11.1%)
    private static final float HUD_SLIDE_RATIO = 0.111f;
    private volatile long introStartNs = -1L;
    private volatile boolean introStarted = false;
    private volatile boolean introAutoResumePending = false;
    private volatile long introAutoResumeAtNs = -1L;
    /** Set by restartInternal() to defer beginIntro() to the GL thread. */
    private volatile boolean restartIntroNeedsInit = false;
    private float frameIntroLineScale = 0f;
    private float frameOutroLineScale = 1f;
    private float frameGlobalAlpha = 0f;
    // Linear intro progress (0→1 over INTRO_DUR_SEC), cached for HUD animation
    private float frameIntroLinearT = 0f;
    private boolean isInOutro = false;
    private volatile boolean wasInIntroOrOutro = true; // tracks previous-frame inIntroOrOutro
    /** Audio-playhead value captured when the intro/hold animation finished.
     *  Subtracted from the measured playhead so that the first real chart frame
     *  always starts at t=0 regardless of when the audio engine began reporting. */
    private double musicReferenceAtIntroExit = 0.0;
    private float outroStartTimeSec = -1f;
    private long outroStartWallNs = -1L; // wall-clock based exit animation timing

    // 200ms hold after intro animation completes before real lines appear
    private boolean isInIntroHold = false;
    private long introHoldStartNs = -1L;
    private boolean isIntroHoldDone = false; // prevents re-triggering after hold completes
    private static final long INTRO_HOLD_NS = (long) (INTRO_HOLD_SEC * 1_000_000_000L); // 200ms

    @Nullable
    private final Callback callback;

    // GL size
    private int viewW = 1;
    private int viewH = 1;

    // Stage rect (screen space)
    private float stageL = 0f;
    private float stageT = 0f;
    private float stageW = 1f;
    private float stageH = 1f;

    // Combo (autoplay increments when notes are triggered)
    private volatile int combo = 0;

    // Score/Acc (prpr formula)
    private final int totalNotes;
    private volatile int maxCombo = 0;
    private final int[] judgeCounts = new int[]{0, 0, 0, 0}; // perfect, good, bad, miss
    private volatile int goodEarly = 0;
    private volatile int goodLate = 0;
    private int timingCount = 0;
    private double timingMeanMs = 0.0;
    private double timingM2Ms = 0.0;
    private boolean apStill = true;
    private boolean fcStill = true;

    // Projection
    private final float[] proj = new float[16];

    // Textures
    private Texture texBackground;
    private Texture texPause;
    private Texture texExit;
    private Texture texRestart;
    private Texture texContinue;
    private Texture texTimerLine;

    // HUD texts (song / difficulty / score / combo) rendered in OpenGL so they can be affected by
    // PRPR shaders and pause blur.
    private Typeface hudTypeface;
    private final Paint hudTextPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private Texture hudTexSongTitle;
    private Texture hudTexDifficulty;
    private Texture hudTexScore;
    private Texture hudTexComboNum;
    private Texture hudTexComboLabel;
    private String hudSongTitleText;
    private String hudDifficultyText;
    private String hudScoreText;
    private String hudComboNumText;
    private String hudComboLabelText;
    private float hudLastLineScalePx = -1f;
    private float hudLastSongTextSize = 0f;
    private String hudSongTitleSourceText;
    private String hudDifficultySourceText;
    private int hudLastScoreValue = Integer.MIN_VALUE;
    private int hudLastComboValue = Integer.MIN_VALUE;

    private Texture[][] noteHeadTex = new Texture[5][2]; // [type][morebets]
    private Texture texHold;   // hold.png
    private Texture texHoldMh; // hold_mh.png
    private Texture texHitFx;  // hit_fx.png (atlas)
    private Texture texWhite;  // 1x1 white

    // Storyboard textures (RePhiEdit)
    private final Map<String, Texture> fileTextureCache = new HashMap<>();
    private final Map<String, Texture> textTextureCache = new HashMap<>();
    private final Map<String, Texture> debugTextCache = new HashMap<>();
    /** Debug text format: type#N Ln A/B sec. Type={T,H,D,F}, N=note index, Ln=line number, A/B=above/below. */
    private Paint textPaint;
    private final Rect tmpTextBounds = new Rect();

    /** Cache of pre-decoded GIF frame data (keyed by file path). Each frame is a separate bitmap, matching  approach. */
    private final java.util.Map<String, PreloadedGif> preloadedGifCache = new java.util.HashMap<>();

    private static class PreloadedGif {
        final Bitmap[] frameBitmaps;
        final int[] frameDurationsMs;
        final long totalDurationMs;
        final int frameCount;

        PreloadedGif(Bitmap[] frameBitmaps, int[] frameDurationsMs, long totalDurationMs) {
            this.frameBitmaps = frameBitmaps;
            this.frameDurationsMs = frameDurationsMs;
            this.totalDurationMs = totalDurationMs;
            this.frameCount = frameBitmaps.length;
        }
    }

    // Judge line draw order cache (zOrder)
    private int[] lineDrawOrder;
    private int lineDrawOrderCount = -1;

    // Respack
    private ResPackInfo respack;

    // Programs
    private int progSprite = 0;
    private int progBlur = 0;

    // Sprite shader locations
    private int locPos;
    private int locUv;
    private int locMvp;
    private int locColor;
    private int locTex;

    // Blur shader locations
    private int locBPos;
    private int locBUv;
    private int locBTex;
    private int locBTexelOffset;

    // Buffers
    private FloatBuffer quadPos;
    private FloatBuffer quadUv;
    private final FloatBuffer ndcPos = createNdcQuadPos();
    private int vboQuadNdc;

    // Per-draw reusable buffers to reduce allocations / GC pressure
    private final float[] tmpModel = new float[16];
    private final float[] tmpMvp = new float[16];
    private final float[] tmpWorld = new float[16];
    private final float[] tmpBatchProj = new float[16];
    private final float[] tmpUv = new float[8];
    private final float[] tmpVisibleRange = new float[2];
    private final ArrayList<Texture> noteHeadTextureDrawList = new ArrayList<>();

    // Batch rendering (O(1) draw calls for many sprites)
    private static final int MAX_BATCH_QUADS = 1024;
    private static final int VERTEX_SIZE = 8; // x, y, u, v, r, g, b, a
    private FloatBuffer batchBuffer;
    private int progBatch = 0;
    private int locBatchPos, locBatchUv, locBatchColor, locBatchProj, locBatchTex;
    private int batchCount = 0;

    // Stage-level mirror transform (applied only while drawing chart content)
    private final float[] stagePreTransform = new float[16];
    private boolean inStageSpace = false;
    // When true, renderSceneDirect() uses frameGlobalAlpha=1 regardless of intro state,
    // so the prpr FBO gets an un-faded scene for shader processing.
    private boolean renderForPrprFbo = false;

    // UI rects (screen space)
    private final RectF pauseRect = new RectF();
    private float pauseHitPadPx = 0f;
    private final RectF exitRect = new RectF();
    private final RectF restartRect = new RectF();
    private final RectF continueRect = new RectF();

    // Double-tap pause detection (works with multi-touch, unlike GestureDetector)
    private long lastPauseTapTimeMs = 0;
    private float lastPauseTapX = 0f;
    private float lastPauseTapY = 0f;
    private static final long DOUBLE_TAP_TIMEOUT_MS = 350;
    private static final float DOUBLE_TAP_MAX_DIST_DP = 40f;

    // State
    // These flags may be set from both the GL thread (touch callbacks queued)
    // and the Activity thread (app losing focus). Mark them volatile for visibility.
    private volatile boolean menuVisible = false;
    private volatile boolean needBlurUpdate = false;
    private boolean finishedReported = false;
    private static final int PAUSE_NONE = 0;
    private static final int PAUSE_MENU = 1;
    private static final int PAUSE_COUNTDOWN = 2;
    private static final int PAUSE_FADEOUT = 3;
    private volatile int pauseMode = PAUSE_NONE;
    private volatile long resumeCountdownStartNs = -1L;
    private volatile long resumeFadeOutStartNs = -1L;
    private volatile long resumeFadeUnpauseAtNs = -1L;
    private volatile boolean resumeFadeUnpaused = false;
    private static final float RESUME_COUNTDOWN_STEP_SEC = 1.0f;
    private static final float RESUME_FADE_DUR_SEC = 0.45f;
    private static final float RESUME_START_DELAY_SEC = 0.28f;
    private Texture hudTexCount3;
    private Texture hudTexCount2;
    private Texture hudTexCount1;

    // Render-side smooth playhead: advances a local clock each frame and gently corrects to native playhead.
    private boolean smoothClockInit = false;
    private boolean isMusicStartedFlag = false;
    private long smoothLastFrameNs = 0L;
    private double smoothPlayheadSec = 0.0;
    private double smoothLastRawPlayheadSec = 0.0;
    private double smoothReferencePlayheadSec = 0.0;
    private long smoothReferenceTimeNs = 0L;
    private final java.util.LinkedList<Double> smoothBiases = new java.util.LinkedList<>();
    private double smoothBiasSum = 0.0;
    private static final int SMOOTH_BIAS_HISTORY_SIZE = 60;
    private boolean visualClockInit = false;
    private long visualLastFrameNs = 0L;
    private double visualTimeSec = 0.0;

    // FPS counter for unlimited frame rate mode
    private long fpsCounterLastNs = 0L;
    private int fpsFrameCount = 0;
    private float currentFps = 0f;

    // Autoplay pointer
    private int autoNoteIndex = 0;

    // Click effect pointer
    private int clickEffectIndex = 0;

    private int judgeCursor = 0;

    private static final int JR_PERFECT = 0;
    private static final int JR_GOOD = 1;
    private static final int JR_BAD = 2;
    private static final int JR_MISS = 3;

    private static final double WIN_PERFECT = 0.080;
    private static final double WIN_GOOD = 0.180;
    private static final double WIN_BAD = 0.220;
    private static final double WINC_PERFECT = 0.040;
    private static final double WINC_GOOD = 0.090;
    private static final double WINC_BAD = 0.140;
    private double challengeTimeSum = 0.0;
    private static final int TIME_SUM_FRAMES = 10;
    private final double[] timeSumBuffer = new double[TIME_SUM_FRAMES];
    private int timeSumBufferIndex = 0;
    private int timeSumBufferCount = 0;
    private long timeSumLastFrameNs = 0L;
    private static final double DRAG_MISS_THRESHOLD = 0.100;
    private static final double EARLY_OFFSET = 0.070;
    private static final int HOLD_SAFE_FRAME_INIT = 2;
    private static final double DIST_FACTOR = 0.2;
    private static final float NOTE_WIDTH_RATIO_BASE = 0.13175016f;
    private static final float FLICK_SPEED_THRESHOLD = 0.8f;
    private static final float FLICK_DPI = 275f;
    private static final double HOLD_PARTICLE_INTERVAL_BEATS = 0.5;
    private static final double HOLD_TAIL_EARLY_SETTLE = 0.220;
    private static final double HOLD_DESTROY_DELAY = 0.250;
    private static final float JUDGE_LINE_Y_TOLERANCE = 0.55f;

    private double frameChartTimeSec = 0.0;
    private double framePlayTimeSec = 0.0;

    private final ConcurrentHashMap<Integer, TouchState> activeTouches = new ConcurrentHashMap<>();
    private final Set<Integer> startedTouchIds = Collections.synchronizedSet(new HashSet<>());
    private final ConcurrentHashMap<Integer, FlickTracker> flickTrackers = new ConcurrentHashMap<>();
    private final ArrayList<HitEffect> hitEffects = new ArrayList<>();
    private final ArrayList<BadEffect> badEffects = new ArrayList<>();
    private final float[] tmpNotePos = new float[2];

    // Menu blur FBO
    private FboTex sceneFbo;
    private FboTex blurFbo1;
    private FboTex blurFbo2;

    // Background blur
    private FboTex bgBlurA;
    private FboTex bgBlurB;
    private Texture texBackgroundBlur;

    // prpr storyboard shader effects (extra.json)
    private boolean hasPrprEffects = false;
    private boolean hasPrprNonGlobal = false;
    private final Map<String, PrprShaderProgram> prprPrograms = new HashMap<>();
    private final ArrayList<PrprEffect> tmpActiveNonGlobal = new ArrayList<>();
    private final ArrayList<PrprEffect> tmpActiveGlobal = new ArrayList<>();

    // FBOs for effects
    private FboTex prprFullA;
    private FboTex prprFullB;
    private FboTex prprStageA;
    private FboTex prprStageB;

    public GameRenderer(@NonNull Context context,
                        @NonNull Chart chart,
                        @NonNull String backgroundPath,
                        @NonNull String songName,
                        @NonNull String difficulty,
                        double totalTimeSec,
                        float targetAspectRatio,
                        float keyScale,
                        float scrollSpeed,
                        boolean mirrorX,
                        float musicSpeed,
                        float userOffsetSec,
                        float backgroundDim,
                        boolean lowResMode,
                        boolean multiPressHighlight,
                        boolean apfcIndicator,
                        boolean autoplay,
                        boolean challengeMode,
                        boolean showFps,
                        boolean showDebugInfo,
                        @Nullable Callback callback) {
        this.context = context.getApplicationContext();
        this.chart = chart;
        this.density = this.context.getResources().getDisplayMetrics().density;
        this.backgroundPath = backgroundPath;
        this.songName = songName;
        this.difficulty = difficulty;
        this.totalTimeSec = totalTimeSec;
        this.targetAspectRatio = targetAspectRatio;
        this.keyScale = keyScale <= 0f ? 1f : keyScale;
        this.scrollSpeed = scrollSpeed <= 0f ? 1f : scrollSpeed;
        this.mirrorX = mirrorX;
        this.musicSpeed = (musicSpeed <= 0f) ? 1f : musicSpeed;
        this.userOffsetSec = userOffsetSec;
        this.backgroundDim = (Float.isFinite(backgroundDim) ? MathUtils.clamp(backgroundDim, 0f, 1f) : 0.6f);
        this.lowResMode = lowResMode;
        this.multiPressHighlight = multiPressHighlight;
        this.apfcIndicator = apfcIndicator;
        this.autoplay = autoplay;
        this.challengeMode = challengeMode;
        this.showFps = showFps;
        this.showDebugInfo = showDebugInfo;
        this.callback = callback;

        // HUD font (used for score/combo/song/diff text rendered in GL).
        try {
            this.hudTypeface = Typeface.createFromAsset(this.context.getAssets(), "res/phigros.ttf");
        } catch (Throwable t) {
            this.hudTypeface = Typeface.DEFAULT;
        }
        hudTextPaint.setColor(Color.WHITE);
        hudTextPaint.setTypeface(hudTypeface);
        hudTextPaint.setTextAlign(Paint.Align.LEFT);
        hudTextPaint.setSubpixelText(true);
        hudTextPaint.setDither(true);

        Matrix.setIdentityM(stagePreTransform, 0);

        // Cache total note count for score computation (chart.initRuntime() already called)
        this.totalNotes = chart.allNotesSorted != null ? chart.allNotesSorted.size() : 0;

        if (chart.prprEffects != null && !chart.prprEffects.isEmpty()) {
            hasPrprEffects = true;
            for (PrprEffect e : chart.prprEffects) {
                if (e != null && !e.isGlobal) {
                    hasPrprNonGlobal = true;
                    break;
                }
            }
        }

        // Preload GIF textures on the calling thread (already a background thread
        // in PlayActivity) so the first render frame doesn't stutter on decode.
        preloadGifTextures();
    }

    private void preloadGifTextures() {
        if (chart == null || chart.judgeLineList == null) return;
        for (JudgeLine line : chart.judgeLineList) {
            if (line == null || line.texture == null) continue;
            String path = line.texture;
            if (!path.toLowerCase(java.util.Locale.ROOT).endsWith(".gif")) continue;
            if (preloadedGifCache.containsKey(path)) continue;
            try {
                PreloadedGif pg = decodeGifFrames(path);
                if (pg != null) {
                    preloadedGifCache.put(path, pg);
                }
            } catch (Exception ignored) {
            }
        }
    }

    /**
     * Decode a GIF file into individual frame bitmaps (style: each frame is independent).
     * Runs on any thread; the resulting bitmaps are later uploaded to GL as separate textures.
     */
    @Nullable
    private PreloadedGif decodeGifFrames(String filePath) {
        try {
            pl.droidsonroids.gif.GifDrawable gif = new pl.droidsonroids.gif.GifDrawable(filePath);
            int frameCount = gif.getNumberOfFrames();
            if (frameCount <= 0) { gif.recycle(); return null; }

            int fw = gif.getIntrinsicWidth();
            int fh = gif.getIntrinsicHeight();
            if (fw <= 0 || fh <= 0) { gif.recycle(); return null; }

            // Decode each frame into a separate bitmap with its own delay.
            // Use android-gif-drawable's ability to render each frame independently.
            Bitmap[] frameBitmaps = new Bitmap[frameCount];
            int[] frameDurationsMs = new int[frameCount];
            long totalDurationMs = 0;

            // Decode each frame independently. android-gif-drawable's seekToFrame()
            // handles frame compositing internally, so we draw to a fresh canvas
            // each time and snapshot the result (matching  per-frame buffers).
            Bitmap workBmp = Bitmap.createBitmap(fw, fh, Bitmap.Config.ARGB_8888);
            Canvas workCanvas = new Canvas(workBmp);

            for (int i = 0; i < frameCount; i++) {
                int delay = gif.getFrameDuration(i);
                if (delay <= 0) delay = 100;
                frameDurationsMs[i] = delay;
                totalDurationMs += delay;

                // Clear bitmap to fully transparent before rendering this frame.
                // drawARGB uses SRC_OVER blending and won't actually clear;
                // eraseColor or drawColor(CLEAR) is needed.
                workBmp.eraseColor(android.graphics.Color.TRANSPARENT);
                gif.seekToFrame(i);
                gif.setBounds(0, 0, fw, fh);
                gif.draw(workCanvas);

                Bitmap frameBmp = Bitmap.createBitmap(workBmp); // copy
                frameBitmaps[i] = frameBmp;
            }
            workBmp.recycle();
            gif.recycle();

            return new PreloadedGif(frameBitmaps, frameDurationsMs, totalDurationMs);
        } catch (Exception e) {
            return null;
        }
    }

    /**
     * Upload all preloaded GIF frames to GL textures eagerly, before the first
     * frame is rendered. Each frame becomes a separate GL texture (matching  approach).
     * This must be called on the GL thread (from onSurfaceCreated).
     */
    private void uploadPreloadedGifTextures() {
        if (preloadedGifCache.isEmpty()) return;
        for (java.util.Map.Entry<String, PreloadedGif> entry : preloadedGifCache.entrySet()) {
            String path = entry.getKey();
            PreloadedGif pg = entry.getValue();
            if (pg == null || pg.frameBitmaps == null) continue;
            Texture t = new Texture();
            t.filePath = path;
            t.isGif = true;
            t.gifFrameCount = pg.frameCount;
            t.gifFrameTexIds = new int[pg.frameCount];
            t.gifFrameWidths = new int[pg.frameCount];
            t.gifFrameHeights = new int[pg.frameCount];
            t.gifFrameDurationsMs = pg.frameDurationsMs;
            t.gifTotalDurationMs = pg.totalDurationMs;
            t.gifFrameBitmaps = pg.frameBitmaps; // retain for GL context loss recovery
            t.contextVersion = glContextVersion;

            // Use the first frame's bitmap dimensions for the "main" texture slot
            // (backward compat for non-GIF code paths that check width/height)
            Bitmap firstBmp = pg.frameBitmaps[0];
            t.width = firstBmp.getWidth();
            t.height = firstBmp.getHeight();

            // Generate a dummy main texture ID (required by ensureTextureValid)
            int[] ids = new int[1];
            GLES20.glGenTextures(1, ids, 0);
            t.id = ids[0];
            GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, t.id);
            GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_LINEAR);
            GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_LINEAR);
            GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_S, GLES20.GL_CLAMP_TO_EDGE);
            GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_T, GLES20.GL_CLAMP_TO_EDGE);
            android.opengl.GLUtils.texImage2D(GLES20.GL_TEXTURE_2D, 0, firstBmp, 0);
            GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, 0);

            // Upload each frame as a separate GL texture
            for (int i = 0; i < pg.frameCount; i++) {
                Bitmap bmp = pg.frameBitmaps[i];
                if (bmp == null || bmp.isRecycled()) continue;
                int[] frameIds = new int[1];
                GLES20.glGenTextures(1, frameIds, 0);
                t.gifFrameTexIds[i] = frameIds[0];
                t.gifFrameWidths[i] = bmp.getWidth();
                t.gifFrameHeights[i] = bmp.getHeight();

                GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, frameIds[0]);
                GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_LINEAR);
                GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_LINEAR);
                GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_S, GLES20.GL_CLAMP_TO_EDGE);
                GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_T, GLES20.GL_CLAMP_TO_EDGE);
                android.opengl.GLUtils.texImage2D(GLES20.GL_TEXTURE_2D, 0, bmp, 0);
            }
            GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, 0);

            fileTextureCache.put(path, t);
        }
        preloadedGifCache.clear();
    }

    // ---------------- Touch handlers (called on GL thread) ----------------

    public void onDoubleTap(float x, float y) {
        if (!menuVisible && isInPauseHitArea(x, y)) {
            menuVisible = true;
            pauseMode = PAUSE_MENU;
            resumeCountdownStartNs = -1L;
            resumeFadeOutStartNs = -1L;
            needBlurUpdate = true;
            NativeAudioEngine.pause(true);
        }
    }

    private boolean isInPauseHitArea(float x, float y) {
        float pad = pauseHitPadPx;
        return x >= pauseRect.left - pad && x <= pauseRect.right + pad
                && y >= pauseRect.top - pad && y <= pauseRect.bottom + pad;
    }

    /**
     * Called from Activity thread when the app loses focus (onPause / onWindowFocusChanged(false)).
     * Thread-safe enough for our simple flags (menuVisible/needBlurUpdate are volatile).
     */
    public void requestPauseMenu() {
        menuVisible = true;
        pauseMode = PAUSE_MENU;
        resumeCountdownStartNs = -1L;
        resumeFadeOutStartNs = -1L;
        needBlurUpdate = true;
        NativeAudioEngine.pause(true);
    }

    public void onTouchEvent(@NonNull MotionEvent e) {
        if (autoplay) return;
        if (viewW <= 1 || viewH <= 1) return;

        final int action = e.getActionMasked();
        // Capture pause state once — handleUiTap may change menuVisible mid-event
        final boolean wasPaused = menuVisible;

        // Handle UI interactions (pause double-tap & menu buttons) for ALL pointer events,
        // not just the first finger. GestureDetector only tracks pointer 0, which fails
        // when other fingers are already on screen.
        if (action == MotionEvent.ACTION_DOWN || action == MotionEvent.ACTION_POINTER_DOWN) {
            int idx = e.getActionIndex();
            if (idx >= 0 && idx < e.getPointerCount()) {
                float tx = e.getX(idx);
                float ty = e.getY(idx);
                handleUiTap(tx, ty);
            }
        }

        // Always track touch state (activeTouches, flickTrackers) even during pause.
        // This prevents stale "phantom fingers" and ensures real fingers are tracked
        // when the game resumes. Only startedTouchIds is paused-dependent (type 1 click
        // events should not fire for fingers placed during pause).
        if (action == MotionEvent.ACTION_DOWN || action == MotionEvent.ACTION_POINTER_DOWN) {
            int idx = e.getActionIndex();
            if (idx < 0 || idx >= e.getPointerCount()) return;
            int id = e.getPointerId(idx);
            float x = transformTouchX(e.getX(idx));
            float y = e.getY(idx);
            TouchState st = activeTouches.get(id);
            if (st == null) {
                st = new TouchState();
                activeTouches.put(id, st);
            }
            st.x = x;
            st.y = y;
            // Only mark as "started" (type 1 click event) when game is active,
            // so fingers placed during pause don't trigger tap judgments on resume.
            if (!wasPaused) {
                startedTouchIds.add(id);
            }
            flickTrackers.put(id, new FlickTracker(e.getX(idx), e.getY(idx)));
            return;
        }

        if (action == MotionEvent.ACTION_MOVE) {
            int pc = e.getPointerCount();
            for (int i = 0; i < pc; i++) {
                int id = e.getPointerId(i);
                TouchState st = activeTouches.get(id);
                if (st == null) continue; // Don't re-create for released fingers
                float x = transformTouchX(e.getX(i));
                float y = e.getY(i);
                FlickTracker tr = flickTrackers.get(id);
                if (tr != null) {
                    // move() with raw pixel coords for correct flick speed
                    tr.move(e.getX(i), e.getY(i));
                }
                st.x = x;
                st.y = y;
            }
            return;
        }

        if (action == MotionEvent.ACTION_CANCEL) {
            // ACTION_CANCEL means ALL pointers are cancelled at once.
            // getActionIndex() is undefined for CANCEL, so we must clear everything.
            activeTouches.clear();
            startedTouchIds.clear();
            flickTrackers.clear();
        } else if (action == MotionEvent.ACTION_UP || action == MotionEvent.ACTION_POINTER_UP) {
            int idx = e.getActionIndex();
            if (idx < 0 || idx >= e.getPointerCount()) return;
            int id = e.getPointerId(idx);
            activeTouches.remove(id);
            startedTouchIds.remove(id);
            flickTrackers.remove(id);
        }
    }

    /**
     * Handle UI tap detection for both gameplay (double-tap pause) and pause menu (buttons).
     * This works with multi-touch since it's called for every ACTION_DOWN/ACTION_POINTER_DOWN,
     * unlike GestureDetector which only tracks the primary pointer.
     */
    private void handleUiTap(float rawX, float rawY) {
        if (menuVisible) {
            // Pause menu button handling
            if (pauseMode == PAUSE_MENU) {
                if (exitRect.contains(rawX, rawY)) {
                    NativeAudioEngine.stop();
                    if (callback != null) callback.onExit();
                    return;
                }
                if (restartRect.contains(rawX, rawY)) {
                    restartInternal();
                    return;
                }
                if (continueRect.contains(rawX, rawY)) {
                    pauseMode = PAUSE_COUNTDOWN;
                    resumeCountdownStartNs = System.nanoTime();
                    resumeFadeOutStartNs = -1L;
                    needBlurUpdate = true;
                    NativeAudioEngine.pause(true);
                    return;
                }
            }
        } else {
            // Double-tap pause detection
            if (isInPauseHitArea(rawX, rawY)) {
                long now = System.currentTimeMillis();
                float dx = rawX - lastPauseTapX;
                float dy = rawY - lastPauseTapY;
                float distSq = dx * dx + dy * dy;
                float maxDistPx = dp(DOUBLE_TAP_MAX_DIST_DP);
                if (now - lastPauseTapTimeMs <= DOUBLE_TAP_TIMEOUT_MS
                        && distSq <= maxDistPx * maxDistPx) {
                    // Double tap detected
                    menuVisible = true;
                    pauseMode = PAUSE_MENU;
                    resumeCountdownStartNs = -1L;
                    resumeFadeOutStartNs = -1L;
                    needBlurUpdate = true;
                    NativeAudioEngine.pause(true);
                    lastPauseTapTimeMs = 0; // Reset to prevent triple-tap
                } else {
                    // First tap (or timeout/distance exceeded)
                    lastPauseTapTimeMs = now;
                    lastPauseTapX = rawX;
                    lastPauseTapY = rawY;
                }
            } else {
                // Tapped outside pause area — reset double-tap state
                lastPauseTapTimeMs = 0;
            }
        }
    }

    public void onSingleTap(float x, float y) {
        if (!menuVisible) return;
        if (pauseMode != PAUSE_MENU) return;
        if (exitRect.contains(x, y)) {
            NativeAudioEngine.stop();
            if (callback != null) callback.onExit();
            return;
        }
        if (restartRect.contains(x, y)) {
            restartInternal();
            return;
        }
        if (continueRect.contains(x, y)) {
            pauseMode = PAUSE_COUNTDOWN;
            resumeCountdownStartNs = System.nanoTime();
            resumeFadeOutStartNs = -1L;
            needBlurUpdate = true;
            NativeAudioEngine.pause(true);
        }
    }

    private void restartInternal() {
        for (Note n : chart.allNotesSorted) {
            n.clicked = false;
            n.judgeResult = -1;
            n.judgeDiffSec = 0.0;
            n.judgeTimeSec = Double.NaN;
            n.preJudge = false;
            n.isJudged = false;
            n.holdActive = false;
            n.holdPerfect = false;
            n.holdPreJudge = false;
            n.holdUpTimeSec = Double.POSITIVE_INFINITY;
            n.holdDiffSec = 0.0;
            n.safeFrame = 0;
            n.holdFxAtSec = Double.NaN;
            n.holdBroken = false;
            n.holdStatus = 0;
            n.holdTapTimeMs = 0;
            n.scored = false;
            n.badTimeMs = 0;
            n.statOffset = 0.0;
            n.frameCount = 0;
        }
        autoNoteIndex = 0;
        clickEffectIndex = 0;
        judgeCursor = 0;
        if (chart.clickEffectsSorted != null) {
            for (ClickEffectItem item : chart.clickEffectsSorted) {
                if (item != null) {
                    item.animStartCached = false;
                    item.animStartSec = 0f;
                    item.positionCached = false;
                }
            }
        }
        combo = 0;
        maxCombo = 0;
        judgeCounts[0] = 0;
        judgeCounts[1] = 0;
        judgeCounts[2] = 0;
        judgeCounts[3] = 0;
        goodEarly = 0;
        goodLate = 0;
        timingCount = 0;
        timingMeanMs = 0.0;
        timingM2Ms = 0.0;
        apStill = true;
        fcStill = true;
        finishedReported = false;
        menuVisible = false;
        needBlurUpdate = false;
        pauseMode = PAUSE_NONE;
        resumeCountdownStartNs = -1L;
        resumeFadeOutStartNs = -1L;

        isInOutro = false;
        outroStartTimeSec = -1f;
        outroStartWallNs = -1L;
        isInIntroHold = false;
        introHoldStartNs = -1L;
        isIntroHoldDone = false;
        introStarted = false;
        isMusicStartedFlag = false;
        introStartNs = -1L;
        frameIntroLineScale = 0f;
        frameOutroLineScale = 1f;
        frameGlobalAlpha = 0f;
        wasInIntroOrOutro = true;
        musicReferenceAtIntroExit = 0.0;
        frameIntroLinearT = 0f;

        smoothClockInit = false;
        smoothLastFrameNs = 0L;
        smoothPlayheadSec = 0.0;
        smoothLastRawPlayheadSec = 0.0;
        smoothReferencePlayheadSec = 0.0;
        smoothReferenceTimeNs = 0L;
        smoothBiases.clear();
        smoothBiasSum = 0.0;

        visualClockInit = false;
        visualLastFrameNs = 0L;
        visualTimeSec = 0.0;

        // Reset event cursors for all judge lines (cursor-based O(1) lookup)
        if (chart.judgeLineList != null) {
            for (JudgeLine line : chart.judgeLineList) {
                if (line != null) line.resetCursors();
            }
        }

        NativeAudioEngine.restart();
        NativeAudioEngine.pause(true);
        // Defer beginIntro() to the GL thread — restartInternal() may be
        // called from a touch handler on the UI thread, and introStartNs
        // must be captured on the GL thread so that computeIntroLineScale()
        // sees an accurate wall-clock reference.
        restartIntroNeedsInit = true;

        // Preserve active touches across restart so held fingers keep providing
        // type-2 (continuous) events for DRAGs and HOLD bodies after the intro.
        // Only reset click/flick state — held fingers should NOT auto-hit HOLD
        // heads (heads require a genuine new touch).
        startedTouchIds.clear();
        flickTrackers.clear();
        hitEffects.clear();
        badEffects.clear();
        lastPauseTapTimeMs = 0;
    }

    private float transformTouchX(float x) {
        if (!mirrorX) return x;
        float sx0 = stageL;
        float sx1 = stageL + stageW;
        if (x < sx0 || x > sx1) return x;
        return sx0 + (sx1 - x);
    }

    private void tryJudgeInputDown(double tChart, float x, float y) {
        tryJudgeAt(tChart, x, y, true);
    }

    private void tryJudgeInputMove(double tChart, float x, float y) {
        tryJudgeAt(tChart, x, y, false);
    }

    private void tryJudgeAt(double tChart, float x, float y, boolean isDown) {
        if (chart == null || chart.allNotesSorted == null) return;
        if (stageW <= 1f || stageH <= 1f) return;

        float stageR = stageL + stageW;
        float stageB = stageT + stageH;
        if (x < stageL || x > stageR || y < stageT || y > stageB) return;

        List<Note> notes = chart.allNotesSorted;
        int nSize = notes.size();
        if (nSize <= 0) return;

        double maxEarlyWindow = getBadWindowSec();
        int scanEnd = Math.min(nSize, judgeCursor + 96);

        float[] pos = new float[2];
        float noteWidthBase0 = stageW * 0.1234375f * keyScale;
        float radius = Math.max(8f, noteWidthBase0 * 0.55f);
        float r2 = radius * radius;

        for (int i = judgeCursor; i < scanEnd; i++) {
            Note n = notes.get(i);
            if (n == null) continue;
            if (n.judgeResult >= 0) continue;

            if (isDown) {
                if (!(n.type == GameConstants.NOTE_TAP || n.type == GameConstants.NOTE_HOLD || n.type == GameConstants.NOTE_FLICK)) {
                    continue;
                }
            } else {
                if (n.type != GameConstants.NOTE_DRAG) continue;
            }

            double dt = tChart - n.sect;
            double abs = Math.abs(dt);

            if (n.sect - tChart > maxEarlyWindow) break;
            if (abs > getLateMissLimitSec(n)) continue;

            double judgeWin = getJudgeWindowSec(n);
            if (abs > judgeWin) continue;

            if (!computeNoteHeadPosition(n, tChart, pos)) continue;
            float dx = pos[0] - x;
            float dy = pos[1] - y;
            if (dx * dx + dy * dy > r2) continue;

            int jr = calcJudgement(n, abs);
            commitJudgement(n, jr, dt);
            while (judgeCursor < nSize) {
                Note nn = notes.get(judgeCursor);
                if (nn != null && nn.judgeResult >= 0) {
                    judgeCursor++;
                } else {
                    break;
                }
            }
            return;
        }
    }

    private boolean computeNoteHeadPosition(@NonNull Note note, double tChart, @NonNull float[] outXY) {
        if (note.master == null) return false;
        JudgeLine line = note.master;
        float stageAspect = (stageH > 1e-6f) ? (stageW / stageH) : (16f / 9f);
        JudgeLine.StateHolder st = line.fillState(tChart, stageAspect);
        if (st == null) return false;

        float lineRot = st.rotateDeg;
        float lineX = stageL + st.xNorm * stageW;
        float lineY = stageT + st.yNorm * stageH;
        double rad = lineRot * Math.PI / 180.0;
        float cosLine = (float) Math.cos(rad);
        float sinLine = (float) Math.sin(rad);

        double beatt = line.sec2beat(tChart);
        double lineFp = EventUtils.getFloorPosition(beatt, line.speedEvents);
        double bpm = (line.bpm > 0) ? line.bpm : 120.0;
        double speed = (note.useOfficialSpeed ? 1.0 : note.speed) * scrollSpeed;

        // separate base (distance) and transY (yOffset)
        // baseFpPx = distance from line in pixels (WITH yCtrl, WITHOUT yOffset)
        double baseFpD = (note.floorPosition - lineFp) * GameConstants.PGRH * (GameConstants.PGRBEAT / bpm) * stageH * speed;
        float baseFpPx = (float) baseFpD;

        // transY in normalized pixels (yOffset * 2/RPE_HEIGHT * speed * stageH/2)
        float transYPx = yOffsetToTransYPx(note.yOffset, speed, stageH);

        // yCtrl applies to base only ( spd = speed * ctrl_obj.y)
        float yCtrl = line.calcNoteControl(baseFpPx, JudgeLine.NOTE_CTRL_Y, 1f);
        float baseFpWithCtrl = applyYControlScale(baseFpPx, yCtrl);

        // note visual y position = base + transY
        float visualFp = baseFpWithCtrl + transYPx;

        // for non-hold, tr.x *= incline_val * ctrl_obj.pos
        float offX = (float) (note.positionX * GameConstants.PGRW * stageW);
        float xCtrl = line.calcNoteControl(baseFpPx, JudgeLine.NOTE_CTRL_X, 1f);

        // incline ()
        float inclineVal = 1f;
        if (Float.isFinite(st.inclineSinr)) {
            inclineVal = calcInclineValue(st.inclineSinr, baseFpWithCtrl, transYPx, stageAspect, stageH);
        }
        if (!note.isHold) {
            offX *= inclineVal;
            if (Float.isFinite(xCtrl) && xCtrl != 0f) offX *= xCtrl;
        }

        float nAtX = lineX + offX * cosLine;
        float nAtY = lineY + offX * sinLine;
        float dirX = note.isAbove ? sinLine : -sinLine;
        float dirY = note.isAbove ? -cosLine : cosLine;

        outXY[0] = nAtX + visualFp * dirX;
        outXY[1] = nAtY + visualFp * dirY;
        return true;
    }

    private double getPerfectWindowSec() {
        return challengeMode ? WINC_PERFECT + challengeTimeSum : WIN_PERFECT;
    }

    private double getGoodWindowSec() {
        return challengeMode ? WINC_GOOD + challengeTimeSum : WIN_GOOD;
    }

    private double getBadWindowSec() {
        return challengeMode ? WINC_BAD + challengeTimeSum : WIN_BAD;
    }

    /** Early window (before note time) — same as badTimeRange for scanning. */
    private double getEarlyWindowSec() {
        return getBadWindowSec();
    }

    /** Per-type judge window for hit detection (maximum |deltaTime| allowed). */
    private double getJudgeWindowSec(@NonNull Note n) {
        switch (n.type) {
            case GameConstants.NOTE_DRAG:
            case GameConstants.NOTE_FLICK:
                return getBadWindowSec();
            case GameConstants.NOTE_HOLD:
                return getGoodWindowSec();
            default: // NOTE_TAP
                return getBadWindowSec();
        }
    }

    /**
     * Phigros CheckNote: compute judge window with spatial distance reduction.
     * touchPos = abs(fingerPositionX - note.positionX) in Phigros positionX coordinate space.
     * touchPos <= 0.9: full badTimeRange window
     * 0.9 < touchPos < 1.9: linearly reduced window
     *   _badTime = badTimeRange + (touchPos - 0.9) * perfectTimeRange * (-0.5)
     * touchPos >= 1.9: no match (return 0)
     */
    private double getSpatialJudgeWindowSec(@NonNull Note n, float touchPos) {
        double perfect = getPerfectWindowSec();
        double bad = getBadWindowSec();

        double window;
        if (touchPos <= 0.9f) {
            window = bad;
        } else if (touchPos < 1.9f) {
            // Phigros: _badTime = badTimeRange + (touchPos - 0.9) * perfectTimeRange * (-0.5)
            window = bad - (touchPos - 0.9) * perfect * 0.5;
        } else {
            return 0; // Too far, no match
        }

        // For Hold, the max window is goodTimeRange
        if (n.type == GameConstants.NOTE_HOLD) {
            window = Math.min(window, getGoodWindowSec());
        }

        return window;
    }

    /** Per-type late Miss threshold (how late a note can be before forced Miss). */
    private double getLateMissLimitSec(@NonNull Note n) {
        double p = getPerfectWindowSec();
        switch (n.type) {
            case GameConstants.NOTE_DRAG:
                // Phigros: DragControl v5 < -0.1 && !isJudged → Miss (always 0.1, no challenge mode adjustment)
                return DRAG_MISS_THRESHOLD;
            case GameConstants.NOTE_FLICK:
                // Phigros: FlickControl late > perfect*1.75 → Miss
                return p * 1.75;
            case GameConstants.NOTE_HOLD:
                // Phigros: Hold head Miss threshold = goodTimeRange
                return getGoodWindowSec();
            default: // NOTE_TAP
                // Phigros: ClickControl — delta < -goodTimeRange → Miss for unjudged Tap
                return getGoodWindowSec();
        }
    }

    private int calcJudgement(@NonNull Note n, double absDiffSec) {
        double p = getPerfectWindowSec();
        double g = getGoodWindowSec();
        double b = getBadWindowSec();
        if (absDiffSec <= p) return JR_PERFECT;
        if (n.type == GameConstants.NOTE_DRAG || n.type == GameConstants.NOTE_FLICK) return JR_MISS;
        if (absDiffSec <= g) return JR_GOOD;
        if (n.type == GameConstants.NOTE_HOLD) return JR_MISS;
        if (absDiffSec <= b) return JR_BAD;
        return JR_MISS;
    }

    private void commitJudgement(@NonNull Note n, int jr, double diffSec) {
        if (n.judgeResult >= 0) return;
        n.judgeResult = jr;
        n.judgeDiffSec = diffSec;
        n.judgeTimeSec = frameChartTimeSec;
        n.preJudge = false;
        n.holdActive = false;
        n.holdPreJudge = false;
        n.holdUpTimeSec = Double.POSITIVE_INFINITY;
        n.clicked = (jr == JR_PERFECT || jr == JR_GOOD);

        if (jr == JR_PERFECT) {
            judgeCounts[0]++;
            combo++;
        } else if (jr == JR_GOOD) {
            judgeCounts[1]++;
            combo++;
            if (diffSec < 0.0) goodEarly++;
            else goodLate++;
            apStill = false;
        } else if (jr == JR_BAD) {
            judgeCounts[2]++;
            combo = 0;
            apStill = false;
            fcStill = false;
        } else if (jr == JR_MISS) {
            judgeCounts[3]++;
            combo = 0;
            apStill = false;
            fcStill = false;
        }

        if (jr == JR_PERFECT || jr == JR_GOOD) {
            double x = diffSec * 1000.0;
            timingCount++;
            double delta = x - timingMeanMs;
            timingMeanMs += delta / (double) timingCount;
            double delta2 = x - timingMeanMs;
            timingM2Ms += delta * delta2;
        }

        if (combo > maxCombo) maxCombo = combo;

        // Spawn effects based on judgment type
        if (jr == JR_PERFECT || jr == JR_GOOD) {
            if (n.type != GameConstants.NOTE_HOLD) {
                int sfxType = n.type;
                if (sfxType == GameConstants.NOTE_HOLD) sfxType = GameConstants.NOTE_TAP;
                NativeAudioEngine.triggerSfx(sfxType);
            }
        }

        if (!autoplay) {
            // Spawn effects based on judgment type.
            // Note: Drag, Flick and Hold effects are already spawned in their respective phases,
            // so we skip them here to avoid duplicate effects.
            // - Drag/Flick: effects spawned in Phase 2/3 before commitJudgement
            // - Hold: head effect in Phase 4, body effects in Phase 5; commitJudgement in Phase 6
            // should NOT add another effect
            if (jr == JR_PERFECT) {
                if (n.type != GameConstants.NOTE_DRAG && n.type != GameConstants.NOTE_FLICK && n.type != GameConstants.NOTE_HOLD) {
                    spawnHitEffect(n, frameChartTimeSec, GameConstants.PCOLOR[0], GameConstants.PCOLOR[1], GameConstants.PCOLOR[2], GameConstants.PALPHA, 4);
                }
            } else if (jr == JR_GOOD) {
                if (n.type != GameConstants.NOTE_DRAG && n.type != GameConstants.NOTE_FLICK && n.type != GameConstants.NOTE_HOLD) {
                    spawnHitEffect(n, frameChartTimeSec, GameConstants.GCOLOR[0], GameConstants.GCOLOR[1], GameConstants.GCOLOR[2], GameConstants.GALPHA, 3);
                }
            } else if (jr == JR_BAD) {
                // Bad: tint note and fade out (no hit effect on line)
                spawnBadEffect(n, frameChartTimeSec);
            }
            // Miss: no effect at all
        }
    }

    private PlayResult buildPlayResult() {
        double stdDev = 0.0;
        if (timingCount > 0) {
            stdDev = Math.sqrt(Math.max(0.0, timingM2Ms / (double) timingCount));
        }
        return new PlayResult(
                getScore(),
                getAccuracy(),
                maxCombo,
                totalNotes,
                judgeCounts[0],
                judgeCounts[1],
                judgeCounts[2],
                judgeCounts[3],
                goodEarly,
                goodLate,
                stdDev
        );
    }

    // ---------------- GLSurfaceView.Renderer ----------------

    @Override
    public void onSurfaceCreated(GL10 gl, EGLConfig config) {
        // Increment GL context version — all existing texture GL IDs are now stale
        glContextVersion++;

        GLES20.glClearColor(0f, 0f, 0f, 1f);
        GLES20.glEnable(GLES20.GL_BLEND);
        // Match Canvas(2D) / Android Bitmap premultiplied-alpha blending.
        // This avoids the "semi-transparent looks black" problem when using PNGs.
        GLES20.glBlendFuncSeparate(
                GLES20.GL_ONE, GLES20.GL_ONE_MINUS_SRC_ALPHA,
                GLES20.GL_ONE, GLES20.GL_ONE_MINUS_SRC_ALPHA);

        // Invalidate all cached texture GL IDs — they'll be re-uploaded on next access
        invalidateAllCachedTextures();

        // Eagerly upload preloaded GIF textures to GL while we're still on the GL thread
        // but before any frames are rendered. This avoids a stutter when a GIF is first
        // encountered during gameplay.
        uploadPreloadedGifTextures();

        // quad buffers
        quadPos = createQuadPos();
        quadUv = createQuadUv();

        // Create VBO for NDC quad
        int[] buffers = new int[1];
        GLES20.glGenBuffers(1, buffers, 0);
        vboQuadNdc = buffers[0];
        GLES20.glBindBuffer(GLES20.GL_ARRAY_BUFFER, vboQuadNdc);
        GLES20.glBufferData(GLES20.GL_ARRAY_BUFFER, ndcPos.capacity() * 4, ndcPos, GLES20.GL_STATIC_DRAW);
        GLES20.glBindBuffer(GLES20.GL_ARRAY_BUFFER, 0);

        // programs
        progSprite = buildProgram(VS_SPRITE, FS_SPRITE);
        // blur pass uses a fullscreen vertex shader (no uMVP)
        progBlur = buildProgram(VS_FULLSCREEN, FS_BLUR);

        // sprite locations
        locPos = GLES20.glGetAttribLocation(progSprite, "aPosition");
        locUv = GLES20.glGetAttribLocation(progSprite, "aTexCoord");
        locMvp = GLES20.glGetUniformLocation(progSprite, "uMVP");
        locColor = GLES20.glGetUniformLocation(progSprite, "uColor");
        locTex = GLES20.glGetUniformLocation(progSprite, "uTexture");

        // blur locations
        locBPos = GLES20.glGetAttribLocation(progBlur, "aPosition");
        locBUv = GLES20.glGetAttribLocation(progBlur, "aTexCoord");
        locBTex = GLES20.glGetUniformLocation(progBlur, "uTexture");
        locBTexelOffset = GLES20.glGetUniformLocation(progBlur, "uTexelOffset");

        // batch program
        progBatch = buildProgram(VS_BATCH, FS_BATCH);
        locBatchPos = GLES20.glGetAttribLocation(progBatch, "aPosition");
        locBatchUv = GLES20.glGetAttribLocation(progBatch, "aTexCoord");
        locBatchColor = GLES20.glGetAttribLocation(progBatch, "aColor");
        locBatchProj = GLES20.glGetUniformLocation(progBatch, "uProjection");
        locBatchTex = GLES20.glGetUniformLocation(progBatch, "uTexture");

        // batch buffer (6 vertices per quad for TRIANGLES)
        ByteBuffer bbb = ByteBuffer.allocateDirect(MAX_BATCH_QUADS * 6 * VERTEX_SIZE * 4);
        bbb.order(ByteOrder.nativeOrder());
        batchBuffer = bbb.asFloatBuffer();

        texWhite = createWhiteTexture();

        // Load respack
        respack = loadResPackFromAssets("res/respack.json");
        if (respack == null) {
            respack = new ResPackInfo();
            respack.holdAtlas = new int[]{64, 64};
            respack.holdAtlasMH = new int[]{64, 64};
            respack.hitFx = new int[]{4, 4};
        }

        // Load textures (assets)
        texPause = loadTextureFromAssetsSafe("res/pause.png");
        // Pause menu icons: force to white (preserve alpha)
        texExit = loadTextureFromAssetsSafe("res/exit.png", true);
        texRestart = loadTextureFromAssetsSafe("res/restart.png", true);
        texContinue = loadTextureFromAssetsSafe("res/continue.png", true);
        texTimerLine = loadTextureFromAssetsSafe("res/timerLine.png");

        noteHeadTex[GameConstants.NOTE_TAP][0] = loadTextureFromAssetsSafe("res/click.png");
        noteHeadTex[GameConstants.NOTE_TAP][1] = loadTextureFromAssetsSafe("res/click_mh.png");
        noteHeadTex[GameConstants.NOTE_DRAG][0] = loadTextureFromAssetsSafe("res/drag.png");
        noteHeadTex[GameConstants.NOTE_DRAG][1] = loadTextureFromAssetsSafe("res/drag_mh.png");
        noteHeadTex[GameConstants.NOTE_FLICK][0] = loadTextureFromAssetsSafe("res/flick.png");
        noteHeadTex[GameConstants.NOTE_FLICK][1] = loadTextureFromAssetsSafe("res/flick_mh.png");

        // hold atlas texture
        texHold = loadTextureFromAssetsSafe("res/hold.png");
        texHoldMh = loadTextureFromAssetsSafe("res/hold_mh.png");

        // Assign hold textures to noteHeadTex for width calculation
        noteHeadTex[GameConstants.NOTE_HOLD][0] = texHold;
        noteHeadTex[GameConstants.NOTE_HOLD][1] = texHoldMh;

        // hit fx
        texHitFx = loadTextureFromAssetsSafe("res/hit_fx.png");

        // background from file
        if (texBackground != null) {
            // Re-upload from retained bitmap or reload from file
            Texture recovered = ensureTextureValid(texBackground);
            if (recovered == null) {
                texBackground = loadTextureFromFileSafe(backgroundPath);
            }
        } else {
            texBackground = loadTextureFromFileSafe(backgroundPath);
        }

        rebuildNoteHeadTextureDrawList();

        // prpr storyboard shaders (extra.json)
        if (hasPrprEffects) {
            initPrprShaderPrograms();
        }
    }

    @Override
    public void onSurfaceChanged(GL10 gl, int width, int height) {
        viewW = Math.max(1, width);
        viewH = Math.max(1, height);
        GLES20.glViewport(0, 0, viewW, viewH);

        // y-down ortho (like Canvas)
        Matrix.orthoM(proj, 0, 0f, (float) viewW, (float) viewH, 0f, -1f, 1f);

        // Stage & UI
        computeStageRect(true);

        // Text textures depend on stage scale; invalidate GL IDs.
        // Keep the retained bitmap data so textures can be re-uploaded.
        for (Texture t : textTextureCache.values()) {
            if (t != null) t.invalidateGl();
        }
        for (Texture t : debugTextCache.values()) {
            if (t != null) t.invalidateGl();
        }
        textPaint = null;
        clearHudTextTextures();

        // (re)create blur fbos at half resolution
        int bw = Math.max(1, viewW / 2);
        int bh = Math.max(1, viewH / 2);
        releaseFbos();
        sceneFbo = new FboTex(bw, bh);
        blurFbo1 = new FboTex(bw, bh);
        blurFbo2 = new FboTex(bw, bh);

        // background blur (一次性计算)
        createBackgroundBlur();

        // prpr storyboard: allocate effect framebuffers (full-screen + optional stage-only)
        ensurePrprFbos();

        needBlurUpdate = true; // force refresh once
    }

    /**
     * Compute stage rect (letterboxed) and UI rects.
     *
     * <p>The stage keeps a fixed aspect ratio (if configured) and is centered on screen.
     * UI elements (pause button / menu buttons / progress bar) are laid out relative to the stage.
     */
    private void computeStageRect(boolean notifyActivity) {
        // Stage rect policy (reference: prpr viewport behavior)
        // - targetAspectRatio <= 0 : follow screen (no letterboxing)
        // - targetAspectRatio  > 0 : keep fixed aspect ratio and letterbox as needed (centered)

        if (viewW <= 0 || viewH <= 0) {
            stageL = 0f;
            stageT = 0f;
            stageW = Math.max(1f, (float) viewW);
            stageH = Math.max(1f, (float) viewH);
        } else if (targetAspectRatio <= 0f) {
            stageL = 0f;
            stageT = 0f;
            stageW = (float) viewW;
            stageH = (float) viewH;
        } else {
            float viewAr = (float) viewW / (float) viewH;

            // Base aspect ratio: user/chart target, falling back to the current viewport ratio.
            float ar = targetAspectRatio;
            if (ar <= 0f) ar = viewAr;

            // Match prpr behavior (when fix_aspect_ratio is false):
            // never force a stage wider than the current viewport.
            ar = Math.min(ar, viewAr);

            if (viewAr >= ar) {
                // Screen is wider than the target ratio: letterbox left/right.
                stageH = (float) viewH;
                stageW = stageH * ar;
                stageL = ((float) viewW - stageW) * 0.5f;
                stageT = 0f;
            } else {
                // Screen is taller than the target ratio: letterbox top/bottom.
                stageW = (float) viewW;
                stageH = stageW / ar;
                stageL = 0f;
                stageT = ((float) viewH - stageH) * 0.5f;
            }
        }

        // UI rects based on stage (match margins)
        float lineScale = stageW > stageH * 0.75f ? (stageH / 18.75f) : (stageW / 14.0625f);

        float pauseW = stageW * (35.0f / 1920.0f);
        float pauseAr = (texPause != null && texPause.height > 0) ? (texPause.width / (float) texPause.height) : 1f;
        float pauseH = pauseW / pauseAr;
        float pauseX = stageL + stageW * (36.0f / 1920.0f);
        float pauseY = stageT + stageH * (41.0f / 1080.0f);
        pauseRect.set(pauseX, pauseY, pauseX + pauseW, pauseY + pauseH);
        pauseHitPadPx = MathUtils.clamp(Math.max(pauseW, pauseH) * 1.1f, dp(20f), dp(80f));

        float btnSize = stageH * 0.1f;
        float btnY = stageT + stageH * 0.5f - btnSize * 0.5f;
        
        float gap = btnSize * 1.35f;
        float maxGap = Math.max(0f, (stageW - btnSize * 3f) * 0.5f);
        gap = Math.min(gap, maxGap * 0.95f);
        
        float centerX = stageL + stageW * 0.5f;
        exitRect.set(centerX - gap - btnSize, btnY, centerX - gap, btnY + btnSize);
        restartRect.set(centerX - btnSize * 0.5f, btnY, centerX + btnSize * 0.5f, btnY + btnSize);
        continueRect.set(centerX + gap, btnY, centerX + gap + btnSize, btnY + btnSize);

        // Stage-level mirror transform (X-axis)
        Matrix.setIdentityM(stagePreTransform, 0);
        if (mirrorX) {
            float cx = stageL + stageW * 0.5f;
            Matrix.translateM(stagePreTransform, 0, cx, 0f, 0f);
            Matrix.scaleM(stagePreTransform, 0, -1f, 1f, 1f);
            Matrix.translateM(stagePreTransform, 0, -cx, 0f, 0f);
        }

        if (notifyActivity && callback != null) {
            callback.onStageRectChanged(stageL, stageT, stageW, stageH);
        }
    }


    @Override
    public void onDrawFrame(GL10 gl) {
        maybeAutoResumeAfterIntro();
        long nowNs = System.nanoTime();
        
        // FPS counter update
        fpsFrameCount++;
        if (fpsCounterLastNs == 0L) {
            fpsCounterLastNs = nowNs;
        } else {
            long fpsDeltaNs = nowNs - fpsCounterLastNs;
            if (fpsDeltaNs >= 500_000_000L) { // Update every 0.5 seconds
                currentFps = (float) (fpsFrameCount * 1e9 / fpsDeltaNs);
                fpsFrameCount = 0;
                fpsCounterLastNs = nowNs;
            }
        }
        
        if (!visualClockInit) {
            visualClockInit = true;
            visualLastFrameNs = nowNs;
        } else {
            long dtNs = nowNs - visualLastFrameNs;
            visualLastFrameNs = nowNs;
            if (dtNs > 0L) {
                double dt = dtNs * 1e-9;
                if (!Double.isFinite(dt) || dt < 0.0) dt = 0.0;
                if (dt > 0.1) dt = 0.1;
                if (!menuVisible) {
                    visualTimeSec += dt;
                }
            }
        }
        if (!menuVisible || pauseMode == PAUSE_FADEOUT) {
            // exit animation completes after 1500ms wall-clock time
            if (isInOutro) {
                long elapsedNs = System.nanoTime() - outroStartWallNs;
                float elapsedSec = elapsedNs / 1_000_000_000f;
                if (elapsedSec >= OUTRO_DUR_SEC + 0.05f) {
                    NativeAudioEngine.stop();
                    if (!finishedReported) {
                        finishedReported = true;
                        if (callback != null) callback.onFinished(buildPlayResult());
                    }
                    return;
                }
            }
        }

        if (menuVisible && pauseMode != PAUSE_FADEOUT) {
            if (needBlurUpdate) {
                renderSceneToFbo(sceneFbo);
                blurPass(sceneFbo, blurFbo1, 1f / sceneFbo.w, 0f);
                blurPass(blurFbo1, blurFbo2, 0f, 1f / sceneFbo.h);
                needBlurUpdate = false;
            }

            GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, 0);
            GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT);
            drawFullscreenTextureFbo(blurFbo2.tex, 1f, 1f, 1f, 1f);

            drawSolidRect(0f, 0f, viewW, viewH, 0f, 0f, 0f, 0.45f);

            if (pauseMode == PAUSE_MENU) {
                drawButton(texExit, exitRect);
                drawButton(texRestart, restartRect);
                drawButton(texContinue, continueRect);
            } else if (pauseMode == PAUSE_COUNTDOWN) {
                NativeAudioEngine.pause(true);
                boolean finished = drawResumeCountdown();
                if (finished) {
                    pauseMode = PAUSE_FADEOUT;
                    long now = System.nanoTime();
                    resumeFadeOutStartNs = now;
                    resumeCountdownStartNs = -1L;
                    resumeFadeUnpaused = false;
                    resumeFadeUnpauseAtNs = now + (long) (RESUME_START_DELAY_SEC * 1_000_000_000L);
                    NativeAudioEngine.pause(true);
                }
            }

            return;
        }

        if (lowResMode && !(hasPrprEffects && prprFullA != null && !prprPrograms.isEmpty())) {
            renderSceneToFbo(sceneFbo);
            GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, 0);
            GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT);
            drawFullscreenTextureFbo(sceneFbo.tex, 1f, 1f, 1f, 1f);
        } else if (hasPrprEffects && prprFullA != null && !prprPrograms.isEmpty()) {
            renderSceneWithPrprEffects();
        } else {
            GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, 0);
            GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT);
            renderSceneDirect();
            drawGameHudWithAnimationsClipped();
        }

        if (menuVisible && pauseMode == PAUSE_FADEOUT) {
            if (!resumeFadeUnpaused) {
                long at = resumeFadeUnpauseAtNs;
                if (at > 0L && System.nanoTime() >= at) {
                    resumeFadeUnpaused = true;
                    resumeFadeUnpauseAtNs = -1L;
                    smoothClockInit = false;
                    NativeAudioEngine.pause(false);
                }
            }
            float a = computeResumeFadeAlpha();
            if (a <= 0f) {
                menuVisible = false;
                pauseMode = PAUSE_NONE;
                resumeFadeOutStartNs = -1L;
                if (!resumeFadeUnpaused) {
                    resumeFadeUnpaused = true;
                    resumeFadeUnpauseAtNs = -1L;
                    smoothClockInit = false;
                    NativeAudioEngine.pause(false);
                }
            } else if (blurFbo2 != null) {
                drawFullscreenTextureFbo(blurFbo2.tex, 1f, 1f, 1f, a);
                drawSolidRect(0f, 0f, viewW, viewH, 0f, 0f, 0f, 0.45f * a);
            }
        }
    }

    private float hudCountTextSizePx = -1f;

    private void maybeAutoResumeAfterIntro() {
        if (!introAutoResumePending) return;
        if (menuVisible) return;
        if (pauseMode != PAUSE_NONE) return;
        long at = introAutoResumeAtNs;
        if (at <= 0L) {
            introAutoResumePending = false;
            return;
        }
        if (System.nanoTime() >= at) {
            introAutoResumePending = false;
            introAutoResumeAtNs = -1L;
            NativeAudioEngine.pause(false);
            // Reset the smooth clock so that biases accumulated during the
            // intro period (where raw playhead stayed at 0 while wall-clock
            // time advanced) do not cause the first real chart frame to jump
            // ahead by 2–3 frames or cause holds to end early.
            smoothClockInit = false;
            isMusicStartedFlag = true;
        }
    }

    private boolean drawResumeCountdown() {
        long start = resumeCountdownStartNs;
        if (start <= 0L) return true;
        double elapsed = (System.nanoTime() - start) * 1e-9;
        if (!Double.isFinite(elapsed)) elapsed = 0.0;
        if (elapsed >= 3.0 * RESUME_COUNTDOWN_STEP_SEC) return true;

        int step = (int) Math.floor(elapsed / RESUME_COUNTDOWN_STEP_SEC);
        if (step < 0) step = 0;
        if (step > 2) step = 2;
        int digit = 3 - step;
        float local = (float) (elapsed - step * RESUME_COUNTDOWN_STEP_SEC);
        local = MathUtils.clamp(local, 0f, RESUME_COUNTDOWN_STEP_SEC);
        float p = local / RESUME_COUNTDOWN_STEP_SEC;

        float textPx = MathUtils.clamp(stageH * 0.25f, dp(25f), dp(55f));
        ensureCountdownTextures(textPx);
        Texture tex = digit == 3 ? hudTexCount3 : (digit == 2 ? hudTexCount2 : hudTexCount1);
        if (tex == null) return false;

        float cx = stageL + stageW * 0.5f;
        float cy = stageT + stageH * 0.5f;
        float slide = stageW * 0.18f;

        float x;
        float alpha;
        if (p < 0.35f) {
            float t = p / 0.35f;
            float e = 1f - (1f - t) * (1f - t) * (1f - t);
            x = cx + slide * (1f - e);
            alpha = e;
        } else if (p < 0.65f) {
            x = cx;
            alpha = 1f;
        } else {
            float t = (p - 0.65f) / 0.35f;
            float e = t * t * t;
            x = cx - slide * e;
            alpha = 1f - e;
        }

        boolean blendWasEnabled = GLES20.glIsEnabled(GLES20.GL_BLEND);
        boolean scissorWasEnabled = GLES20.glIsEnabled(GLES20.GL_SCISSOR_TEST);
        if (!blendWasEnabled) GLES20.glEnable(GLES20.GL_BLEND);
        GLES20.glBlendFuncSeparate(
                GLES20.GL_ONE, GLES20.GL_ONE_MINUS_SRC_ALPHA,
                GLES20.GL_ONE, GLES20.GL_ONE_MINUS_SRC_ALPHA);
        if (scissorWasEnabled) GLES20.glDisable(GLES20.GL_SCISSOR_TEST);

        drawTextureCentered(tex, x, cy, (float) tex.width, (float) tex.height, 0f, 1f, 1f, 1f, alpha, 0f, 0f, 1f, 1f);

        if (scissorWasEnabled) GLES20.glEnable(GLES20.GL_SCISSOR_TEST);
        if (!blendWasEnabled) GLES20.glDisable(GLES20.GL_BLEND);
        return false;
    }

    private void ensureCountdownTextures(float textPx) {
        if (!Float.isFinite(textPx) || textPx <= 0f) return;
        // Check if already valid for current GL context
        if (hudCountTextSizePx > 0f && Math.abs(hudCountTextSizePx - textPx) < 0.5f
                && hudTexCount1 != null && hudTexCount1.isValidForContext(glContextVersion)
                && hudTexCount2 != null && hudTexCount2.isValidForContext(glContextVersion)
                && hudTexCount3 != null && hudTexCount3.isValidForContext(glContextVersion)) {
            return;
        }
        deleteGlTexture(hudTexCount1);
        deleteGlTexture(hudTexCount2);
        deleteGlTexture(hudTexCount3);
        // Retain bitmaps so countdown numbers survive GL context loss
        hudTexCount3 = createHudTextTexture("3", textPx, true, true, true, true, true);
        hudTexCount2 = createHudTextTexture("2", textPx, true, true, true, true, true);
        hudTexCount1 = createHudTextTexture("1", textPx, true, true, true, true, true);
        hudCountTextSizePx = textPx;
    }

    private float computeResumeFadeAlpha() {
        long start = resumeFadeOutStartNs;
        if (start <= 0L) return 0f;
        float t = (float) ((System.nanoTime() - start) * 1e-9);
        if (!Float.isFinite(t) || t <= 0f) return 1f;
        float p = t / RESUME_FADE_DUR_SEC;
        if (p >= 1f) return 0f;
        return 1f - p;
    }

    // --- prpr storyboard shader pipeline ---

    private void renderSceneWithPrprEffects() {
        if (chart.prprEffects == null || chart.prprEffects.isEmpty()) {
            GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, 0);
            GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT);
            renderSceneDirect();
            drawGameHudWithAnimationsClipped();
            return;
        }

        final double musicPos = getSmoothedPlayheadSeconds();
        final double chartTimeSec = musicPos - chart.offset - userOffsetSec;

        tmpActiveNonGlobal.clear();
        tmpActiveGlobal.clear();
        for (PrprEffect e : chart.prprEffects) {
            if (e != null && e.isActive(chartTimeSec)) {
                if (e.isGlobal) tmpActiveGlobal.add(e); else tmpActiveNonGlobal.add(e);
            }
        }

        if (tmpActiveNonGlobal.isEmpty() && tmpActiveGlobal.isEmpty()) {
            GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, 0);
            GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT);
            renderSceneDirect();
            drawGameHudWithAnimationsClipped();
            return;
        }

        // shaders only apply during animateStatus=1 (playing).
        // During intro (frameIntroLineScale < 1) and outro (isInOutro), skip shader effects.
        if (frameIntroLineScale < 0.999f || isInOutro) {
            GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, 0);
            GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT);
            renderSceneDirect();
            drawGameHudWithAnimationsClipped();
            return;
        }

        // ---- intro fade tracking: storyboard should not be affected by entrance animation ----
        float savedGlobalAlpha = frameGlobalAlpha;
        boolean introFadeActive = (savedGlobalAlpha < 1f);

        // 1) Base scene (NO HUD) -> full fbo
        // During intro, render at full alpha so storyboard shaders process an un-faded scene.
        GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, prprFullA.fbo);
        GLES20.glViewport(0, 0, viewW, viewH);
        GLES20.glClearColor(0f, 0f, 0f, 1f);
        GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT);
        renderForPrprFbo = true;
        renderSceneDirect();
        renderForPrprFbo = false;
        Texture fullInput = prprFullA.tex;

        // 2) Stage-only effects: full-size ping-pong, viewport = stage rect (HUD still not included).
        // applyPrprEffectPassViewport copies the full input before scissoring the shader,
        // so stageResult already contains the complete scene (outside stage = original input,
        // inside stage = shader output). No composite needed — and compositing would cause an
        // FBO feedback loop when fullInput shares the composite target's texture attachment.
        Texture afterStage = fullInput;
        if (!tmpActiveNonGlobal.isEmpty()) {
            afterStage = applyPrprEffectChainStageViewport(tmpActiveNonGlobal, fullInput,
                    prprFullA, prprFullB, viewW, viewH, chartTimeSec,
                    stageL, stageT, stageW, stageH);
        }

        // 3) Global effects full-screen (these should affect HUD too).
        Texture finalTex = afterStage;
        boolean hudAlreadyComposited = false;
        if (!tmpActiveGlobal.isEmpty()) {
            // Composite HUD onto the current scene first, then run global shader chain.
            final FboTex hudCompositeTarget = (afterStage == prprFullB.tex) ? prprFullA : prprFullB;

            GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, hudCompositeTarget.fbo);
            GLES20.glViewport(0, 0, viewW, viewH);
            GLES20.glClearColor(0f, 0f, 0f, 0f);
            GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT);

            drawFullscreenTextureFbo(afterStage, 1f, 1f, 1f, 1f);

            drawGameHudWithAnimationsClipped();
            hudAlreadyComposited = true;

            finalTex = applyPrprEffectChainFull(tmpActiveGlobal, hudCompositeTarget.tex,
                    prprFullA, prprFullB, viewW, viewH, chartTimeSec);
        }

        // 4) Present storyboard output (phispler: shader result composited over existing scene)
        GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, 0);
        GLES20.glViewport(0, 0, viewW, viewH);
        GLES20.glClearColor(0f, 0f, 0f, 0f);
        GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT);
        // Draw scene background so transparent areas in shader output show the scene, not black.
        drawBackgroundAndBarsOnly();

        // Use replace blend mode to composite the shader result. Many prpr shaders
        // only write gl_FragColor.rgb, leaving alpha unset. Replace mode ignores
        // the source alpha and writes the FBO content directly, avoiding both
        // overexposure (additive blending with alpha=0) and black screen.
        GLES20.glBlendFunc(GLES20.GL_ONE, GLES20.GL_ZERO);
        drawFullscreenTextureFbo(finalTex, 1f, 1f, 1f, 1f);
        // Restore premultiplied-alpha blending for HUD and subsequent rendering.
        GLES20.glBlendFuncSeparate(
                GLES20.GL_ONE, GLES20.GL_ONE_MINUS_SRC_ALPHA,
                GLES20.GL_ONE, GLES20.GL_ONE_MINUS_SRC_ALPHA);

        // If there are no global shaders, HUD should remain unshaded (behavior).
        if (!hudAlreadyComposited) {
            drawGameHudWithAnimationsClipped();
        }

        // Apply a subtle intro fade overlay so the stage isn't jarringly bright
        // while decorative lines extend and HUD slides in.
        if (introFadeActive && savedGlobalAlpha < 1f) {
            float fadeAlpha = Math.max(0f, 1f - savedGlobalAlpha);
            if (fadeAlpha > 0.005f) {
                drawSolidRect(0, 0, viewW, viewH, 0f, 0f, 0f, fadeAlpha);
            }
        }

    }

    private void drawBackgroundAndBarsOnly() {
        // Background cover
        if (texBackgroundBlur != null) {
            drawTextureCover(texBackgroundBlur, 0f, 0f, (float) viewW, (float) viewH, 1.1f, true, 1f, 1f, 1f, 1f);
        } else if (texBackground != null) {
            drawTextureCover(texBackground, 0f, 0f, (float) viewW, (float) viewH, 1.1f, false, 1f, 1f, 1f, 1f);
        } else {
            drawSolidRect(0, 0, viewW, viewH, 0f, 0f, 0f, 1f);
        }
        // Dim outside-stage area ( unsafeBackgroundDim = 0.8, i.e. 20% visible)
        drawSolidRect(0, 0, viewW, viewH, 0f, 0f, 0f, 0.80f);
    }

    private Texture renderStageSceneToFbo(@NonNull FboTex target) {
        // Render the stage content as if the stage fills the whole target surface.
        // We temporarily override viewW/viewH and stage rect, then restore them.
        final int oldViewW = viewW;
        final int oldViewH = viewH;
        final float oldStageL = stageL;
        final float oldStageT = stageT;
        final float oldStageW = stageW;
        final float oldStageH = stageH;
        final float[] oldProj = proj.clone();
        final float[] oldStagePre = stagePreTransform.clone();

        GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, target.fbo);
        GLES20.glViewport(0, 0, target.w, target.h);

        viewW = target.w;
        viewH = target.h;
        stageL = 0f;
        stageT = 0f;
        stageW = (float) target.w;
        stageH = (float) target.h;

        Matrix.orthoM(proj, 0, 0f, viewW, viewH, 0f, -1f, 1f);

        Matrix.setIdentityM(stagePreTransform, 0);
        if (mirrorX) {
            float cx = stageW * 0.5f;
            Matrix.translateM(stagePreTransform, 0, cx, 0f, 0f);
            Matrix.scaleM(stagePreTransform, 0, -1f, 1f, 1f);
            Matrix.translateM(stagePreTransform, 0, -cx, 0f, 0f);
        }

        renderSceneDirect();

        // Restore state
        viewW = oldViewW;
        viewH = oldViewH;
        stageL = oldStageL;
        stageT = oldStageT;
        stageW = oldStageW;
        stageH = oldStageH;
        System.arraycopy(oldProj, 0, proj, 0, 16);
        System.arraycopy(oldStagePre, 0, stagePreTransform, 0, 16);
        GLES20.glViewport(0, 0, oldViewW, oldViewH);

        return target.tex;
    }

    private Texture applyPrprEffectChain(@NonNull List<PrprEffect> effects,
                                        @NonNull Texture input,
                                        @NonNull FboTex ping,
                                        @NonNull FboTex pong,
                                        int outW,
                                        int outH,
                                        double chartTimeSec) {
        Texture cur = input;
        FboTex out = (cur == ping.tex) ? pong : ping;
        for (PrprEffect effect : effects) {
            if (effect == null) continue;
            PrprShaderProgram prog = getPrprProgram(effect.shader);
            if (prog == null) continue;
            applyPrprEffectPass(effect, prog, cur, out, outW, outH, chartTimeSec);
            cur = out.tex;
            out = (out == ping) ? pong : ping;
        }
        return cur;
    }

    private void applyPrprEffectPass(@NonNull PrprEffect effect,
                                    @NonNull PrprShaderProgram prog,
                                    @NonNull Texture input,
                                    @NonNull FboTex output,
                                    int outW,
                                    int outH,
                                    double chartTimeSec) {
        GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, output.fbo);
        GLES20.glViewport(0, 0, outW, outH);
        GLES20.glClearColor(0f, 0f, 0f, 0f);
        GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT);

        // Disable blending so shader output replaces FBO content directly.
        boolean blendWasEnabled = GLES20.glIsEnabled(GLES20.GL_BLEND);
        if (blendWasEnabled) GLES20.glDisable(GLES20.GL_BLEND);

        // Bind program + texture
        GLES20.glUseProgram(prog.program);
        GLES20.glActiveTexture(GLES20.GL_TEXTURE0);
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, input.id);
        if (prog.uScreenTextureLoc >= 0) {
            GLES20.glUniform1i(prog.uScreenTextureLoc, 0);
        }

        // Defaults first (shader preset defaults via // %...%)
        prog.applyDefaults();

        // Common uniforms
        if (prog.uTimeLoc >= 0) {
            // Keep一致性: prpr passes the absolute chart time (seconds).
            GLES20.glUniform1f(prog.uTimeLoc, (float) chartTimeSec);
        }
        if (prog.uScreenSizeLoc >= 0) {
            // screenSize is the input buffer size in pixels.
            GLES20.glUniform2f(prog.uScreenSizeLoc, (float) input.width, (float) input.height);
        }
        if (prog.uUVScaleLoc >= 0) {
            // UVScale is viewport size divided by input texture size (behavior).
            float sx = (input.width != 0) ? ((float) outW / (float) input.width) : 1f;
            float sy = (input.height != 0) ? ((float) outH / (float) input.height) : 1f;
            GLES20.glUniform2f(prog.uUVScaleLoc, sx, sy);
        }

        // Effect vars
        for (Map.Entry<String, PrprEffect.PrprVar> ent : effect.vars.entrySet()) {
            String name = ent.getKey();
            if (name == null) continue;
            PrprShaderProgram.UniformInfo ui = prog.uniforms.get(name);
            if (ui == null || ui.location < 0) continue;
            float[] val = evaluatePrprVarValue(ent.getValue(), name, prog, chartTimeSec);
            if (val == null) continue;
            applyUniform(ui.location, ui.type, val);
        }

        drawFullQuadWithCurrentProgram(prog.aPosLoc, prog.aUvLoc, 0f, 1f, 1f, 0f);

        if (blendWasEnabled) GLES20.glEnable(GLES20.GL_BLEND);
    }

    

    private Texture applyPrprEffectChainFull(@NonNull List<PrprEffect> effects,
                                            @NonNull Texture input,
                                            @NonNull FboTex ping,
                                            @NonNull FboTex pong,
                                            int outW,
                                            int outH,
                                            double chartTimeSec) {
        Texture cur = input;
        FboTex out = (cur == ping.tex) ? pong : ping;
        for (PrprEffect effect : effects) {
            if (effect == null) continue;
            PrprShaderProgram prog = getPrprProgram(effect.shader);
            if (prog == null) continue;
            applyPrprEffectPassFull(effect, prog, cur, out, outW, outH, chartTimeSec);
            cur = out.tex;
            out = (out == ping) ? pong : ping;
        }
        return cur;
    }

    private Texture applyPrprEffectChainStageViewport(@NonNull List<PrprEffect> effects,
                                                     @NonNull Texture input,
                                                     @NonNull FboTex ping,
                                                     @NonNull FboTex pong,
                                                     int fullW,
                                                     int fullH,
                                                     double chartTimeSec,
                                                     float stageL,
                                                     float stageT,
                                                     float stageW,
                                                     float stageH) {
        Texture cur = input;
        FboTex out = (cur == ping.tex) ? pong : ping;

        int vx = Math.round(stageL);
        int vy = Math.round(fullH - (stageT + stageH));
        int vw = Math.round(stageW);
        int vh = Math.round(stageH);

        if (vw <= 0 || vh <= 0) return cur;
        if (vx < 0) { vw += vx; vx = 0; }
        if (vy < 0) { vh += vy; vy = 0; }
        if (vx + vw > fullW) vw = fullW - vx;
        if (vy + vh > fullH) vh = fullH - vy;
        if (vw <= 0 || vh <= 0) return cur;

        for (PrprEffect effect : effects) {
            if (effect == null) continue;
            PrprShaderProgram prog = getPrprProgram(effect.shader);
            if (prog == null) continue;

            applyPrprEffectPassViewport(effect, prog, cur, out, fullW, fullH, chartTimeSec, vx, vy, vw, vh);

            cur = out.tex;
            out = (out == ping) ? pong : ping;
        }
        return cur;
    }

    private void applyPrprEffectPassFull(@NonNull PrprEffect effect,
                                         @NonNull PrprShaderProgram prog,
                                         @NonNull Texture input,
                                         @NonNull FboTex output,
                                         int outW,
                                         int outH,
                                         double chartTimeSec) {
        GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, output.fbo);
        GLES20.glViewport(0, 0, outW, outH);
        // phispler: clear output to transparent before each shader pass
        GLES20.glClearColor(0f, 0f, 0f, 0f);
        GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT);

        // Disable blending so shader output replaces FBO content directly.
        // Shaders may not set alpha explicitly (e.g. gl_FragColor.rgb only),
        // and premultiplied blending with default alpha=0 would double-add brightness.
        boolean blendWasEnabled = GLES20.glIsEnabled(GLES20.GL_BLEND);
        if (blendWasEnabled) GLES20.glDisable(GLES20.GL_BLEND);

        applyPrprEffectPassCommon(effect, prog, input, outW, outH, chartTimeSec, outW, outH);

        drawFullQuadWithCurrentProgram(prog.aPosLoc, prog.aUvLoc, 0f, 1f, 1f, 0f);

        if (blendWasEnabled) GLES20.glEnable(GLES20.GL_BLEND);
    }

    private void applyPrprEffectPassViewport(@NonNull PrprEffect effect,
                                             @NonNull PrprShaderProgram prog,
                                             @NonNull Texture input,
                                             @NonNull FboTex output,
                                             int fullW,
                                             int fullH,
                                             double chartTimeSec,
                                             int vx, int vy, int vw, int vh) {
        // Stage (non-global) effects: copy full input to output, then render shader
        // scissored to the stage rect. Outside-stage pixels retain the original input.
        GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, output.fbo);
        GLES20.glViewport(0, 0, fullW, fullH);

        // Clear output before copying to prevent stale content from previous frames
        // from bleeding through via premultiplied-alpha blending.
        boolean blendWasEnabled = GLES20.glIsEnabled(GLES20.GL_BLEND);
        if (blendWasEnabled) GLES20.glDisable(GLES20.GL_BLEND);
        GLES20.glClearColor(0f, 0f, 0f, 0f);
        GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT);
        // Re-enable blending for the copy so the opaque input writes over the clear.
        if (blendWasEnabled) GLES20.glEnable(GLES20.GL_BLEND);

        drawFullscreenTextureFbo(input, 1f, 1f, 1f, 1f);

        // Render the shader effect only within the stage area via scissor.
        // Disable blending so shader output replaces stage-region content directly.
        boolean scissorWasEnabled = GLES20.glIsEnabled(GLES20.GL_SCISSOR_TEST);
        if (blendWasEnabled) GLES20.glDisable(GLES20.GL_BLEND);
        if (!scissorWasEnabled) GLES20.glEnable(GLES20.GL_SCISSOR_TEST);
        GLES20.glScissor(vx, vy, vw, vh);

        // Full-screen UVs (input is full-size FBO).
        float u0 = 0f, v0 = 1f, u1 = 1f, v1 = 0f;

        // Common uniforms: output size should be full buffer (viewport is full).
        applyPrprEffectPassCommon(effect, prog, input, fullW, fullH, chartTimeSec, fullW, fullH);

        drawFullQuadWithCurrentProgram(prog.aPosLoc, prog.aUvLoc, u0, v0, u1, v1);

        if (blendWasEnabled) GLES20.glEnable(GLES20.GL_BLEND);
        if (!scissorWasEnabled) GLES20.glDisable(GLES20.GL_SCISSOR_TEST);
    }

    private void applyPrprEffectPassCommon(@NonNull PrprEffect effect,
                                          @NonNull PrprShaderProgram prog,
                                          @NonNull Texture input,
                                          int screenW,
                                          int screenH,
                                          double chartTimeSec,
                                          int viewportW,
                                          int viewportH) {
        // Bind program + texture
        GLES20.glUseProgram(prog.program);
        GLES20.glActiveTexture(GLES20.GL_TEXTURE0);
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, input.id);
        if (prog.uScreenTextureLoc >= 0) {
            GLES20.glUniform1i(prog.uScreenTextureLoc, 0);
        }

        // Defaults first (shader preset defaults via // %...%)
        prog.applyDefaults();

        // Common uniforms
        if (prog.uTimeLoc >= 0) {
            float t = (float) (chartTimeSec - effect.startTimeSec);
            GLES20.glUniform1f(prog.uTimeLoc, t);
        }
        if (prog.uScreenSizeLoc >= 0) {
            GLES20.glUniform2f(prog.uScreenSizeLoc, (float) screenW, (float) screenH);
        }
        if (prog.uUVScaleLoc >= 0) {
            float sx = (screenW != 0) ? ((float) viewportW / (float) screenW) : 1f;
            float sy = (screenH != 0) ? ((float) viewportH / (float) screenH) : 1f;
            GLES20.glUniform2f(prog.uUVScaleLoc, sx, sy);
        }

        // Effect vars
        for (Map.Entry<String, PrprEffect.PrprVar> ent : effect.vars.entrySet()) {
            String name = ent.getKey();
            if (name == null) continue;
            PrprShaderProgram.UniformInfo ui = prog.uniforms.get(name);
            if (ui == null || ui.location < 0) continue;
            float[] val = evaluatePrprVarValue(ent.getValue(), name, prog, chartTimeSec);
            if (val == null) continue;
            applyUniform(ui.location, ui.type, val);
        }
    }
private float[] evaluatePrprVarValue(PrprEffect.PrprVar var,
                                         String uniformName,
                                         PrprShaderProgram prog,
                                         double chartTimeSec) {
        if (var == null) return null;
        if (var instanceof PrprEffect.ConstFloat) {
            return new float[]{((PrprEffect.ConstFloat) var).value};
        }
        if (var instanceof PrprEffect.ConstVec) {
            return ((PrprEffect.ConstVec) var).value;
        }
        if (var instanceof PrprEffect.FloatEvents) {
            List<LineEvent> events = ((PrprEffect.FloatEvents) var).events;
            float def = prog.getDefaultFloat(uniformName, 0f);
            float v = evalEventList(events, chartTimeSec, def);
            return new float[]{v};
        }
        return null;
    }

    private static float evalEventList(List<LineEvent> events, double t, float defaultVal) {
        if (events == null || events.isEmpty()) return defaultVal;
        // Before first event, return default
        LineEvent first = events.get(0);
        if (t < first.startTime) return defaultVal;

        float last = defaultVal;
        for (LineEvent e : events) {
            if (t < e.startTime) break;
            if (t <= e.endTime) {
                double dt = e.endTime - e.startTime;
                double p = (dt <= 0.0) ? 1.0 : (t - e.startTime) / dt;
                // apply easing/bezier/clip window like normal line events
                p = EventUtils.applyEasing(p, e);
                return (float) (e.start + (e.end - e.start) * p);
            }
            last = (float) e.end;
        }
        return last;
    }

    private void applyUniform(int loc, int type, float[] val) {
        if (loc < 0 || val == null || val.length == 0) return;
        switch (type) {
            case GLES20.GL_FLOAT:
                GLES20.glUniform1f(loc, val[0]);
                break;
            case GLES20.GL_FLOAT_VEC2:
                if (val.length >= 2) GLES20.glUniform2f(loc, val[0], val[1]);
                break;
            case GLES20.GL_FLOAT_VEC3:
                if (val.length >= 3) GLES20.glUniform3f(loc, val[0], val[1], val[2]);
                break;
            case GLES20.GL_FLOAT_VEC4:
                if (val.length >= 4) GLES20.glUniform4f(loc, val[0], val[1], val[2], val[3]);
                break;
        }
    }


    /**
     * Smoothed audio playhead: advances a local clock each frame via nanoTime and gently
     * corrects towards the native audio playhead for buttery motion at high refresh rates.
     */
    private double getSmoothedPlayheadSeconds() {
        final double raw = NativeAudioEngine.getPlayheadSeconds();
        final long nowNs = System.nanoTime();

        if (!smoothClockInit) {
            smoothClockInit = true;
            smoothLastFrameNs = nowNs;
            smoothPlayheadSec = raw;
            smoothLastRawPlayheadSec = raw;
            smoothReferencePlayheadSec = raw;
            smoothReferenceTimeNs = nowNs;
            smoothBiases.clear();
            smoothBiasSum = 0.0;
            return raw;
        }

        if (menuVisible && (pauseMode != PAUSE_FADEOUT || !resumeFadeUnpaused)) {
            smoothLastFrameNs = nowNs;
            smoothPlayheadSec = raw;
            smoothLastRawPlayheadSec = raw;
            smoothReferencePlayheadSec = raw;
            smoothReferenceTimeNs = nowNs;
            smoothBiases.clear();
            smoothBiasSum = 0.0;
            return raw;
        }

        double dt = (nowNs - smoothLastFrameNs) * 1e-9;
        if (!Double.isFinite(dt) || dt < 0.0) dt = 0.0;
        if (dt > 0.1) dt = 0.1; // Clamp large stalls

        // -style bias averaging for ultra-smooth playback
        // Calculate elapsed time since reference point
        double elapsedSinceRef = (nowNs - smoothReferenceTimeNs) * 1e-9;
        
        // Calculate bias: difference between predicted position and actual audio position
        // This captures the audio system's timing jitter
        double predicted = smoothReferencePlayheadSec + elapsedSinceRef * (double) musicSpeed;
        double bias = predicted - raw;
        
        // Add bias to history and maintain rolling average
        smoothBiases.add(bias);
        smoothBiasSum += bias;
        while (smoothBiases.size() > SMOOTH_BIAS_HISTORY_SIZE) {
            Double removed = smoothBiases.removeFirst();
            if (removed != null) smoothBiasSum -= removed;
        }
        
        // Calculate average bias over the history window
        double avgBias = smoothBiases.isEmpty() ? 0.0 : (smoothBiasSum / smoothBiases.size());
        
        // Detect discontinuities (restart/seek/pause-resume edge)
        double diff = raw - smoothLastRawPlayheadSec;
        boolean jumpedBack = diff < -0.1; // 100ms backwards
        boolean bigJump = Math.abs(diff) > 0.5; // 500ms mismatch
        
        double out;
        if (jumpedBack || bigJump || !Double.isFinite(predicted) || !Double.isFinite(raw)) {
            // Reset on discontinuity
            out = raw;
            smoothReferencePlayheadSec = raw;
            smoothReferenceTimeNs = nowNs;
            smoothBiases.clear();
            smoothBiasSum = 0.0;
        } else {
            // Apply bias-corrected smoothing
            // Subtract average bias to get smooth, jitter-free time
            out = predicted - avgBias;
            
            // Gentle correction to prevent drift
            // Keep smoothed time within a tight tolerance of raw time
            final double maxDeviation = 0.010; // ±10ms tolerance (tighter than before)
            if (out > raw + maxDeviation) out = raw + maxDeviation;
            if (out < raw - maxDeviation) out = raw - maxDeviation;
        }

        smoothLastFrameNs = nowNs;
        smoothPlayheadSec = out;
        smoothLastRawPlayheadSec = raw;
        return out;
    }

    // ---------------- Rendering core ----------------

    private void renderSceneDirect() {
        boolean needBars = !isStageFullscreen();

        if (needBars) {
            if (texBackgroundBlur != null) {
                drawTextureCover(texBackgroundBlur, 0f, 0f, viewW, viewH, 1.1f, true, 1f, 1f, 1f, 1f);
            } else if (texBackground != null) {
                drawTextureCover(texBackground, 0f, 0f, viewW, viewH, 1.1f, false, 1f, 1f, 1f, 1f);
            } else {
                drawSolidRect(0, 0, viewW, viewH, 0f, 0f, 0f, 1f);
            }

            // Dim outside-stage area ( unsafeBackgroundDim = 0.8, i.e. 20% visible)
            drawSolidRect(0, 0, viewW, viewH, 0f, 0f, 0f, 0.80f);

            enableStageScissor();
        }

        inStageSpace = true;
        batchFlipUv = mirrorX;

        // Deferred intro initialization for restart: beginIntro() must run on
        // the GL thread so that introStartNs is a valid wall-clock reference.
        if (restartIntroNeedsInit) {
            restartIntroNeedsInit = false;
            beginIntro();
            wasInIntroOrOutro = true;
            introAutoResumePending = true;
            introAutoResumeAtNs = introStartNs + (long) (INTRO_BUFFER_MS * 1_000_000L);
        }

        // Detect when music actually starts playing (initial play path).
        // NativeAudioEngine is started from PlayActivity at INTRO_BUFFER_MS.
        // We reset smoothClockInit so the next getSmoothedPlayheadSeconds()
        // initializes fresh from the actual audio playhead, avoiding
        // accumulated biases from the pre-music gap period.
        if (!isMusicStartedFlag) {
            double rawCheck = NativeAudioEngine.getPlayheadSeconds();
            if (rawCheck > 0.001) {
                isMusicStartedFlag = true;
                smoothClockInit = false;
            }
        }
        final double musicPos = getSmoothedPlayheadSeconds();
        frameIntroLineScale = computeIntroLineScale();
        float outroAlpha = computeOutroAlpha(musicPos);
        frameGlobalAlpha = introStarted ? 1f : 0f;

        // 200ms hold after intro animation completes.
        // During this hold, the fake line stays at full width while real lines
        // are computed (but hidden). After 200ms, real lines appear.
        if (introStarted && frameIntroLineScale >= 0.999f && !isInOutro && !isIntroHoldDone) {
            if (!isInIntroHold && introHoldStartNs < 0L) {
                isInIntroHold = true;
                introHoldStartNs = System.nanoTime();
            }
        }
        if (isInIntroHold) {
            if (System.nanoTime() - introHoldStartNs >= INTRO_HOLD_NS) {
                isInIntroHold = false;
                introHoldStartNs = -1L;
                isIntroHoldDone = true; // prevent re-triggering the hold
            }
        }

        // during intro, hold, outro, or before music starts, use fake line and clamp t
        boolean inIntroOrOutro = (frameIntroLineScale < 0.999f) || isInIntroHold || isInOutro || !isMusicStartedFlag;

        // When exiting the intro/hold/outro state on this frame, snapshot the
        // audio playhead as a reference.  Subtracting this reference from
        // every subsequent musicPos reading makes the first real chart frame
        // start at t≈0 regardless of whether the audio was unpaused by
        // maybeAutoResumeAfterIntro() (restart) or by PlayActivity (initial
        // start), and without any hard t=0 discontinuity.
        final boolean justExitedIntro = (!inIntroOrOutro && wasInIntroOrOutro);
        if (justExitedIntro) {
            musicReferenceAtIntroExit = musicPos;
            smoothClockInit = false;
            isMusicStartedFlag = true;
        }
        wasInIntroOrOutro = inIntroOrOutro;

        double t;
        if (inIntroOrOutro) {
            // during enter/hold/exit, chart time is clamped to 0
            t = Math.max(0.0, musicPos - musicReferenceAtIntroExit
                              - chart.offset - userOffsetSec);
        } else {
            t = musicPos - musicReferenceAtIntroExit
                - chart.offset - userOffsetSec;
        }

        if (texBackgroundBlur != null) {
            drawTextureCover(texBackgroundBlur, stageL, stageT, stageW, stageH, 1.0f, true, 1f, 1f, 1f, frameGlobalAlpha);
        } else if (texBackground != null) {
            drawTextureCover(texBackground, stageL, stageT, stageW, stageH, 1.0f, false, 1f, 1f, 1f, frameGlobalAlpha);
        } else {
            drawSolidRect(stageL, stageT, stageW, stageH, 0f, 0f, 0f, frameGlobalAlpha);
        }

        // backgroundDim is applied independently (not multiplied by globalAlpha).
        // during exit, bg dim fades out (cover.alpha = bgDim * progress)
        float dimAlpha = backgroundDim;
        drawSolidRect(stageL, stageT, stageW, stageH, 0f, 0f, 0f, dimAlpha);

        updateGameplay(t);

        ensureLineDrawOrder();
        final List<JudgeLine> lines = chart.judgeLineList;
        final float noteWidthBase0 = stageW * 0.1234375f * keyScale;
        final float stageAspect = (stageH > 1e-6f) ? (stageW / stageH) : (16f / 9f);

        final float noteCullL = stageL - stageW / 12f;
        final float noteCullT = stageT - stageH / 12f;
        final float noteCullR = stageL + stageW * 13f / 12f;
        final float noteCullB = stageT + stageH * 13f / 12f;

        if (lines != null) {
            for (int i = 0; i < lines.size(); i++) {
                JudgeLine line = lines.get(i);
                if (line == null) continue;

                JudgeLine.StateHolder st = line.fillState(t, stageAspect);

                line.updateLoopStartIndex(t);

            }
        }

        // during intro, hold, and outro, draw a single fake judgment line
        // at the center of the stage instead of the real chart judgment lines.
        // Real lines are hidden (alpha=0) — only the fake line animates.
        // (inIntroOrOutro is computed above, after computeIntroLineScale)

        if (inIntroOrOutro) {
            // Draw fake judgment line at center of stage (fakeJudgeline)
            float combinedScale = frameIntroLineScale * frameOutroLineScale;
            drawFakeJudgeLine(combinedScale);
        } else if (lines != null) {
            // Normal gameplay: draw real chart judgment lines
            for (int oi = 0; oi < lineDrawOrder.length; oi++) {
                JudgeLine line = lines.get(lineDrawOrder[oi]);
                if (line == null) continue;

                if (line.attachUiElementId != 0) continue;

                JudgeLine.StateHolder st = line.lastState;
                if (st == null) continue;

                float lineRot = st.rotateDeg;
                float lineX = stageL + st.xNorm * stageW;
                float lineY = stageT + st.yNorm * stageH;
                float lineAlphaRaw = st.alpha;
                float lineAlpha = normalizeAlpha01(lineAlphaRaw);
                double appearBeforeBeats = decodePrprAppearBeforeBeats(lineAlphaRaw);

                double rad = lineRot * Math.PI / 180.0;
                float cosLine = (float) Math.cos(rad);
                float sinLine = (float) Math.sin(rad);

                if (!st.hideLine) {
                    drawJudgeLineVisual(line, st, lineX, lineY, lineRot, cosLine, sinLine, lineAlpha, 1f);
                }
            }
        }

        // notes are hidden during intro, hold, and exit animation
        // (inIntroOrOutro already includes intro, hold, and outro states)
        boolean blockNotesDuringIntro = inIntroOrOutro || t < 0;

        if (!blockNotesDuringIntro && lines != null) {
            Texture[] holdTextures = {texHold, texHoldMh};
            for (Texture holdTex : holdTextures) {
                if (holdTex == null) continue;
                for (int oi = 0; oi < lineDrawOrder.length; oi++) {
                    JudgeLine line = lines.get(lineDrawOrder[oi]);
                    if (line == null || line.attachUiElementId != 0) continue;
                    JudgeLine.StateHolder st = line.lastState;
                    if (st == null || line.notes == null || st.hideNotes) continue;

                    float lineRot = st.rotateDeg;
                    float lineX = stageL + st.xNorm * stageW;
                    float lineY = stageT + st.yNorm * stageH;
                    float lineAlphaRaw = st.alpha;
                    double appearBeforeBeats = decodePrprAppearBeforeBeats(lineAlphaRaw);

                    double rad = lineRot * Math.PI / 180.0;
                    float cosLine = (float) Math.cos(rad);
                    float sinLine = (float) Math.sin(rad);

                    double beatt = line.sec2beat(t);
                    double lineFp = EventUtils.getFloorPosition(beatt, line.speedEvents);

                    int invisibleCount = 0;
                    for (int nIdx = line.loopStartIndex; nIdx < line.notes.size(); nIdx++) {
                        Note note = line.notes.get(nIdx);
                        if (!note.isHold || note.holdEndTime < t) continue;
                        
                        Texture noteHoldTex = (note.morebets == 1) ? texHoldMh : texHold;
                        if (noteHoldTex != holdTex) continue;

                        if (isBeforePrprAppearTime(note, t, appearBeforeBeats)) continue;

                        double bpm = (line.bpm > 0) ? line.bpm : 120.0;
                        double speed = (note.useOfficialSpeed ? 1.0 : note.speed) * scrollSpeed;
                        double speedForHoldBody = note.isHold ? (note.useOfficialSpeed ? 1.0 : note.speed) * scrollSpeed : speed;

                        // separate base and transY
                        double baseFpD = (note.floorPosition - lineFp) * GameConstants.PGRH * (GameConstants.PGRBEAT / bpm) * stageH * speed;
                        float baseFpPx = (float) baseFpD;
                        float transYPx = yOffsetToTransYPx(note.yOffset, speed, stageH);

                        float yCtrl = line.calcNoteControl(baseFpPx, JudgeLine.NOTE_CTRL_Y, 1f);
                        float baseFpWithCtrl = applyYControlScale(baseFpPx, yCtrl);
                        float visualFp = baseFpWithCtrl + transYPx;
                        float signedLength = applyYControlScale((float) (note.holdLength * stageH * speedForHoldBody), yCtrl);

                        float noteAlpha = computeNoteRenderAlpha(note.alpha, lineAlphaRaw, note.isAbove, (chart != null && chart.formatVersion > 0));
                        //: hold notes with speed === 0 are hidden
                        if (note.isHold && note.speed == 0.0) noteAlpha = 0f;
                        noteAlpha *= frameGlobalAlpha;
                        if (note.judgeResult == JR_MISS) noteAlpha *= 0.5f;
                        
                        // Cover culling only applies before the hold head reaches the
                        // judge line. After the head passes, the body is clamped to
                        // the line and must not be hidden by the cover, even when the
                        // unclamped visualFp is far behind lineFp.
                        if (lineAlphaRaw < 0f && noteAlpha > 0f && note.sect > t) {
                             int w = (int) Math.floor(-lineAlphaRaw);
                             if (w == 2) {
                                 float topFp = Math.max(visualFp, visualFp + signedLength);
                                 float bottomFp = Math.min(visualFp, visualFp + signedLength);

                                 if (note.isAbove) {
                                     if (topFp < -0.001f) noteAlpha = 0f;
                                 } else {
                                     if (bottomFp > 0.001f) noteAlpha = 0f;
                                 }
                             }
                        }

                        noteAlpha *= line.calcNoteControl(baseFpPx, JudgeLine.NOTE_CTRL_ALPHA, 1f);
                        noteAlpha = applyRpeVisibleTimeFade(note, t, noteAlpha);
                        boolean isHidden = (noteAlpha <= 0f);

                        if (isHidden) {
                            invisibleCount = 0;
                            continue;
                        }

                        // Compute actual draw positions before visible-range culling
                        // so the culling uses the clamped position when the head has
                        // already passed the judge line.
                        boolean holdHeadPassedLine = (note.sect <= t);
                        float drawHeadFp;
                        float drawTailFp;

                        if (holdHeadPassedLine) {
                            drawHeadFp = transYPx;
                            if (note.useOfficialSpeed) {
                                double elapsed = t - note.sect;
                                double totalSec = note.secht;
                                if (totalSec > 1e-9) {
                                    double remainingFrac = 1.0 - Math.min(1.0, Math.max(0.0, elapsed / totalSec));
                                    drawTailFp = drawHeadFp + signedLength * (float) remainingFrac;
                                } else {
                                    drawTailFp = drawHeadFp;
                                }
                            } else {
                                double holdEndFp = note.holdEndFloorPosition;
                                if (Double.isFinite(holdEndFp) && Math.abs(holdEndFp - note.floorPosition) > 1e-9) {
                                    double tailBaseFpD = (holdEndFp - lineFp) * GameConstants.PGRH * (GameConstants.PGRBEAT / bpm) * stageH * speedForHoldBody;
                                    float tailBaseFpPx = (float) tailBaseFpD;
                                    float tailYCtrl = line.calcNoteControl(tailBaseFpPx, JudgeLine.NOTE_CTRL_Y, 1f);
                                    float tailBaseFpWithCtrl = applyYControlScale(tailBaseFpPx, tailYCtrl);
                                    drawTailFp = tailBaseFpWithCtrl + transYPx;
                                } else {
                                    drawTailFp = drawHeadFp + signedLength;
                                }
                            }
                        } else {
                            drawHeadFp = visualFp;
                            drawTailFp = visualFp + signedLength;
                        }

                        // Correct cover culling with clamped positions for holds whose
                        // head has already passed the judge line. The earlier cover
                        // check used the unclamped visualFp which is wrong when the
                        // head is far behind the lineFp.
                        if (noteAlpha > 0f && lineAlphaRaw < 0f && holdHeadPassedLine) {
                            int coverW = (int) Math.floor(-lineAlphaRaw);
                            if (coverW == 2) {
                                float topFp = Math.max(drawHeadFp, drawTailFp);
                                float bottomFp = Math.min(drawHeadFp, drawTailFp);
                                if (note.isAbove) {
                                    if (topFp < -0.001f) noteAlpha = 0f;
                                } else {
                                    if (bottomFp > 0.001f) noteAlpha = 0f;
                                }
                            }
                        }
                        if (noteAlpha <= 0f) {
                            invisibleCount = 0;
                            continue;
                        }

                        computeLineVisibleRange(st, tmpVisibleRange);
                        float pad = stageW * 2.0f;
                        float hMin = Math.min(drawHeadFp, drawTailFp);
                        float hMax = Math.max(drawHeadFp, drawTailFp);

                        if (hMax < tmpVisibleRange[0] - pad || hMin > tmpVisibleRange[1] + pad) {
                            invisibleCount++;
                            if (invisibleCount > 50) break;
                            continue;
                        }
                        invisibleCount = 0;

                        HoldUv holdUv = getHoldUv(note.morebets);
                        float widthScale = 1.0f;
                        Texture normalHeadTex = getNoteHeadTextureSameType(note.type, 0);
                        Texture mhHeadTex = getNoteHeadTextureSameType(note.type, 1);
                        if (note.morebets == 1 && normalHeadTex != null && mhHeadTex != null && normalHeadTex.width > 0) {
                            widthScale = (float) mhHeadTex.width / (float) normalHeadTex.width;
                        }

                        float sizeMul = (!Float.isFinite(note.size) || note.size == 0f) ? 1f : note.size;
                        float xScaleMul = (!Float.isFinite(note.xScale) || note.xScale == 0f) ? 1f : note.xScale;
                        float scaleCtrl = line.calcNoteControl(baseFpPx, JudgeLine.NOTE_CTRL_SCALE, 1f);
                        if (!Float.isFinite(scaleCtrl) || scaleCtrl == 0f) scaleCtrl = 1f;

                        float inclineValue = 1f;
                        if (Float.isFinite(st.inclineSinr)) {
                            inclineValue = calcInclineValue(st.inclineSinr, baseFpWithCtrl, transYPx, stageW / (float) stageH, (float) stageH);
                        }

                        float baseWidth = noteWidthBase0 * sizeMul * widthScale * scaleCtrl * inclineValue;
                        float noteWidth = baseWidth * xScaleMul;

                        float headHpx = holdUv.headHpx, tailHpx = holdUv.tailHpx;
                        float noteHeadH = (headHpx > 0 && holdTex.width > 0) ? (baseWidth / (float) holdTex.width) * headHpx : baseWidth;
                        float tailH = (tailHpx > 0 && holdTex.width > 0) ? (baseWidth / (float) holdTex.width) * tailHpx : noteHeadH;

                        float offsetX = (float) (note.positionX * GameConstants.PGRW * stageW);
                        float xCtrl = line.calcNoteControl(baseFpPx, JudgeLine.NOTE_CTRL_X, 1f);
                        // for hold, tr.x is NOT multiplied by incline or xCtrl
                        // (hold x position stays at positionX without perspective distortion)
                        
                        float noteAtX = lineX + offsetX * cosLine;
                        float noteAtY = lineY + offsetX * sinLine;

                        float dirX = note.isAbove ? sinLine : -sinLine;
                        float dirY = note.isAbove ? -cosLine : cosLine;

                        if (line.isCover && note.sect > t && visualFp < 0f) continue;
                        // For negative-length (reverse) holds before the head reaches the judge line:
                        // when the tail crosses past the judge line to the opposite side from the head,
                        // clamp it to the judge line (Fp=0). This prevents the tail from rendering on
                        // the back side where it appears embedded in the hold body because its
                        // orientation is not flipped. Once the head has passed the line, the tail is
                        // allowed to extend in the negative direction as the hold shrinks normally.
                        // Normal (positive-length) holds are NOT affected.
                        if (!holdHeadPassedLine && signedLength < 0f) {
                            if (drawHeadFp >= 0f && drawTailFp < 0f) {
                                drawTailFp = 0f;
                            } else if (drawHeadFp <= 0f && drawTailFp > 0f) {
                                drawTailFp = 0f;
                            }
                        }
                        // When the hold has been hit and the tail is below the head
                        // (negative visual length), clamp the tail to the judgment
                        // line (Fp=0) to prevent it from crossing to the opposite side.
                        // The clamp naturally releases when the unclamped tail position
                        // returns above the head (positive visual length).
                        float drawTailFpUnclamped = drawTailFp;
                        if (holdHeadPassedLine && drawTailFp < drawHeadFp) {
                            drawTailFp = Math.max(drawTailFp, 0f);
                        }
                        boolean tailClampedToJudgmentLine = holdHeadPassedLine && drawTailFpUnclamped < 0f && drawTailFp == 0f;
                        // Reverse hold: when tail crosses past the head (length ≤ 0),
                        // lock the length to 0 — skip body and tail rendering entirely.
                        // Exception: when the tail is clamped to the judgment line above,
                        // allow rendering so the tail stays visible at the judgment line.
                        boolean reverseHoldLocked = holdHeadPassedLine && speed < 0 && drawTailFp >= drawHeadFp && !tailClampedToJudgmentLine;
                        float drawRot = lineRot + (note.isAbove ? 0f : 180f);

                        // hold note is a PIXI Container with children
                        // [head, body, end] rendered in that order (head behind body,
                        // body behind end). Match this order: head → body → tail.
                        if (!reverseHoldLocked) {
                        // --- Head (for future holds, before the head reaches the judge line) ---
                        // hold container layout (head above body):
                        // head center at judgmentPoint + headH/2
                        // body start  at judgmentPoint
                        // Shift both head and body down by 0.4 * headH to match, keeping them adjacent:
                        // head center at visualFp - 0.4 * headH
                        // body start  at visualFp + 0.1 * headH
                        if (!holdHeadPassedLine) {
                            float headCenterFp = visualFp - noteHeadH * 0.4f;
                            // Clamp: keep at least half the head visible above the judge line.
                            float headDrawFp = Math.max(-noteHeadH * 0.5f, headCenterFp);
                            float headX = noteAtX + headDrawFp * dirX;
                            float headY = noteAtY + headDrawFp * dirY;
                            if (isNoteRectInArea(headX, headY, noteWidth, noteHeadH, drawRot, noteCullL, noteCullT, noteCullR, noteCullB)) {
                                addQuadToBatch(headX, headY, noteWidth, noteHeadH, drawRot, 1f, 1f, 1f, noteAlpha, 0f, holdUv.headV0, 1f, holdUv.headV1);
                            }
                        }

                        // --- Body ---
                        float bodyStartFp, bodyEndFp;
                        if (holdHeadPassedLine) {
                            bodyStartFp = drawHeadFp;
                            // Body extends to meet the shifted tail (tail shifted +tailH).
                            bodyEndFp = drawTailFp;
                        } else {
                            // body starts at visualFp + 0.1*headH, adjacent below the head.
                            // Head bottom: (visualFp - 0.4*headH) + headH/2 = visualFp + 0.1*headH ✓
                            bodyStartFp = visualFp + noteHeadH * 0.1f;
                            bodyEndFp = drawTailFp;
                        }

                        boolean isReverseHold = holdHeadPassedLine && (drawTailFp < drawHeadFp);
                        float drawStartFp, drawEndFp;
                        if (isReverseHold) {
                            drawStartFp = Math.min(bodyStartFp, bodyEndFp);
                            drawEndFp = Math.max(bodyStartFp, bodyEndFp);
                            drawEndFp = Math.min(drawEndFp, 0f);
                        } else {
                            drawStartFp = Math.max(0f, Math.min(bodyStartFp, bodyEndFp));
                            drawEndFp = Math.max(0f, Math.max(bodyStartFp, bodyEndFp));
                        }
                        float drawLen = drawEndFp - drawStartFp;

                        if (drawLen > 0.01f) {
                            float bodyX = noteAtX + ((drawStartFp + drawEndFp) / 2f) * dirX;
                            float bodyY = noteAtY + ((drawStartFp + drawEndFp) / 2f) * dirY;
                            if (isNoteRectInArea(bodyX, bodyY, noteWidth, drawLen, drawRot, noteCullL, noteCullT, noteCullR, noteCullB)) {
                                addQuadToBatch(bodyX, bodyY, noteWidth, drawLen, drawRot, 1f, 1f, 1f, noteAlpha, 0f, holdUv.bodyV0, 1f, holdUv.bodyV1);
                            }
                        }

                        // --- Tail ---
                        {
                            // Lift tail up by 1 tail height away from the head.
                            float tailTipFp = drawTailFp + tailH;
                            if (!isReverseHold) {
                                if (tailTipFp < tailH) tailTipFp = tailH;
                            } else {
                                if (tailTipFp > -tailH) tailTipFp = -tailH;
                            }
                            float tailPosFp = tailTipFp - (tailH / 2f);
                            float tailX = noteAtX + tailPosFp * dirX;
                            float tailY = noteAtY + tailPosFp * dirY;
                            float tailRot = drawRot;
                            if (isNoteRectInArea(tailX, tailY, noteWidth, tailH, tailRot, noteCullL, noteCullT, noteCullR, noteCullB)) {
                                addQuadToBatch(tailX, tailY, noteWidth, tailH, tailRot, 1f, 1f, 1f, noteAlpha, 0f, holdUv.tailV0, 1f, holdUv.tailV1);
                            }
                        }
                        } // end if (!reverseHoldLocked)
                        if (batchCount >= MAX_BATCH_QUADS - 3) flushBatch(holdTex);
                    }
                }
                flushBatch(holdTex);
            }
        }

        if (!blockNotesDuringIntro && lines != null) {
            for (int ti = 0; ti < noteHeadTextureDrawList.size(); ti++) {
                Texture currentHeadTex = noteHeadTextureDrawList.get(ti);
                if (currentHeadTex == null) continue;
                for (int oi = 0; oi < lineDrawOrder.length; oi++) {
                    JudgeLine line = lines.get(lineDrawOrder[oi]);
                    if (line == null || line.attachUiElementId != 0) continue;
                    JudgeLine.StateHolder st = line.lastState;
                    if (st == null || line.notes == null || st.hideNotes) continue;

                    float lineRot = st.rotateDeg;
                    float lineX = stageL + st.xNorm * stageW;
                    float lineY = stageT + st.yNorm * stageH;
                    float lineAlphaRaw = st.alpha;
                    double appearBeforeBeats = decodePrprAppearBeforeBeats(lineAlphaRaw);

                    double rad = lineRot * Math.PI / 180.0;
                    float cosLine = (float) Math.cos(rad);
                    float sinLine = (float) Math.sin(rad);

                    double beatt = line.sec2beat(t);
                    double lineFp = EventUtils.getFloorPosition(beatt, line.speedEvents);

                    int invisibleCount = 0;
                    for (int nIdx = line.loopStartIndex; nIdx < line.notes.size(); nIdx++) {
                        Note note = line.notes.get(nIdx);
                        
                        Texture noteHTex;
                        if (note.isHold) {
                            noteHTex = (note.morebets == 1) ? texHoldMh : texHold;
                        } else {
                            noteHTex = getNoteHeadTexture(note);
                        }
                        if (noteHTex != currentHeadTex) continue;

                        // Hold heads are now rendered in the body pass (hold body + head are a single container). Skip holds here.
                        if (note.isHold) continue;

                        if (note.judgeResult == JR_PERFECT || note.judgeResult == JR_GOOD) continue;
                        if (note.judgeResult == JR_BAD || note.judgeResult == JR_MISS) continue;
                        double missFadeWin = challengeMode ? WINC_GOOD : WIN_GOOD;
                        double late = t - note.sect;
                        if (late > missFadeWin) continue;
                        if (note.isFake && note.sect <= t) continue;

                        if (isBeforePrprAppearTime(note, t, appearBeforeBeats)) continue;

                        double bpm = (line.bpm > 0) ? line.bpm : 120.0;
                        double speed = (note.isHold && note.useOfficialSpeed ? 1.0 : note.speed) * scrollSpeed;

                        // separate base and transY
                        double baseFpD = (note.floorPosition - lineFp) * GameConstants.PGRH * (GameConstants.PGRBEAT / bpm) * stageH * speed;
                        float baseFpPx = (float) baseFpD;
                        float transYPx = yOffsetToTransYPx(note.yOffset, speed, stageH);

                        float yCtrl = line.calcNoteControl(baseFpPx, JudgeLine.NOTE_CTRL_Y, 1f);
                        float baseFpWithCtrl = applyYControlScale(baseFpPx, yCtrl);
                        float visualFp = baseFpWithCtrl + transYPx;

                        if ((chart != null && chart.formatVersion > 0) && !note.isHold && note.sect > t && visualFp < -0.001f) {
                            invisibleCount = 0;
                            continue;
                        }

                        float noteAlpha = computeNoteRenderAlpha(note.alpha, lineAlphaRaw, note.isAbove, (chart != null && chart.formatVersion > 0));
                        //: hold notes with speed === 0 are hidden
                        if (note.isHold && note.speed == 0.0) noteAlpha = 0f;
                        noteAlpha *= frameGlobalAlpha;
                        
                        if (lineAlphaRaw < 0f && noteAlpha > 0f) {
                             int w = (int) Math.floor(-lineAlphaRaw);
                             if (w == 2) {
                                 if (note.isAbove) {
                                     if (visualFp < -0.001f) noteAlpha = 0f;
                                 } else {
                                     if (visualFp > 0.001f) noteAlpha = 0f;
                                 }
                             }
                        }

                        noteAlpha *= line.calcNoteControl(baseFpPx, JudgeLine.NOTE_CTRL_ALPHA, 1f);
                        noteAlpha = applyRpeVisibleTimeFade(note, t, noteAlpha);
                        if (!note.isHold && note.judgeResult < 0 && note.sect <= t) {
                            float p = (float) ((t - note.sect) / missFadeWin);
                            p = MathUtils.clamp(p, 0f, 1f);
                            noteAlpha *= (1f - p);
                        }
                        boolean isHidden = (noteAlpha <= 0f);
                        if (isHidden) {
                            invisibleCount = 0;
                            continue;
                        }

                        computeLineVisibleRange(st, tmpVisibleRange);
                        float pad = stageW * 2.0f;
                        if (visualFp < tmpVisibleRange[0] - pad || visualFp > tmpVisibleRange[1] + pad) {
                            invisibleCount++;
                            if (invisibleCount > 50) break;
                            continue;
                        }
                        invisibleCount = 0;

                        float widthS = 1.0f;
                        Texture normalHeadT = getNoteHeadTextureSameType(note.type, 0);
                        Texture mhHeadT = getNoteHeadTextureSameType(note.type, 1);
                        if (note.morebets == 1 && normalHeadT != null && mhHeadT != null && normalHeadT.width > 0) {
                            widthS = (float) mhHeadT.width / (float) normalHeadT.width;
                        }

                        float sizeM = (!Float.isFinite(note.size) || note.size == 0f) ? 1f : note.size;
                        float xScaleM = (!Float.isFinite(note.xScale) || note.xScale == 0f) ? 1f : note.xScale;
                        float scaleC = line.calcNoteControl(baseFpPx, JudgeLine.NOTE_CTRL_SCALE, 1f);
                        if (!Float.isFinite(scaleC) || scaleC == 0f) scaleC = 1f;

                        float inclineV = 1f;
                        if (Float.isFinite(st.inclineSinr)) {
                            inclineV = calcInclineValue(st.inclineSinr, baseFpWithCtrl, transYPx, stageW / (float) stageH, (float) stageH);
                        }

                        float baseW = noteWidthBase0 * sizeM * widthS * scaleC * inclineV;
                        float noteW = baseW * xScaleM;
                        float nHeadH = (currentHeadTex.height > 0 && currentHeadTex.width > 0) ? (baseW / (float) currentHeadTex.width) * (float) currentHeadTex.height : baseW;

                        // for non-hold, tr.x *= incline_val * ctrl_obj.pos
                        float offX = (float) (note.positionX * GameConstants.PGRW * stageW);
                        float xCtrl = line.calcNoteControl(baseFpPx, JudgeLine.NOTE_CTRL_X, 1f);
                        offX *= inclineV;
                        if (Float.isFinite(xCtrl) && xCtrl != 0f) offX *= xCtrl;
                        float nAtX = lineX + offX * cosLine;
                        float nAtY = lineY + offX * sinLine;

                        float dirX = note.isAbove ? sinLine : -sinLine;
                        float dirY = note.isAbove ? -cosLine : cosLine;

                        if (line.isCover && note.sect > t && visualFp < 0f) continue;

                        float drawHeadFp = visualFp;
                        float headX = nAtX + drawHeadFp * dirX;
                        float headY = nAtY + drawHeadFp * dirY;
                        float drawRot = lineRot + (note.isAbove ? 0f : 180f);

                        if (isNoteRectInArea(headX, headY, Math.abs(noteW), Math.abs(nHeadH), drawRot, noteCullL, noteCullT, noteCullR, noteCullB)) {
                            addQuadToBatch(headX, headY, noteW, nHeadH, drawRot, 1f, 1f, 1f, noteAlpha, 0f, 0f, 1f, 1f);
                        }
                        if (batchCount >= MAX_BATCH_QUADS) flushBatch(currentHeadTex);
                    }
                }
                flushBatch(currentHeadTex);
            }
        }


        
        if (autoplay) drawClickEffects(t);
        drawBadEffects(t);
        drawHitEffects(t);

        if (showDebugInfo) drawDebugOverlay(t, lines);

        if (needBars) {
            disableStageScissor();
        }

        inStageSpace = false;
    }

    /** Debug overlay: colored rectangles at judge line/note positions. Lines=violet, Notes=lime. */
    private void drawDebugOverlay(double chartTime, @Nullable List<JudgeLine> lines) {
        if (lines == null || lineDrawOrder == null || texWhite == null) return;
        final float stageAspect = (stageH > 1e-6f) ? (stageW / stageH) : (16f / 9f);
        final float rectSize = stageH * 0.005f;
        final float textSizePx = rectSize * 6f;

        for (int oi = 0; oi < lineDrawOrder.length; oi++) {
            JudgeLine line = lines.get(lineDrawOrder[oi]);
            if (line == null || line.attachUiElementId != 0) continue;
            JudgeLine.StateHolder st = line.lastState;
            if (st == null) continue;

            float lineX = stageL + st.xNorm * stageW;
            float lineY = stageT + st.yNorm * stageH;
            float lineRot = st.rotateDeg;

            // Violet rectangle + line ID label at line position
            if (isNoteRectInArea(lineX, lineY, rectSize, rectSize, 0f,
                    stageL, stageT, stageL + stageW, stageT + stageH)) {
                addQuadToBatch(lineX, lineY, rectSize * 4f, rectSize * 4f, 0f,
                        0.8f, 0.2f, 1.0f, 1f, 0f, 0f, 1f, 1f);
            }
            flushBatch(texWhite);
            
            // Draw line ID label text
            String lineLabel = String.valueOf(lineDrawOrder[oi]);
            Texture lineLabelTex = getOrCreateDebugText(lineLabel, textSizePx);
            if (lineLabelTex != null) {
                drawTextureAnchored(lineLabelTex, lineX, lineY - rectSize * 2f,
                        lineLabelTex.width, lineLabelTex.height, 0f,
                        1f, 0.9f, 0.2f, 1f, 0.5f, 1f);
            }

            // Draw note positions on this line
            if (line.notes == null || st.hideNotes) continue;

            double rad = lineRot * Math.PI / 180.0;
            float cosLine = (float) Math.cos(rad);
            float sinLine = (float) Math.sin(rad);

            double beatt = line.sec2beat(chartTime);
            double lineFp = EventUtils.getFloorPosition(beatt, line.speedEvents);

            int noteIdx = 0;
            for (Note note : line.notes) {
                if (note == null) { noteIdx++; continue; }
                double bpm = (line.bpm > 0) ? line.bpm : 120.0;
                double speed = (note.useOfficialSpeed ? 1.0 : note.speed) * scrollSpeed;

                double baseFpD = (note.floorPosition - lineFp) * GameConstants.PGRH * (GameConstants.PGRBEAT / bpm) * stageH * speed;
                float baseFpPx = (float) baseFpD;
                float transYPx = yOffsetToTransYPx(note.yOffset, speed, stageH);
                float yCtrl = line.calcNoteControl(baseFpPx, JudgeLine.NOTE_CTRL_Y, 1f);
                float baseFpWithCtrl = applyYControlScale(baseFpPx, yCtrl);
                float visualFp = baseFpWithCtrl + transYPx;

                float offX = (float) (note.positionX * GameConstants.PGRW * stageW);
                float xCtrl = line.calcNoteControl(baseFpPx, JudgeLine.NOTE_CTRL_X, 1f);
                float inclineV = 1f;
                if (Float.isFinite(st.inclineSinr)) {
                    inclineV = calcInclineValue(st.inclineSinr, baseFpWithCtrl, transYPx, stageAspect, stageH);
                }
                if (!note.isHold) {
                    offX *= inclineV;
                    if (Float.isFinite(xCtrl) && xCtrl != 0f) offX *= xCtrl;
                }

                float nAtX = lineX + offX * cosLine;
                float nAtY = lineY + offX * sinLine;
                float dirX = note.isAbove ? sinLine : -sinLine;
                float dirY = note.isAbove ? -cosLine : cosLine;

                float noteCx = nAtX + visualFp * dirX;
                float noteCy = nAtY + visualFp * dirY;

                // Check if note is hidden (alpha ≈ 0 or not visible)
                boolean isHidden = note.alpha <= 0f || (note.sect > chartTime && visualFp < -0.001f);
                float debugAlpha = isHidden ? 0.45f : 1f;

                boolean onScreen = isNoteRectInArea(noteCx, noteCy, rectSize, rectSize, 0f,
                        stageL, stageT, stageL + stageW, stageT + stageH);
                if (onScreen) {
                    // Lime rectangle at note position
                    addQuadToBatch(noteCx, noteCy, rectSize * 6f, rectSize * 6f, 0f,
                            0.2f, 1.0f, 0.2f, debugAlpha, 0f, 0f, 1f, 1f);
                }
                flushBatch(texWhite);

                // Note info label: type#idx Lline side sect
                if (onScreen) {
                    char nt;
                    switch (note.type) {
                        case GameConstants.NOTE_TAP:   nt = 'T'; break;
                        case GameConstants.NOTE_HOLD:  nt = 'H'; break;
                        case GameConstants.NOTE_DRAG:  nt = 'D'; break;
                        case GameConstants.NOTE_FLICK: nt = 'F'; break;
                        default: nt = '?';
                    }
                    String side = note.isAbove ? "A" : "B";
                    int lineIdx = lineDrawOrder[oi];
                    String label = nt + "#" + noteIdx + " L" + lineIdx + " " + side + " " +
                            String.format("%.2f", note.sect);
                    Texture noteLabelTex = getOrCreateDebugText(label, textSizePx);
                    if (noteLabelTex != null) {
                        float labelX = noteCx + rectSize * 3f + noteLabelTex.width * 0.5f;
                        float labelY = noteCy;
                        drawTextureAnchored(noteLabelTex, labelX, labelY,
                                noteLabelTex.width, noteLabelTex.height, 0f,
                                0.2f, 1.0f, 1.0f, debugAlpha, 0f, 0.5f);
                    }
                }

                noteIdx++;
            }
            flushBatch(texWhite);
        }
    }

    // ---------------- RePhiEdit helpers (draw order, storyboard textures) ----------------

    private static float normalizeAlpha01(float a) {
        if (!Float.isFinite(a)) return 1f;
        if (a <= 0f) return 0f;
        // Some editors store alpha in 0..255.
        if (a > 1.5f) return MathUtils.clamp(a / 255f, 0f, 1f);
        return MathUtils.clamp(a, 0f, 1f);
    }

    private static float normalizeNoteAlpha01(float a) {
        if (!Float.isFinite(a)) return 1f;
        // RePhiEdit / some editors store alpha in 0..255 or even negative packed values.
        if (a > 1.5f || a < -2.5f) a = a / 255f;
        return MathUtils.clamp(a, 0f, 1f);
    }

    /** Apply Y control scale. ctrl_obj.y multiplies speed, not the entire noteFp. */
    static float applyYControlScale(float baseFp, float yCtrl) {
        return Float.isFinite(yCtrl) ? (baseFp * yCtrl) : baseFp;
    }

    @Nullable
    private Texture getOrCreateDebugText(String text, float textPx) {
        Texture cached = debugTextCache.get(text);
        if (cached != null) {
            Texture valid = ensureTextureValid(cached);
            if (valid != null) return valid;
            debugTextCache.remove(text);
        }
        Texture tex = createHudTextTexture(text, textPx, false, false, false, false, true); // retain for GC survival
        if (tex != null) debugTextCache.put(text, tex);
        return tex;
    }

    /**
     * Calculate incline value using  formula.
     *incline_val = 1 - sin(incline) * (base * aspect + transY) * RPE_HEIGHT / 2 / 360
     *
     * @param inclineSin sin of incline angle (radians)
     * @param baseFpPx   base distance in pixels (note-to-line, WITH yCtrl applied, WITHOUT yOffset)
     * @param transYPx   y-offset in pixels ( yOffset * 2/RPE_HEIGHT * speed * stageH/2)
     * @param stageAspect stageW / stageH
     * @param stageH     stage height in pixels
     * @return incline scale factor
     */
    static float calcInclineValue(float inclineSin, float baseFpPx, float transYPx,
                                   float stageAspect, float stageH) {
        //1 - sin(incline) * (base_norm * aspect + transY_norm) * RPE_HEIGHT/2/360
        // In pixel space: (baseFpPx * aspect + transYPx) * RPE_HEIGHT / (stageH * 360)
        float param = (baseFpPx * stageAspect + transYPx) * GameConstants.RPE_HEIGHT / (stageH * 360f);
        float val = 1f - inclineSin * param;
        if (!Float.isFinite(val) || val == 0f) val = 1f;
        return val;
    }

    /**
     * Convert RPE yOffset to normalized y-offset in pixels.
     *translation.1 = yOffset * 2 / RPE_HEIGHT * speed
     * In pixel space: yOffset * 2 / RPE_HEIGHT * speed * stageH / 2
     */
    static float yOffsetToTransYPx(float yOffset, double speed, float stageH) {
        return yOffset * 2f / GameConstants.RPE_HEIGHT * (float)speed * stageH * 0.5f;
    }

    /**
     * Compute final note render alpha.
     * Important: in prpr, note alpha is NOT multiplied by the
     * judge line alpha. Judge line alpha only affects the line visual itself.
     * "negative alpha" extension (used by PhiEdit/PEC):
     *   [-1, 0): hide all notes
     *   [-2, -1): hide below notes, keep above notes
     */
    private static float computeNoteRenderAlpha(float noteAlphaRaw, float lineAlphaRaw, boolean isAbove, boolean isOfficial) {
        // Note's own alpha (may be 0..1 or 0..255 depending on editor).
        float noteA = normalizeNoteAlpha01(noteAlphaRaw);
        if (noteA <= 0f) return 0f;

        // Negative line alpha only controls NOTE VISIBILITY, not alpha blending.
        if (lineAlphaRaw < 0f) {
            // Official charts: STRICT negative alpha hidden ( disables extension).
            if (isOfficial) return 0f;

            // Logic aligned with  RPE extension: w matches the floor of negative alpha
            int w = (int) Math.floor(-lineAlphaRaw);
            
            // w=1 => range (-2, -1]: Hide ALL notes ( behavior).
            if (w == 1) return 0f;
            
            // w=2 => range (-3, -2]: Hide BELOW notes.
            // Moved to render loop to check visual position (visualFp) instead of static isAbove.
            // if (w == 2 && !isAbove) return 0f;
            
            // w=0 => range (-1, 0]: Hide ALL notes.
            // (Preserving Chigros legacy behavior for safety, although  default might be visible).
            if (w == 0) return 0f;
        }
        return noteA;
    }

    /**
     *  handling of RPE note {@code visibleTime}.
     *
     * <p>If {@code noteTime - currentTime > visibleTime}, the note is hard-hidden.
     * (No fade-in.)</p>
     */
    private static float applyRpeVisibleTimeFade(Note note, double currentSec, float alpha01) {
        if (alpha01 <= 0f) return 0f;
        float vt = note.visibleTime;
        if (!(vt >= 0f)) return alpha01; // Negative => always visible.
        double dt = note.sect - currentSec;
        return (dt > vt) ? 0f : alpha01;
    }

    /**
     * prpr-compatible "pe-alpha extension": when line alpha is negative and its integer part
     * encodes 100..999, it sets an <b>appear-before</b> beat count.
     */
    private static double decodePrprAppearBeforeBeats(float lineAlphaRaw) {
        if (!(lineAlphaRaw < 0f)) return Double.POSITIVE_INFINITY;
        int w = (int) Math.floor(-lineAlphaRaw);
        if (w >= 100 && w < 1000) {
            return (w - 100) / 10.0;
        }
        return Double.POSITIVE_INFINITY;
    }

    private boolean isBeforePrprAppearTime(Note note, double currentSec, double appearBeforeBeats) {
        if (!Double.isFinite(appearBeforeBeats)) return false;
        if (chart == null || chart.bpmTimeline == null) return false;

        // Use cached appear time if available and appearBeforeBeats hasn't changed.
        if (note.lastAppearBeforeBeats == appearBeforeBeats && Double.isFinite(note.cachedAppearSec)) {
            return currentSec < note.cachedAppearSec;
        }

        double gb = note.rpeGlobalBeat;
        if (!Double.isFinite(gb)) return false;
        double appearBeat = gb - appearBeforeBeats * 32.0;
        double appearSec = chart.bpmTimeline.beatToSec(appearBeat);

        // Cache for future frames
        note.cachedAppearSec = appearSec;
        note.lastAppearBeforeBeats = appearBeforeBeats;

        return currentSec < appearSec;
    }

    private void ensureLineDrawOrder() {
        final List<JudgeLine> lines = (chart != null) ? chart.judgeLineList : null;
        final int n = (lines != null) ? lines.size() : 0;
        if (lineDrawOrder != null && lineDrawOrderCount == n) {
            return;
        }
        lineDrawOrderCount = n;
        lineDrawOrder = new int[n];
        for (int i = 0; i < n; i++) {
            lineDrawOrder[i] = i;
        }
        // Stable insertion sort by zOrder.
        for (int i = 1; i < n; i++) {
            int key = lineDrawOrder[i];
            int zKey = getLineZOrder(lines, key);
            int j = i - 1;
            while (j >= 0) {
                int zJ = getLineZOrder(lines, lineDrawOrder[j]);
                if (zJ > zKey) {
                    lineDrawOrder[j + 1] = lineDrawOrder[j];
                    j--;
                } else {
                    break;
                }
            }
            lineDrawOrder[j + 1] = key;
        }
    }

    private static int getLineZOrder(@Nullable List<JudgeLine> lines, int idx) {
        if (lines == null) return 0;
        if (idx < 0 || idx >= lines.size()) return 0;
        JudgeLine l = lines.get(idx);
        return l != null ? l.zOrder : 0;
    }

    /**
     * fakeJudgeline: a single decorative line drawn at the center
     * of the stage during intro and exit animations. Real judgment lines are hidden
     * during these phases — only this fake line is visible.
     *
     * @param scale 0→1 during intro (line extends from center), 1→0 during exit (line shrinks to center)
     */
    private void drawFakeJudgeLine(float scale) {
        if (scale <= 0.001f) return;
        scale = MathUtils.clamp(scale, 0f, 1f);

        boolean isOfficial = (chart != null && chart.formatVersion > 0);
        float decorHalfLen = isOfficial ? stageW * 0.72f : stageW * GameConstants.LINEH_RPE;
        float halfLen = decorHalfLen * scale;
        float lineWidth = stageH * (isOfficial ? GameConstants.LINEW : GameConstants.LINEW_RPE);

        // Center of stage
        float cx = stageL + stageW * 0.5f;
        float cy = stageT + stageH * 0.5f;

        // AP/FC indicator color (fakeJudgeline.tint)
        float r, g, b;
        if (apfcIndicator) {
            int good = judgeCounts[1];
            int bad = judgeCounts[2];
            int miss = judgeCounts[3];

            if (bad + miss > 0) {
                r = 1f; g = 1f; b = 1f;
            } else if (good > 0) {
                r = GameConstants.FC_INDICATOR_COLOR[0];
                g = GameConstants.FC_INDICATOR_COLOR[1];
                b = GameConstants.FC_INDICATOR_COLOR[2];
            } else {
                r = GameConstants.AP_INDICATOR_COLOR[0];
                g = GameConstants.AP_INDICATOR_COLOR[1];
                b = GameConstants.AP_INDICATOR_COLOR[2];
            }
        } else {
            r = 1f; g = 1f; b = 1f;
        }

        float p0x = cx - halfLen;
        float p0y = cy;
        float p1x = cx + halfLen;
        float p1y = cy;
        drawLine(p0x, p0y, p1x, p1y, lineWidth, r, g, b, 1f);
    }

    private void drawJudgeLineVisual(@NonNull JudgeLine line,
                                     @NonNull JudgeLine.StateHolder st,
                                     float lineX, float lineY,
                                     float lineRot,
                                     float cosLine, float sinLine,
                                     float alpha,
                                     float introScale) {
        if (alpha <= 0f) return;
        if (!Float.isFinite(introScale)) introScale = 1f;
        introScale = MathUtils.clamp(introScale, 0f, 1f);

        // During intro/outro (introScale < 1), use a decorative line length (1.44x screen width)
        // so the extend/contract animation is visible over the full 1.2s duration.
        // When introScale == 1 (normal gameplay), use the chart's original length.
        boolean isDecorative = (introScale < 1f);
        // Decorative half-length: format-dependent (matches ).
        // Official: decorative (0.72 * stageW). RPE/PHIEDIT: width-based like normal lines.
        boolean isOfficial = (chart != null && chart.formatVersion > 0);
        float decorHalfLen = isOfficial ? stageW * 0.72f : stageW * GameConstants.LINEH_RPE;

        float r = st.colorR;
        float g = st.colorG;
        float b = st.colorB;

        boolean hasTexture = (line.texture != null && !line.texture.isEmpty());
        boolean hasText = (st.text != null && !st.text.isEmpty());

        // During intro/outro, always use AP/FC indicator color for plain lines
        // (no hits yet during intro; during outro we want the final indicator color)
        if (!st.hasColorEvent && !hasTexture && !hasText) {
            if (apfcIndicator) {
                int good = judgeCounts[1];
                int bad = judgeCounts[2];
                int miss = judgeCounts[3];

                if (bad + miss > 0) {
                    r = 1f;
                    g = 1f;
                    b = 1f;
                } else if (good > 0) {
                    r = GameConstants.FC_INDICATOR_COLOR[0];
                    g = GameConstants.FC_INDICATOR_COLOR[1];
                    b = GameConstants.FC_INDICATOR_COLOR[2];
                } else {
                    r = GameConstants.AP_INDICATOR_COLOR[0];
                    g = GameConstants.AP_INDICATOR_COLOR[1];
                    b = GameConstants.AP_INDICATOR_COLOR[2];
                }
            } else {
                r = 1f;
                g = 1f;
                b = 1f;
            }
        }

        // Determine line type (matches : text events take priority over texture).
        // In , lines with textEvents are always Text objects — texture is ignored.
        boolean lineHasTextEvents = (line.judgeLineTextEvents != null && !line.judgeLineTextEvents.isEmpty());

        // 1) Texture (only for lines WITHOUT text events)
        if (!lineHasTextEvents && line.texture != null && !line.texture.isEmpty()) {
            Texture t = getOrLoadFileTexture(line.texture);
            if (t != null && t.id != 0) {
                float sx = st.scaleX;
                float sy = st.scaleY;

                // Base scaling: RPE's authoring space is 1350x900.
                // Use independent X/Y bases (prpr style).
                float baseScaleX = Math.max(0.1f, stageW / 1350f);
                float baseScaleY = Math.max(0.1f, stageH / 900f);

                float texW, texH;
                int gifFrameTexId = 0;
                if (t.isGif && t.gifFrameCount > 0) {
                    int frameIdx;
                    if (Float.isFinite(st.gifPosition) && st.gifPosition >= 0f && st.gifPosition <= 1f) {
                        // Controlled by GIF event: pause at given progress
                        frameIdx = Math.round(st.gifPosition * (t.gifFrameCount - 1));
                        if (frameIdx < 0) frameIdx = 0;
                        if (frameIdx >= t.gifFrameCount) frameIdx = t.gifFrameCount - 1;
                    } else {
                        // Normal playback: non-uniform frame timing (matching  get_time_frame)
                        long totalMs = (long) (visualTimeSec * 1000.0);
                        frameIdx = t.getGifFrameIndex(totalMs);
                    }
                    gifFrameTexId = t.getGifFrameTexId(frameIdx);
                    texW = (t.gifFrameWidths != null && frameIdx < t.gifFrameWidths.length) ? t.gifFrameWidths[frameIdx] : t.width;
                    texH = (t.gifFrameHeights != null && frameIdx < t.gifFrameHeights.length) ? t.gifFrameHeights[frameIdx] : t.height;
                } else {
                    texW = t.width;
                    texH = t.height;
                }

                float w;
                float h;

                if (line.rpeTextureScaleInPixels) {
                    w = sx * baseScaleX;
                    h = sy * baseScaleX;
                } else {
                    if (line.useOfficialScale && texH > 0) {
                        float newSy = (1080f / texH) * Math.abs(sy);
                        float newSx = newSy * sx;
                        sx = newSx;
                        sy = newSy;
                    }
                    if (isDecorative) {
                        w = decorHalfLen * 2f * introScale;
                        h = texH * baseScaleX * sy;
                    } else {
                        w = texW * baseScaleX * sx;
                        h = texH * baseScaleX * sy;
                    }
                }

                // Draw with individual frame texture (style) or full texture
                if (gifFrameTexId != 0) {
                    drawGifFrame(gifFrameTexId, lineX, lineY, w, h, lineRot,
                            r, g, b, alpha,
                            st.anchorX, st.anchorY);
                } else {
                    drawTextureAnchored(t, lineX, lineY, w, h, lineRot,
                            r, g, b, alpha,
                            st.anchorX, st.anchorY);
                }
            }
            return;
        }

        // 2) Text (renders on lines WITH textEvents, or any line with active text)
        if (st.text != null && !st.text.isEmpty()) {
            Texture t = getOrCreateTextTexture(st.text);
            if (t != null && t.height > 0) {
                // Match : fontSize=50 in a 1350-wide design space,
                // scaled to screen by p(1) = stageW / 1350.
                float fontBaseSize = stageW * (50f / 1350f);

                // For multi-line text, scale so each line gets the full fontBaseSize height.
                String normalized = st.text.replace("\\n", "\n");
                int lineCount = 1;
                for (int i = 0; i < normalized.length(); i++) {
                    if (normalized.charAt(i) == '\n') lineCount++;
                }
                float targetHeight = fontBaseSize * lineCount;

                float scaleToTarget = targetHeight / (float) t.height;

                float w = (float) t.width * scaleToTarget * st.scaleX;
                float h = (float) t.height * scaleToTarget * st.scaleY;

                drawTextureAnchored(t, lineX, lineY, w, h, lineRot,
                        r, g, b, alpha,
                        st.anchorX, st.anchorY);
            }
            return;
        }

        // If this line has textEvents but current text is empty, show nothing
        // (matches : empty Text object renders nothing).
        if (lineHasTextEvents) return;

        // 3) Plain judge line
        float halfLen;
        float width;
        boolean isOfficialFmt = (chart != null && chart.formatVersion > 0);
        if (isDecorative) {
            // Intro/outro: decorative length extends from center to both sides
            halfLen = decorHalfLen * introScale;
            width = stageH * (isOfficialFmt ? GameConstants.LINEW : GameConstants.LINEW_RPE) * st.scaleY;
        } else {
            // Normal gameplay: use chart's original line length (format-dependent, matches )
            if (isOfficialFmt) {
                halfLen = stageH * GameConstants.LINEH * st.scaleX;
                width   = stageH * GameConstants.LINEW * st.scaleY;
            } else {
                halfLen = stageW * GameConstants.LINEH_RPE * st.scaleX;
                width   = stageH * GameConstants.LINEW_RPE * st.scaleY;
            }
        }
        float p0x = lineX + halfLen * cosLine;
        float p0y = lineY + halfLen * sinLine;
        float p1x = lineX - halfLen * cosLine;
        float p1y = lineY - halfLen * sinLine;
        drawLine(p0x, p0y, p1x, p1y, width, r, g, b, alpha);
    }

    private Texture getOrLoadFileTexture(@NonNull String path) {
        Texture cached = fileTextureCache.get(path);
        if (cached != null) {
            Texture valid = ensureTextureValid(cached);
            if (valid != null) return valid;
            // Recover failed, remove stale entry and reload
            fileTextureCache.remove(path);
        }
        boolean isGif = path.toLowerCase(java.util.Locale.ROOT).endsWith(".gif");
        Texture t;
        if (isGif) {
            t = loadGifTexture(path);
        } else {
            t = loadTextureFromFileSafe(path, true); // retain bitmap for storyboard textures
        }
        if (t != null) {
            t.filePath = path;
            fileTextureCache.put(path, t);
        }
        return t;
    }

    private Texture getOrCreateTextTexture(@NonNull String text) {
        Texture cached = textTextureCache.get(text);
        if (cached != null) {
            Texture valid = ensureTextureValid(cached);
            if (valid != null) return valid;
            textTextureCache.remove(text);
        }

        Paint p = getTextPaint();
        int sizePx = Math.max(16, Math.round(stageW * (50f / 1350f)));
        p.setTextSize(sizePx);

        // Handle newlines: normalize literal "\\n" to actual newline, then split and draw multi-line.
        String normalized = text.replace("\\n", "\n");
        if (normalized.contains("\n")) {
            String[] lines = normalized.split("\n", -1);
            Paint.FontMetrics fm = p.getFontMetrics();
            float lineHeight = fm.descent - fm.ascent + fm.leading;
            if (lineHeight <= 0) lineHeight = sizePx * 1.2f;

            // Measure max width
            float maxWidth = 0;
            for (String line : lines) {
                float lw = p.measureText(line);
                if (lw > maxWidth) maxWidth = lw;
            }
            int pad = Math.max(4, Math.round(sizePx * 0.25f));
            int w = Math.max(1, Math.round(maxWidth) + pad * 2);
            float totalTextH = lineHeight * lines.length;
            int h = Math.max(1, Math.round(totalTextH) + pad * 2);

            Bitmap bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888);
            Canvas canvas = new Canvas(bmp);
            canvas.drawARGB(0, 0, 0, 0);

            float y = pad - fm.ascent;
            for (String line : lines) {
                canvas.drawText(line, pad, y, p);
                y += lineHeight;
            }

            Texture tex = uploadBitmapAsTexture(bmp, true);
            if (tex != null) {
                tex.textContent = text;
                tex.textSizePx = sizePx;
                textTextureCache.put(text, tex);
            }
            return tex;
        }

        // Single-line text (fast path)
        p.getTextBounds(text, 0, text.length(), tmpTextBounds);
        int pad = Math.max(4, Math.round(sizePx * 0.25f));
        int w = Math.max(1, tmpTextBounds.width() + pad * 2);
        int h = Math.max(1, tmpTextBounds.height() + pad * 2);

        Bitmap bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888);
        Canvas canvas = new Canvas(bmp);
        canvas.drawARGB(0, 0, 0, 0);

        float x = pad - tmpTextBounds.left;
        float y = pad - tmpTextBounds.top;
        canvas.drawText(text, x, y, p);

        Texture tex = uploadBitmapAsTexture(bmp, true);
        if (tex != null) {
            tex.textContent = text;
            tex.textSizePx = sizePx;
            textTextureCache.put(text, tex);
        }
        return tex;
    }

    private Paint getTextPaint() {
        if (textPaint != null) return textPaint;
        Paint p = new Paint(Paint.ANTI_ALIAS_FLAG | Paint.SUBPIXEL_TEXT_FLAG);
        p.setColor(0xFFFFFFFF);
        
        // Try to load custom font
        Typeface tf = null;
        try {
            tf = Typeface.createFromAsset(context.getAssets(), "res/phigros.ttf");
        } catch (Exception e) {
            // Fallback
            tf = Typeface.DEFAULT;
        }
        p.setTypeface(tf);
        
        p.setTextAlign(Paint.Align.LEFT);
        textPaint = p;
        return p;
    }

    // -----------------------------
    // HUD (song / difficulty / score / combo) text rendering (GL)
    // -----------------------------

    private float dp(float dp) {
        return dp * density;
    }

    private static void deleteGlTexture(Texture t) {
        if (t == null) return;
        if (t.id != 0) {
            int[] ids = new int[]{t.id};
            GLES20.glDeleteTextures(1, ids, 0);
            t.id = 0;
        }
        // Release retained bitmap to free Java heap memory
        if (t.retainedBitmap != null) {
            t.retainedBitmap.recycle();
            t.retainedBitmap = null;
        }
        t.contextVersion = 0;
    }

    private void invalidateAllCachedTextures() {
        // Invalidate file-based textures
        for (Texture t : fileTextureCache.values()) {
            if (t != null) t.invalidateGl();
        }
        // Invalidate text-based textures (they'll be re-rendered)
        for (Texture t : textTextureCache.values()) {
            if (t != null) t.invalidateGl();
        }
        // Invalidate debug text cache
        for (Texture t : debugTextCache.values()) {
            if (t != null) t.invalidateGl();
        }
        // Invalidate HUD textures
        invalidateGl(hudTexSongTitle);
        invalidateGl(hudTexDifficulty);
        invalidateGl(hudTexScore);
        invalidateGl(hudTexComboNum);
        invalidateGl(hudTexComboLabel);
        invalidateGl(hudTexCount1);
        invalidateGl(hudTexCount2);
        invalidateGl(hudTexCount3);
        // Clear countdown texture size tracking to force re-render
        hudCountTextSizePx = -1f;
    }

    private static void invalidateGl(Texture t) {
        if (t != null) t.invalidateGl();
    }

    /**
     * Ensure a cached texture's GL ID is valid for the current GL context.
     * Re-uploads from retained bitmap or re-renders text as needed.
     */
    private Texture ensureTextureValid(Texture t) {
        if (t == null) return null;
        if (t.isValidForContext(glContextVersion)) return t;

        // Re-upload from retained bitmap if available (fast path)
        if (t.retainedBitmap != null) {
            int[] ids = new int[1];
            GLES20.glGenTextures(1, ids, 0);
            t.id = ids[0];
            t.contextVersion = glContextVersion;

            GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, t.id);
            GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_LINEAR);
            GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_LINEAR);
            GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_S, GLES20.GL_CLAMP_TO_EDGE);
            GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_T, GLES20.GL_CLAMP_TO_EDGE);
            android.opengl.GLUtils.texImage2D(GLES20.GL_TEXTURE_2D, 0, t.retainedBitmap, 0);
            GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, 0);
            return t;
        }

        // Re-load GIF textures from retained frame bitmaps (must be before file-path recovery,
        // since decoding a .gif file as a static bitmap would only give the first frame).
        if (t.isGif && t.gifFrameBitmaps != null && t.gifFrameTexIds == null) {
            t.gifFrameTexIds = new int[t.gifFrameCount];
            t.gifFrameWidths = new int[t.gifFrameCount];
            t.gifFrameHeights = new int[t.gifFrameCount];
            t.contextVersion = glContextVersion;

            int[] ids = new int[1];
            GLES20.glGenTextures(1, ids, 0);
            t.id = ids[0];
            Bitmap firstBmp = t.gifFrameBitmaps[0];
            t.width = firstBmp.getWidth();
            t.height = firstBmp.getHeight();
            GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, t.id);
            GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_LINEAR);
            GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_LINEAR);
            GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_S, GLES20.GL_CLAMP_TO_EDGE);
            GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_T, GLES20.GL_CLAMP_TO_EDGE);
            android.opengl.GLUtils.texImage2D(GLES20.GL_TEXTURE_2D, 0, firstBmp, 0);
            GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, 0);

            for (int i = 0; i < t.gifFrameCount; i++) {
                Bitmap bmp = t.gifFrameBitmaps[i];
                if (bmp == null || bmp.isRecycled()) continue;
                int[] frameIds = new int[1];
                GLES20.glGenTextures(1, frameIds, 0);
                t.gifFrameTexIds[i] = frameIds[0];
                t.gifFrameWidths[i] = bmp.getWidth();
                t.gifFrameHeights[i] = bmp.getHeight();
                GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, frameIds[0]);
                GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_LINEAR);
                GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_LINEAR);
                GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_S, GLES20.GL_CLAMP_TO_EDGE);
                GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_T, GLES20.GL_CLAMP_TO_EDGE);
                android.opengl.GLUtils.texImage2D(GLES20.GL_TEXTURE_2D, 0, bmp, 0);
            }
            GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, 0);
            return t;
        }

        // Re-load from file path if available (slower but doesn't need retained bitmap).
        // Skip for GIFs — they're handled above or need a full reload from file.
        if (!t.isGif && t.filePath != null) {
            try {
                Bitmap bmp = BitmapFactory.decodeFile(t.filePath);
                if (bmp != null) {
                    t.width = bmp.getWidth();
                    t.height = bmp.getHeight();
                    t.contextVersion = glContextVersion;

                    int[] ids = new int[1];
                    GLES20.glGenTextures(1, ids, 0);
                    t.id = ids[0];

                    GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, t.id);
                    GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_LINEAR);
                    GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_LINEAR);
                    GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_S, GLES20.GL_CLAMP_TO_EDGE);
                    GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_T, GLES20.GL_CLAMP_TO_EDGE);
                    android.opengl.GLUtils.texImage2D(GLES20.GL_TEXTURE_2D, 0, bmp, 0);
                    GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, 0);
                    bmp.recycle();
                    return t;
                }
            } catch (Exception ignored) {}
        }

        // Re-render text if textContent is available
        if (t.textContent != null && t.textSizePx > 0f) {
            Texture recreated = createHudTextTexture(t.textContent, t.textSizePx, false, false, false, false);
            if (recreated != null) {
                t.id = recreated.id;
                t.width = recreated.width;
                t.height = recreated.height;
                t.contextVersion = glContextVersion;
                return t;
            }
        }

        return null; // Texture cannot be recovered
    }

    private void clearHudTextTextures() {
        deleteGlTexture(hudTexSongTitle);
        deleteGlTexture(hudTexDifficulty);
        deleteGlTexture(hudTexScore);
        deleteGlTexture(hudTexComboNum);
        deleteGlTexture(hudTexComboLabel);

        hudTexSongTitle = null;
        hudTexDifficulty = null;
        hudTexScore = null;
        hudTexComboNum = null;
        hudTexComboLabel = null;

        hudSongTitleText = null;
        hudDifficultyText = null;
        hudScoreText = null;
        hudComboNumText = null;
        hudComboLabelText = null;
        hudSongTitleSourceText = null;
        hudDifficultySourceText = null;
        hudLastScoreValue = Integer.MIN_VALUE;
        hudLastComboValue = Integer.MIN_VALUE;

        hudLastLineScalePx = -1f;
    }

    private String ellipsizeToWidth(String text, float maxWidthPx, float textSizePx, boolean bold) {
        if (text == null) return "";
        if (maxWidthPx <= 1f) return text;

        hudTextPaint.setTextSize(textSizePx);
        hudTextPaint.setFakeBoldText(bold);
        if (hudTextPaint.measureText(text) <= maxWidthPx) return text;

        final String ell = "...";
        float ellW = hudTextPaint.measureText(ell);
        if (ellW >= maxWidthPx) return ell;

        int end = text.length();
        while (end > 0) {
            float w = hudTextPaint.measureText(text, 0, end);
            if (w + ellW <= maxWidthPx) break;
            end--;
        }
        if (end <= 0) return ell;
        return text.substring(0, end) + ell;
    }

    private Texture createHudTextTexture(String text, float textSizePx, boolean bold) {
        return createHudTextTexture(text, textSizePx, bold, true, false, false, false, false);
    }

    private Texture createHudTextTexture(String text, float textSizePx, boolean bold, boolean shadow, boolean trimHoriz, boolean trimVert) {
        return createHudTextTexture(text, textSizePx, bold, shadow, trimHoriz, trimVert, false, false);
    }

    private Texture createHudTextTexture(String text, float textSizePx, boolean bold, boolean shadow, boolean trimHoriz, boolean trimVert, boolean retainBitmap) {
        return createHudTextTexture(text, textSizePx, bold, shadow, trimHoriz, trimVert, retainBitmap, false);
    }

    private Texture createHudTextTexture(String text, float textSizePx, boolean bold, boolean shadow, boolean trimHoriz, boolean trimVert, boolean retainBitmap, boolean useFontTrueBottom) {
        if (text == null) text = "";
        if (text.isEmpty()) text = " ";

        hudTextPaint.setTextSize(textSizePx);
        hudTextPaint.setFakeBoldText(bold);
        hudTextPaint.setColor(0xFFFFFFFF);

        float shadowRadius = 0f;
        float shadowDy = 0f;
        if (shadow) {
            shadowRadius = Math.max(1f, textSizePx * 0.12f);
            shadowDy = Math.max(1f, textSizePx * 0.06f);
            hudTextPaint.setShadowLayer(shadowRadius, 0f, shadowDy, 0x66000000);
        } else {
            hudTextPaint.clearShadowLayer();
        }

        Rect bounds = new Rect();
        hudTextPaint.getTextBounds(text, 0, text.length(), bounds);

        Paint.FontMetrics fm = hudTextPaint.getFontMetrics();

        int w;
        if (trimHoriz) {
            w = Math.max(1, bounds.width());
            if (w <= 1) {
                w = Math.max(1, (int) Math.ceil(hudTextPaint.measureText(text)));
            }
        } else {
            w = Math.max(1, (int) Math.ceil(hudTextPaint.measureText(text)));
        }

        int h;
        int paddingBottom = 0;
        float effectiveBottom = useFontTrueBottom ? Math.max(fm.descent, fm.bottom) : fm.descent;
        if (trimVert) {
            h = Math.max(1, bounds.height());
        } else if (shadow) {
            float bottomSpace = Math.max(effectiveBottom, shadowDy + shadowRadius);
            h = Math.max(1, (int) Math.ceil(bottomSpace - fm.ascent));
            paddingBottom = (int) Math.ceil(h - (fm.descent - fm.ascent));
            if (paddingBottom < 0) paddingBottom = 0;
        } else {
            h = Math.max(1, (int) Math.ceil(effectiveBottom - fm.ascent));
            paddingBottom = (int) Math.ceil(effectiveBottom - fm.descent);
            if (paddingBottom < 0) paddingBottom = 0;
        }

        Bitmap bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888);
        Canvas canvas = new Canvas(bmp);
        canvas.drawARGB(0, 0, 0, 0);

        float x = trimHoriz ? -bounds.left : 0f;
        float y = trimVert ? -bounds.top : -fm.ascent;
        canvas.drawText(text, x, y, hudTextPaint);

        hudTextPaint.clearShadowLayer();

        Texture tex = uploadBitmapAsTexture(bmp, retainBitmap);
        if (tex != null) {
            tex.paddingBottom = paddingBottom;
            if (retainBitmap) {
                tex.textContent = text;
                tex.textSizePx = textSizePx;
            }
        }
        return tex;
    }

    private void updateHudTextTexturesIfNeeded() {
        // Same lineScale logic as PlayActivity#applyOverlayStageLayout (compatible).
        float lineScale = (stageW > stageH * 0.75f) ? (stageH / 18.75f) : (stageW / 14.0625f);
        if (lineScale <= 0f) return;

        // Recreate textures if scale changed (e.g., surface resize).
        if (hudLastLineScalePx <= 0f || Math.abs(lineScale - hudLastLineScalePx) > 0.5f) {
            clearHudTextTextures();
            hudLastLineScalePx = lineScale;
        }

        float bottomTextPx = (stageW + stageH) / 86.25f;
        float scorePx = (stageW + stageH) / 56.25f;
        // In fix mode, combo number uses larger size (size=1.0), legacy uses default.
        boolean useFixMode = (chart != null && chart.useAttachUiFix);
        float comboNumPx = MathUtils.clamp(lineScale * 1.3f, dp(22f), dp(62f));
        float comboLabelPx = MathUtils.clamp(lineScale * 0.4f, dp(8f), dp(21f));
        float difficultyPx = (stageW + stageH) / 86.25f;

        // Song title (bottom-left)
        String songSource = songName;
        if (hudTexSongTitle == null || hudSongTitleSourceText == null || !hudSongTitleSourceText.equals(songSource)) {
            float songMaxW = stageW * 0.50f;
            float actualSongSizePx = bottomTextPx;
            hudTextPaint.setTextSize(bottomTextPx);
            hudTextPaint.setFakeBoldText(false);
            float textW = hudTextPaint.measureText(songSource);
            if (textW > songMaxW) {
                float scale = songMaxW / textW;
                actualSongSizePx = bottomTextPx * scale;
            }
            deleteGlTexture(hudTexSongTitle);
            hudTexSongTitle = createHudTextTexture(songSource, actualSongSizePx, false, false, false, false, false, true);
            hudSongTitleText = songSource;
            hudSongTitleSourceText = songSource;
            hudLastSongTextSize = actualSongSizePx;
        }

        // Difficulty (bottom-right)
        if (hudTexDifficulty == null || hudDifficultySourceText == null || !hudDifficultySourceText.equals(difficulty)) {
            float diffMaxW = stageW * 0.35f;
            String diffText = ellipsizeToWidth(difficulty, diffMaxW, difficultyPx, false);
            deleteGlTexture(hudTexDifficulty);
            hudTexDifficulty = createHudTextTexture(diffText, difficultyPx, false, false, false, false, false, true);
            hudDifficultyText = diffText;
            hudDifficultySourceText = difficulty;
        }

        // Score (top-right)
        int scoreVal = getScore();
        if (hudTexScore == null || hudLastScoreValue != scoreVal) {
            String scoreStr = formatScore7(scoreVal);
            deleteGlTexture(hudTexScore);
            hudTexScore = createHudTextTexture(scoreStr, scorePx, false, true, false, false);
            hudScoreText = scoreStr;
            hudLastScoreValue = scoreVal;
        }

        // Combo (top-center, only when combo >= 3)
        if (combo >= 3) {
            if (hudTexComboNum == null || hudLastComboValue != combo) {
                String comboStr = String.valueOf(combo);
                deleteGlTexture(hudTexComboNum);
                hudTexComboNum = createHudTextTexture(comboStr, comboNumPx, false, false, true, false);
                hudComboNumText = comboStr;
                hudLastComboValue = combo;
            }
            // Combo label text can be driven by attachUI (UIElement::Combo) text events.
            String comboLabel = autoplay ? "AUTOPLAY" : "COMBO";
            if (chart != null) {
                double ct = getSmoothedPlayheadSeconds() - chart.offset - userOffsetSec;
                float sa = (stageH > 1e-6f) ? (stageW / stageH) : (16f / 9f);
                JudgeLine.StateHolder cst = getAttachUiState(UI_ELEMENT_COMBO, ct, sa);
                if (cst != null && cst.text != null && !cst.text.isEmpty()) comboLabel = cst.text;
            }
            if (hudTexComboLabel == null || hudComboLabelText == null || !hudComboLabelText.equals(comboLabel)) {
                deleteGlTexture(hudTexComboLabel);
                hudTexComboLabel = createHudTextTexture(comboLabel, comboLabelPx, false, true, true, true);
                hudComboLabelText = comboLabel;
            }
        } else {
            // Ensure it disappears like the original UI behaviour.
            deleteGlTexture(hudTexComboNum);
            deleteGlTexture(hudTexComboLabel);
            hudTexComboNum = null;
            hudTexComboLabel = null;
            hudComboNumText = null;
            hudLastComboValue = Integer.MIN_VALUE;
        }
    }

    private void drawHudTexts(float hudAlpha, float slideAmount, double chartTimeSec, float stageAspect, float lineScale) {
        updateHudTextTexturesIfNeeded();

        if (lineScale <= 0f) return;

        // Song title (bottom-left, phispler: x=w*0.0225, y=h*0.965, textBaseline=bottom, textAlign=left)
        if (hudTexSongTitle != null) {
            float x = stageL + stageW * 0.0225f;
            float y = stageT + stageH * 0.965f - (hudTexSongTitle.height - hudTexSongTitle.paddingBottom);
            applyUiTransformAndDraw(hudTexSongTitle,
                    x + hudTexSongTitle.width * 0.5f,
                    y + hudTexSongTitle.height * 0.5f,
                    hudTexSongTitle.width, hudTexSongTitle.height,
                    UI_ELEMENT_NAME, hudAlpha, slideAmount, chartTimeSec, stageAspect, lineScale);
        }

        // Difficulty (bottom-right, phispler: x=w*0.9775, y=h*0.965, textBaseline=bottom, textAlign=right)
        if (hudTexDifficulty != null) {
            float x = stageL + stageW * 0.9775f - hudTexDifficulty.width;
            float y = stageT + stageH * 0.965f - (hudTexDifficulty.height - hudTexDifficulty.paddingBottom);
            applyUiTransformAndDraw(hudTexDifficulty,
                    x + hudTexDifficulty.width * 0.5f,
                    y + hudTexDifficulty.height * 0.5f,
                    hudTexDifficulty.width, hudTexDifficulty.height,
                    UI_ELEMENT_LEVEL, hudAlpha, slideAmount, chartTimeSec, stageAspect, lineScale);
        }

        // Score (top-right, phispler: x=w*(1-40/1920), y=h*(31/1080), textBaseline=top, textAlign=right)
        if (hudTexScore != null) {
            float x = stageL + stageW * (1.0f - 40.0f / 1920.0f) - hudTexScore.width;
            float y = stageT + stageH * (31.0f / 1080.0f);
            applyUiTransformAndDraw(hudTexScore,
                    x + hudTexScore.width * 0.5f,
                    y + hudTexScore.height * 0.5f,
                    hudTexScore.width, hudTexScore.height,
                    UI_ELEMENT_SCORE, hudAlpha, slideAmount, chartTimeSec, stageAspect, lineScale);
        }

        // Combo
        if (combo >= 3 && hudTexComboNum != null && hudTexComboLabel != null) {
            float centerX = stageL + stageW * 0.5f;
            float topY = stageT + lineScale * 0.275f;
            float spacing = Math.max(0f, lineScale * 0.065f);
            float numCenterY = topY + hudTexComboNum.height * 0.5f;
            applyUiTransformAndDraw(hudTexComboNum,
                    centerX, numCenterY,
                    hudTexComboNum.width, hudTexComboNum.height,
                    UI_ELEMENT_COMBO_NUMBER, hudAlpha, slideAmount, chartTimeSec, stageAspect, lineScale);

            float labelCenterY = numCenterY + hudTexComboNum.height * 0.5f + spacing + hudTexComboLabel.height * 0.5f;
            applyUiTransformAndDraw(hudTexComboLabel,
                    centerX, labelCenterY,
                    hudTexComboLabel.width, hudTexComboLabel.height,
                    UI_ELEMENT_COMBO, hudAlpha, slideAmount, chartTimeSec, stageAspect, lineScale);
        }
    }

    /** Draws all in-game HUD elements (pause button, progress bar, score/combo texts). */
    // attachUI mapping (UIElement)
    private static final int UI_ELEMENT_PAUSE = 1;
    private static final int UI_ELEMENT_COMBO_NUMBER = 2;
    private static final int UI_ELEMENT_COMBO = 3;
    private static final int UI_ELEMENT_SCORE = 4;
    private static final int UI_ELEMENT_BAR = 5;
    private static final int UI_ELEMENT_NAME = 6;
    private static final int UI_ELEMENT_LEVEL = 7;

    private static float clamp01(float v) {
        return v < 0f ? 0f : (v > 1f ? 1f : v);
    }

    private static String formatScore7(int v) {
        int x = v;
        if (x < 0) x = 0;
        char[] buf = new char[7];
        for (int i = 6; i >= 0; i--) {
            int d = x % 10;
            buf[i] = (char) ('0' + d);
            x /= 10;
        }
        return new String(buf);
    }

    private JudgeLine.StateHolder getAttachUiState(int elementId, double chartTimeSec, float stageAspect) {
        if (chart == null || chart.attachUiLineIndex == null) return null;
        if (elementId <= 0 || elementId >= chart.attachUiLineIndex.length) return null;
        int idx = chart.attachUiLineIndex[elementId];
        if (idx < 0 || idx >= chart.judgeLineList.size()) return null;
        return chart.judgeLineList.get(idx).lastState; // Use cached state from render pass
    }

    private void applyUiTransformAndDraw(Texture tex,
                                         float baseCenterX, float baseCenterY,
                                         float baseW, float baseH,
                                         int elementId,
                                         float hudAlpha, float slideAmount,
                                         double chartTimeSec,
                                         float stageAspect,
                                         float lineScale) {
        applyUiTransformAndDrawUv(tex, baseCenterX, baseCenterY, baseW, baseH, elementId,
                hudAlpha, slideAmount, chartTimeSec, stageAspect, 0f, 0f, 1f, 1f, lineScale);
    }

    private void applyUiTransformAndDrawUv(Texture tex,
                                           float baseCenterX, float baseCenterY,
                                           float baseW, float baseH,
                                           int elementId,
                                           float hudAlpha, float slideAmount,
                                           double chartTimeSec,
                                           float stageAspect,
                                           float u0, float v0, float u1, float v1,
                                           float lineScale) {
        // Slide distance matches: lineScale * 1.75
        float slidePx = slideAmount * lineScale * 1.75f;
        boolean bottom = (elementId == UI_ELEMENT_NAME || elementId == UI_ELEMENT_LEVEL);
        float baseCx = baseCenterX;
        float baseCy = baseCenterY + (bottom ? slidePx : -slidePx);

        float x = baseCx;
        float y = baseCy;
        float w = baseW;
        float h = baseH;
        float rot = 0f;
        float a = hudAlpha;

        JudgeLine.StateHolder st = getAttachUiState(elementId, chartTimeSec, stageAspect);
        if (st != null) {
            // prpr attachUI semantics (Chart::with_element):
            // Matrix = Translation(line_pos) * Rotation(wrt rotation_point) * Scale(wrt scale_point)
            // - Translation uses the attached line position relative to the STAGE center (not full view).
            // - Rotation/Scale pivot points are element-specific and live in HUD coordinates.

            float sx = Float.isFinite(st.scaleX) ? st.scaleX : 1f;
            float sy = Float.isFinite(st.scaleY) ? st.scaleY : 1f;

            // Convert line normalized position (0..1) to pixel translation relative to stage center.
            float tx = (st.xNorm - 0.5f) * stageW;
            float ty = (st.yNorm - 0.5f) * stageH;

            float lineRot = Float.isFinite(st.rotateDeg) ? st.rotateDeg : 0f;
            // prpr uses -rotation (because its UI y-axis is up); our HUD y-axis is down.
            float rotDeg = -lineRot;

            // alpha (support 0..255 style alpha too)
            a *= normalizeAlpha01(st.alpha);
            if (a <= 0.001f) return;

            // Negative scale mirrors the texture by flipping UVs (same behavior as prpr).
            float absSx = Math.abs(sx);
            float absSy = Math.abs(sy);
            float uu0 = u0, uu1 = u1, vv0 = v0, vv1 = v1;
            if (sx < 0f) { float tmp = uu0; uu0 = uu1; uu1 = tmp; }
            if (sy < 0f) { float tmp = vv0; vv0 = vv1; vv1 = tmp; }

            // --- attachUI fix mode (use_attach_ui_fix) ---
            // In fix mode (legacy_aui = false), scale_point = rotation_point for most elements,
            // meaning scaling is centered at the same point as rotation.
            // In legacy mode, scale_point uses the element's center (different from rotation_point).
            boolean legacyAui = (chart != null && !chart.useAttachUiFix);

            // Rotation pivot: the element's anchor point in HUD coordinates.
            // This matches  rotation_point parameter passed to with_element.
            // For Score: (score_right, score_top), for Pause: (pause_center - offset), etc.
            // These are already baseCx/baseCy (the element center in our drawing).
            float rpx = baseCx;
            float rpy = baseCy;

            // Scale pivot:
            // - In fix mode: same as rotation_point ( scale_point = rotation_point when not specified)
            // - In legacy mode: element center for most, stage center for others (oldbehavior)
            float spx, spy;
            if (elementId == UI_ELEMENT_BAR) {
                // BAR: always scales from its left-center ( Some((-1., top + height / 2.)))
                spx = stageL;
                spy = baseCy;
            } else if (legacyAui) {
                // Legacy mode: scale pivot = element center (matching oldscale_point offset)
                spx = baseCx;
                spy = baseCy;
                // rotation_point is different from scale_point for Score/Name/Level
                // (e.g. Score rotation_point = (score_right, score_top) which is corner, not center).
                // Our current implementation already uses element center for rotation, which is close
                // enough for legacy mode since the element is already positioned there.
            } else {
                // Fix mode: scale_point = rotation_point (fix behavior)
                spx = rpx;
                spy = rpy;
            }

            // Apply Scale(wrt scale pivot) to the element center
            float px = baseCx;
            float py = baseCy;
            px = spx + (px - spx) * absSx;
            py = spy + (py - spy) * absSy;

            // Apply Rotation(wrt rotation pivot) to the center
            double rad = rotDeg * Math.PI / 180.0;
            float cos = (float) Math.cos(rad);
            float sin = (float) Math.sin(rad);
            float dx = px - rpx;
            float dy = py - rpy;
            float rx = rpx + dx * cos - dy * sin;
            float ry = rpy + dx * sin + dy * cos;

            // Finally apply Translation(line_pos)
            x = rx + tx;
            y = ry + ty;

            // Size scaling (element local size)
            float drawW = baseW * absSx;
            float drawH = baseH * absSy;

            // tint (line.color)
            float r = Float.isFinite(st.colorR) ? st.colorR : 1f;
            float g = Float.isFinite(st.colorG) ? st.colorG : 1f;
            float b = Float.isFinite(st.colorB) ? st.colorB : 1f;

            drawTextureAnchored(tex, x, y, drawW, drawH, rot + rotDeg,
                    r, g, b, a,
                    0.5f, 0.5f, uu0, vv0, uu1, vv1);
            return;
        }

        drawTextureAnchored(tex, x, y, w, h, rot, 1f, 1f, 1f, a,
                0.5f, 0.5f, u0, v0, u1, v1);
    }

    private void drawGameHudWithAnimations() {
        double musicPos = getSmoothedPlayheadSeconds();
        // Use cached frameIntroLineScale (computed in renderSceneDirect earlier this frame)
        float introLineScale = frameIntroLineScale;

        // Compute lineScale for slide distance (matches: Math.min(stageH/18.75, stageW/14.0625))
        float lineScale = (stageW > stageH * 0.75f) ? (stageH / 18.75f) : (stageW / 14.0625f);

        // --- HUD intro (easeOutSine slide from edges, matches: tween.easeOutSine(time * 1.5)) ---
        float hudIntro;
        if (frameIntroLinearT >= 1f) {
            hudIntro = 1f; // intro finished, HUD fully visible
        } else {
            // Multiplier 1.8 = 1.2 (intro duration) * 1.5 (ease multiplier)
            float hudT = Math.min(frameIntroLinearT * 1.8f, 1f);
            // easeOutSine: sin(t * π/2) — matches tween.easeOutSine
            hudIntro = (float) Math.sin(hudT * Math.PI / 2.0);
        }

        // --- HUD outro (: quartic ease-in, wall-clock based, 1500ms) ---
        float hudOutro = 0f;
        if (isInOutro && outroStartWallNs > 0L) {
            long elapsedNs = System.nanoTime() - outroStartWallNs;
            float elapsedSec = elapsedNs / 1_000_000_000f;
            float durSec = OUTRO_DUR_SEC;
            if (elapsedSec >= durSec) {
                hudOutro = 1f;
            } else if (elapsedSec > 0f) {
                float _progress = elapsedSec / durSec;
                // exit: progress = (1 - _progress)^4 → fades from 1→0
                // hudOutro is the "amount off-screen": 1 - progress = 1 - (1 - _progress)^4
                float progress = (float) Math.pow(1.0 - _progress, 4.0);
                hudOutro = 1f - progress;
            }
        }

        float hudAlpha = hudIntro * (1f - hudOutro);
        float slideAmount = (1f - hudIntro) + hudOutro;

        drawGameHud(hudAlpha, slideAmount, lineScale);
    }

    private void drawGameHudWithAnimationsClipped() {
        if (!isStageFullscreen()) {
            enableStageScissor();
            drawGameHudWithAnimations();
            disableStageScissor();
        } else {
            drawGameHudWithAnimations();
        }
    }

    /** Draws all in-game HUD elements (pause button, progress bar, texts) with attachUI + animations. */
    private void drawGameHud(float hudAlpha, float slideAmount, float lineScale) {
        final double chartTimeSec = (getSmoothedPlayheadSeconds() - chart.offset - userOffsetSec);
        final float stageAspect = (stageH > 1e-6f) ? (stageW / stageH) : (16f / 9f);

        // pause button (top-left of stage)
        if (texPause != null) {
            float cx = pauseRect.left + pauseRect.width() * 0.5f;
            float cy = pauseRect.top + pauseRect.height() * 0.5f;
            applyUiTransformAndDraw(texPause, cx, cy, pauseRect.width(), pauseRect.height(),
                    UI_ELEMENT_PAUSE, hudAlpha, slideAmount, chartTimeSec, stageAspect, lineScale);
        }

        // progress bar (top of stage)
        drawTimerLine(hudAlpha, slideAmount, chartTimeSec, stageAspect, lineScale);

        // texts
        drawHudTexts(hudAlpha, slideAmount, chartTimeSec, stageAspect, lineScale);
        
        // FPS counter (top-right corner, for debugging unlimited frame rate mode)
        if (showFps && currentFps > 0f) {
            drawFpsCounter(hudAlpha);
        }
    }
    
    private Texture hudTexFps;
    private String hudFpsText;
    private float hudLastFpsValue = -1f;
    
    private void drawFpsCounter(float alpha) {
        // Only update texture if FPS changed significantly
        if (hudTexFps == null || Math.abs(currentFps - hudLastFpsValue) > 1f) {
            String fpsStr = String.format("%.0f FPS", currentFps);
            deleteGlTexture(hudTexFps);
            float textPx = dp(12f); // Small text size
            hudTexFps = createHudTextTexture(fpsStr, textPx, false, false, false, false);
            hudFpsText = fpsStr;
            hudLastFpsValue = currentFps;
        }
        
        if (hudTexFps != null) {
            float margin = dp(8f);
            float x = stageL + (stageW - hudTexFps.width) / 2f;
            float y = stageT + stageH - margin - hudTexFps.height;
            
            // Semi-transparent background for readability
            drawSolidRect(x - dp(4f), y - dp(2f), 
                         hudTexFps.width + dp(8f), hudTexFps.height + dp(4f),
                         0f, 0f, 0f, 0.5f * alpha);
            
            drawTextureTopLeft(hudTexFps, x, y, (float) hudTexFps.width, (float) hudTexFps.height,
                              0f, 1f, 1f, 1f, alpha, 0f, 0f, 1f, 1f);
        }
    }

    private void drawTextureAnchored(Texture tex,
                                    float x, float y,
                                    float w, float h,
                                    float rotationDeg,
                                    float r, float g, float b, float a,
                                    float anchorX, float anchorY) {
        drawTextureAnchored(tex, x, y, w, h, rotationDeg, r, g, b, a, anchorX, anchorY, 0f, 0f, 1f, 1f);
    }

    private void drawTextureAnchored(Texture tex,
                                     float x, float y,
                                     float w, float h,
                                     float rotationDeg,
                                     float r, float g, float b, float a,
                                     float anchorX, float anchorY,
                                     float u0, float v0, float u1, float v1) {
        float ax = anchorX;
        float ay = anchorY;
        if (!Float.isFinite(ax)) ax = 0.5f;
        if (!Float.isFinite(ay)) ay = 0.5f;

        float dx = (ax - 0.5f) * w;
        float dy = (ay - 0.5f) * h;

        double rad = rotationDeg * Math.PI / 180.0;
        float cos = (float) Math.cos(rad);
        float sin = (float) Math.sin(rad);

        float ox = dx * cos - dy * sin;
        float oy = dx * sin + dy * cos;

        float cx = x - ox;
        float cy = y - oy;

        drawTextureCentered(tex, cx, cy, w, h, rotationDeg, r, g, b, a, u0, v0, u1, v1);
    }

    /** Draw a GIF frame using its raw OpenGL texture ID (per-frame texture). */
    private void drawGifFrame(int texId,
                              float x, float y,
                              float w, float h,
                              float rotationDeg,
                              float r, float g, float b, float a,
                              float anchorX, float anchorY) {
        float ax = Float.isFinite(anchorX) ? anchorX : 0.5f;
        float ay = Float.isFinite(anchorY) ? anchorY : 0.5f;

        float dx = (ax - 0.5f) * w;
        float dy = (ay - 0.5f) * h;

        double rad = rotationDeg * Math.PI / 180.0;
        float cos = (float) Math.cos(rad);
        float sin = (float) Math.sin(rad);

        float ox = dx * cos - dy * sin;
        float oy = dx * sin + dy * cos;

        float cx = x - ox;
        float cy = y - oy;

        GLES20.glUseProgram(progSprite);

        GLES20.glActiveTexture(GLES20.GL_TEXTURE0);
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, texId);
        GLES20.glUniform1i(locTex, 0);

        Matrix.setIdentityM(tmpModel, 0);
        Matrix.translateM(tmpModel, 0, cx, cy, 0f);
        Matrix.rotateM(tmpModel, 0, rotationDeg, 0f, 0f, 1f);
        Matrix.scaleM(tmpModel, 0, w, h, 1f);

        if (inStageSpace && mirrorX) {
            Matrix.multiplyMM(tmpWorld, 0, stagePreTransform, 0, tmpModel, 0);
            Matrix.multiplyMM(tmpMvp, 0, proj, 0, tmpWorld, 0);
        } else {
            Matrix.multiplyMM(tmpMvp, 0, proj, 0, tmpModel, 0);
        }
        GLES20.glUniformMatrix4fv(locMvp, 1, false, tmpMvp, 0);
        GLES20.glUniform4f(locColor, r, g, b, a);

        // Full-frame UV, no mirroring needed for GIF frames
        updateQuadUv(0f, 0f, 1f, 1f);

        GLES20.glEnableVertexAttribArray(locPos);
        GLES20.glVertexAttribPointer(locPos, 2, GLES20.GL_FLOAT, false, 0, quadPos);
        GLES20.glEnableVertexAttribArray(locUv);
        GLES20.glVertexAttribPointer(locUv, 2, GLES20.GL_FLOAT, false, 0, quadUv);

        GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4);

        GLES20.glDisableVertexAttribArray(locPos);
        GLES20.glDisableVertexAttribArray(locUv);
    }


    /** Render only the gameplay scene into FBO (used for menu blur) */
    private void renderSceneToFbo(FboTex target) {
        GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, target.fbo);
        GLES20.glViewport(0, 0, target.w, target.h);
        GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT);

        // Draw the scene using the original projection and view size.
        // The Viewport will handle the downscaling from logical coordinates to the FBO size.
        // This ensures all UI elements (especially those with fixed pixel sizes) maintain their relative size.

        // Set render scale for glScissor
        renderScaleX = (float) target.w / (float) viewW;
        renderScaleY = (float) target.h / (float) viewH;

        renderSceneDirect();
        // Include HUD so it remains visible (blurred/dimmed) behind the pause menu.
        drawGameHudWithAnimations();

        // Reset render scale
        renderScaleX = 1f;
        renderScaleY = 1f;

        GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, 0);
        GLES20.glViewport(0, 0, viewW, viewH);
    }

    private void blurPass(FboTex src, FboTex dst, float texelOffsetX, float texelOffsetY) {
        GLES20.glUseProgram(progBlur);
        GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, dst.fbo);
        GLES20.glViewport(0, 0, dst.w, dst.h);
        // blur pass 需要覆盖写入，避免混合产生残影
        GLES20.glDisable(GLES20.GL_BLEND);
        GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT);

        GLES20.glActiveTexture(GLES20.GL_TEXTURE0);
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, src.tex.id);
        GLES20.glUniform1i(locBTex, 0);
        GLES20.glUniform2f(locBTexelOffset, texelOffsetX, texelOffsetY);

        drawFullQuadWithCurrentProgram(locBPos, locBUv, 0f, 0f, 1f, 1f);

        GLES20.glEnable(GLES20.GL_BLEND);

        GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, 0);
        GLES20.glViewport(0, 0, viewW, viewH);
    }

    /**
     * GPU-based background blur using downscale + multi-pass Gaussian approximation.
     */
    private void createBackgroundBlur() {
        texBackgroundBlur = null;
        // 释放旧的 bg FBO
        if (bgBlurA != null) deleteFbo(bgBlurA);
        if (bgBlurB != null) deleteFbo(bgBlurB);
        bgBlurA = null;
        bgBlurB = null;

        if (texBackground == null || viewW <= 0 || viewH <= 0) {
            return;
        }

        // Downsample to reduce cost
                int bw = Math.max(128, viewW / 8);
        int bh = Math.max(128, viewH / 8);
        bgBlurA = new FboTex(bw, bh);
        bgBlurB = new FboTex(bw, bh);

        // render background into bgBlurA (bitmap texture)
        renderBackgroundToFbo(bgBlurA);

        // multiple blur iterations to approximate stronger blur radius
        // 6 iterations (12 passes) provides a good balance between visual quality and GPU cost.
        // This matches phispler's approach of using a reasonable blur radius rather than extreme iteration count.
                int iterations = 6;
        float offX = 2f / (float) bw;
        float offY = 2f / (float) bh;
        for (int i = 0; i < iterations; i++) {
            blurPass(bgBlurA, bgBlurB, offX, 0f);
            blurPass(bgBlurB, bgBlurA, 0f, offY);
        }

        texBackgroundBlur = bgBlurA.tex;
    }

    private void renderBackgroundToFbo(FboTex dst) {
        if (dst == null || texBackground == null) return;

        // Temporarily switch projection + virtual screen size to match dst
        int oldW = viewW;
        int oldH = viewH;
        float[] savedProj = proj.clone();

        viewW = dst.w;
        viewH = dst.h;
        // orthoM signature: (m, offset, left, right, bottom, top, near, far)
        // We use a top-left origin: y grows downward.
        Matrix.orthoM(proj, 0, 0f, (float) viewW, (float) viewH, 0f, -1f, 1f);

        GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, dst.fbo);
        GLES20.glViewport(0, 0, dst.w, dst.h);
        GLES20.glClearColor(0f, 0f, 0f, 1f);
        GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT);

        // Keep the same orientation as in normal gameplay rendering.
        drawFullscreenTextureBackground(texBackground, 1f, 1f, 1f, 1f);

        // restore
        GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, 0);
        viewW = oldW;
        viewH = oldH;
        System.arraycopy(savedProj, 0, proj, 0, 16);
        GLES20.glViewport(0, 0, oldW, oldH);
    }

    private void drawTimerLine(float hudAlpha, float slideAmount, double chartTimeSec, float stageAspect, float lineScale) {
        if (texTimerLine == null) return;
        // TimerLine scrolls from left to right.
        final float srcW = 960f;
        final float srcH = 5f;

        double musicPos = NativeAudioEngine.getPlayheadSeconds();
        float progress = (float) (musicPos / totalTimeSec);
        progress = MathUtils.clamp(progress, 0f, 1f);

        // Keep a minimum visible piece
        final float minPx = 2f;
        float visiblePx = minPx + progress * (srcW - minPx);
        float visibleRatio = MathUtils.clamp(visiblePx / srcW, 0f, 1f);

        // Progress bar spans the full stage width and starts at (stageL, stageT)
        float fullW = stageW;
        float barH = stageH / 95.0f;

        float visibleW = fullW * visibleRatio;
        float x = stageL;
        float y = stageT;

        // Draw right-aligned portion of the texture
        float u0 = 1f - visibleRatio;
        float u1 = 1f;

        float cx = x + visibleW * 0.5f;
        float cy = y + barH * 0.5f;

        applyUiTransformAndDrawUv(texTimerLine, cx, cy, visibleW, barH,
                UI_ELEMENT_BAR, hudAlpha, slideAmount, chartTimeSec, stageAspect,
                u0, 0f, u1, 1f, lineScale);
    }


    private void updateGameplay(double tChart) {
        frameChartTimeSec = tChart;
        framePlayTimeSec = getSmoothedPlayheadSeconds();
        if (autoplay) {
            updateAutoplay(tChart);
        } else {
            updateJudgeManualPhi(tChart);
        }
        startedTouchIds.clear();
    }

    private void updateAutoplay(double tChart) {
        List<Note> notes = chart.allNotesSorted;
        while (autoNoteIndex < notes.size()) {
            Note n = notes.get(autoNoteIndex);
            if (n.sect <= tChart) {
                if (n.judgeResult < 0) {
                    if (n.type == GameConstants.NOTE_HOLD) {
                        NativeAudioEngine.triggerSfx(GameConstants.NOTE_TAP);
                        n.holdActive = true;
                        n.holdPerfect = true;
                        n.holdPreJudge = false;
                        n.holdUpTimeSec = Double.POSITIVE_INFINITY;
                        n.holdDiffSec = 0.0;
                        n.safeFrame = HOLD_SAFE_FRAME_INIT;
                        JudgeLine ml = n.master;
                        double bpm0 = (ml != null && ml.bpm > 0) ? ml.bpm : 120.0;
                        double interval0 = (HOLD_PARTICLE_INTERVAL_BEATS * 60.0 / bpm0) / Math.max(0.001f, musicSpeed);
                        n.holdFxAtSec = tChart + interval0;
                        n.clicked = true;
                    } else {
                        commitJudgement(n, JR_PERFECT, tChart - n.sect);
                    }
                }
                autoNoteIndex++;
            } else {
                break;
            }
        }

        for (Note n : notes) {
            if (n == null) continue;
            if (n.type != GameConstants.NOTE_HOLD) continue;
            if (!n.holdActive) continue;
            if (n.judgeResult >= 0) continue;

            JudgeLine masterLine = n.master;
            double bpm = (masterLine != null && masterLine.bpm > 0) ? masterLine.bpm : 120.0;
            double interval = (HOLD_PARTICLE_INTERVAL_BEATS * 60.0 / bpm) / Math.max(0.001f, musicSpeed);

            if (!Double.isFinite(n.holdFxAtSec)) n.holdFxAtSec = tChart + interval;

            // Note: In autoplay mode, hold particle effects are already covered by
            // chart.clickEffectsSorted (pre-generated in Chart.initSort with the same
            // 30/BPM interval). drawClickEffects() renders them at Line 2450.
            // We must NOT spawn duplicate HitEffects here, otherwise drawHitEffects()
            // at Line 2452 would render a second set of effects, making visuals too "thick".
            while (tChart >= n.holdFxAtSec && n.holdFxAtSec <= n.holdEndTime) {
                n.holdFxAtSec += interval;
            }

            if (tChart >= n.holdEndTime) {
                n.holdActive = false;
                n.holdPreJudge = false;
                commitJudgement(n, JR_PERFECT, 0.0);
            }
        }
    }

    /**
     * Manual judgment with CheckNote protection. Execution order:
     * 1. Generate judge events from touches
     * 2. CheckNote: per new touch, select single best candidate
     * 3. Drag judgment (continuous touch matching)
     * 4. Flick judgment (gesture matching + nearNotes)
     * 5. Tap/Hold settlement
     * 6. Hold particles
     * 7. Hold tail finalization
     * 8. Miss detection
     */
    private void updateJudgeManualPhi(double tChart) {
        if (chart == null || chart.allNotesSorted == null) return;
        if (stageW <= 1f || stageH <= 1f) return;

        // Phigros: _timeSum = average recent frame time * 0.5 (challenge mode dynamic adjustment)
        // Updated once per frame to smooth over frame time variance
        long nowNs = System.nanoTime();
        if (timeSumLastFrameNs > 0L) {
            double dt = (nowNs - timeSumLastFrameNs) * 1e-9;
            if (dt > 0.1) dt = 0.1;
            timeSumBuffer[timeSumBufferIndex] = dt;
            timeSumBufferIndex = (timeSumBufferIndex + 1) % TIME_SUM_FRAMES;
            if (timeSumBufferCount < TIME_SUM_FRAMES) timeSumBufferCount++;
        }
        timeSumLastFrameNs = nowNs;

        if (timeSumBufferCount > 0) {
            double sum = 0.0;
            for (int i = 0; i < timeSumBufferCount; i++) sum += timeSumBuffer[i];
            challengeTimeSum = (sum / timeSumBufferCount) * 0.5;
        }

        double limitPerfect = getPerfectWindowSec();
        double limitGood = getGoodWindowSec();
        double limitBad = getBadWindowSec();
        double spd = Math.max(0.001f, musicSpeed);

        List<Note> notes = chart.allNotesSorted;
        int nSize = notes.size();
        if (nSize <= 0) return;

        float stageAspect = (stageH > 1e-6f) ? (stageW / stageH) : (16f / 9f);
        double horizon = tChart + 1.5;
        // ---- Phase 1: Generate judge events from active touches ----
        // generates JudgeEvents for each touch:
        // isTapped (first frame) → type 1 (Click)
        // isActive (held) → type 2 (Hold/Drag continuous)
        // flicking && !flicked → type 3 (Flick/Move)

        class JudgeEvent {
            float offsetX; float offsetY;
            int type;          // 1=Click, 2=Hold/Drag, 3=Flick/Move
            boolean judged;
            boolean preventBad;
            FlickTracker event; // Flick tracker reference (type 3 only)
            int touchId;

            JudgeEvent(float ox, float oy, int type, FlickTracker evt, int tid) {
                this.offsetX = ox; this.offsetY = oy; this.type = type;
                this.judged = false; this.preventBad = false;
                this.event = evt; this.touchId = tid;
            }
        }

        ArrayList<JudgeEvent> judgeList = new ArrayList<>();
        for (Map.Entry<Integer, TouchState> ent : activeTouches.entrySet()) {
            int id = ent.getKey();
            TouchState ts = ent.getValue();
            boolean isTapped = startedTouchIds.contains(id);
            FlickTracker tr = flickTrackers.get(id);

            if (isTapped) {
                judgeList.add(new JudgeEvent(ts.x, ts.y, 1, null, id));
            }
            // type 2: continuous touch (always present if touch is active)
            judgeList.add(new JudgeEvent(ts.x, ts.y, 2, null, id));
            // type 3: flick gesture (flicking && !flicked)
            if (tr != null && tr.flicking && !tr.flicked) {
                judgeList.add(new JudgeEvent(ts.x, ts.y, 3, tr, id));
            }
        }

        // ---- Phase 2: CheckNote — unified single-candidate selection per new touch ----
        // Phigros: CheckNote runs once per new touch (phase==0), scans all note types,
        // selects the SINGLE best candidate based on time proximity + type priority.
        // Sets note.isJudged=1 for the winner; loser notes wait (protection mechanism).
        // Scan window: [nowTime - goodTimeRange, nowTime + badTimeRange] = [-0.18, +0.22]
        // Type priority (±0.01s): Tap/Hold > Drag/Flick
        java.util.Map<Integer, Note> checkNoteSelections = new java.util.HashMap<>();
        try {
        for (JudgeEvent je : judgeList) {
            if (je.type != 1) continue; // Only new touches
            if (je.judged) continue;

            Note bestNote = null;
            double bestAbsTimeDiff = Double.MAX_VALUE;
            float bestSpatialDist = Float.MAX_VALUE;
            int bestTypePriority = 0; // 2 = Tap/Hold, 1 = Drag/Flick

            for (int i = judgeCursor; i < nSize; i++) {
                Note note = notes.get(i);
                if (note == null || note.isFake || note.judgeResult >= 0) continue;
                if (note.isJudged) continue; // Already assigned to another finger (multi-touch fix)
                if (note.holdActive) continue;
                if (note.sect > horizon) break;
                // CheckNote for type 1 (click) events only selects Tap/Hold;
                // Drag/Flick use their own per-frame continuous/flick matching
                if (note.type != GameConstants.NOTE_TAP && note.type != GameConstants.NOTE_HOLD) continue;

                double deltaTime = note.sect - tChart;

                // Scan window: [nowTime - goodTimeRange, nowTime + badTimeRange]
                // deltaTime = note.sect - tChart: [-0.18, +0.22]
                // 早限 -0.18s (goodTimeRange), 晚限 +0.22s (badTimeRange)
                if (deltaTime < -limitGood) continue; // Too far before now (too early)
                if (deltaTime > limitBad) continue;   // Too far past now (too late)

                // Spatial check
                float touchPos = getPhigrosTouchPos(je.offsetX, je.offsetY, note, tChart, stageAspect);
                if (touchPos >= 1.9f) continue;

                // Spatial-distance-reduced late window (Phigros CheckNote: note.realTime-nowTime <= _badTime)
                double spatialWindow = getSpatialJudgeWindowSec(note, touchPos);
                if (deltaTime > spatialWindow) continue; // Note is too far past now (late)

                double absDt = Math.abs(deltaTime);
                int typePriority = (note.type == GameConstants.NOTE_TAP || note.type == GameConstants.NOTE_HOLD) ? 2 : 1;

                // Select best candidate
                boolean isBetter = false;
                if (bestNote == null) {
                    isBetter = true;
                } else if (absDt < bestAbsTimeDiff - 0.009) {
                    // Significantly closer in time (>0.01 ahead → clear win)
                    isBetter = true;
                } else if (Math.abs(absDt - bestAbsTimeDiff) <= 0.01) {
                    // Within ±0.01s: type priority first, then spatial distance
                    if (typePriority > bestTypePriority) {
                        isBetter = true;
                    } else if (typePriority == bestTypePriority && touchPos < bestSpatialDist) {
                        isBetter = true;
                    }
                }

                if (isBetter) {
                    bestNote = note;
                    bestAbsTimeDiff = absDt;
                    bestSpatialDist = touchPos;
                    bestTypePriority = typePriority;
                }
            }

            if (bestNote != null) {
                bestNote.isJudged = true;
                checkNoteSelections.put(je.touchId, bestNote);
                je.judged = true; // Consume this touch
            }
        }
        } catch (Throwable t) { /* prevent crash */ }

        // Consume all type-1 touch events from startedTouchIds after Phase 2.
        // Phigros: a touch-down generates only one click opportunity per finger.
        // Without this, held/sliding fingers keep creating type-1 events every frame,
        // which can cause accidental Bad judgments on notes far from the judgment line.
        for (JudgeEvent je : judgeList) {
            if (je.type == 1) {
                startedTouchIds.remove(je.touchId);
            }
        }

        // Build reverse map: Note → JudgeEvent (for preventBad lookup in Phase 5)
        // Maps each CheckNote-selected note back to the type-1 judge event that selected it
        java.util.Map<Note, JudgeEvent> noteToJudgeEvent = new java.util.HashMap<>();
        for (JudgeEvent je : judgeList) {
            if (je.type != 1) continue;
            Note selNote = checkNoteSelections.get(je.touchId);
            if (selNote != null) {
                noteToJudgeEvent.put(selNote, je);
            }
        }

        // ---- Phase 3: Drag judgment ----
        // Phigros: DragControl — abs(fingerX - noteX) < 2.1 sets isJudged PER FRAME
        // v5 < 0.005 && isJudged → Perfect (deltaTime near zero AND finger is in range)
        // v5 < -0.1 && !isJudged → Miss (late > 0.1s AND no finger in range)
        // No Good/Bad branches
        try {
        for (int i = judgeCursor; i < nSize; i++) {
            Note note = notes.get(i);
            if (note == null || note.isFake || note.judgeResult >= 0) continue;
            if (note.holdActive) continue;
            if (note.sect > horizon) break;
            if (note.type != GameConstants.NOTE_DRAG) continue;

            double deltaTime = note.sect - tChart;
            // Phigros: early window = badTimeRange, late Miss at 0.1s
            if (deltaTime > limitBad) continue;

            // Per-frame: reset PreJudge, recalculate based on current frame's touches
            note.preJudge = false;

            // Drag preventBad (style Drag protection):
            // When a Drag hasn't reached the judgment line yet (deltaTime > 0),
            // mark nearby type-1 (click) judge events as preventBad.
            // This prevents those taps from causing Bad judgments on Tap/Hold notes,
            // because Drag only has Perfect/Miss — the tap is "absorbed" by the Drag.
            if (deltaTime > 0) {
                for (JudgeEvent je : judgeList) {
                    if (je.type != 1) continue;
                    float touchPos = getPhigrosTouchPos(je.offsetX, je.offsetY, note, tChart, stageAspect);
                    if (touchPos < 2.1f) {
                        je.preventBad = true;
                    }
                }
            }

            // Drag isJudged: any continuous touch (type 2) within Phigros range (touchPos < 2.1)
            // Phigros: DragControl matching only within |delta| <= 0.1
            double absDragDelta = Math.abs(deltaTime);
            if (absDragDelta > 0.1) {
                note.preJudge = false;
            } else {
                for (JudgeEvent je : judgeList) {
                    if (je.type != 2) continue;
                    float touchPos = getPhigrosTouchPos(je.offsetX, je.offsetY, note, tChart, stageAspect);
                    if (touchPos < 2.1f) {
                        note.preJudge = true;
                        break;
                    }
                }
            }

            // Phigros: v5 < 0.005 && isJudged → confirm Perfect immediately
            if (note.preJudge && deltaTime < 0.005) {
                note.preJudge = false;
                NativeAudioEngine.triggerSfx(GameConstants.NOTE_DRAG);
                spawnHitEffect(note, tChart, GameConstants.PCOLOR[0], GameConstants.PCOLOR[1], GameConstants.PCOLOR[2], GameConstants.PALPHA, 4);
                commitJudgement(note, JR_PERFECT, 0.0);
            }
            // Late Miss: v5 < -0.1 && !isJudged → Miss
            if (!note.preJudge && deltaTime < -DRAG_MISS_THRESHOLD) {
                commitJudgement(note, JR_MISS, 0.25);
            }
        }
        } catch (Throwable t) { /* prevent crash */ }

        // ---- Phase 4: Flick judgment ----
        // Phigros: CheckFlick runs in ±perfect*1.75 window, matching flick gesture to Flick notes
        // touchPos < 2.1 → isJudgedForFlick = 1
        // FlickControl: v5 < 0.005 && isJudgedForFlick → Perfect
        // late > perfect*1.75 → Miss
        // No Good/Bad branches
        double flickMissLimit = getPerfectWindowSec() * 1.75;
        try {
        for (int i = judgeCursor; i < nSize; i++) {
            Note note = notes.get(i);
            if (note == null || note.isFake || note.judgeResult >= 0) continue;
            if (note.holdActive) continue;
            if (note.sect > horizon) break;
            if (note.type != GameConstants.NOTE_FLICK) continue;

            double deltaTime = note.sect - tChart;

            // Phigros CheckFlick window: [nowTime - perfect*1.75, nowTime + perfect*1.75]
            // Late expired Flick → Miss
            if (deltaTime < -flickMissLimit) {
                commitJudgement(note, JR_MISS, 0.25);
                continue;
            }
            // Too far in future, skip
            if (deltaTime > flickMissLimit) continue;

            // Flick preventBad (style Flick protection):
            // When a Flick hasn't reached the judgment line (deltaTime > 0) or
            // hasn't been pre-judged yet (!preJudge), mark nearby type-1 (click)
            // judge events as preventBad to prevent Bad on Tap/Hold notes.
            if (deltaTime > 0 || !note.preJudge) {
                for (JudgeEvent je : judgeList) {
                    if (je.type != 1) continue;
                    float touchPos = getPhigrosTouchPos(je.offsetX, je.offsetY, note, tChart, stageAspect);
                    if (touchPos < 2.1f) {
                        je.preventBad = true;
                    }
                }
            }

            // Flick PreJudge: type 3 (active flick gesture) within Phigros range (touchPos < 2.1)
            if (!note.preJudge) {
                for (JudgeEvent je : judgeList) {
                    if (je.type != 3) continue;
                    if (je.judged) continue;
                    float touchPos = getPhigrosTouchPos(je.offsetX, je.offsetY, note, tChart, stageAspect);
                    if (touchPos >= 2.1f) continue;

                    float distance = touchPos;
                    Note noteJudge = note;
                    boolean nearcomp = false;

                    for (Note nearNote : note.nearNotes) {
                        if (nearNote.judgeResult >= 0) continue;
                        if (nearNote.preJudge) continue;
                        if (nearNote.sect - tChart > limitGood) break;
                        float nearTouchPos = getPhigrosTouchPos(je.offsetX, je.offsetY, nearNote, tChart, stageAspect);
                        if (nearTouchPos >= 2.1f) continue;
                        if (nearTouchPos < distance) {
                            distance = nearTouchPos;
                            noteJudge = nearNote;
                            nearcomp = true;
                        }
                    }

                    if (je.event == null) {
                        noteJudge.preJudge = true;
                        if (!nearcomp) break;
                    } else if (!je.event.flicked) {
                        noteJudge.preJudge = true;
                        je.event.flicked = true;
                        if (!nearcomp) break;
                    }
                }
            }

            // Flick fallback: type 2 (held finger) that has flicked before can also
            // pre-judge flick notes. This catches flicks where the flick gesture ended
            // slightly before the note reached the judgment line.
            if (!note.preJudge) {
                for (JudgeEvent je : judgeList) {
                    if (je.type != 2) continue;
                    FlickTracker tr = flickTrackers.get(je.touchId);
                    if (tr == null || !tr.hasFlicked || tr.flicked) continue;
                    float touchPos = getPhigrosTouchPos(je.offsetX, je.offsetY, note, tChart, stageAspect);
                    if (touchPos >= 2.1f) continue;

                    float distance = touchPos;
                    Note noteJudge = note;
                    boolean nearcomp = false;

                    for (Note nearNote : note.nearNotes) {
                        if (nearNote.judgeResult >= 0) continue;
                        if (nearNote.preJudge) continue;
                        if (nearNote.sect - tChart > limitGood) break;
                        float nearTouchPos = getPhigrosTouchPos(je.offsetX, je.offsetY, nearNote, tChart, stageAspect);
                        if (nearTouchPos >= 2.1f) continue;
                        if (nearTouchPos < distance) {
                            distance = nearTouchPos;
                            noteJudge = nearNote;
                            nearcomp = true;
                        }
                    }

                    noteJudge.preJudge = true;
                    tr.flicked = true;
                    if (!nearcomp) break;
                }
            }

            // Phigros FlickControl: v5 < 0.005 && isJudgedForFlick → confirm Perfect
            if (note.preJudge && deltaTime < 0.005) {
                note.preJudge = false;
                NativeAudioEngine.triggerSfx(GameConstants.NOTE_FLICK);
                spawnHitEffect(note, tChart, GameConstants.PCOLOR[0], GameConstants.PCOLOR[1], GameConstants.PCOLOR[2], GameConstants.PALPHA, 4);
                commitJudgement(note, JR_PERFECT, 0.0);
            }
        }
        } catch (Throwable t) { /* prevent crash */ }

        // ---- Phase 5: Tap/Hold settlement (ClickControl) ----
        // Phigros: ClickControl — checks note.isJudged from CheckNote (Phase 2)
        // isJudged=true: judge based on abs(|delta|)
        //   < perfectTimeRange → Perfect
        //   < goodTimeRange → Good
        //   < badTimeRange && type==Tap → Bad
        //   Hold with |delta| >= goodTimeRange → revoke isJudged, wait
        // isJudged=false:
        //   delta >= -goodTimeRange → wait (protection mechanism)
        //   delta < -goodTimeRange → Miss
        try {
        for (int i = judgeCursor; i < nSize; i++) {
            Note note = notes.get(i);
            if (note == null || note.isFake || note.judgeResult >= 0) continue;
            if (note.sect > horizon) break;
            if (note.type != GameConstants.NOTE_TAP && note.type != GameConstants.NOTE_HOLD) continue;

            double deltaTime = note.sect - tChart;

            // Hold body check (head already activated, holdActive=true)
            if (note.type == GameConstants.NOTE_HOLD && note.holdActive) {
                // Phigros: HoldControl Phase 2 — safeFrame tolerance mechanism
                // Default assume no finger is holding this frame
                note.holdBroken = true;

                // Check ALL active fingers — Phigros: abs(fingerX - noteX) < 1.9
                for (JudgeEvent je : judgeList) {
                    if (je.type != 2) continue;
                    float touchPos = getPhigrosTouchPos(je.offsetX, je.offsetY, note, tChart, stageAspect);
                    if (touchPos < 1.9f) {
                        note.holdBroken = false;
                        note.safeFrame = HOLD_SAFE_FRAME_INIT; // Reset safe frame counter
                        break;
                    }
                }

                // Safe frame tolerance check (Phigros: _safeFrame from 2, check < 0, so 3 frames of grace)
                if (note.holdBroken) {
                    int sf = note.safeFrame;
                    if (sf < 0) {
                        // Safe frame exhausted → hard Miss
                        note.holdActive = false;
                        note.clicked = false;
                        commitJudgement(note, JR_MISS, 0.25);
                    } else {
                        note.safeFrame = sf - 1; // Consume one frame of tolerance
                        note.holdBroken = false; // Forgive this frame
                    }
                }
                continue;
            }

            // Phigros: ClickControl settlement based on note.isJudged from CheckNote
            if (note.isJudged) {
                double absDt = Math.abs(deltaTime);
                if (absDt < limitPerfect) {
                    // Perfect
                    NativeAudioEngine.triggerSfx(GameConstants.NOTE_TAP);
                    if (note.type == GameConstants.NOTE_TAP) {
                        commitJudgement(note, JR_PERFECT, deltaTime);
                    } else {
                        // Hold head: activate hold
                        note.holdActive = true;
                        note.holdPerfect = true;
                        note.holdPreJudge = false;
                        note.holdUpTimeSec = Double.POSITIVE_INFINITY;
                        note.holdDiffSec = deltaTime;
                        note.safeFrame = HOLD_SAFE_FRAME_INIT;
                        JudgeLine masterLine0 = note.master;
                        double bpm0 = (masterLine0 != null && masterLine0.bpm > 0) ? masterLine0.bpm : 120.0;
                        double interval0 = (HOLD_PARTICLE_INTERVAL_BEATS * 60.0 / bpm0) / Math.max(0.001f, musicSpeed);
                        note.holdFxAtSec = tChart + interval0;
                        note.clicked = true;
                        note.holdTapTimeMs = System.nanoTime() / 1_000_000L;
                        note.holdBroken = false;
                        spawnHoldHeadHitEffect(note, tChart, GameConstants.PCOLOR[0], GameConstants.PCOLOR[1], GameConstants.PCOLOR[2], GameConstants.PALPHA, 4);
                    }
                    note.statOffset = deltaTime;
                } else if (absDt < limitGood) {
                    // Good
                    NativeAudioEngine.triggerSfx(GameConstants.NOTE_TAP);
                    if (note.type == GameConstants.NOTE_TAP) {
                        commitJudgement(note, JR_GOOD, deltaTime);
                    } else {
                        // Hold head: Good → holdPerfect = false
                        note.holdActive = true;
                        note.holdPerfect = false;
                        note.holdPreJudge = false;
                        note.holdUpTimeSec = Double.POSITIVE_INFINITY;
                        note.holdDiffSec = deltaTime;
                        note.safeFrame = HOLD_SAFE_FRAME_INIT;
                        JudgeLine masterLine0 = note.master;
                        double bpm0 = (masterLine0 != null && masterLine0.bpm > 0) ? masterLine0.bpm : 120.0;
                        double interval0 = (HOLD_PARTICLE_INTERVAL_BEATS * 60.0 / bpm0) / Math.max(0.001f, musicSpeed);
                        note.holdFxAtSec = tChart + interval0;
                        note.clicked = true;
                        note.holdTapTimeMs = System.nanoTime() / 1_000_000L;
                        note.holdBroken = false;
                        spawnHoldHeadHitEffect(note, tChart, GameConstants.GCOLOR[0], GameConstants.GCOLOR[1], GameConstants.GCOLOR[2], GameConstants.GALPHA, 3);
                    }
                    note.statOffset = deltaTime;
                } else if (note.type != GameConstants.NOTE_HOLD && absDt < limitBad) {
                    // Bad — Tap only; Hold has no Bad judgment (no hit SFX on Bad)
                    // Drag/Flick preventBad: if the tap that selected this note was
                    // "absorbed" by an approaching Drag/Flick, skip Bad and revoke
                    // isJudged so the note waits for a better touch.
                    JudgeEvent selectingEvent = noteToJudgeEvent.get(note);
                    if (selectingEvent != null && selectingEvent.preventBad && deltaTime > 0) {
                        // Early tap absorbed by nearby Drag/Flick — revoke selection
                        note.isJudged = false;
                    } else {
                        commitJudgement(note, JR_BAD, deltaTime);
                        note.badTimeMs = System.nanoTime() / 1_000_000L;
                        note.statOffset = deltaTime;
                    }
                } else if (note.type == GameConstants.NOTE_HOLD) {
                    // Hold: |delta| >= goodTimeRange → revoke activation, wait for a better touch
                    note.isJudged = false;
                }
            }

            // Not isJudged: wait if within grace, Miss if too late
            if (!note.isJudged && !note.holdActive && note.judgeResult < 0) {
                // Phigros: ClickControl — delta < -goodTimeRange → Miss
                // (note more than goodTimeRange past due without activation)
                if (deltaTime < -limitGood) {
                    commitJudgement(note, JR_MISS, 0.25);
                }
            }
        }
        } catch (Throwable t) { /* prevent crash */ }

        // ---- Phase 6: Hold particles and tail pre-judge ----
        // Note: Hold body check (holdBroken) is now in Phase 5 (ClickControl)
        try {
        for (int i = 0; i < nSize; i++) {
            Note n = notes.get(i);
            if (n == null || !n.holdActive || n.judgeResult >= 0) continue;

            if (!n.clicked && tChart >= n.sect) n.clicked = true;

            JudgeLine masterLine = n.master;
            double bpm = (masterLine != null && masterLine.bpm > 0) ? masterLine.bpm : 120.0;
            double interval = (HOLD_PARTICLE_INTERVAL_BEATS * 60.0 / bpm) / Math.max(0.001f, musicSpeed);

            if (!Double.isFinite(n.holdFxAtSec)) n.holdFxAtSec = tChart + interval;

            while (tChart >= n.holdFxAtSec) {
                if (n.holdFxAtSec <= n.holdEndTime) {
                    if (n.holdPerfect) {
                        spawnHitEffect(n, n.holdFxAtSec, GameConstants.PCOLOR[0], GameConstants.PCOLOR[1], GameConstants.PCOLOR[2], GameConstants.PALPHA, 4);
                    } else {
                        spawnHitEffect(n, n.holdFxAtSec, GameConstants.GCOLOR[0], GameConstants.GCOLOR[1], GameConstants.GCOLOR[2], GameConstants.GALPHA, 3);
                    }
                }
                n.holdFxAtSec += interval;
            }

            // Hold tail pre-judge
            if ((n.holdEndTime - tChart) / spd <= limitBad) {
                n.holdPreJudge = true;
            }
        }
        } catch (Throwable t) { /* prevent crash */ }

        // ---- Phase 7: Hold tail finalization ----
        // Phigros: settles early at realTime + holdTime - 0.22
        try {
        for (int i = 0; i < nSize; i++) {
            Note n = notes.get(i);
            if (n == null || !n.holdActive || !n.holdPreJudge || n.judgeResult >= 0) continue;
            double earlySettleTime = n.holdEndTime - HOLD_TAIL_EARLY_SETTLE;
            if (tChart >= earlySettleTime) {
                int jr = n.holdPerfect ? JR_PERFECT : JR_GOOD;
                n.holdActive = false;
                n.holdPreJudge = false;
                commitJudgement(n, jr, n.holdDiffSec);
            }
        }
        } catch (Throwable t) { /* prevent crash */ }

        // ---- Phase 8: Miss detection ----
        // Phigros: per-type Miss thresholds (fixed in seconds, no speed scaling)
        try {
        while (judgeCursor < nSize) {
            Note n = notes.get(judgeCursor);
            if (n == null) { judgeCursor++; continue; }
            if (n.isFake) { judgeCursor++; continue; }
            if (n.judgeResult >= 0) { judgeCursor++; continue; }
            if (n.holdActive) break;

            double deltaSec = tChart - n.sect;

            // Per-type late Miss threshold
            double missLimit = getLateMissLimitSec(n);
            if (deltaSec > missLimit) {
                commitJudgement(n, JR_MISS, 0.25);
                judgeCursor++;
                continue;
            }
            break;
        }
        } catch (Throwable t) { /* prevent crash */ }

    }

    /**
     * Spatial matching: compute along-line distance between touch and note screen position,
     * convert to positionX coordinate space for consistent distance comparison.
     */
    private float getPhigrosTouchPos(float touchX, float touchY, Note note, double tChart, float stageAspect) {
        JudgeLine line = note.master;
        if (line == null) return Float.MAX_VALUE;
        JudgeLine.StateHolder st = line.fillState(tChart, stageAspect);
        if (st == null) return Float.MAX_VALUE;
        float lineX = stageL + st.xNorm * stageW;
        float lineY = stageT + st.yNorm * stageH;
        float lineRot = st.rotateDeg;
        double rad = lineRot * Math.PI / 180.0;
        float cosLine = (float) Math.cos(rad);
        float sinLine = (float) Math.sin(rad);

        // Project (touch - lineCenter) onto the along-line direction in pixel space
        float dx = touchX - lineX;
        float dy = touchY - lineY;
        float alongLinePixels = dx * cosLine + dy * sinLine;

        // Compute the note's actual along-line pixel offset (same as rendering)
        float offX = (float) (note.positionX * GameConstants.PGRW * stageW);
        if (!note.isHold) {
            // Apply same incline/xCtrl as rendering (see computeNoteLocalX / drawNoteHead)
            double beatt = line.sec2beat(tChart);
            double lineFp = EventUtils.getFloorPosition(beatt, line.speedEvents);
            double bpm = (line.bpm > 0) ? line.bpm : 120.0;
            double speed = note.speed * scrollSpeed;
            double baseFpD = (note.floorPosition - lineFp) * GameConstants.PGRH * (GameConstants.PGRBEAT / bpm) * stageH * speed;
            float baseFpPx = (float) baseFpD;
            float transYPx = yOffsetToTransYPx(note.yOffset, speed, stageH);
            float yCtrl = line.calcNoteControl(baseFpPx, JudgeLine.NOTE_CTRL_Y, 1f);
            float baseFpWithCtrl = applyYControlScale(baseFpPx, yCtrl);
            float inclineValue = 1f;
            if (Float.isFinite(st.inclineSinr)) {
                inclineValue = calcInclineValue(st.inclineSinr, baseFpWithCtrl, transYPx, stageW / (float) stageH, (float) stageH);
            }
            offX *= inclineValue;
            float xCtrl = line.calcNoteControl(baseFpPx, JudgeLine.NOTE_CTRL_X, 1f);
            if (Float.isFinite(xCtrl) && xCtrl != 0f) offX *= xCtrl;
        }

        // Convert pixel distance to positionX coordinate space:
        // offX = positionX_effective * PGRW * stageW (with incline/xCtrl baked in)
        // So: positionX_effective = offX / (PGRW * stageW)
        // fingerPositionX = alongLinePixels / (PGRW * stageW)
        float fingerPositionX = alongLinePixels / (GameConstants.PGRW * stageW);
        float notePositionX = offX / (GameConstants.PGRW * stageW);

        return Math.abs(fingerPositionX - notePositionX);
    }

    /**: getJudgeOffset = |noteLocalX - touchLocalX| / judgeArea (along-line distance) */
    private float getJudgeOffset(float touchX, float touchY, Note note, double tChart, float stageAspect) {
        JudgeLine line = note.master;
        if (line == null) return Float.MAX_VALUE;
        JudgeLine.StateHolder st = line.fillState(tChart, stageAspect);
        if (st == null) return Float.MAX_VALUE;
        float lineX = stageL + st.xNorm * stageW;
        float lineY = stageT + st.yNorm * stageH;
        float lineRot = st.rotateDeg;
        double rad = lineRot * Math.PI / 180.0;
        float cosLine = (float) Math.cos(rad);
        float sinLine = (float) Math.sin(rad);
        float touchLocalX = toLineLocalX(touchX, touchY, lineX, lineY, cosLine, sinLine);
        float noteLocalX = computeNoteLocalX(tChart, note, line, st);
        return Math.abs(noteLocalX - touchLocalX) / (note.judgeArea > 0 ? note.judgeArea : 1.0f);
    }

    /**: getJudgeDistance = getJudgeOffset + |touchLocalY| (Manhattan distance) */
    private float getJudgeDistance(float touchX, float touchY, Note note, double tChart, float stageAspect) {
        JudgeLine line = note.master;
        if (line == null) return Float.MAX_VALUE;
        JudgeLine.StateHolder st = line.fillState(tChart, stageAspect);
        if (st == null) return Float.MAX_VALUE;
        float lineX = stageL + st.xNorm * stageW;
        float lineY = stageT + st.yNorm * stageH;
        float lineRot = st.rotateDeg;
        double rad = lineRot * Math.PI / 180.0;
        float cosLine = (float) Math.cos(rad);
        float sinLine = (float) Math.sin(rad);
        float touchLocalX = toLineLocalX(touchX, touchY, lineX, lineY, cosLine, sinLine);
        float noteLocalX = computeNoteLocalX(tChart, note, line, st);
        float touchLocalY = toLineLocalY(touchX, touchY, lineX, lineY, cosLine, sinLine);
        float ja = note.judgeArea > 0 ? note.judgeArea : 1.0f;
        return (Math.abs(noteLocalX - touchLocalX) + Math.abs(touchLocalY)) / ja;
    }

    /** Check if touch is near the judge line vertically (Y-distance) */
    private boolean isTouchNearLine(float touchX, float touchY, Note note, double tChart, float stageAspect) {
        JudgeLine line = note.master;
        if (line == null) return false;
        JudgeLine.StateHolder st = line.fillState(tChart, stageAspect);
        if (st == null) return false;
        float lineX = stageL + st.xNorm * stageW;
        float lineY = stageT + st.yNorm * stageH;
        float lineRot = st.rotateDeg;
        double rad = lineRot * Math.PI / 180.0;
        float cosLine = (float) Math.cos(rad);
        float sinLine = (float) Math.sin(rad);
        float touchLocalY = toLineLocalY(touchX, touchY, lineX, lineY, cosLine, sinLine);
        return Math.abs(touchLocalY) <= JUDGE_LINE_Y_TOLERANCE;
    }

    private void statDisp(double offset) {
        // Track judgment offset for statistics display (Early/Late)
        // Similar to's stat.addDisp
    }

    private float toScreenLocalX(float x) {
        int w = viewW <= 1 ? 1 : viewW;
        return x / (float) w * 2f - 1f;
    }

    private float toScreenLocalY(float y) {
        int h = viewH <= 1 ? 1 : viewH;
        return y / (float) h * 2f - 1f;
    }

    private float toLineLocalX(float touchX, float touchY,
                               float lineX, float lineY,
                               float cosLine, float sinLine) {
        float dx = (touchX - lineX) / (stageW * 0.5f);
        float dy = (touchY - lineY) / (stageH * 0.5f);
        return dx * cosLine + dy * sinLine;
    }

    /** Y-component of touch in the line's local coordinate system (perpendicular distance from line). */
    private float toLineLocalY(float touchX, float touchY,
                               float lineX, float lineY,
                               float cosLine, float sinLine) {
        float dx = (touchX - lineX) / (stageW * 0.5f);
        float dy = (touchY - lineY) / (stageH * 0.5f);
        return -dx * sinLine + dy * cosLine;
    }

    private float computeNoteLocalX(double tChart, @NonNull Note note, @NonNull JudgeLine line, @NonNull JudgeLine.StateHolder st) {
        double beatt = line.sec2beat(tChart);
        double lineFp = EventUtils.getFloorPosition(beatt, line.speedEvents);

        double bpm = (line.bpm > 0) ? line.bpm : 120.0;
        double speed = (note.isHold && note.useOfficialSpeed ? 1.0 : note.speed) * scrollSpeed;

        // separate base and transY
        double baseFpD = (note.floorPosition - lineFp) * GameConstants.PGRH * (GameConstants.PGRBEAT / bpm) * stageH * speed;
        float baseFpPx = (float) baseFpD;
        float transYPx = yOffsetToTransYPx(note.yOffset, speed, stageH);

        float yCtrl = line.calcNoteControl(baseFpPx, JudgeLine.NOTE_CTRL_Y, 1f);
        float baseFpWithCtrl = applyYControlScale(baseFpPx, yCtrl);

        float inclineValue = 1f;
        if (Float.isFinite(st.inclineSinr)) {
            inclineValue = calcInclineValue(st.inclineSinr, baseFpWithCtrl, transYPx, stageW / (float) stageH, (float) stageH);
        }

        // for non-hold, tr.x *= incline_val * ctrl_obj.pos
        float offX = (float) (note.positionX * GameConstants.PGRW * stageW);
        float xCtrl = line.calcNoteControl(baseFpPx, JudgeLine.NOTE_CTRL_X, 1f);
        if (!note.isHold) {
            offX *= inclineValue;
            if (Float.isFinite(xCtrl) && xCtrl != 0f) offX *= xCtrl;
        }
        return offX / (stageW * 0.5f);
    }

    private void spawnHitEffect(@NonNull Note note, double timeSec, float r, float g, float b, float a, int numOfParts) {
        if (texHitFx == null || texWhite == null) return;
        
        // Default: effect appears on the judge line (for tap notes, drag, flick, and hold ongoing effects)
        if (!computeNoteHeadPositionOnLine(note, timeSec, tmpNotePos)) return;

        double spd = Math.max(0.001f, musicSpeed);
        double lag = Math.max(0.0, (frameChartTimeSec - timeSec) / spd);
        hitEffects.add(new HitEffect((float) (visualTimeSec - lag), tmpNotePos[0], tmpNotePos[1], r, g, b, a, numOfParts));
    }

    /**
     * Hold head hit effect: places effect at head's current visual position (above or on the judge line).
     */
    private void spawnHoldHeadHitEffect(@NonNull Note note, double timeSec, float r, float g, float b, float a, int numOfParts) {
        if (texHitFx == null || texWhite == null) return;

        if (note.sect > timeSec) {
            // Head hasn't reached the line yet — spawn at head's current visual position
            if (!computeNoteHeadPosition(note, timeSec, tmpNotePos)) return;
        } else {
            // Head has already passed the line — spawn on the judge line
            if (!computeNoteHeadPositionOnLine(note, timeSec, tmpNotePos)) return;
        }

        double spd = Math.max(0.001f, musicSpeed);
        double lag = Math.max(0.0, (frameChartTimeSec - timeSec) / spd);
        hitEffects.add(new HitEffect((float) (visualTimeSec - lag), tmpNotePos[0], tmpNotePos[1], r, g, b, a, numOfParts));
    }
    
    private boolean computeNoteHeadPositionOnLine(@NonNull Note note, double tChart, @NonNull float[] outXY) {
        if (note.master == null) return false;
        JudgeLine line = note.master;
        float stageAspect = (stageH > 1e-6f) ? (stageW / stageH) : (16f / 9f);
        JudgeLine.StateHolder st = line.fillState(tChart, stageAspect);
        if (st == null) return false;

        float lineRot = st.rotateDeg;
        float lineX = stageL + st.xNorm * stageW;
        float lineY = stageT + st.yNorm * stageH;
        double rad = lineRot * Math.PI / 180.0;
        float cosLine = (float) Math.cos(rad);
        float sinLine = (float) Math.sin(rad);

        // Force distance to 0 (on line), but keep yOffset ( tr.y = transY when base=0)
        double speed = (note.useOfficialSpeed ? 1.0 : note.speed) * scrollSpeed;
        float transYPx = yOffsetToTransYPx(note.yOffset, speed, stageH);
        float visualFp = transYPx; // base = 0, so visual = 0 + transY

        float offX = (float) (note.positionX * GameConstants.PGRW * stageW);
        // On the line: no incline distortion needed for x position

        float nAtX = lineX + offX * cosLine;
        float nAtY = lineY + offX * sinLine;
        float dirX = note.isAbove ? sinLine : -sinLine;
        float dirY = note.isAbove ? -cosLine : cosLine;

        outXY[0] = nAtX + visualFp * dirX;
        outXY[1] = nAtY + visualFp * dirY;
        return true;
    }

    private void spawnBadEffect(@NonNull Note note, double timeSec) {
        Texture tex = getNoteHeadTexture(note);
        if (tex == null) return;
        if (!computeNoteHeadPosition(note, timeSec, tmpNotePos)) return;

        float stageAspect = (stageH > 1e-6f) ? (stageW / stageH) : (16f / 9f);
        JudgeLine line = note.master;
        if (line == null) return;
        JudgeLine.StateHolder st = line.fillState(timeSec, stageAspect);
        if (st == null) return;

        double beatt = line.sec2beat(timeSec);
        double lineFp = EventUtils.getFloorPosition(beatt, line.speedEvents);
        double bpm = (line.bpm > 0) ? line.bpm : 120.0;
        double speed = (note.isHold && note.useOfficialSpeed ? 1.0 : note.speed) * scrollSpeed;

        // separate base and transY
        double baseFpD = (note.floorPosition - lineFp) * GameConstants.PGRH * (GameConstants.PGRBEAT / bpm) * stageH * speed;
        float baseFpPx = (float) baseFpD;
        float transYPx = yOffsetToTransYPx(note.yOffset, speed, stageH);

        float yCtrl = line.calcNoteControl(baseFpPx, JudgeLine.NOTE_CTRL_Y, 1f);
        float baseFpWithCtrl = applyYControlScale(baseFpPx, yCtrl);
        float visualFp = baseFpWithCtrl + transYPx;

        float inclineV = 1f;
        if (Float.isFinite(st.inclineSinr)) {
            inclineV = calcInclineValue(st.inclineSinr, baseFpWithCtrl, transYPx, stageAspect, (float) stageH);
        }

        float noteWidthBase0 = stageW * 0.1234375f * keyScale;
        float widthS = 1.0f;
        Texture normalHeadTex = getNoteHeadTextureSameType(note.type, 0);
        Texture mhHeadTex = getNoteHeadTextureSameType(note.type, 1);
        if (note.morebets == 1 && normalHeadTex != null && mhHeadTex != null && normalHeadTex.width > 0) {
            widthS = (float) mhHeadTex.width / (float) normalHeadTex.width;
        }

        float sizeM = (!Float.isFinite(note.size) || note.size == 0f) ? 1f : note.size;
        float xScaleM = (!Float.isFinite(note.xScale) || note.xScale == 0f) ? 1f : note.xScale;
        float scaleC = line.calcNoteControl(baseFpPx, JudgeLine.NOTE_CTRL_SCALE, 1f);
        if (!Float.isFinite(scaleC) || scaleC == 0f) scaleC = 1f;

        float baseW = noteWidthBase0 * sizeM * widthS * scaleC * inclineV;
        float noteW = baseW * xScaleM;
        float nHeadH = (tex.height > 0 && tex.width > 0) ? (baseW / (float) tex.width) * (float) tex.height : baseW;
        float drawRot = st.rotateDeg + (note.isAbove ? 0f : 180f);

        double spd = Math.max(0.001f, musicSpeed);
        double lag = Math.max(0.0, (frameChartTimeSec - timeSec) / spd);
        badEffects.add(new BadEffect((float) (visualTimeSec - lag), tex, tmpNotePos[0], tmpNotePos[1], noteW, nHeadH, drawRot, 0f, 0f, 1f, 1f));
    }

    private static final class TouchState {
        float x;
        float y;
    }

    /**
     * Flick direction tracker with projected-speed hysteresis.
     * Starts flicking at speed > 1.0, stops at < 0.5. 'flicked' flag prevents gesture reuse.
     */
    private static final class FlickTracker {
        private float lastDeltaX;
        private float lastDeltaY;
        private float nowDeltaX;
        private float nowDeltaY;
        private float lastOffsetX;
        private float lastOffsetY;
        private long currentTimeMs;
        boolean flicking = false;
        boolean flicked = false;
        /** One-way latch: true once flicking has ever been detected on this tracker. */
        boolean hasFlicked = false;

        FlickTracker(float x, float y) {
            this.lastDeltaX = 0f;
            this.lastDeltaY = 0f;
            this.nowDeltaX = 0f;
            this.nowDeltaY = 0f;
            this.lastOffsetX = x;
            this.lastOffsetY = y;
            this.currentTimeMs = System.nanoTime() / 1_000_000L;
        }

        /** Called from touch move. Direct port of HitEvent.move(). */
        void move(float offsetX, float offsetY) {
            //: this.lastDeltaX = this.nowDeltaX; this.lastDeltaY = this.nowDeltaY;
            lastDeltaX = nowDeltaX;
            lastDeltaY = nowDeltaY;
            //: this.nowDeltaX = offsetX - this.offsetX; this.nowDeltaY = offsetY - this.offsetY;
            nowDeltaX = offsetX - lastOffsetX;
            nowDeltaY = offsetY - lastOffsetY;
            //: this.offsetX = offsetX; this.offsetY = offsetY;
            lastOffsetX = offsetX;
            lastOffsetY = offsetY;

            long time = System.nanoTime() / 1_000_000L;
            float dt = (float) (time - currentTimeMs);
            currentTimeMs = time;
            if (dt <= 0f) return;

            //: const flickSpeed = (this.nowDeltaX * this.lastDeltaX + this.nowDeltaY * this.lastDeltaY)
            //                       / Math.sqrt(this.lastDeltaX ** 2 + this.lastDeltaY ** 2) / this.deltaTime;
            float lastLen = (float) Math.sqrt(lastDeltaX * lastDeltaX + lastDeltaY * lastDeltaY);
            if (lastLen < 1e-6f) return;

            float flickSpeed = (nowDeltaX * lastDeltaX + nowDeltaY * lastDeltaY) / lastLen / dt;

            //: if (this.flicking && flickSpeed < 0.5) { this.flicking = false; this.flicked = false; }
            //        else if (!this.flicking && flickSpeed > 1.0) this.flicking = true;
            if (flicking && flickSpeed < 0.5f) {
                flicking = false;
                flicked = false;
            } else if (!flicking && flickSpeed > 1.0f) {
                flicking = true;
                hasFlicked = true;
            }
        }
    }

    private static final class HitEffect {
        final float timeSec;
        final float x;
        final float y;
        final float r;
        final float g;
        final float b;
        final float a;
        final int numOfParts;
        final float[] effectRotateDeg = new float[4];
        final float[] effectRBase = new float[4];

        HitEffect(float timeSec, float x, float y, float r, float g, float b, float a, int numOfParts) {
            this.timeSec = timeSec;
            this.x = x;
            this.y = y;
            this.r = r;
            this.g = g;
            this.b = b;
            this.a = a;
            this.numOfParts = numOfParts;
            for (int i = 0; i < 4; i++) {
                effectRotateDeg[i] = (float) (Math.random() * 360f);
                effectRBase[i] = 185f + (float) (Math.random() * (265f - 185f));
            }
        }
    }

    private static final class BadEffect {
        final float timeSec;
        final Texture tex;
        final float x;
        final float y;
        final float w;
        final float h;
        final float rotDeg;
        final float u0;
        final float v0;
        final float u1;
        final float v1;

        BadEffect(float timeSec,
                  Texture tex,
                  float x, float y,
                  float w, float h,
                  float rotDeg,
                  float u0, float v0, float u1, float v1) {
            this.timeSec = timeSec;
            this.tex = tex;
            this.x = x;
            this.y = y;
            this.w = w;
            this.h = h;
            this.rotDeg = rotDeg;
            this.u0 = u0;
            this.v0 = v0;
            this.u1 = u1;
            this.v1 = v1;
        }
    }

    /** Set before building a batch to control per-quad UV mirroring. */
    private boolean batchFlipUv = false;

    private void addQuadToBatch(float cx, float cy, float w, float h, float rotationDeg,
                                float r, float g, float b, float a,
                                float u0, float v0, float u1, float v1) {
        if (batchCount >= MAX_BATCH_QUADS) return;

        float hw = w * 0.5f;
        float hh = h * 0.5f;
        double rad = Math.toRadians(rotationDeg);
        float cos = (float) Math.cos(rad);
        float sin = (float) Math.sin(rad);

        // 4 corners: TL, TR, BL, BR
        float[][] corners = {
            {-hw, -hh}, {hw, -hh}, {-hw, hh}, {hw, hh}
        };
        // Per-quad UV swap (u0↔u1) mirrors the texture without shifting
        // spritesheet frame columns — unlike the shader-level vTex.x flip.
        float[][] uvs;
        if (batchFlipUv) {
            uvs = new float[][]{{u1, v0}, {u0, v0}, {u1, v1}, {u0, v1}};
        } else {
            uvs = new float[][]{{u0, v0}, {u1, v0}, {u0, v1}, {u1, v1}};
        }

        // Two triangles: (0, 1, 2) and (1, 2, 3)
        int[] indices = {0, 1, 2, 1, 2, 3};

        for (int idx : indices) {
            float rx = corners[idx][0] * cos - corners[idx][1] * sin;
            float ry = corners[idx][0] * sin + corners[idx][1] * cos;
            batchBuffer.put(cx + rx);
            batchBuffer.put(cy + ry);
            batchBuffer.put(uvs[idx][0]);
            batchBuffer.put(uvs[idx][1]);
            batchBuffer.put(r);
            batchBuffer.put(g);
            batchBuffer.put(b);
            batchBuffer.put(a);
        }
        batchCount++;
    }

    private void flushBatch(Texture tex) {
        if (batchCount == 0 || tex == null) {
            batchCount = 0;
            if (batchBuffer != null) batchBuffer.position(0);
            return;
        }

        GLES20.glUseProgram(progBatch);
        if (inStageSpace && mirrorX) {
            Matrix.multiplyMM(tmpBatchProj, 0, proj, 0, stagePreTransform, 0);
            GLES20.glUniformMatrix4fv(locBatchProj, 1, false, tmpBatchProj, 0);
        } else {
            GLES20.glUniformMatrix4fv(locBatchProj, 1, false, proj, 0);
        }
        GLES20.glActiveTexture(GLES20.GL_TEXTURE0);
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, tex.id);
        GLES20.glUniform1i(locBatchTex, 0);

        batchBuffer.position(0);
        GLES20.glEnableVertexAttribArray(locBatchPos);
        GLES20.glVertexAttribPointer(locBatchPos, 2, GLES20.GL_FLOAT, false, VERTEX_SIZE * 4, batchBuffer);

        batchBuffer.position(2);
        GLES20.glEnableVertexAttribArray(locBatchUv);
        GLES20.glVertexAttribPointer(locBatchUv, 2, GLES20.GL_FLOAT, false, VERTEX_SIZE * 4, batchBuffer);

        batchBuffer.position(4);
        GLES20.glEnableVertexAttribArray(locBatchColor);
        GLES20.glVertexAttribPointer(locBatchColor, 4, GLES20.GL_FLOAT, false, VERTEX_SIZE * 4, batchBuffer);

        GLES20.glDrawArrays(GLES20.GL_TRIANGLES, 0, batchCount * 6);

        GLES20.glDisableVertexAttribArray(locBatchPos);
        GLES20.glDisableVertexAttribArray(locBatchUv);
        GLES20.glDisableVertexAttribArray(locBatchColor);

        batchCount = 0;
        batchBuffer.position(0);
    }

    private void drawClickEffects(double tChart) {
        if (texHitFx == null || texWhite == null) return;
        if (respack == null || respack.hitFx == null || respack.hitFx.length < 2) return;

        int cols = Math.max(1, respack.hitFx[0]);
        int rows = Math.max(1, respack.hitFx[1]);
        int frames = cols * rows;

        final double dur = 0.5;
        final double tVis = visualTimeSec;
        float noteWidth = stageW * 0.1234375f * keyScale;
        float effectSize = noteWidth * 1.375f * 1.12f;

        List<ClickEffectItem> eff = chart.clickEffectsSorted;
        if (eff == null) return;

        while (clickEffectIndex < eff.size()) {
            ClickEffectItem e0 = eff.get(clickEffectIndex);
            if (e0.timeSec > tChart) break;
            if (!e0.animStartCached) {
                e0.animStartCached = true;
                e0.animStartSec = (float) tVis;
                break;
            }
            if ((tVis - e0.animStartSec) > dur) {
                clickEffectIndex++;
            } else {
                break;
            }
        }

        // 1. Batch hit animations
        for (int i = clickEffectIndex; i < eff.size(); i++) {
            ClickEffectItem item = eff.get(i);
            if (item.timeSec > tChart) break;
            if (!item.animStartCached) {
                item.animStartCached = true;
                item.animStartSec = (float) tVis;
            }
            double elapsed = tVis - item.animStartSec;
            if (elapsed < 0.0) continue;
            if (elapsed > dur) continue;

            float p = (float) (elapsed / dur);
            p = MathUtils.clamp(p, 0f, 1f);

            int idx = (int) Math.floor(p * frames);
            if (idx < 0) idx = 0;
            if (idx >= frames) idx = frames - 1;

            int cx = idx % cols;
            int cy = idx / cols;
            float u0 = (float) cx / (float) cols;
            float u1 = (float) (cx + 1) / (float) cols;
            float v0 = (float) cy / (float) rows;
            float v1 = (float) (cy + 1) / (float) rows;

            if (item.note == null || item.note.master == null) continue;

            float x, y;
            if (item.positionCached) {
                x = item.cachedScreenX;
                y = item.cachedScreenY;
            } else {
                float stageAspect = (stageH > 1e-6f) ? (stageW / stageH) : (16f / 9f);
                JudgeLine.StateHolder st = item.note.master.fillState(item.timeSec, stageAspect);
                float lineRot = st.rotateDeg;
                float lineX = stageL + st.xNorm * stageW;
                float lineY = stageT + st.yNorm * stageH;
                double rad = lineRot * Math.PI / 180.0;
                float cosLine = (float) Math.cos(rad);
                float sinLine = (float) Math.sin(rad);
                float off = (float) (item.note.positionX * GameConstants.PGRW * stageW);
                x = lineX + off * cosLine;
                y = lineY + off * sinLine;
                item.cachedScreenX = x;
                item.cachedScreenY = y;
                item.positionCached = true;
            }

            addQuadToBatch(x, y, effectSize, effectSize, 0f,
                    GameConstants.PCOLOR[0], GameConstants.PCOLOR[1], GameConstants.PCOLOR[2], GameConstants.PALPHA,
                    u0, v0, u1, v1);
            
            if (batchCount >= MAX_BATCH_QUADS) flushBatch(texHitFx);
        }
        flushBatch(texHitFx);

        // 2. Batch particles
        float s = stageW / 4040f * 3f;
        float baseSize = s * 30f;

        for (int i = clickEffectIndex; i < eff.size(); i++) {
            ClickEffectItem item = eff.get(i);
            if (item.timeSec > tChart) break;
            if (!item.animStartCached) {
                item.animStartCached = true;
                item.animStartSec = (float) tVis;
            }
            double elapsed = tVis - item.animStartSec;
            if (elapsed < 0.0) continue;
            if (elapsed > dur) continue;

            float p = (float) (elapsed / dur);
            p = MathUtils.clamp(p, 0f, 1f);
            float k = 9f * p / (8f * p + 1f);
            float alpha = 1f - p;
            float size = baseSize * (((0.2078f * p - 1.6524f) * p + 1.6399f) * p + 0.4988f);

            float x = item.cachedScreenX;
            float y = item.cachedScreenY;

            for (int pi = 0; pi < item.numOfParts; pi++) {
                float r = s * item.effectRBase[pi] * k;
                double prad = item.effectRotateDeg[pi] * Math.PI / 180.0;
                float rx = x + (float) (r * Math.cos(prad));
                float ry = y + (float) (r * Math.sin(prad));
                
                addQuadToBatch(rx, ry, size, size, 0f,
                        GameConstants.PCOLOR[0], GameConstants.PCOLOR[1], GameConstants.PCOLOR[2], alpha,
                        0f, 0f, 1f, 1f);
                
                if (batchCount >= MAX_BATCH_QUADS) flushBatch(texWhite);
            }
        }
        flushBatch(texWhite);
    }

    private void drawBadEffects(double tChart) {
        if (badEffects.isEmpty()) return;
        final float badTime = 0.5f;
        final double tVis = visualTimeSec;

        while (!badEffects.isEmpty()) {
            BadEffect e = badEffects.get(0);
            if (tVis - e.timeSec > badTime) {
                badEffects.remove(0);
            } else {
                break;
            }
        }
        if (badEffects.isEmpty()) return;

        Texture current = null;
        for (int i = 0; i < badEffects.size(); i++) {
            BadEffect it = badEffects.get(i);
            float p = (float) ((tVis - it.timeSec) / badTime);
            p = MathUtils.clamp(p, 0f, 1f);
            float a = 1f - p;
            if (a <= 0f) continue;

            if (it.tex == null) continue;
            if (current != it.tex) {
                if (current != null) flushBatch(current);
                current = it.tex;
            }
            addQuadToBatch(it.x, it.y, it.w, it.h, it.rotDeg,
                    0.423529f, 0.262745f, 0.262745f, a,
                    it.u0, it.v0, it.u1, it.v1);
            if (batchCount >= MAX_BATCH_QUADS) flushBatch(current);
        }
        if (current != null) flushBatch(current);
    }

    private void drawHitEffects(double tChart) {
        if (texHitFx == null || texWhite == null) return;
        if (respack == null || respack.hitFx == null || respack.hitFx.length < 2) return;
        if (hitEffects.isEmpty()) return;

        int cols = Math.max(1, respack.hitFx[0]);
        int rows = Math.max(1, respack.hitFx[1]);
        int frames = cols * rows;

        final double dur = 0.5;
        final double tVis = visualTimeSec;
        float noteWidth = stageW * 0.1234375f * keyScale;
        float effectSize = noteWidth * 1.375f * 1.12f;

        while (!hitEffects.isEmpty()) {
            HitEffect e = hitEffects.get(0);
            if (e.timeSec + dur < tVis) {
                hitEffects.remove(0);
            } else {
                break;
            }
        }
        if (hitEffects.isEmpty()) return;

        for (int i = 0; i < hitEffects.size(); i++) {
            HitEffect item = hitEffects.get(i);
            if (item.timeSec + dur < tVis) continue;

            float p = (float) ((tVis - item.timeSec) / dur);
            p = MathUtils.clamp(p, 0f, 1f);

            int idx = (int) Math.floor(p * frames);
            if (idx < 0) idx = 0;
            if (idx >= frames) idx = frames - 1;

            int cx = idx % cols;
            int cy = idx / cols;
            float u0 = (float) cx / (float) cols;
            float u1 = (float) (cx + 1) / (float) cols;
            float v0 = (float) cy / (float) rows;
            float v1 = (float) (cy + 1) / (float) rows;

            addQuadToBatch(item.x, item.y, effectSize, effectSize, 0f,
                    item.r, item.g, item.b, item.a,
                    u0, v0, u1, v1);
            if (batchCount >= MAX_BATCH_QUADS) flushBatch(texHitFx);
        }
        flushBatch(texHitFx);

        float s = stageW / 4040f * 3f;
        float baseSize = s * 30f;
        for (int i = 0; i < hitEffects.size(); i++) {
            HitEffect item = hitEffects.get(i);
            if (item.timeSec + dur < tVis) continue;

            float p = (float) ((tVis - item.timeSec) / dur);
            p = MathUtils.clamp(p, 0f, 1f);
            float k = 9f * p / (8f * p + 1f);
            float alpha = 1f - p;
            float size = baseSize * (((0.2078f * p - 1.6524f) * p + 1.6399f) * p + 0.4988f);

            for (int pi = 0; pi < item.numOfParts; pi++) {
                float rr = s * item.effectRBase[pi] * k;
                double prad = item.effectRotateDeg[pi] * Math.PI / 180.0;
                float rx = item.x + (float) (rr * Math.cos(prad));
                float ry = item.y + (float) (rr * Math.sin(prad));
                addQuadToBatch(rx, ry, size, size, 0f,
                        item.r, item.g, item.b, alpha,
                        0f, 0f, 1f, 1f);
                if (batchCount >= MAX_BATCH_QUADS) flushBatch(texWhite);
            }
        }
        flushBatch(texWhite);
    }


    /**
     * Compute the min/max projection of the stage area onto the judge line's axis.
     * This defines the range of "visualFp" that could possibly be visible on screen.
     *
     * @param st      The current state of the judge line.
     * @param result  A float[2] array to store {minProj, maxProj}.
     */
    private void computeLineVisibleRange(@NonNull JudgeLine.StateHolder st, @NonNull float[] result) {
        // Line center in screen space
        float lineX = stageL + st.xNorm * stageW;
        float lineY = stageT + st.yNorm * stageH;

        // Line direction
        double rad = st.rotateDeg * Math.PI / 180.0;
        float cos = (float) Math.cos(rad);
        float sin = (float) Math.sin(rad);

        // Stage corners (TL, TR, BL, BR)
        // Note: Stage rect is (stageL, stageT, stageL+stageW, stageT+stageH)
        // Relative to line center (lineX, lineY)
        float l = stageL - lineX;
        float r = (stageL + stageW) - lineX;
        float t = stageT - lineY;
        float b = (stageT + stageH) - lineY;

        // Expand stage area slightly (1/12 margin as per isNoteRectInArea) to be safe
        float marginW = stageW / 12f;
        float marginH = stageH / 12f;
        l -= marginW;
        r += marginW;
        t -= marginH;
        b += marginH;

        // Project 4 corners onto the line vector (cos, sin)
        // P = x * cos + y * sin
        float p1 = l * cos + t * sin;
        float p2 = r * cos + t * sin;
        float p3 = l * cos + b * sin;
        float p4 = r * cos + b * sin;

        float min = p1;
        if (p2 < min) min = p2;
        if (p3 < min) min = p3;
        if (p4 < min) min = p4;

        float max = p1;
        if (p2 > max) max = p2;
        if (p3 > max) max = p3;
        if (p4 > max) max = p4;

        // When scrollSpeed < 1.0, notes move slower and stay on screen longer,
        // so more notes can be visible simultaneously. Expand the visible range
        // proportionally to avoid clipping notes that are actually on screen.
        // High scrollSpeed (>= 1.0) needs no adjustment.
        if (scrollSpeed < 1.0f) {
            float factor = 1.0f / scrollSpeed;
            result[0] = min * factor;
            result[1] = max * factor;
        } else {
            result[0] = min;
            result[1] = max;
        }
    }

    // ---------------- Drawing helpers ----------------


    /**
     * Draw a texture into a destination rect using a "cover" rule (keep aspect, fill & crop).
     *
     * <p>When the destination is scissor-clipped, this behaves like's adjustSize(..., factor)
     * for the stage and background.
     */
    private void drawTextureCover(Texture tex,
                                 float dstX, float dstY, float dstW, float dstH,
                                 float extraScale,
                                 boolean isFboTexture,
                                 float r, float g, float b, float a) {
        if (tex == null) return;

        float tw = tex.width > 0 ? (float) tex.width : dstW;
        float th = tex.height > 0 ? (float) tex.height : dstH;
        float texAr = tw / Math.max(1f, th);
        float dstAr = dstW / Math.max(1f, dstH);

        float w;
        float h;
        if (texAr > dstAr) {
            // texture wider -> fit height
            h = dstH;
            w = h * texAr;
        } else {
            // texture taller -> fit width
            w = dstW;
            h = w / texAr;
        }

        w *= extraScale;
        h *= extraScale;

        float x = dstX + (dstW - w) * 0.5f;
        float y = dstY + (dstH - h) * 0.5f;

        if (isFboTexture) {
            // FBO textures need v flip to match our bitmap/canvas convention
            drawTextureTopLeft(tex, x, y, w, h, 0f, r, g, b, a, 0f, 1f, 1f, 0f);
        } else {
            drawTextureTopLeft(tex, x, y, w, h, 0f, r, g, b, a, 0f, 0f, 1f, 1f);
        }
    }

    private boolean isStageFullscreen() {
        return Math.abs(stageL) < 0.5f
                && Math.abs(stageT) < 0.5f
                && Math.abs(stageW - viewW) < 0.5f
                && Math.abs(stageH - viewH) < 0.5f;
    }

    // State for temporary rendering scale (used during FBO downsampling)
    private float renderScaleX = 1f;
    private float renderScaleY = 1f;

    private void enableStageScissor() {
        // glScissor uses bottom-left origin; our stage rect uses top-left origin.
        // Apply render scale for FBO downsampling.
        int sx = Math.round(stageL * renderScaleX);
        int sy = Math.round((viewH - (stageT + stageH)) * renderScaleY);
        int sw = Math.round(stageW * renderScaleX);
        int sh = Math.round(stageH * renderScaleY);

        // Clamp to current viewport (scaled if needed)
        int vw = Math.round(viewW * renderScaleX);
        int vh = Math.round(viewH * renderScaleY);

        if (sx < 0) {
            sw += sx;
            sx = 0;
        }
        if (sy < 0) {
            sh += sy;
            sy = 0;
        }
        if (sx + sw > vw) sw = vw - sx;
        if (sy + sh > vh) sh = vh - sy;

        if (sw <= 0 || sh <= 0) {
            GLES20.glDisable(GLES20.GL_SCISSOR_TEST);
            return;
        }

        GLES20.glEnable(GLES20.GL_SCISSOR_TEST);
        GLES20.glScissor(sx, sy, sw, sh);
    }

    private void disableStageScissor() {
        GLES20.glDisable(GLES20.GL_SCISSOR_TEST);
    }

    private void drawButton(Texture tex, RectF r) {
        if (tex == null) return;
        drawTextureTopLeft(tex, r.left, r.top, r.width(), r.height(), 0f,
                1f, 1f, 1f, 1f,
                0f, 0f, 1f, 1f);
    }

    private void drawFullscreenTexture(Texture tex, float r, float g, float b, float a) {
        drawTextureTopLeft(tex, 0f, 0f, viewW, viewH, 0f, r, g, b, a, 0f, 0f, 1f, 1f);
    }

    /**
     * Fullscreen background helper (bitmap textures).
     *
     * <p>Background uses the same UV convention as other sprite bitmaps in this renderer.
     * Do <b>not</b> apply extra v flips here. For FBO-produced textures, use
     * {@link #drawFullscreenTextureFbo(Texture, float, float, float, float)}.
     */
    private void drawFullscreenTextureBackground(Texture tex, float r, float g, float b, float a) {
        // Background is a normal bitmap texture, same UV convention as other sprites.
        drawTextureTopLeft(tex, 0f, 0f, viewW, viewH, 0f, r, g, b, a, 0f, 0f, 1f, 1f);
    }


    private void drawFullscreenTextureFbo(Texture tex, float r, float g, float b, float a) {
        // FBO 渲染得到的纹理，其 v 方向与从 Bitmap 上传的纹理不同：
        // 为了保持与 Canvas 风格（v=0 表示“上”）一致，这里需要翻转 v。
        drawTextureTopLeft(tex, 0f, 0f, viewW, viewH, 0f, r, g, b, a, 0f, 1f, 1f, 0f);
    }

    private void drawSolidRect(float x, float y, float w, float h, float r, float g, float b, float a) {
        if (texWhite == null) return;
        drawTextureTopLeft(texWhite, x, y, w, h, 0f, r, g, b, a, 0f, 0f, 1f, 1f);
    }

    private void drawLine(float x0, float y0, float x1, float y1, float width, float r, float g, float b, float a) {
        float dx = x1 - x0;
        float dy = y1 - y0;
        float len = (float) Math.sqrt(dx * dx + dy * dy);
        if (len < 0.0001f) return;

        float cx = (x0 + x1) * 0.5f;
        float cy = (y0 + y1) * 0.5f;
        float rot = (float) (Math.atan2(dy, dx) * 180.0 / Math.PI);

        drawTextureCentered(texWhite, cx, cy, len, width, rot, r, g, b, a, 0f, 0f, 1f, 1f);
    }

    private void drawTextureTopLeft(Texture tex,
                                   float x, float y, float w, float h,
                                   float rotationDeg,
                                   float r, float g, float b, float a,
                                   float u0, float v0, float u1, float v1) {
        // convert to centered draw
        float cx = x + w * 0.5f;
        float cy = y + h * 0.5f;
        drawTextureCentered(tex, cx, cy, w, h, rotationDeg, r, g, b, a, u0, v0, u1, v1);
    }

    private void drawTextureCentered(Texture tex,
                                     float cx, float cy,
                                     float w, float h,
                                     float rotationDeg,
                                     float r, float g, float b, float a,
                                     float u0, float v0, float u1, float v1) {
        if (tex == null) return;

        GLES20.glUseProgram(progSprite);

        GLES20.glActiveTexture(GLES20.GL_TEXTURE0);
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, tex.id);
        GLES20.glUniform1i(locTex, 0);

        Matrix.setIdentityM(tmpModel, 0);
        Matrix.translateM(tmpModel, 0, cx, cy, 0f);
        Matrix.rotateM(tmpModel, 0, rotationDeg, 0f, 0f, 1f);
        Matrix.scaleM(tmpModel, 0, w, h, 1f);

        if (inStageSpace && mirrorX) {
            Matrix.multiplyMM(tmpWorld, 0, stagePreTransform, 0, tmpModel, 0);
            Matrix.multiplyMM(tmpMvp, 0, proj, 0, tmpWorld, 0);
        } else {
            Matrix.multiplyMM(tmpMvp, 0, proj, 0, tmpModel, 0);
        }
        GLES20.glUniformMatrix4fv(locMvp, 1, false, tmpMvp, 0);
        GLES20.glUniform4f(locColor, r, g, b, a);

        // update uv buffer for this draw
        // When the whole stage is mirrored (demo), the geometry is reflected by stagePreTransform,
        // which would also mirror the texture. To keep note / hit-fx (and other sprite) materials
        // visually unmirrored, flip the U coordinates once more when mirrorX is active.
        float uu0 = u0;
        float uu1 = u1;
        if (inStageSpace && mirrorX) {
            float tmp = uu0;
            uu0 = uu1;
            uu1 = tmp;
        }
        updateQuadUv(uu0, v0, uu1, v1);

        GLES20.glEnableVertexAttribArray(locPos);
        GLES20.glVertexAttribPointer(locPos, 2, GLES20.GL_FLOAT, false, 0, quadPos);
        GLES20.glEnableVertexAttribArray(locUv);
        GLES20.glVertexAttribPointer(locUv, 2, GLES20.GL_FLOAT, false, 0, quadUv);

        GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4);

        GLES20.glDisableVertexAttribArray(locPos);
        GLES20.glDisableVertexAttribArray(locUv);
    }

    /**
     * Note visibility culling: check whether a rotated rectangle intersects
     * the extended stage area (stage expanded by 1/12 on each side).
     */
    private static boolean isNoteRectInArea(
            float cx, float cy,
            float w, float h,
            float rotationDeg,
            float areaL, float areaT, float areaR, float areaB
    ) {
        if (!(w > 0f) || !(h > 0f)) return false;
        // Exact AABB of a rotated rectangle.
        double rad = rotationDeg * Math.PI / 180.0;
        float cos = (float) Math.abs(Math.cos(rad));
        float sin = (float) Math.abs(Math.sin(rad));
        float halfW = w * 0.5f;
        float halfH = h * 0.5f;
        float extX = halfW * cos + halfH * sin;
        float extY = halfW * sin + halfH * cos;
        float minX = cx - extX;
        float maxX = cx + extX;
        float minY = cy - extY;
        float maxY = cy + extY;
        return !(maxX < areaL || minX > areaR || maxY < areaT || minY > areaB);
    }

    private void drawFullQuadWithCurrentProgram(int aPos, int aUv, float u0, float v0, float u1, float v1) {
        // Use NDC quad positions and specified UVs.
        GLES20.glBindBuffer(GLES20.GL_ARRAY_BUFFER, vboQuadNdc);
        if (aPos >= 0) {
            GLES20.glEnableVertexAttribArray(aPos);
            GLES20.glVertexAttribPointer(aPos, 2, GLES20.GL_FLOAT, false, 0, 0);
        }

        GLES20.glBindBuffer(GLES20.GL_ARRAY_BUFFER, 0);

        updateQuadUvNdc(u0, v0, u1, v1);
        if (aUv >= 0) {
            GLES20.glEnableVertexAttribArray(aUv);
            GLES20.glVertexAttribPointer(aUv, 2, GLES20.GL_FLOAT, false, 0, quadUv);
        }

        GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4);

        if (aPos >= 0) GLES20.glDisableVertexAttribArray(aPos);
        if (aUv >= 0) GLES20.glDisableVertexAttribArray(aUv);
    }

    // ---------------- Hold UV ----------------

    private static class HoldUv {
        float headV0;
        float headV1;
        float bodyV0;
        float bodyV1;
        float tailV0;
        float tailV1;
        float tailHpx;
        float headHpx;
    }

    private HoldUv getHoldUv(int morebets) {
        if (!multiPressHighlight) morebets = 0;
        Texture t = (morebets == 1) ? texHoldMh : texHold;
        if (t == null || t.height <= 0) {
            HoldUv uv = new HoldUv();
            uv.headV0 = 0.8f; uv.headV1 = 1f;
            uv.bodyV0 = 0f; uv.bodyV1 = 1f;
            uv.tailV0 = 0f; uv.tailV1 = 0.2f;
            uv.tailHpx = 16f;
            uv.headHpx = 16f;
            return uv;
        }

        int[] atlas = (morebets == 1 ? respack.holdAtlasMH : respack.holdAtlas);
        int tailH = (atlas != null && atlas.length > 0) ? atlas[0] : 64;
        int headH = (atlas != null && atlas.length > 1) ? atlas[1] : 64;
        int texH = t.height;

        // Use atlas values as-is. Resource packs should provide slice sizes that match
        // the actual texture pixels.

        int yTail0 = 0;
        int yTail1 = Math.min(texH, Math.max(0, tailH));

        int yBody0 = yTail1;
        int yBody1 = Math.max(yBody0, texH - Math.max(0, headH));

        int yHead0 = yBody1;
        int yHead1 = texH;

        HoldUv uv = new HoldUv();
        float padTail = (yTail1 - yTail0) > 1 ? 0.5f : 0f;
        float padBody = (yBody1 - yBody0) > 1 ? 0.5f : 0f;
        float padHead = (yHead1 - yHead0) > 1 ? 0.5f : 0f;

        uv.tailV0 = (yTail0 + padTail) / (float) texH;
        uv.tailV1 = (yTail1 - padTail) / (float) texH;
        uv.bodyV0 = (yBody0 + padBody) / (float) texH;
        uv.bodyV1 = (yBody1 - padBody) / (float) texH;
        uv.headV0 = (yHead0 + padHead) / (float) texH;
        uv.headV1 = (yHead1 - padHead) / (float) texH;

        if (!(uv.tailV1 > uv.tailV0)) {
            uv.tailV0 = (float) yTail0 / (float) texH;
            uv.tailV1 = (float) yTail1 / (float) texH;
        }
        if (!(uv.bodyV1 > uv.bodyV0)) {
            uv.bodyV0 = (float) yBody0 / (float) texH;
            uv.bodyV1 = (float) yBody1 / (float) texH;
        }
        if (!(uv.headV1 > uv.headV0)) {
            uv.headV0 = (float) yHead0 / (float) texH;
            uv.headV1 = (float) yHead1 / (float) texH;
        }
        uv.tailHpx = (float) (yTail1 - yTail0);
        uv.headHpx = (float) (yHead1 - yHead0);
        return uv;
    }

    // ---------------- Texture choose ----------------

    private Texture getNoteHeadTexture(Note note) {
        if (note == null) return null;
        int mb = multiPressHighlight ? note.morebets : 0;
        if (note.type == GameConstants.NOTE_HOLD) {
            return (mb == 1) ? texHoldMh : texHold;
        }
        Texture t = noteHeadTex[note.type][mb];
        if (t == null) t = noteHeadTex[note.type][0];
        return t;
    }

    private Texture getNoteHeadTextureSameType(int type, int morebets) {
        if (type < 0 || type >= noteHeadTex.length) return null;
        if (!multiPressHighlight) morebets = 0;
        Texture t = noteHeadTex[type][morebets];
        if (t == null) t = noteHeadTex[type][0];
        return t;
    }

    private void rebuildNoteHeadTextureDrawList() {
        noteHeadTextureDrawList.clear();
        // Render order: Drag(2) < Click(1) < Flick(4).
        // Hold heads are rendered inside the body pass
        // hold body+head are a single container), so NOTE_HOLD is excluded here.
        // In painter's algorithm, earlier draw = behind.
        int[] drawOrder = {
            GameConstants.NOTE_DRAG,
            GameConstants.NOTE_TAP,
            GameConstants.NOTE_FLICK
        };
        for (int tType : drawOrder) {
            for (int m = 0; m < 2; m++) {
                Texture tex = getNoteHeadTextureSameType(tType, m);
                if (tex == null) continue;
                if (!noteHeadTextureDrawList.contains(tex)) noteHeadTextureDrawList.add(tex);
            }
        }
    }

    // ---------------- GL helpers ----------------

    private static class Texture {
        int id;
        int width;
        int height;
        int paddingBottom; // Extra padding at bottom (e.g. for shadow/tails)

        // --- GC-survivable fields (style: keep pixel data alive on Java heap) ---
        /** Retained bitmap for re-upload after GL context loss. Null if not retained. */
        Bitmap retainedBitmap;
        /** File path for file-based textures (used as fallback if bitmap is null). */
        String filePath;
        /** Text content for text-based textures (used for re-rendering after context loss). */
        String textContent;
        /** Text size used when textContent was rendered. */
        float textSizePx;
        /** GL context version this texture was uploaded in. 0 = not uploaded. */
        int contextVersion;

        // --- GIF animation fields (style: individual texture per frame) ---
        boolean isGif;
        int gifFrameCount;
        /** OpenGL texture IDs for each GIF frame. */
        int[] gifFrameTexIds;
        /** Width of each frame texture (may differ between frames). */
        int[] gifFrameWidths;
        /** Height of each frame texture (may differ between frames). */
        int[] gifFrameHeights;
        /** Delay per GIF frame in milliseconds. */
        int[] gifFrameDurationsMs;
        /** Total duration of one GIF loop in milliseconds. */
        long gifTotalDurationMs;
        /** Retained bitmaps for GIF frames (for re-upload after GL context loss). */
        Bitmap[] gifFrameBitmaps;

        void invalidateGl() {
            id = 0;
            if (gifFrameTexIds != null && isGif) {
                // Also invalidate individual frame texture IDs
                int[] idsToDelete = gifFrameTexIds;
                gifFrameTexIds = null;
                // Schedule deletion — will be recreated on next use
                // GL deletion happens in ensureTextureValid → full reload
            }
            contextVersion = 0;
        }

        boolean isValidForContext(int currentVersion) {
            return id != 0 && contextVersion == currentVersion && currentVersion > 0;
        }

        /**
         * Get the OpenGL texture ID for the given GIF frame index.
         * Returns 0 if the frame texture is not available.
         */
        int getGifFrameTexId(int frameIndex) {
            if (!isGif || gifFrameTexIds == null || frameIndex < 0 || frameIndex >= gifFrameTexIds.length) {
                return id; // fallback to main texture
            }
            return gifFrameTexIds[frameIndex];
        }

        /**
         * Get the frame index for the given elapsed time (non-uniform timing).
         * @param elapsedMs total elapsed time since playback start, in milliseconds
         * @return 0-based frame index
         */
        int getGifFrameIndex(long elapsedMs) {
            if (!isGif || gifFrameCount <= 0 || gifTotalDurationMs <= 0) return 0;
            long t = elapsedMs % gifTotalDurationMs;
            for (int i = 0; i < gifFrameCount; i++) {
                long d = gifFrameDurationsMs != null ? gifFrameDurationsMs[i] : 100;
                if (d <= 0) d = 100;
                if (t < d) return i;
                t -= d;
            }
            return gifFrameCount - 1;
        }
    }

    /** Monotonically increasing GL context version. Incremented on each onSurfaceCreated. */
    private int glContextVersion = 0;

    private static class RectF {
        float left, top, right, bottom;
        void set(float l, float t, float r, float b) { left = l; top = t; right = r; bottom = b; }
        float width() { return right - left; }
        float height() { return bottom - top; }
        boolean contains(float x, float y) { return x >= left && x <= right && y >= top && y <= bottom; }
    }

    private class FboTex {
        final int w;
        final int h;
        final int fbo;
        final Texture tex;

        FboTex(int w, int h) {
            this.w = w;
            this.h = h;
            this.tex = createEmptyTexture(w, h);
            this.fbo = createFboForTexture(tex.id);
        }
    }

    private void releaseFbos() {
        if (sceneFbo != null) deleteFbo(sceneFbo);
        if (blurFbo1 != null) deleteFbo(blurFbo1);
        if (blurFbo2 != null) deleteFbo(blurFbo2);
        if (bgBlurA != null) deleteFbo(bgBlurA);
        if (bgBlurB != null) deleteFbo(bgBlurB);
        if (prprFullA != null) deleteFbo(prprFullA);
        if (prprFullB != null) deleteFbo(prprFullB);
        if (prprStageA != null) deleteFbo(prprStageA);
        if (prprStageB != null) deleteFbo(prprStageB);
        sceneFbo = null;
        blurFbo1 = null;
        blurFbo2 = null;
        bgBlurA = null;
        bgBlurB = null;
        prprFullA = null;
        prprFullB = null;
        prprStageA = null;
        prprStageB = null;
        texBackgroundBlur = null;
    }

    private void ensurePrprFbos() {
        if (!hasPrprEffects) return;

        // Full-screen buffers (global effects & final composition)
        prprFullA = new FboTex(viewW, viewH);
        prprFullB = new FboTex(viewW, viewH);

        // Stage-only buffers (non-global effects)
        if (hasPrprNonGlobal) {
            int sw = Math.max(2, Math.round(stageW));
            int sh = Math.max(2, Math.round(stageH));
            prprStageA = new FboTex(sw, sh);
            prprStageB = new FboTex(sw, sh);
        }
    }

    private void deleteFbo(FboTex f) {
        if (f == null) return;
        int[] a = new int[1];
        a[0] = f.fbo;
        GLES20.glDeleteFramebuffers(1, a, 0);
        a[0] = f.tex.id;
        GLES20.glDeleteTextures(1, a, 0);
    }

    private static final class PrprShaderProgram {
        final String key;
        final int program;
        final int aPosLoc;
        final int aUvLoc;

        final int uScreenTextureLoc;
        final int uTimeLoc;
        final int uScreenSizeLoc;
        final int uUVScaleLoc;

        final Map<String, UniformInfo> uniforms;

        PrprShaderProgram(String key, int program, Map<String, float[]> parsedDefaults) {
            this.key = key;
            this.program = program;
            this.aPosLoc = GLES20.glGetAttribLocation(program, "aPosition");
            this.aUvLoc = GLES20.glGetAttribLocation(program, "aTexCoord");
            this.uScreenTextureLoc = GLES20.glGetUniformLocation(program, "screenTexture");
            this.uTimeLoc = GLES20.glGetUniformLocation(program, "time");
            this.uScreenSizeLoc = GLES20.glGetUniformLocation(program, "screenSize");
            this.uUVScaleLoc = GLES20.glGetUniformLocation(program, "UVScale");

            this.uniforms = new HashMap<>();
            int[] countArr = new int[1];
            GLES20.glGetProgramiv(program, GLES20.GL_ACTIVE_UNIFORMS, countArr, 0);
            int count = countArr[0];
            int[] sizeArr = new int[1];
            int[] typeArr = new int[1];
            for (int i = 0; i < count; i++) {
                String name = GLES20.glGetActiveUniform(program, i, sizeArr, 0, typeArr, 0);
                if (name == null || name.isEmpty()) continue;
                if (name.endsWith("[0]")) {
                    name = name.substring(0, name.length() - 3);
                }
                int loc = GLES20.glGetUniformLocation(program, name);
                float[] def = parsedDefaults != null ? parsedDefaults.get(name) : null;
                
                // Smart defaults for common uniforms when no explicit default is provided
                if (def == null) {
                    if ("color".equals(name) && typeArr[0] == GLES20.GL_FLOAT_VEC4) {
                        def = new float[]{1f, 1f, 1f, 1f};
                    } else if ("tint".equals(name) && typeArr[0] == GLES20.GL_FLOAT_VEC4) {
                        def = new float[]{1f, 1f, 1f, 1f};
                    }
                }

                uniforms.put(name, new UniformInfo(loc, typeArr[0], def));
            }
        }

        void applyDefaults() {
            for (UniformInfo ui : uniforms.values()) {
                if (ui.location >= 0 && ui.defaultValue != null) {
                    switch (ui.type) {
                        case GLES20.GL_FLOAT:
                            GLES20.glUniform1f(ui.location, ui.defaultValue[0]);
                            break;
                        case GLES20.GL_FLOAT_VEC2:
                            if (ui.defaultValue.length >= 2) GLES20.glUniform2f(ui.location, ui.defaultValue[0], ui.defaultValue[1]);
                            break;
                        case GLES20.GL_FLOAT_VEC3:
                            if (ui.defaultValue.length >= 3) GLES20.glUniform3f(ui.location, ui.defaultValue[0], ui.defaultValue[1], ui.defaultValue[2]);
                            break;
                        case GLES20.GL_FLOAT_VEC4:
                            if (ui.defaultValue.length >= 4) GLES20.glUniform4f(ui.location, ui.defaultValue[0], ui.defaultValue[1], ui.defaultValue[2], ui.defaultValue[3]);
                            break;
                    }
                }
            }
        }

        float getDefaultFloat(String name, float fallback) {
            UniformInfo ui = uniforms.get(name);
            if (ui != null && ui.defaultValue != null && ui.defaultValue.length >= 1) {
                return ui.defaultValue[0];
            }
            return fallback;
        }

        static final class UniformInfo {
            final int location;
            final int type;
            final float[] defaultValue;

            UniformInfo(int location, int type, float[] defaultValue) {
                this.location = location;
                this.type = type;
                this.defaultValue = defaultValue;
            }
        }
    }

    private void initPrprShaderPrograms() {
        prprPrograms.clear();
        if (!hasPrprEffects || chart.prprEffects == null || chart.prprEffects.isEmpty()) {
            return;
        }

        java.util.HashSet<String> unique = new java.util.HashSet<>();
        for (PrprEffect e : chart.prprEffects) {
            if (e == null || e.shader == null) continue;
            String k = e.shader.trim();
            if (!k.isEmpty()) unique.add(k);
        }

        for (String key : unique) {
            String src = resolvePrprShaderSource(key);
            if (src == null) {
                Log.w(TAG, "prpr shader not found: " + key);
                continue;
            }

            Map<String, float[]> defaults = parsePrprUniformDefaults(src);
            String fs = preprocessPrprFragment(src);
            int p = buildProgram(VS_PRPR, fs);
            if (p == 0) {
                Log.w(TAG, "prpr shader compile failed: " + key);
                continue;
            }
            PrprShaderProgram prog = new PrprShaderProgram(key, p, defaults);
            prprPrograms.put(key, prog);

            // Common aliases: strip ".glsl" for preset names, and snake_case for camelCase presets.
            String norm = normalizePrprShaderKey(key);
            if (!norm.equals(key) && !prprPrograms.containsKey(norm)) {
                prprPrograms.put(norm, prog);
            }
        }
    }

    private PrprShaderProgram getPrprProgram(String shaderKey) {
        if (shaderKey == null) return null;
        PrprShaderProgram p = prprPrograms.get(shaderKey);
        if (p != null) return p;
        String norm = normalizePrprShaderKey(shaderKey);
        return prprPrograms.get(norm);
    }

    private String resolvePrprShaderSource(String shaderKey) {
        if (shaderKey == null) return null;
        String key = shaderKey.trim();
        if (key.isEmpty()) return null;

        // 1) Custom shader bundled with the chart pack (loaded by ChartLoader).
        if (chart.prprShaderSources != null) {
            String src = chart.prprShaderSources.get(key);
            if (src != null) return src;
            if (key.startsWith("/")) {
                src = chart.prprShaderSources.get(key.substring(1));
                if (src != null) return src;
            }
        }

        // 2) Presets from assets.
        String norm = normalizePrprShaderKey(key);
        String presetFile = prprPresetFileForKey(norm);
        if (presetFile != null) {
            return readAssetText("prpr_shaders/" + presetFile);
        }

        // 3) If the key itself looks like a glsl file name, also try opening it directly from presets dir.
        if (!key.startsWith("/") && key.toLowerCase(java.util.Locale.US).endsWith(".glsl")) {
            return readAssetText("prpr_shaders/" + key);
        }

        return null;
    }

    private static String normalizePrprShaderKey(String shaderKey) {
        if (shaderKey == null) return "";
        String k = shaderKey.trim();
        if (!k.startsWith("/") && k.toLowerCase(java.util.Locale.US).endsWith(".glsl")) {
            k = k.substring(0, k.length() - 5);
        }
        return k;
    }

    private static String prprPresetFileForKey(String key) {
        if (key == null) return null;
        switch (key) {
            case "chromatic":
                return "chromatic.glsl";
            case "circleBlur":
            case "circle_blur":
                return "circle_blur.glsl";
            case "fisheye":
                return "fisheye.glsl";
            case "glitch":
                return "glitch.glsl";
            case "grayscale":
                return "grayscale.glsl";
            case "noise":
                return "noise.glsl";
            case "pixel":
                return "pixel.glsl";
            case "radialBlur":
            case "radial_blur":
                return "radial_blur.glsl";
            case "shockwave":
                return "shockwave.glsl";
            case "vignette":
                return "vignette.glsl";
            default:
                return null;
        }
    }

    private String readAssetText(String assetPath) {
        try (InputStream is = context.getAssets().open(assetPath)) {
            ByteArrayOutputStream baos = new ByteArrayOutputStream();
            byte[] buf = new byte[4096];
            int n;
            while ((n = is.read(buf)) != -1) {
                baos.write(buf, 0, n);
            }
            return baos.toString(StandardCharsets.UTF_8.name());
        } catch (IOException e) {
            return null;
        }
    }

    private static Map<String, float[]> parsePrprUniformDefaults(String src) {
        Map<String, float[]> out = new HashMap<>();
        if (src == null) return out;
        // Example: uniform float strength; // %0.25%
        // Example: uniform vec4 tint; // %1, 0, 0, 1%
        Pattern p = Pattern.compile("uniform\\s+(\\w+)\\s+(\\w+)\\s*;\\s*//\\s*%([^%]+)%");
        Matcher m = p.matcher(src);
        while (m.find()) {
            String type = m.group(1);
            String name = m.group(2);
            String raw = m.group(3);
            if (type == null || name == null || raw == null) continue;

            String[] parts = raw.split(",");
            float[] vals = new float[parts.length];
            int cnt = 0;
            for (String part : parts) {
                String s = part.trim();
                if (s.isEmpty()) continue;
                try {
                    vals[cnt++] = Float.parseFloat(s);
                } catch (NumberFormatException ignore) {
                }
            }
            if (cnt <= 0) continue;
            float[] finalVals = new float[cnt];
            System.arraycopy(vals, 0, finalVals, 0, cnt);

            // Only keep sizes that make sense for our setter.
            if ("float".equals(type)) {
                out.put(name, new float[]{finalVals[0]});
            } else if ("vec2".equals(type) && finalVals.length >= 2) {
                out.put(name, new float[]{finalVals[0], finalVals[1]});
            } else if ("vec4".equals(type) && finalVals.length >= 4) {
                out.put(name, new float[]{finalVals[0], finalVals[1], finalVals[2], finalVals[3]});
            }
        }
        return out;
    }

    private static String preprocessPrprFragment(@NonNull String source) {
        // Strip any #version directive. We assume GLSL ES 1.00 (OpenGL ES 2.0) for runtime compilation.
        StringBuilder sb = new StringBuilder();
        String[] lines = source.replace("\r", "").split("\n");
        for (String line : lines) {
            String t = line.trim();
            if (t.startsWith("#version")) continue;
            sb.append(line).append('\n');
        }

        String code = sb.toString();

        int mainIndex = code.indexOf("void main");
        if (mainIndex < 0) return code;

        String preMain = code.substring(0, mainIndex);
        String postMain = code.substring(mainIndex);

        // Only rewrite variable initializers at GLOBAL SCOPE (braceDepth==0).
        // Do NOT touch helper functions declared before main (MoveCamera.fs etc. rely on local initializers).
        StringBuilder newPre = new StringBuilder(preMain.length());
        StringBuilder initCode = new StringBuilder();

        String[] preLines = preMain.split("\n", -1);
        int braceDepth = 0;
        Pattern declInit = Pattern.compile("^\\s*(?!const\\b)(mat[234]|vec[234]|float|int|bool)\\s+(\\w+)\\s*=\\s*([^;]+);\\s*$");

        for (String raw : preLines) {
            // Determine if this line is in global scope BEFORE applying it.
            boolean atGlobal = (braceDepth == 0);

            Matcher mm = atGlobal ? declInit.matcher(raw) : null;
            if (mm != null && mm.matches()) {
                newPre.append(mm.group(1)).append(' ').append(mm.group(2)).append(';').append('\n');
                initCode.append(mm.group(2)).append(" = ").append(mm.group(3)).append(";\n");
            } else {
                newPre.append(raw).append('\n');
            }

            // Update brace depth (simple, but sufficient for prpr shaders)
            for (int i = 0; i < raw.length(); i++) {
                char c = raw.charAt(i);
                if (c == '{') braceDepth++;
                else if (c == '}') braceDepth--;
            }
            if (braceDepth < 0) braceDepth = 0;
        }

        if (initCode.length() > 0) {
            postMain = postMain.replaceFirst("\\{", "{\n" + initCode.toString());
            return newPre.toString() + postMain;
        }
        return code;
    }

    private Texture createWhiteTexture() {
        Texture t = new Texture();
        int[] ids = new int[1];
        GLES20.glGenTextures(1, ids, 0);
        t.id = ids[0];
        t.width = 1;
        t.height = 1;

        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, t.id);
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_LINEAR);
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_LINEAR);
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_S, GLES20.GL_CLAMP_TO_EDGE);
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_T, GLES20.GL_CLAMP_TO_EDGE);

        ByteBuffer b = ByteBuffer.allocateDirect(4);
        b.put((byte) 0xFF).put((byte) 0xFF).put((byte) 0xFF).put((byte) 0xFF);
        b.position(0);
        GLES20.glTexImage2D(GLES20.GL_TEXTURE_2D, 0, GLES20.GL_RGBA, 1, 1, 0,
                GLES20.GL_RGBA, GLES20.GL_UNSIGNED_BYTE, b);

        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, 0);
        return t;
    }

    private Texture createEmptyTexture(int w, int h) {
        Texture t = new Texture();
        int[] ids = new int[1];
        GLES20.glGenTextures(1, ids, 0);
        t.id = ids[0];
        t.width = w;
        t.height = h;

        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, t.id);
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_LINEAR);
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_LINEAR);
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_S, GLES20.GL_CLAMP_TO_EDGE);
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_T, GLES20.GL_CLAMP_TO_EDGE);
        GLES20.glTexImage2D(GLES20.GL_TEXTURE_2D, 0, GLES20.GL_RGBA, w, h, 0, GLES20.GL_RGBA,
                GLES20.GL_UNSIGNED_BYTE, null);
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, 0);
        return t;
    }

    private int createFboForTexture(int texId) {
        int[] ids = new int[1];
        GLES20.glGenFramebuffers(1, ids, 0);
        int fbo = ids[0];
        GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, fbo);
        GLES20.glFramebufferTexture2D(GLES20.GL_FRAMEBUFFER, GLES20.GL_COLOR_ATTACHMENT0,
                GLES20.GL_TEXTURE_2D, texId, 0);
        int status = GLES20.glCheckFramebufferStatus(GLES20.GL_FRAMEBUFFER);
        if (status != GLES20.GL_FRAMEBUFFER_COMPLETE) {
            // still return but may not work
        }
        GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, 0);
        return fbo;
    }

    private Texture loadTextureFromAssetsSafe(String assetPath) {
        return loadTextureFromAssetsSafe(assetPath, false);
    }

    private Texture loadTextureFromAssetsSafe(String assetPath, boolean forceWhite) {
        try (InputStream is = context.getAssets().open(assetPath)) {
            Bitmap bmp = BitmapFactory.decodeStream(is);
            if (bmp == null) return null;
            if (forceWhite) {
                bmp = forceBitmapWhite(bmp);
            }
            return uploadBitmapAsTexture(bmp);
        } catch (IOException e) {
            return null;
        }
    }

    /** Force RGB to white while preserving alpha. */
    private static Bitmap forceBitmapWhite(Bitmap src) {
        try {
            int w = src.getWidth();
            int h = src.getHeight();
            int[] pixels = new int[w * h];
            src.getPixels(pixels, 0, w, 0, 0, w, h);
            for (int i = 0; i < pixels.length; i++) {
                int a = (pixels[i] >>> 24) & 0xFF;
                pixels[i] = (a << 24) | 0x00FFFFFF;
            }
            Bitmap out = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888);
            out.setPixels(pixels, 0, w, 0, 0, w, h);
            src.recycle();
            return out;
        } catch (Throwable t) {
            // if anything goes wrong, fall back to original
            return src;
        }
    }

    private Texture loadTextureFromFileSafe(String filePath) {
        return loadTextureFromFileSafe(filePath, false);
    }

    private Texture loadTextureFromFileSafe(String filePath, boolean retainBitmap) {
        try {
            Bitmap bmp = BitmapFactory.decodeFile(filePath);
            if (bmp == null) return null;
            Texture t = uploadBitmapAsTexture(bmp, retainBitmap);
            if (t != null) {
                t.filePath = filePath;
            }
            return t;
        } catch (Exception e) {
            return null;
        }
    }

    private Texture loadGifTexture(String filePath) {
        // Fast path: use pre-decoded frames from cache
        PreloadedGif cached = preloadedGifCache.get(filePath);
        if (cached != null) {
            preloadedGifCache.remove(filePath); // transfer ownership, free cache entry
            return uploadGifFramesFromPreloaded(cached, filePath);
        }

        // Slow path: decode synchronously (shouldn't normally be reached after preloading)
        try {
            PreloadedGif pg = decodeGifFrames(filePath);
            if (pg != null) {
                return uploadGifFramesFromPreloaded(pg, filePath);
            }
        } catch (Exception e) {
            // Fall through to static bitmap fallback
        }
        // Fallback: load as static bitmap
        return loadTextureFromFileSafe(filePath, true);
    }

    /** Upload pre-decoded GIF frames as individual GL textures and build the Texture object. */
    private Texture uploadGifFramesFromPreloaded(PreloadedGif pg, String filePath) {
        if (pg == null || pg.frameBitmaps == null) return null;
        Texture t = new Texture();
        t.filePath = filePath;
        t.isGif = true;
        t.gifFrameCount = pg.frameCount;
        t.gifFrameTexIds = new int[pg.frameCount];
        t.gifFrameWidths = new int[pg.frameCount];
        t.gifFrameHeights = new int[pg.frameCount];
        t.gifFrameDurationsMs = pg.frameDurationsMs;
        t.gifTotalDurationMs = pg.totalDurationMs;
        t.gifFrameBitmaps = pg.frameBitmaps;
        t.contextVersion = glContextVersion;

        Bitmap firstBmp = pg.frameBitmaps[0];
        t.width = firstBmp.getWidth();
        t.height = firstBmp.getHeight();

        // Generate dummy main texture ID
        int[] ids = new int[1];
        GLES20.glGenTextures(1, ids, 0);
        t.id = ids[0];
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, t.id);
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_LINEAR);
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_LINEAR);
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_S, GLES20.GL_CLAMP_TO_EDGE);
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_T, GLES20.GL_CLAMP_TO_EDGE);
        android.opengl.GLUtils.texImage2D(GLES20.GL_TEXTURE_2D, 0, firstBmp, 0);
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, 0);

        // Upload each frame as a separate GL texture
        for (int i = 0; i < pg.frameCount; i++) {
            Bitmap bmp = pg.frameBitmaps[i];
            if (bmp == null || bmp.isRecycled()) continue;
            int[] frameIds = new int[1];
            GLES20.glGenTextures(1, frameIds, 0);
            t.gifFrameTexIds[i] = frameIds[0];
            t.gifFrameWidths[i] = bmp.getWidth();
            t.gifFrameHeights[i] = bmp.getHeight();

            GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, frameIds[0]);
            GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_LINEAR);
            GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_LINEAR);
            GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_S, GLES20.GL_CLAMP_TO_EDGE);
            GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_T, GLES20.GL_CLAMP_TO_EDGE);
            android.opengl.GLUtils.texImage2D(GLES20.GL_TEXTURE_2D, 0, bmp, 0);
        }
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, 0);

        return t;
    }

    private Texture uploadBitmapAsTexture(Bitmap bmp) {
        return uploadBitmapAsTexture(bmp, false);
    }

    private Texture uploadBitmapAsTexture(Bitmap bmp, boolean retainBitmap) {
        Texture t = new Texture();
        t.width = bmp.getWidth();
        t.height = bmp.getHeight();
        t.contextVersion = glContextVersion;

        int[] ids = new int[1];
        GLES20.glGenTextures(1, ids, 0);
        t.id = ids[0];

        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, t.id);
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_LINEAR);
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_LINEAR);
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_S, GLES20.GL_CLAMP_TO_EDGE);
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_T, GLES20.GL_CLAMP_TO_EDGE);

        android.opengl.GLUtils.texImage2D(GLES20.GL_TEXTURE_2D, 0, bmp, 0);
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, 0);

        if (retainBitmap) {
            t.retainedBitmap = bmp;
        } else {
            bmp.recycle();
        }
        return t;
    }

    private ResPackInfo loadResPackFromAssets(String assetPath) {
        try (InputStream is = context.getAssets().open(assetPath)) {
            byte[] data = new byte[is.available()];
            int read = is.read(data);
            if (read <= 0) return null;
            String json = new String(data);
            return JSON_MAPPER.readValue(json, ResPackInfo.class);
        } catch (IOException e) {
            return null;
        }
    }

    private static FloatBuffer createQuadPos() {
        // unit quad centered at (0,0): (-0.5,-0.5) .. (0.5,0.5)
        float[] v = new float[]{
                -0.5f, -0.5f,
                0.5f, -0.5f,
                -0.5f, 0.5f,
                0.5f, 0.5f
        };
        ByteBuffer bb = ByteBuffer.allocateDirect(v.length * 4);
        bb.order(ByteOrder.nativeOrder());
        FloatBuffer fb = bb.asFloatBuffer();
        fb.put(v);
        fb.position(0);
        return fb;
    }

    private static FloatBuffer createNdcQuadPos() {
        float[] v = new float[]{
                -1f, -1f,
                1f, -1f,
                -1f, 1f,
                1f, 1f
        };
        ByteBuffer bb = ByteBuffer.allocateDirect(v.length * 4);
        bb.order(ByteOrder.nativeOrder());
        FloatBuffer fb = bb.asFloatBuffer();
        fb.put(v);
        fb.position(0);
        return fb;
    }

    private static FloatBuffer createQuadUv() {
        // top-based uv: (0,0) top-left, (1,1) bottom-right
        float[] v = new float[]{
                0f, 1f,
                1f, 1f,
                0f, 0f,
                1f, 0f
        };
        // NOTE: we will overwrite per draw, but keep buffer size.
        ByteBuffer bb = ByteBuffer.allocateDirect(v.length * 4);
        bb.order(ByteOrder.nativeOrder());
        FloatBuffer fb = bb.asFloatBuffer();
        fb.put(v);
        fb.position(0);
        return fb;
    }

    private void updateQuadUv(float u0, float v0, float u1, float v1) {
        // Vertex order (triangle strip) in createQuadPos():
        // 0: (-0.5, -0.5)
        // 1: ( 0.5, -0.5)
        // 2: (-0.5,  0.5)
        // 3: ( 0.5,  0.5)
        //
        // With our projection (y-down like Canvas), this corresponds to:
        // 0: top-left, 1: top-right, 2: bottom-left, 3: bottom-right.
        //
        // We intentionally use a Canvas-like UV convention everywhere in the renderer:
        // v = 0 at TOP of the bitmap, v = 1 at BOTTOM of the bitmap.
        //
        // On Android, GLUtils.texImage2D uploads bitmap rows top-to-bottom, so the bitmap's
        // top row ends up at texture v=0. Therefore we do NOT need an extra v flip here.
        tmpUv[0] = u0; tmpUv[1] = v0; // top-left
        tmpUv[2] = u1; tmpUv[3] = v0; // top-right
        tmpUv[4] = u0; tmpUv[5] = v1; // bottom-left
        tmpUv[6] = u1; tmpUv[7] = v1; // bottom-right
        quadUv.position(0);
        quadUv.put(tmpUv);
        quadUv.position(0);
    }

    /**
     * Update quadUv for the fullscreen NDC quad used by post-process passes (blur / prpr shaders).
     *
     * Vertex order in {@link #ndcPos} (triangle strip):
     *   0: (-1, -1) bottom-left
     *   1: ( 1, -1) bottom-right
     *   2: (-1,  1) top-left
     *   3: ( 1,  1) top-right
     *
     * We keep a Canvas-like UV convention where v=0 is "top".
     */
    private void updateQuadUvNdc(float u0, float v0, float u1, float v1) {
        // order: bottom-left, bottom-right, top-left, top-right
        tmpUv[0] = u0; tmpUv[1] = v1;
        tmpUv[2] = u1; tmpUv[3] = v1;
        tmpUv[4] = u0; tmpUv[5] = v0;
        tmpUv[6] = u1; tmpUv[7] = v0;
        quadUv.position(0);
        quadUv.put(tmpUv);
        quadUv.position(0);
    }

    private int buildProgram(String vs, String fs) {
        int v = compileShader(GLES20.GL_VERTEX_SHADER, vs);
        int f = compileShader(GLES20.GL_FRAGMENT_SHADER, fs);
        int p = GLES20.glCreateProgram();
        GLES20.glAttachShader(p, v);
        GLES20.glAttachShader(p, f);
        GLES20.glLinkProgram(p);
        int[] link = new int[1];
        GLES20.glGetProgramiv(p, GLES20.GL_LINK_STATUS, link, 0);
        if (link[0] == 0) {
            String log = GLES20.glGetProgramInfoLog(p);
            GLES20.glDeleteProgram(p);
            return 0;
        }
        GLES20.glDeleteShader(v);
        GLES20.glDeleteShader(f);
        return p;
    }

    private int compileShader(int type, String src) {
        int s = GLES20.glCreateShader(type);
        GLES20.glShaderSource(s, src);
        GLES20.glCompileShader(s);
        int[] ok = new int[1];
        GLES20.glGetShaderiv(s, GLES20.GL_COMPILE_STATUS, ok, 0);
        if (ok[0] == 0) {
            String log = GLES20.glGetShaderInfoLog(s);
            GLES20.glDeleteShader(s);
            return 0;
        }
        return s;
    }

    // ---------------- Shaders ----------------

    /**
     * Fullscreen quad vertex shader (NDC positions, no uMVP).
     * texCoord 采用“v=0 在顶部”的 canvas 风格（与本渲染器一致）。
     */
    private static final String VS_FULLSCREEN =
            "attribute vec2 aPosition;\n" +
            "attribute vec2 aTexCoord;\n" +
            "varying vec2 vTex;\n" +
            "void main(){\n" +
            "  gl_Position = vec4(aPosition, 0.0, 1.0);\n" +
            "  // aTexCoord is generated by updateQuadUv() using a Canvas-like v convention.\n" +
            "  vTex = aTexCoord;\n" +
            "}\n";

    /**
     * prpr effect vertex shader.
     * <p>
     * Preset/custom prpr fragment shaders expect a varying named <code>uv</code>
     * and sample a uniform named <code>screenTexture</code>.
     */
    private static final String VS_PRPR =
            "attribute vec2 aPosition;\n" +
            "attribute vec2 aTexCoord;\n" +
            "varying lowp vec2 uv;\n" +
            "uniform vec2 UVScale;\n" +
            "void main(){\n" +
            "  gl_Position = vec4(aPosition, 0.0, 1.0);\n" +
            "  uv = (aTexCoord - vec2(0.5)) * UVScale + vec2(0.5);\n" +
            "}\n";
    private static final String VS_SPRITE =
            "attribute vec2 aPosition;\n" +
            "attribute vec2 aTexCoord;\n" +
            "uniform mat4 uMVP;\n" +
            "varying vec2 vTex;\n" +
            "void main(){\n" +
            "  gl_Position = uMVP * vec4(aPosition, 0.0, 1.0);\n" +
            "  // aTexCoord 使用的是“v=0 在顶部”的坐标（与 Canvas 一致），无需再翻转\n" +
            "  vTex = aTexCoord;\n" +
            "}\n";

    private static final String FS_SPRITE =
            "precision mediump float;\n" +
            "uniform sampler2D uTexture;\n" +
            "uniform vec4 uColor;\n" +
            "varying vec2 vTex;\n" +
            "void main(){\n" +
            "  vec4 tex = texture2D(uTexture, vTex);\n" +
            "  // Premultiplied-alpha output to match Canvas/Android bitmap behavior\n" +
            "  vec4 outC;\n" +
            "  outC.a = tex.a * uColor.a;\n" +
            "  outC.rgb = tex.rgb * uColor.rgb * uColor.a;\n" +
            "  gl_FragColor = outC;\n" +
            "}\n";

    private static final String FS_BLUR =
            "precision mediump float;\n" +
            "uniform sampler2D uTexture;\n" +
            "uniform vec2 uTexelOffset;\n" +
            "varying vec2 vTex;\n" +
            "void main(){\n" +
            "  vec4 sum = vec4(0.0);\n" +
            "  sum += texture2D(uTexture, vTex) * 0.227027;\n" +
            "  sum += texture2D(uTexture, vTex + uTexelOffset * 1.384615) * 0.316216;\n" +
            "  sum += texture2D(uTexture, vTex - uTexelOffset * 1.384615) * 0.316216;\n" +
            "  sum += texture2D(uTexture, vTex + uTexelOffset * 3.230769) * 0.070270;\n" +
            "  sum += texture2D(uTexture, vTex - uTexelOffset * 3.230769) * 0.070270;\n" +
            "  gl_FragColor = sum;\n" +
            "}\n";

    private static final String VS_BATCH =
            "uniform mat4 uProjection;\n" +
            "attribute vec2 aPosition;\n" +
            "attribute vec2 aTexCoord;\n" +
            "attribute vec4 aColor;\n" +
            "varying vec2 vTex;\n" +
            "varying vec4 vColor;\n" +
            "void main(){\n" +
            "  gl_Position = uProjection * vec4(aPosition, 0.0, 1.0);\n" +
            "  vTex = aTexCoord;\n" +
            "  vColor = aColor;\n" +
            "}\n";

    private static final String FS_BATCH =
            "precision mediump float;\n" +
            "uniform sampler2D uTexture;\n" +
            "varying vec2 vTex;\n" +
            "varying vec4 vColor;\n" +
            "void main(){\n" +
            "  vec4 tex = texture2D(uTexture, vTex);\n" +
            "  vec4 outC;\n" +
            "  outC.a = tex.a * vColor.a;\n" +
            "  outC.rgb = tex.rgb * vColor.rgb * vColor.a;\n" +
            "  gl_FragColor = outC;\n" +
            "}\n";
}
