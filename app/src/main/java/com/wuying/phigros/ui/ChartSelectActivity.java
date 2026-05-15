package com.wuying.phigros.ui;

import android.content.Intent;
import android.content.SharedPreferences;
import android.net.Uri;
import android.os.Bundle;
import android.os.Build;
import android.text.TextUtils;
import android.view.View;
import android.view.WindowManager;
import android.widget.TextView;
import android.widget.Toast;

import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.annotation.Nullable;
import androidx.appcompat.app.AppCompatActivity;
import androidx.viewpager2.widget.ViewPager2;

import com.google.android.material.appbar.MaterialToolbar;
import com.google.android.material.floatingactionbutton.ExtendedFloatingActionButton;
import com.google.android.material.tabs.TabLayout;
import com.google.android.material.tabs.TabLayoutMediator;

import com.wuying.phigros.R;
import com.wuying.phigros.util.FileUtils;
import com.wuying.phigros.util.InfoFileUtils;
import com.wuying.phigros.util.ZipPackUtils;

import java.io.File;
import java.io.IOException;
import java.util.List;

public class ChartSelectActivity extends AppCompatActivity {

    private static final String PREFS_NAME = "chart_select_settings";
    private static final String PREF_ASPECT_RATIO = "aspect_ratio";
    private static final String PREF_KEY_SCALE = "key_scale";
    private static final String PREF_MIRROR_X = "mirror_x";
    private static final String PREF_AUTOPLAY = "autoplay";
    private static final String PREF_CHALLENGE = "challenge";
    private static final String PREF_BG_DIM = "bg_dim";
    private static final String PREF_LOW_RES = "low_res";
    private static final String PREF_ANTIALIAS = "antialias";
    private static final String PREF_SHOW_FPS = "show_fps";
    private static final String PREF_SHOW_DEBUG = "show_debug";
    private static final String PREF_MULTI_HIGHLIGHT = "multi_highlight";
    private static final String PREF_APFC = "apfc";
    private static final String PREF_MUSIC_VOL_PCT = "music_vol_pct";
    private static final String PREF_SFX_VOL_PCT = "sfx_vol_pct";
    private static final String PREF_MUSIC_SPEED = "music_speed";
    private static final String PREF_SCROLL_SPEED = "scroll_speed";

    private SharedPreferences prefs;

    private File musicFile;
    private File chartFile;
    private File bgFile;
    private File infoFile;

    private String songName;
    private String difficulty;

    private String lastMusicDisplayName;

    private boolean userEditedSongName = false;
    private boolean userEditedDifficulty = false;

    private int chartOffsetMs = 0;

    private float aspectRatio = 16f / 9f;
    private float keyScale = 1.0f;
    private float scrollSpeed = 1.0f;
    private boolean mirrorX = false;
    private boolean autoplay = false;
    private boolean challengeMode = false;
    private float backgroundDim = 0.6f;
    private boolean lowResMode = false;
    private boolean antialias = false;
    private boolean showFps = false;
    private boolean showDebugInfo = false;
    private boolean multiPressHighlight = true;
    private boolean apfcIndicator = false;

    private int musicVolumePct = 100;
    private int sfxVolumePct = 100;
    private float musicSpeed = 1.0f;

    private ActivityResultLauncher<String[]> pickMusicLauncher;
    private ActivityResultLauncher<String[]> pickChartLauncher;
    private ActivityResultLauncher<String[]> pickBgLauncher;
    private ActivityResultLauncher<String[]> pickZipLauncher;
    private ActivityResultLauncher<String[]> pickSkinLauncher;

    private ExtendedFloatingActionButton fabStart;
    private MaterialToolbar toolbar;

    @Override
    protected void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        applyImmersiveMode();
        setContentView(R.layout.activity_select);

        loadPersistedSettings();
        initLaunchers();
        setupPagerUi();
    }

    @Override
    protected void onResume() {
        super.onResume();
        applyImmersiveMode();
    }

    @Override
    public void onWindowFocusChanged(boolean hasFocus) {
        super.onWindowFocusChanged(hasFocus);
        if (hasFocus) {
            applyImmersiveMode();
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

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                WindowManager.LayoutParams lp = getWindow().getAttributes();
                lp.layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES;
                getWindow().setAttributes(lp);
            }
        } catch (Exception ignored) {
        }
    }

    private void setupPagerUi() {
        toolbar = findViewById(R.id.toolbar);
        setSupportActionBar(toolbar);

        TabLayout tabLayout = findViewById(R.id.tab_layout);
        ViewPager2 pager = findViewById(R.id.view_pager);
        SelectPagerAdapter adapter = new SelectPagerAdapter(this);
        pager.setAdapter(adapter);
        new TabLayoutMediator(tabLayout, pager, (tab, position) -> {
            if (position == 0) tab.setText("铺面");
            else if (position == 1) tab.setText("视频");
            else tab.setText("音频");
        }).attach();
        pager.registerOnPageChangeCallback(new ViewPager2.OnPageChangeCallback() {
            @Override
            public void onPageSelected(int position) {
                updateToolbarTitle(position);
            }
        });
        updateToolbarTitle(0);

        fabStart = findViewById(R.id.fab_start);
        if (fabStart != null) {
            fabStart.setEnabled(false);
            fabStart.setAlpha(1f);
            updateStartButtonText();
            fabStart.setOnClickListener(v -> {
                if (!isReadyToStart()) {
                    toast("请先选择音乐、铺面与背景");
                    return;
                }
                if (TextUtils.isEmpty(songName) || TextUtils.isEmpty(difficulty)) {
                    toast("请在“铺面”选项卡填写曲名与难度");
                    return;
                }
                startPlay();
            });
        }
    }

    private void updateToolbarTitle(int position) {
        if (toolbar == null) return;
        if (position == 0) toolbar.setTitle("铺面设置");
        else if (position == 1) toolbar.setTitle("视频设置");
        else toolbar.setTitle("音频设置");
    }

    private void initLaunchers() {
        pickMusicLauncher = registerForActivityResult(new ActivityResultContracts.OpenDocument(), uri -> {
            if (uri == null) return;
            tryTakePersistableRead(uri);
            try {
                musicFile = FileUtils.copyUriToCache(this, uri, "music");
                lastMusicDisplayName = FileUtils.queryDisplayName(this, uri);
                if (!userEditedSongName) {
                    songName = FileUtils.stripExtension(lastMusicDisplayName);
                }
            } catch (IOException e) {
                musicFile = null;
                toast("复制音乐文件失败：" + e.getMessage());
            }
            notifyChartTabFilesChanged();
        });

        pickChartLauncher = registerForActivityResult(new ActivityResultContracts.OpenDocument(), uri -> {
            if (uri == null) return;
            tryTakePersistableRead(uri);
            try {
                resetChartSpecificFields();
                chartFile = FileUtils.copyUriToCache(this, uri, "chart");
            } catch (IOException e) {
                chartFile = null;
                toast("复制铺面文件失败：" + e.getMessage());
            }
            tryUpdateSongInfo();
            notifyChartTabFilesChanged();
        });

        pickBgLauncher = registerForActivityResult(new ActivityResultContracts.OpenDocument(), uri -> {
            if (uri == null) return;
            tryTakePersistableRead(uri);
            try {
                bgFile = FileUtils.copyUriToCache(this, uri, "bg");
            } catch (IOException e) {
                bgFile = null;
                toast("复制背景图片失败：" + e.getMessage());
            }
            notifyChartTabFilesChanged();
        });

        pickZipLauncher = registerForActivityResult(new ActivityResultContracts.OpenDocument(), uri -> {
            if (uri == null) return;
            tryTakePersistableRead(uri);
            try {
                resetChartSpecificFields();
                File zipFile = FileUtils.copyUriToCache(this, uri, "pack_zip");
                ZipPackUtils.DetectedPack pack = ZipPackUtils.extractAndDetect(zipFile, new File(getCacheDir(), "pack"));

                musicFile = pack.musicFile;
                chartFile = pack.chartFile;
                bgFile = pack.bgFile;
                infoFile = pack.infoFile;

                if (musicFile != null) {
                    lastMusicDisplayName = musicFile.getName();
                    if (!userEditedSongName) {
                        songName = FileUtils.stripExtension(lastMusicDisplayName);
                    }
                }

                if (infoFile != null && infoFile.exists()) {
                    InfoFileUtils.Info info = InfoFileUtils.readInfoFile(infoFile);
                    if (info != null) {
                        if (!userEditedSongName && !TextUtils.isEmpty(info.name)) songName = info.name;
                        String levelLabel = InfoFileUtils.buildDifficultyLabel(info, chartFile);
                        if (!userEditedDifficulty && !TextUtils.isEmpty(levelLabel)) difficulty = levelLabel;
                    }
                }

                toast("压缩包解析完成");
            } catch (OutOfMemoryError oom) {
                toast("压缩包过大，内存不足无法加载");
            } catch (IOException e) {
                toast("处理压缩包失败：" + e.getMessage());
            }
            tryUpdateSongInfo();
            notifyChartTabFilesChanged();
        });

        pickSkinLauncher = registerForActivityResult(new ActivityResultContracts.OpenDocument(), uri -> {
            if (uri == null) return;
            tryTakePersistableRead(uri);
            try {
                File zipFile = FileUtils.copyUriToCache(this, uri, "skin_zip");
                SkinManager.importSkin(this, zipFile);
                toast("皮肤导入成功");
                notifyVideoTabSkinChanged();
            } catch (IOException e) {
                toast("导入皮肤失败：" + e.getMessage());
            }
        });
    }

    private void tryTakePersistableRead(Uri uri) {
        try {
            final int flags = Intent.FLAG_GRANT_READ_URI_PERMISSION;
            getContentResolver().takePersistableUriPermission(uri, flags);
        } catch (Exception ignored) {
        }
    }

    private void tryUpdateSongInfo() {
        try {
            InfoFileUtils.Info info = null;
            if (infoFile != null && infoFile.exists()) {
                info = InfoFileUtils.readInfoFile(infoFile);
            }
            if (info == null) info = new InfoFileUtils.Info();

            if (chartFile != null && chartFile.exists()) {
                InfoFileUtils.Info meta = InfoFileUtils.readInfoFromChartFile(chartFile);
                if (meta != null) {
                    if (TextUtils.isEmpty(info.name) && !TextUtils.isEmpty(meta.name)) info.name = meta.name;
                    if (TextUtils.isEmpty(info.level) && !TextUtils.isEmpty(meta.level)) info.level = meta.level;
                    if (TextUtils.isEmpty(info.difficulty) && !TextUtils.isEmpty(meta.difficulty)) info.difficulty = meta.difficulty;
                }
            }

            if (!userEditedSongName && !TextUtils.isEmpty(info.name)) songName = info.name;
            String levelLabel = InfoFileUtils.buildDifficultyLabel(info, chartFile);
            if (!userEditedDifficulty && !TextUtils.isEmpty(levelLabel)) difficulty = levelLabel;
        } catch (Exception ignored) {
        }
    }

    private void resetChartSpecificFields() {
        userEditedSongName = false;
        userEditedDifficulty = false;
        songName = "";
        difficulty = "";
        chartOffsetMs = 0;
    }

    void notifyChartTabFilesChanged() {
        updateStartButtonState();
        try {
            androidx.fragment.app.Fragment f = getSupportFragmentManager().findFragmentByTag("f0");
            if (f instanceof SelectChartFragment) {
                ((SelectChartFragment) f).refreshFromActivity();
            }
        } catch (Exception ignored) {
        }
    }

    private boolean isReadyToStart() {
        return musicFile != null && musicFile.exists()
                && chartFile != null && chartFile.exists()
                && bgFile != null && bgFile.exists();
    }

    void updateStartButtonState() {
        if (fabStart == null) return;
        boolean enable = isReadyToStart();
        fabStart.setEnabled(enable);
        fabStart.setAlpha(1f);
    }

    private void updateStartButtonText() {
        if (fabStart == null) return;
        fabStart.setText(autoplay ? "开始演示" : "开始");
    }

    private void startPlay() {
        int offsetMs = chartOffsetMs;
        if (offsetMs > 5000) offsetMs = 5000;
        if (offsetMs < -5000) offsetMs = -5000;

        Intent it = new Intent(this, PlayActivity.class);
        it.putExtra(PlayActivity.EXTRA_MUSIC_PATH, musicFile.getAbsolutePath());
        it.putExtra(PlayActivity.EXTRA_CHART_PATH, chartFile.getAbsolutePath());
        it.putExtra(PlayActivity.EXTRA_BG_PATH, bgFile.getAbsolutePath());
        it.putExtra(PlayActivity.EXTRA_SONG_NAME, songName);
        it.putExtra(PlayActivity.EXTRA_DIFFICULTY, difficulty);

        it.putExtra(PlayActivity.EXTRA_ASPECT_RATIO, aspectRatio <= 0f ? 0f : aspectRatio);
        it.putExtra(PlayActivity.EXTRA_CHART_OFFSET_MS, offsetMs);
        it.putExtra(PlayActivity.EXTRA_MUSIC_SPEED, musicSpeed);
        it.putExtra(PlayActivity.EXTRA_KEY_SCALE, keyScale);
        it.putExtra(PlayActivity.EXTRA_SCROLL_SPEED, scrollSpeed);
        it.putExtra(PlayActivity.EXTRA_SFX_VOLUME, clampPctToMul(sfxVolumePct));
        it.putExtra(PlayActivity.EXTRA_MUSIC_VOLUME, clampPctToMul(musicVolumePct));
        it.putExtra(PlayActivity.EXTRA_MIRROR_X, mirrorX);
        it.putExtra(PlayActivity.EXTRA_BG_DIM, backgroundDim);
        it.putExtra(PlayActivity.EXTRA_LOW_RES, lowResMode);
        it.putExtra(PlayActivity.EXTRA_ANTIALIAS, antialias);
        it.putExtra(PlayActivity.EXTRA_SHOW_FPS, showFps);
        it.putExtra(PlayActivity.EXTRA_SHOW_DEBUG, showDebugInfo);
        it.putExtra(PlayActivity.EXTRA_MULTI_HIGHLIGHT, multiPressHighlight);
        it.putExtra(PlayActivity.EXTRA_APFC, apfcIndicator);
        it.putExtra(PlayActivity.EXTRA_AUTOPLAY, autoplay);
        it.putExtra(PlayActivity.EXTRA_CHALLENGE, challengeMode);

        int skinIdx = SkinManager.getSelectedSkinIndex(this);
        List<SkinManager.SkinEntry> skinEntries = SkinManager.getSkinList(this);
        if (skinIdx > 0 && skinIdx < skinEntries.size()) {
            SkinManager.SkinEntry entry = skinEntries.get(skinIdx);
            if (!entry.isBuiltin()) {
                File skinDir = SkinManager.getSkinDir(this, entry.id);
                if (skinDir != null && skinDir.isDirectory()) {
                    it.putExtra(PlayActivity.EXTRA_SKIN_PATH, skinDir.getAbsolutePath());
                }
            }
        }

        startActivity(it);
    }

    private static float clampPctToMul(int pct) {
        int p = pct;
        if (p < 0) p = 0;
        if (p > 500) p = 500;
        return p / 100f;
    }

    private void toast(String s) {
        Toast.makeText(this, s, Toast.LENGTH_SHORT).show();
    }

    public void launchPickZip() {
        pickZipLauncher.launch(new String[]{"application/zip", "application/x-zip-compressed", "application/octet-stream", "*/*"});
    }

    public void launchPickMusic() {
        pickMusicLauncher.launch(new String[]{"audio/*", "application/ogg", "application/octet-stream"});
    }

    public void launchPickChart() {
        pickChartLauncher.launch(new String[]{"application/json", "text/*", "application/octet-stream"});
    }

    public void launchPickBg() {
        pickBgLauncher.launch(new String[]{"image/*"});
    }

    public void launchPickSkin() {
        pickSkinLauncher.launch(new String[]{"application/zip", "application/x-zip-compressed", "application/octet-stream", "*/*"});
    }

    public int getSelectedSkinIndex() {
        return SkinManager.getSelectedSkinIndex(this);
    }

    public void setSelectedSkinIndex(int index) {
        SkinManager.setSelectedSkinIndex(this, index);
    }

    private void notifyVideoTabSkinChanged() {
        try {
            androidx.fragment.app.Fragment f = getSupportFragmentManager().findFragmentByTag("f1");
            if (f instanceof SelectVideoFragment) {
                ((SelectVideoFragment) f).onResume();
            }
        } catch (Exception ignored) {
        }
    }

    public File getMusicFile()           { return musicFile; }
    public File getChartFile()           { return chartFile; }
    public File getBgFile()              { return bgFile; }
    public String getSongName()          { return songName; }
    public String getDifficulty()        { return difficulty; }
    public int getChartOffsetMs()        { return chartOffsetMs; }

    public void setSongName(String v, boolean fromUser) {
        if (fromUser) userEditedSongName = true;
        songName = v;
        updateStartButtonState();
    }

    public void setDifficulty(String v, boolean fromUser) {
        if (fromUser) userEditedDifficulty = true;
        difficulty = v;
        updateStartButtonState();
    }

    public void setChartOffsetMs(int ms) { chartOffsetMs = ms; }

    public float getAspectRatio()        { return aspectRatio; }
    public void setAspectRatio(float v)  { aspectRatio = sanitizeAspectRatio(v); editPrefs().putFloat(PREF_ASPECT_RATIO, aspectRatio).apply(); }
    public float getKeyScale()           { return keyScale; }
    public void setKeyScale(float v)     { keyScale = clampFinite(v, 0.7f, 1.5f, 1.0f); editPrefs().putFloat(PREF_KEY_SCALE, keyScale).apply(); }
    public float getScrollSpeed()        { return scrollSpeed; }
    public void setScrollSpeed(float v)  { scrollSpeed = clampFinite(v, 0.1f, 5.0f, 1.0f); editPrefs().putFloat(PREF_SCROLL_SPEED, scrollSpeed).apply(); }
    public boolean isMirrorX()           { return mirrorX; }
    public void setMirrorX(boolean v)    { mirrorX = v; editPrefs().putBoolean(PREF_MIRROR_X, mirrorX).apply(); }
    public boolean isAutoplay()          { return autoplay; }
    public void setAutoplay(boolean v)   { autoplay = v; editPrefs().putBoolean(PREF_AUTOPLAY, autoplay).apply(); updateStartButtonText(); }
    public boolean isChallengeMode()     { return challengeMode; }
    public void setChallengeMode(boolean v) { challengeMode = v; editPrefs().putBoolean(PREF_CHALLENGE, challengeMode).apply(); }
    public float getBackgroundDim()      { return backgroundDim; }
    public void setBackgroundDim(float v) { backgroundDim = clampFinite(v, 0f, 1f, 0.6f); editPrefs().putFloat(PREF_BG_DIM, backgroundDim).apply(); }
    public boolean isLowResMode()        { return lowResMode; }
    public void setLowResMode(boolean v) { lowResMode = v; editPrefs().putBoolean(PREF_LOW_RES, lowResMode).apply(); }
    public boolean isAntialias()         { return antialias; }
    public void setAntialias(boolean v)  { antialias = v; editPrefs().putBoolean(PREF_ANTIALIAS, antialias).apply(); }
    public boolean isShowFps()           { return showFps; }
    public void setShowFps(boolean v)    { showFps = v; editPrefs().putBoolean(PREF_SHOW_FPS, showFps).apply(); }
    public boolean isShowDebugInfo()     { return showDebugInfo; }
    public void setShowDebugInfo(boolean v) { showDebugInfo = v; editPrefs().putBoolean(PREF_SHOW_DEBUG, showDebugInfo).apply(); }
    public boolean isMultiPressHighlight()  { return multiPressHighlight; }
    public void setMultiPressHighlight(boolean v) { multiPressHighlight = v; editPrefs().putBoolean(PREF_MULTI_HIGHLIGHT, multiPressHighlight).apply(); }
    public boolean isApfcIndicator()     { return apfcIndicator; }
    public void setApfcIndicator(boolean v) { apfcIndicator = v; editPrefs().putBoolean(PREF_APFC, apfcIndicator).apply(); }
    public int getMusicVolumePct()       { return musicVolumePct; }
    public void setMusicVolumePct(int v) { musicVolumePct = clampInt(v, 0, 500); editPrefs().putInt(PREF_MUSIC_VOL_PCT, musicVolumePct).apply(); }
    public int getSfxVolumePct()         { return sfxVolumePct; }
    public void setSfxVolumePct(int v)   { sfxVolumePct = clampInt(v, 0, 500); editPrefs().putInt(PREF_SFX_VOL_PCT, sfxVolumePct).apply(); }
    public float getMusicSpeed()         { return musicSpeed; }
    public void setMusicSpeed(float v)   { musicSpeed = clampFinite(v, 0.5f, 2.0f, 1.0f); editPrefs().putFloat(PREF_MUSIC_SPEED, musicSpeed).apply(); }

    private void loadPersistedSettings() {
        SharedPreferences p = getPrefs();
        aspectRatio = sanitizeAspectRatio(p.getFloat(PREF_ASPECT_RATIO, aspectRatio));
        keyScale = clampFinite(p.getFloat(PREF_KEY_SCALE, keyScale), 0.7f, 1.5f, 1.0f);
        scrollSpeed = clampFinite(p.getFloat(PREF_SCROLL_SPEED, scrollSpeed), 0.1f, 5.0f, 1.0f);
        mirrorX = p.getBoolean(PREF_MIRROR_X, mirrorX);
        autoplay = p.getBoolean(PREF_AUTOPLAY, autoplay);
        challengeMode = p.getBoolean(PREF_CHALLENGE, challengeMode);
        backgroundDim = clampFinite(p.getFloat(PREF_BG_DIM, backgroundDim), 0f, 1f, 0.6f);
        lowResMode = p.getBoolean(PREF_LOW_RES, lowResMode);
        antialias = p.getBoolean(PREF_ANTIALIAS, antialias);
        showFps = p.getBoolean(PREF_SHOW_FPS, showFps);
        showDebugInfo = p.getBoolean(PREF_SHOW_DEBUG, showDebugInfo);
        multiPressHighlight = p.getBoolean(PREF_MULTI_HIGHLIGHT, multiPressHighlight);
        apfcIndicator = p.getBoolean(PREF_APFC, apfcIndicator);
        musicVolumePct = clampInt(p.getInt(PREF_MUSIC_VOL_PCT, musicVolumePct), 0, 500);
        sfxVolumePct = clampInt(p.getInt(PREF_SFX_VOL_PCT, sfxVolumePct), 0, 500);
        musicSpeed = clampFinite(p.getFloat(PREF_MUSIC_SPEED, musicSpeed), 0.5f, 2.0f, 1.0f);
    }

    private SharedPreferences getPrefs() {
        if (prefs == null) {
            prefs = getSharedPreferences(PREFS_NAME, MODE_PRIVATE);
        }
        return prefs;
    }

    private SharedPreferences.Editor editPrefs() {
        return getPrefs().edit();
    }

    private static float sanitizeAspectRatio(float v) {
        if (!Float.isFinite(v)) return 0f;
        if (v == 0f) return 0f;
        return clampFinite(v, 0.5f, 5.0f, 16f / 9f);
    }

    private static float clampFinite(float v, float lo, float hi, float fallback) {
        if (!Float.isFinite(v)) return fallback;
        if (v < lo) return lo;
        if (v > hi) return hi;
        return v;
    }

    private static int clampInt(int v, int lo, int hi) {
        if (v < lo) return lo;
        if (v > hi) return hi;
        return v;
    }
}
