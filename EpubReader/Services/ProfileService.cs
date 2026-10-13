using System;
using System.IO;
using System.Linq;
using System.Windows.Media;
using System.Windows.Media.Imaging;

namespace EpubReader.Services
{
    public static class ProfileService
    {
        public const int MaxNameLength = 24;

        private static string ProfileFolder
        {
            get
            {
                string folder = Path.Combine(
                    Environment.GetFolderPath(Environment.SpecialFolder.ApplicationData),
                    "EpubReaderApp", "profile");
                if (!Directory.Exists(folder))
                {
                    Directory.CreateDirectory(folder);
                }
                return folder;
            }
        }

        // اگر اسم ذخیره نشده باشد، یعنی اولین اجراست
        public static bool HasProfile
        {
            get { return !string.IsNullOrWhiteSpace(UserName); }
        }

        public static string UserName
        {
            get { return AppSettings.Get("UserName", ""); }
        }

        // مسیر کامل عکس ذخیره‌شده؛ اگر عکسی نباشد یا فایل پاک شده باشد، null
        public static string AvatarPath
        {
            get
            {
                string file = AppSettings.Get("AvatarFile", "");
                if (string.IsNullOrEmpty(file)) return null;
                string full = Path.Combine(ProfileFolder, file);
                return File.Exists(full) ? full : null;
            }
        }

        // اسم را تمیز می‌کند: فاصله‌های اضافه، خط جدید و طول بیش از حد
        public static string CleanName(string name)
        {
            if (name == null) return "";
            name = name.Replace("\r", " ").Replace("\n", " ").Trim();
            if (name.Length > MaxNameLength)
            {
                name = name.Substring(0, MaxNameLength).Trim();
            }
            return name;
        }

        public static bool SaveName(string name)
        {
            name = CleanName(name);
            if (name.Length == 0) return false;
            AppSettings.Set("UserName", name);
            return true;
        }

        // عکس انتخاب‌شده را داخل پوشه‌ی برنامه کپی می‌کند تا با پاک‌شدن فایل اصلی گم نشود
        public static bool SaveAvatar(string sourcePath)
        {
            try
            {
                if (string.IsNullOrEmpty(sourcePath) || !File.Exists(sourcePath)) return false;

                string ext = Path.GetExtension(sourcePath).ToLowerInvariant();
                string[] allowed = { ".jpg", ".jpeg", ".png", ".bmp", ".gif", ".webp" };
                if (!allowed.Contains(ext)) return false;

                string newFile = "avatar_" + Guid.NewGuid().ToString("N") + ext;
                File.Copy(sourcePath, Path.Combine(ProfileFolder, newFile), true);

                DeleteOldAvatar();
                AppSettings.Set("AvatarFile", newFile);
                return true;
            }
            catch
            {
                return false;
            }
        }

        // عکس را برای نمایش آماده می‌کند: چرخش درست (EXIF گوشی)، کوچک‌کردن برای سرعت، و آزادماندن فایل.
        // اگر عکس باز نشد، null برمی‌گرداند.
        public static BitmapSource LoadAvatarImage(string path)
        {
            try
            {
                BitmapFrame frame;
                int orientation = 1;
                using (FileStream stream = new FileStream(path, FileMode.Open, FileAccess.Read, FileShare.ReadWrite))
                {
                    BitmapDecoder decoder = BitmapDecoder.Create(stream, BitmapCreateOptions.PreservePixelFormat, BitmapCacheOption.OnLoad);
                    frame = decoder.Frames[0];
                    try
                    {
                        BitmapMetadata meta = frame.Metadata as BitmapMetadata;
                        if (meta != null)
                        {
                            object o = meta.GetQuery("/app1/ifd/{ushort=274}");
                            if (o != null) orientation = Convert.ToInt32(o);
                        }
                    }
                    catch { }
                }

                BitmapSource result = frame;

                double minSide = Math.Min(result.PixelWidth, result.PixelHeight);
                const double target = 256.0;
                if (minSide > target)
                {
                    double k = target / minSide;
                    result = new TransformedBitmap(result, new ScaleTransform(k, k));
                }

                int angle = 0;
                if (orientation == 3) angle = 180;
                else if (orientation == 6) angle = 90;
                else if (orientation == 8) angle = 270;
                if (angle != 0)
                {
                    result = new TransformedBitmap(result, new RotateTransform(angle));
                }

                if (result.CanFreeze) result.Freeze();
                return result;
            }
            catch
            {
                return null;
            }
        }

        public static void ClearAvatar()
        {
            DeleteOldAvatar();
            AppSettings.Set("AvatarFile", "");
        }

        private static void DeleteOldAvatar()
        {
            try
            {
                string old = AppSettings.Get("AvatarFile", "");
                if (string.IsNullOrEmpty(old)) return;
                string full = Path.Combine(ProfileFolder, old);
                if (File.Exists(full)) File.Delete(full);
            }
            catch { }
        }
    }
}