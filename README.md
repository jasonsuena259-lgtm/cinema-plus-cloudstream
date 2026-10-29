# إضافة TukTuk Cinema المستقلة

رابط التثبيت في CloudStream ← الإعدادات ← الإضافات ← إضافة مستودع:

```text
https://raw.githubusercontent.com/jasonsuena259-lgtm/cinema-plus-cloudstream/main/repo-tuktukcinema-v2.json
```

الاسم: `TukTuk Cinema | توك توك سينما`. ثبّتها مستقلة واخترها من مزوّدي المحتوى. لا تحتاج إزالة Cinema Plus أو أكوام.

نجحت اختبارات حية للفهرس والتفاصيل والبحث ومصادر فيلم وحلقة. التشغيل على Android والتنزيل الكامل لم يُختبرا. بعض المستضيفين محجوبون أو غير مدعومين؛ راجع [التحليل](docs/TUKTUK_ANALYSIS.md) و[نتائج الفحص](docs/TUKTUK_TEST_REPORT.md).

للبناء: `./gradlew TukTukCinema:testDebugUnitTest TukTukCinema:make`، والناتج `TukTukCinema/build/TukTukCinema.cs3`.

---

# Cinema Plus وأكوام — إضافتان مستقلتان لـ CloudStream

كل إضافة لها ملف `.cs3` ونقطة دخول وقائمة تثبيت مستقلة. Cinema Plus v6 لا يسجل أكوام، وأكوام v2 لا يحتاج Cinema Plus.

روابط المستودعات التي تُضاف من إعدادات CloudStream ← الإضافات ← إضافة مستودع:

- Cinema Plus: https://raw.githubusercontent.com/jasonsuena259-lgtm/cinema-plus-cloudstream/main/repo-cinemaplus-v6.json
- أكوام: https://raw.githubusercontent.com/jasonsuena259-lgtm/cinema-plus-cloudstream/main/repo-akwam-v2.json

عند الانتقال من النسخة المجمّعة v5، احذف الإضافة المجمّعة أولًا، ثم ثبّت كل إضافة تريدها من قائمتها. أعد تشغيل CloudStream وافتح العناصر من بحث المزوّد مجددًا عند وجود روابط قديمة في المكتبة. لا يلزم مسح بيانات التطبيق أو المكتبة.

## حالة الاختبار

نجاح البناء أو اختبار مصدر على الشبكة ليس تأكيد تشغيله داخل التطبيق. أبلغ المستخدم عن تعطل v5؛ لم تُعَد المشكلة على جهاز Android في هذه البيئة ولا يتوفر جهاز متصل. v6 يحد مدة طلبات البيانات الوصفية، يصحح بعض الروابط القديمة، ويعرض سبب فشل التفاصيل بدل إرجاع `null`. ما زال تأكيد الحل على جهاز المستخدم مطلوبًا.

راجع [تقرير الإصدار والفصل](docs/V6_REPORT.md)، [تحليل الموقع](docs/SITE_ANALYSIS.md)، [تحليل أكوام](docs/AKWAM_ANALYSIS.md)، و[المشاكل المعروفة](KNOWN_ISSUES.md).

## البناء

يتطلب JDK 17 أو أحدث وAndroid SDK 35:

```sh
./gradlew CinemaPlus:testDebugUnitTest Akwam:testDebugUnitTest CinemaPlus:make Akwam:make
python3 tests/check_icu_regex.py
python3 tests/package_release.py
```

النواتج: `CinemaPlus/build/CinemaPlus.cs3` و`Akwam/build/Akwam.cs3`. اختبارات عقد Provider تستخدم ملف API الذي يجلبه CloudStream Gradle plugin إلى ذاكرته المؤقتة. تستخدم اختبارات البيانات fixtures محلية، ولا تعتمد على TMDB أو الإنترنت.

GitHub Actions يبني الإضافتين ويختبرهما، ثم ينشر الملفات والقوائم المستقلة إلى فرع `builds`. فحوص المصادر الشبكية اختيارية، وتتطلب Python وffprobe: `python3 tests/probe_representative.py /path/to/cinema-plas-bot`. تُنزّل هذه الفحوص أجزاء قصيرة فقط، وليست اختبارات تنزيل أفلام كاملة.

تحديث أكوام v2: راجع [تقرير الاختبارات والعائق الشبكي الحالي](docs/AKWAM_V2_REPORT.md). أكد المستخدم عمل Cinema Plus v6؛ هذا لا يثبت عمل أكوام.

تحديث TukTuk v2 يوحّد روابط البطاقات والتفاصيل؛ راجع [تقرير الإصدار](docs/TUKTUK_V2_REPORT.md).

## CineWave — إصدار تجريبي مستقل

رابط إضافة مستودعه داخل CloudStream:

```text
https://raw.githubusercontent.com/jasonsuena259-lgtm/cinema-plus-cloudstream/main/repo-cinewave-v1.json
```

من إعدادات CloudStream افتح «الإضافات/المستودعات» ثم «إضافة مستودع»، الصق الرابط،
وثبّت **CineWave**. فعّل اللغة **الإنجليزية** في مرشح المزودين لأن بيانات الموقع إنجليزية.
ملف التثبيت المباشر: [CineWave-v1.cs3](dist/CineWave-v1.cs3).

تم البناء واختبار القوائم والبحث والتفاصيل وعينات HLS على بيئة العمل.
**لم يُثبت التشغيل داخل CloudStream على أندرويد؛ ليست نسخة مضمونة أو مكتملة لكل السيرفرات.**
الموقع يعرض كتالوج TMDB بروابط مشغلات؛ بعض العناوين قد لا يتوفر لها فيديو.
التفاصيل: [تحليل الموقع](docs/CINEWAVE_ANALYSIS.md)، [نتائج الاختبارات](docs/CINEWAVE_TEST_REPORT.md).

لإعادة الاختبارات المحلية: `./gradlew CineWave:testDebugUnitTest CineWave:make`.
للاختبارات الحية أضف `CINEWAVE_LIVE_TEST=1`؛ اختبار الروابط المؤقتة يتطلب
`CINEWAVE_LINK_CAPTURE` يشير إلى ملف خاص خارج المستودع. لا ترفع روابط موقعة أو cookies إلى Git.

### MySeed | ماي سيد — مستقل

[تعليمات MySeed](MySeed/README.md) · [تحليل الموقع](docs/myseed/SITE_ANALYSIS.md) · [نتائج السيرفرات والتنزيل](docs/myseed/SERVER_TEST_REPORT.md)

```text
https://raw.githubusercontent.com/jasonsuena259-lgtm/cinema-plus-cloudstream/main/repo-myseed-v1.json
```

نسخة تجريبية مبنية؛ عينات فيديو موثقة من 12 مصدرًا، مع قيود Vidspeed والمستضيفين الآخرين في التقرير. تشغيل Android غير مثبت.

### CimaNow VIP — نسخة اختبار الدخول والتوافق

[التثبيت والقيود](CimaNowVIP/README.md). ليست نسخة مثبتة للتصفح أو المشاهدة داخل Android؛ الدخول يتم على جهاز المستخدم فقط.

## AkwCam — أكوام akw.cam

إضافة مستقلة جديدة للقوائم والبطاقات والمواسم والحلقات وروابط المشاهدة والتحميل، مع توسيع مرايا Share4max. إصدار تجريبي؛ بعض السيرفرات غير متاحة، وAndroid غير مختبر. [التثبيت والتفاصيل](docs/akwcam/README.md) و[نتائج الفحص](docs/akwcam/SERVER_TEST_REPORT.md).
