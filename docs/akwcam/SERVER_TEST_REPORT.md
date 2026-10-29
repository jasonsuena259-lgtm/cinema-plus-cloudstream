# تقرير سيرفرات AkwCam — 2026-10-29

## حدود الإثبات

- **NOT_TESTED**: التشغيل داخل CloudStream على جهاز/محاكي Android والتقديم والتأخير ومدير التنزيل داخل التطبيق. تعذر تثبيت CloudStream في المحاكي البرمجي في المحاولات السابقة بهذه البيئة؛ لم نكرر وصف تشغيل مكتبي بأنه Android.
- **PASS** لبناء `.cs3` و16 اختبارًا محليًا؛ اختبار الشبكة المنفصل نفذ بنجاح مع تسجيل نتائج كل مصدر. الاختبار الحي يسجل فشل المستضيفين ولا يحوّله إلى نجاح لأن عملية Gradle اكتملت.
- نجاح التحقق أدناه يعني توقيع MP4 أو manifest HLS وجزء فيديو صالح؛ لا يضمن إكمال التنزيل أو استمرار جميع أجزاء الفيديو.
- عينة Uqload للحلقة: تنزيل 3 ثوانٍ وفك ترميز ثانيتين **PASS**، H264 ‏636×480 وAAC، حجم 177663 بايت.
- عينة Earnvids للفيلم: عينات 1080 و720 و480 اجتازت تنزيل العينة وفك الترميز في إعادة الاختبار بالترويسات المطابقة. Mp4upload اجتاز تنزيل العينة وفك الترميز أيضًا. بقية العينات ونتائج الإعادة في `media-results.json`. لم يُنزّل أي عمل كامل.
- Mixdrop: استُخرجت الروابط، لكن روابط خرج التنفيذ الحي أعادت 403 عند الفحص؛ رابط من استجابة curl منفصلة أعاد MP4 صحيحًا. النتيجة المعتمدة للإضافة FAIL حتى لا يُخلط اختلاف الطلبات بنجاح مؤكد.
- q7v3k8m2p1.top: تحميل HTML نجح، طلب المرايا رُفض. Share4max لفيلم Blue Heron أعاد 21 مرآة. لا يوجد تجاوز لقيود النطاق أو CAPTCHA.
- Rapidgator/Nitroflare وغيرها: جرى اكتشاف صفحاتها وفحص المستخرجات، ولم ينتج فيديو قابل للاستخدام. التحميل التلقائي لهذه العينات **UNSUPPORTED** في الإصدار الحالي. لا يُعرض HTML على أنه فيديو.

## النتائج التفصيلية

عمود «الاستخراج» هنا استخراج **رابط الفيديو**؛ روابط صفحات جميع المستضيفين المدرجة اكتُشفت مسبقًا بنجاح.

| المصدر | المحتوى / المسار | استخراج الفيديو | التحقق من الفيديو | التشغيل داخل التطبيق |
|---|---|---|---|---|
| سيرفر 4 · doodstream.com | akdrama-lizzie-mcguire-e2-s15 / watch | FAIL | FAIL | NOT_TESTED |
| سيرفر 5 · doodstream.com | akdrama-lizzie-mcguire-e2-s15 / watch | FAIL | FAIL | NOT_TESTED |
| سيرفر 6 · doodstream.com | akdrama-lizzie-mcguire-e2-s15 / watch | FAIL | FAIL | NOT_TESTED |
| سيرفر 7 · q7v3k8m2p1.top | akdrama-lizzie-mcguire-e2-s15 / watch | FAIL | FAIL | NOT_TESTED |
| سيرفر 3 · mixdrop.ps | akdrama-lizzie-mcguire-e2-s15 / watch | PASS | FAIL | NOT_TESTED |
| سيرفر 2 · mixdrop.ps | akdrama-lizzie-mcguire-e2-s15 / watch | PASS | FAIL | NOT_TESTED |
| سيرفر 1 · mixdrop.ps | akdrama-lizzie-mcguire-e2-s15 / watch | PASS | FAIL | NOT_TESTED |
| تحميل 4 · doodstream.com | akdrama-lizzie-mcguire-e2-s15 / download | FAIL | FAIL | NOT_TESTED |
| تحميل 5 · doodstream.com | akdrama-lizzie-mcguire-e2-s15 / download | FAIL | FAIL | NOT_TESTED |
| تحميل 7 · rapidgator.net | akdrama-lizzie-mcguire-e2-s15 / download | FAIL | FAIL | NOT_TESTED |
| تحميل 6 · doodstream.com | akdrama-lizzie-mcguire-e2-s15 / download | FAIL | FAIL | NOT_TESTED |
| تحميل 1 · mixdrop.ps | akdrama-lizzie-mcguire-e2-s15 / download | PASS | FAIL | NOT_TESTED |
| تحميل 3 · mixdrop.ps | akdrama-lizzie-mcguire-e2-s15 / download | PASS | FAIL | NOT_TESTED |
| تحميل 8 · nitroflare.com | akdrama-lizzie-mcguire-e2-s15 / download | FAIL | FAIL | NOT_TESTED |
| تحميل 2 · mixdrop.ps | akdrama-lizzie-mcguire-e2-s15 / download | PASS | FAIL | NOT_TESTED |
| تحميل 13 · doodstream.com | akdrama-lizzie-mcguire-e2-s15 / download | FAIL | FAIL | NOT_TESTED |
| تحميل 11 · streamwish.to | akdrama-lizzie-mcguire-e2-s15 / download | FAIL | FAIL | NOT_TESTED |
| تحميل 14 · byseraguci.com | akdrama-lizzie-mcguire-e2-s15 / download | FAIL | FAIL | NOT_TESTED |
| تحميل 10 · uqload.vc | akdrama-lizzie-mcguire-e2-s15 / download | PASS | PASS | NOT_TESTED |
| تحميل 12 · mixdrop.ag | akdrama-lizzie-mcguire-e2-s15 / download | PASS | FAIL | NOT_TESTED |
| تحميل 9 · vikingfile.com | akdrama-lizzie-mcguire-e2-s15 / download | FAIL | FAIL | NOT_TESTED |
| تحميل 15 · vidhidevip.com | akdrama-lizzie-mcguire-e2-s15 / download | FAIL | FAIL | NOT_TESTED |
| تحميل 17 · streamruby.com | akdrama-lizzie-mcguire-e2-s15 / download | FAIL | FAIL | NOT_TESTED |
| تحميل 19 · q7v3k8m2p1.top | akdrama-lizzie-mcguire-e2-s15 / download | FAIL | FAIL | NOT_TESTED |
| تحميل 18 · send.cm | akdrama-lizzie-mcguire-e2-s15 / download | FAIL | FAIL | NOT_TESTED |
| تحميل 16 · voe.sx | akdrama-lizzie-mcguire-e2-s15 / download | FAIL | FAIL | NOT_TESTED |
| doodstream · 1080p (source) | akfilm-blue-heron / watch | FAIL | FAIL | NOT_TESTED |
| earnvids · 1080p (source) | akfilm-blue-heron / watch | PASS | PASS | NOT_TESTED |
| streamruby · 1080p (source) | akfilm-blue-heron / watch | PASS | FAIL | NOT_TESTED |
| mixdrop · 1080p (source) | akfilm-blue-heron / watch | PASS | FAIL | NOT_TESTED |
| byse · 1080p (source) | akfilm-blue-heron / watch | FAIL | FAIL | NOT_TESTED |
| vidara · 1080p (source) | akfilm-blue-heron / watch | PASS | PASS | NOT_TESTED |
| doodstream · 720p | akfilm-blue-heron / watch | FAIL | FAIL | NOT_TESTED |
| earnvids · 720p | akfilm-blue-heron / watch | PASS | PASS | NOT_TESTED |
| Firestream · 1080p (source) | akfilm-blue-heron / watch | PASS | FAIL | NOT_TESTED |
| byse · 720p | akfilm-blue-heron / watch | FAIL | FAIL | NOT_TESTED |
| vidara · 720p | akfilm-blue-heron / watch | PASS | FAIL | NOT_TESTED |
| mixdrop · 720p | akfilm-blue-heron / watch | PASS | FAIL | NOT_TESTED |
| doodstream · 480p | akfilm-blue-heron / watch | FAIL | FAIL | NOT_TESTED |
| vidara · 480p | akfilm-blue-heron / watch | PASS | FAIL | NOT_TESTED |
| byse · 480p | akfilm-blue-heron / watch | FAIL | FAIL | NOT_TESTED |
| mixdrop · 480p | akfilm-blue-heron / watch | PASS | FAIL | NOT_TESTED |
| lulustream · 480p | akfilm-blue-heron / watch | PASS | FAIL | NOT_TESTED |
| earnvids · 480p | akfilm-blue-heron / watch | PASS | PASS | NOT_TESTED |
| mp4upload · 480p | akfilm-blue-heron / watch | PASS | PASS | NOT_TESTED |
| Firestream · 720p | akfilm-blue-heron / watch | PASS | FAIL | NOT_TESTED |
| Firestream · 480p | akfilm-blue-heron / watch | PASS | FAIL | NOT_TESTED |

## القوائم

العينات الحية موثقة في `catalog-results.json`: الأفلام، المسلسلات، أحدث الحلقات، أحدث الإضافات، الصفحة الثانية، البحث العربي والإنجليزي، ومسلسلان إضافيان. fixtures تختبر Lizzie McGuire مع الأرقام الفعلية والموسم الثاني، ونقص الحلقة 23 دون اختلاقها.

## إعادة الاختبار

`./gradlew AkwCam:testDebugUnitTest AkwCam:make` للاختبارات المحلية. `AKWCAM_LIVE=1` يشغّل الاختبار الحي المنفصل، ويكتب نتائج تشخيص خاصة خارج المستودع. لا تشارك ملفات `*-private.tsv`؛ فيها روابط CDN مؤقتة. الاختبارات الحية ليست ضمانًا أن كل صفحة وكل مستضيف سيعمل في كل بلد.
