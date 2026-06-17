// SRB2Kart -> OUYA: Android-specific native glue.
//
// The Java AssetExporter unpacks the bundled WADs (srb2.srb, gfx.kart,
// textures.kart, chars.kart, maps.kart, ...) into the app's external files
// dir, where the fopen-based engine can read them. We point SRB2 there before
// D_SRB2Main runs:
//   - SRB2WADDIR : locateWad() checks this env first (src/sdl/i_system.c)
//   - HOME       : D_Home() -> srb2home (config + savegames, must be writable)
//   - chdir()    : so any relative path resolves inside the writable dir
//
// Called from the top of SDL_main() in src/sdl/i_main.c under __ANDROID__.

#include <stdlib.h>
#include <unistd.h>
#include <locale.h>
#include <stdio.h>
#include "SDL.h"

// bionic before API 21 has no localeconv(); Lua's lexer (blua/llex.c) needs it
// to read the locale decimal point. SRB2 always uses "C" locale numerics, so a
// fixed "." / "" struct is correct here.
#if __ANDROID_API__ < 21
struct lconv *localeconv(void)
{
	static struct lconv c;
	static char dot[] = ".";
	static char empty[] = "";
	c.decimal_point = dot;
	c.thousands_sep = empty;
	c.grouping = empty;
	return &c;
}
#endif

// gl4es tuning for the hardware renderer on Tegra 3 (Ouya).
//   LIBGL_ES=2        translate desktop GL onto GLES2
//   LIBGL_GL=21       advertise desktop OpenGL 2.1 to the engine
//   LIBGL_NOTEST=1    skip gl4es' startup probe that crashes NVIDIA Tegra's
//                     Cg-based GLES driver (the testGLSL probe in hardext.c
//                     compiles a `layout(location=...)` shader that SIGSEGVs
//                     inside libcgdrv.so CgDrv_Compile on Tegra 3)
//   LIBGL_NOHIGHP=1   force mediump in generated shaders (safe everywhere)
//   LIBGL_MIPMAP=3    auto-generate mipmaps (Doom textures arrive un-mipmapped)
//   LIBGL_SILENTSTUB=1 don't spam logcat for unimplemented GL stubs
//
// CRITICAL ORDERING: gl4es initialises itself from init.c's
//   __attribute__((constructor(101))) void initialize_gl4es()
// which reads these env vars at libmain.so *load* time -- before SDL_main (and
// therefore before SRB2_AndroidDataPath) ever runs. Setting them in SDL_main
// is too late and lets the Tegra Cg probe crash on boot. So we set them from a
// constructor with a lower priority number (100 < 101) so the C runtime runs it
// *before* gl4es' constructor. (Priority <101 warns "reserved for the
// implementation" but is honoured: the linker sorts .init_array by priority.)
__attribute__((constructor(100)))
static void SRB2_Gl4esEnv(void)
{
	setenv("LIBGL_ES", "2", 1);
	setenv("LIBGL_GL", "21", 1);
	setenv("LIBGL_NOTEST", "1", 1);
	setenv("LIBGL_NOHIGHP", "1", 1);
	// Was 3 (force + CPU-generate full mipmap chains on every texture upload),
	// which stutters when new textures appear mid-race on Tegra. 0 = no CPU
	// mipmap generation -> smoother streaming (distant textures alias a bit).
	setenv("LIBGL_MIPMAP", "0", 1);
	setenv("LIBGL_SILENTSTUB", "1", 1);
	// Perf: don't let gl4es track/check GL errors (avoids glGetError sync stalls
	// and bookkeeping on every call) now that the GL path is validated.
	setenv("LIBGL_NOERROR", "1", 1);
}

void SRB2_AndroidDataPath(void)
{
	const char *path;

	SRB2_Gl4esEnv();

	path = SDL_AndroidGetExternalStoragePath();
	if (!path || !path[0])
	{
		SDL_Log("srb2kart: external storage path unavailable");
		return;
	}
	setenv("SRB2WADDIR", path, 1);
	setenv("HOME", path, 1);
	if (chdir(path) != 0)
		SDL_Log("srb2kart: chdir(%s) failed", path);
	else
		SDL_Log("srb2kart: data/save base = %s", path);

	// DEBUG (Ouya bring-up): SRB2 writes I_OutputMsg/CONS_Printf/I_Error to
	// stdout/stderr, which Android does not route to logcat. Redirect both to a
	// file in HOME so we can see the engine's startup log + the reason it quits.
	// Unbuffered so the last line survives a crash/exit.
	{
		char logpath[1024];
		snprintf(logpath, sizeof logpath, "%s/srb2log.txt", path);
		if (freopen(logpath, "w", stdout)) setvbuf(stdout, NULL, _IONBF, 0);
		if (freopen(logpath, "a", stderr)) setvbuf(stderr, NULL, _IONBF, 0);
		SDL_Log("srb2kart: stdout/stderr -> %s", logpath);
	}
}
