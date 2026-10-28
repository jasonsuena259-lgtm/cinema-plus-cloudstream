# تحليل TukTuk Cinema

تاريخ الفحص: 2026-10-28. الموقع: https://tuktukcinemas.com/ . إضافة مستقلة باسم `TukTukCinema` ونقطة الدخول `com.tuktuk.TukTukPlugin`.

## الفهرسة والتفاصيل

الصفحة `/` بوابة تعريفية؛ الفهرس في `/main/` و`/recent/` و`/category/movies-2/`، والمسلسلات في `/sercat/.../`. تُقرأ `.Block--Item > a[href]` مع `title` أو `.title` وصورة `data-src` ثم `src`؛ صور data URI البديلة لا تُعرض كأغلفة. تُزال تكرارات الرابط فقط. التصفح يتبع `/page/N/` وزر `a.next.page-numbers`. البحث الأصلي WordPress باستخدام `?s=`، مع ترميز الاستعلام العربي.

صفحات المحتوى تحتوي `.post-title` و`.story` و`og:image` وJSON-LD لأنواع Movie وTVEpisode وTVSeries وTVSeason. لا تُستخدم قوائم TMDB العامة. عناوين الحلقات وروابطها هي هويتها المستقرة؛ الرقم والموسم من رابط `-sNN-eNN` أو عنوان الحلقة الصريح.

## المواسم والحلقات

صفحات الحلقة تعرض `.allseasonss .Block--Item a`، وصفحة المسلسل تعرض `.SeriesPosterGrid .Block--Item a`. تحتوي كل بطاقة على رقم الموسم. تُجلب جميع صفحات المواسم وتُقرأ `a.SeriesEpisodeCard` أو `.episodes--list--side a`؛ يُتبع رابط الصفحة التالية عند وجوده. المثال الفعلي محمد الفاتح أعاد 90 حلقة عبر أربعة مواسم في اختبار Provider. فشل موسم منفرد لا يلغي بقية المواسم؛ فشل جميع الحلقات يعرض خطأ واضحًا.

## كشف مصادر التشغيل

`#main-video-frame[data-crypt]` هو Base64 لعنوان iframe. وكل `.server--item[data-link]` يستخدم التحويل الذي ينفذه `wp-content/themes/TukTukCinema3/setup.js`: أخذ النص قبل `0REL0Y&`، ثم عكسه ثم Base64. هذا ترميز رابط عادي وليس فك DRM. تتطابق الطريقتان في fixtures الفيلم والحلقة؛ يمنع التكرار.

المستضيف المرصود `megatuktuk.store/iframe/{id}` يستخدم Inertia. سكربت `script[data-page=app]` يعلن component=`files/mirror/video` والإصدار. مشغل الموقع يجلب `streams` عبر إعادة طلب نفس iframe مع X-Inertia وX-Inertia-Partial-Data؛ تستخدم الإضافة نفس الطلب العام وكوكيز الجلسة. `props.streams.data` يعلن مستويات resolution وقائمة mirrors. لا تُحفظ tokens أو عناوين الفيديو المؤقتة في كود الإنتاج؛ يُعاد الاكتشاف عند التشغيل.

## المستضيفون

- earnvids وstreamruby: قراءة مصادر HLS من سكربت المشغل العادي أو عبر JsUnpacker. لا يُنفذ JavaScript الخاص بالإعلانات.
- Firestream: مشغل الموقع يرسل `token-blob` إلى `/api/videos/{slug}/resolve` للحصول على `signedVideoUrl` و`signedVideoSdUrl`. تُستخدم العملية العادية فقط، دون تجاوز انتظار التحميل أو أي حماية.
- vidara: مشغل الموقع يرسل filecode وdevice وcodecs إلى `/api/stream`. تستخدم الإضافة Android وقائمة codecs اختيارية فارغة لعدم الادعاء بدعم AV1/HEVC على كل جهاز. تُقرأ streaming_url.
- للمستضيفين الآخرين تُستخدم مستخرجات CloudStream المتاحة. صفحات CAPTCHA تُستبعد دون حلها. فشل مستخرج أو عدم توافقه لا يوقف المصادر الأخرى.

الروابط تحمل Referer وUser-Agent اللازمين. الجودة من resolution المعلن؛ مثال المصدر 1616×1072 يُحفظ 1072 وليس 1080 مفترضًا. لا تُعطى نسخة SD جودة رقمية غير مثبتة. HLS master يصل إلى المشغل بمستوياته، ولا يُعاد تسمية HLS إلى MP4.

## حدود التغطية

توجد بوابة تنزيل منفصلة `go.php?u=...` تقود إلى share4max؛ لم يُنفذ حل مستقل لبوابة التنزيل. دعم التنزيل هنا من روابط الفيديو التي تُسلَّم لآليات CloudStream. لا توجد ضمانات لتنزيل ملف كامل أو تشغيل على Android قبل الاختبار هناك. ملفات الترجمة الخارجية ليست مضمونة؛ يمرر ما تُرجعه مستخرجات CloudStream فقط، وتبقى المسارات المضمنة في HLS للمشغل.

Fixtures المحلية مأخوذة من خمس صفحات الموقع الفعلية مع إزالة السكربتات غير JSON-LD والنماذج. يوجد تقرير مستقل لاختبارات الشبكة والتشغيل.
