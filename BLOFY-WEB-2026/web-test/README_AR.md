# BLOFY PLAYER API

هذه الخدمة ليست مشغل فيديو. دور Railway في النسخة الحالية هو التحكم فقط:

- تفعيل الجهاز والتجربة والاشتراك.
- حفظ وربط بيانات Xtream Codes أو M3U/M3U8.
- جلب التصنيفات والكتالوج والمواسم والحلقات وEPG.
- إصدار رابط تشغيل قصير العمر بعد التحقق من الجهاز.
- إعادة توجيه التطبيق إلى رابط مزود IPTV الأصلي.

## مسار التشغيل

`Android -> BLOFY API authorization -> provider URL -> Media3`

لا يوجد في Railway:

- FFmpeg server أو transcoding.
- HLS conversion.
- video proxy/relay.
- WebView player أو Web player.
- hls.js.

المشغل الفعلي موجود داخل تطبيق Android ويستخدم Media3 مباشرة، مع TS أولًا للبث الحي في Xtream وHLS fallback داخل التطبيق.

## التشغيل

```bash
npm ci
npm test
npm run check
npm start
```

## أهم المتغيرات

راجع `.env.example`. أهمها `SESSION_SECRET`, `ADMIN_TOKEN`, `LICENSE_DB_PATH` و`TRIAL_DAYS`.

صفحة `/activate` هي الواجهة الويب الوحيدة المقصودة وتستخدم لربط الجهاز وإرسال بيانات الباقة. المسار `/api/health` يعرض حالة الخدمة، ويجب أن يظهر `nativePlayback: direct-provider`, و`mediaProxy: false`, و`transcoding: false`.
