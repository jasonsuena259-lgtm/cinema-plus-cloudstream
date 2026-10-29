# CimaNow VIP — نسخة اختبار v1

هذه نسخة تمهيدية **للدخول المحلي والتحقق من توافق صفحات حسابك**، وليست إعلانًا بأن الموقع وكل سيرفراته يعملان. لا تستبدل MySeed أو CinemaPlus.

رابط المستودع المستقل:

```text
https://raw.githubusercontent.com/jasonsuena259-lgtm/cinema-plus-cloudstream/main/repo-cimanowvip-v1.json
```

بعد التثبيت افتح إعدادات الإضافة من رمز الترس، وسجّل الدخول بنفسك داخل صفحة الموقع. أكمل أي تحقق يدوي يظهر، ثم اضغط «إغلاق والعودة للإضافة». اختر CimaNow VIP وحدّث الصفحة الرئيسية. إذا لم يظهر ترس الإعدادات، يلزم التحقق من إصدار تطبيقك وتوافقه مع Plugin.openSettings.

الجلسة في CookieManager الخاص بـWebView على جهازك. لا يوجد حساب مشترك ولا إرسال للكوكيز إلى المطوّر، ولا تضمين لكلمة مرور أو token في ملف cs3. تطبق ترويسات الجلسة على نطاق VIP المطابق فقط. تسجيل الخروج يتم من قائمة الحساب في الموقع داخل نافذة الدخول، دون مسح جلسات الإضافات الأخرى.

## ما تم وما لم يتم اختباره

- بناء cs3: PASS.
- 7 اختبارات محلية: النطاقات المسموحة، حذف token من هوية الحلقة، فك href.li وإزالة التكرار، Jetload، استبعاد شعار البطاقة، ترتيب الموسم والحلقة، HTML المتغير.
- اختبار مستخرج Forafile بلغة Kotlin مع التحقق من MP4: PASS (اختبار حي منفصل).
- تنزيل عينة Forafile وفك H.264/AAC بأدوات ffmpeg/ffprobe: PASS، أبعاد 1280×640.
- تسجيل الدخول داخل Android، فهرس VIP، البحث، كل المواسم، تبويبات السيرفرات الديناميكية، التشغيل والتنزيل داخل CloudStream: **NOT_TESTED**.

المحلل يستخدم بنية المقالات والتفاصيل المعروفة من دراسة الموقع العام، مع قراءة الصفحة بعد JavaScript عند الحاجة. لا توجد fixtures من VIP بعد الدخول حتى الآن. لذلك قد تتطلب محددات VIP أو نموذج بحث POST أو تبويبات السيرفرات تحديثًا بعد الاختبار الفعلي. الصفحة الرئيسية تستخرج الأقسام الموجودة في DOM، ولا تدعي دعم pagination غير مثبت.

Forafile يُستخرج عبر نموذج التحميل العادي. Jetload يفك معامل link ثم يتحقق من الرابط؛ عينة المستخدم أعادت 403. بقية المستضيفين تمر عبر Extractors التطبيق ثم تحقق الوسائط. FRDL يعرض hCaptcha ولا يوجد تجاوز له. لا يتم حفظ روابط تشغيل مؤقتة في بيانات الحلقات.

## البناء

```bash
./gradlew CimaNowVIP:testDebugUnitTest CimaNowVIP:make
CIMANOW_VIP_LIVE=1 ./gradlew CimaNowVIP:testDebugUnitTest --tests '*liveForafileExtractsActualMp4' --rerun-tasks
```

الملف في `CimaNowVIP/build/CimaNowVIP.cs3`. التفاصيل: [تحليل المستودع وVIP](../docs/cimanow/UPSTREAM_AND_VIP_ANALYSIS.md) و[تقرير المصادر](../docs/cimanow/DOWNLOAD_SOURCE_REPORT.md).
