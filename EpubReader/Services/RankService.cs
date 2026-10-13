using System;

namespace EpubReader.Services
{
    public sealed class RankInfo
    {
        public readonly int Index;
        private readonly string name;
        public readonly int MinBooks;

        public RankInfo(int index, string name, int minBooks)
        {
            Index = index;
            this.name = name;
            MinBooks = minBooks;
        }

        // اسم رتبه به زبان انتخاب‌شده
        public string Name
        {
            get { return Loc.T(name); }
        }
    }

    // رتبه‌ی کتابخوان بر اساس تعداد کتاب‌های تمام‌شده (کتاب‌های خصوصی حساب نمی‌شن).
    // هر رتبه یک تم شخصیت رو باز می‌کنه. بالاترین رتبه‌ای که تا حالا گرفتی ذخیره می‌شه،
    // پس اگه بعداً کتابی پاک کنی تم‌های بازشده دوباره قفل نمی‌شن.
    public static class RankService
    {
        // برای تغییر چالش‌ها، فقط عددهای MinBooks رو عوض کن
        public static readonly RankInfo[] Ranks = new RankInfo[]
        {
            new RankInfo(0, "تازه‌وارد", 0),
            new RankInfo(1, "کتاب‌باز", 1),
            new RankInfo(2, "کتاب‌خور", 3),
            new RankInfo(3, "کتابدار", 10),
            new RankInfo(4, "افسانه‌ی کتابخانه", 25)
        };

        public static int RankIndexFor(int done)
        {
            for (int i = Ranks.Length - 1; i >= 0; i--)
            {
                if (done >= Ranks[i].MinBooks)
                {
                    return i;
                }
            }
            return 0;
        }

        public static string NameFor(int done)
        {
            return Ranks[RankIndexFor(done)].Name;
        }

        public static RankInfo Get(int index)
        {
            if (index < 0)
            {
                index = 0;
            }
            if (index >= Ranks.Length)
            {
                index = Ranks.Length - 1;
            }
            return Ranks[index];
        }

        // بالاترین رتبه‌ای که تا حالا ثبت شده. -1 یعنی هنوز هیچ‌وقت ثبت نشده (اولین اجرا بعد از اضافه‌شدن تم‌ها)
        public static int MaxRankReached
        {
            get
            {
                int v;
                if (int.TryParse(AppSettings.Get("MaxRank", "-1"), out v))
                {
                    return v;
                }
                return -1;
            }
            set { AppSettings.Set("MaxRank", value.ToString()); }
        }

        public static bool IsRankUnlocked(int requiredRank)
        {
            return requiredRank <= Math.Max(0, MaxRankReached);
        }

        // تم یا با رتبه باز شده، یا با راز مخفیِ خودش
        public static bool IsUnlocked(CharacterTheme theme)
        {
            return theme != null && (IsRankUnlocked(theme.RequiredRank) || IsSecretUnlocked(theme.Id));
        }

        // فقط با رتبه باز شده (نه با راز مخفی)
        public static bool IsUnlockedByRank(CharacterTheme theme)
        {
            return theme != null && IsRankUnlocked(theme.RequiredRank);
        }

        // ---------- بازشدن مخفی تم‌ها (بدون توجه به رتبه) ----------
        // شناسه‌ی تم‌هایی که با راز باز شدن (جدا شده با ویرگول) توی appsettings.txt ذخیره می‌شه
        private const string SecretKey = "SecretThemes";

        public static bool IsSecretUnlocked(string themeId)
        {
            if (string.IsNullOrEmpty(themeId))
            {
                return false;
            }
            string[] ids = AppSettings.Get(SecretKey, "").Split(new char[] { ',' }, StringSplitOptions.RemoveEmptyEntries);
            foreach (string id in ids)
            {
                if (id.Trim() == themeId)
                {
                    return true;
                }
            }
            return false;
        }

        public static void SecretUnlock(string themeId)
        {
            if (string.IsNullOrEmpty(themeId) || IsSecretUnlocked(themeId))
            {
                return;
            }
            string old = AppSettings.Get(SecretKey, "");
            AppSettings.Set(SecretKey, old.Length == 0 ? themeId : old + "," + themeId);
        }

        public static int UnlockedCount()
        {
            int n = 0;
            foreach (CharacterTheme t in CharacterThemes.All)
            {
                if (IsUnlocked(t))
                {
                    n++;
                }
            }
            return n;
        }
    }
}
