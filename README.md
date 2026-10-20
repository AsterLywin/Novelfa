# ناول‌فا | Novelfa

کتاب‌خوان EPUB فارسی با پشتیبانی کامل از متن راست‌به‌چپ — در دو نسخه‌ی **ویندوز** و **اندروید**.

این ریپو سورس کامل پروژه‌ی ناول‌فاست و هر دو اپ از همین‌جا توسعه داده می‌شن:

| بخش | توضیح |
|---|---|
| [`EpubReader/`](EpubReader/) | نسخه‌ی دسکتاپ ویندوز — WPF و C#‎/.NET |
| [`android/`](android/) | نسخه‌ی بومی اندروید — Kotlin و Jetpack Compose |

نسخه‌ی ویندوز، پیاده‌سازی مرجع پروژه است و نسخه‌ی اندروید با همان رفتار و ظاهر توسعه داده می‌شود. این پروژه بر پایه‌ی [NovelFaReader](https://github.com/ali1234536/NovelFaReader) ساخته شده است.

## امکانات

- خواندن کتاب‌های EPUB با رندر کامل فارسی و چیدمان راست‌به‌چپ
- کتابخانه با جستجو، مرتب‌سازی، انتخاب چندتایی و ایمپورت گروهی
- کالکشن‌ها (مجموعه‌های شخصی کتاب) با آمار و «ادامه‌ی مطالعه»
- تم‌های کاراکتری روز/شب با پالت رنگ اختصاصی و ذرات متحرک (پروانه، گلبرگ، برف و...)
- رنگ تأکیدی (Accent) قابل تنظیم + تم‌های مخفی با کد
- حالت مطالعه‌ی افقی (ورق‌زدنی صفحه‌ای) و عمودی (اسکرول پیوسته یا تک‌بخشی)
- تنظیم جدا برای هر کتاب: فونت، اندازه‌ی متن، فاصله‌ی خطوط و حاشیه
- فونت‌های فارسی داخلی (وزیرمتن، کودک و...) + امکان افزودن فونت دلخواه
- قفل PIN برای کتابخانه و سیستم رتبه‌بندی بر اساس پیشرفت مطالعه
- رابط کاربری دوزبانه (فارسی/انگلیسی)

## بیلد

**ویندوز:** فایل `EpubReader.sln` را در Visual Studio باز کنید.

**اندروید:** داخل پوشه‌ی [`android/`](android/) فایل `task.cmd` را اجرا کنید (بیلد Debug یا Release).

- Android: ‏`minSdk 29` ‏، ‏`targetSdk 35` ‏، پکیج `com.novelfa.reader`

---

**Novelfa** is a Persian (RTL) EPUB reader with two native apps in one repo: a WPF desktop app for Windows (`EpubReader/`) and a Kotlin/Jetpack Compose app for Android (`android/`). The Windows app is the reference implementation; the Android app is developed in parity with it. Based on [ali1234536/NovelFaReader](https://github.com/ali1234536/NovelFaReader).
