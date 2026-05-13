package com.wuying.phigros.ui;

import android.os.Bundle;
import android.text.Editable;
import android.text.TextUtils;
import android.text.TextWatcher;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.fragment.app.Fragment;

import com.google.android.material.slider.Slider;
import com.google.android.material.textfield.TextInputEditText;
import com.wuying.phigros.R;

import java.util.Locale;

public class SelectAudioFragment extends Fragment {

    private TextInputEditText etMusicVol;
    private Slider sliderMusicVol;
    private TextInputEditText etSfxVol;
    private Slider sliderSfxVol;
    private TextInputEditText etSpeed;
    private Slider sliderSpeed;

    private boolean suppress = false;

    @Nullable
    @Override
    public View onCreateView(@NonNull LayoutInflater inflater, @Nullable ViewGroup container, @Nullable Bundle savedInstanceState) {
        return inflater.inflate(R.layout.fragment_select_audio, container, false);
    }

    @Override
    public void onViewCreated(@NonNull View view, @Nullable Bundle savedInstanceState) {
        etMusicVol = view.findViewById(R.id.et_music_volume);
        sliderMusicVol = view.findViewById(R.id.slider_music_volume);
        etSfxVol = view.findViewById(R.id.et_sfx_volume);
        sliderSfxVol = view.findViewById(R.id.slider_sfx_volume);
        etSpeed = view.findViewById(R.id.et_music_speed);
        sliderSpeed = view.findViewById(R.id.slider_music_speed);

        if (sliderMusicVol != null) {
            sliderMusicVol.setValueFrom(0f);
            sliderMusicVol.setValueTo(500f);
            sliderMusicVol.setStepSize(1f);
            sliderMusicVol.addOnChangeListener((slider, value, fromUser) -> {
                if (suppress) return;
                int v = clampInt(Math.round(value), 0, 500);
                host().setMusicVolumePct(v);
                suppress = true;
                if (etMusicVol != null) etMusicVol.setText(String.valueOf(v));
                suppress = false;
            });
        }
        if (etMusicVol != null) {
            etMusicVol.addTextChangedListener(new SimpleWatcher() {
                @Override
                public void afterTextChanged(Editable s) {
                    if (suppress) return;
                    int v = parseIntSafe(s == null ? "" : s.toString(), host().getMusicVolumePct());
                    v = clampInt(v, 0, 500);
                    host().setMusicVolumePct(v);
                    suppress = true;
                    if (sliderMusicVol != null) sliderMusicVol.setValue(v);
                    suppress = false;
                }
            });
        }

        if (sliderSfxVol != null) {
            sliderSfxVol.setValueFrom(0f);
            sliderSfxVol.setValueTo(500f);
            sliderSfxVol.setStepSize(1f);
            sliderSfxVol.addOnChangeListener((slider, value, fromUser) -> {
                if (suppress) return;
                int v = clampInt(Math.round(value), 0, 500);
                host().setSfxVolumePct(v);
                suppress = true;
                if (etSfxVol != null) etSfxVol.setText(String.valueOf(v));
                suppress = false;
            });
        }
        if (etSfxVol != null) {
            etSfxVol.addTextChangedListener(new SimpleWatcher() {
                @Override
                public void afterTextChanged(Editable s) {
                    if (suppress) return;
                    int v = parseIntSafe(s == null ? "" : s.toString(), host().getSfxVolumePct());
                    v = clampInt(v, 0, 500);
                    host().setSfxVolumePct(v);
                    suppress = true;
                    if (sliderSfxVol != null) sliderSfxVol.setValue(v);
                    suppress = false;
                }
            });
        }

        if (sliderSpeed != null) {
            sliderSpeed.setValueFrom(0.5f);
            sliderSpeed.setValueTo(2.0f);
            sliderSpeed.addOnChangeListener((slider, value, fromUser) -> {
                if (suppress) return;
                float v = clamp(value, 0.5f, 2.0f);
                host().setMusicSpeed(v);
                suppress = true;
                if (etSpeed != null) etSpeed.setText(formatFloat(v));
                suppress = false;
            });
        }
        if (etSpeed != null) {
            etSpeed.addTextChangedListener(new SimpleWatcher() {
                @Override
                public void afterTextChanged(Editable s) {
                    if (suppress) return;
                    float v = parseFloatSafe(s == null ? "" : s.toString());
                    if (!Float.isFinite(v)) return;
                    v = clamp(v, 0.5f, 2.0f);
                    host().setMusicSpeed(v);
                    suppress = true;
                    if (sliderSpeed != null) sliderSpeed.setValue(v);
                    suppress = false;
                }
            });
        }

        refreshFromActivity();
    }

    @Override
    public void onResume() {
        super.onResume();
        refreshFromActivity();
    }

    private void refreshFromActivity() {
        ChartSelectActivity a = host();
        suppress = true;
        int mv = clampInt(a.getMusicVolumePct(), 0, 500);
        int sv = clampInt(a.getSfxVolumePct(), 0, 500);
        float sp = clamp(a.getMusicSpeed(), 0.5f, 2.0f);
        if (etMusicVol != null) etMusicVol.setText(String.valueOf(mv));
        if (sliderMusicVol != null) sliderMusicVol.setValue(mv);
        if (etSfxVol != null) etSfxVol.setText(String.valueOf(sv));
        if (sliderSfxVol != null) sliderSfxVol.setValue(sv);
        if (etSpeed != null) etSpeed.setText(formatFloat(sp));
        if (sliderSpeed != null) sliderSpeed.setValue(sp);
        suppress = false;
    }

    private static int parseIntSafe(String s, int fallback) {
        if (TextUtils.isEmpty(s)) return fallback;
        String x = s.trim();
        try {
            return Integer.parseInt(x);
        } catch (Exception ignored) {
            return fallback;
        }
    }

    private static float parseFloatSafe(String s) {
        if (TextUtils.isEmpty(s)) return Float.NaN;
        String x = s.trim().replace(",", ".");
        try {
            return Float.parseFloat(x);
        } catch (Exception ignored) {
            return Float.NaN;
        }
    }

    private static float clamp(float v, float lo, float hi) {
        return v < lo ? lo : (v > hi ? hi : v);
    }

    private static int clampInt(int v, int lo, int hi) {
        return v < lo ? lo : (v > hi ? hi : v);
    }

    private static String formatFloat(float v) {
        return String.format(Locale.US, "%.2f", v);
    }

    private ChartSelectActivity host() {
        return (ChartSelectActivity) requireActivity();
    }

    private abstract static class SimpleWatcher implements TextWatcher {
        @Override public void beforeTextChanged(CharSequence s, int start, int count, int after) {}
        @Override public void onTextChanged(CharSequence s, int start, int before, int count) {}
    }
}
