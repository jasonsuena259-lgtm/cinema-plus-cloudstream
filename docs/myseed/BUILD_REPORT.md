# تقرير البناء

Gradle 8.12، Android compileSdk 35، Java 21، 2026-10-29.

`MySeed:testDebugUnitTest MySeed:make`: BUILD SUCCESSFUL.

الاختبارات: 14، فشل 0، أخطاء 0، تجاوز 1 (Vidspeed HTTP 403).

فحص Android ICU: 46 تعبيرًا نمطيًا، 0 أخطاء. ملف cs3 يحتوي manifest.json وclasses.dex، ونقطة الدخول com.myseed.MySeedPlugin.

التشغيل داخل CloudStream على Android غير مثبت: NOT_TESTED. نتائج فك الفيديو على الكمبيوتر موثقة منفصلة.

SHA-256: `3f8da0acbebe6e1fdc0fd4b0704372f2df33b749e2b8b27783e12584068b5ff3`

محاولة Android الأخيرة: اكتمل إقلاع API 30 بعد 327 ثانية، ثم فشل تثبيت CloudStream 4.8.0 بالرسالة `Failure calling service package: Broken pipe (32)`. لم تصل المحاولة إلى تحميل الإضافة أو تشغيل فيديو.

اختبارات fixtures بعد تنسيقها: BUILD SUCCESSFUL (10 محلية ناجحة؛ 4 حية غير مفعلة في هذا التشغيل).
تم التحقق من رابط repo العام، ثم plugins، ثم cs3 وبصمته وحجمه ونقطة دخوله: PASS.

فحص إضافي مستقل: liveDifferentEpisodeAndMovie نجح عبر Provider.loadLinks الحقيقي لحلقة أخرى وفيلم؛ النتائج في additional-content-results.json. إجمالي الحالات التي اختُبرت: 15؛ 14 ناجحة وواحدة محجوبة (Vidspeed).

نجح GitHub Actions: https://github.com/jasonsuena259-lgtm/cinema-plus-cloudstream/actions/runs/40859987255
جرى أيضًا التحقق من سلسلة repo → plugins → cs3 في فرع builds وبصمة الملف: PASS.
