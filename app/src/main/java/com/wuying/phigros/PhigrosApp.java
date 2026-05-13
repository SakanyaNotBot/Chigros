package com.wuying.phigros;

import android.app.Application;
import android.util.Log;

import java.io.File;

/**
 * Application entry point. Clears chart cache on startup.
 */
public class PhigrosApp extends Application {

    private static final String TAG = "PhigrosApp";

    @Override
    public void onCreate() {
        super.onCreate();
        clearChartCache();
    }

    private void clearChartCache() {
        try {
            File cacheDir = getCacheDir();
            if (cacheDir == null) return;

            File pickedDir = new File(cacheDir, "picked");
            if (pickedDir.exists()) {
                deleteRecursive(pickedDir);
                Log.i(TAG, "Cleared picked cache: " + pickedDir.getAbsolutePath());
            }

            File packDir = new File(cacheDir, "pack");
            if (packDir.exists()) {
                deleteRecursive(packDir);
                Log.i(TAG, "Cleared pack cache: " + packDir.getAbsolutePath());
            }

        } catch (Exception e) {
            Log.e(TAG, "Failed to clear cache on startup", e);
        }
    }

    private void deleteRecursive(File fileOrDir) {
        if (fileOrDir.isDirectory()) {
            File[] children = fileOrDir.listFiles();
            if (children != null) {
                for (File child : children) {
                    deleteRecursive(child);
                }
            }
        }
        //noinspection ResultOfMethodCallIgnored
        fileOrDir.delete();
    }
}
