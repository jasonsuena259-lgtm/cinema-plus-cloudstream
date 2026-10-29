# تحليل akw.cam — 2026-10-29

هذه إضافة مستقلة AkwCam، وليست تغيير نطاق إضافة akwam.day القديمة.

## الكتالوج والبحث

الصفحات الفعلية: `/all-content`، `/films`، `/seriestv`، `/episodestv`، وروابط `/category/...` في القائمة. البطاقات `a.br-card-link`، العنوان `.br-card-title` والصورة `img[src]` والنوع `.ak-type-label`. بعض البطاقات في المصدر بلا صورة؛ لا تُختلق بوسترات. الصفحة التالية `/page/2` من `.br-pagination`؛ البحث GET `/search?q=...` يدعم العربية والإنجليزية.

## المسلسلات والحلقات

بطاقة المسلسل تشير إلى `/series/{slug}`. الصفحة تحتوي `.br-series-season` لكل موسم، وعنوانه `.br-series-season-head h3`، وكل حلقة `.br-series-detail-card-link` مع `h4` وصورة ورقم `.br-series-detail-epno`. يتم الاحتفاظ برابط الحلقة نفسه كمعرّف التشغيل. صفحة الحلقة تتضمن رابط المسلسل في `.br-breadcrumb`؛ فتح بطاقة الحلقة يعرض مسلسلها بكامل الحلقات المنشورة. روابط الموسم تُحل عبر حلقة فعلية إلى المسلسل الأم.

العينة Lizzie McGuire: 19 حلقة منشورة من الموسم الثاني، تبدأ بالحلقة 15 وتنتهي 34 مع غياب 23. لا يتم ترقيمها 1–19 أو اختراع الحلقة الناقصة. عينة Karakuri Kiden Hiwou Senki: 26 حلقة. Where the Mask Ends: حلقة واحدة منشورة وقت الفحص.

## المشاهدة والتحميل

صفحة التفاصيل `.br-single-actions a` تعطي صفحة `/watch/{slug}/servers` وصفحة `/download/{slug}` إن نشرت. بعض الأفلام لا تنشر زر تحميل أصلًا.

المشاهدة: `.br-server-item[data-src]` و`data-name`؛ iframe `#brWatchFrame` يكرر السيرفر المحدد ولا يُضاف مرتين. JavaScript الصفحة يبدل iframe ويكتب «يعمل الآن» عند الاختيار؛ هذه العبارة ليست فحص صحة.

التحميل: **جميع** `.br-download-row a[href]`. عينة الحلقة فيها 7 سيرفرات مشاهدة و19 رابط تحميل. تشمل Mixdrop وDood وRapidgator وNitroflare وVikingfile وUqload وStreamwish وByse وVidhide وVOE وStreamruby وSend ومرآة q7v3k8m2p1.top. روابط التحميل هي صفحات مستضيفين غالبًا وليست ملفات MP4 مباشرة.

## المرايا

Share4max يعرض `script[data-page][type=application/json]` ببيانات Inertia. طلب GET لنفس iframe مع ترويسات Inertia القياسية ونسخة الصفحة يعيد `props.streams.data[].mirrors[].link`. عينة Blue Heron أعادت 21 مرآة بثلاث جودات مسماة في المصدر، تتضمن Earnvids وVidara وMixdrop وDood وFirestream وByse وStreamruby وLulustream وMp4upload. لا تُختزل هذه الصفحة إلى سيرفر واحد.

نفس طلب q7v3k8m2p1.top في العينة رُفض؛ لا يتم تغيير الهوية أو انتحال نطاق مسموح لتجاوز الرفض. قائمة المرايا تُطلب وقت التشغيل ولا تُخزن روابط CDN الموقّعة في الإنتاج.

## الفصل بين الاستخراج وصحة الفيديو

`AkwCamParser` لا يتصل بالشبكة. `AkwCamRepository` يجلب الصفحات بمهلة وإعادة محاولة واحدة للأخطاء المؤقتة. `AkwCamResolver` يوسع المرايا ثم يحل كل مستضيف مستقلاً، عبر JavaScript literals/JsUnpacker والمستخرجات المتاحة في CloudStream؛ Vidara يستخدم طلب المشغل العام. WebView fallback يلتزم TLS ويتوقف عند CAPTCHA. `AkwCamLinkValidator` يفحص توقيع MP4 أو HLS والجزء الأول، ويرفض HTML وDRM. لا يكفي HTTP 200.

تُرسل النتائج المتحققة فور وصولها؛ فشل مستضيف لا يمنع الآخرين. حد المحاولة الإجمالي 120 ثانية، والفردية 18 ثانية، والتوازي 6؛ المصادر التي تتجاوز المهلة لا تُعلن ناجحة. التحميل والتشغيل يستخدمان روابط CloudStream نفسها؛ لا تحويل وهمي لملف HLS إلى MP4.

## مراجع تمت مراجعتها

- `Abodabodd/re-3arabi` commit `e1dc6d9bc454042e4cc9eeff26b06ce94b8fcd73`.
- إضافة `Akwam/Akwam.kt` تستهدف ak.sv وتستخدم HTML مختلفًا؛ لم تُنقل محدداتها ولا تحويل HTTPS إلى HTTP ولا الجودة الافتراضية 720p.
- `Anime4up/.../anime4up.kt`، طريقة `processMegabox`: أفادت في فهم بروتوكول Inertia للمرايا. كتبنا تنفيذًا منفصلًا باستخدام Jackson والتحقق من الردود والمخطط، ثم اختبرنا البروتوكول على Share4max الفعلي.
- `.github/workflows/main.yml`: مراجعة البناء والنشر المستقل. آخر ثلاث تشغيلات Actions المفحوصة ناجحة، وآخرها https://github.com/Abodabodd/re-3arabi/actions/runs/40629683324 . نجاح Actions ليس إثبات مشاهدة Android.

Fixtures محفوظة في `AkwCam/src/test/resources/akwcam`؛ نسخة المرايا منزوعة رمز الجلسة. لا توجد روابط CDN مؤقتة أو كوكيز في المستودع.
