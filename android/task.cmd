@echo off
setlocal EnableExtensions

rem ============================================================
rem  Novelfa Android - build task runner
rem
rem  Usage (from C:\Novelfa\android):
rem      task build            pick debug / release / both interactively
rem      task build debug      assembleDebug  -> app-debug.apk
rem      task build release    assembleRelease + bundleRelease
rem      task test             :app:testDebugUnitTest
rem      task clean            gradlew clean
rem      task kspclean         delete KSP output (Windows KSP bug)
rem      task help             show this help
rem ============================================================

set "SCRIPT_DIR=%~dp0"
pushd "%SCRIPT_DIR%" >nul
if errorlevel 1 (
    echo [ERROR] Cannot change to "%SCRIPT_DIR%"
    exit /b 1
)

if "%~1"=="" goto :help
if /I "%~1"=="build"    goto :build
if /I "%~1"=="test"     goto :test
if /I "%~1"=="clean"    goto :clean
if /I "%~1"=="kspclean" goto :kspclean
if /I "%~1"=="help"     goto :help
if /I "%~1"=="--help"   goto :help
if /I "%~1"=="-h"       goto :help

echo [ERROR] Unknown task "%~1". Try "task help".
popd
exit /b 1

:build
if "%~2"=="" goto :pick
if /I "%~2"=="debug"   ( set "DO_DEBUG=1" & set "DO_RELEASE=0" & goto :run )
if /I "%~2"=="release" ( set "DO_DEBUG=0" & set "DO_RELEASE=1" & goto :run )
if /I "%~2"=="both"    ( set "DO_DEBUG=1" & set "DO_RELEASE=1" & goto :run )
echo [ERROR] Unknown build variant "%~2". Use: debug, release, both.
popd
exit /b 1

:pick
echo.
echo  Novelfa Android - choose a build variant
echo.
echo    [1] Debug    fast build, debug-signed  -^> app-debug.apk
echo    [2] Release  R8/minified final build  -^> app-release-unsigned.apk + app-release.aab
echo    [3] Both
echo.
choice /C 123 /N /M "  Select 1, 2 or 3: "
if errorlevel 3 ( set "DO_DEBUG=1" & set "DO_RELEASE=1" & goto :run )
if errorlevel 2 ( set "DO_DEBUG=0" & set "DO_RELEASE=1" & goto :run )
if errorlevel 1 ( set "DO_DEBUG=1" & set "DO_RELEASE=0" & goto :run )
popd
exit /b 1

:run
set "RC=0"
if "%DO_DEBUG%"=="0" goto :skip_debug
echo.
echo === :app:assembleDebug ===
call "%SCRIPT_DIR%gradlew.bat" :app:assembleDebug --console=plain
set "RC=%ERRORLEVEL%"
if not "%RC%"=="0" goto :fail
rem Verify the APK was actually produced (Gradle can exit 0 without output on config errors)
call :verify "app\build\outputs\apk\debug\app-debug.apk" "debug APK"
if errorlevel 1 goto :fail
:skip_debug

if "%DO_RELEASE%"=="0" goto :skip_release
echo.
echo === :app:assembleRelease ===
call "%SCRIPT_DIR%gradlew.bat" :app:assembleRelease --console=plain
set "RC=%ERRORLEVEL%"
if not "%RC%"=="0" goto :fail
call :verify "app\build\outputs\apk\release\app-release-unsigned.apk" "release APK"
if errorlevel 1 goto :fail
echo.
echo === :app:bundleRelease ===
call "%SCRIPT_DIR%gradlew.bat" :app:bundleRelease --console=plain
set "RC=%ERRORLEVEL%"
if not "%RC%"=="0" goto :fail
call :verify "app\build\outputs\bundle\release\app-release.aab" "release AAB"
if errorlevel 1 goto :fail
:skip_release

echo.
echo [OK] Build finished successfully.
call :report
goto :done

:test
echo.
echo === :app:testDebugUnitTest ===
call "%SCRIPT_DIR%gradlew.bat" :app:testDebugUnitTest --console=plain
set "RC=%ERRORLEVEL%"
if not "%RC%"=="0" goto :fail
echo.
echo [OK] Unit tests passed.
goto :done

:clean
echo.
echo === gradlew clean ===
call "%SCRIPT_DIR%gradlew.bat" clean --console=plain
set "RC=%ERRORLEVEL%"
if not "%RC%"=="0" goto :fail
echo.
echo [OK] Clean finished.
goto :done

:kspclean
echo Removing KSP output - workaround for a Windows KSP FileAlreadyExists bug...
if not exist "app\build\generated\ksp" goto :ksp_none
rmdir /s /q "app\build\generated\ksp"
echo   removed.
goto :done
:ksp_none
echo   nothing to remove.
goto :done

:verify
rem %1 = relative path to artifact, %2 = friendly name
rem Fails if the file is missing or zero bytes (stale/failed build).
if not exist "%~1" (
    echo.
    echo [FAILED] %~2 not found at "%~1".
    echo         Gradle exited 0 but produced no output - the build did NOT succeed.
    exit /b 1
)
for %%F in ("%~1") do (
    if %%~zF==0 (
        echo.
        echo [FAILED] %~2 is 0 bytes - the build did NOT succeed.
        exit /b 1
    )
)
exit /b 0

:report
echo.
echo Outputs:
if exist "app\build\outputs\apk\debug\app-debug.apk" (
    for %%F in ("app\build\outputs\apk\debug\app-debug.apk") do echo   debug    %%~nxF  %%~zF bytes
)
if exist "app\build\outputs\apk\release\app-release-unsigned.apk" (
    for %%F in ("app\build\outputs\apk\release\app-release-unsigned.apk") do echo   release  %%~nxF  %%~zF bytes
)
if exist "app\build\outputs\bundle\release\app-release.aab" (
    for %%F in ("app\build\outputs\bundle\release\app-release.aab") do echo   bundle   %%~nxF  %%~zF bytes
)
echo.
echo NOTE: release artifacts are UNSIGNED unless a keystore is configured
echo       (see docs/PLAY-RELEASE.md). Never upload an unsigned APK to Play.
goto :eof

:fail
echo.
echo [FAILED] gradle exited with code %RC%.
popd
exit /b %RC%

:done
popd
exit /b 0

:help
echo.
echo Novelfa Android task runner
echo.
echo Usage: task ^<command^> [option]
echo.
echo   build [debug^|release^|both]   Build the app. No option = interactive menu.
echo   test                         Run unit tests (app:testDebugUnitTest).
echo   clean                        Remove build outputs.
echo   kspclean                     Remove KSP output (fixes a Windows KSP bug).
echo   help                         Show this help.
echo.
echo Examples:
echo   task build            then pick 1/2/3
echo   task build debug
echo   task build release
echo.
popd
exit /b 0
