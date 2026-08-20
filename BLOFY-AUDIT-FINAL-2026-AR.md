# خلاصة التدقيق النهائي — BLOFY PLAYER

الإصدار المستهدف: `2026.08.20.3`

## التشخيص المثبت

Railway والجلسة وقاعدة التفعيل والواجهة كانت تعمل. الخطأ الأحمر `500` ظهر في `/api/play/live` لأن اختبار Safari أخذ بث TS الخام إلى FFmpeg على Railway؛ سجل التشغيل أثبت `ffmpeg-exit code=1`. أما Media3 الذي عمل سابقًا فهو مسار Android مختلف.

داخل APK كان يوجد خطأ منطقي ثانٍ: إذا لم تبدأ القناة خلال 20 ثانية يضيف المشغل `compat=2`، فيحوّل طلب Media3 المباشر إلى نفس مسار Railway/FFmpeg الفاشل. لذلك بدا أن Media3 توقف، بينما الكود هو الذي غادر Media3 المباشر مبكرًا.

## ما تغير

- Media3 مباشر دائمًا، ولا توجد ترقية تلقائية إلى FFmpeg.
- Railway يتأكد من التوقيع ثم يعيد `302` للمصدر الأصلي حتى لو أرسل عميل قديم `compat=2`.
- دعم MPEG‑TS غير المنتظم بأعلام Media3 الرسمية.
- مهلة واقعية واتصال مباشر جديد عند الضغط على إعادة المحاولة.
- HLS وDASH ومحتوى Progressive مربوط بأنواع صحيحة؛ الامتداد المجهول يُكتشف بدل إجباره على HLS.
- جسر Android ورابط Railway أكثر تقييدًا وأمانًا.
- رقم إصدار ظاهر في `/api/health` لمنع اختبار Deployment قديم بالخطأ.

## كيف نعرف أن النسخة الصحيحة وصلت

1. `/api/health` يعرض `2026.08.20.3` و`direct`.
2. APK التجريبي من Codemagic يحمل Version Name `2026.08.20.3-test` (الإصدار الأساسي `2026.08.20.3`).
3. عند اختيار قناة من APK يظهر `native-open ... mode=direct` في Railway.
4. لا يظهر `ffmpeg-start` لهذا التشغيل.

## الحكم

التصميم والربط والمصدر بعد هذا التحديث صحيحون وفق السجلات والكود واختبارات الخادم والمراجع الرسمية. لا يجوز وصف التطبيق بأنه مختبر 100% على جهازك قبل أن يبني Codemagic هذا الإصدار ويُجرب APK فعليًا؛ هذا اختبار جهاز، وليس تعديلًا إضافيًا في Railway.

## المراجع الرسمية التي بُني عليها الإصلاح

- Android Media3 — معالجة MPEG‑TS الذي يفتقد AUD أو IDR، والسماح بإعادة التوجيه بين HTTPS وHTTP: <https://developer.android.com/media/media3/exoplayer/troubleshooting>
- Android Media3 — الصيغ والحاويات المدعومة: <https://developer.android.com/media/media3/exoplayer/supported-formats>
- إصدارات Media3 الرسمية: <https://developer.android.com/jetpack/androidx/releases/media3>
- توافق Android Gradle Plugin 8.12: <https://developer.android.com/build/releases/agp-8-12-0-release-notes>
- Railway — Healthchecks: <https://docs.railway.com/deployments/healthchecks>
- Railway — Volumes والتخزين الدائم: <https://docs.railway.com/volumes/reference>
