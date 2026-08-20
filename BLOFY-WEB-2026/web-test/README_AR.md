# BLOFY PLAYER WEB / Railway 2026

الإصدار: `2026.08.21.5`

هذا المجلد هو Root Directory الصحيح في Railway:

`BLOFY-WEB-2026/web-test`

## مسؤولية الخادم

- جلسة Xtream أو M3U مشفّرة داخل Cookie آمنة و`HttpOnly`.
- فئات، قنوات، أفلام، مسلسلات، مواسم، حلقات، EPG وصور عبر Proxy موقّع.
- تجربة وتفعيل محفوظان حسب رقم الجهاز داخل Railway Volume.
- ربط QR آمن يحفظ بيانات Xtream/M3U مشفّرة ويتيح للتطبيق الأصلي استلامها بمفتاح الجهاز.
- رابط Media3 مؤقت ومشفّر؛ `/api/native-play` يتحقق منه ثم يعيد توجيه التطبيق إلى المصدر مباشرة.
- واجهة RTL كاملة وPWA، مع تنقّل ريموت وBack وإغلاق المشغل.

## إعداد Railway

1. Root Directory: `BLOFY-WEB-2026/web-test`
2. أنشئ Volume واربطه بالمسار `/data`.
3. أضف المتغيرات التالية:

```text
SESSION_SECRET=قيمة عشوائية ثابتة 32 حرفًا أو أكثر
ADMIN_TOKEN=قيمة إدارة سرية 20 حرفًا أو أكثر
LICENSE_DB_PATH=/data/licenses.json
DEVICE_PROFILE_DB_PATH=/data/device-profiles.json
TRIAL_DAYS=7
REQUEST_TIMEOUT_MS=9000
CACHE_TTL_MS=300000
MAX_TRANSCODE_SESSIONS=4
TRANSCODE_VIDEO=false
```

4. اترك `ACTIVATION_URL` فارغًا لاستخدام `/activate` المدمج.
5. بعد النشر افتح `/api/health`. الإصدار الصحيح يعيد:

```json
{"ok":true,"version":"2026.08.21.5","nativePlayback":"direct"}
```

## مهم: المتصفح مقابل APK

Safari والمتصفح لا يستخدمان Media3. تشغيل HLS الحقيقي في المتصفح مدعوم، أما رابط TS حي خام فقد يحتاج تحويل FFmpeg من Railway، وقد يرفضه بعض المزودين أو يحظرون عناوين مراكز البيانات. لذلك نجاح أو فشل القناة على iPad لا يثبت نجاح أو فشل APK.

المسار المعتمد للقنوات في Android هو Media3 المباشر. FFmpeg باقٍ فقط كأفضل محاولة لمتصفح الويب، وليس ضمن مسار APK.

## الاختبارات المحلية

```bash
npm ci
npm run check
npm test
npm start
```

إنشاء رمز تفعيل:

```bash
curl -X POST "https://blofy-player-2026-production.up.railway.app/api/admin/codes" \
  -H "Authorization: Bearer YOUR_ADMIN_TOKEN" \
  -H "Content-Type: application/json" \
  -d '{"days":365,"maxUses":1,"label":"عميل 001"}'
```

الرمز الناتج رقمي من 8 خانات. صفحة `/activate` تقبل رمزًا من 6 إلى 12 رقمًا، وعند فتحها من QR تعرض أيضًا حقول إرسال بيانات الباقة إلى الجهاز.

> لا تضع `SESSION_SECRET` أو `ADMIN_TOKEN` داخل GitHub أو الصور العامة.
