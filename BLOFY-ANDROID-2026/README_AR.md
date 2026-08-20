# BLOFY PLAYER Android 2026

الإصدار: `2026.08.20.4`

تطبيق Android للهاتف والتابلت وAndroid TV والريسيفر. واجهة الحساب والفئات تأتي من Railway داخل WebView، لكن الفيديو لا يعمل داخل WebView ولا يمر عبر Railway: عند اختيار المحتوى يحصل التطبيق على رابط قصير مشفّر وموقّع، ثم يعيد Railway توجيه **Media3 على الجهاز مباشرة إلى مصدر المستخدم**.

## مسار التشغيل النهائي

1. الواجهة تطلب `/api/native-link/...` باستخدام جلسة المستخدم المشفّرة.
2. Railway يعيد رابط `/api/native-play` مؤقتًا لا يكشف بيانات المصدر للواجهة.
3. `PlayerActivity` يفتح الرابط بواسطة Media3.
4. `/api/native-play` يتحقق من التوقيع ثم يعيد `302` إلى المصدر الأصلي فقط.
5. بيانات الفيديو تنتقل من المزود إلى الجهاز مباشرة؛ لا يوجد FFmpeg أو Proxy في مسار APK.

## المشاكل التي عولجت

- إزالة التحويل التلقائي إلى `compat=2` بعد 20 ثانية؛ هذا التحويل كان ينقل القناة إلى FFmpeg على Railway ويفشل مع المزود.
- رفع مهلة بدء المصدر إلى 60 ثانية، و90 ثانية عند إعادة الاتصال، حتى لا تُغلق قناة بطيئة قبل وصول أول إطار.
- تفعيل علمَي Media3 الرسميين `FLAG_DETECT_ACCESS_UNITS` و`FLAG_ALLOW_NON_IDR_KEYFRAMES` لبث MPEG‑TS الذي ينقصه AUD أو IDR.
- دعم MPEG‑TS وHLS وDASH وMP4/MKV/WebM والصوت، وترك الامتداد غير المعروف لـ Media3 كي يكتشفه بدل إجباره خطأً على HLS.
- تفعيل تحويلات HTTP/HTTPS المتقاطعة لأن بعض مزودي Xtream يعيدون من رابط HTTPS إلى بث HTTP.
- استخدام Decoder fallback، وBuffer مناسب للبث المباشر، وحفظ موضع الأفلام والحلقات، وأزرار ريموت ورجوع أصلية.
- قبول رابط تشغيل موقّع من نفس أصل Railway فقط؛ لم يعد التطبيق يقبل مسار `/api/play` القديم.
- تطبيع رابط Railway إلى الأصل فقط ومنع اسم مستخدم/كلمة مرور أو منفذ مختلف داخل رابط الجسر.

## البناء

المتطلبات: JDK 17، Android SDK 36، Gradle 8.13، وAGP 8.12.2.

```bash
cd BLOFY-ANDROID-2026
export BLOFY_BASE_URL="https://blofy-player-2026-production.up.railway.app"
./scripts/build-apk.sh
```

الناتج:

`app/build/outputs/apk/debug/app-debug.apk`

أو ادفع التحديث إلى GitHub؛ ملف `codemagic.yaml` يشغّل `lintDebug` و`testDebugUnitTest` ثم يبني APK تلقائيًا.

## اختبار القبول

- تأكد أن `/api/health` يعيد `version: 2026.08.20.4` و`nativePlayback: direct`.
- ابنِ APK جديدًا؛ تحديث موقع Railway وحده لا يغيّر كود Media3 المثبت سابقًا.
- اختبر قناة TS وقناة M3U8 وفيلمًا، ثم استخدم زر الرجوع للخروج من المشغل.
- في Deploy Logs يجب أن يظهر `native-open ... mode=direct`، ويجب ألا يظهر `ffmpeg-start` عند التشغيل من APK.

> التطبيق مشغل فقط ولا يضم محتوى أو اشتراكات. استخدم مصدرًا تملك حق الوصول إليه.
