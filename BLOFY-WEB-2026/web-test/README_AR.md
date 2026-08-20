# BLOFY PLAYER WEB 2026

نسخة ويب كاملة تعمل على الجوال والتابلت ومتصفحات التلفزيون، ومجهزة للنشر على Railway من المجلد:

`BLOFY-WEB-2026/web-test`

## الوظائف

- تسجيل Xtream Codes أو رابط M3U/M3U8.
- بث مباشر وأفلام ومسلسلات ومواسم وحلقات.
- EPG، بحث، مفضلة، سجل مشاهدة، وإعدادات تشغيل.
- تنقّل بالأسهم وOK/Enter وBack/Escape لأجهزة التلفزيون.
- HLS أصلي على Safari وhls.js على بقية المتصفحات.
- وسيط آمن لتجاوز CORS والمحتوى المختلط مع إخفاء بيانات الاشتراك.
- تحويل سريع للبث الخام إلى HLS عبر FFmpeg عند الحاجة.
- تجربة 7 أيام محفوظة على الخادم، صفحة QR وتفعيل مدمجة، وأكواد تفعيل تُنشأ بمسار إدارة محمي.
- PWA قابلة للإضافة للشاشة الرئيسية، ولا يتم تخزين بيانات البث في Service Worker.

## إعداد Railway

1. اجعل **Root Directory**: `BLOFY-WEB-2026/web-test`
2. سيستخدم Railway ملف `Dockerfile` تلقائيًا.
3. أضف متغير `SESSION_SECRET` بقيمة عشوائية طويلة وثابتة.
4. أضف `ADMIN_TOKEN` طويلًا وسريًا لإنشاء أكواد التفعيل.
5. أنشئ Railway Volume على `/data` واضبط `LICENSE_DB_PATH=/data/licenses.json`.
6. اترك `ACTIVATION_URL` فارغًا لاستخدام `/activate` المدمج، أو ضع رابط صفحة التفعيل النهائية.
7. عند توفر نظام تراخيص خارجي، ضع رابط API في `LICENSE_API_URL`.

إنشاء رمز تفعيل:

```bash
curl -X POST "https://YOUR-PROJECT.up.railway.app/api/admin/codes" \
  -H "Authorization: Bearer YOUR_ADMIN_TOKEN" \
  -H "Content-Type: application/json" \
  -d '{"days":365,"maxUses":1,"label":"عميل 001"}'
```

## التشغيل المحلي

```bash
npm ci
cp .env.example .env
npm start
```

ثم افتح `http://localhost:3000`.

> التطبيق مشغل فقط ولا يضم أي محتوى أو اشتراكات. المستخدم يضيف مصدره المصرح له.
