package org.srb2kart.ouya;

import android.content.Context;
import android.net.wifi.WifiManager;
import android.os.Bundle;

import org.libsdl.app.SDLActivity;

/**
 * SRB2Kart on the OUYA (Tegra 3, API 16).
 *
 * Extends SDL's base activity. InstallActivity (the launcher) unpacks the
 * bundled game WADs to external storage before starting this activity; a
 * synchronous fallback export stays here in case something launches the game
 * component directly. The native libraries are listed explicitly in
 * dependency order - the API-16 dynamic linker does not resolve transitive
 * DT_NEEDED entries from the app lib dir (main must be last: SDL loads
 * SDL_main from it).
 */
public class SRB2KartActivity extends SDLActivity {
    private WifiManager.MulticastLock multicastLock;
    private WifiManager.WifiLock wifiLock;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        // Fallback for direct launches (old shortcuts); InstallActivity
        // normally did this already, making this a stamp check + no-op.
        AssetExporter.export(this);

        // LAN play needs both locks:
        //  - MulticastLock: Android's wifi driver drops broadcast/multicast
        //    frames by default, and SRB2Kart's LAN discovery ("connect any")
        //    is a UDP broadcast the HOST must receive.
        //  - WifiLock HIGH_PERF: the Ouya's wifi power-save adds seconds of
        //    latency / drops packets mid-game (same fix as the Hedgewars port).
        WifiManager wm = (WifiManager) getApplicationContext().getSystemService(Context.WIFI_SERVICE);
        if (wm != null) {
            multicastLock = wm.createMulticastLock("srb2kart-lan");
            multicastLock.setReferenceCounted(false);
            multicastLock.acquire();
            wifiLock = wm.createWifiLock(WifiManager.WIFI_MODE_FULL_HIGH_PERF, "srb2kart-wifi");
            wifiLock.setReferenceCounted(false);
            wifiLock.acquire();
        }

        super.onCreate(savedInstanceState);
    }

    @Override
    protected void onDestroy() {
        if (multicastLock != null && multicastLock.isHeld())
            multicastLock.release();
        if (wifiLock != null && wifiLock.isHeld())
            wifiLock.release();
        super.onDestroy();
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
