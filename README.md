# SRB2Kart — OUYA port

A native **OUYA** (NVIDIA Tegra 3, Android 4.1 / API 16) port of
[**SRB2Kart**](https://srb2.org/mods/), the kart-racing mod of the *Sonic Robo
Blast 2* fangame (a modified [Doom Legacy](http://doomlegacy.sourceforge.net/)
engine).

It runs on the OUYA's GPU via the [`gl4es`](https://github.com/ptitSeb/gl4es)
desktop-GL → OpenGL ES 2.0 translation layer, full-screen, with audio and the
online server browser working. Software rendering is kept as a fallback.

> Fork of [STJr/Kart-Public](https://github.com/STJr/Kart-Public) with an Android/OUYA
> backend added. All OUYA-specific work lives in `ouya/` plus small, guarded edits
> under `src/`.

## Status

- ✅ Boots and runs on real OUYA hardware, full-screen
- ✅ **OpenGL (gl4es)** renderer — GPU-accelerated, ~45–60 fps (default)
- ✅ Software renderer fallback (~28–45 fps)
- ✅ Audio (SDL2_mixer / OGG), gamepad, online master server (HTTPS), LAN/direct-IP
- ⏳ Local splitscreen multi-gamepad mapping — TODO

See **[docs/OUYA_PORT.md](docs/OUYA_PORT.md)** for the full technical write-up
(architecture, build, the renderer/Cg-compiler work, performance, and the boot
bring-up fixes).

## Download / install

Grab the prebuilt APK from the [**Releases**](../../releases) page (it bundles all
game data and is self-contained). Install with:

```
adb install -r srb2kart-ouya.apk
```

It appears in the OUYA games menu (`tv.ouya.intent.category.GAME`).

## Building from source

Requires Android NDK r23.2, CMake 3.22 + Ninja, JDK 11, and the SRB2Kart v1.6 data
WADs (see below).

```bat
REM 1) build the native engine library
cmd /c ouya\build_native.bat

REM 2) package the APK (JDK 11)
set JAVA_HOME=<path-to-jdk-11>
ouya\gradlew.bat -p ouya assembleDebug
```

### Game data

SRB2Kart's data is free and redistributable but **not** included here (too large
for git; it is a release artifact). Download `AssetsLinuxOnly.zip` (v1.6) from the
[official STJr release](https://github.com/STJr/Kart-Public/releases) and copy the
WADs into `ouya/app/src/main/assets/srb2kart/`:

```
srb2.srb  gfx.kart  textures.kart  chars.kart  maps.kart  sounds.kart  music.kart
```

(The prebuilt APK already bundles these.)

## Credits & license

GPL-2.0, same as the engine. **SRB2Kart** by Kart Krew, **SRB2** by Sonic Team
Junior; engine heritage *Doom Legacy* / *Doom* (id Software). `gl4es` by ptitSeb.

Kart Krew is in no way affiliated with SEGA or Sonic Team; *Sonic the Hedgehog* and
related characters are trademarks of SEGA. This is a non-commercial fan project.
