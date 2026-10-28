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
