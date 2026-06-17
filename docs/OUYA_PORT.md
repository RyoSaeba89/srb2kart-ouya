# SRB2Kart — OUYA port (technical documentation)

A native port of **SRB2Kart** (SRB2 / Doom Legacy engine fork) to the **OUYA**
microconsole, built on the engine's existing SDL2 backend and the `gl4es`
desktop-GL → OpenGL ES 2.0 translation layer.

---

## 1. Target hardware

| | |
|---|---|
| Device | OUYA (2013) |
| SoC | NVIDIA Tegra 3 (T33), quad-core ARM Cortex-A9 @ ~1.7 GHz |
| GPU | NVIDIA ULP GeForce (OpenGL ES 2.0 only; Cg-based GLSL compiler) |
| OS | Android 4.1.2 (API 16), `armeabi-v7a` |
| RAM | 1 GB |

Constraints that shaped the port: **API 16** (old bionic, no API21+ symbols),
**GLES 2.0 only**, a **fragile Cg GLSL compiler**, and limited CPU/GPU/VRAM.

---

## 2. Architecture

```
SRB2Kart engine (C, src/)                    ← Doom Legacy heritage
  ├── src/sdl/         SDL2 platform backend (video/input/audio/net/system)
  ├── software renderer (render_soft)        ← CPU rasterizer
  └── hardware renderer (render_opengl, HWRENDER)
        └── src/hardware/r_opengl/r_opengl.c  desktop fixed-function GL
              └── gl4es (static libGL.a)       ← translates desktop GL → GLES2
                    └── libGLESv2_tegra (system EGL/GLES2 driver)
SDL2 2.0.x (Android backend, prebuilt)
SDL2_mixer 2.6.3 (OGG via stb_vorbis)
libcurl 8.11.1 + mbedTLS 3.6.2 (HTTPS master server)
```

Both renderers are compiled in and switchable at runtime (`rendermode`):
- **OpenGL (gl4es)** — default. GPU-accelerated, ~60 fps. Shaders are disabled
  (the Tegra Cg compiler rejects SRB2's GLSL); the renderer falls back to
  fixed-function, which gl4es translates fine.
- **Software** — CPU rasterizer, correct but slower (~28–45 fps at 960×540).
  Kept as a fallback and for debugging.

---

## 3. Build environment

| Tool | Version | Notes |
|---|---|---|
| Android NDK | r23.2.8568313 | clang, `armeabi-v7a`, `android-16` |
| CMake | 3.22.1 + Ninja | bundled with the Android SDK |
| JDK | 11 (Microsoft build) | for Gradle / AGP |
| Android SDK | platform-tools (adb), build-tools | |
| Gradle / AGP | wrapper in `ouya/` | `org.gradle.jvmargs=-Xmx4g` (asset packaging) |

Build is **two-stage** because AGP 7.0.2's `externalNativeBuild` `.so` copy is
broken on the Windows host:

```bat
REM 1) build the native engine lib (CMake/Ninja) and stage it into jniLibs
cmd /c ouya\build_native.bat

REM 2) package the APK (JDK 11)
set JAVA_HOME=<jdk-11>
ouya\gradlew.bat -p ouya assembleDebug
```

`build_native.bat` runs CMake/Ninja against `ouya/app/src/main/cpp/CMakeLists.txt`
(produces `libmain.so`), then copies it plus the prebuilt `libSDL2.so`,
`libSDL2_mixer.so`, `libhidapi.so`, `libc++_shared.so` into
`ouya/app/src/main/jniLibs/armeabi-v7a/`.

### Compiler flags (critical for performance)

The engine is built with:

```
-O3 -fno-strict-aliasing -fomit-frame-pointer -mtune=cortex-a9 -mfpu=neon -fsigned-char
```

> **Note:** the default NDK Release configuration left `CMAKE_C_FLAGS_RELEASE`
> empty, so the engine was being compiled at **`-O0`** (no optimization). Forcing
> `-O3` gave a ~2.6× speedup of the CPU rasterizer/game logic (≈ 9× over the
> initial unoptimized + wrong-resolution baseline). `-fno-strict-aliasing` is
> **required** — the Doom-heritage code type-puns heavily and miscompiles at
> `-O2`+ without it.

---

## 4. Engine source changes (`src/`)

Minimal, all guarded for Android where possible:

- **`src/sdl/i_main.c`** — call `SRB2_AndroidDataPath()` at the top of `SDL_main`.
- **`src/sdl/i_system.c`** — disable `NEWSIGNALHANDLER` under `__ANDROID__`. It
  `fork()`s at startup so a parent process can watch the child for crash signals;
  on Android this **deadlocks** (the forked child inherits libc mutexes locked by
  threads that don't exist in the child). Falls back to the plain signal handler.
- **`src/d_main.c`** — `D_Home()` had a hardcoded `#ifdef ANDROID return
  "/data/data/org.srb2/";` (wrong package, not writable). The NDK CMake toolchain
  defines bare `ANDROID`, so this fired and caused `I_Error: Couldn't write game
  config`. Removed so it uses `$HOME` (set by the glue to the writable app dir).
- **`src/sdl/i_video.c`**
  - On Android, do **not** call `SDL_SetWindowSize` (it maps to
    `SurfaceHolder.setFixedSize` and desyncs SDL's idea of the window from the real
    EGL surface → `glViewport` rendered into a tiny corner).
  - Sync `vid.width/height` to the real drawable (`SDL_GL_GetDrawableSize` /
    `SDL_GetWindowSize`). For software, render at a reduced height (default 540,
    tunable) and let SDL upscale to the surface.
  - Default renderer = OpenGL on Android.
  - Fast software present: convert the 8bpp framebuffer straight into a locked
    RGB565 streaming texture via a palette LUT (`pal565[]`, rebuilt in
    `I_SetPalette`).
- **`src/hardware/r_opengl/r_opengl.c`** — `LoadShaders()` returns `false` instead
  of `I_Error` on shader compile/link failure under Android → `gr_shadersavailable
  = false` → fixed-function fallback (the Tegra Cg compiler rejects SRB2's GLSL:
  `EXT_shader_non_constant_global_initializers not supported`).
- **`src/sdl/ogl_sdl.c`** — `OglSdlFinishUpdate` bypasses the
  `MakeScreenFinalTexture` / `DrawScreenFinalTexture` capture+redraw on Android
  (that path renders all-white under gl4es; the backbuffer is presented directly).
- **`src/hardware/hw_main.c`** — new `HWR_PrecacheLevel()` uploads all level wall
  textures + flats to GL at level load (called from `p_setup.c`). `R_PrecacheLevel`
  skips this in 3D mode, so textures were uploaded lazily on first sight, causing
  hitches when new scenery scrolled in.
- **`src/r_main.c`** — `cv_drawdist` defaults to `2048` on Android (was `Infinite`).
- Misc API-16 fixes: `m_misc.c` (`off64_t`), `i_tcp.c` (`<sys/endian.h>` for the
  bswap htons/htonl macros), `dehacked.c` include, `mserv.c`/`d_clisrv.c` curl guards.

## 5. Android glue (`ouya/app/src/main/cpp/srb2k_android.c`)

- Sets `SRB2WADDIR` / `HOME` to `SDL_AndroidGetExternalStoragePath()` and `chdir`s
  there before `D_SRB2Main` runs.
- `localeconv()` stub for API < 21 (Lua's lexer needs it).
- Redirects `stdout`/`stderr` to `<HOME>/srb2log.txt` (the engine logs there, not
  to logcat).
- **`SRB2_Gl4esEnv()` is a `__attribute__((constructor(100)))`.** gl4es initialises
  itself from its own `constructor(101)` at library-load time (before `SDL_main`),
  reading `LIBGL_*` then; setting them in `SDL_main` is too late and the Tegra Cg
  hardware probe crashes. A lower-priority constructor sets the env first:
  ```
  LIBGL_ES=2  LIBGL_GL=21  LIBGL_NOTEST=1  LIBGL_NOHIGHP=1
  LIBGL_MIPMAP=0  LIBGL_SILENTSTUB=1  LIBGL_NOERROR=1
  ```
  `LIBGL_NOTEST=1` skips the probe that SIGSEGVs in `libcgdrv.so`; `LIBGL_MIPMAP=0`
  avoids per-upload CPU mipmap generation.

## 6. Java (`SRB2KartActivity` / `SDLActivity`)

- `getLibraries()` lists libs in dependency order incl. **`SDL2_mixer`** (the API-16
  linker doesn't resolve transitive `DT_NEEDED` from the app lib dir).
- `AssetExporter` unpacks the bundled WADs to the external files dir on first launch.
- The `SDLSurface` is `MATCH_PARENT` and uses `setFixedSize` to render into a
  smaller buffer that the HW compositor upscales to 1080p.
- Manifest: `tv.ouya.intent.category.GAME` + `res/drawable-xhdpi/ouya_icon.png`
  (732×412 banner) for the OUYA games menu.

---

## 7. Boot bring-up (the four crashes, in order)

1. `libSDL2_mixer.so not found` → add `"SDL2_mixer"` to `getLibraries()`.
2. SIGSEGV in `libcgdrv.so` `CgDrv_Compile` → gl4es hardware probe; fixed by setting
   `LIBGL_NOTEST=1` **from a constructor** (priority 100 < gl4es' 101).
3. Hang at boot (`fork()` deadlock) → disable `NEWSIGNALHANDLER`.
4. `I_Error: Couldn't write game config` → remove the hardcoded `D_Home()` Android
   path.

---

## 8. Performance

Measured on real hardware (attract-demo, 960×540), via in-engine instrumentation
(`PERF` / `PERF2` lines in `srb2log.txt`):

| Renderer | Scene | FPS | Notes |
|---|---|---|---|
| Software, -O0, 1280×800 | 3D | ~5 | original broken baseline |
| Software, -O3, 960×540 | 3D | ~28–45 | CPU rasterizer + 8→16 present |
| **OpenGL (gl4es), -O3, 960×540** | 3D | **~45–60** | GPU; present only ~2 ms |

Key findings: the slowdown "with CPU players" is the **renderer** (more
sprites/geometry to draw), not the bot AI (`logic` is ~0.1–7 ms). The GL `present`
is GPU-cheap (~2 ms); its cost is the gl4es CPU translation of draw calls.

Levers applied: `-O3`/NEON, render-resolution decouple, GL fixed-function path,
final-texture bypass, `HWR_PrecacheLevel` (anti-hitch), SRB2 batching (on),
`LIBGL_NOERROR`, `drawdist=2048`.

---

## 9. Runtime tuning (on device)

Files in `<external>/Android/data/org.srb2kart.ouya/files/.srb2kart/`:

| File | Effect |
|---|---|
| `renderer.txt` | `opengl` or `software` |
| `renderheight.txt` | software render height in px (lower = faster, blurrier; ≥480 readable) |
| `kartconfig.cfg` | standard SRB2 config (`drawdist`, controls, …) |

Engine log: `…/files/srb2log.txt` (`PERF`/`PERF2` = fps + render-vs-logic split).

> The `PERF`/`PERF2`/`VIDDBG` instrumentation and the `renderheight.txt` knob are
> debug aids; strip them for a release build.

---

## 10. Game data (WADs)

SRB2Kart's data is **free and redistributable**. The 5 mandatory v1.6 WADs
(`srb2.srb`, `gfx.kart`, `textures.kart`, `chars.kart`, `maps.kart`) plus
`sounds.kart` / `music.kart` are **not** in this repository (too large for git and
they are release artifacts). Get them from STJr's official release:

> `https://github.com/STJr/Kart-Public/releases` → `AssetsLinuxOnly.zip` (v1.6)

Place them in `ouya/app/src/main/assets/srb2kart/` before building, or use the
prebuilt APK (which bundles them). Also bundled: `cacert.pem` (CA store for the
HTTPS master server) and `ouya_icon.png`.

---

## 11. Audio & networking

- **Audio:** SDL2_mixer 2.6.3, OGG via bundled `stb_vorbis` (`music.kart` is OGG),
  SFX are DMX converted in-engine.
- **Online:** libcurl + mbedTLS, HTTPS to `ms.kartkrew.org`; CA store via bundled
  `cacert.pem` injected with `CURLOPT_CAINFO`. LAN/direct-IP works.
- **Splitscreen (P2–P4):** engine supports it; multi-gamepad mapping on Android is
  still TODO.

---

## 12. Known issues / remaining work

- GL hitch on first sight of new **object sprites** (lazy gl4es upload); wall/flat
  textures are precached, sprites are not (combinatorial skins×colours×frames and
  limited Tegra VRAM). Smooths out after first exposure in a real race.
- Splitscreen multi-gamepad mapping.
- Strip debug instrumentation for release.

---

## 13. Credits & license

- **SRB2Kart** by Kart Krew; **SRB2** by Sonic Team Junior — GPL-2.0.
- Engine heritage: **Doom Legacy** / **Doom** (id Software).
- **gl4es** by ptitSeb (MIT). **SDL2** (zlib). **mbedTLS** (Apache-2.0).
  **libcurl** (curl license). **stb_vorbis** (public domain).
- This port: GPL-2.0, same as the engine. *Sonic the Hedgehog* and related
  characters are trademarks of SEGA; this is a non-commercial fan project.
