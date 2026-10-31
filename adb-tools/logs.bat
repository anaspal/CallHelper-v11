@echo off
REM يعرض سجل التطبيق مباشرة: اتصل على جوالك واضغط رفع الصوت وشوف شو يصير
adb logcat -c
adb logcat -s A11y:D CallCtrl:D NotifL:D VoiceSvc:D
