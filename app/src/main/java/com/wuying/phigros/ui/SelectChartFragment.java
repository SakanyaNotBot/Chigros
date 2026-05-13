package com.wuying.phigros.ui;

import android.os.Bundle;
import android.text.Editable;
import android.text.TextUtils;
import android.text.TextWatcher;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.fragment.app.Fragment;

import com.google.android.material.button.MaterialButton;
import com.google.android.material.textfield.TextInputEditText;
import com.google.android.material.textfield.TextInputLayout;
import com.wuying.phigros.R;

import java.io.File;

public class SelectChartFragment extends Fragment {

    private TextView tvPackStatus;
    private TextView tvMusic;
    private TextView tvChart;
    private TextView tvBg;
    private TextInputLayout tilSongName;
    private TextInputLayout tilDifficulty;
    private TextInputEditText etSongName;
    private TextInputEditText etDifficulty;
    private TextInputEditText etOffset;
    private boolean suppressTextCallbacks = false;

    @Nullable
    @Override
    public View onCreateView(@NonNull LayoutInflater inflater, @Nullable ViewGroup container, @Nullable Bundle savedInstanceState) {
        return inflater.inflate(R.layout.fragment_select_chart, container, false);
    }

    @Override
    public void onViewCreated(@NonNull View view, @Nullable Bundle savedInstanceState) {
        tvPackStatus = view.findViewById(R.id.tv_pack_status);
        tvMusic = view.findViewById(R.id.tv_music);
        tvChart = view.findViewById(R.id.tv_chart);
        tvBg = view.findViewById(R.id.tv_bg);
        tilSongName = view.findViewById(R.id.til_song_name);
        tilDifficulty = view.findViewById(R.id.til_difficulty);
        etSongName = view.findViewById(R.id.et_song_name);
        etDifficulty = view.findViewById(R.id.et_difficulty);
        etOffset = view.findViewById(R.id.et_chart_offset_ms);

        MaterialButton btnZip = view.findViewById(R.id.btn_pick_zip);
        MaterialButton btnMusic = view.findViewById(R.id.btn_pick_music);
        MaterialButton btnChartPick = view.findViewById(R.id.btn_pick_chart);
        MaterialButton btnBgPick = view.findViewById(R.id.btn_pick_bg);

        btnZip.setOnClickListener(v -> host().launchPickZip());
        btnMusic.setOnClickListener(v -> host().launchPickMusic());
        btnChartPick.setOnClickListener(v -> host().launchPickChart());
        btnBgPick.setOnClickListener(v -> host().launchPickBg());

        if (etSongName != null) {
            etSongName.addTextChangedListener(new SimpleWatcher() {
                @Override
                public void afterTextChanged(Editable s) {
                    if (suppressTextCallbacks) return;
                    host().setSongName(s == null ? "" : s.toString(), true);
                }
            });
        }
        if (etDifficulty != null) {
            etDifficulty.addTextChangedListener(new SimpleWatcher() {
                @Override
                public void afterTextChanged(Editable s) {
                    if (suppressTextCallbacks) return;
                    host().setDifficulty(s == null ? "" : s.toString(), true);
                }
            });
        }
        if (etOffset != null) {
            etOffset.addTextChangedListener(new SimpleWatcher() {
                @Override
                public void afterTextChanged(Editable s) {
                    if (suppressTextCallbacks) return;
                    int v = 0;
                    try {
                        String raw = s == null ? "" : s.toString().trim();
                        if (!TextUtils.isEmpty(raw)) v = Integer.parseInt(raw);
                    } catch (Exception ignored) {
                    }
                    host().setChartOffsetMs(v);
                }
            });
            etOffset.setText(String.valueOf(host().getChartOffsetMs()));
        }

        refreshFromActivity();
    }

    @Override
    public void onResume() {
        super.onResume();
        refreshFromActivity();
    }

    public void refreshFromActivity() {
        ChartSelectActivity a = host();
        File music = a.getMusicFile();
        File chart = a.getChartFile();
        File bg = a.getBgFile();

        if (tvPackStatus != null) {
            String s = "未选择";
            if (music != null || chart != null || bg != null) {
                s = "已载入："
                        + (music != null ? " 音乐" : "")
                        + (chart != null ? " 铺面" : "")
                        + (bg != null ? " 背景" : "");
            }
            tvPackStatus.setText(s);
        }

        if (tvMusic != null) tvMusic.setText("音乐：" + (music == null ? "未选择" : music.getName()));
        if (tvChart != null) tvChart.setText("铺面：" + (chart == null ? "未选择" : chart.getName()));
        if (tvBg != null) tvBg.setText("背景：" + (bg == null ? "未选择" : bg.getName()));

        boolean enableInfo = (chart != null && chart.exists());
        if (tilSongName != null) tilSongName.setEnabled(enableInfo);
        if (tilDifficulty != null) tilDifficulty.setEnabled(enableInfo);

        if (enableInfo) {
            String name = a.getSongName();
            String diff = a.getDifficulty();
            suppressTextCallbacks = true;
            if (etSongName != null) {
                String cur = etSongName.getText() == null ? "" : etSongName.getText().toString();
                String target = name == null ? "" : name;
                if (!TextUtils.equals(cur, target)) etSongName.setText(target);
            }
            if (etDifficulty != null) {
                String cur = etDifficulty.getText() == null ? "" : etDifficulty.getText().toString();
                String target = diff == null ? "" : diff;
                if (!TextUtils.equals(cur, target)) etDifficulty.setText(target);
            }
            suppressTextCallbacks = false;
        }

        a.updateStartButtonState();
    }

    private ChartSelectActivity host() {
        return (ChartSelectActivity) requireActivity();
    }

    private abstract static class SimpleWatcher implements TextWatcher {
        @Override public void beforeTextChanged(CharSequence s, int start, int count, int after) {}
        @Override public void onTextChanged(CharSequence s, int start, int before, int count) {}
    }
}
