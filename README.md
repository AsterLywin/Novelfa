<p align="center">
  <img src="EpubReader/Assets/AppLogo.png" width="128" alt="Novelfa Logo"/>
</p>

<h1 align="center">ناول‌فا | Novelfa</h1>

<p align="center">
  <b>کتاب‌خوان EPUB فارسی با پشتیبانی کامل از راست‌به‌چپ — برای ویندوز و اندروید</b><br/>
  <i>A Persian (RTL) EPUB reader for Windows &amp; Android — two native apps, one repository.</i>
</p>

<p align="center">
  <img src="https://img.shields.io/badge/Platform-Windows%20%7C%20Android-4C8DFF" alt="Platform"/>
  <img src="https://img.shields.io/badge/Android-Kotlin%202.0.20%20%7C%20Jetpack%20Compose-7F52FF?logo=kotlin&logoColor=white" alt="Android stack"/>
  <img src="https://img.shields.io/badge/Windows-WPF%20%7C%20.NET%20Framework%204.7.2-512BD4?logo=dotnet&logoColor=white" alt="Windows stack"/>
  <img src="https://img.shields.io/badge/Android-Min%20SDK%2029%20%7C%20Target%2035-3DDC84?logo=android&logoColor=white" alt="Android SDK"/>
  <img src="https://img.shields.io/badge/Version-1.0.1-F48C06" alt="Version"/>
  <img src="https://img.shields.io/badge/UI-FA%20%7C%20EN-2EA043" alt="UI languages"/>
</p>

---

## 📖 درباره‌ی پروژه

**ناول‌فا** یک کتاب‌خوان EPUB است که از ابتدا برای متن فارسی و چیدمان راست‌به‌چپ طراحی شده. این ریپو سورس کامل پروژه است و هر دو نسخه از همین‌جا توسعه داده می‌شوند:

| نسخه | مسیر | فناوری |
|---|---|---|
| 🖥️ **دسکتاپ ویندوز** | [`EpubReader/`](EpubReader/) | C# · WPF · ‎.NET Framework 4.7.2 |
| 📱 **اندروید** | [`android/`](android/) | Kotlin 2.0.20 · Jetpack Compose · Material 3 |

نسخه‌ی ویندوز **پیاده‌سازی مرجع** پروژه است: رفتار، ظاهر و جزئیات تجربه‌ی مطالعه اول آن‌جا شکل می‌گیرد و نسخه‌ی اندروید با همان مشخصات، به‌صورت بومی برای موبایل بازسازی می‌شود — از تم‌ها و سیستم رتبه‌ها تا ریزه‌کاری‌های حالت مطالعه‌ی صفحه‌ای.

---

## ✨ امکانات

### 📚 مطالعه

- رندر کامل EPUB با جهت و چیدمان خودکار بر اساس زبان کتاب (راست‌به‌چپ / چپ‌به‌راست)
- دو حالت مطالعه: **عمودی** (اسکرول پیوسته یا تک‌بخشی) و **افقیِ ورق‌زدنی** (صفحه‌به‌صفحه، هم‌رفتار با حالت تک‌صفحه‌ای نسخه‌ی ویندوز)
- تناسب خودکار تصاویر با صفحه در حالت ورق‌زدنی — بدون صفحه‌ی خالی یا تصویر بریده
- تنظیم جدا برای هر کتاب: فونت، اندازه‌ی متن (دکمه‌های A+/A− و کادر عددی)، فاصله‌ی خطوط و حاشیه‌ها
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

### 💾 پشتیبان‌گیری و بازیابی — `.novelfabak`

- خروجی و بازیابی کامل پروفایل در یک فایل: فونت‌های کاربر، آواتار، نام پروفایل، بالاترین رتبه، تم‌های مخفی و تعداد کتاب‌های تمام‌شده
- اعتبارسنجی کامل **قبل** از هر تغییری: هش SHA-256 برای تک‌تک فایل‌ها + چک‌سام یکپارچگی کل بسته — فایل ناقص یا دست‌کاری‌شده هیچ اثری روی برنامه نمی‌گذارد
- قوانین ادغام مشخص: کتاب‌های تمام‌شده با موجودی فعلی **جمع** می‌شوند، رتبه فقط صعود می‌کند، تم‌های مخفی اجتماع می‌گیرند و فونت‌های تکراری (بر اساس هش) دوباره اضافه نمی‌شوند
- بعد از بازیابی، یک گزارش شفاف نمایش داده می‌شود: تغییر رتبه، مجموع جدید کتاب‌ها و فونت‌های اضافه‌شده

---

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
└── README.md
```

---

## 🛠️ پشته‌ی فناوری

|  | 🖥️ ویندوز | 📱 اندروید |
|---|---|---|
| زبان | C# | Kotlin 2.0.20 |
| رابط کاربری | WPF (XAML) | Jetpack Compose · Material 3 |
| فریم‌ورک | ‎.NET Framework 4.7.2 | Android SDK 35 (حداقل 29) |
| رندر کتاب | WebView2 | Android WebView |
| ذخیره‌سازی | فایل‌های محلی کاربر | Room + DataStore |
| بیلد | Visual Studio | Gradle (AGP 8.5.2) |

---

## 🚀 بیلد و اجرا

**ویندوز**

فایل [`EpubReader.sln`](EpubReader.sln) را در Visual Studio باز کنید و پروژه‌ی `EpubReader` را Build/Run کنید.

**اندروید**

داخل پوشه‌ی [`android/`](android/) فایل `task.cmd` را اجرا کنید — بیلد Debug یا Release را انجام می‌دهد، خروجی (APK/AAB) را بررسی می‌کند و نتیجه را رنگی گزارش می‌دهد. باز کردن پوشه در Android Studio و اجرای `assembleDebug` هم ممکن است.

- پکیج: `com.novelfa.reader` · نسخه:‌ 1.0.1 · `minSdk 29` · `targetSdk 35`

---

## 🌍 English Summary

**Novelfa** is a Persian EPUB reader with first-class RTL support, developed as two native apps in a single repository:

- **Windows** — a WPF desktop app (`.NET Framework 4.7.2`) in [`EpubReader/`](EpubReader/); the reference implementation.
- **Android** — a native Kotlin / Jetpack Compose app (`minSdk 29`, `targetSdk 35`) in [`android/`](android/), built in parity with the desktop app.

Highlights: per-book reading settings, vertical & paged reading modes, collections, 7 day/night character themes with animated particles and emblem artwork, secret unlockable themes, a rank system based on finished books, PIN lock, custom fonts, full FA/EN bilingual UI, and a validated backup format (`.novelfabak`) that carries fonts, avatar, rank and secret themes between devices.

---

<p align="center">
  بر پایه‌ی <a href="https://github.com/ali1234536/NovelFaReader">NovelFaReader</a> اثر <b>ali1234536</b> — با تشکر از پروژه‌ی اصلی 🌹<br/>
  توسعه و نگهداری: <a href="https://github.com/AsterLywin"><b>AsterLywin</b></a>
</p>
