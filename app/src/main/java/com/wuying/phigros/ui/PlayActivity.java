package com.wuying.phigros.ui;

import android.annotation.SuppressLint;
import android.content.Intent;
import android.graphics.Color;
import android.graphics.Typeface;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.util.TypedValue;
import android.view.Display;
import android.view.Gravity;
import android.view.Surface;
import android.view.View;
import android.view.WindowManager;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.TextView;
import android.widget.Toast;

import androidx.activity.OnBackPressedCallback;
import androidx.annotation.Nullable;
import androidx.appcompat.app.AppCompatActivity;

import com.wuying.phigros.R;
import com.wuying.phigros.audio.NativeAudioEngine;
import com.wuying.phigros.game.Chart;
import com.wuying.phigros.game.ChartLoader;
import com.wuying.phigros.game.GameConstants;
import com.wuying.phigros.game.GameGLSurfaceView;
import com.wuying.phigros.game.GameRenderer;
import com.wuying.phigros.game.PlayResult;
import com.wuying.phigros.game.ReplayData;
import com.wuying.phigros.util.PcmDecoder;
import com.wuying.phigros.util.WavDecoder;

import java.io.File;
import java.io.IOException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class PlayActivity extends AppCompatActivity implements GameRenderer.Callback {

    public static final String EXTRA_MUSIC_PATH = "extra_music_path";
    public static final String EXTRA_CHART_PATH = "extra_chart_path";
    public static final String EXTRA_BG_PATH = "extra_bg_path";
    public static final String EXTRA_SONG_NAME = "extra_song_name";
    public static final String EXTRA_DIFFICULTY = "extra_difficulty";
    public static final String EXTRA_ASPECT_RATIO = "extra_aspect_ratio";
    public static final String EXTRA_CHART_OFFSET_MS = "extra_chart_offset_ms";
    public static final String EXTRA_MUSIC_SPEED = "extra_music_speed";
    public static final String EXTRA_KEY_SCALE = "extra_key_scale";
    public static final String EXTRA_SCROLL_SPEED = "extra_scroll_speed";
    public static final String EXTRA_SFX_VOLUME = "extra_sfx_volume";
    public static final String EXTRA_MUSIC_VOLUME = "extra_music_volume";
    public static final String EXTRA_MIRROR_X = "extra_mirror_x";
    public static final String EXTRA_BG_DIM = "extra_bg_dim";
    public static final String EXTRA_LOW_RES = "extra_low_res";
    public static final String EXTRA_ANTIALIAS = "extra_antialias";
    public static final String EXTRA_SHOW_FPS = "extra_show_fps";
    public static final String EXTRA_SHOW_DEBUG = "extra_show_debug";
    public static final String EXTRA_MULTI_HIGHLIGHT = "extra_multi_highlight";
    public static final String EXTRA_APFC = "extra_apfc";
    public static final String EXTRA_AUTOPLAY = "extra_autoplay";
    public static final String EXTRA_CHALLENGE = "extra_challenge";
    public static final String EXTRA_SKIN_PATH = "extra_skin_path";
    public static final String EXTRA_REPLAY_MODE = "extra_replay_mode";
    public static final String EXTRA_REPLAY_PATH = "extra_replay_path";

    private String musicPath;
    private String chartPath;
    private String bgPath;
    private String songName;
    private String difficulty;
    private float aspectRatio = 0f;
    private int chartOffsetMs = 0;
    private float musicSpeed = 1.0f;
    private float keyScale = 1.0f;
    private float scrollSpeed = 1.0f;
    private float sfxVolume = 1.0f;
    private float musicVolume = 1.0f;
    private boolean mirrorX = false;
    private float backgroundDim = 0.6f;
    private boolean lowResMode = false;
    private boolean antialias = false;
    private boolean showFps = false;
    private boolean showDebugInfo = false;
    private boolean multiPressHighlight = true;
    private boolean apfcIndicator = false;
    private boolean autoplay = false;
    private boolean challengeMode = false;

    private boolean replayMode = false;
    private String replayPath;
    private ReplayData replayRecorderData;
    private String replayCachedPath;

    private String skinPath;

    private ExecutorService loaderExecutor;
    private GameGLSurfaceView glView;
    private GameRenderer renderer;
    private FrameLayout loadingView;
    private ProgressBar loadingProgress;
    private TextView loadingText;
    private FrameLayout rootLayout;
    private FrameLayout overlayLayout;

    private TextView tvSongTitle;
    private TextView tvDifficulty;
    private TextView tvScore;
    private LinearLayout comboLayout;
    private TextView tvComboNum;
    private TextView tvComboLabel;

    private Typeface gameTypeface;
    private final Handler uiHandler = new Handler(Looper.getMainLooper());
    private Runnable uiTicker;
    private int lastCombo = -1;
    private int lastScore = -1;

    private float stageL = 0f;
    private float stageT = 0f;
    private float stageW = 0f;
    private float stageH = 0f;

    @Override
    protected void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        applyImmersiveMode();

        musicPath = getIntent().getStringExtra(EXTRA_MUSIC_PATH);
        chartPath = getIntent().getStringExtra(EXTRA_CHART_PATH);
        bgPath = getIntent().getStringExtra(EXTRA_BG_PATH);
        songName = getIntent().getStringExtra(EXTRA_SONG_NAME);
        difficulty = getIntent().getStringExtra(EXTRA_DIFFICULTY);
        aspectRatio = getIntent().getFloatExtra(EXTRA_ASPECT_RATIO, 0f);
        chartOffsetMs = getIntent().getIntExtra(EXTRA_CHART_OFFSET_MS, 0);
        musicSpeed = getIntent().getFloatExtra(EXTRA_MUSIC_SPEED, 1.0f);
        keyScale = getIntent().getFloatExtra(EXTRA_KEY_SCALE, 1.0f);
        scrollSpeed = getIntent().getFloatExtra(EXTRA_SCROLL_SPEED, 1.0f);
        sfxVolume = getIntent().getFloatExtra(EXTRA_SFX_VOLUME, 1.0f);
        musicVolume = getIntent().getFloatExtra(EXTRA_MUSIC_VOLUME, 1.0f);
        mirrorX = getIntent().getBooleanExtra(EXTRA_MIRROR_X, false);
        backgroundDim = getIntent().getFloatExtra(EXTRA_BG_DIM, 0.6f);
        lowResMode = getIntent().getBooleanExtra(EXTRA_LOW_RES, false);
        antialias = getIntent().getBooleanExtra(EXTRA_ANTIALIAS, false);
        showFps = getIntent().getBooleanExtra(EXTRA_SHOW_FPS, false);
        showDebugInfo = getIntent().getBooleanExtra(EXTRA_SHOW_DEBUG, false);
        multiPressHighlight = getIntent().getBooleanExtra(EXTRA_MULTI_HIGHLIGHT, true);
        apfcIndicator = getIntent().getBooleanExtra(EXTRA_APFC, false);
        autoplay = getIntent().getBooleanExtra(EXTRA_AUTOPLAY, false);
        challengeMode = getIntent().getBooleanExtra(EXTRA_CHALLENGE, false);
        skinPath = getIntent().getStringExtra(EXTRA_SKIN_PATH);
        replayMode = getIntent().getBooleanExtra(EXTRA_REPLAY_MODE, false);
        replayPath = getIntent().getStringExtra(EXTRA_REPLAY_PATH);

        if (musicSpeed <= 0f) musicSpeed = 1.0f;
        musicSpeed = Math.max(0.5f, Math.min(musicSpeed, 2.0f));
        if (keyScale <= 0f) keyScale = 1.0f;
        keyScale = Math.max(0.7f, Math.min(keyScale, 1.5f));
        if (scrollSpeed <= 0f) scrollSpeed = 1.0f;
        scrollSpeed = Math.max(0.1f, Math.min(scrollSpeed, 5.0f));
        if (sfxVolume < 0f) sfxVolume = 0f;
        sfxVolume = Math.max(0.0f, Math.min(sfxVolume, 5.0f));
        if (musicVolume < 0f) musicVolume = 0f;
        musicVolume = Math.max(0.0f, Math.min(musicVolume, 5.0f));
        if (!Float.isFinite(backgroundDim)) backgroundDim = 0.6f;
        backgroundDim = Math.max(0.0f, Math.min(backgroundDim, 1.0f));

        try {
            gameTypeface = Typeface.createFromAsset(getAssets(), "res/phigros.ttf");
        } catch (Throwable ignored) {
            gameTypeface = null;
        }

        getOnBackPressedDispatcher().addCallback(this, new OnBackPressedCallback(true) {
            @Override
            public void handleOnBackPressed() {
                if (renderer == null) {
                    finish();
                    return;
                }
                if (!renderer.isMenuVisible()) {
                    renderer.requestPauseMenu();
                }
            }
        });

        setLoadingUi();

        try {
            NativeAudioEngine.create();
        } catch (Throwable ignored) {
        }

        loaderExecutor = Executors.newSingleThreadExecutor();
        loaderExecutor.execute(this::loadAndStart);
    }

    private void setLoadingUi() {
        FrameLayout root = new FrameLayout(this);
        root.setLayoutParams(new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT));

        loadingProgress = new ProgressBar(this);
        FrameLayout.LayoutParams pbLp = new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.WRAP_CONTENT,
                FrameLayout.LayoutParams.WRAP_CONTENT);
        pbLp.gravity = Gravity.CENTER;
        loadingProgress.setLayoutParams(pbLp);

        loadingText = new TextView(this);
        FrameLayout.LayoutParams tvLp = new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.WRAP_CONTENT,
                FrameLayout.LayoutParams.WRAP_CONTENT);
        tvLp.gravity = Gravity.CENTER_HORIZONTAL | Gravity.CENTER_VERTICAL;
        tvLp.topMargin = (int) (64 * getResources().getDisplayMetrics().density);
        loadingText.setLayoutParams(tvLp);
        loadingText.setText("加载中...\n(正在解析谱面与解码音频)");

        root.addView(loadingProgress);
        root.addView(loadingText);
        loadingView = root;
        setContentView(root);
    }

    private void loadAndStart() {
        try {
            ExecutorService parallel = Executors.newFixedThreadPool(2);
            java.util.concurrent.Future<PcmDecoder.DecodedAudio> audioFuture = parallel.submit(() -> {
                try {
                    return PcmDecoder.decodeFileToFloatPcm(musicPath);
                } catch (OutOfMemoryError oom) {
                    throw new IOException("音频文件过大，内存不足无法解码，请尝试更小的音频文件", oom);
                }
            });
            java.util.concurrent.Future<Chart> chartFuture = parallel.submit(() -> {
                try {
                    return ChartLoader.loadFromFile(chartPath);
                } catch (OutOfMemoryError oom) {
                    throw new IOException("谱面文件过大，内存不足无法解析", oom);
                }
            });
            parallel.shutdown();

            PcmDecoder.DecodedAudio musicPcm = audioFuture.get();
            Chart chart = chartFuture.get();

            final int musicSampleRate = Math.max(1, musicPcm.sampleRate);
            final int musicChannels = Math.max(1, musicPcm.channels);
            final double totalTimeSec = musicPcm.getNumFrames() / (double) musicSampleRate;

            NativeAudioEngine.create();
            NativeAudioEngine.setMusicData(musicPcm.pcm, musicPcm.length, musicSampleRate, musicChannels);
            NativeAudioEngine.setPlaybackSpeed(musicSpeed);
            NativeAudioEngine.setSfxVolume(sfxVolume);
            NativeAudioEngine.setMusicVolume(musicVolume);

            musicPcm = null;
            forceGc();

            tryLoadSfxRawWav(GameConstants.NOTE_TAP, R.raw.tap);
            tryLoadSfxRawWav(GameConstants.NOTE_DRAG, R.raw.drag);
            tryLoadSfxRawWav(GameConstants.NOTE_FLICK, R.raw.flick);

            if (skinPath != null && !skinPath.isEmpty()) {
                tryLoadSfxFromSkinDir(skinPath);
            }

            NativeAudioEngine.prepareDefaultSfxIfMissing();

            float userOffsetSec = chartOffsetMs / 1000.0f;
            if (replayMode && replayPath != null) {
                try {
                    File replayFile = new File(replayPath);
                    ReplayData replayData = ReplayManager.loadReplayJson(replayFile);
                    // Replay playback: do NOT set autoplay (preserves double-tap pause)
                    renderer = new GameRenderer(
                            this, chart, bgPath, songName, difficulty,
                            totalTimeSec, aspectRatio, keyScale, scrollSpeed,
                            mirrorX, musicSpeed, userOffsetSec, backgroundDim,
                            lowResMode, multiPressHighlight, apfcIndicator,
                            false, challengeMode, showFps, showDebugInfo,
                            skinPath, this);
                    renderer.setReplayPlayback(replayData);
                } catch (IOException e) {
                    runOnUiThread(() -> {
                        Toast.makeText(this, "加载回放失败：" + e.getMessage(), Toast.LENGTH_SHORT).show();
                        finish();
                    });
                    return;
                }
            } else {
                boolean useAutoplay = autoplay;
                boolean recordReplay = ReplayManager.isReplayEnabled(this);
                if (recordReplay && !useAutoplay) {
                    replayRecorderData = ReplayManager.startRecording();
                }
                renderer = new GameRenderer(
                        this, chart, bgPath, songName, difficulty,
                        totalTimeSec, aspectRatio, keyScale, scrollSpeed,
                        mirrorX, musicSpeed, userOffsetSec, backgroundDim,
                        lowResMode, multiPressHighlight, apfcIndicator,
                        useAutoplay, challengeMode, showFps, showDebugInfo,
                        skinPath, this);
                if (replayRecorderData != null) {
                    renderer.setReplayRecorder(replayRecorderData);
                }
            }

            runOnUiThread(() -> {
                glView = new GameGLSurfaceView(this);
                glView.enableUnlimitedFrameRate();
                if (antialias) {
                    glView.enableMultisample();
                }
                glView.setRenderer(renderer);
                glView.setRenderMode(GameGLSurfaceView.RENDERMODE_CONTINUOUSLY);
                glView.bindToRenderer(renderer);

                hintSurfaceFrameRateIfPossible();
                // Add glView behind the loading indicator so GL can initialize.
                // loadingView stays as content view the entire time — never detached,
                // so the ProgressBar animation runs smoothly without jumping.
                loadingView.addView(glView, 0, new FrameLayout.LayoutParams(
                        FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT));
                buildOverlayUiOn(loadingView);
                startUiTicker();
            });
        } catch (Throwable e) {
            try {
                NativeAudioEngine.stop();
                NativeAudioEngine.delete();
            } catch (Throwable ignored) {
            }
            final String msg = (e instanceof OutOfMemoryError)
                    ? "内存不足，无法加载该谱面（文件过大）"
                    : "加载失败：" + e.getMessage();
            runOnUiThread(() -> {
                Toast.makeText(this, msg, Toast.LENGTH_LONG).show();
                finish();
            });
        }
    }

    private static void forceGc() {
        byte[] trigger = new byte[4096];
        System.gc();
        System.runFinalization();
        try {
            Thread.sleep(16);
        } catch (InterruptedException ignored) {
        }
        trigger[0] = 0;
    }

    private void hintSurfaceFrameRateIfPossible() {
        if (glView == null) return;
        if (android.os.Build.VERSION.SDK_INT < android.os.Build.VERSION_CODES.R) return;
        try {
            Display display = getWindowManager() != null ? getWindowManager().getDefaultDisplay() : null;
            final float rate = display != null ? display.getRefreshRate() : 0f;
            if (rate <= 0f) return;

            android.view.SurfaceHolder holder = glView.getHolder();
            if (holder == null) return;

            android.view.Surface s = holder.getSurface();
            if (s != null && s.isValid()) {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                    s.setFrameRate(rate,
                            Surface.FRAME_RATE_COMPATIBILITY_DEFAULT,
                            Surface.CHANGE_FRAME_RATE_ONLY_IF_SEAMLESS);
                }
            }

            holder.addCallback(new android.view.SurfaceHolder.Callback() {
                @Override public void surfaceCreated(android.view.SurfaceHolder holder) {
                    try {
                        android.view.Surface surface = holder.getSurface();
                        if (surface != null && surface.isValid()) {
                            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                                surface.setFrameRate(rate, Surface.FRAME_RATE_COMPATIBILITY_DEFAULT, Surface.CHANGE_FRAME_RATE_ONLY_IF_SEAMLESS);
                            }
                        }
                    } catch (Throwable ignored) {}
                }
                @Override public void surfaceChanged(android.view.SurfaceHolder holder, int format, int width, int height) {}
                @Override public void surfaceDestroyed(android.view.SurfaceHolder holder) {}
            });
        } catch (Throwable ignored) {}
    }

    private void buildOverlayUiOn(FrameLayout container) {
        rootLayout = container;

        overlayLayout = new FrameLayout(this);
        overlayLayout.setLayoutParams(new FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT));
        overlayLayout.setClickable(false);

        tvSongTitle = new TextView(this);
        tvSongTitle.setText(songName == null ? "" : songName);
        tvSongTitle.setTextColor(Color.WHITE);
        tvSongTitle.setShadowLayer(6f, 0f, 0f, 0x66000000);
        tvSongTitle.setSingleLine(true);
        tvSongTitle.setIncludeFontPadding(false);
        tvSongTitle.setPadding(0, 8, 0, 4);

        tvDifficulty = new TextView(this);
        tvDifficulty.setText(difficulty == null ? "" : difficulty);
        tvDifficulty.setTextColor(Color.WHITE);
        tvDifficulty.setShadowLayer(6f, 0f, 0f, 0x66000000);
        tvDifficulty.setSingleLine(true);
        tvDifficulty.setIncludeFontPadding(false);
        tvDifficulty.setPadding(0, 8, 0, 4);

        tvScore = new TextView(this);
        tvScore.setText("0000000");
        tvScore.setTextColor(Color.WHITE);
        tvScore.setShadowLayer(6f, 0f, 0f, 0x66000000);
        tvScore.setSingleLine(true);
        tvScore.setIncludeFontPadding(false);

        comboLayout = new LinearLayout(this);
        comboLayout.setOrientation(LinearLayout.VERTICAL);
        comboLayout.setGravity(Gravity.CENTER_HORIZONTAL);

        tvComboNum = new TextView(this);
        tvComboNum.setTextColor(Color.WHITE);
        tvComboNum.setShadowLayer(6f, 0f, 0f, 0x66000000);
        tvComboNum.setText("0");
        tvComboNum.setGravity(Gravity.CENTER_HORIZONTAL);
        tvComboNum.setTextAlignment(View.TEXT_ALIGNMENT_CENTER);
        tvComboNum.setIncludeFontPadding(false);

        tvComboLabel = new TextView(this);
        tvComboLabel.setTextColor(Color.WHITE);
        tvComboLabel.setShadowLayer(6f, 0f, 0f, 0x66000000);
        tvComboLabel.setText(replayMode ? "REPLAY" : (autoplay ? "AUTOPLAY" : "COMBO"));
        tvComboLabel.setGravity(Gravity.CENTER_HORIZONTAL);
        tvComboLabel.setTextAlignment(View.TEXT_ALIGNMENT_CENTER);
        tvComboLabel.setIncludeFontPadding(false);

        comboLayout.addView(tvComboNum);
        comboLayout.addView(tvComboLabel);
        comboLayout.setVisibility(View.GONE);

        if (gameTypeface != null) {
            tvSongTitle.setTypeface(gameTypeface);
            tvDifficulty.setTypeface(gameTypeface);
            tvScore.setTypeface(gameTypeface);
            tvComboNum.setTypeface(gameTypeface);
            tvComboLabel.setTypeface(gameTypeface);
        }

        overlayLayout.addView(tvSongTitle, new FrameLayout.LayoutParams(FrameLayout.LayoutParams.WRAP_CONTENT, FrameLayout.LayoutParams.WRAP_CONTENT));
        overlayLayout.addView(tvDifficulty, new FrameLayout.LayoutParams(FrameLayout.LayoutParams.WRAP_CONTENT, FrameLayout.LayoutParams.WRAP_CONTENT));
        overlayLayout.addView(tvScore, new FrameLayout.LayoutParams(FrameLayout.LayoutParams.WRAP_CONTENT, FrameLayout.LayoutParams.WRAP_CONTENT));
        overlayLayout.addView(comboLayout, new FrameLayout.LayoutParams(FrameLayout.LayoutParams.WRAP_CONTENT, FrameLayout.LayoutParams.WRAP_CONTENT));

        rootLayout.addView(overlayLayout);
        overlayLayout.setVisibility(View.GONE);
        applyOverlayStageLayout();
    }

    private void startUiTicker() {
        stopUiTicker();
        uiTicker = new Runnable() {
            @Override
            public void run() {
                if (renderer != null) {
                    int c = renderer.getCombo();
                    if (c != lastCombo) {
                        lastCombo = c;
                        if (comboLayout != null) {
                            if (c >= 3) {
                                comboLayout.setVisibility(View.VISIBLE);
                                if (tvComboNum != null) tvComboNum.setText(String.valueOf(c));
                            } else {
                                comboLayout.setVisibility(View.GONE);
                            }
                        }
                    }
                    int sc = renderer.getScore();
                    if (sc != lastScore) {
                        lastScore = sc;
                        if (tvScore != null) {
                            if (sc < 0) sc = 0;
                            if (sc > 1_000_000) sc = 1_000_000;
                            tvScore.setText(String.format("%07d", sc));
                        }
                    }
                }
                uiHandler.postDelayed(this, 33);
            }
        };
        uiHandler.post(uiTicker);
    }

    private void stopUiTicker() {
        if (uiTicker != null) {
            uiHandler.removeCallbacks(uiTicker);
            uiTicker = null;
        }
    }

    private void applyOverlayStageLayout() {
        if (overlayLayout == null || stageW <= 0f || stageH <= 0f) return;
        int sw = overlayLayout.getWidth();
        int sh = overlayLayout.getHeight();
        if (sw <= 0) sw = getResources().getDisplayMetrics().widthPixels;
        if (sh <= 0) sh = getResources().getDisplayMetrics().heightPixels;

        float lineScale = stageW > stageH * 0.75f ? (stageH / 18.75f) : (stageW / 14.0625f);

        float bottomTextPx = clamp((stageW + stageH) / 86.25f, dp(11), dp(26));
        float scorePx = clamp((stageW + stageH) / 56.25f, dp(13), dp(33));
        float comboNumPx = clamp(lineScale * 1.3f, dp(22), dp(62));
        float comboLabelPx = clamp(lineScale * 0.4f, dp(8), dp(21));
        float difficultyPx = clamp((stageW + stageH) / 86.25f, dp(12), dp(27));

        if (tvSongTitle != null) {
            tvSongTitle.setTextSize(TypedValue.COMPLEX_UNIT_PX, bottomTextPx);
            FrameLayout.LayoutParams lp = (FrameLayout.LayoutParams) tvSongTitle.getLayoutParams();
            lp.gravity = Gravity.BOTTOM | Gravity.START;
            lp.leftMargin = (int) (stageL + stageW * 0.0225f);
            lp.bottomMargin = (int) (sh - (stageT + stageH * 0.965f));
            tvSongTitle.setLayoutParams(lp);
            tvSongTitle.setMaxWidth((int) (stageW * 0.50f));
        }
        if (tvDifficulty != null) {
            tvDifficulty.setTextSize(TypedValue.COMPLEX_UNIT_PX, difficultyPx);
            FrameLayout.LayoutParams lp = (FrameLayout.LayoutParams) tvDifficulty.getLayoutParams();
            lp.gravity = Gravity.BOTTOM | Gravity.END;
            lp.rightMargin = (int) (sw - (stageL + stageW * 0.9775f));
            lp.bottomMargin = (int) (sh - (stageT + stageH * 0.965f));
            tvDifficulty.setLayoutParams(lp);
            tvDifficulty.setMaxWidth((int) (stageW * 0.35f));
        }
        if (tvScore != null) {
            tvScore.setTextSize(TypedValue.COMPLEX_UNIT_PX, scorePx);
            FrameLayout.LayoutParams lp = (FrameLayout.LayoutParams) tvScore.getLayoutParams();
            lp.gravity = Gravity.TOP | Gravity.END;
            lp.rightMargin = (int) (sw - (stageL + stageW * (1.0f - 40.0f / 1920.0f)));
            lp.topMargin = (int) (stageT + stageH * 31.0f / 1080.0f);
            tvScore.setLayoutParams(lp);
        }
        if (comboLayout != null) {
            float spacing = Math.max(0f, lineScale * 0.065f);
            if (tvComboNum != null) tvComboNum.setTextSize(TypedValue.COMPLEX_UNIT_PX, comboNumPx);
            if (tvComboLabel != null) tvComboLabel.setTextSize(TypedValue.COMPLEX_UNIT_PX, comboLabelPx);
            FrameLayout.LayoutParams lp = (FrameLayout.LayoutParams) comboLayout.getLayoutParams();
            lp.gravity = Gravity.TOP | Gravity.CENTER_HORIZONTAL;
            lp.topMargin = (int) (stageT + spacing);
            comboLayout.setLayoutParams(lp);
        }
    }

    private float dp(float dp) { return dp * getResources().getDisplayMetrics().density; }
    private static float clamp(float v, float lo, float hi) { return v < lo ? lo : (v > hi ? hi : v); }

    @SuppressLint("GestureBackNavigation")
    @Override
    public void onBackPressed() {
        if (renderer == null) {
            super.onBackPressed();
            return;
        }
        if (!renderer.isMenuVisible()) {
            renderer.requestPauseMenu();
        }
    }

    private void tryLoadSfxRawWav(int noteType, int resId) {
        try {
            PcmDecoder.DecodedAudio sfx = WavDecoder.decodeRawWavToFloatPcm(this, resId);
            if (sfx != null && sfx.pcm != null && sfx.pcm.length > 0) {
                NativeAudioEngine.setSfxData(noteType, sfx.pcm, sfx.sampleRate, sfx.channels);
            }
        } catch (Throwable ignored) {}
    }

    private static final String[] SFX_EXTENSIONS = {".wav", ".ogg", ".mp3"};

    private void tryLoadSfxFromSkinDir(String skinDirPath) {
        File skinDir = new File(skinDirPath);
        if (!skinDir.isDirectory()) return;
        tryLoadSfxFromSkinFile("click", GameConstants.NOTE_TAP, skinDir);
        tryLoadSfxFromSkinFile("drag", GameConstants.NOTE_DRAG, skinDir);
        tryLoadSfxFromSkinFile("flick", GameConstants.NOTE_FLICK, skinDir);
    }

    private void tryLoadSfxFromSkinFile(String baseName, int noteType, File skinDir) {
        for (String ext : SFX_EXTENSIONS) {
            File file = new File(skinDir, baseName + ext);
            if (!file.isFile()) continue;
            try {
                PcmDecoder.DecodedAudio sfx;
                if (".wav".equals(ext)) {
                    sfx = WavDecoder.decodeWavFileToFloatPcm(file);
                } else {
                    sfx = PcmDecoder.decodeFileToFloatPcm(file.getAbsolutePath());
                }
                if (sfx != null && sfx.pcm != null && sfx.pcm.length > 0) {
                    NativeAudioEngine.setSfxData(noteType, sfx.pcm, sfx.sampleRate, sfx.channels);
                    return;
                }
            } catch (Throwable ignored) {}
        }
    }

    @Override
    protected void onPause() {
        super.onPause();
        if (renderer != null) renderer.requestPauseMenu();
        if (glView != null) glView.onPause();
        NativeAudioEngine.pause(true);
    }

    @Override
    public void onWindowFocusChanged(boolean hasFocus) {
        super.onWindowFocusChanged(hasFocus);
        if (hasFocus) {
            applyImmersiveMode();
            return;
        }
        if (!hasFocus) {
            if (renderer != null) renderer.requestPauseMenu();
            NativeAudioEngine.pause(true);
        }
    }

    @Override
    protected void onResume() {
        super.onResume();
        applyImmersiveMode();
        if (glView != null) glView.onResume();
        if (renderer != null && !renderer.isMenuVisible()) {
            NativeAudioEngine.pause(false);
        }
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        stopUiTicker();
        try {
            if (loaderExecutor != null) {
                loaderExecutor.shutdownNow();
                loaderExecutor = null;
            }
        } catch (Exception ignored) {}
        NativeAudioEngine.stop();
        NativeAudioEngine.delete();
    }

    @Override
    public void onExit() {
        runOnUiThread(() -> {
            if (!replayMode && replayRecorderData != null) {
                ReplayManager.clearCachedReplay(this);
            }
            NativeAudioEngine.stop();
            finish();
        });
    }

    @Override
    public void onFinished(PlayResult result) {
        runOnUiThread(() -> {
            stopUiTicker();
            NativeAudioEngine.stop();
            Bundle playBundle = new Bundle();
            playBundle.putString(EXTRA_MUSIC_PATH, musicPath);
            playBundle.putString(EXTRA_CHART_PATH, chartPath);
            playBundle.putString(EXTRA_BG_PATH, bgPath);
            playBundle.putString(EXTRA_SONG_NAME, songName);
            playBundle.putString(EXTRA_DIFFICULTY, difficulty);
            playBundle.putFloat(EXTRA_ASPECT_RATIO, aspectRatio);
            playBundle.putInt(EXTRA_CHART_OFFSET_MS, chartOffsetMs);
            playBundle.putFloat(EXTRA_MUSIC_SPEED, musicSpeed);
            playBundle.putFloat(EXTRA_KEY_SCALE, keyScale);
            playBundle.putFloat(EXTRA_SCROLL_SPEED, scrollSpeed);
            playBundle.putFloat(EXTRA_SFX_VOLUME, sfxVolume);
            playBundle.putFloat(EXTRA_MUSIC_VOLUME, musicVolume);
            playBundle.putBoolean(EXTRA_MIRROR_X, mirrorX);
            playBundle.putFloat(EXTRA_BG_DIM, backgroundDim);
            playBundle.putBoolean(EXTRA_LOW_RES, lowResMode);
            playBundle.putBoolean(EXTRA_ANTIALIAS, antialias);
            playBundle.putBoolean(EXTRA_SHOW_FPS, showFps);
            playBundle.putBoolean(EXTRA_SHOW_DEBUG, showDebugInfo);
            playBundle.putBoolean(EXTRA_MULTI_HIGHLIGHT, multiPressHighlight);
            playBundle.putBoolean(EXTRA_APFC, apfcIndicator);
            playBundle.putBoolean(EXTRA_AUTOPLAY, autoplay);
            playBundle.putBoolean(EXTRA_CHALLENGE, challengeMode);
            playBundle.putString(EXTRA_SKIN_PATH, skinPath);

            Intent it = new Intent(this, ResultActivity.class);
            it.putExtra(ResultActivity.EXTRA_PLAY_BUNDLE, playBundle);
            it.putExtra(ResultActivity.EXTRA_RESULT, result);

            // Replay: finish recording and pass to ResultActivity
            if (replayRecorderData != null && !replayMode) {
                ReplayManager.finishRecording(replayRecorderData, result, songName, difficulty);
                try {
                    File cachedFile = ReplayManager.saveReplayToCache(this, replayRecorderData);
                    replayCachedPath = cachedFile.getAbsolutePath();
                } catch (IOException ignored) {}
            }
            if (replayCachedPath != null) {
                it.putExtra(ChartSelectActivity.EXTRA_REPLAY_CACHED_PATH, replayCachedPath);
                it.putExtra(ChartSelectActivity.EXTRA_REPLAY_ENABLED_FLAG, true);
            }
            if (replayMode) {
                it.putExtra(ChartSelectActivity.EXTRA_IS_REPLAY, true);
                if (replayPath != null) {
                    it.putExtra(ChartSelectActivity.EXTRA_REPLAY_CACHED_PATH, replayPath);
                }
            }

            startActivity(it);
            finish();
        });
    }

    @Override
    public void onStageRectChanged(float left, float top, float width, float height) {
        stageL = left;
        stageT = top;
        stageW = width;
        stageH = height;
        runOnUiThread(this::applyOverlayStageLayout);
    }

    @Override
    public void onResourcesReady() {
        if (loadingProgress != null) loadingView.removeView(loadingProgress);
        if (loadingText != null) loadingView.removeView(loadingText);
        new Handler(Looper.getMainLooper()).postDelayed(() -> {
            if (isFinishing() || isDestroyed()) return;
            if (renderer == null) return;

            renderer.beginIntro();
            new Handler(Looper.getMainLooper()).postDelayed(() -> {
                if (isFinishing() || isDestroyed()) return;
                if (renderer != null && renderer.isMenuVisible()) return;
                NativeAudioEngine.start();
            }, GameRenderer.INTRO_BUFFER_MS);
        }, 150L);
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
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                WindowManager.LayoutParams lp = getWindow().getAttributes();
                lp.layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES;
                getWindow().setAttributes(lp);
            }
        } catch (Exception ignored) {}
        applyHighRefreshRate();
    }

    private void applyHighRefreshRate() {
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                Display display = getWindowManager().getDefaultDisplay();
                if (display == null) return;
                Display.Mode current = display.getMode();
                if (current == null) return;
                int curW = current.getPhysicalWidth();
                int curH = current.getPhysicalHeight();
                Display.Mode best = current;
                for (Display.Mode m : display.getSupportedModes()) {
                    if (m == null) continue;
                    if (m.getPhysicalWidth() == curW && m.getPhysicalHeight() == curH) {
                        if (m.getRefreshRate() > best.getRefreshRate() + 0.01f) {
                            best = m;
                        }
                    }
                }
                WindowManager.LayoutParams lp = getWindow().getAttributes();
                lp.preferredDisplayModeId = best.getModeId();
                lp.preferredRefreshRate = best.getRefreshRate();
                getWindow().setAttributes(lp);
            } else {
                WindowManager.LayoutParams lp = getWindow().getAttributes();
                lp.preferredRefreshRate = 240f;
                getWindow().setAttributes(lp);
            }
        } catch (Throwable ignored) {}
    }
}
