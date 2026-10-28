# إعداد اختبار Android

هذه بيئة اختبار فعلية بالمحاكي، وليست بديلًا عن إثبات تشغيل مصادر الموقع.

## البيئة المستخدمة في 2026-10-28

- Android Emulator 37.2.12.0.
- صورة `system-images;android-30;google_apis;x86_64` (Android 11).
- AVD: `fasel-test`، ذاكرة 2048 MB، معالجان افتراضيان، عرض 320×640.
- لا يوجد `/dev/kvm`؛ استُخدم `-accel off`، ولذلك الإقلاع الأول بطيء.
- CloudStream الرسمي: [v4.8.0](https://github.com/recloudstream/cloudstream/releases/tag/v4.8.0)،
  الحزمة `com.lagradost.cloudstream3`، versionCode 68.

## أمر التشغيل

المسارات التالية خاصة ببيئة العمل، وليست ملفات تحتاج تثبيتها على هاتف المستخدم:

```bash
ANDROID_HOME=/workspace/toolchain/android-sdk \
ANDROID_SDK_ROOT=/workspace/toolchain/android-sdk \
/workspace/toolchain/android-sdk/emulator/emulator \
  -avd fasel-test -accel off -no-window -no-audio -no-snapshot \
  -gpu swiftshader -memory 2048 -cores 2 -http-proxy http://proxy:8080
```

انتظر `adb shell getprop sys.boot_completed` حتى يعيد `1`؛ ظهور المحاكي في
`adb devices` وحده لا يثبت اكتمال الإقلاع أو توفر خدمة تثبيت الحزم.

```bash
adb install -r cloudstream-4.8.0.apk
adb shell monkey -p com.lagradost.cloudstream3 \
  -c android.intent.category.LAUNCHER 1
adb shell dumpsys activity activities
adb exec-out screencap -p > cloudstream-screen.png
```

لا تُعتبر نتيجة أمر إطلاق التطبيق وحدها نجاحًا: تحقق من النشاط المعروض ولقطة الشاشة
وسجل الأعطال. فتح التطبيق وحده لا يثبت تحميل إضافة أو مشاهدة فيديو.

## متطلبات اختبار الموقع

يجب أن تسمح شبكة الاختبار بالوصول إلى الموقع ومصادره. لا تعطل التحقق من TLS،
ولا تتجاوز صفحات الحماية. إذا لزم CA لشبكة الاختبار، اضبط الثقة في بيئة الاختبار
بصورة صحيحة بدل تعديل الإضافة لتجاهل الشهادات.

النتائج الحالية الخاصة بـ FaselHD مسجلة في `FASELHD_ACCESS_REPORT.md`.

المحاولة الثانية استخدمت `-no-boot-anim -cores 1` بدل `-cores 2`.
المحاكي رفض تمرير `ro.hw_timeout_multiplier` عبر `-prop` لأنه يقبل خصائص `qemu.*`
فقط. بعد تشغيل `adb root` ضُبطت الخاصية داخل المحاكي إلى `10`، لكن هذا لا يثبت
أن كل الخدمات التي بدأت قبل ضبطها استخدمت القيمة الجديدة.

ظهر اكتمال الإقلاع في المحاولة الثانية، لكن تثبيت APK انتهى بـ `Broken pipe (32)`
متزامنًا مع إعادة تشغيل خدمات Android. نتيجة تثبيت وفتح CloudStream: **FAIL**.
نتيجة مشاهدة إضافة FaselHD وتنزيلها داخل التطبيق: **NOT_TESTED**.
