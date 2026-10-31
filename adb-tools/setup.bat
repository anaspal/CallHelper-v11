@echo off
chcp 65001 >nul
REM Full CallHelper setup over ADB without touching the phone screen (Windows)
set P=com.example.callhelper
set A11Y=%P%/%P%.InCallAccessibilityService
set NL=%P%/%P%.CallNotificationListener

for %%p in (RECORD_AUDIO READ_PHONE_STATE ANSWER_PHONE_CALLS READ_CONTACTS READ_CALL_LOG POST_NOTIFICATIONS) do adb shell pm grant %P% android.permission.%%p 2>nul

set CUR=
for /f "delims=" %%i in ('adb shell settings get secure enabled_accessibility_services') do set CUR=%%i
if "%CUR%"=="null" set CUR=
echo الخدمات الحالية: %CUR%

echo %CUR%| find "%A11Y%" >nul
if not errorlevel 1 goto RESTART_A11Y

REM Not enabled yet: append it to the existing services
if "%CUR%"=="" (set NEW=%A11Y%) else (set NEW=%CUR%:%A11Y%)
adb shell settings put secure enabled_accessibility_services "%NEW%"
goto AFTER_A11Y

:RESTART_A11Y
REM Already enabled: turn it off and on again so it reconnects
adb shell settings delete secure enabled_accessibility_services
timeout /t 1 >nul
adb shell settings put secure enabled_accessibility_services "%CUR%"

:AFTER_A11Y
adb shell settings put secure accessibility_enabled 1

adb shell cmd notification allow_listener %NL%

adb shell dumpsys deviceidle whitelist +%P%
adb shell cmd appops set %P% RUN_IN_BACKGROUND allow
adb shell cmd appops set %P% RUN_ANY_IN_BACKGROUND allow

adb shell am start -n %P%/.MainActivity --ez autostart true
echo تم.
pause
