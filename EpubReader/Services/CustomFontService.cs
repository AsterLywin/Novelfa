using System;
using System.Collections.Generic;
using System.Globalization;
using System.IO;
using System.Reflection;
using System.Runtime.InteropServices;
using System.Text;
using System.Windows.Media;

namespace EpubReader.Services
{
    /// <summary>خطایی که مربوط به اسم انتخابی کاربره (خالی، طولانی یا تکراری). با این خطا کادر اسم دوباره باز می‌شه.</summary>
    public class FontNameException : ArgumentException
    {
        public FontNameException(string message) : base(message)
        {
        }
    }

    /// <summary>یک فونت شخصی که کاربر اضافه کرده.</summary>
    public class CustomFontInfo
    {
        /// <summary>اسمی که کاربر انتخاب کرده (فارسی یا انگلیسی).</summary>
        public string Name { get; set; }

        /// <summary>اسم فایل کپی‌شده داخل پوشه Fonts برنامه.</summary>
        public string FileName { get; set; }

        /// <summary>اسم داخلی خود فونت (چیزی که موتور نمایش متن کتاب می‌شناسه).</summary>
        public string FamilyName { get; set; }

        /// <summary>آیا فونت با موفقیت برای برنامه ثبت شده؟ (false یعنی فایل هست ولی ثبتش نشده)</summary>
        public bool IsAvailable { get; set; }

        /// <summary>مسیر کامل فایل کپی‌شده.</summary>
        public string FullPath
        {
            get { return Path.Combine(CustomFontService.FontsFolder, FileName); }
        }
    }

    /// <summary>
    /// فاز ۱ و ۳: ذخیره دائمی فونت‌های شخصی + ثبت اون‌ها برای استفاده داخل برنامه.
    /// فایل فونت داخل %AppData%\EpubReaderApp\Fonts کپی می‌شه و
    /// لیست توی fonts.txt همون پوشه ذخیره می‌شه (اسم دلخواه، اسم فایل، اسم داخلی فونت).
    /// </summary>
    public static class CustomFontService
    {
        private const int MaxNameLength = 60;
        private const uint FR_PRIVATE = 0x10;

        [DllImport("gdi32.dll", CharSet = CharSet.Unicode, EntryPoint = "AddFontResourceExW")]
        private static extern int AddFontResourceEx(string lpszFilename, uint fl, IntPtr pdv);

        [DllImport("gdi32.dll", CharSet = CharSet.Unicode, EntryPoint = "RemoveFontResourceExW")]
        private static extern bool RemoveFontResourceEx(string lpszFilename, uint fl, IntPtr pdv);

        private static List<CustomFontInfo> fonts = null;

        public static string FontsFolder
        {
            get
            {
                string folder = Path.Combine(
                    Environment.GetFolderPath(Environment.SpecialFolder.ApplicationData),
                    "EpubReaderApp",
                    "Fonts");
                if (!Directory.Exists(folder))
                {
                    Directory.CreateDirectory(folder);
                }
                return folder;
            }
        }

        private static string PreviewRoot
        {
            get
            {
                return Path.Combine(
                    Environment.GetFolderPath(Environment.SpecialFolder.ApplicationData),
                    "EpubReaderApp",
                    "FontPreview");
            }
        }

        /// <summary>
        /// برای نمایش اسم فونت‌ها توی لیست، ویندوز فایل فونت رو قفل می‌کنه.
        /// برای همین یک کپی جدا (توی پوشه FontPreview) استفاده می‌شه تا فایل اصلی توی Fonts آزاد بمونه و حذف بشه.
        /// </summary>
        public static string GetPreviewFolder(CustomFontInfo info)
        {
            string folder = Path.Combine(PreviewRoot, Path.GetFileNameWithoutExtension(info.FileName));
            try
            {
                if (!Directory.Exists(folder))
                {
                    Directory.CreateDirectory(folder);
                }
                string dest = Path.Combine(folder, info.FileName);
                if (!File.Exists(dest))
                {
                    File.Copy(info.FullPath, dest, false);
                }
            }
            catch
            {
            }
            return folder + "\\";
        }

        // کپی‌های نمایشی قدیمی موقع بالا اومدن برنامه پاک می‌شن (قبل از اینکه چیزی قفلشون کنه)
        private static void CleanPreviewRoot()
        {
            try
            {
                if (Directory.Exists(PreviewRoot))
                {
                    Directory.Delete(PreviewRoot, true);
                }
            }
            catch
            {
            }
        }

        private static string IndexPath
        {
            get { return Path.Combine(FontsFolder, "fonts.txt"); }
        }

        /// <summary>موقع بالا اومدن برنامه صدا زده بشه: فونت‌های ذخیره‌شده رو لود و ثبت می‌کنه.</summary>
        public static void Initialize()
        {
            EnsureLoaded();
        }

        /// <summary>آیا این فایل فرمت پشتیبانی‌شده (ttf / otf) داره؟</summary>
        public static bool IsSupportedFile(string path)
        {
            if (string.IsNullOrEmpty(path)) return false;
            string ext = Path.GetExtension(path);
            return string.Equals(ext, ".ttf", StringComparison.OrdinalIgnoreCase)
                || string.Equals(ext, ".otf", StringComparison.OrdinalIgnoreCase);
        }

        /// <summary>اسم رو تمیز می‌کنه: فاصله‌های اضافه، تب و خط جدید حذف می‌شن.</summary>
        public static string CleanName(string name)
        {
            if (name == null) return "";
            name = name.Replace('\t', ' ').Replace('\r', ' ').Replace('\n', ' ').Trim();
            while (name.Contains("  "))
            {
                name = name.Replace("  ", " ");
            }
            return name;
        }

        /// <summary>همه فونت‌های شخصی (یک کپی از لیست).</summary>
        public static List<CustomFontInfo> GetAll()
        {
            EnsureLoaded();
            return new List<CustomFontInfo>(fonts);
        }

        /// <summary>آیا فونت شخصی با این اسم قبلاً هست؟ (بدون حساسیت به حروف)</summary>
        public static bool NameExists(string name)
        {
            EnsureLoaded();
            string clean = CleanName(name);
            foreach (CustomFontInfo f in fonts)
            {
                if (string.Equals(f.Name, clean, StringComparison.OrdinalIgnoreCase))
                {
                    return true;
                }
            }
            return false;
        }

        /// <summary>
        /// فایل فونت رو کپی و با اسم دلخواه ذخیره و ثبت می‌کنه.
        /// اگه ورودی نامعتبر باشه ArgumentException می‌ندازه (پیامش فارسیه و قابل نمایش به کاربره).
        /// </summary>
        public static CustomFontInfo Add(string sourcePath, string displayName)
        {
            EnsureLoaded();

            if (!File.Exists(sourcePath))
            {
                throw new ArgumentException(Loc.T("فایل فونت پیدا نشد."));
            }
            if (!IsSupportedFile(sourcePath))
            {
                throw new ArgumentException(Loc.T("فقط فایل‌های ttf و otf پشتیبانی می‌شن."));
            }

            string name = CleanName(displayName);
            if (name.Length == 0)
            {
                throw new FontNameException(Loc.T("اسم فونت نمی‌تونه خالی باشه."));
            }
            if (name.Length > MaxNameLength)
            {
                throw new FontNameException(Loc.F("اسم فونت خیلی طولانیه (حداکثر {0} حرف).", MaxNameLength));
            }
            if (NameExists(name))
            {
                throw new FontNameException(Loc.T("فونتی با این اسم قبلاً اضافه شده."));
            }

            // اسم داخلی فونت رو از خود فایل می‌خونیم؛ اگه نشد یعنی فایل فونت سالم نیست
            string familyName = ReadFamilyName(sourcePath);
            if (string.IsNullOrEmpty(familyName))
            {
                throw new ArgumentException(Loc.T("این فایل یک فونت معتبر نیست."));
            }

            // اسم فایل رو یکتا می‌سازیم تا با اسم فارسی یا تکراری مشکلی پیش نیاد
            string fileName = Guid.NewGuid().ToString("N") + Path.GetExtension(sourcePath).ToLowerInvariant();
            string destPath = Path.Combine(FontsFolder, fileName);
            try
            {
                File.Copy(sourcePath, destPath, false);
            }
            catch (Exception)
            {
                throw new ArgumentException(Loc.T("کپی کردن فایل فونت انجام نشد."));
            }

            CustomFontInfo info = new CustomFontInfo();
            info.Name = name;
            info.FileName = fileName;
            info.FamilyName = familyName;
            fonts.Add(info);

            try
            {
                Save();
            }
            catch
            {
                // اگه ذخیره لیست شکست خورد، تغییر رو برمی‌گردونیم
                fonts.Remove(info);
                try { File.Delete(destPath); } catch { }
                throw new ArgumentException(Loc.T("ذخیره فونت انجام نشد."));
            }

            if (!RegisterFont(info))
            {
                // فایل فونت سالم به نظر نمی‌رسه یا ویندوز ثبتش نکرد؛ تغییر رو برمی‌گردونیم
                fonts.Remove(info);
                try { Save(); } catch { }
                try { File.Delete(destPath); } catch { }
                throw new ArgumentException(Loc.T("ویندوز این فونت رو نپذیرفت. فایل ممکنه خراب باشه."));
            }
            return info;
        }

        /// <summary>فونت شخصی رو از لیست و از دیسک حذف می‌کنه.</summary>
        public static bool Remove(string name)
        {
            EnsureLoaded();
            string clean = CleanName(name);

            CustomFontInfo target = null;
            foreach (CustomFontInfo f in fonts)
            {
                if (string.Equals(f.Name, clean, StringComparison.OrdinalIgnoreCase))
                {
                    target = f;
                    break;
                }
            }
            if (target == null) return false;

            UnregisterFont(target);
            fonts.Remove(target);
            Save();
            DeleteFontFile(target.FullPath);
            if (File.Exists(target.FullPath))
            {
                // هنوز قفله؛ توی پس‌زمینه چند ثانیه دیگه هم تلاش می‌کنیم (و اجرای بعدی برنامه هر جور شده پاکش می‌کنه)
                ScheduleDeleteRetry(target.FullPath);
            }
            return true;
        }

        // ---------- ثبت فونت برای برنامه ----------

        private static bool RegisterFont(CustomFontInfo info)
        {
            bool ok = false;
            try
            {
                // نتیجه صفر یعنی ثبت نشد
                ok = AddFontResourceEx(info.FullPath, FR_PRIVATE, IntPtr.Zero) > 0;
            }
            catch { }
            info.IsAvailable = ok;
            return ok;
        }

        private static void UnregisterFont(CustomFontInfo info)
        {
            try
            {
                // هر بار ثبت‌کردن یک شمارنده بالا می‌برد؛ تا وقتی ویندوز می‌پذیرد، آزاد می‌کنیم
                for (int i = 0; i < 10; i++)
                {
                    if (!RemoveFontResourceEx(info.FullPath, FR_PRIVATE, IntPtr.Zero)) break;
                }
            }
            catch { }
        }

        /// <summary>
        /// فایل فونت را پاک می‌کند. ممکن است ویندوز یا WPF هنوز فایل را نگه داشته باشند،
        /// پس چند بار با آزادسازی حافظه دوباره امتحان می‌کنیم.
        /// اگر باز هم قفل بود، چون دیگر توی fonts.txt نیست، اجرای بعدی برنامه پاکش می‌کند.
        /// </summary>
        private static void DeleteFontFile(string path)
        {
            for (int i = 0; i < 6; i++)
            {
                try
                {
                    if (!File.Exists(path)) return;
                    File.Delete(path);
                    return;
                }
                catch
                {
                    GC.Collect();
                    GC.WaitForPendingFinalizers();
                    System.Threading.Thread.Sleep(150);
                }
            }
        }

        private static void ScheduleDeleteRetry(string path)
        {
            System.Threading.Tasks.Task.Factory.StartNew(() =>
            {
                for (int i = 0; i < 30; i++)
                {
                    System.Threading.Thread.Sleep(1000);
                    try
                    {
                        if (!File.Exists(path)) return;
                        File.Delete(path);
                        return;
                    }
                    catch
                    {
                        if (i % 5 == 4)
                        {
                            GC.Collect();
                            GC.WaitForPendingFinalizers();
                        }
                    }
                }
            });
        }

        /// <summary>اسم داخلی فونت (همون چیزی که ویندوز به اسمش می‌شناسه) رو از فایل می‌خونه.</summary>
        public static string ReadFamilyName(string path)
        {
            try
            {
                GlyphTypeface gt = new GlyphTypeface(new Uri(Path.GetFullPath(path), UriKind.Absolute));
                string result = null;

                // اسمی که ویندوز (GDI) استفاده می‌کنه؛ اول انگلیسی
                IDictionary<CultureInfo, string> names = gt.Win32FamilyNames;
                if (names != null && names.Count > 0)
                {
                    foreach (KeyValuePair<CultureInfo, string> kv in names)
                    {
                        if (kv.Key.Name.StartsWith("en", StringComparison.OrdinalIgnoreCase))
                        {
                            result = kv.Value;
                            break;
                        }
                    }
                    if (result == null)
                    {
                        foreach (KeyValuePair<CultureInfo, string> kv in names)
                        {
                            result = kv.Value;
                            break;
                        }
                    }
                }
                return result;
            }
            catch
            {
                return null;
            }
        }

        // ---------- داخلی ----------

        private static void EnsureLoaded()
        {
            if (fonts != null) return;
            fonts = new List<CustomFontInfo>();
            CleanPreviewRoot();

            bool needSave = false;
            HashSet<string> referenced = new HashSet<string>(StringComparer.OrdinalIgnoreCase);
            try
            {
                if (!File.Exists(IndexPath))
                {
                    CleanupOrphanFiles(referenced);
                    return;
                }
                foreach (string line in File.ReadAllLines(IndexPath, Encoding.UTF8))
                {
                    string[] parts = line.Split('\t');
                    if (parts.Length >= 2 && parts[1].Length > 0)
                    {
                        referenced.Add(parts[1]);
                    }
                    if (parts.Length < 2 || parts[0].Length == 0) continue;

                    CustomFontInfo info = new CustomFontInfo();
                    info.Name = parts[0];
                    info.FileName = parts[1];
                    info.FamilyName = (parts.Length >= 3) ? parts[2] : "";

                    // اگه فایلش از بین رفته، ردش می‌کنیم
                    if (info.FileName.Length == 0 || !File.Exists(info.FullPath)) continue;

                    // فایل‌هایی که با نسخه قدیمی ذخیره شدن اسم داخلی ندارن؛ الان می‌خونیم
                    if (string.IsNullOrEmpty(info.FamilyName))
                    {
                        info.FamilyName = ReadFamilyName(info.FullPath);
                        needSave = true;
                    }
                    if (string.IsNullOrEmpty(info.FamilyName)) continue;

                    fonts.Add(info);
                    RegisterFont(info); // اگه ثبت نشه IsAvailable=false می‌مونه ولی از لیست ذخیره‌شده حذف نمی‌شه
                }
                if (needSave)
                {
                    Save();
                }
                CleanupOrphanFiles(referenced);
            }
            catch { }
        }

        /// <summary>فایل‌های فونتی که توی fonts.txt نیستن (مثلاً بعد از حذف ناقص) پاک می‌شن.</summary>
        private static void CleanupOrphanFiles(HashSet<string> referenced)
        {
            try
            {
                foreach (string file in Directory.GetFiles(FontsFolder))
                {
                    string ext = Path.GetExtension(file);
                    bool isFont = string.Equals(ext, ".ttf", StringComparison.OrdinalIgnoreCase)
                        || string.Equals(ext, ".otf", StringComparison.OrdinalIgnoreCase);
                    if (!isFont) continue;
                    if (referenced.Contains(Path.GetFileName(file))) continue;
                    try { File.Delete(file); } catch { }
                }
            }
            catch { }
        }

        private static void Save()
        {
            List<string> lines = new List<string>();
            foreach (CustomFontInfo f in fonts)
            {
                lines.Add(f.Name + "\t" + f.FileName + "\t" + f.FamilyName);
            }
            File.WriteAllLines(IndexPath, lines.ToArray(), Encoding.UTF8);
        }
    }

    /// <summary>یک فونت پیشنهادی (همراه برنامه).</summary>
    public class RecommendedFontInfo
    {
        /// <summary>اسمی که توی لیست نشون داده می‌شه.</summary>
        public string DisplayName { get; set; }

        /// <summary>اسم داخلی فونت (چیزی که موتور نمایش متن می‌شناسه).</summary>
        public string FamilyName { get; set; }

        /// <summary>پوشه‌ای که فایل فونت همراه برنامه توش استخراج شده؛ اگه فونت همراه برنامه نباشه null.</summary>
        public string PreviewFolder { get; set; }
    }

    /// <summary>
    /// فونت‌های پیشنهادی (بی کودک و وزیرمتن): فایل فونت‌ها داخل خود برنامه (پوشه RecommendedFonts) گذاشته می‌شه،
    /// موقع اجرا توی %AppData%\EpubReaderApp\RecommendedFonts استخراج و فقط برای همین برنامه ثبت می‌شن،
    /// پس سیستمی که این فونت‌ها رو نصب نداره هم می‌تونه ازشون استفاده کنه.
    /// </summary>
    public static class RecommendedFontService
    {
        private const uint FR_PRIVATE = 0x10;
        private const string ResourcePrefix = "EpubReader.RecommendedFonts.";

        [DllImport("gdi32.dll", CharSet = CharSet.Unicode, EntryPoint = "AddFontResourceExW")]
        private static extern int AddFontResourceEx(string lpszFilename, uint fl, IntPtr pdv);

        private class CatalogEntry
        {
            public string Display;
            public string FileKey;
            public string DefaultFamily;
        }

        private static readonly CatalogEntry[] catalog = new CatalogEntry[]
        {
            new CatalogEntry { Display = "بی کودک (B Koodak)", FileKey = "koodak", DefaultFamily = "B Koodak" },
            new CatalogEntry { Display = "وزیرمتن (Vazirmatn)", FileKey = "vazirmatn", DefaultFamily = "Vazirmatn" }
        };

        private static List<RecommendedFontInfo> items = null;

        private static string Folder
        {
            get
            {
                return Path.Combine(
                    Environment.GetFolderPath(Environment.SpecialFolder.ApplicationData),
                    "EpubReaderApp",
                    "RecommendedFonts");
            }
        }

        public static void Initialize()
        {
            Load();
        }

        public static List<RecommendedFontInfo> GetAll()
        {
            Load();
            return new List<RecommendedFontInfo>(items);
        }

        private static void Load()
        {
            if (items != null)
            {
                return;
            }
            items = new List<RecommendedFontInfo>();
            Dictionary<string, string> bundled = new Dictionary<string, string>();
            try
            {
                Directory.CreateDirectory(Folder);
                Assembly asm = Assembly.GetExecutingAssembly();
                foreach (string res in asm.GetManifestResourceNames())
                {
                    if (!res.StartsWith(ResourcePrefix, StringComparison.OrdinalIgnoreCase))
                    {
                        continue;
                    }
                    string fileName = res.Substring(ResourcePrefix.Length);
                    string ext = Path.GetExtension(fileName);
                    if (!string.Equals(ext, ".ttf", StringComparison.OrdinalIgnoreCase) && !string.Equals(ext, ".otf", StringComparison.OrdinalIgnoreCase))
                    {
                        continue;
                    }
                    string dest = Path.Combine(Folder, fileName);
                    try
                    {
                        using (Stream s = asm.GetManifestResourceStream(res))
                        {
                            if (s == null)
                            {
                                continue;
                            }
                            if (!File.Exists(dest) || new FileInfo(dest).Length != s.Length)
                            {
                                using (FileStream fs = new FileStream(dest, FileMode.Create, FileAccess.Write, FileShare.Read))
                                {
                                    s.CopyTo(fs);
                                }
                            }
                        }
                    }
                    catch
                    {
                        // اگه فایل از قبل هست (مثلاً یه نسخه‌ی دیگه‌ی برنامه بازه) همون استفاده می‌شه
                    }
                    if (!File.Exists(dest))
                    {
                        continue;
                    }
                    try
                    {
                        AddFontResourceEx(dest, FR_PRIVATE, IntPtr.Zero);
                    }
                    catch
                    {
                    }
                    string lower = fileName.ToLowerInvariant();
                    foreach (CatalogEntry entry in catalog)
                    {
                        if (lower.IndexOf(entry.FileKey, StringComparison.Ordinal) >= 0)
                        {
                            // اگه چند وزن (Regular, Bold, ...) باشه، اسم خانواده رو از نسخه‌ی Regular می‌گیریم
                            if (!bundled.ContainsKey(entry.FileKey) || lower.IndexOf("regular", StringComparison.Ordinal) >= 0)
                            {
                                bundled[entry.FileKey] = dest;
                            }
                        }
                    }
                }
            }
            catch
            {
            }

            foreach (CatalogEntry entry in catalog)
            {
                RecommendedFontInfo info = new RecommendedFontInfo();
                info.DisplayName = entry.Display;
                info.FamilyName = entry.DefaultFamily;
                string path;
                if (bundled.TryGetValue(entry.FileKey, out path))
                {
                    string family = CustomFontService.ReadFamilyName(path);
                    if (!string.IsNullOrEmpty(family))
                    {
                        info.FamilyName = family;
                    }
                    info.PreviewFolder = Folder + "\\";
                }
                items.Add(info);
            }
        }
    }
}
