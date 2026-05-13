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

import androidx.annotation.Nullable;
import androidx.appcompat.app.AppCompatActivity;

import com.wuying.phigros.R;
import com.wuying.phigros.game.PlayResult;

import java.io.IOException;
import java.io.InputStream;
import java.util.Locale;

public class ResultActivity extends AppCompatActivity {

    public static final String EXTRA_RESULT = "extra_result";
    public static final String EXTRA_PLAY_BUNDLE = "extra_play_bundle";

    private static final String[] GRADE_FILES = {"F.png", "C.png", "B.png", "A.png", "S.png", "V.png", "FC.png", "AP.png"};

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

        String songName = "";
        String difficulty = "";
        String bgPath = null;

        if (playBundle != null) {
            songName = playBundle.getString(PlayActivity.EXTRA_SONG_NAME, "");
            difficulty = playBundle.getString(PlayActivity.EXTRA_DIFFICULTY, "");
            bgPath = playBundle.getString(PlayActivity.EXTRA_BG_PATH, null);
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
            if (tvStd != null) tvStd.setText(String.format(Locale.US, "%.3f ms", result.stdDevMs));
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

        applyPhigrosFont();

        if (tvSong != null) tvSong.setIncludeFontPadding(true);
        if (tvDiff != null) tvDiff.setIncludeFontPadding(true);

        startEnterAnimation();
    }

    private Typeface phigrosTypeface;

    private void applyPhigrosFont() {
        try {
            phigrosTypeface = Typeface.createFromAsset(getAssets(), "res/phigros.ttf");
        } catch (Throwable ignored) {
            phigrosTypeface = null;
        }
        if (phigrosTypeface != null) {
            applyFontRecursive(findViewById(R.id.content_container));
        }
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

    private void applyBlurToView(View view, float radius) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            try {
                view.setRenderEffect(RenderEffect.createBlurEffect(radius, radius, Shader.TileMode.CLAMP));
            } catch (Throwable ignored) {}
        }
    }

    private void startEnterAnimation() {
        View contentContainer = findViewById(R.id.content_container);
        View overlay = findViewById(R.id.view_dim_overlay);
        View ivGrade = findViewById(R.id.iv_grade);
        View tvScore = findViewById(R.id.tv_score);
        View upperBoard = findViewById(R.id.upper_board);
        View cardIllustration = findViewById(R.id.card_illustration);
        View lowerBoard = findViewById(R.id.lower_board);
        View buttonsContainer = findViewById(R.id.buttons_container);

        if (contentContainer == null) return;

        long duration1_5 = 640;
        long duration3 = 1280;

        if (overlay != null) {
            ObjectAnimator overlayToDark = ObjectAnimator.ofFloat(overlay, "alpha", 0f, 0.7f);
            overlayToDark.setDuration(duration1_5);
            overlayToDark.setInterpolator(new android.view.animation.DecelerateInterpolator(1.5f));

            ObjectAnimator overlayToClear = ObjectAnimator.ofFloat(overlay, "alpha", 0.7f, 0.45f);
            overlayToClear.setStartDelay(duration1_5);
            overlayToClear.setDuration(duration1_5);
            overlayToClear.setInterpolator(new android.view.animation.AccelerateInterpolator(1.5f));

            AnimatorSet overlayAnim = new AnimatorSet();
            overlayAnim.playSequentially(overlayToDark, overlayToClear);
            overlayAnim.start();
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
