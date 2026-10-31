#!/bin/bash
# تجهيز CallHelper كاملاً عبر ADB بدون لمس شاشة الجوال (Linux / Mac)
P=com.example.callhelper
A11Y=$P/$P.InCallAccessibilityService
NL=$P/$P.CallNotificationListener

for perm in RECORD_AUDIO READ_PHONE_STATE ANSWER_PHONE_CALLS READ_CONTACTS READ_CALL_LOG POST_NOTIFICATIONS; do
  adb shell pm grant $P android.permission.$perm 2>/dev/null && echo "granted $perm"
done

# تفعيل خدمة الأزرار (Accessibility) مع الحفاظ على الخدمات المفعّلة سابقاً
CUR=$(adb shell settings get secure enabled_accessibility_services | tr -d '\r')
[ "$CUR" = "null" ] && CUR=""
# نحذف خدمتنا من القائمة (إن وُجدت) وننظّف النقطتين الزائدتين
CUR=$(echo "$CUR" | sed "s#$A11Y##; s#::#:#g; s#^:##; s#:\$##")
# وضع قيمة فارغة عبر adb يضيع في الاقتباس، فنحذف المفتاح بدلها
if [ -z "$CUR" ]; then
  adb shell settings delete secure enabled_accessibility_services
else
  adb shell settings put secure enabled_accessibility_services "$CUR"
fi
sleep 1
if [ -z "$CUR" ]; then NEW="$A11Y"; else NEW="$CUR:$A11Y"; fi
adb shell settings put secure enabled_accessibility_services "$NEW"
adb shell settings put secure accessibility_enabled 1

# تفعيل الوصول للإشعارات (طريقة الرد عبر إشعار المكالمة)
adb shell cmd notification allow_listener $NL

# منع قتل التطبيق بالخلفية
adb shell dumpsys deviceidle whitelist +$P
adb shell cmd appops set $P RUN_IN_BACKGROUND allow
adb shell cmd appops set $P RUN_ANY_IN_BACKGROUND allow

adb shell am start -n $P/.MainActivity --ez autostart true
echo "تم. شغّل الخدمة بالضغط على الدائرة في التطبيق (من الماوس عبر scrcpy)."
