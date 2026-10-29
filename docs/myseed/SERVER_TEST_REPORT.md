# تقرير سيرفرات MySeed — 2026-10-29

العينة: `watch.php?vid=77396b294`، صفحة الموقع تسميها Silo الموسم الثالث الحلقة 11. بعض المستضيفين يسمون الملف الحلقة 10 والأخيرة؛ هذا اختلاف في بيانات الموقع/المضيف ولم نغير ترقيم الموقع أو نستبدل محتواه.

| المصدر | الاستخراج | تحقق رابط الفيديو | فك عينة فيديو | تنزيل عينة | تشغيل CloudStream | ملاحظات |
|---|---|---|---|---|---|---|
| Vidspeed | PASS | BLOCKED | NOT_TESTED | NOT_TESTED | NOT_TESTED | الرابط يُستخرج؛ CDN يرد 403 من اتصال الاختبار، وكذلك المتصفح. |
| Mp4 | PASS | PASS | PASS | PASS | NOT_TESTED | اسم الزر Mp4 لكن المصدر الحقيقي HLS. |
| Rty1 | PASS | PASS | PASS | PASS | NOT_TESTED | رابط مستخرج حديثًا؛ تحقق من المحتوى لا من HTTP وحده. |
| Vidoba | PASS | PASS | PASS | PASS | NOT_TESTED | رابط مستخرج حديثًا؛ تحقق من المحتوى لا من HTTP وحده. |
| Mp4plus | PASS | PASS | PASS | PASS | NOT_TESTED | روابط MP4 مباشرة، لا HLS. |
| Anafast | PASS | PASS | PASS | PASS | NOT_TESTED | رابط مستخرج حديثًا؛ تحقق من المحتوى لا من HTTP وحده. |
| Vidmoly | PASS | NOT_TESTED | PASS | PASS | NOT_TESTED | رابط مستخرج حديثًا؛ تحقق من المحتوى لا من HTTP وحده. |
| Vidara | PASS | PASS | PASS | PASS | NOT_TESTED | أول جزء أعاد 206/1024 بايت؛ فُحص جزء لاحق منفصل. |
| Upzur | PASS | PASS | PASS | PASS | NOT_TESTED | MP4 مباشر ضمن مصفوفة نصية معكوسة. |
| Hgcloud | BLOCKED | NOT_TESTED | NOT_TESTED | NOT_TESTED | NOT_TESTED | صفحة المتصفح تعرض Sandboxed؛ لا مصدر مُثبت. |
| Streamhls | UNSUPPORTED | NOT_TESTED | NOT_TESTED | NOT_TESTED | NOT_TESTED | فُحص نموذج POST /dl؛ لم يظهر مصدر قابل للتحقق. |
| Luluvdo | PASS | PASS | PASS | PASS | NOT_TESTED | رابط مستخرج حديثًا؛ تحقق من المحتوى لا من HTTP وحده. |
| Bysejikuar | UNSUPPORTED | NOT_TESTED | NOT_TESTED | NOT_TESTED | NOT_TESTED | مشغل JavaScript؛ لم يظهر مصدر في اختبار المتصفح العادي. |
| Mixdrop | PASS | PASS | PASS | PASS | NOT_TESTED | رابط مستخرج حديثًا؛ تحقق من المحتوى لا من HTTP وحده. |
| Dsvplay | BLOCKED | NOT_TESTED | NOT_TESTED | NOT_TESTED | NOT_TESTED | تعذر الاتصال من البيئة. |
| Voe | PASS | PASS | PASS | PASS | NOT_TESTED | اتباع تحويل الصفحة العام ثم قراءة مصدر MP4. |

**هذه نتائج عينة واحدة لكل مستضيف، وليست ضمانًا لكل ملفات الموقع.** الصفحات الرئيسية والأقسام والبحث والتفاصيل اختُبرت منفصلة لفيلم ومسلسل. مسار الفيديو يبدأ باستخراج روابط جديدة من play.php ثم المشغل، دون حفظ الروابط الموقعة في المستودع.

التنزيل PASS يعني تنزيل جزء HLS أو مقطع MP4 وفحصه بـffprobe وفك ثانيتين بـffmpeg. لم يُختبر تنزيل حلقة كاملة أو واجهة التنزيل في CloudStream أو التقديم والتأخير داخل التطبيق؛ حالتها NOT_TESTED. HLS يبقى HLS، ولم نسمِّ manifest بامتداد MP4.

Vidspeed: الاختبار المستقل متجاوز (SKIPPED) بسبب 403 المؤكد، وليس PASS. الأسماء غير المدعومة تُفحص عبر Extractors التطبيق ثم WebView عادي عند توفره؛ لم يُثبت نجاح هذا البديل على Android. لا تجاوز CAPTCHA أو DRM أو أخطاء TLS.

البيئة بلا /dev/kvm. أُعيدت محاولة تشغيل Android API 30 دون تسريع؛ لا دليل على تشغيل الإضافة داخله. لذلك ظهور Provider والمشاهدة والتنزيل والبحث في واجهة Android جميعها NOT_TESTED.

أدلة قابلة للمراجعة: native-results.json وmedia-results.json، اختبارات MySeedTest، وأداة tests/probe_myseed_media.py. لا تحتوي التقارير على روابط تشغيل مؤقتة أو كوكيز.

محاولة Android الأخيرة: اكتمل إقلاع API 30 بعد 327 ثانية، ثم فشل تثبيت CloudStream 4.8.0 بالرسالة `Failure calling service package: Broken pipe (32)`. لم تصل المحاولة إلى تحميل الإضافة أو تشغيل فيديو.

فحص محتوى إضافي: نجح loadLinks للحلقة الأولى من قائمة Silo وللفيلم Sense and Sensibility 2026، مع مصادر فيديو مختلفة صالحة؛ أسماء المصادر في additional-content-results.json. هذا تحقق روابط ووسائط، وليس تشغيلًا داخل Android.

التوسع إلى المحتوى الآخر اكتشف أيضًا Forafile وUqload وVibuxer. اجتازت روابطها الاستخراج والتحقق من الفيديو (PASS)، لكن تنزيل وفك عينة منها وAndroid: NOT_TESTED. Vidspeed اجتاز التحقق في الفيلم الإضافي؛ 403 أعلاه يخص رابط الحلقة الأصلية وقت الفحص ولا يثبت حجب المستضيف بأكمله.
