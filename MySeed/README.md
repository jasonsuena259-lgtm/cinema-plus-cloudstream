# MySeed v3 — myseed.cam

النطاق الجديد: https://myseed.cam — نفس إضافة MySeed المستقلة (internalName=MySeed)، وليس إضافة ثانية.

## ما تغير؟

- أرشيف الأفلام /films، المسلسلات /seriestv والحلقات /episodestv؛ أقسام الموقع العربية مباشرة.
- بطاقات وصور من tt-archive-grid وtt-card-link، والبحث من /search?q=.
- مسلسل /series/{slug}: المواسم والحلقات الفعلية من tt-season وtt-episode-card، مع ترتيب الموسم والحلقة دون إدخال أعمال مقترحة بالخطأ.
- صفحة المشاهدة /watch/{slug}/servers: روابط iframes الحقيقية من button.tt-watch-server[data-src].
- صفحة التحميل /download/{slug}: المرايا الفعلية من .tt-download-list.
- يتم استخراج الوسائط والتحقق منها قبل إرسالها إلى CloudStream. ظهور رابط استضافة لا يضمن ملف فيديو شغّال؛ لا يُعرض رابط مزيف.
- حدود زمنية لكل مستضيف ومهلة إجمالية؛ نتائج السيرفرات التي نجحت تُرسل فورًا عند اكتشافها.

**مهم:** تم التحقق من بنية HTML مباشرة بمسبار GitHub Actions في 30-10-2026: فيلم GoodFellas يحتوي 23 زر مشاهدة و22 مرآة تحميل، وصفحات المسلسلات تستخدم tt-season.
هذا لا يعني ضمان فك/تشغيل جميع الاستضافات، خصوصًا المواقع التي تطلب JavaScript أو ترفض الطلبات الآلية أو تعتمد على عضوية/DRM. لم يُثبت التشغيل على جهاز Android.

## التثبيت بعد نجاح CI والنشر

رابط المستودع المستقر:
```
https://raw.githubusercontent.com/jasonsuena259-lgtm/cinema-plus-cloudstream/builds/repo-myseed.json
```

### الاختبارات

```sh
./gradlew MySeed:testDebugUnitTest MySeed:make
```

ملفات v1 وv2 القديمة محفوظة ولا تُحذف من السجل. اختبارات MySeed القديمة (myseed.baby) تُستخدم كاختبارات رجعية للمحلل السابق؛ v3 يشغّل MySeedCamParser.
