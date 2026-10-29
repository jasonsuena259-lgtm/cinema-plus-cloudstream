# CineWave: نتائج السيرفرات والاختبارات

التاريخ: 2026-10-29. الاختبار داخل متصفح Chromium عادي ثم طلبات HTTP وffprobe/ffmpeg.
أعمدة الاستخراج تعني التقاط طلب الفيديو في المتصفح المكتبي، وليست إثبات عمل WebView أندرويد.

| المصدر | المحتوى | استخراج HLS | فحص العينة وفكها | مشاهدة Android | تحميل CloudStream | ملاحظات |
|---|---|---|---|---|---|---|
| VidSrc | Fight Club (1999) | FAIL | NOT_TESTED | NOT_TESTED | NOT_TESTED | HTTP 200 |
| MoviesAPI | Fight Club (1999) | PASS | PASS | NOT_TESTED | NOT_TESTED | HTTP 200؛ H.264/AAC، 1920×800، HLS AES-128 عادي |
| VidLink | Fight Club (1999) | FAIL | NOT_TESTED | NOT_TESTED | NOT_TESTED | HTTP 200؛ رُصد HLS في فحص سابق، لم يُرصد في الإعادة؛ وصول متقطع |
| VidEasy | Fight Club (1999) | BLOCKED | NOT_TESTED | NOT_TESTED | NOT_TESTED | تعذر تنقل المتصفح؛ السبب الدقيق غير معزول |
| XPass | Fight Club (1999) | PASS | PASS | NOT_TESTED | NOT_TESTED | HTTP 200؛ H.264/AAC، 640×266؛ 67 بايت قبل MPEG-TS، فُحص بصيغة TS |
| 111Movies | Fight Club (1999) | FAIL | NOT_TESTED | NOT_TESTED | NOT_TESTED | HTTP 200 |
| 2Embed | Fight Club (1999) | FAIL | NOT_TESTED | NOT_TESTED | NOT_TESTED | HTTP 200 |
| Mapple | Fight Club (1999) | FAIL | NOT_TESTED | NOT_TESTED | NOT_TESTED | HTTP 200 |
| PrimeSrc | Fight Club (1999) | FAIL | NOT_TESTED | NOT_TESTED | NOT_TESTED | HTTP 200 |
| VidUp | Fight Club (1999) | BLOCKED | NOT_TESTED | NOT_TESTED | NOT_TESTED | HTTP 403 |
| VidFast | Fight Club (1999) | BLOCKED | NOT_TESTED | NOT_TESTED | NOT_TESTED | HTTP 403 |
| VidRock | Fight Club (1999) | BLOCKED | NOT_TESTED | NOT_TESTED | NOT_TESTED | HTTP 503 |
| HNEmbed | Fight Club (1999) | FAIL | NOT_TESTED | NOT_TESTED | NOT_TESTED | HTTP 200 |
| TV-PrimeSrc | Game of Thrones S1E1 | FAIL | NOT_TESTED | NOT_TESTED | NOT_TESTED | HTTP 200 |
| XPass | Game of Thrones S1E1 | FAIL | NOT_TESTED | NOT_TESTED | NOT_TESTED | فتحت صفحة الحلقة الصحيحة، دون HLS خلال مهلة الفحص |

## البناء والاختبارات

- Gradle: `CineWave:testDebugUnitTest CineWave:make` — PASS.
- 11 اختبارًا، صفر فشل/تجاوز في التشغيل الحي النهائي.
- اختبارات محلية للبيانات الفعلية، هوية المواسم والحلقات، الروابط التالفة، إزالة التكرار، استبعاد روابط TV المبهمة وخلط معرفات الأنمي، ورفض صورة/HTML بوصفها فيديو.
- اختبارات حية: الرئيسية والبحث وتفاصيل فيلم ومسلسل، اكتشاف 13 سيرفرًا، وفحص MoviesAPI وXPass بواسطة محقق الروابط الإنتاجي.
- فحص ICU لصيغ regex: PASS.
- تنزيل عينة وفكها على بيئة العمل: PASS للمصدرين المذكورين. لا يعني تنزيل عمل كامل أو تنزيلًا داخل التطبيق.

## Android

اختبار التشغيل والتقديم والتأخير وتنزيل الفيديو داخل CloudStream: **NOT_TESTED**.
محاولات المحاكي السابقة في البيئة نفسها فشلت باستقرار خدمات Android/تثبيت التطبيق؛
لم يُعد هذا العمل إعلان نجاح لها. راجع ANDROID_TEST_SETUP.md.
لا توجد أدلة كافية بعد لاعتبار الإضافة مكتملة أو مضمونة على الهاتف. النسخة منشورة كتجريبية.

## إعادة فحص المتصفح والعينات

تحتاج Node.js مع Playwright وChromium في `/usr/bin/chromium`، وPython وffprobe/ffmpeg وOpenSSL.
لا يُستخدم هذا الاختبار في CI ولا ينشر ملفات الجلسة:

```bash
node tests/cinewave_browser_probe.cjs /tmp/cinewave-private
python3 tests/probe_cinewave_media.py /tmp/cinewave-private/private-all-streams.json --output /tmp/cinewave-report.json --samples /tmp/cinewave-private/samples
```

ملف `private-all-streams.json` قد يحتوي روابط موقعة وترويسات جلسة؛ يبقى خارج Git.
