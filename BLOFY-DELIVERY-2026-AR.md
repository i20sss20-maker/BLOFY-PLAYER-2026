# تسليم BLOFY PLAYER 2026 — الإصدار 2026.08.20.3

الحزمة موحدة وتحتوي على:

1. `BLOFY-WEB-2026/web-test`: واجهة وخادم Railway والجلسة والتفعيل والكتالوج والروابط الموقّعة.
2. `BLOFY-ANDROID-2026`: تطبيق Android وAndroid TV وتشغيل أصلي مباشر عبر Media3 1.11.0.
3. `codemagic.yaml`: فحص وبناء APK تجريبي من GitHub.

## القرار المعماري النهائي

Railway يدير الحساب والجلسة والتفعيل والبيانات، لكنه **لا يحمل فيديو APK**. التطبيق يحصل على رابط موقّع قصير، ثم يتصل Media3 من الجهاز إلى مزود المستخدم مباشرة. هذا يمنع مشكلة مزود يعمل من المنزل لكنه يرفض Railway/FFmpeg أو عنوان مركز البيانات.

| المشكلة التي ظهرت | السبب المثبت | الإصلاح النهائي |
|---|---|---|
| `/api/play/live` يعيد 500 | Safari أرسل TS الخام إلى مسار تحويل Railway، وFFmpeg خرج بالرمز 1 | لم يعد هذا المسار مستخدمًا في APK |
| كان Media3 يعمل ثم أصبح يفشل | مهلة 20 ثانية كانت تحوله إلى `compat=2` ثم إلى FFmpeg | حذف التحويل نهائيًا؛ إعادة المحاولة تبقى مباشرة |
| القناة عالقة على التخزين المؤقت | بعض MPEG‑TS لا يحتوي AUD/IDR بالشكل المتوقع | تفعيل أعلام TS الرسمية في Media3 |
| الخروج من القناة يحتاج تحديث الصفحة | الاعتماد على مشغل الويب أو حالة قديمة | `PlayerActivity` أصلية؛ Back ينفذ `finish()` فورًا، وإغلاق الويب ينظف الفيديو وHLS |
| مسلسلات أو حلقات ناقصة | اختلاف صيغ Xtream بين المزودين | تطبيع `id` و`stream_id` و`episode_id` والمواسم المسطحة أو المجمعة |
| فقدان التفعيل بعد النشر | التخزين داخل الحاوية المؤقتة | `LICENSE_DB_PATH=/data/licenses.json` مع Railway Volume |
| احتمال تشغيل رابط غير موثوق من WebView | الجسر القديم قبل مسار التوقيع | الجسر يقبل `/api/native-play` الموقّع ومن نفس الأصل والمنفذ فقط |

## إعداد Railway الثابت

- Root Directory: `BLOFY-WEB-2026/web-test`
- Volume mount: `/data`
- `LICENSE_DB_PATH=/data/licenses.json`
- `SESSION_SECRET` ثابت وطويل
- `ADMIN_TOKEN` ثابت وطويل
- `TRANSCODE_VIDEO=false`

بعد كل نشر افتح:

`https://blofy-player-2026-production.up.railway.app/api/health`

ويجب أن تجد `version` مساويًا `2026.08.20.3` و`nativePlayback` مساويًا `direct`.

## بناء APK

ادفع الملفات إلى `main`. Codemagic يشغّل الفحص واختبارات Android ثم ينتج:

`BLOFY-ANDROID-2026/app/build/outputs/apk/debug/app-debug.apk`

النسخة التجريبية قابلة للتثبيت. نسخة المتجر تحتاج توقيعًا خاصًا لا يوضع داخل GitHub.

## معيار النجاح الحقيقي

- في APK: يظهر في Railway Log سطر `native-open ... mode=direct`.
- لا يظهر `ffmpeg-start` بسبب تشغيل APK.
- تظهر أول صورة وتعمل Back على الجهاز الفعلي.
- تجربة Safari ليست بديلًا عن اختبار APK لأن Safari لا يشغّل Media3.
