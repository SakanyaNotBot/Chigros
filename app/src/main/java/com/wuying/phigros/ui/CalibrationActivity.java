package com.wuying.phigros.ui;

import android.annotation.SuppressLint;
import android.content.Intent;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.LinearGradient;
import android.graphics.Paint;
import android.graphics.PorterDuff;
import android.graphics.PorterDuffColorFilter;
import android.graphics.RectF;
import android.graphics.Shader;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.view.WindowManager;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.Nullable;
import androidx.appcompat.app.AppCompatActivity;

import com.google.android.material.button.MaterialButton;
import com.google.android.material.slider.Slider;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.wuying.phigros.R;
import com.wuying.phigros.audio.NativeAudioEngine;
import com.wuying.phigros.game.GameConstants;
import com.wuying.phigros.game.ResPackInfo;
import com.wuying.phigros.util.PcmDecoder;
import com.wuying.phigros.util.WavDecoder;

import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.Random;
public class CalibrationActivity extends AppCompatActivity {

    public static final String EXTRA_AUDIO_OFFSET_MS = "extra_audio_offset_ms";
    private static final ObjectMapper JSON_MAPPER = new ObjectMapper()
            .configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);

    private final Handler handler = new Handler(Looper.getMainLooper());
    private CalibrationView calibrationView;
    private Slider slider;
    private TextView valueText;
    private int audioOffsetMs = 0;
    private boolean suppress = false;
    private boolean audioReady = false;

    private final Runnable ticker = new Runnable() {
        @Override
        public void run() {
            if (audioReady) {
                double duration = NativeAudioEngine.getMusicDurationSeconds();
                double pos = NativeAudioEngine.getPlayheadSeconds();
                if (duration > 0.0 && pos >= duration - 0.015) {
                    NativeAudioEngine.restart();
                }
                if (calibrationView != null) {
                    calibrationView.invalidate();
                }
                handler.postDelayed(this, 16L);
            }
        }
    };

    @Override
    protected void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        applyImmersiveMode();

        audioOffsetMs = clamp(getIntent().getIntExtra(EXTRA_AUDIO_OFFSET_MS, 0), -500, 500);
        buildUi();

        try {
            loadAudio();
            audioReady = true;
            NativeAudioEngine.start();
            handler.removeCallbacks(ticker);
            handler.post(ticker);
        } catch (Throwable e) {
            Toast.makeText(this, getString(R.string.calibration_load_failed, e.getMessage()), Toast.LENGTH_LONG).show();
            finishWithResult();
        }
    }

    private void buildUi() {
        FrameLayout root = new FrameLayout(this);
        root.setBackgroundColor(0xFF10131A);

        calibrationView = new CalibrationView(this);
        calibrationView.setAudioOffsetMs(audioOffsetMs);
        int panelWidth = dp(320);
        calibrationView.setPanelWidthPx(panelWidth);
        root.addView(calibrationView, new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT));

        LinearLayout panel = new LinearLayout(this);
        panel.setOrientation(LinearLayout.VERTICAL);
        panel.setGravity(Gravity.CENTER_HORIZONTAL);
        panel.setPadding(dp(20), dp(18), dp(20), dp(18));
        panel.setBackgroundColor(0xCC1B222D);
        FrameLayout.LayoutParams panelLp = new FrameLayout.LayoutParams(panelWidth, FrameLayout.LayoutParams.MATCH_PARENT);
        panelLp.gravity = Gravity.END;

        TextView title = new TextView(this);
        title.setText(R.string.calibration_title);
        title.setTextColor(0xFFF3F7FB);
        title.setTextSize(20);
        title.setGravity(Gravity.CENTER);
        title.setTypeface(android.graphics.Typeface.DEFAULT_BOLD);
        panel.addView(title, matchWrap());

        TextView hint = new TextView(this);
        hint.setText(R.string.calibration_hint);
        hint.setTextColor(0xFFA9B4C2);
        hint.setTextSize(13);
        hint.setGravity(Gravity.CENTER);
        hint.setLineSpacing(dp(2), 1f);
        LinearLayout.LayoutParams hintLp = matchWrap();
        hintLp.topMargin = dp(14);
        panel.addView(hint, hintLp);

        valueText = new TextView(this);
        valueText.setTextColor(0xFFF3F7FB);
        valueText.setTextSize(28);
        valueText.setGravity(Gravity.CENTER);
        valueText.setTypeface(android.graphics.Typeface.DEFAULT_BOLD);
        LinearLayout.LayoutParams valueLp = matchWrap();
        valueLp.topMargin = dp(24);
        panel.addView(valueText, valueLp);

        slider = new Slider(this);
        slider.setValueFrom(-500f);
        slider.setValueTo(500f);
        slider.setStepSize(1f);
        slider.addOnChangeListener((s, value, fromUser) -> {
            if (suppress) return;
            setAudioOffsetMs(Math.round(value), true);
        });
        LinearLayout.LayoutParams sliderLp = matchWrap();
        sliderLp.topMargin = dp(12);
        panel.addView(slider, sliderLp);

        LinearLayout stepRow = new LinearLayout(this);
        stepRow.setOrientation(LinearLayout.HORIZONTAL);
        stepRow.setGravity(Gravity.CENTER);
        LinearLayout.LayoutParams stepLp = matchWrap();
        stepLp.topMargin = dp(16);
        panel.addView(stepRow, stepLp);
        addStepButton(stepRow, "-50", -50);
        addStepButton(stepRow, "-5", -5);
        addStepButton(stepRow, "+5", 5);
        addStepButton(stepRow, "+50", 50);

        View spacer = new View(this);
        panel.addView(spacer, new LinearLayout.LayoutParams(1, 0, 1f));

        MaterialButton reset = makeButton(getString(R.string.calibration_reset));
        reset.setOnClickListener(v -> setAudioOffsetMs(0, true));
        LinearLayout.LayoutParams resetLp = matchWrap();
        resetLp.bottomMargin = dp(10);
        panel.addView(reset, resetLp);

        MaterialButton done = makeButton(getString(R.string.calibration_done));
        done.setOnClickListener(v -> finishWithResult());
        panel.addView(done, matchWrap());

        root.addView(panel, panelLp);
        setContentView(root);
        refreshValueViews();
    }

    private void addStepButton(LinearLayout row, String label, int delta) {
        MaterialButton btn = makeButton(label);
        btn.setMinWidth(0);
        btn.setOnClickListener(v -> setAudioOffsetMs(audioOffsetMs + delta, true));
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f);
        lp.leftMargin = dp(3);
        lp.rightMargin = dp(3);
        row.addView(btn, lp);
    }

    private MaterialButton makeButton(String text) {
        MaterialButton btn = new MaterialButton(this);
        btn.setText(text);
        btn.setAllCaps(false);
        btn.setTextColor(0xFFF3F7FB);
        btn.setBackgroundTintList(android.content.res.ColorStateList.valueOf(0xFF263343));
        btn.setStrokeColor(android.content.res.ColorStateList.valueOf(0xFF4A5A6E));
        btn.setStrokeWidth(dp(1));
        btn.setCornerRadius(dp(8));
        return btn;
    }

    private LinearLayout.LayoutParams matchWrap() {
        return new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT);
    }

    private void setAudioOffsetMs(int value, boolean updateSlider) {
        audioOffsetMs = clamp(value, -500, 500);
        if (calibrationView != null) {
            calibrationView.setAudioOffsetMs(audioOffsetMs);
        }
        if (updateSlider) {
            refreshValueViews();
        }
    }

    private void refreshValueViews() {
        if (valueText != null) {
            valueText.setText(getString(R.string.calibration_offset_format, audioOffsetMs));
        }
        if (slider != null) {
            suppress = true;
            slider.setValue(audioOffsetMs);
            suppress = false;
        }
    }

    private void loadAudio() throws IOException {
        NativeAudioEngine.create();
        NativeAudioEngine.stop();
        PcmDecoder.DecodedAudio cali = WavDecoder.decodeRawWavToFloatPcm(this, R.raw.calibration);
        PcmDecoder.DecodedAudio hit = WavDecoder.decodeRawWavToFloatPcm(this, R.raw.calibration_hit);
        NativeAudioEngine.setMusicData(cali.pcm, cali.length, cali.sampleRate, cali.channels);
        NativeAudioEngine.setSfxData(GameConstants.NOTE_TAP, hit.pcm, hit.sampleRate, hit.channels);
        NativeAudioEngine.setPlaybackSpeed(1.0f);
        NativeAudioEngine.setMusicVolume(1.0f);
        NativeAudioEngine.setSfxVolume(1.0f);
    }

    private void finishWithResult() {
        Intent data = new Intent();
        data.putExtra(EXTRA_AUDIO_OFFSET_MS, audioOffsetMs);
        setResult(RESULT_OK, data);
        finish();
    }

    @SuppressLint("GestureBackNavigation")
    @Override
    public void onBackPressed() {
        finishWithResult();
    }

    @Override
    protected void onPause() {
        super.onPause();
        handler.removeCallbacks(ticker);
        if (audioReady) NativeAudioEngine.pause(true);
    }

    @Override
    protected void onResume() {
        super.onResume();
        applyImmersiveMode();
        if (audioReady) {
            NativeAudioEngine.pause(false);
            handler.removeCallbacks(ticker);
            handler.post(ticker);
        }
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        handler.removeCallbacksAndMessages(null);
        audioReady = false;
        try {
            NativeAudioEngine.stop();
            NativeAudioEngine.delete();
        } catch (Throwable ignored) {
        }
    }

    private void applyImmersiveMode() {
        try {
            getWindow().addFlags(WindowManager.LayoutParams.FLAG_FULLSCREEN);
            View decor = getWindow().getDecorView();
            int flags = View.SYSTEM_UI_FLAG_LAYOUT_STABLE
                    | View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
                    | View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
                    | View.SYSTEM_UI_FLAG_FULLSCREEN
                    | View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
                    | View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY;
            decor.setSystemUiVisibility(flags);
        } catch (Exception ignored) {
        }
    }

    private int dp(float value) {
        return Math.round(value * getResources().getDisplayMetrics().density);
    }

    private static int clamp(int v, int lo, int hi) {
        if (v < lo) return lo;
        if (v > hi) return hi;
        return v;
    }

    private static final class CalibrationView extends View {
        private static final int FALLBACK_HIT_FX_COLS = 6;
        private static final int FALLBACK_HIT_FX_ROWS = 5;

        private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint bitmapPaint = new Paint(Paint.ANTI_ALIAS_FLAG | Paint.DITHER_FLAG);
        private final Paint hitFxPaint = new Paint(Paint.ANTI_ALIAS_FLAG | Paint.DITHER_FLAG | Paint.FILTER_BITMAP_FLAG);
        private final RectF dstRect = new RectF();
        private final ArrayList<CalibrationHitEffect> hitEffects = new ArrayList<>();
        private final Random random = new Random(0xC411B47E);
        private final Bitmap tapBitmap;
        private final Bitmap[] hitFxFrames;
        private final int hitFxFrameCount;
        private int audioOffsetMs = 0;
        private int panelWidthPx = 0;
        private boolean wasBeforeHit = false;
        private long touchMarkerStartMs = -1L;
        private float touchMarkerY = 0f;
        private final boolean apfcIndicator;
        private LinearGradient bgGradient;
        private int bgW = -1;
        private int bgH = -1;

        CalibrationView(CalibrationActivity context) {
            super(context);
            setClickable(true);
            apfcIndicator = ChartSelectActivity.isApfcIndicatorEnabled(context);
            tapBitmap = loadAssetBitmap(context, "res/click.png");
            int[] hitFxGrid = loadHitFxGrid(context);
            hitFxFrames = splitHitFxFrames(loadAssetBitmap(context, "res/hit_fx.png"), hitFxGrid[0], hitFxGrid[1]);
            hitFxFrameCount = hitFxFrames == null ? 0 : hitFxFrames.length;
        }

        void setAudioOffsetMs(int audioOffsetMs) {
            this.audioOffsetMs = audioOffsetMs;
            invalidate();
        }

        void setPanelWidthPx(int panelWidthPx) {
            this.panelWidthPx = panelWidthPx;
        }

        @Override
        protected void onDraw(Canvas canvas) {
            super.onDraw(canvas);
            int w = getWidth();
            int h = getHeight();
            if (w <= 0 || h <= 0) return;
            if (bgGradient == null || bgW != w || bgH != h) {
                bgW = w;
                bgH = h;
                bgGradient = new LinearGradient(0, 0, 0, h,
                        new int[]{0xFF10131A, 0xFF151A23, 0xFF0D1016},
                        null, Shader.TileMode.CLAMP);
            }
            paint.setAlpha(255);
            paint.setColor(Color.WHITE);
            paint.setShader(bgGradient);
            canvas.drawRect(0, 0, w, h, paint);
            paint.setShader(null);
            paint.setAlpha(255);

            float playRight = Math.max(w * 0.55f, w - panelWidthPx);
            float centerX = playRight * 0.5f;
            float lineY = h * 0.58f;
            float travel = h * 0.44f;
            float noteW = playRight * 0.1234375f;
            float noteH = tapBitmap != null
                    ? noteW * tapBitmap.getHeight() / Math.max(1f, tapBitmap.getWidth())
                    : noteW * 0.36f;

            paint.setColor(indicatorColor(apfcIndicator));
            canvas.drawRect(centerX - playRight * 0.28f, lineY - dp(2), centerX + playRight * 0.28f, lineY + dp(2), paint);

            double cycle = NativeAudioEngine.getPlayheadSeconds() - audioOffsetMs / 1000.0;
            cycle = cycle - Math.floor(cycle / 2.0) * 2.0;
            if (cycle < 0.0) cycle += 2.0;

            if (cycle <= 1.0) {
                float y = (float) (lineY - travel + travel * cycle);
                drawTap(canvas, centerX, y, noteW, noteH);
                wasBeforeHit = true;
            } else {
                if (wasBeforeHit) {
                    NativeAudioEngine.triggerSfx(GameConstants.NOTE_TAP);
                    hitEffects.add(new CalibrationHitEffect(centerX, lineY, System.currentTimeMillis(), random));
                }
                wasBeforeHit = false;
            }

            drawHitEffects(canvas, playRight, noteW);
            drawTouchMarker(canvas, centerX, playRight, lineY);
            postInvalidateOnAnimation();
        }

        private void drawTap(Canvas canvas, float x, float y, float noteW, float noteH) {
            if (tapBitmap == null) {
                dstRect.set(x - noteW * 0.5f, y - noteH * 0.5f, x + noteW * 0.5f, y + noteH * 0.5f);
                paint.setColor(0xFF56D6C9);
                canvas.drawRoundRect(dstRect, noteH * 0.5f, noteH * 0.5f, paint);
                return;
            }
            dstRect.set(x - noteW * 0.5f, y - noteH * 0.5f, x + noteW * 0.5f, y + noteH * 0.5f);
            bitmapPaint.setAlpha(255);
            bitmapPaint.setColorFilter(null);
            canvas.drawBitmap(tapBitmap, null, dstRect, bitmapPaint);
        }

        private void drawHitEffects(Canvas canvas, float stageW, float noteWidth) {
            final long now = System.currentTimeMillis();
            final float durMs = 500f;
            final float effectSize = noteWidth * 1.375f * 1.12f;
            final float particleScale = stageW / 4040f * 3f;
            final float baseParticleSize = particleScale * 30f;
            final int perfectColor = perfectColor();

            Iterator<CalibrationHitEffect> iterator = hitEffects.iterator();
            while (iterator.hasNext()) {
                CalibrationHitEffect effect = iterator.next();
                float p = (now - effect.startMs) / durMs;
                if (p >= 1f) {
                    iterator.remove();
                    continue;
                }
                if (p < 0f) continue;

                if (hitFxFrames != null && hitFxFrameCount > 0) {
                    int idx = (int) Math.floor(p * hitFxFrameCount);
                    if (idx < 0) idx = 0;
                    if (idx >= hitFxFrameCount) idx = hitFxFrameCount - 1;
                    Bitmap frame = hitFxFrames[idx];
                    dstRect.set(effect.x - effectSize * 0.5f, effect.y - effectSize * 0.5f,
                            effect.x + effectSize * 0.5f, effect.y + effectSize * 0.5f);
                    hitFxPaint.setAlpha(Math.round(GameConstants.PALPHA * 255f));
                    hitFxPaint.setColorFilter(new PorterDuffColorFilter(perfectColor, PorterDuff.Mode.SRC_IN));
                    canvas.drawBitmap(frame, null, dstRect, hitFxPaint);
                    hitFxPaint.setColorFilter(null);
                    hitFxPaint.setAlpha(255);
                }

                float k = 9f * p / (8f * p + 1f);
                float alpha = 1f - p;
                float size = baseParticleSize * (((0.2078f * p - 1.6524f) * p + 1.6399f) * p + 0.4988f);
                paint.setColor(Color.argb(
                        Math.round(alpha * 255f),
                        Math.round(GameConstants.PCOLOR_R * 255f),
                        Math.round(GameConstants.PCOLOR_G * 255f),
                        Math.round(GameConstants.PCOLOR_B * 255f)));
                for (int i = 0; i < effect.numParts; i++) {
                    float radius = particleScale * effect.effectRBase[i] * k;
                    double rad = effect.effectRotateDeg[i] * Math.PI / 180.0;
                    float px = effect.x + (float) (radius * Math.cos(rad));
                    float py = effect.y + (float) (radius * Math.sin(rad));
                    canvas.drawRect(px - size * 0.5f, py - size * 0.5f, px + size * 0.5f, py + size * 0.5f, paint);
                }
            }
        }

        private void drawTouchMarker(Canvas canvas, float centerX, float playRight, float lineY) {
            long start = touchMarkerStartMs;
            if (start <= 0L) return;
            float elapsed = (System.currentTimeMillis() - start) / 800f;
            if (elapsed >= 1f) {
                touchMarkerStartMs = -1L;
                return;
            }
            float alpha = elapsed <= 0.5f ? 1f : (1f - elapsed) * 2f;
            paint.setColor(Color.argb(Math.round(210 * alpha), 255, 255, 255));
            float half = playRight * 0.22f;
            canvas.drawRect(centerX - half, touchMarkerY - dp(2), centerX + half, touchMarkerY + dp(2), paint);
        }

        @Override
        public boolean onTouchEvent(MotionEvent event) {
            if (event == null) return true;
            if (event.getActionMasked() == MotionEvent.ACTION_DOWN) {
                if (event.getX() < getWidth() - panelWidthPx) {
                    touchMarkerStartMs = System.currentTimeMillis();
                    touchMarkerY = currentNoteY();
                    invalidate();
                }
                return true;
            }
            return true;
        }

        private float currentNoteY() {
            int h = getHeight();
            float lineY = h * 0.58f;
            float travel = h * 0.44f;
            double cycle = NativeAudioEngine.getPlayheadSeconds() - audioOffsetMs / 1000.0;
            cycle = cycle - Math.floor(cycle / 2.0) * 2.0;
            if (cycle < 0.0) cycle += 2.0;
            double visible = Math.min(2.0, Math.max(0.0, cycle));
            return (float) (lineY - travel + travel * visible);
        }

        private float dp(float value) {
            return value * getResources().getDisplayMetrics().density;
        }

        private static int perfectColor() {
            return rgb(GameConstants.PCOLOR);
        }

        private static int indicatorColor(boolean apfcIndicator) {
            if (!apfcIndicator) return Color.WHITE;
            return rgb(GameConstants.AP_INDICATOR_COLOR);
        }

        private static int rgb(float[] color) {
            return Color.rgb(
                    Math.round(color[0] * 255f),
                    Math.round(color[1] * 255f),
                    Math.round(color[2] * 255f));
        }

        @Nullable
        private static Bitmap loadAssetBitmap(CalibrationActivity context, String path) {
            try (InputStream is = context.getAssets().open(path)) {
                return BitmapFactory.decodeStream(is);
            } catch (IOException ignored) {
                return null;
            }
        }

        @Nullable
        private static Bitmap[] splitHitFxFrames(@Nullable Bitmap atlas, int cols, int rows) {
            if (atlas == null) return null;
            if (cols <= 0) cols = FALLBACK_HIT_FX_COLS;
            if (rows <= 0) rows = FALLBACK_HIT_FX_ROWS;
            int frameW = atlas.getWidth() / cols;
            int frameH = atlas.getHeight() / rows;
            if (frameW <= 2 || frameH <= 2) return null;

            Bitmap[] frames = new Bitmap[cols * rows];
            for (int i = 0; i < frames.length; i++) {
                int col = i % cols;
                int row = i / cols;
                frames[i] = Bitmap.createBitmap(atlas, col * frameW + 1, row * frameH + 1, frameW - 2, frameH - 2);
            }
            return frames;
        }

        private static int[] loadHitFxGrid(CalibrationActivity context) {
            try (InputStream is = context.getAssets().open("res/respack.json")) {
                ResPackInfo info = JSON_MAPPER.readValue(is, ResPackInfo.class);
                if (info != null && info.hitFx != null && info.hitFx.length >= 2
                        && info.hitFx[0] > 0 && info.hitFx[1] > 0) {
                    return new int[]{info.hitFx[0], info.hitFx[1]};
                }
            } catch (Throwable ignored) {
            }
            return new int[]{FALLBACK_HIT_FX_COLS, FALLBACK_HIT_FX_ROWS};
        }
    }

    private static final class CalibrationHitEffect {
        final float x;
        final float y;
        final long startMs;
        final int numParts = 4;
        final float[] effectRotateDeg = new float[4];
        final float[] effectRBase = new float[4];

        CalibrationHitEffect(float x, float y, long startMs, Random random) {
            this.x = x;
            this.y = y;
            this.startMs = startMs;
            for (int i = 0; i < 4; i++) {
                effectRotateDeg[i] = random.nextFloat() * 360f;
                effectRBase[i] = 185f + random.nextFloat() * (265f - 185f);
            }
        }
    }
}
