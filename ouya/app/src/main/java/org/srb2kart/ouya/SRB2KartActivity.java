package org.srb2kart.ouya;

import android.content.pm.PackageManager;
import android.os.Bundle;

import org.libsdl.app.SDLActivity;

/**
 * SRB2Kart on the OUYA (Tegra 3, API 16).
 *
 * Extends SDL's base activity. Unpacks the bundled game WADs to external
 * storage (where the fopen-based engine reads them via SRB2WADDIR) before SDL
 * starts the native thread, then lists the native libraries explicitly in
 * dependency order - the API-16 dynamic linker does not resolve transitive
 * DT_NEEDED entries from the app lib dir (main must be last: SDL loads
 * SDL_main from it).
 *
 * MILESTONE 1: software renderer only -> no gl4es .so to list (gl4es is a
 * static lib linked into libmain when HWRENDER lands in milestone 2).
 */
public class SRB2KartActivity extends SDLActivity {
    @Override
    protected void onCreate(Bundle savedInstanceState) {
        int versionCode = 1;
        try {
            versionCode = getPackageManager().getPackageInfo(getPackageName(), 0).versionCode;
        } catch (PackageManager.NameNotFoundException e) {
            // keep default
        }
        // Synchronous on first launch only (versioned); SDL's native thread
        // starts in super.onCreate, so the data must be in place first.
        AssetExporter.export(this, versionCode);
        super.onCreate(savedInstanceState);
    }

    @Override
    protected String[] getLibraries() {
        return new String[] {
            "c++_shared",
            "hidapi",
            "SDL2",
            "SDL2_mixer",
            "main"
        };
    }
}
