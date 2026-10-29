# MySeed | ماي سيد — إضافة مستقلة

تثبيت المستودع من إعدادات CloudStream ← الإضافات ← إضافة مستودع:

```text
https://raw.githubusercontent.com/jasonsuena259-lgtm/cinema-plus-cloudstream/main/repo-myseed-v1.json
```

بعدها نزّل **MySeed | ماي سيد** واخترها من مقدمي المحتوى. لا تحتاج إزالة Cinema Plus أو CineWave. ترتيب الأقسام الـ16 يطابق قائمة الموقع؛ القسم الفارغ في الموقع لا يمكن ملؤه ببيانات مخترعة.

الإصدار 2 تجريبي: البحث العربي والإنجليزي، البطاقات، تفاصيل الأفلام، مواسم وحلقات المسلسلات، اكتشاف السيرفرات وقت التشغيل، وHLS/MP4 مع فحص محتوى الفيديو. يظهر رابط اجتاز التحقق فقط؛ اسم زر Mp4 لا يعني أن الرابط MP4، والجودة تأتي من manifest عند توفرها.

تم تنزيل وفك عينات فيديو من 12 سيرفرًا في العينة المختبرة. **لم يثبت التشغيل داخل CloudStream على Android، ولا نجاح جميع السيرفرات.** Vidspeed أعاد 403 للحلقة الأصلية، لكن رابطَه اجتاز التحقق في الفيلم الإضافي؛ وأربعة مستضيفين آخرين لم يعطوا مصدرًا قابلًا للتحقق. التفاصيل في [تقرير السيرفرات](../docs/myseed/SERVER_TEST_REPORT.md) و[تحليل الموقع](../docs/myseed/SITE_ANALYSIS.md).

## البناء والاختبار

```bash
./gradlew MySeed:testDebugUnitTest MySeed:make
python3 tests/check_icu_regex.py
```

الناتج `MySeed/build/MySeed.cs3`. الاختبارات المحلية تعمل دون الإنترنت. لتفعيل فحص الموقع والمستضيفين:

```bash
MYSEED_LIVE_TEST=1 MYSEED_REPORT_PATH=/tmp/myseed-results.json MYSEED_CAPTURE_PATH=/tmp/myseed-private.json ./gradlew MySeed:testDebugUnitTest --rerun-tasks
python3 tests/probe_myseed_media.py /tmp/myseed-private.json --output /tmp/media-results.json --samples /tmp/myseed-samples
```

ملف CAPTURE خاص يحتوي روابط مؤقتة وترويسات: لا تنشره أو تضعه في Git. اختبار Vidspeed يُسجل SKIPPED فقط عند رد 403؛ حالات الفشل الأخرى لا تُحوَّل إلى نجاح. الاختبارات الحية مرتبطة بتوفر المستضيف في وقت الفحص.

GitHub Actions يبني الإضافة مستقلة وينشر بياناتها في فرع builds. رابط main أعلاه يستخدم ملف cs3 المبني والمرفق في dist.

## تحديث السرعة v2

إرسال الروابط فور جاهزيتها، ومهل محدودة للمصادر البطيئة. [القياسات والتفاصيل](../docs/myseed/PERFORMANCE_V2.md). حدّث الإضافة من مستودعها الحالي؛ رابط v1 يستقبل تحديث v2 أيضًا.
