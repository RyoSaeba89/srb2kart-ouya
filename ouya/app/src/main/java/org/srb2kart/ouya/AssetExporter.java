package org.srb2kart.ouya;

import android.content.Context;
import android.content.res.AssetFileDescriptor;
import android.content.res.AssetManager;
import android.util.Log;

import java.io.BufferedOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;

/**
 * Unpacks the bundled SRB2Kart data (srb2.srb, gfx.kart, textures.kart,
 * chars.kart, maps.kart, ...) from assets/srb2kart/ into the app's external
 * files dir, where the native engine reads them via normal filesystem I/O
 * (SRB2's wad layer is fopen-based; it cannot read straight out of the APK).
 *
 * The native glue (srb2k_android.c) points SRB2WADDIR/HOME at this same dir,
 * which is where SRB2's locateWad() looks first.
 *
 * Re-extracts only when ASSETS_REVISION changes - bump it when the bundled
 * data itself changes, NOT on every APK versionCode bump (the WADs are ~490MB
 * and take minutes to copy on the Ouya).
 */
public class AssetExporter {
    private static final String TAG = "srb2kart";
    private static final String VERSION_FILE = "exported_versioncode.txt";
    private static final String ASSET_ROOT = "srb2kart"; // assets/srb2kart/*

    /**
     * Revision of the bundled data set. History: 1..6 tracked versionCode
     * (v1.6 WADs + cacert.pem, unchanged since); 1000 = decoupled from
     * versionCode.
     */
    private static final int ASSETS_REVISION = 1000;

    /** Progress callback for the install screen. Called from the copy thread. */
    public interface Listener {
        /** totalBytes is -1 when the size could not be determined. */
        void onProgress(long copiedBytes, long totalBytes, String currentFile);
    }

    /** True when the data for ASSETS_REVISION is already on external storage. */
    public static boolean isExported(Context ctx) {
        File base = ctx.getExternalFilesDir(null);
        return base != null && stampMatches(new File(base, VERSION_FILE));
    }

    public static void export(Context ctx, Listener listener) {
        File base = ctx.getExternalFilesDir(null);
        if (base == null) {
            Log.e(TAG, "getExternalFilesDir returned null; cannot export assets");
            return;
        }
        File stamp = new File(base, VERSION_FILE);
        if (stampMatches(stamp)) {
            Log.i(TAG, "assets already exported for revision " + ASSETS_REVISION);
            return;
        }
        Log.i(TAG, "exporting WADs to " + base.getAbsolutePath());
        AssetManager am = ctx.getAssets();
        try {
            Progress progress = new Progress(listener, totalBytes(am, ASSET_ROOT));
            // Copy the CONTENTS of assets/srb2kart/ directly into base, so the
            // WADs land in SRB2WADDIR (= base) without an extra path segment.
            copyAssetChildren(am, ASSET_ROOT, base, progress);
            writeStamp(stamp);
            Log.i(TAG, "asset export complete");
        } catch (IOException e) {
            Log.e(TAG, "asset export failed", e);
        }
    }

    /** Kept for callers that don't care about progress. */
    public static void export(Context ctx) {
        export(ctx, null);
    }

    private static class Progress {
        final Listener listener;
        final long total;
        long copied;

        Progress(Listener listener, long total) {
            this.listener = listener;
            this.total = total;
        }

        void advance(long bytes, String file) {
            copied += bytes;
            if (listener != null)
                listener.onProgress(copied, total, file);
        }
    }

    /**
     * Sum the sizes of everything under an asset dir. The WADs are stored
     * uncompressed (aaptOptions noCompress), so openFd() works and reports
     * the real length; anything compressed just doesn't count towards the
     * total (close enough for a progress bar).
     */
    private static long totalBytes(AssetManager am, String assetDir) {
        long total = 0;
        try {
            String[] children = am.list(assetDir);
            if (children == null) return 0;
            for (String child : children) {
                String path = assetDir + "/" + child;
                String[] sub = am.list(path);
                if (sub != null && sub.length > 0) {
                    total += totalBytes(am, path);
                } else {
                    try {
                        AssetFileDescriptor fd = am.openFd(path);
                        total += fd.getLength();
                        fd.close();
                    } catch (IOException compressed) {
                        // no fd for compressed entries; skip in the total
                    }
                }
            }
        } catch (IOException e) {
            Log.w(TAG, "could not size assets under " + assetDir, e);
        }
        return total;
    }

    private static boolean stampMatches(File stamp) {
        if (!stamp.exists()) return false;
        try {
            byte[] buf = new byte[16];
            InputStream in = new java.io.FileInputStream(stamp);
            int n = in.read(buf);
            in.close();
            if (n <= 0) return false;
            int v = Integer.parseInt(new String(buf, 0, n).trim());
            // 3..6 = versionCode-era stamps for this exact same data set;
            // accept them so updating the APK doesn't re-copy ~490MB.
            return v == ASSETS_REVISION || (v >= 3 && v <= 6);
        } catch (Exception e) {
            return false;
        }
    }

    private static void writeStamp(File stamp) throws IOException {
        OutputStream out = new FileOutputStream(stamp);
        out.write(Integer.toString(ASSETS_REVISION).getBytes());
        out.close();
    }

    /** Copy each child of an asset directory into destDir (recursively). */
    private static void copyAssetChildren(AssetManager am, String assetDir, File destDir, Progress progress) throws IOException {
        String[] children = am.list(assetDir);
        if (children == null || children.length == 0) {
            Log.w(TAG, "no assets under " + assetDir + " - bundle the SRB2Kart WADs there");
            return;
        }
        if (!destDir.exists() && !destDir.mkdirs())
            Log.w(TAG, "could not create dir " + destDir);
        for (String child : children)
            copyAsset(am, assetDir + "/" + child, child, destDir, progress);
    }

    /** Recursively copy an asset path (dir or file) into destParent under relName. */
    private static void copyAsset(AssetManager am, String assetPath, String relName, File destParent, Progress progress) throws IOException {
        String[] children = am.list(assetPath);
        File dest = new File(destParent, relName);
        if (children != null && children.length > 0) {
            if (!dest.exists() && !dest.mkdirs())
                Log.w(TAG, "could not create dir " + dest);
            for (String child : children)
                copyAsset(am, assetPath + "/" + child, child, dest, progress);
        } else {
            copyFile(am, assetPath, dest, progress);
        }
    }

    private static void copyFile(AssetManager am, String assetPath, File dest, Progress progress) throws IOException {
        File parent = dest.getParentFile();
        if (parent != null && !parent.exists()) parent.mkdirs();
        String name = dest.getName();
        InputStream in = am.open(assetPath);
        OutputStream out = new BufferedOutputStream(new FileOutputStream(dest), 64 * 1024);
        byte[] buf = new byte[64 * 1024];
        int n;
        while ((n = in.read(buf)) > 0) {
            out.write(buf, 0, n);
            progress.advance(n, name);
        }
        out.flush();
        out.close();
        in.close();
    }
}
