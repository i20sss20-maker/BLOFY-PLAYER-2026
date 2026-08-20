# BLOFY PLAYER Native — تسليم 2026.08.21.5

هذه الحزمة تستبدل نسخة WebView بتطبيق Android أصلي للهاتف والتابلت وAndroid TV والريسيفر.

## الموجود في التطبيق

- واجهات Android أصلية بالكامل: دخول، تفعيل وQR، قراءة الباقة، الرئيسية، التصنيفات، البحث، الأفلام، المسلسلات، المواسم، الحلقات، المفضلة، السجل والإعدادات.
- رقم جهاز ثابت من حروف وأرقام، ورمز تفعيل رقمي من 8 خانات.
- إدخال Xtream Codes أو M3U/M3U8 من التطبيق، أو إرسال البيانات من صفحة QR إلى الجهاز بصورة مشفّرة.
- نسبة قراءة حقيقية من 0 إلى 100، وتحليل نوع الخادم وامتدادات التشغيل، وحفظ القوائم في SQLite لفتح أسرع لاحقًا.
- Media3 مباشر من الجهاز إلى مزود المستخدم، مع TS وHLS وDASH وMP4/MKV/WebM، وحفظ موضع المشاهدة وخروج فوري بزر الرجوع.

## الرفع من Codespaces

ضع ملف ZIP في جذر المستودع، ثم نفّذ:

```bash
cd /workspaces/BLOFY-PLAYER-2026
git pull --rebase origin main
unzip -o BLOFY-PLAYER-NATIVE-2026.08.21.5.zip
git add BLOFY-ANDROID-2026 BLOFY-WEB-2026 codemagic.yaml BLOFY-NATIVE-DELIVERY-AR.md
git commit -m "Build BLOFY PLAYER native Android app"
git push origin main
```

## بعد الرفع

1. Railway ينشر تلقائيًا من `BLOFY-WEB-2026/web-test`.
2. تأكد أن `/api/health` يعيد `version: 2026.08.21.5` و`nativePlayback: direct`.
3. في Codemagic اختر `main` ثم `BLOFY PLAYER Android Test APK` واضغط Start new build.
4. حمّل `app-debug.apk` من Artifacts وثبّته بعد حذف النسخة القديمة حتى لا تبقى بيانات WebView السابقة.

## اختبار القبول

- لا يظهر موقع أو شريط متصفح داخل APK.
- يظهر رقم الجهاز والـQR، ويقبل رمز تفعيل رقمي فقط.
- بعد إدخال الباقة تظهر نسبة القراءة ثم الصفحة الرئيسية الأصلية.
- القناة تفتح في Media3، وزر الرجوع يخرج فورًا.
- الفيلم يعرض التفاصيل ويستكمل من آخر موضع، والمسلسل يعرض المواسم والحلقات.

> BLOFY PLAYER مشغل فقط ولا يوفّر محتوى أو اشتراكات. استخدم مصدرًا تملك حق الوصول إليه.
