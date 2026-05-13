package com.wuying.phigros.ui;

import androidx.annotation.NonNull;
import androidx.fragment.app.Fragment;
import androidx.fragment.app.FragmentActivity;
import androidx.viewpager2.adapter.FragmentStateAdapter;

public class SelectPagerAdapter extends FragmentStateAdapter {

    public SelectPagerAdapter(@NonNull FragmentActivity fa) {
        super(fa);
    }

    @NonNull
    @Override
    public Fragment createFragment(int position) {
        if (position == 0) return new SelectChartFragment();
        if (position == 1) return new SelectVideoFragment();
        return new SelectAudioFragment();
    }

    @Override
    public int getItemCount() {
        return 3;
    }
}
