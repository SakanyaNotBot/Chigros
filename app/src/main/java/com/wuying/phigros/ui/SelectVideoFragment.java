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

import com.google.android.material.button.MaterialButton;
import com.google.android.material.slider.Slider;
import com.google.android.material.switchmaterial.SwitchMaterial;
import com.google.android.material.textfield.MaterialAutoCompleteTextView;
import com.google.android.material.textfield.TextInputEditText;
import com.wuying.phigros.R;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

public class SelectVideoFragment extends Fragment {

    private static final String[] PRESET_RATIO_LABELS = new String[]{
            null, "5:4", "4:3", "10:7", "19:13", "8:5", "5:3", "22:13", "16:9"
    };
    private static final float[] PRESET_VALUES = new float[]{
            0f, 1.25f, 1.3333333f, 1.4285714f, 1.4615385f, 1.6f, 1.6666667f, 1.6923077f, 1.7777778f
    };

    private TextInputEditText etAspect;
    private MaterialAutoCompleteTextView actPreset;
    private TextInputEditText etKeyScale;
    private Slider sliderKeyScale;
    private TextInputEditText etScrollSpeed;
    private Slider sliderScrollSpeed;
    private SwitchMaterial swMirrorX;
    private SwitchMaterial swAutoplay;
    private SwitchMaterial swChallenge;
    private Slider sliderBgDim;
    private SwitchMaterial swLowRes;
    private SwitchMaterial swAa;
    private SwitchMaterial swShowFps;
    private SwitchMaterial swShowDebug;
    private SwitchMaterial swMultiHighlight;
    private SwitchMaterial swApfc;

    private MaterialAutoCompleteTextView actSkin;
    private MaterialButton btnSkinImport;
    private MaterialButton btnSkinDelete;

    private boolean suppress = false;

    @Nullable
    @Override
    public View onCreateView(@NonNull LayoutInflater inflater, @Nullable ViewGroup container, @Nullable Bundle savedInstanceState) {
        return inflater.inflate(R.layout.fragment_select_video, container, false);
    }

    @Override
    public void onViewCreated(@NonNull View view, @Nullable Bundle savedInstanceState) {
        etAspect = view.findViewById(R.id.et_aspect_decimal);
        actPreset = view.findViewById(R.id.act_aspect_preset);
        etKeyScale = view.findViewById(R.id.et_key_scale);
        sliderKeyScale = view.findViewById(R.id.slider_key_scale);
        etScrollSpeed = view.findViewById(R.id.et_scroll_speed);
        sliderScrollSpeed = view.findViewById(R.id.slider_scroll_speed);
        swMirrorX = view.findViewById(R.id.sw_mirror_x);
        swAutoplay = view.findViewById(R.id.sw_autoplay);
        swChallenge = view.findViewById(R.id.sw_challenge);
        sliderBgDim = view.findViewById(R.id.slider_bg_dim);
        swLowRes = view.findViewById(R.id.sw_low_res);
        swAa = view.findViewById(R.id.sw_antialias);
        swShowFps = view.findViewById(R.id.sw_show_fps);
        swShowDebug = view.findViewById(R.id.sw_show_debug);
        swMultiHighlight = view.findViewById(R.id.sw_multi_highlight);
        swApfc = view.findViewById(R.id.sw_apfc);

        actSkin = view.findViewById(R.id.act_skin);
        btnSkinImport = view.findViewById(R.id.btn_skin_import);
        btnSkinDelete = view.findViewById(R.id.btn_skin_delete);

        if (btnSkinImport != null) {
            btnSkinImport.setOnClickListener(v -> host().launchPickSkin());
        }
        if (btnSkinDelete != null) {
            btnSkinDelete.setOnClickListener(v -> {
                int idx = host().getSelectedSkinIndex();
                List<SkinManager.SkinEntry> entries = SkinManager.getSkinList(requireContext());
                if (idx > 0 && idx < entries.size()) {
                    SkinManager.SkinEntry entry = entries.get(idx);
                    if (!entry.isBuiltin()) {
                        SkinManager.deleteSkin(requireContext(), entry.id);
                        host().setSelectedSkinIndex(0);
                        refreshSkinDropdown();
                    }
                }
            });
        }
        if (actSkin != null) {
            refreshSkinDropdown();
        }

        if (actPreset != null) {
            android.widget.ArrayAdapter<String> ad = new android.widget.ArrayAdapter<>(
                    requireContext(),
                    android.R.layout.simple_list_item_1,
                    buildPresetLabels()
            );
            actPreset.setAdapter(ad);
            actPreset.setOnItemClickListener((parent, v, position, id) -> {
                if (suppress) return;
                float val = (position >= 0 && position < PRESET_VALUES.length) ? PRESET_VALUES[position] : 0f;
                applyPresetSelection(getPresetLabel(position), val);
            });
        }

        if (etAspect != null) {
            etAspect.addTextChangedListener(new SimpleWatcher() {
                @Override
                public void afterTextChanged(Editable s) {
                    if (suppress) return;
                    float v = parseFloatSafe(s == null ? "" : s.toString());
                    if (!(v > 0f) || !Float.isFinite(v)) return;
                    ChartSelectActivity a = host();
                    a.setAspectRatio(v);
                    updatePresetLabelForValue(v);
                }
            });
        }

        if (sliderKeyScale != null) {
            sliderKeyScale.setValueFrom(0.7f);
            sliderKeyScale.setValueTo(1.5f);
            sliderKeyScale.addOnChangeListener((slider, value, fromUser) -> {
                if (suppress) return;
                suppress = true;
                if (etKeyScale != null) etKeyScale.setText(formatFloat(value));
                suppress = false;
                host().setKeyScale(clamp(value, 0.7f, 1.5f));
            });
        }
        if (etKeyScale != null) {
            etKeyScale.addTextChangedListener(new SimpleWatcher() {
                @Override
                public void afterTextChanged(Editable s) {
                    if (suppress) return;
                    float v = parseFloatSafe(s == null ? "" : s.toString());
                    if (!Float.isFinite(v)) return;
                    v = clamp(v, 0.7f, 1.5f);
                    host().setKeyScale(v);
                    suppress = true;
                    if (sliderKeyScale != null) sliderKeyScale.setValue(v);
                    suppress = false;
                }
            });
        }

        if (sliderScrollSpeed != null) {
            sliderScrollSpeed.setValueFrom(0.1f);
            sliderScrollSpeed.setValueTo(5.0f);
            sliderScrollSpeed.addOnChangeListener((slider, value, fromUser) -> {
                if (suppress) return;
                suppress = true;
                if (etScrollSpeed != null) etScrollSpeed.setText(formatFloat(value));
                suppress = false;
                host().setScrollSpeed(clamp(value, 0.1f, 5.0f));
            });
        }
        if (etScrollSpeed != null) {
            etScrollSpeed.addTextChangedListener(new SimpleWatcher() {
                @Override
                public void afterTextChanged(Editable s) {
                    if (suppress) return;
                    float v = parseFloatSafe(s == null ? "" : s.toString());
                    if (!Float.isFinite(v)) return;
                    v = clamp(v, 0.1f, 5.0f);
                    host().setScrollSpeed(v);
                    suppress = true;
                    if (sliderScrollSpeed != null) sliderScrollSpeed.setValue(v);
                    suppress = false;
                }
            });
        }

        if (swMirrorX != null) swMirrorX.setOnCheckedChangeListener((buttonView, isChecked) -> host().setMirrorX(isChecked));
        if (swAutoplay != null) swAutoplay.setOnCheckedChangeListener((buttonView, isChecked) -> host().setAutoplay(isChecked));
        if (swChallenge != null) swChallenge.setOnCheckedChangeListener((buttonView, isChecked) -> host().setChallengeMode(isChecked));
        if (swLowRes != null) swLowRes.setOnCheckedChangeListener((buttonView, isChecked) -> host().setLowResMode(isChecked));
        if (swAa != null) swAa.setOnCheckedChangeListener((buttonView, isChecked) -> host().setAntialias(isChecked));
        if (swShowFps != null) swShowFps.setOnCheckedChangeListener((buttonView, isChecked) -> host().setShowFps(isChecked));
        if (swShowDebug != null) swShowDebug.setOnCheckedChangeListener((buttonView, isChecked) -> host().setShowDebugInfo(isChecked));
        if (swMultiHighlight != null) swMultiHighlight.setOnCheckedChangeListener((buttonView, isChecked) -> host().setMultiPressHighlight(isChecked));
        if (swApfc != null) swApfc.setOnCheckedChangeListener((buttonView, isChecked) -> host().setApfcIndicator(isChecked));

        if (sliderBgDim != null) {
            sliderBgDim.setValueFrom(0f);
            sliderBgDim.setValueTo(1f);
            sliderBgDim.addOnChangeListener((slider, value, fromUser) -> host().setBackgroundDim(1f - clamp(value, 0f, 1f)));
        }

        refreshFromActivity();
    }

    @Override
    public void onResume() {
        super.onResume();
        refreshFromActivity();
    }

    private void refreshSkinDropdown() {
        if (actSkin == null) return;
        List<SkinManager.SkinEntry> entries = SkinManager.getSkinList(requireContext());
        List<String> names = new ArrayList<>();
        for (SkinManager.SkinEntry e : entries) {
            names.add(e.name);
        }
        android.widget.ArrayAdapter<String> ad = new android.widget.ArrayAdapter<>(
                requireContext(),
                android.R.layout.simple_list_item_1,
                names
        );
        actSkin.setAdapter(ad);
        int sel = host().getSelectedSkinIndex();
        if (sel >= 0 && sel < names.size()) {
            actSkin.setText(names.get(sel), false);
        }
        if (btnSkinDelete != null) {
            btnSkinDelete.setEnabled(sel > 0);
        }
        if (actSkin != null) {
            actSkin.setOnItemClickListener((parent, v, position, id) -> {
                int idx = host().getSelectedSkinIndex();
                if (idx == position) return;
                List<SkinManager.SkinEntry> ents = SkinManager.getSkinList(requireContext());
                if (position >= 0 && position < ents.size()) {
                    host().setSelectedSkinIndex(position);
                    if (btnSkinDelete != null) {
                        btnSkinDelete.setEnabled(position > 0);
                    }
                }
            });
        }
    }

    private void refreshFromActivity() {
        ChartSelectActivity a = host();
        suppress = true;
        refreshSkinDropdown();
        float ar = a.getAspectRatio();
        if (actPreset != null) {
            if (ar <= 0f) {
                actPreset.setText(getString(R.string.option_follow_screen), false);
            } else {
                updatePresetLabelForValue(ar);
            }
        }
        if (etAspect != null) {
            if (ar <= 0f) {
                float screen = getScreenAspect();
                etAspect.setText(formatFloat(screen));
                etAspect.setEnabled(false);
            } else {
                etAspect.setText(formatFloat(ar));
                etAspect.setEnabled(true);
            }
        }

        float ks = a.getKeyScale();
        if (sliderKeyScale != null) sliderKeyScale.setValue(clamp(ks, 0.7f, 1.5f));
        if (etKeyScale != null) etKeyScale.setText(formatFloat(clamp(ks, 0.7f, 1.5f)));

        float ss = a.getScrollSpeed();
        if (sliderScrollSpeed != null) sliderScrollSpeed.setValue(clamp(ss, 0.1f, 5.0f));
        if (etScrollSpeed != null) etScrollSpeed.setText(formatFloat(clamp(ss, 0.1f, 5.0f)));

        if (swMirrorX != null) swMirrorX.setChecked(a.isMirrorX());
        if (swAutoplay != null) swAutoplay.setChecked(a.isAutoplay());
        if (swChallenge != null) swChallenge.setChecked(a.isChallengeMode());
        if (sliderBgDim != null) sliderBgDim.setValue(1f - clamp(a.getBackgroundDim(), 0f, 1f));
        if (swLowRes != null) swLowRes.setChecked(a.isLowResMode());
        if (swAa != null) swAa.setChecked(a.isAntialias());
        if (swShowFps != null) swShowFps.setChecked(a.isShowFps());
        if (swShowDebug != null) swShowDebug.setChecked(a.isShowDebugInfo());
        if (swMultiHighlight != null) swMultiHighlight.setChecked(a.isMultiPressHighlight());
        if (swApfc != null) swApfc.setChecked(a.isApfcIndicator());
        suppress = false;
    }

    private void applyPresetSelection(String label, float val) {
        ChartSelectActivity a = host();
        suppress = true;
        if (getString(R.string.option_follow_screen).equals(label) || val <= 0f) {
            a.setAspectRatio(0f);
            float screen = getScreenAspect();
            if (etAspect != null) {
                etAspect.setText(formatFloat(screen));
                etAspect.setEnabled(false);
            }
            if (actPreset != null) actPreset.setText(getString(R.string.option_follow_screen), false);
        } else {
            a.setAspectRatio(val);
            if (etAspect != null) {
                etAspect.setText(formatFloat(val));
                etAspect.setEnabled(true);
            }
            if (actPreset != null) actPreset.setText(label, false);
        }
        suppress = false;
    }

    private void updatePresetLabelForValue(float v) {
        if (actPreset == null) return;
        int idx = findPresetIndex(v);
        if (idx >= 0) {
            actPreset.setText(getPresetLabel(idx), false);
        } else {
            actPreset.setText(getString(R.string.option_manual_input), false);
        }
    }

    private String[] buildPresetLabels() {
        String[] labels = new String[PRESET_RATIO_LABELS.length];
        labels[0] = getString(R.string.option_follow_screen);
        for (int i = 1; i < labels.length; i++) {
            labels[i] = PRESET_RATIO_LABELS[i];
        }
        return labels;
    }

    private String getPresetLabel(int index) {
        if (index == 0) return getString(R.string.option_follow_screen);
        if (index > 0 && index < PRESET_RATIO_LABELS.length) return PRESET_RATIO_LABELS[index];
        return getString(R.string.option_manual_input);
    }

    private int findPresetIndex(float v) {
        for (int i = 1; i < PRESET_VALUES.length; i++) {
            if (Math.abs(PRESET_VALUES[i] - v) < 1e-4f) return i;
        }
        return -1;
    }

    private float getScreenAspect() {
        View root = getView();
        int w = root != null ? root.getWidth() : 0;
        int h = root != null ? root.getHeight() : 0;
        if (w > 0 && h > 0) return (float) w / (float) h;
        return 16f / 9f;
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

    private static String formatFloat(float v) {
        return String.format(Locale.US, "%.5f", v);
    }

    private ChartSelectActivity host() {
        return (ChartSelectActivity) requireActivity();
    }

    private abstract static class SimpleWatcher implements TextWatcher {
        @Override public void beforeTextChanged(CharSequence s, int start, int count, int after) {}
        @Override public void onTextChanged(CharSequence s, int start, int before, int count) {}
    }
}
