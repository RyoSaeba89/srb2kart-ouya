@echo off
REM ===================================================================
REM SRB2Kart -> OUYA: build libmain.so standalone, then stage jniLibs.
REM
REM AGP 7.0.2's externalNativeBuild .so copy (FileUtils.isSameFile) is
REM broken on this Windows host, so we build the native engine lib with
REM CMake/ninja directly and drop the result into src/main/jniLibs. The
REM gradle build then just packages the prebuilt libs + assets.
REM
REM Run this whenever an SRB2Kart .c source or the CMakeLists change,
REM THEN run gradlew assembleDebug.
REM ===================================================================
setlocal
set SDK=%LOCALAPPDATA%\Android\Sdk
set NDK=%SDK%\ndk\23.2.8568313
set CM=%SDK%\cmake\3.22.1\bin
set CPP=%~dp0app\src\main\cpp
set BUILD=%~dp0..\srb2k-nativebuild
set JNI=%~dp0app\src\main\jniLibs\armeabi-v7a

"%CM%\cmake.exe" -S "%CPP%" -B "%BUILD%" -G Ninja ^
  -DCMAKE_MAKE_PROGRAM="%CM%\ninja.exe" ^
  -DCMAKE_TOOLCHAIN_FILE="%NDK%\build\cmake\android.toolchain.cmake" ^
  -DANDROID_ABI=armeabi-v7a -DANDROID_PLATFORM=android-16 -DANDROID_STL=c++_shared ^
  -DCMAKE_BUILD_TYPE=Release || exit /b 1
"%CM%\cmake.exe" --build "%BUILD%" || exit /b 1

if not exist "%JNI%" mkdir "%JNI%"
copy /Y "%BUILD%\libmain.so"                       "%JNI%\" || exit /b 1
copy /Y "%CPP%\lib\SDL2\armeabi-v7a\libSDL2.so"       "%JNI%\" || exit /b 1
copy /Y "%CPP%\lib\SDL2\armeabi-v7a\libSDL2_mixer.so" "%JNI%\" || exit /b 1
copy /Y "%CPP%\lib\SDL2\armeabi-v7a\libhidapi.so"     "%JNI%\" || exit /b 1
copy /Y "%CPP%\lib\cppshared\armeabi-v7a\libc++_shared.so" "%JNI%\" || exit /b 1

echo.
echo Native libs staged into %JNI%
echo Now run: gradlew.bat assembleDebug --no-daemon
endlocal
