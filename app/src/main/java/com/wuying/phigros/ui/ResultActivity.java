package com.wuying.phigros.ui;

import android.animation.AnimatorSet;
import android.animation.ObjectAnimator;
import android.annotation.SuppressLint;
import android.content.Intent;
import android.content.res.AssetManager;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Color;
import android.graphics.RenderEffect;
import android.graphics.Shader;
import android.graphics.Typeface;
import android.os.Build;
import android.os.Bundle;
import android.view.View;
import android.view.WindowInsets;
import android.view.WindowInsetsController;
import android.view.WindowManager;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.Nullable;
import androidx.appcompat.app.AppCompatActivity;

import com.wuying.phigros.R;
import com.wuying.phigros.game.PlayResult;
import com.wuying.phigros.game.ReplayData;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.util.Locale;

public class ResultActivity extends AppCompatActivity {

    public static final String EXTRA_RESULT = "extra_result";
    public static final String EXTRA_PLAY_BUNDLE = "extra_play_bundle";

    private static final String PHIGROS_FONT_ASSET = "res/phigros.ttf";
    private static final String[] GRADE_FILES = {"F.png", "C.png", "B.png", "A.png", "S.png", "V.png", "FC.png", "AP.png"};

    private String replayCachedPath;

    private static int iconIndex(int score, boolean fullCombo) {
        if (score == 1000000) return 7;
        if (fullCombo) return 6;
        if (score < 700000) return 0;
        if (score < 820000) return 1;
        if (score < 880000) return 2;
        if (score < 920000) return 3;
        if (score < 960000) return 4;
        return 5;
    }

    @Override
    protected void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        requestWindowFeature(android.view.Window.FEATURE_NO_TITLE);
        getWindow().setFlags(
            WindowManager.LayoutParams.FLAG_FULLSCREEN,
            WindowManager.LayoutParams.FLAG_FULLSCREEN
        );

        setContentView(R.layout.activity_result);
        setupFullscreen();

        ImageView ivBackground = findViewById(R.id.iv_background);
        ImageView ivGrade = findViewById(R.id.iv_grade);
        TextView tvScore = findViewById(R.id.tv_score);
        TextView tvSong = findViewById(R.id.tv_song);
        TextView tvDiff = findViewById(R.id.tv_diff);
        TextView tvAcc = findViewById(R.id.tv_acc);
        TextView tvMaxCombo = findViewById(R.id.tv_max_combo);
        TextView tvStd = findViewById(R.id.tv_std);
        TextView tvPerfect = findViewById(R.id.tv_perfect);
        TextView tvGood = findViewById(R.id.tv_good);
        TextView tvBad = findViewById(R.id.tv_bad);
        TextView tvMiss = findViewById(R.id.tv_miss);
        TextView tvEarly = findViewById(R.id.tv_early);
        TextView tvLate = findViewById(R.id.tv_late);
        LinearLayout buttonsContainer = findViewById(R.id.buttons_container);
        FrameLayout btnRetryContainer = findViewById(R.id.btn_retry);
        FrameLayout btnExitContainer = findViewById(R.id.btn_exit);
        ImageView btnRetry = findViewById(R.id.btn_retry_icon);
        ImageView btnExit = findViewById(R.id.btn_exit_icon);
        View btnRetryBg = findViewById(R.id.btn_retry_bg);
        View btnExitBg = findViewById(R.id.btn_exit_bg);
        ImageView ivIllustration = findViewById(R.id.iv_illustration);

        Bundle playBundle = getIntent().getBundleExtra(EXTRA_PLAY_BUNDLE);
        PlayResult result = (PlayResult) getIntent().getSerializableExtra(EXTRA_RESULT);

        replayCachedPath = getIntent().getStringExtra(ChartSelectActivity.EXTRA_REPLAY_CACHED_PATH);
        boolean replayEnabled = getIntent().getBooleanExtra(ChartSelectActivity.EXTRA_REPLAY_ENABLED_FLAG, false);
        boolean isReplay = getIntent().getBooleanExtra(ChartSelectActivity.EXTRA_IS_REPLAY, false);

        String songName = "";
        String difficulty = "";
        String bgPath = null;
        float backgroundDim = 0.6f;

        if (playBundle != null) {
            songName = playBundle.getString(PlayActivity.EXTRA_SONG_NAME, "");
            difficulty = playBundle.getString(PlayActivity.EXTRA_DIFFICULTY, "");
            bgPath = playBundle.getString(PlayActivity.EXTRA_BG_PATH, null);
            backgroundDim = clampFloat(playBundle.getFloat(PlayActivity.EXTRA_BG_DIM, 0.6f), 0.3f, 0.8f, 0.6f);
        }

        if (tvSong != null) tvSong.setText(songName);
        if (tvDiff != null) tvDiff.setText(difficulty);

        if (ivBackground != null && bgPath != null) {
            try {
                Bitmap bmp = BitmapFactory.decodeFile(bgPath);
                if (bmp != null) {
                    if (ivIllustration != null) {
                        ivIllustration.setImageBitmap(bmp);
                    }
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                        ivBackground.setImageBitmap(bmp);
                        applyBlurToView(ivBackground, 30f);
                    } else {
                        new Thread(() -> {
                            try {
                                Bitmap blurred = gaussianBlur(bmp, 30);
                                runOnUiThread(() -> {
                                    if (!isDestroyed() && !isFinishing() && ivBackground != null) {
                                        ivBackground.setImageBitmap(blurred);
                                    }
                                });
                            } catch (Throwable ignored) {
                            }
                        }).start();
                    }
                }
            } catch (Throwable ignored) {
            }
        }

        if (result != null) {
            int idx = iconIndex(result.score, result.isFullCombo());
            if (ivGrade != null) loadGradeImage(ivGrade, idx);
            if (tvScore != null) tvScore.setText(String.format(Locale.US, "%07d", result.score));
            if (tvAcc != null) tvAcc.setText(String.format(Locale.US, "%.2f%%", result.accuracy));
            if (tvMaxCombo != null) tvMaxCombo.setText(String.valueOf(result.maxCombo));
            if (tvStd != null) tvStd.setText(String.format(Locale.US, "%.3f\u00A0ms", result.stdDevMs));
            if (tvPerfect != null) tvPerfect.setText(String.valueOf(result.perfect));
            if (tvGood != null) tvGood.setText(String.valueOf(result.goodEarly + result.goodLate));
            if (tvBad != null) tvBad.setText(String.valueOf(result.bad));
            if (tvMiss != null) tvMiss.setText(String.valueOf(result.miss));
            if (tvEarly != null) tvEarly.setText(String.valueOf(result.goodEarly));
            if (tvLate != null) tvLate.setText(String.valueOf(result.goodLate));
        }

        if (btnRetry != null) loadButtonImage(btnRetry, "retry.png");
        if (btnRetryContainer != null) {
            btnRetryContainer.setOnClickListener(v -> {
                if (playBundle == null) {
                    finish();
                    return;
                }
                Intent it = new Intent(this, PlayActivity.class);
                it.putExtras(playBundle);
                startActivity(it);
                finish();
            });
        }

        if (btnExit != null) loadButtonImage(btnExit, "home.png");
        if (btnExitContainer != null) btnExitContainer.setOnClickListener(v -> finish());

        if (btnRetryBg != null) applyBlurToView(btnRetryBg, 4f);
        if (btnExitBg != null) applyBlurToView(btnExitBg, 4f);

        // Replay buttons
        LinearLayout replayButtonsContainer = findViewById(R.id.replay_buttons_container);
        FrameLayout btnReplayContainer = findViewById(R.id.btn_replay);
        FrameLayout btnSaveContainer = findViewById(R.id.btn_save);
        ImageView btnReplayIcon = findViewById(R.id.btn_replay_icon);
        ImageView btnSaveIcon = findViewById(R.id.btn_save_icon);
        View btnReplayBg = findViewById(R.id.btn_replay_bg);
        View btnSaveBg = findViewById(R.id.btn_save_bg);

        if (isReplay && btnRetryContainer != null) {
            btnRetryContainer.setVisibility(View.GONE);
        }

        boolean showReplayButtons = false;
        boolean showSaveOnly = false;

        if (replayEnabled && !isReplay && replayCachedPath != null) {
            showReplayButtons = true;
        } else if (isReplay && replayCachedPath != null) {
            // After replay playback: check if this replay is already saved
            try {
                File cachedReplayFile = new File(replayCachedPath);
                if (cachedReplayFile.exists()) {
                    ReplayData cachedData = ReplayManager.loadReplayJson(cachedReplayFile);
                    if (cachedData != null && !ReplayManager.isReplayAlreadySaved(this, cachedData)) {
                        showReplayButtons = true;
                        showSaveOnly = true; // Only need save button, replay button is optional
                    }
                }
            } catch (Exception ignored) {}
        }

        if (showReplayButtons) {
            if (replayButtonsContainer != null) {
                replayButtonsContainer.setVisibility(View.VISIBLE);
            }
            if (btnReplayIcon != null) loadButtonImage(btnReplayIcon, "replay.png");
            if (btnSaveIcon != null) loadButtonImage(btnSaveIcon, "save.png");
            if (btnReplayBg != null) applyBlurToView(btnReplayBg, 4f);
            if (btnSaveBg != null) applyBlurToView(btnSaveBg, 4f);

            if (btnReplayContainer != null && !showSaveOnly) {
                btnReplayContainer.setVisibility(View.VISIBLE);
                btnReplayContainer.setOnClickListener(v -> {
                    if (playBundle == null) return;
                    Intent it = new Intent(this, PlayActivity.class);
                    it.putExtras(playBundle);
                    it.putExtra(PlayActivity.EXTRA_REPLAY_MODE, true);
                    it.putExtra(PlayActivity.EXTRA_REPLAY_PATH, replayCachedPath);
                    startActivity(it);
                    finish();
                });
            } else if (btnReplayContainer != null) {
                btnReplayContainer.setVisibility(View.GONE);
            }

            if (btnSaveContainer != null) {
                btnSaveContainer.setOnClickListener(v -> saveReplay(playBundle));
            }
        }

        applyPhigrosFont();

        if (tvSong != null) tvSong.setIncludeFontPadding(true);
        if (tvDiff != null) tvDiff.setIncludeFontPadding(true);

        startEnterAnimation(backgroundDim);
    }

    private void saveReplay(Bundle playBundle) {
        try {
            File replayJsonFile = new File(replayCachedPath);
            if (!replayJsonFile.exists()) {
                Toast.makeText(this, R.string.toast_replay_cache_missing, Toast.LENGTH_SHORT).show();
                return;
            }
            ReplayData replayData = ReplayManager.loadReplayJson(replayJsonFile);
            if (replayData == null) {
                Toast.makeText(this, R.string.toast_replay_data_corrupt, Toast.LENGTH_SHORT).show();
                return;
            }
            File chartFile = null, musicFile = null, bgFile = null;
            ReplayManager.InfoJson settings = new ReplayManager.InfoJson();
            if (playBundle != null) {
                String cp = playBundle.getString(PlayActivity.EXTRA_CHART_PATH);
                String mp = playBundle.getString(PlayActivity.EXTRA_MUSIC_PATH);
                String bp = playBundle.getString(PlayActivity.EXTRA_BG_PATH);
                if (cp != null) chartFile = new File(cp);
                if (mp != null) musicFile = new File(mp);
                if (bp != null) bgFile = new File(bp);
                settings.aspectRatio = playBundle.getFloat(PlayActivity.EXTRA_ASPECT_RATIO, 0f);
                settings.keyScale = playBundle.getFloat(PlayActivity.EXTRA_KEY_SCALE, 1.0f);
                settings.scrollSpeed = playBundle.getFloat(PlayActivity.EXTRA_SCROLL_SPEED, 1.0f);
                settings.mirrorX = playBundle.getBoolean(PlayActivity.EXTRA_MIRROR_X, false);
                settings.bgDim = clampFloat(playBundle.getFloat(PlayActivity.EXTRA_BG_DIM, 0.6f), 0.3f, 0.8f, 0.6f);
                settings.lowRes = playBundle.getBoolean(PlayActivity.EXTRA_LOW_RES, false);
                settings.antialias = playBundle.getBoolean(PlayActivity.EXTRA_ANTIALIAS, false);
                settings.showFps = playBundle.getBoolean(PlayActivity.EXTRA_SHOW_FPS, false);
                settings.showDebug = playBundle.getBoolean(PlayActivity.EXTRA_SHOW_DEBUG, false);
                settings.multiHighlight = playBundle.getBoolean(PlayActivity.EXTRA_MULTI_HIGHLIGHT, true);
                settings.apfc = playBundle.getBoolean(PlayActivity.EXTRA_APFC, false);
                settings.challenge = playBundle.getBoolean(PlayActivity.EXTRA_CHALLENGE, false);
                settings.musicSpeed = playBundle.getFloat(PlayActivity.EXTRA_MUSIC_SPEED, 1.0f);
                settings.musicVolPct = (int)(playBundle.getFloat(PlayActivity.EXTRA_MUSIC_VOLUME, 1.0f) * 100);
                settings.sfxVolPct = (int)(playBundle.getFloat(PlayActivity.EXTRA_SFX_VOLUME, 1.0f) * 100);
                settings.chartOffsetMs = playBundle.getInt(PlayActivity.EXTRA_CHART_OFFSET_MS, 0);
            }
            String uuid = ReplayManager.savePersistedReplay(this, replayData, chartFile, musicFile, bgFile, settings);
            if (uuid == null) {
                Toast.makeText(this, R.string.toast_replay_already_saved, Toast.LENGTH_SHORT).show();
            } else {
                Toast.makeText(this, R.string.toast_replay_saved, Toast.LENGTH_SHORT).show();
            }
        } catch (IOException e) {
            Toast.makeText(this, getString(R.string.toast_replay_save_failed, e.getMessage()), Toast.LENGTH_SHORT).show();
        }
    }

    private Typeface phigrosTypeface;

    private void applyPhigrosFont() {
        phigrosTypeface = Typeface.createFromAsset(getAssets(), PHIGROS_FONT_ASSET);
        applyFontRecursive(findViewById(R.id.content_container));
    }

    private void applyFontRecursive(View view) {
        if (view instanceof TextView) {
            TextView tv = (TextView) view;
            tv.setTypeface(phigrosTypeface);
            tv.setIncludeFontPadding(false);
        } else if (view instanceof android.view.ViewGroup) {
            android.view.ViewGroup vg = (android.view.ViewGroup) view;
            for (int i = 0; i < vg.getChildCount(); i++) {
                applyFontRecursive(vg.getChildAt(i));
            }
        }
    }

    private void loadGradeImage(ImageView imageView, int gradeIndex) {
        if (gradeIndex < 0 || gradeIndex >= GRADE_FILES.length) gradeIndex = 0;
        String fileName = "grades/" + GRADE_FILES[gradeIndex];
        loadImageFromAssets(imageView, fileName);
    }

    private void loadButtonImage(ImageView imageView, String fileName) {
        loadImageFromAssets(imageView, "res/" + fileName);
    }

    private void loadImageFromAssets(ImageView imageView, String path) {
        AssetManager assetManager = getAssets();
        InputStream inputStream = null;
        try {
            inputStream = assetManager.open(path);
            Bitmap bitmap = BitmapFactory.decodeStream(inputStream);
            if (bitmap != null) imageView.setImageBitmap(bitmap);
        } catch (IOException ignored) {
        } finally {
            if (inputStream != null) {
                try { inputStream.close(); } catch (IOException ignored) {}
            }
        }
    }

    private void setupFullscreen() {
        try {
            getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                getWindow().setDecorFitsSystemWindows(false);
                WindowInsetsController controller = getWindow().getInsetsController();
                if (controller != null) {
                    controller.hide(WindowInsets.Type.statusBars() | WindowInsets.Type.navigationBars());
                    controller.setSystemBarsBehavior(WindowInsetsController.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE);
                }
            } else {
                View decorView = getWindow().getDecorView();
                decorView.setSystemUiVisibility(
                    View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
                    | View.SYSTEM_UI_FLAG_FULLSCREEN
                    | View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
                    | View.SYSTEM_UI_FLAG_LAYOUT_STABLE
                    | View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
                    | View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
                );
            }
            getWindow().setStatusBarColor(Color.TRANSPARENT);
            getWindow().setNavigationBarColor(Color.TRANSPARENT);
        } catch (Throwable ignored) {}
    }

    /**
     * Gaussian blur approximation using 3 iterations of separable box blur.
     */
    private Bitmap gaussianBlur(Bitmap src, int radius) {
        if (radius < 1) return src;
        int w = src.getWidth();
        int h = src.getHeight();
        if (w == 0 || h == 0) return src;

        int[] pix = new int[w * h];
        src.getPixels(pix, 0, w, 0, 0, w, h);

        int[] tmp = new int[w * h];
        int div = radius * 2 + 1;

        for (int pass = 0; pass < 3; pass++) {
            for (int y = 0; y < h; y++) {
                int row = y * w;
                int sr = 0, sg = 0, sb = 0;
                for (int i = -radius; i <= radius; i++) {
                    int c = pix[row + clamp(i, 0, w - 1)];
                    sr += (c >> 16) & 0xff;
                    sg += (c >> 8) & 0xff;
                    sb += c & 0xff;
                }
                tmp[row] = pack(sr / div, sg / div, sb / div);
                for (int x = 1; x < w; x++) {
                    int remCol = clamp(x - radius - 1, 0, w - 1);
                    int addCol = clamp(x + radius, 0, w - 1);
                    int rc = pix[row + remCol];
                    sr -= (rc >> 16) & 0xff; sg -= (rc >> 8) & 0xff; sb -= rc & 0xff;
                    int ac = pix[row + addCol];
                    sr += (ac >> 16) & 0xff; sg += (ac >> 8) & 0xff; sb += ac & 0xff;
                    tmp[row + x] = pack(sr / div, sg / div, sb / div);
                }
            }
            for (int x = 0; x < w; x++) {
                int sr = 0, sg = 0, sb = 0;
                for (int i = -radius; i <= radius; i++) {
                    int c = tmp[clamp(i, 0, h - 1) * w + x];
                    sr += (c >> 16) & 0xff; sg += (c >> 8) & 0xff; sb += c & 0xff;
                }
                pix[x] = pack(sr / div, sg / div, sb / div);
                for (int y = 1; y < h; y++) {
                    int remRow = clamp(y - radius - 1, 0, h - 1);
                    int addRow = clamp(y + radius, 0, h - 1);
                    int rc = tmp[remRow * w + x];
                    sr -= (rc >> 16) & 0xff; sg -= (rc >> 8) & 0xff; sb -= rc & 0xff;
                    int ac = tmp[addRow * w + x];
                    sr += (ac >> 16) & 0xff; sg += (ac >> 8) & 0xff; sb += ac & 0xff;
                    pix[y * w + x] = pack(sr / div, sg / div, sb / div);
                }
            }
        }

        Bitmap result = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888);
        result.setPixels(pix, 0, w, 0, 0, w, h);
        return result;
    }

    private static int clamp(int val, int min, int max) { return val < min ? min : (val > max ? max : val); }
    private static int pack(int r, int g, int b) { return (0xff << 24) | ((r & 0xff) << 16) | ((g & 0xff) << 8) | (b & 0xff); }
    private static float clampFloat(float val, float min, float max, float fallback) {
        if (!Float.isFinite(val)) return fallback;
        return val < min ? min : (val > max ? max : val);
    }

    private void applyBlurToView(View view, float radius) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            try {
                view.setRenderEffect(RenderEffect.createBlurEffect(radius, radius, Shader.TileMode.CLAMP));
            } catch (Throwable ignored) {}
        }
    }

    private void startEnterAnimation(float backgroundDim) {
        View contentContainer = findViewById(R.id.content_container);
        View overlay = findViewById(R.id.view_dim_overlay);
        View ivGrade = findViewById(R.id.iv_grade);
        View tvScore = findViewById(R.id.tv_score);
        View upperBoard = findViewById(R.id.upper_board);
        View cardIllustration = findViewById(R.id.card_illustration);
        View lowerBoard = findViewById(R.id.lower_board);
        View buttonsContainer = findViewById(R.id.buttons_container);
        View replayButtonsContainer = findViewById(R.id.replay_buttons_container);

        if (contentContainer == null) return;

        long duration1_5 = 640;
        long duration3 = 1280;

        if (overlay != null) {
            overlay.setAlpha(backgroundDim);
        }

        float startY = 1000f;
        contentContainer.setTranslationY(startY);
        contentContainer.setScaleX(0.75f);
        contentContainer.setScaleY(0.75f);

        ObjectAnimator contentY = ObjectAnimator.ofFloat(contentContainer, "translationY", startY, 0f);
        contentY.setDuration(duration3);
        contentY.setInterpolator(new android.view.animation.DecelerateInterpolator(1.5f));

        ObjectAnimator contentScaleX = ObjectAnimator.ofFloat(contentContainer, "scaleX", 0.75f, 1f);
        contentScaleX.setDuration(duration3);
        contentScaleX.setInterpolator(new android.view.animation.AccelerateInterpolator(1.5f));

        ObjectAnimator contentScaleY = ObjectAnimator.ofFloat(contentContainer, "scaleY", 0.75f, 1f);
        contentScaleY.setDuration(duration3);
        contentScaleY.setInterpolator(new android.view.animation.AccelerateInterpolator(1.5f));

        AnimatorSet contentAnim = new AnimatorSet();
        contentAnim.playTogether(contentY, contentScaleX, contentScaleY);
        contentAnim.start();

        if (ivGrade != null) {
            ivGrade.setScaleX(2f); ivGrade.setScaleY(2f);
            ivGrade.setTranslationX(-200f); ivGrade.setTranslationY(-500f);

            ObjectAnimator gradeScaleX = ObjectAnimator.ofFloat(ivGrade, "scaleX", 2f, 1f);
            gradeScaleX.setDuration(duration3);
            gradeScaleX.setInterpolator(new android.view.animation.AccelerateInterpolator(1.5f));
            ObjectAnimator gradeScaleY = ObjectAnimator.ofFloat(ivGrade, "scaleY", 2f, 1f);
            gradeScaleY.setDuration(duration3);
            gradeScaleY.setInterpolator(new android.view.animation.AccelerateInterpolator(1.5f));
            ObjectAnimator gradeY1 = ObjectAnimator.ofFloat(ivGrade, "translationY", -500f, -50f);
            gradeY1.setDuration(duration1_5);
            gradeY1.setInterpolator(new android.view.animation.DecelerateInterpolator());
            ObjectAnimator gradeY2 = ObjectAnimator.ofFloat(ivGrade, "translationY", -50f, 0f);
            gradeY2.setStartDelay(duration1_5);
            gradeY2.setDuration(duration1_5);
            gradeY2.setInterpolator(new android.view.animation.AccelerateInterpolator());
            ObjectAnimator gradeX = ObjectAnimator.ofFloat(ivGrade, "translationX", -200f, 0f);
            gradeX.setStartDelay(duration1_5);
            gradeX.setDuration(duration1_5);
            gradeX.setInterpolator(new android.view.animation.AccelerateInterpolator());

            AnimatorSet gradeAnim = new AnimatorSet();
            gradeAnim.playTogether(gradeScaleX, gradeScaleY, gradeY1, gradeY2, gradeX);
            gradeAnim.start();
        }

        View[] statsViews = {tvScore, upperBoard, cardIllustration, lowerBoard};
        for (View view : statsViews) {
            if (view == null) continue;
            view.setTranslationY(-200f);
            view.setAlpha(0f);
            ObjectAnimator alphaAnim = ObjectAnimator.ofFloat(view, "alpha", 0f, 1f);
            alphaAnim.setStartDelay(duration3);
            alphaAnim.setDuration(duration1_5);
            ObjectAnimator slideAnim = ObjectAnimator.ofFloat(view, "translationY", 100f, 0f);
            slideAnim.setStartDelay(duration3);
            slideAnim.setDuration(duration1_5);
            slideAnim.setInterpolator(new android.view.animation.DecelerateInterpolator());
            AnimatorSet statAnim = new AnimatorSet();
            statAnim.playTogether(alphaAnim, slideAnim);
            statAnim.start();
        }

        if (buttonsContainer != null) {
            buttonsContainer.setAlpha(0f);
            buttonsContainer.postDelayed(() -> {
                try {
                    ObjectAnimator alphaAnim = ObjectAnimator.ofFloat(buttonsContainer, "alpha", 0f, 1f);
                    alphaAnim.setDuration(300);
                    ObjectAnimator translationY = ObjectAnimator.ofFloat(buttonsContainer, "translationY", 50f, 0f);
                    translationY.setDuration(300);
                    AnimatorSet animatorSet = new AnimatorSet();
                    animatorSet.playTogether(alphaAnim, translationY);
                    animatorSet.start();
                } catch (Throwable ignored) {}
            }, duration3 + 200);
        }

        if (replayButtonsContainer != null && replayButtonsContainer.getVisibility() == View.VISIBLE) {
            replayButtonsContainer.setAlpha(0f);
            replayButtonsContainer.postDelayed(() -> {
                try {
                    ObjectAnimator alphaAnim = ObjectAnimator.ofFloat(replayButtonsContainer, "alpha", 0f, 1f);
                    alphaAnim.setDuration(300);
                    ObjectAnimator translationY = ObjectAnimator.ofFloat(replayButtonsContainer, "translationY", 50f, 0f);
                    translationY.setDuration(300);
                    AnimatorSet animatorSet = new AnimatorSet();
                    animatorSet.playTogether(alphaAnim, translationY);
                    animatorSet.start();
                } catch (Throwable ignored) {}
            }, duration3 + 200);
        }
    }

    @Override
    public void onWindowFocusChanged(boolean hasFocus) {
        super.onWindowFocusChanged(hasFocus);
        if (hasFocus) setupFullscreen();
    }

    @SuppressLint("GestureBackNavigation")
    @Override
    public void onBackPressed() {
        super.onBackPressed();
        finish();
    }
}
