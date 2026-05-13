package com.wuying.phigros.util;

import android.content.ContentResolver;
import android.content.Context;
import android.database.Cursor;
import android.net.Uri;
import android.provider.OpenableColumns;
import android.text.TextUtils;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;

public final class FileUtils {

    private FileUtils() {}

    @Nullable
    public static String queryDisplayName(@NonNull Context ctx, @NonNull Uri uri) {
        ContentResolver cr = ctx.getContentResolver();
        try (Cursor cursor = cr.query(uri, null, null, null, null)) {
            if (cursor != null && cursor.moveToFirst()) {
                int idx = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME);
                if (idx >= 0) {
                    return cursor.getString(idx);
                }
            }
        } catch (Exception ignored) {
        }
        String p = uri.getPath();
        if (p == null) return null;
        int slash = p.lastIndexOf('/');
        return slash >= 0 ? p.substring(slash + 1) : p;
    }

    @NonNull
    public static String stripExtension(@Nullable String name) {
        if (TextUtils.isEmpty(name)) return "";
        int dot = name.lastIndexOf('.');
        if (dot <= 0) return name;
        return name.substring(0, dot);
    }

    @NonNull
    public static File copyUriToCache(@NonNull Context ctx, @NonNull Uri uri, @NonNull String tag) throws IOException {
        String displayName = queryDisplayName(ctx, uri);
        if (TextUtils.isEmpty(displayName)) {
            displayName = tag;
        }

        String safeName = displayName.replaceAll("[^a-zA-Z0-9._-]", "_");
        File outDir = new File(ctx.getCacheDir(), "picked");
        //noinspection ResultOfMethodCallIgnored
        outDir.mkdirs();

        File out = new File(outDir, tag + "_" + System.currentTimeMillis() + "_" + safeName);

        try (InputStream in = ctx.getContentResolver().openInputStream(uri);
             FileOutputStream fos = new FileOutputStream(out)) {
            if (in == null) throw new IOException("openInputStream returned null");
            byte[] buf = new byte[64 * 1024];
            int n;
            while ((n = in.read(buf)) >= 0) {
                fos.write(buf, 0, n);
            }
            fos.flush();
        }

        return out;
    }
}
