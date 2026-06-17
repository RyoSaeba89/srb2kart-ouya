cmake_minimum_required(VERSION 3.4.1)
project(srb2kart)

# --------------------------------------------------------------------------
# SRB2Kart (STJr/Kart-Public) -> OUYA (Tegra 3, API 16, armeabi-v7a)
#
# MILESTONE 2: SOFTWARE + HARDWARE (gl4es) renderer, runtime-switchable.
#   - HWRENDER compiles the Doom-Legacy GL renderer (hardware/*.c +
#     r_opengl/r_opengl.c + sdl/ogl_sdl.c). The renderer is fixed-function
#     vertex-array GL; we route it through gl4es (static libGL.a) onto GLES2.
#   - Software renderer stays in (render_soft); `rendermode` toggles at runtime.
#   - Still no SDL2_mixer / libgme / libpng / libcurl / discord -> minimal deps.
#
# gl4es wiring (see ogl_sdl.c GetGLFunc / LoadGL and srb2k_android.c):
#   * SDL creates a GLES2 EGL context (attrs set in i_video.c under __ANDROID__).
#   * LoadGL() feeds gl4es the GLES loader (SDL_GL_GetProcAddress) then
#     initialize_gl4es(); GetGLFunc() resolves desktop-GL via gl4es_GetProcAddress.
#   * LIBGL_* env (LIBGL_ES=2, LIBGL_GL=21, LIBGL_NOTEST=1, ...) set before video.
# --------------------------------------------------------------------------

add_definitions(-D__ANDROID__)

# SRB2Kart source root: this file lives at <root>/ouya/app/src/main/cpp
get_filename_component(SRB2 "${CMAKE_CURRENT_SOURCE_DIR}/../../../../.." ABSOLUTE)
set(SRC ${SRB2}/src)

# SRB2 net/string code assumes signed char (Doom heritage); ARM defaults unsigned.
set(CMAKE_C_FLAGS "${CMAKE_C_FLAGS} -fsigned-char")

# PERF: the NDK Release config left CMAKE_C_FLAGS_RELEASE empty, so the engine was
# being built at -O0 (no optimization) -- crippling for the CPU-bound software
# renderer on Tegra 3. Force aggressive optimization here.
#   -fno-strict-aliasing: Doom-heritage code type-puns heavily; required at -O2+.
#   -mtune=cortex-a9 + -mfpu=neon: tune for the OUYA's Cortex-A9 and let the
#   compiler use NEON/VFPv3 (Tegra 3 has NEON).
set(CMAKE_C_FLAGS "${CMAKE_C_FLAGS} -O3 -fno-strict-aliasing -fomit-frame-pointer -mtune=cortex-a9 -mfpu=neon")
set(CMAKE_C_FLAGS_RELEASE "-O3 -DNDEBUG")
add_definitions(-DNDEBUG)

# ---- engine feature defines (mirror the Makefile, minimal M1 profile) ----
add_definitions(
  -DUNIXCOMMON        # POSIX platform family (sdl/MakeNIX.cfg)
  -DLINUX             # Android libc is Linux-flavoured; enables LUA_USE_POSIX
  -DHAVE_SDL
  -DDIRECTFULLSCREEN
  -DHAVE_BLUA         # Lua scripting (blua/Makefile.cfg)
  -DHAVE_THREADS      # SDL threads (i_threads.c)
  -DNONX86            # not x86 -> use C blit, no asm tables
  -DNOASM             # no NASM tmap
  -DFORCESDLMAIN      # entry point is SDL_main (SDL2 Android calls it via JNI)
  -DNOMUMBLE          # no Mumble voice link (avoids shm_open, missing on bionic)
  -DSTDC_HEADERS      # use <stdlib.h> in md5.c (skip the bcopy memcpy alias)
  -DHWRENDER          # compile the hardware (OpenGL) renderer -> gl4es/GLES2
  -DHAVE_MIXER        # audio via SDL2_mixer (mixer_sound.c) -> sounds.kart/music.kart
  -DHAVE_CURL         # HTTP(S) via libcurl+mbedTLS: master server browser + HTTP file dl
)
# NOTE: networking stays ON (no NONET) - NONET is not cleanly supported in
# SRB2Kart (it guards out chat/menu/ban symbols used unconditionally elsewhere).
# The master server is HTTP/curl-only; we keep sockets but stub out the few
# curl-gated master-server symbols (see mserv.c HAVE_CURL guards).
# NOTE: HWRENDER, HAVE_MIXER, HAVE_PNG, HAVE_LIBGME, HAVE_CURL, HAVE_DISCORDRPC,
#       CMAKECONFIG, COMPVERSION are all intentionally left UNDEFINED for M1.

# ------------------------- prebuilt deps ----------------------------------
set(LIBDIR ${CMAKE_CURRENT_SOURCE_DIR}/lib)

add_library(sdl SHARED IMPORTED)
set_target_properties(sdl PROPERTIES IMPORTED_LOCATION ${LIBDIR}/SDL2/${ANDROID_ABI}/libSDL2.so)

add_library(hidapi SHARED IMPORTED)
set_target_properties(hidapi PROPERTIES IMPORTED_LOCATION ${LIBDIR}/SDL2/${ANDROID_ABI}/libhidapi.so)

# SDL2_mixer 2.6.3 (OGG via stb_vorbis + WAV), built standalone against our SDL2.
add_library(sdl_mixer SHARED IMPORTED)
set_target_properties(sdl_mixer PROPERTIES IMPORTED_LOCATION ${LIBDIR}/SDL2/${ANDROID_ABI}/libSDL2_mixer.so)

# libcurl (static) + mbedTLS (static) for HTTPS to the master server.
add_library(curl STATIC IMPORTED)
set_target_properties(curl PROPERTIES IMPORTED_LOCATION ${LIBDIR}/curl/${ANDROID_ABI}/libcurl.a)
add_library(mbedtls STATIC IMPORTED)
set_target_properties(mbedtls PROPERTIES IMPORTED_LOCATION ${LIBDIR}/mbedtls/${ANDROID_ABI}/libmbedtls.a)
add_library(mbedx509 STATIC IMPORTED)
set_target_properties(mbedx509 PROPERTIES IMPORTED_LOCATION ${LIBDIR}/mbedtls/${ANDROID_ABI}/libmbedx509.a)
add_library(mbedcrypto STATIC IMPORTED)
set_target_properties(mbedcrypto PROPERTIES IMPORTED_LOCATION ${LIBDIR}/mbedtls/${ANDROID_ABI}/libmbedcrypto.a)

# gl4es: static desktop-GL -> GLES2 translation layer (prebuilt libGL.a).
add_library(gl4es STATIC IMPORTED)
set_target_properties(gl4es PROPERTIES IMPORTED_LOCATION ${LIBDIR}/gl4es/${ANDROID_ABI}/libGL.a)

# ------------------------- include paths ----------------------------------
# gl4es/ must precede include/SDL2 so r_opengl.h's <GL/gl.h> resolves to the
# gl4es desktop-GL emulation headers (the staged SDL_opengl.h pulls windows.h).
include_directories(
  ${SRC}
  ${SRC}/sdl
  ${SRC}/blua
  ${CMAKE_CURRENT_SOURCE_DIR}/include/gl4es
  ${CMAKE_CURRENT_SOURCE_DIR}/include/SDL2
  ${CMAKE_CURRENT_SOURCE_DIR}/include
  ${CMAKE_CURRENT_SOURCE_DIR}
)

# ------------------------- source lists -----------------------------------
# SDL interface backend (src/sdl). NOMIXER -> sdl_sound.c; HWRENDER -> ogl_sdl.c.
set(SRC_SDL
  ${SRC}/sdl/i_main.c
  ${SRC}/sdl/i_system.c
  ${SRC}/sdl/i_video.c
  ${SRC}/sdl/i_net.c
  ${SRC}/sdl/i_cdmus.c
  ${SRC}/sdl/dosstr.c
  ${SRC}/sdl/endtxt.c
  ${SRC}/sdl/hwsym_sdl.c
  ${SRC}/sdl/mixer_sound.c
  ${SRC}/sdl/i_threads.c
  ${SRC}/sdl/ogl_sdl.c
)

# Hardware (OpenGL) renderer (src/Makefile NOHW block + r_opengl driver).
# hw3sound.c is OpenAL/FMOD 3D audio (HW3SOUND) -> intentionally omitted.
set(SRC_HW
  ${SRC}/hardware/hw_bsp.c
  ${SRC}/hardware/hw_draw.c
  ${SRC}/hardware/hw_light.c
  ${SRC}/hardware/hw_main.c
  ${SRC}/hardware/hw_clip.c
  ${SRC}/hardware/hw_md2.c
  ${SRC}/hardware/hw_cache.c
  ${SRC}/hardware/hw_trick.c
  ${SRC}/hardware/hw_md2load.c
  ${SRC}/hardware/hw_md3load.c
  ${SRC}/hardware/hw_model.c
  ${SRC}/hardware/u_list.c
  ${SRC}/hardware/r_opengl/r_opengl.c
)

# Engine core (from src/Makefile OBJS block; software renderer only).
set(SRC_CORE
  ${SRC}/comptime.c ${SRC}/string.c ${SRC}/md5.c ${SRC}/lzf.c
  ${SRC}/d_main.c ${SRC}/d_clisrv.c ${SRC}/d_net.c ${SRC}/d_netfil.c ${SRC}/d_netcmd.c
  ${SRC}/dehacked.c ${SRC}/z_zone.c ${SRC}/f_finale.c ${SRC}/f_wipe.c
  ${SRC}/g_game.c ${SRC}/g_input.c ${SRC}/am_map.c ${SRC}/command.c ${SRC}/console.c
  ${SRC}/hu_stuff.c ${SRC}/y_inter.c ${SRC}/st_stuff.c ${SRC}/k_kart.c
  ${SRC}/m_aatree.c ${SRC}/m_anigif.c ${SRC}/m_argv.c ${SRC}/m_bbox.c ${SRC}/m_cheat.c
  ${SRC}/m_cond.c ${SRC}/m_fixed.c ${SRC}/m_menu.c ${SRC}/m_misc.c ${SRC}/m_random.c
  ${SRC}/m_queue.c ${SRC}/info.c
  ${SRC}/p_ceilng.c ${SRC}/p_enemy.c ${SRC}/p_floor.c ${SRC}/p_inter.c ${SRC}/p_lights.c
  ${SRC}/p_map.c ${SRC}/p_maputl.c ${SRC}/p_mobj.c ${SRC}/p_polyobj.c ${SRC}/p_saveg.c
  ${SRC}/p_setup.c ${SRC}/p_sight.c ${SRC}/p_spec.c ${SRC}/p_telept.c ${SRC}/p_tick.c
  ${SRC}/p_user.c ${SRC}/p_slopes.c ${SRC}/tables.c
  ${SRC}/r_bsp.c ${SRC}/r_data.c ${SRC}/r_draw.c ${SRC}/r_fps.c ${SRC}/r_main.c
  ${SRC}/r_plane.c ${SRC}/r_segs.c ${SRC}/r_sky.c ${SRC}/r_splats.c ${SRC}/r_things.c
  ${SRC}/screen.c ${SRC}/v_video.c ${SRC}/s_sound.c ${SRC}/sounds.c ${SRC}/w_wad.c
  ${SRC}/filesrch.c ${SRC}/mserv.c ${SRC}/http-mserv.c ${SRC}/i_tcp.c ${SRC}/i_time.c ${SRC}/b_bot.c
)

# Lua: blua VM (blua/Makefile.cfg) + SRB2 lua bindings (lua_*.c).
set(SRC_LUA
  ${SRC}/blua/lapi.c ${SRC}/blua/lbaselib.c ${SRC}/blua/ldo.c ${SRC}/blua/lfunc.c
  ${SRC}/blua/linit.c ${SRC}/blua/liolib.c ${SRC}/blua/llex.c ${SRC}/blua/lmem.c
  ${SRC}/blua/lobject.c ${SRC}/blua/lstate.c ${SRC}/blua/lstrlib.c ${SRC}/blua/ltablib.c
  ${SRC}/blua/lundump.c ${SRC}/blua/lzio.c ${SRC}/blua/lauxlib.c ${SRC}/blua/lcode.c
  ${SRC}/blua/ldebug.c ${SRC}/blua/ldump.c ${SRC}/blua/lgc.c ${SRC}/blua/lopcodes.c
  ${SRC}/blua/lparser.c ${SRC}/blua/lstring.c ${SRC}/blua/ltable.c ${SRC}/blua/ltm.c
  ${SRC}/blua/lvm.c
  ${SRC}/lua_script.c ${SRC}/lua_baselib.c ${SRC}/lua_blockmaplib.c ${SRC}/lua_mathlib.c
  ${SRC}/lua_hooklib.c ${SRC}/lua_consolelib.c ${SRC}/lua_infolib.c ${SRC}/lua_mobjlib.c
  ${SRC}/lua_playerlib.c ${SRC}/lua_skinlib.c ${SRC}/lua_thinkerlib.c ${SRC}/lua_maplib.c
  ${SRC}/lua_hudlib.c ${SRC}/lua_hudlib_drawlist.c
)

# Android glue (data-path / chdir).
file(GLOB SRC_GLUE ${CMAKE_CURRENT_SOURCE_DIR}/*.c)

add_library(main SHARED
  ${SRC_SDL}
  ${SRC_CORE}
  ${SRC_HW}
  ${SRC_LUA}
  ${SRC_GLUE}
)

find_library(log-lib log)
find_library(z-lib z)
find_library(egl-lib EGL)
find_library(glesv2-lib GLESv2)
find_library(glesv1-lib GLESv1_CM)
find_library(dl-lib dl)
find_library(m-lib m)

target_link_libraries(main
  sdl
  sdl_mixer
  hidapi
  gl4es           # static; must precede the GLES/EGL system libs it calls into
  ${egl-lib}
  ${glesv2-lib}
  ${glesv1-lib}
  curl            # static libcurl, then its mbedTLS backend (tls -> x509 -> crypto)
  mbedtls
  mbedx509
  mbedcrypto
  ${z-lib}
  ${log-lib}
  ${dl-lib}
  ${m-lib}
)
