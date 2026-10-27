# Cinema Plus لـ CloudStream

محاولة تطوير إضافة Kotlin لموقع Cinema Plus. الحالة: **قيد التطوير**. نجح البناء وأنتج [CinemaPlus.cs3](dist/CinemaPlus.cs3)، لكن لم يُختبر داخل التطبيق ولا تتحقق شروط القبول بعد. راجع [المشاكل المعروفة](KNOWN_ISSUES.md) و[تحليل الموقع](docs/SITE_ANALYSIS.md) و[تقرير المصادر](docs/SERVER_TEST_REPORT.md).

للبناء في بيئة Android SDK 35 وJDK 17، من مجلد المشروع:

```sh
./gradlew CinemaPlus:testDebugUnitTest CinemaPlus:make makePluginsJson
```

يمكن تجربة ملف cs3 الناتج من واجهة إضافات CloudStream. بعد رفع المشروع إلى مستودع GitHub وتشغيل workflow، يُفترض إنشاء فرع `builds` يحوي الحزمة و`plugins.json` و`repo.json` بعناوين ذلك المستودع؛ أضف رابط `repo.json` الخام في إعدادات إضافات CloudStream. لم يُختبر النشر ولا يوجد مستودع منشور لهذه النسخة حالياً. لا تستخدم `build/plugins.json` الناتج محلياً دون تعيين `GITHUB_REPOSITORY` لأنه يحتوي عنوان القالب الافتراضي. فحوص الشبكة الاختيارية: `python3 tests/probe_sources.py`؛ تحتاج Python وffprobe واتصالاً بالمصادر، وتنزّل أجزاء قصيرة لا المحتوى كاملاً.
