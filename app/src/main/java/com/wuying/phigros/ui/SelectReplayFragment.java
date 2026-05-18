package com.wuying.phigros.ui;

import android.content.Intent;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Typeface;
import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.CheckBox;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.appcompat.app.AlertDialog;
import androidx.fragment.app.Fragment;

import com.google.android.material.button.MaterialButton;
import com.google.android.material.card.MaterialCardView;
import com.google.android.material.switchmaterial.SwitchMaterial;
import com.wuying.phigros.R;
import com.wuying.phigros.game.ReplayData;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

public class SelectReplayFragment extends Fragment {

    private SwitchMaterial swReplayEnable;
    private MaterialButton btnImport;
    private MaterialButton btnExport;
    private MaterialButton btnDelete;
    private LinearLayout replayListContainer;

    private boolean selectionMode = false;
    private final Set<String> selectedUuids = new HashSet<>();

    @Nullable
    @Override
    public View onCreateView(@NonNull LayoutInflater inflater, @Nullable ViewGroup container, @Nullable Bundle savedInstanceState) {
        return inflater.inflate(R.layout.fragment_select_replay, container, false);
    }

    @Override
    public void onViewCreated(@NonNull View view, @Nullable Bundle savedInstanceState) {
        swReplayEnable = view.findViewById(R.id.sw_replay_enable);
        btnImport = view.findViewById(R.id.btn_replay_import);
        btnExport = view.findViewById(R.id.btn_replay_export);
        btnDelete = view.findViewById(R.id.btn_replay_delete);
        replayListContainer = view.findViewById(R.id.replay_list_container);

        if (swReplayEnable != null) {
            swReplayEnable.setChecked(ReplayManager.isReplayEnabled(requireContext()));
            swReplayEnable.setOnCheckedChangeListener((buttonView, isChecked) -> {
                ReplayManager.setReplayEnabled(requireContext(), isChecked);
                host().setReplayEnabled(isChecked);
            });
        }

        if (btnImport != null) {
            btnImport.setOnClickListener(v -> host().launchPickReplayZip());
        }

        if (btnExport != null) {
            btnExport.setOnClickListener(v -> {
                if (!selectionMode) {
                    enterSelectionMode();
                } else {
                    if (selectedUuids.isEmpty()) {
                        exitSelectionMode();
                        return;
                    }
                    try {
                        int count = ReplayManager.exportReplaysToCgrp(requireContext(), new ArrayList<>(selectedUuids));
                        Toast.makeText(requireContext(), "已导出 " + count + " 个回放到 Download/Chigros/", Toast.LENGTH_SHORT).show();
                    } catch (IOException e) {
                        Toast.makeText(requireContext(), "导出失败：" + e.getMessage(), Toast.LENGTH_SHORT).show();
                    }
                    exitSelectionMode();
                }
            });
        }

        if (btnDelete != null) {
            btnDelete.setOnClickListener(v -> {
                if (!selectionMode) {
                    enterSelectionMode();
                } else {
                    if (selectedUuids.isEmpty()) {
                        exitSelectionMode();
                        return;
                    }
                    new AlertDialog.Builder(requireContext())
                            .setTitle("确认删除")
                            .setMessage("确定要删除选中的 " + selectedUuids.size() + " 个回放吗？")
                            .setPositiveButton("删除", (dialog, which) -> {
                                for (String uuid : new ArrayList<>(selectedUuids)) {
                                    ReplayManager.deleteReplay(requireContext(), uuid);
                                }
                                selectedUuids.clear();
                                exitSelectionMode();
                                refreshList();
                            })
                            .setNegativeButton("取消", null)
                            .show();
                }
            });
        }

        refreshList();
    }

    @Override
    public void onResume() {
        super.onResume();
        refreshList();
    }

    private void enterSelectionMode() {
        selectionMode = true;
        selectedUuids.clear();
        refreshList();
    }

    private void exitSelectionMode() {
        selectionMode = false;
        selectedUuids.clear();
        refreshList();
    }

    public void refreshFromActivity() {
        if (swReplayEnable != null) {
            swReplayEnable.setChecked(ReplayManager.isReplayEnabled(requireContext()));
        }
        refreshList();
    }

    private void refreshList() {
        if (replayListContainer == null) return;
        replayListContainer.removeAllViews();

        List<ReplayManager.ReplayInfo> replays = ReplayManager.getReplayList(requireContext());
        if (replays.isEmpty()) {
            TextView empty = new TextView(requireContext());
            empty.setText("暂无回放记录");
            empty.setAlpha(0.5f);
            empty.setPadding(0, 8, 0, 8);
            replayListContainer.addView(empty);
            return;
        }

        for (ReplayManager.ReplayInfo info : replays) {
            replayListContainer.addView(buildReplayItem(info));
        }
    }

    private View buildReplayItem(ReplayManager.ReplayInfo info) {
        MaterialCardView card = new MaterialCardView(requireContext());
        LinearLayout.LayoutParams cardLp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        cardLp.topMargin = dp(4);
        cardLp.bottomMargin = dp(4);
        card.setLayoutParams(cardLp);
        card.setCardElevation(0);
        card.setStrokeWidth(dp(1));
        card.setStrokeColor(0x22000000);
        card.setRadius(dp(8));
        card.setClickable(true);
        card.setFocusable(true);

        LinearLayout row = new LinearLayout(requireContext());
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setPadding(dp(12), dp(8), dp(8), dp(8));
        row.setGravity(android.view.Gravity.CENTER_VERTICAL);

        // Checkbox (selection mode only)
        CheckBox checkBox = new CheckBox(requireContext());
        checkBox.setVisibility(selectionMode ? View.VISIBLE : View.GONE);
        checkBox.setChecked(selectedUuids.contains(info.uuid));
        LinearLayout.LayoutParams cbLp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        cbLp.gravity = android.view.Gravity.CENTER_VERTICAL;
        cbLp.setMarginEnd(dp(8));
        checkBox.setLayoutParams(cbLp);
        checkBox.setOnCheckedChangeListener((buttonView, isChecked) -> {
            if (isChecked) selectedUuids.add(info.uuid);
            else selectedUuids.remove(info.uuid);
        });

        // Info text
        LinearLayout infoCol = new LinearLayout(requireContext());
        infoCol.setOrientation(LinearLayout.VERTICAL);
        LinearLayout.LayoutParams infoLp = new LinearLayout.LayoutParams(
                0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f);
        infoCol.setLayoutParams(infoLp);

        TextView tvTitle = new TextView(requireContext());
        tvTitle.setText(info.songName + "  " + info.difficulty);
        tvTitle.setTextSize(14);
        tvTitle.setSingleLine(true);
        tvTitle.setTypeface(tvTitle.getTypeface(), Typeface.BOLD);

        TextView tvSubtitle = new TextView(requireContext());
        String dateStr = new SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.getDefault())
                .format(new Date(info.timestamp));
        tvSubtitle.setText(String.format(Locale.US, "%07d  %s", info.score, dateStr));
        tvSubtitle.setTextSize(12);
        tvSubtitle.setAlpha(0.6f);

        infoCol.addView(tvTitle);
        infoCol.addView(tvSubtitle);

        // Favorite button (asset images)
        ImageView btnFav = new ImageView(requireContext());
        btnFav.setImageBitmap(loadAssetBitmap(info.favorite ? "res/like_1.png" : "res/like_0.png"));
        btnFav.setPadding(dp(8), dp(4), dp(8), dp(4));
        int btnSize = dp(32);
        LinearLayout.LayoutParams favLp = new LinearLayout.LayoutParams(btnSize, btnSize);
        favLp.gravity = android.view.Gravity.CENTER_VERTICAL;
        btnFav.setLayoutParams(favLp);
        btnFav.setScaleType(ImageView.ScaleType.FIT_CENTER);
        btnFav.setOnClickListener(v -> {
            boolean newFav = !info.favorite;
            ReplayManager.setFavorite(requireContext(), info.uuid, newFav);
            info.favorite = newFav;
            btnFav.setImageBitmap(loadAssetBitmap(newFav ? "res/like_1.png" : "res/like_0.png"));
            refreshList();
        });

        // Play button (asset image)
        ImageView btnPlay = new ImageView(requireContext());
        btnPlay.setImageBitmap(loadAssetBitmap("res/play.png"));
        btnPlay.setPadding(dp(8), dp(4), dp(4), dp(4));
        LinearLayout.LayoutParams playLp = new LinearLayout.LayoutParams(btnSize, btnSize);
        playLp.gravity = android.view.Gravity.CENTER_VERTICAL;
        btnPlay.setLayoutParams(playLp);
        btnPlay.setScaleType(ImageView.ScaleType.FIT_CENTER);
        btnPlay.setOnClickListener(v -> playReplay(info.uuid));

        row.addView(checkBox);
        row.addView(infoCol);
        row.addView(btnFav);
        row.addView(btnPlay);
        card.addView(row);

        // Click / long press
        card.setOnClickListener(v -> {
            if (selectionMode) {
                checkBox.setChecked(!checkBox.isChecked());
            }
        });
        card.setOnLongClickListener(v -> {
            if (!selectionMode) {
                enterSelectionMode();
                selectedUuids.add(info.uuid);
                refreshList();
                return true;
            }
            return false;
        });

        return card;
    }

    private Bitmap loadAssetBitmap(String path) {
        try (InputStream is = requireContext().getAssets().open(path)) {
            return BitmapFactory.decodeStream(is);
        } catch (IOException e) {
            return null;
        }
    }

    private void playReplay(String uuid) {
        ReplayData data = ReplayManager.getReplayData(requireContext(), uuid);
        if (data == null || data.meta == null) {
            Toast.makeText(requireContext(), "回放数据损坏", Toast.LENGTH_SHORT).show();
            return;
        }

        File chartFile = ReplayManager.getChartFile(requireContext(), uuid);
        File musicFile = ReplayManager.getMusicFile(requireContext(), uuid);
        File bgFile = ReplayManager.getBgFile(requireContext(), uuid);
        File replayJson = new File(ReplayManager.getReplayDir(requireContext(), uuid), "replay.json");

        if (chartFile == null || musicFile == null || bgFile == null || !replayJson.exists()) {
            Toast.makeText(requireContext(), "回放资源不完整", Toast.LENGTH_SHORT).show();
            return;
        }

        // Read stored settings
        ReplayManager.InfoJson info = ReplayManager.getReplayInfo(requireContext(), uuid);

        Intent it = new Intent(requireContext(), PlayActivity.class);
        it.putExtra(PlayActivity.EXTRA_MUSIC_PATH, musicFile.getAbsolutePath());
        it.putExtra(PlayActivity.EXTRA_CHART_PATH, chartFile.getAbsolutePath());
        it.putExtra(PlayActivity.EXTRA_BG_PATH, bgFile.getAbsolutePath());
        it.putExtra(PlayActivity.EXTRA_SONG_NAME, data.meta.songName);
        it.putExtra(PlayActivity.EXTRA_DIFFICULTY, data.meta.difficulty);
        it.putExtra(PlayActivity.EXTRA_AUTOPLAY, false);
        it.putExtra(PlayActivity.EXTRA_REPLAY_MODE, true);
        it.putExtra(PlayActivity.EXTRA_REPLAY_PATH, replayJson.getAbsolutePath());

        // Apply stored settings (or defaults)
        if (info != null) {
            it.putExtra(PlayActivity.EXTRA_ASPECT_RATIO, info.aspectRatio);
            it.putExtra(PlayActivity.EXTRA_KEY_SCALE, info.keyScale > 0 ? info.keyScale : 1.0f);
            it.putExtra(PlayActivity.EXTRA_SCROLL_SPEED, info.scrollSpeed > 0 ? info.scrollSpeed : 1.0f);
            it.putExtra(PlayActivity.EXTRA_MIRROR_X, info.mirrorX);
            it.putExtra(PlayActivity.EXTRA_BG_DIM, info.bgDim > 0 ? info.bgDim : 0.6f);
            it.putExtra(PlayActivity.EXTRA_LOW_RES, info.lowRes);
            it.putExtra(PlayActivity.EXTRA_ANTIALIAS, info.antialias);
            it.putExtra(PlayActivity.EXTRA_SHOW_FPS, info.showFps);
            it.putExtra(PlayActivity.EXTRA_SHOW_DEBUG, info.showDebug);
            it.putExtra(PlayActivity.EXTRA_MULTI_HIGHLIGHT, info.multiHighlight);
            it.putExtra(PlayActivity.EXTRA_APFC, info.apfc);
            it.putExtra(PlayActivity.EXTRA_CHALLENGE, info.challenge);
            it.putExtra(PlayActivity.EXTRA_MUSIC_SPEED, info.musicSpeed > 0 ? info.musicSpeed : 1.0f);
            it.putExtra(PlayActivity.EXTRA_MUSIC_VOLUME, info.musicVolPct / 100f);
            it.putExtra(PlayActivity.EXTRA_SFX_VOLUME, info.sfxVolPct / 100f);
            it.putExtra(PlayActivity.EXTRA_CHART_OFFSET_MS, info.chartOffsetMs);
        } else {
            it.putExtra(PlayActivity.EXTRA_ASPECT_RATIO, 0f);
            it.putExtra(PlayActivity.EXTRA_MUSIC_SPEED, 1.0f);
            it.putExtra(PlayActivity.EXTRA_KEY_SCALE, 1.0f);
            it.putExtra(PlayActivity.EXTRA_SCROLL_SPEED, 1.0f);
            it.putExtra(PlayActivity.EXTRA_MUSIC_VOLUME, 1.0f);
            it.putExtra(PlayActivity.EXTRA_SFX_VOLUME, 1.0f);
            it.putExtra(PlayActivity.EXTRA_MIRROR_X, false);
            it.putExtra(PlayActivity.EXTRA_BG_DIM, 0.6f);
            it.putExtra(PlayActivity.EXTRA_LOW_RES, false);
            it.putExtra(PlayActivity.EXTRA_CHALLENGE, false);
            it.putExtra(PlayActivity.EXTRA_SHOW_FPS, false);
            it.putExtra(PlayActivity.EXTRA_SHOW_DEBUG, false);
        }

        // Apply current skin
        int skinIdx = SkinManager.getSelectedSkinIndex(requireContext());
        List<SkinManager.SkinEntry> skinEntries = SkinManager.getSkinList(requireContext());
        if (skinIdx > 0 && skinIdx < skinEntries.size()) {
            SkinManager.SkinEntry entry = skinEntries.get(skinIdx);
            if (!entry.isBuiltin()) {
                File skinDir = SkinManager.getSkinDir(requireContext(), entry.id);
                if (skinDir != null && skinDir.isDirectory()) {
                    it.putExtra(PlayActivity.EXTRA_SKIN_PATH, skinDir.getAbsolutePath());
                }
            }
        }

        startActivity(it);
    }

    private int dp(int dp) {
        return (int) (dp * requireContext().getResources().getDisplayMetrics().density);
    }

    private ChartSelectActivity host() {
        return (ChartSelectActivity) requireActivity();
    }
}
