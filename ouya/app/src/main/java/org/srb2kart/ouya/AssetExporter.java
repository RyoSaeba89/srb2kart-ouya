package org.srb2kart.ouya;

import android.content.Context;
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
 * which is where SRB2's locateWad() looks first. Re-extracts only when the app
 * versionCode changes.
 */
public class AssetExporter {
    private static final String TAG = "srb2kart";
    private static final String VERSION_FILE = "exported_versioncode.txt";
    private static final String ASSET_ROOT = "srb2kart"; // assets/srb2kart/*

    public static void export(Context ctx, int versionCode) {
        File base = ctx.getExternalFilesDir(null);
        if (base == null) {
            Log.e(TAG, "getExternalFilesDir returned null; cannot export assets");
            return;
        }
        File stamp = new File(base, VERSION_FILE);
        if (alreadyExported(stamp, versionCode)) {
            Log.i(TAG, "assets already exported for versionCode " + versionCode);
            return;
        }
        Log.i(TAG, "exporting WADs to " + base.getAbsolutePath());
        AssetManager am = ctx.getAssets();
        try {
            // Copy the CONTENTS of assets/srb2kart/ directly into base, so the
            // WADs land in SRB2WADDIR (= base) without an extra path segment.
            copyAssetChildren(am, ASSET_ROOT, base);
            writeStamp(stamp, versionCode);
            Log.i(TAG, "asset export complete");
        } catch (IOException e) {
            Log.e(TAG, "asset export failed", e);
        }
    }

    private static boolean alreadyExported(File stamp, int versionCode) {
        if (!stamp.exists()) return false;
        try {
            byte[] buf = new byte[16];
            InputStream in = new java.io.FileInputStream(stamp);
            int n = in.read(buf);
            in.close();
            if (n <= 0) return false;
            return Integer.parseInt(new String(buf, 0, n).trim()) == versionCode;
        } catch (Exception e) {
            return false;
        }
    }

    private static void writeStamp(File stamp, int versionCode) throws IOException {
        OutputStream out = new FileOutputStream(stamp);
        out.write(Integer.toString(versionCode).getBytes());
        out.close();
    }

    /** Copy each child of an asset directory into destDir (recursively). */
    private static void copyAssetChildren(AssetManager am, String assetDir, File destDir) throws IOException {
        String[] children = am.list(assetDir);
        if (children == null || children.length == 0) {
            Log.w(TAG, "no assets under " + assetDir + " - bundle the SRB2Kart WADs there");
            return;
        }
        if (!destDir.exists() && !destDir.mkdirs())
            Log.w(TAG, "could not create dir " + destDir);
        for (String child : children)
            copyAsset(am, assetDir + "/" + child, child, destDir);
    }

    /** Recursively copy an asset path (dir or file) into destParent under relName. */
    private static void copyAsset(AssetManager am, String assetPath, String relName, File destParent) throws IOException {
        String[] children = am.list(assetPath);
        File dest = new File(destParent, relName);
        if (children != null && children.length > 0) {
            if (!dest.exists() && !dest.mkdirs())
                Log.w(TAG, "could not create dir " + dest);
            for (String child : children)
                copyAsset(am, assetPath + "/" + child, child, dest);
        } else {
            copyFile(am, assetPath, dest);
        }
    }

    private static void copyFile(AssetManager am, String assetPath, File dest) throws IOException {
        File parent = dest.getParentFile();
        if (parent != null && !parent.exists()) parent.mkdirs();
        InputStream in = am.open(assetPath);
        OutputStream out = new BufferedOutputStream(new FileOutputStream(dest), 64 * 1024);
        byte[] buf = new byte[64 * 1024];
        int n;
        while ((n = in.read(buf)) > 0)
            out.write(buf, 0, n);
        out.flush();
        out.close();
        in.close();
    }
}
