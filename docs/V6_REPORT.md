# تقرير فصل الإضافتين وCinema Plus v6

## سبب هذا الإصدار

المستخدم أبلغ عن خطأ في جميع عناصر v5. لا يوجد جهاز متصل (`adb devices` فارغ)، ولم يصل نص الاستثناء الأخير؛ لذلك **السبب الدقيق على الجهاز غير مثبت**، ولا يصح الإعلان عن حل نهائي.

أكوام v1 وحدة Gradle مستقلة ونقطة دخول `com.akwam.AkwamPlugin`. Cinema Plus v6 يسجل `CinemaPlusProvider` فقط. يتحقق برنامج التغليف من manifest ومن غياب فئات المزوّد الآخر في DEX لكل ملف.

## تغييرات تحميل تفاصيل Cinema Plus

- تطبيع الروابط النسبية وHTTP و`&amp;` القديمة مع حصر المضيف ومسار الموقع.
- منع النتيجة الفارغة: رسائل أخطاء مميزة للروابط غير المدعومة، فقدان صفحة المسلسل، تغير بنية الحلقات، وعدم توافق API.
- طلبات TMDB اختيارية: مهلة 4 ثوان لكل طلب، ومهلة مشتركة 4.5 ثانية لطلبات المواسم مع حد أربعة اتصالات متوازية. يعرض قائمة الحلقات الأصلية حتى عند تعطل البيانات الوصفية.
- الحفاظ على إلغاء العمليات، والمحاولة المحدودة، والفهرس عند فشل مصدر جزئي.
- إزالة درجة التقييم الرقمية الاختيارية في هذا الإصدار لتقليل اعتماد صفحة التفاصيل على API أحدث؛ ليس لدينا دليل أنها سبب خطأ v5.

## اختبارات محلية

تستدعي اختبارات Provider دالة `load()` الفعلية باستخدام ملف CloudStream API، وتتحقق من `MovieLoadResponse` و`TvSeriesLoadResponse` مع بيانات الموقع المحلية وغياب TMDB. تشمل الاختبارات كل خرائط المسلسلات الـ69 وجميع بطاقات الأفلام ذات مصادر Mux في fixtures، وليس Moon Knight فقط. هذه اختبارات JVM؛ ليست تشغيلًا على Android.

Fixtures `site-index.html` و`site-discover.html` مأخوذة من مستودع الموقع الذي فُحص، و`series-maps.json` يحتوي خرائطه الـ69. اختبارات أخرى تفحص JSON التالف، انقطاع مصدر الفهرسة، إلغاء الطلب، الروابط القديمة، ربط الحلقات، وأخطاء الصفحات. يوجد فحص ICU منفصل لتوافق regex مع عائلة محرك Android.

## الشبكة: 15 عينة

نتائج خام: [network-v6.jsonl](network-v6.jsonl). فُحص master ثم variant والأجزاء وبيانات الفيديو عبر ffprobe. تنزيل الجزء ليس تنزيل الفيلم كاملًا أو إثبات تشغيل التطبيق.

| العينة | manifest | الأجزاء وقراءة الفيديو | التشغيل في التطبيق | التنزيل داخل التطبيق |
|---|---|---|---|---|
| series/111110/1-1 | PASS | PASS | NOT_TESTED | NOT_TESTED |
| series/111110/2-8 | PASS | PASS | NOT_TESTED | NOT_TESTED |
| series/106379/1-1 | PASS | PASS | NOT_TESTED | NOT_TESTED |
| series/106379/2-8 | PASS | PASS | NOT_TESTED | NOT_TESTED |
| series/92749/1-1 | PASS | PASS | NOT_TESTED | NOT_TESTED |
| series/92749/1-6 | PASS | PASS | NOT_TESTED | NOT_TESTED |
| series/246621/4-1 | PASS | PASS | NOT_TESTED | NOT_TESTED |
| series/246621/1-12 | PASS | PASS | NOT_TESTED | NOT_TESTED |
| series/60059/1-1 | PASS | PASS | NOT_TESTED | NOT_TESTED |
| series/60059/6-13 | PASS | PASS | NOT_TESTED | NOT_TESTED |
| movie/1701686 | PASS | PASS | NOT_TESTED | NOT_TESTED |
| movie/1773759 | PASS | PASS | NOT_TESTED | NOT_TESTED |
| movie/1512010 | PASS | PASS | NOT_TESTED | NOT_TESTED |
| movie/1306845 | PASS | PASS | NOT_TESTED | NOT_TESTED |
| movie/862739 | PASS | PASS | NOT_TESTED | NOT_TESTED |

لم يتغير محلل أكوام أو resolver في هذا الإصدار؛ نقلت الملفات إلى حزمة مستقلة. تبقى اختبارات المضيفات الشبكية السابقة موثقة في SERVER_TEST_REPORT.md ولا تعني أنها أُعيدت داخل التطبيق.

نتيجة البناء النهائية: `BUILD SUCCESSFUL`. اجتاز 25 اختبارًا آليًا دون فشل، واجتازت 20 عبارة regex فحص ICU. نجح التحقق من فصل ملفات DEX وmanifest للإضافتين.
