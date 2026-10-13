using System;
using System.Collections.Generic;
using System.Windows.Media;

namespace EpubReader.Services
{
    // رنگ‌های یک تم (حالت تیره یا روشن). ترتیب مقادیر در Parse:
    // پس‌زمینه، سایدبار، کارت، متن، متن کم‌رنگ، هاور، کادر، زمینه‌ی نرم، هیرو۱، هیرو۲،
    // رنگ اصلی، رنگ اصلی تیره، گرادیان۱، گرادیان۲، هاله‌ی۱، هاله‌ی۲
    public sealed class ThemePalette
    {
        public string Bg;
        public string Sidebar;
        public string Card;
        public string Text;
        public string Muted;
        public string Hover;
        public string Border;
        public string Soft;
        public string Hero1;
        public string Hero2;
        public string Accent;
        public string AccentDark;
        public string Grad1;
        public string Grad2;
        public string Aura1;
        public string Aura2;

        public static ThemePalette Parse(string spaced)
        {
            string[] v = spaced.Split(new char[] { ' ' }, StringSplitOptions.RemoveEmptyEntries);
            if (v.Length < 16)
            {
                throw new ArgumentException("palette needs 16 colors");
            }
            ThemePalette p = new ThemePalette();
            p.Bg = v[0];
            p.Sidebar = v[1];
            p.Card = v[2];
            p.Text = v[3];
            p.Muted = v[4];
            p.Hover = v[5];
            p.Border = v[6];
            p.Soft = v[7];
            p.Hero1 = v[8];
            p.Hero2 = v[9];
            p.Accent = v[10];
            p.AccentDark = v[11];
            p.Grad1 = v[12];
            p.Grad2 = v[13];
            p.Aura1 = v[14];
            p.Aura2 = v[15];
            return p;
        }
    }

    // یک تم اختصاصی شخصیت. RequiredRank شماره‌ی رتبه‌ای است که باید به آن برسی تا تم باز بشه (RankService.Ranks)
    public sealed class CharacterTheme
    {
        public readonly string Id;
        private readonly string name;
        private readonly string fullName;
        private readonly string tagline;
        public readonly string Particle;
        public readonly int RequiredRank;
        public readonly ThemePalette Dark;
        public readonly ThemePalette Light;
        private readonly string emblemData;
        private Geometry emblem;

        public CharacterTheme(string id, string name, string fullName, string tagline, string particle, int requiredRank,
            ThemePalette dark, ThemePalette light, string emblemData)
        {
            Id = id;
            this.name = name;
            this.fullName = fullName;
            this.tagline = tagline;
            Particle = particle;
            RequiredRank = requiredRank;
            Dark = dark;
            Light = light;
            this.emblemData = emblemData;
        }

        // اسم و توضیح تم به زبان انتخاب‌شده
        public string Name { get { return Loc.T(name); } }

        public string FullName { get { return Loc.T(fullName); } }

        public string Tagline { get { return Loc.T(tagline); } }

        // تم‌های فقط-مخفی با رتبه باز نمی‌شن (رتبه‌ی ۹۹ هیچ‌وقت نمی‌رسه) و تا وقتی باز نشدن توی لیست دیده نمی‌شن
        public bool SecretOnly { get { return RequiredRank >= 99; } }

        // کلید تم صفحه‌ی خواندن (شب / روز) که در Book.Theme ذخیره می‌شه
        public string PageKeyNight { get { return "rz." + Id + ".night"; } }

        public string PageKeyDay { get { return "rz." + Id + ".day"; } }

        public ThemePalette Palette(bool light)
        {
            return light ? Light : Dark;
        }

        // نشان وکتوری شخصیت (جعبه‌ی ۱۰۰×۱۰۰)
        public Geometry Emblem
        {
            get
            {
                if (emblem == null)
                {
                    Geometry g = Geometry.Parse(emblemData);
                    if (g.CanFreeze)
                    {
                        g.Freeze();
                    }
                    emblem = g;
                }
                return emblem;
            }
        }
    }

    public static class CharacterThemes
    {
        // به ترتیب رتبه: سوبارو (باز از همون اول) تا امیلیا (بالاترین رتبه)
        public static readonly CharacterTheme[] All = new CharacterTheme[]
        {
            new CharacterTheme(
                "subaru", "سوبارو", "ناتسوکی سوبارو",
                "سبزِ شب و نارنجیِ گرم؛ هر بار از اول، با اراده‌ای نو",
                "embers", 0,
                ThemePalette.Parse("#0C1210 #111A16 #17231D #E8F0EA #8AA095 #1F2F27 #26382F #3A2616 #1E2F25 #101A14 #FF8A3D #F26B1D #FFB066 #F26B1D #FF8A3D #2FBF71"),
                ThemePalette.Parse("#F3F8F4 #FFFFFF #FFFFFF #14231B #5E7A6B #E9F3EC #D5E5DA #FFEBDB #EAF6EE #FFE7D1 #CC4A08 #A83806 #FF9A52 #E8590C #FF8A3D #2FBF71"),
                "F1 M97,50 C97,75.96 75.96,97 50,97 C24.04,97 3,75.96 3,50 C3,24.04 24.04,3 50,3 C75.96,3 97,24.04 97,50 Z M91,50 C91,27.36 72.64,9 50,9 C27.36,9 9,27.36 9,50 C9,72.64 27.36,91 50,91 C72.64,91 91,72.64 91,50 Z M48.3,21 L48.3,13 L51.7,13 L51.7,21 Z M65.63,20.92 L67.63,17.46 L69.37,18.46 L67.37,21.92 Z M78.08,32.63 L81.54,30.63 L82.54,32.37 L79.08,34.37 Z M79,48.3 L87,48.3 L87,51.7 L79,51.7 Z M79.08,65.63 L82.54,67.63 L81.54,69.37 L78.08,67.37 Z M67.37,78.08 L69.37,81.54 L67.63,82.54 L65.63,79.08 Z M51.7,79 L51.7,87 L48.3,87 L48.3,79 Z M34.37,79.08 L32.37,82.54 L30.63,81.54 L32.63,78.08 Z M21.92,67.37 L18.46,69.37 L17.46,67.63 L20.92,65.63 Z M21,51.7 L13,51.7 L13,48.3 L21,48.3 Z M20.92,34.37 L17.46,32.37 L18.46,30.63 L21.92,32.63 Z M32.63,21.92 L30.63,18.46 L32.37,17.46 L34.37,20.92 Z M48.44,50.9 L33.44,24.92 L36.56,23.12 L51.56,49.1 Z M51.2,52.08 L33.88,62.08 L31.48,57.92 L48.8,47.92 Z M56,50 C56,53.31 53.31,56 50,56 C46.69,56 44,53.31 44,50 C44,46.69 46.69,44 50,44 C53.31,44 56,46.69 56,50 Z M41,69 L50,63.5 L50,74.5 Z M50,69 L59,63.5 L59,74.5 Z"),
            new CharacterTheme(
                "ram", "رام", "رام",
                "صورتیِ تند، زبانِ تیز و بادی که نمی‌ایسته",
                "petals", 1,
                ThemePalette.Parse("#130A10 #1A0E16 #25121F #FBE9F1 #B58AA2 #341A2B #43203A #4A1735 #3A1530 #1B0C16 #FF6FAE #E8458F #FF9CC8 #E11D6B #FF4D94 #C81E4F"),
                ThemePalette.Parse("#FFF3F8 #FFFFFF #FFFFFF #3A1226 #9A6580 #FFE6F0 #F8D2E3 #FFE0EE #FFEAF3 #FFD3E6 #D6246E #B01457 #FF7FB4 #D6246E #FF6FAE #E11D48"),
                "F0 M35,94 C28,68 40,34 66,5 C62,32 72,62 63,94 Z M39.63,84.2 L59.5,79.8 L59.5,82.4 L39.63,86.8 Z M39.8,70.2 L60,65.8 L60,68.2 L39.8,72.6 Z M42.41,56.2 L60.6,51.8 L60.6,54 L42.41,58.4 Z M46.61,43.2 L58.4,38.8 L58.4,40.8 L46.61,45.2 Z M70,24 C86,28 95,46 90,64 C88,50 80,40 66,33 Z M77,45 C86,50 90,60 88,72 C85,63 80,57 73,53 Z M24,50 C10,56 5,72 12,86 C12,74 18,64 29,58 Z M20,66 C13,70 10,78 13,88 C14,80 17,75 23,72 Z M84,4 L86.2,9.6 L92,12 L86.2,14.4 L84,20 L81.8,14.4 L76,12 L81.8,9.6 Z"),
            new CharacterTheme(
                "rem", "رم", "رم",
                "آبیِ آرومِ آسمون و دلی که بی‌دریغ می‌ده",
                "sparkles", 2,
                ThemePalette.Parse("#080E1A #0C1424 #111C32 #E6F1FF #85A0C4 #182A4A #1F3358 #14335E #153059 #0A1428 #5BB5FF #2F8FEA #9AD3FF #2F7FE0 #4DA8FF #22D3EE"),
                ThemePalette.Parse("#EFF6FF #FFFFFF #FFFFFF #0F2347 #5C7AA6 #E1EFFF #CFE2F8 #DCEEFF #E6F2FF #CDE5FF #1D74D8 #1558AD #6DB8FF #1D74D8 #5BB5FF #22D3EE"),
                "F1 M72,60 C72,69.39 64.39,77 55,77 C45.61,77 38,69.39 38,60 C38,50.61 45.61,43 55,43 C64.39,43 72,50.61 72,60 Z M67,60 C67,53.37 61.63,48 55,48 C48.37,48 43,53.37 43,60 C43,66.63 48.37,72 55,72 C61.63,72 67,66.63 67,60 Z M62.2,60 C62.2,63.98 58.98,67.2 55,67.2 C51.02,67.2 47.8,63.98 47.8,60 C47.8,56.02 51.02,52.8 55,52.8 C58.98,52.8 62.2,56.02 62.2,60 Z M57.29,44.67 L68.01,28.59 L64.22,47.54 Z M67.46,50.78 L86.41,46.99 L70.33,57.71 Z M70.33,62.29 L86.41,73.01 L67.46,69.22 Z M64.22,72.46 L68.01,91.41 L57.29,75.33 Z M52.71,75.33 L41.99,91.41 L45.78,72.46 Z M42.54,69.22 L23.59,73.01 L39.67,62.29 Z M39.67,57.71 L23.59,46.99 L42.54,50.78 Z M45.78,47.54 L41.99,28.59 L52.71,44.67 Z M45.6,45.6 C45.6,48.36 43.36,50.6 40.6,50.6 C37.84,50.6 35.6,48.36 35.6,45.6 C35.6,42.84 37.84,40.6 40.6,40.6 C43.36,40.6 45.6,42.84 45.6,45.6 Z M43.1,45.6 C43.1,44.22 41.98,43.1 40.6,43.1 C39.22,43.1 38.1,44.22 38.1,45.6 C38.1,46.98 39.22,48.1 40.6,48.1 C41.98,48.1 43.1,46.98 43.1,45.6 Z M37.4,37.4 C37.4,40.16 35.16,42.4 32.4,42.4 C29.64,42.4 27.4,40.16 27.4,37.4 C27.4,34.64 29.64,32.4 32.4,32.4 C35.16,32.4 37.4,34.64 37.4,37.4 Z M34.9,37.4 C34.9,36.02 33.78,34.9 32.4,34.9 C31.02,34.9 29.9,36.02 29.9,37.4 C29.9,38.78 31.02,39.9 32.4,39.9 C33.78,39.9 34.9,38.78 34.9,37.4 Z M29.2,29.2 C29.2,31.96 26.96,34.2 24.2,34.2 C21.44,34.2 19.2,31.96 19.2,29.2 C19.2,26.44 21.44,24.2 24.2,24.2 C26.96,24.2 29.2,26.44 29.2,29.2 Z M26.7,29.2 C26.7,27.82 25.58,26.7 24.2,26.7 C22.82,26.7 21.7,27.82 21.7,29.2 C21.7,30.58 22.82,31.7 24.2,31.7 C25.58,31.7 26.7,30.58 26.7,29.2 Z M21.2,18 C21.2,22.53 17.53,26.2 13,26.2 C8.47,26.2 4.8,22.53 4.8,18 C4.8,13.47 8.47,9.8 13,9.8 C17.53,9.8 21.2,13.47 21.2,18 Z M17.8,18 C17.8,15.35 15.65,13.2 13,13.2 C10.35,13.2 8.2,15.35 8.2,18 C8.2,20.65 10.35,22.8 13,22.8 C15.65,22.8 17.8,20.65 17.8,18 Z"),
            new CharacterTheme(
                "beatrice", "بیاتریس", "بیاتریس",
                "نگهبانِ کتابخانه‌ی ممنوعه؛ طلاییِ شمع و پروانه‌های شب",
                "butterflies", 3,
                ThemePalette.Parse("#100E1C #161228 #1E1933 #F6EEDC #A99FBD #2A2347 #342C57 #3A2E4A #2E2350 #15112A #F2C14E #E0A526 #FFE08A #E8A33A #F2C14E #F08DB6"),
                ThemePalette.Parse("#FFF8EA #FFFDF6 #FFFFFF #2C2140 #8C7A66 #FFF0D2 #F1DFB8 #FFEFC7 #FFF3D6 #FBD9E6 #A8690A #86500A #F5C75A #D9902B #F2C14E #F08DB6"),
                "F0 M53,45 C57,23 80,5 96,9 C102,23 95,43 80,51 C71,55 61,52 53,45 Z M47,45 C43,23 20,5 4,9 C-2,23 5,43 20,51 C29,55 39,52 47,45 Z M87.2,27 C87.2,29.87 84.87,32.2 82,32.2 C79.13,32.2 76.8,29.87 76.8,27 C76.8,24.13 79.13,21.8 82,21.8 C84.87,21.8 87.2,24.13 87.2,27 Z M12.8,27 C12.8,29.87 15.13,32.2 18,32.2 C20.87,32.2 23.2,29.87 23.2,27 C23.2,24.13 20.87,21.8 18,21.8 C15.13,21.8 12.8,24.13 12.8,27 Z M53,55 C64,54 77,58 83,68 C86,82 72,92 63,85 C56,79 54,65 53,55 Z M47,55 C36,54 23,58 17,68 C14,82 28,92 37,85 C44,79 46,65 47,55 Z M75.6,76 C75.6,77.99 73.99,79.6 72,79.6 C70.01,79.6 68.4,77.99 68.4,76 C68.4,74.01 70.01,72.4 72,72.4 C73.99,72.4 75.6,74.01 75.6,76 Z M24.4,76 C24.4,77.99 26.01,79.6 28,79.6 C29.99,79.6 31.6,77.99 31.6,76 C31.6,74.01 29.99,72.4 28,72.4 C26.01,72.4 24.4,74.01 24.4,76 Z M48.4,36 C48.4,31 51.6,31 51.6,36 L51.6,73 C51.6,78 48.4,78 48.4,73 Z M49.2,35 C47.6,25 42,18 35,13.5 L34.2,15.6 C40,19.5 45,26.5 47.6,35.5 Z M50.8,35 C52.4,25 58,18 65,13.5 L65.8,15.6 C60,19.5 55,26.5 52.4,35.5 Z"),
            new CharacterTheme(
                "emilia", "امیلیا", "امیلیا",
                "نقره‌ای و یخی؛ آرامشِ برف و بنفشِ گل‌های بهاری",
                "snow", 4,
                ThemePalette.Parse("#0D0F1E #12152A #191D38 #EEF1FF #96A0C8 #232850 #2B3263 #2A2A5C #28306A #101431 #B7A8FF #8F7CF0 #D8CFFF #7C6CF0 #A99BFF #8FE0FF"),
                ThemePalette.Parse("#F4F5FF #FFFFFF #FFFFFF #1B1E3F #6C73A6 #E9EBFF #D9DCF6 #E6E3FF #EEF0FF #DDE3FF #5F4BD6 #4A38B8 #A99BFF #6D5BE0 #A99BFF #7FD6F5"),
                "F1 M64,50 C64,57.73 57.73,64 50,64 C42.27,64 36,57.73 36,50 C36,42.27 42.27,36 50,36 C57.73,36 64,42.27 64,50 Z M60.5,50 C60.5,44.2 55.8,39.5 50,39.5 C44.2,39.5 39.5,44.2 39.5,50 C39.5,55.8 44.2,60.5 50,60.5 C55.8,60.5 60.5,55.8 60.5,50 Z M48.3,39 L48.3,12 L51.7,12 L51.7,39 Z M49.35,29.13 L39.82,23.63 L41.12,21.37 L50.65,26.87 Z M49.35,26.87 L58.88,21.37 L60.18,23.63 L50.65,29.13 Z M49.35,21.13 L42.85,17.38 L44.15,15.12 L50.65,18.87 Z M49.35,18.87 L55.85,15.12 L57.15,17.38 L50.65,21.13 Z M46.4,9 L50,2 L53.6,9 L50,15 Z M58.68,43.03 L82.06,29.53 L83.76,32.47 L60.38,45.97 Z M67.75,39 L67.75,28 L70.35,28 L70.35,39 Z M69.7,37.87 L79.23,43.37 L77.93,45.63 L68.4,40.13 Z M74.68,35 L74.68,27.5 L77.28,27.5 L77.28,35 Z M76.63,33.87 L83.13,37.62 L81.83,39.88 L75.33,36.13 Z M83.71,26.38 L91.57,26 L87.31,32.62 L80.31,32.5 Z M60.38,54.03 L83.76,67.53 L82.06,70.47 L58.68,56.97 Z M68.4,59.87 L77.93,54.37 L79.23,56.63 L69.7,62.13 Z M70.35,61 L70.35,72 L67.75,72 L67.75,61 Z M75.33,63.87 L81.83,60.12 L83.13,62.38 L76.63,66.13 Z M77.28,65 L77.28,72.5 L74.68,72.5 L74.68,65 Z M87.31,67.38 L91.57,74 L83.71,73.62 L80.31,67.5 Z M51.7,61 L51.7,88 L48.3,88 L48.3,61 Z M50.65,70.87 L60.18,76.37 L58.88,78.63 L49.35,73.13 Z M50.65,73.13 L41.12,78.63 L39.82,76.37 L49.35,70.87 Z M50.65,78.87 L57.15,82.62 L55.85,84.88 L49.35,81.13 Z M50.65,81.13 L44.15,84.88 L42.85,82.62 L49.35,78.87 Z M53.6,91 L50,98 L46.4,91 L50,85 Z M41.32,56.97 L17.94,70.47 L16.24,67.53 L39.62,54.03 Z M32.25,61 L32.25,72 L29.65,72 L29.65,61 Z M30.3,62.13 L20.77,56.63 L22.07,54.37 L31.6,59.87 Z M25.32,65 L25.32,72.5 L22.72,72.5 L22.72,65 Z M23.37,66.13 L16.87,62.38 L18.17,60.12 L24.67,63.87 Z M16.29,73.62 L8.43,74 L12.69,67.38 L19.69,67.5 Z M39.62,45.97 L16.24,32.47 L17.94,29.53 L41.32,43.03 Z M31.6,40.13 L22.07,45.63 L20.77,43.37 L30.3,37.87 Z M29.65,39 L29.65,28 L32.25,28 L32.25,39 Z M24.67,36.13 L18.17,39.88 L16.87,37.62 L23.37,33.87 Z M22.72,35 L22.72,27.5 L25.32,27.5 L25.32,35 Z M12.69,32.62 L8.43,26 L16.29,26.38 L19.69,32.5 Z"),
            new CharacterTheme(
                "echidna", "اکیدنا", "اکیدنا، جادوگرِ طمع",
                "سبزِ زهرآگین و چایِ نیمه‌شب؛ طمعِ سیری‌ناپذیر برای دانستن",
                "leaves", 99,
                ThemePalette.Parse("#040A07 #08120D #0E1D15 #E6FFEF #7FB095 #14301F #1B4A2E #0F2A1B #0F3A22 #06120B #2CFF8A #12C968 #7CFFB5 #0FA85A #2CFF8A #00E5C0"),
                ThemePalette.Parse("#F0FAF4 #FFFFFF #FFFFFF #0B2417 #5B8068 #E2F5EA #C6E6D2 #D8F5E3 #D2F4E0 #BDEBD2 #0B8F4A #076B37 #3DDB86 #0B8F4A #1FD174 #00BFA0"),
                "F1 M24,46 L76,46 C76,68 66,80 50,80 C34,80 24,68 24,46 Z M76,50 C90,50 92,68 74,70 L73,65 C83,64 83,55 75,55 Z M14,86 C14,83 30,81 50,81 C70,81 86,83 86,86 C86,90 70,92 50,92 C30,92 14,90 14,86 Z M38,40 C32,33 42,28 38,19 L41.5,18 C46,28 36,33 41.5,39 Z M50,38 C44,30 55,24 50,13 L53.5,12 C58.5,24 47,30 53.5,37 Z M62,40 C56,33 66,28 62,19 L65.5,18 C70,28 60,33 65.5,39 Z M84,6 L86.2,11.6 L92,14 L86.2,16.4 L84,22 L81.8,16.4 L76,14 L81.8,11.6 Z")
        };

        // تعداد تم‌هایی که توی شمارنده نشون داده می‌شن (تم‌های مخفیِ باز‌نشده حساب نمی‌شن)
        public static int VisibleCount()
        {
            int n = 0;
            foreach (CharacterTheme t in All)
            {
                if (!t.SecretOnly || RankService.IsUnlocked(t))
                {
                    n++;
                }
            }
            return n;
        }

        public static CharacterTheme Find(string id)
        {
            if (string.IsNullOrEmpty(id))
            {
                return null;
            }
            foreach (CharacterTheme t in All)
            {
                if (t.Id == id)
                {
                    return t;
                }
            }
            return null;
        }

        // تم مربوط به یک رتبه (هر رتبه یک شخصیت)
        public static CharacterTheme ForRank(int rank)
        {
            foreach (CharacterTheme t in All)
            {
                if (t.RequiredRank == rank)
                {
                    return t;
                }
            }
            return null;
        }

        // شکل‌های کوچک ذرات متحرک (جعبه‌ی ۲۰×۲۰)
        private static readonly Dictionary<string, string> glyphData = new Dictionary<string, string>
        {
            { "petal", "F0 M10,1 C15,1 19,6 17,11 C15.5,15 12,17 10,19.5 C8,17 4.5,15 3,11 C1,6 5,1 10,1 Z" },
            { "leaf", "F0 M10,1 C16,4 18,12 10,19 C2,12 4,4 10,1 Z M9.4,6 L10.6,6 L10.6,17 L9.4,17 Z" },
            { "spark", "F0 M10,0 L12.2,7.8 L20,10 L12.2,12.2 L10,20 L7.8,12.2 L0,10 L7.8,7.8 Z" },
            { "snow", "F1 M12.2,10 C12.2,11.22 11.22,12.2 10,12.2 C8.78,12.2 7.8,11.22 7.8,10 C7.8,8.78 8.78,7.8 10,7.8 C11.22,7.8 12.2,8.78 12.2,10 Z M9.15,19 L9.15,1 L10.85,1 L10.85,19 Z M1.78,13.76 L17.37,4.76 L18.22,6.24 L2.63,15.24 Z M2.63,4.76 L18.22,13.76 L17.37,15.24 L1.78,6.24 Z" }
        };

        private static readonly Dictionary<string, Geometry> glyphCache = new Dictionary<string, Geometry>();

        public static Geometry Glyph(string name)
        {
            Geometry g;
            if (glyphCache.TryGetValue(name, out g))
            {
                return g;
            }
            string data;
            if (!glyphData.TryGetValue(name, out data))
            {
                return Geometry.Empty;
            }
            g = Geometry.Parse(data);
            if (g.CanFreeze)
            {
                g.Freeze();
            }
            glyphCache[name] = g;
            return g;
        }
    }
}
