<p align="center">
  <img src="EpubReader/Assets/AppLogo.png" width="140" alt="Novelfa Logo"/>
</p>

<h1 align="center">ناول‌فا | Novelfa</h1>

<p align="center">
  <b>کتاب‌خوان EPUB فارسی با پشتیبانی کامل از راست‌به‌چپ — برای ویندوز و اندروید</b><br/>
  <i>A Persian EPUB reader — Ali's original Windows app, and the Android version developed in this repository.</i>
</p>

<p align="center">
  <img src="https://img.shields.io/badge/Platform-Windows%20%7C%20Android-4C8DFF" alt="Platform"/>
  <img src="https://img.shields.io/badge/Android-Kotlin%202.0.20%20%7C%20Jetpack%20Compose-7F52FF?logo=kotlin&logoColor=white" alt="Android stack"/>
  <img src="https://img.shields.io/badge/Windows-WPF%20%7C%20.NET%20Framework%204.7.2-512BD4?logo=dotnet&logoColor=white" alt="Windows stack"/>
  <img src="https://img.shields.io/badge/Android-Min%20SDK%2029%20%7C%20Target%2035-3DDC84?logo=android&logoColor=white" alt="Android SDK"/>
  <img src="https://img.shields.io/badge/Status-In%20Development-F48C06" alt="Status"/>
  <img src="https://img.shields.io/badge/UI-FA%20%7C%20EN-2EA043" alt="UI languages"/>
</p>

<p align="center">
  <a href="#features">✨ امکانات</a> ·
  <a href="#backup">💾 پشتیبان‌گیری</a> ·
  <a href="#structure">🗂️ ساختار پروژه</a> ·
  <a href="#build">🚀 بیلد و اجرا</a> ·
  <a href="#english">🌍 English</a>
</p>

---

<a id="about"></a>

## 📖 درباره‌ی پروژه

**ناول‌فا** یک کتاب‌خوان EPUB فارسی است؛ کتابخانه‌ای شخصی برای رمان‌ها و کتاب‌های الکترونیک که مطالعه را از باز کردن یک فایل ساده فراتر می‌برد: تم‌های کاراکتری روز و شب، کالکشن‌بندی کتاب‌ها، سیستم رتبه بر اساس کتاب‌های تمام‌شده، قفل خصوصی برای کتاب‌های شخصی و پشتیبان‌گیری قابل‌حمل — همه دور یک هدف جمع شده‌اند: مطالعه‌ی راحت و لذت‌بخش کتاب فارسی، با پشتیبانی کامل از متن و چیدمان راست‌به‌چپ.

### این ریپو شامل چیست؟

این ریپو دو بخش دارد که هر کدام صاحب مشخص خودش را دارند:

| بخش | مسیر | توسعه‌دهنده | توضیح |
|---|---|---|---|
| 🖥️ **نسخه‌ی ویندوز** | [`EpubReader/`](EpubReader/) | **علی (ali1234536)** | اپ دسکتاپ WPF و **پروژه‌ی اصلی**؛ ناول‌فا از همین‌جا شروع شده. در این ریپو به‌عنوان پیاده‌سازی مرجع نگه‌داری می‌شود و توسعه‌اش بر عهده‌ی صاحب اصلی پروژه است. |
| 📱 **نسخه‌ی اندروید** | [`android/`](android/) | **Aster (AsterLywin)** | بازسازی بومی نسخه‌ی مرجع برای موبایل، با همان رفتار، ظاهر و جزئیات. **توسعه‌ی این نسخه در همین ریپو و بر عهده‌ی Aster است.** |

### 🔎 در یک نگاه

| | |
|---|---|
| 📚 **فرمت کتاب** | EPUB |
| 🖥️📱 **پلتفرم‌ها** | ویندوز (WPF) · اندروید (Compose) |
| 🌐 **زبان رابط کاربری** | فارسی (راست‌به‌چپ) · انگلیسی (چپ‌به‌راست) |
| 💾 **فایل پشتیبان** | ‎`.novelfabak` — قابل انتقال بین دستگاه‌ها |
| 🚧 **وضعیت** | در حال توسعه — هنوز انتشار رسمی ندارد |

### فهرست

- [✨ امکانات](#features)
- [💾 پشتیبان‌گیری و بازیابی](#backup)
- [🗂️ ساختار پروژه](#structure)
- [🛠️ پشته‌ی فناوری](#tech)
- [🚀 بیلد و اجرا](#build)
- [🌍 English Summary](#english)

---

<a id="features"></a>

## ✨ امکانات

### 📚 مطالعه

- رندر کامل EPUB با جهت و چیدمان خودکار بر اساس زبان کتاب (راست‌به‌چپ / چپ‌به‌راست)
- دو حالت مطالعه: **عمودی** (اسکرول پیوسته یا تک‌بخشی) و **افقیِ ورق‌زدنی** (صفحه‌به‌صفحه، هم‌رفتار با حالت تک‌صفحه‌ای نسخه‌ی ویندوز)
- تناسب خودکار تصاویر با صفحه در حالت ورق‌زدنی — بدون صفحه‌ی خالی یا تصویر بریده
- تنظیم جدا برای هر کتاب: فونت، اندازه‌ی متن (دکمه‌های ‎A+/A−‎ و کادر عددی)، فاصله‌ی خطوط و حاشیه‌ها
- ۶ تم مطالعه‌ی روشن و تیره + تم مطالعه‌ی هماهنگ با تم کاراکتری فعال
- ادامه‌ی مطالعه دقیقاً از آخرین موقعیت، با نوار پیشرفت فصل‌به‌فصل

### 🗂️ کتابخانه و کالکشن‌ها

- جستجو، مرتب‌سازی و تب‌های همه‌ی کتاب‌ها / در حال مطالعه / تمام‌شده / علاقه‌مندی‌ها / اخیراً بازشده
- ایمپورت تکی و گروهی EPUB، با دیالوگ تأیید بازنویسی برای کتاب‌های تکراری
- باز کردن مستقیم فایل EPUB از فایل‌منیجر اندروید و ایمپورت خودکار
- انتخاب چندتایی و حذف گروهی کتاب‌ها
- کالکشن‌های شخصی: ساخت، تغییر نام، سنجاق‌کردن، آمار هر مجموعه و «ادامه‌ی مطالعه» در هر کالکشن
- کارت «ادامه‌ی مطالعه» و آمار زنده‌ی کتابخانه در صفحه‌ی اصلی

### 🎨 تم و شخصی‌سازی

- ۷ تم کاراکتری روز/شب با پالت ۱۶ رنگی اختصاصی برای هر شخصیت
- واترمارک نشان (Emblem)، هاله‌ی نور پس‌زمینه (Aura) و ذرات متحرک موضوعی — پروانه، گلبرگ، برف و…
- تم «تسوگی»: دوخت‌های تزئینی روی کارت‌ها و جلدها و افکت پارگی کنار کتابِ در حال مطالعه
- افکت گازگرفتگی (Bite) روی جلد کتاب‌های تمام‌شده
- تم‌های مخفی قابل فعال‌سازی با کد — `BEAKO` · `EMT` · `TEA` — و تم‌هایی که با رتبه باز می‌شوند
- رنگ تأکیدی (Accent) با پریست‌های آماده و انتخاب‌گر آزاد HSV
- فونت‌های فارسی داخلی (وزیرمتن، کودک و…) + افزودن فونت دلخواه با پیش‌نمایش و جستجو
- جهت و زبان رابط کاربری مستقل از کتاب: فارسی (راست‌به‌چپ) یا انگلیسی (چپ‌به‌راست)

### 🔐 حریم خصوصی و پیشرفت

- قفل PIN برای ورود به برنامه و کتاب‌های خصوصی — هش امن PBKDF2 و قفل موقت پس از تلاش‌های ناموفق پیاپی
- سیستم رتبه بر اساس تعداد کتاب‌های تمام‌شده، یکسان در هر دو نسخه:

| رتبه | نام | Name | 📕 کتاب تمام‌شده |
|:---:|---|---|:---:|
| ۰ | تازه‌وارد | Newcomer | ۰ |
| ۱ | کتاب‌باز | Book Lover | ۱ |
| ۲ | کتاب‌خور | Bookworm | ۳ |
| ۳ | کتابدار | Librarian | ۱۰ |
| ۴ | افسانه‌ی کتابخانه | Library Legend | ۲۵ |
| ۵ | دانای پلایادس | Sage of Pleiades | ۶۰ |
| ۶ | هیولای پلایادس | Monster of Pleiades | ۸۰ |

---

<a id="backup"></a>

## 💾 پشتیبان‌گیری و بازیابی — ‎`.novelfabak`

پروفایل شما در یک فایل قابل‌حمل خلاصه می‌شود تا روی دستگاه تازه — یا بعد از نصب دوباره‌ی برنامه — همه‌چیز سر جایش برگردد.

**داخل فایل پشتیبان چه چیزهایی هست؟**

فونت‌های کاربر، آواتار، نام پروفایل، بالاترین رتبه، تم‌های مخفی، تعداد کتاب‌های تمام‌شده و **رکورد پیشرفت مطالعه‌ی هر کتاب**.

**📍 ادامه‌ی مطالعه، حتی بعد از نصب دوباره** — برای هر کتاب، موقعیت فعلی مطالعه (با هویت دقیق خود فایل EPUB و شناسه‌ی داخلی کتاب) داخل بکاپ ذخیره می‌شود. کافی است بعد از بازیابی، کتاب‌ها را دوباره ایمپورت کنید: هر کتاب دقیقاً از همان‌جایی باز می‌شود که رهایش کرده بودید — وضعیت تمام‌شده، علاقه‌مندی و کالکشنش هم برمی‌گردد. حتی اگر کتابی را فقط از کتابخانه حذف و دوباره ایمپورت کنید، جایش حفظ می‌شود. کتاب‌های قفسه‌ی خصوصی در رکوردها نمی‌آیند، چون فایل پشتیبان رمزنگاری‌شده نیست.

**امنیت و اعتبارسنجی**

- اعتبارسنجی کامل **قبل** از هر تغییری انجام می‌شود: هش SHA-256 برای تک‌تک فایل‌ها + چک‌سام یکپارچگی کل بسته
- فایل ناقص یا دست‌کاری‌شده رد می‌شود و هیچ اثری روی برنامه نمی‌گذارد

**قوانین ادغام هنگام بازیابی**

| داده | قانون ادغام |
|---|---|
| 📕 کتاب‌های تمام‌شده | با موجودی فعلی **جمع** می‌شوند |
| 🏅 رتبه | فقط **صعود** می‌کند (بیشینه‌ی دو طرف) |
| 🔓 تم‌های مخفی | **اجتماع** می‌گیرند |
| 🔤 فونت‌ها | تکراری‌ها بر اساس هش دوباره اضافه نمی‌شوند |
| 📍 رکورد مطالعه‌ی هر کتاب | پیشرفت **بیشتر** برنده است؛ پیشرفت هیچ‌وقت عقب نمی‌رود |

بعد از بازیابی، یک گزارش شفاف نمایش داده می‌شود: تغییر رتبه، مجموع جدید کتاب‌ها و فونت‌های اضافه‌شده.

---

<a id="structure"></a>

## 🗂️ ساختار پروژه

```
Novelfa/
├── EpubReader.sln               # سولوشن ویندوز
├── EpubReader/                  # نسخه‌ی دسکتاپ (WPF) — پیاده‌سازی مرجع
│   ├── Assets/                  # لوگو و منابع گرافیکی
│   ├── Models/                  # مدل‌های داده
│   ├── Services/                # تم‌های کاراکتری، رتبه‌ها، فونت‌ها و…
│   └── *Window.xaml             # پنجره‌های کتابخانه، مطالعه، کالکشن، تنظیمات…
├── android/                     # نسخه‌ی اندروید (Kotlin + Compose)
│   ├── app/src/main/java/com/novelfa/reader/
│   │   ├── data/                # دیتابیس (Room)، تنظیمات (DataStore)، بکاپ
│   │   ├── library/             # کتابخانه و کالکشن‌ها
│   │   ├── parser/              # پارس EPUB
│   │   ├── reader/              # موتور مطالعه (WebView)
│   │   ├── settings/            # تنظیمات و پروفایل
│   │   └── theme/               # تم‌ها، رتبه‌ها، ذرات و آثار تزئینی
│   └── task.cmd                 # ابزار بیلد ویندوزی (Debug / Release + بررسی خروجی)
├── docs/                        # راهنمای انتشار و اسناد طرح‌های توسعه
└── README.md
```

---

<a id="tech"></a>

## 🛠️ پشته‌ی فناوری

|  | 🖥️ ویندوز | 📱 اندروید |
|---|---|---|
| **زبان** | C# | Kotlin 2.0.20 |
| **رابط کاربری** | WPF (XAML) | Jetpack Compose · Material 3 |
| **فریم‌ورک** | ‎.NET Framework 4.7.2 | Android SDK 35 (حداقل 29) |
| **رندر کتاب** | WebView2 | Android WebView |
| **ذخیره‌سازی** | فایل‌های محلی کاربر | Room + DataStore |
| **بیلد** | Visual Studio | Gradle (AGP 8.5.2) |

---

<a id="build"></a>

## 🚀 بیلد و اجرا

### 🖥️ ویندوز

فایل [`EpubReader.sln`](EpubReader.sln) را در Visual Studio باز کنید و پروژه‌ی `EpubReader` را Build/Run کنید.

### 📱 اندروید

داخل پوشه‌ی [`android/`](android/) فایل `task.cmd` را اجرا کنید — بیلد Debug یا Release را انجام می‌دهد، خروجی (APK/AAB) را بررسی می‌کند و نتیجه را رنگی گزارش می‌دهد. باز کردن پوشه در Android Studio و اجرای `assembleDebug` هم ممکن است:

```bash
cd android
gradlew.bat assembleDebug
```

> 📦 پکیج: `com.novelfa.reader` · ‎`minSdk 29` · ‎`targetSdk 35`

---

<a id="english"></a>

## 🌍 English Summary

**Novelfa** is a Persian EPUB reader with first-class RTL support. This repository holds two related apps with two different owners:

- **Windows** — the original WPF desktop app (`.NET Framework 4.7.2`) in [`EpubReader/`](EpubReader/), created by **ali1234536** and kept here as the reference implementation.
- **Android** — a native Kotlin / Jetpack Compose app (`minSdk 29`, `targetSdk 35`) in [`android/`](android/), developed in this repository by **AsterLywin** in parity with the desktop reference.

The project is in active development; no official release has been published yet.

Highlights: per-book reading settings, vertical & paged reading modes, collections, 7 day/night character themes with animated particles and emblem artwork, secret unlockable themes, a rank system based on finished books, PIN lock, custom fonts, full FA/EN bilingual UI, and a validated backup format (`.novelfabak`) that carries fonts, avatar, rank, secret themes and per-book reading records between devices — re-import a book and it resumes exactly where you left off.

---

---

<a id="credits"></a>

## 🙏 اعتبار و تشکر

این پروژه بر پایه‌ی پروژه‌ی **[NovelFaReader](https://github.com/ali1234536/NovelFaReader)** اثر **[ali1234536](https://github.com/ali1234536)** ساخته شده است — کتاب‌خوان ویندوزی‌ای که ناول‌فا از دل آن متولد شد. از علی برای ساخت و انتشار پروژه‌ی اصلی صمیمانه تشکر می‌کنم؛ نسخه‌ی ویندوز داخل این ریپو همان اثر او و پیاده‌سازی مرجع پروژه است و بدون آن، این کار وجود نداشت. 🌹

**نسخه‌ی اندروید** — بازسازی بومی و کامل نسخه‌ی مرجع برای موبایل — و نگهداری این ریپو بر عهده‌ی **[AsterLywin](https://github.com/AsterLywin)** است.
