using System;
using System.Runtime.InteropServices;
using System.Windows;
using System.Windows.Controls;
using System.Windows.Interop;
using System.Windows.Shapes;
using System.Windows.Media;

namespace EpubReader.Services
{
    public sealed class AccentPreset
    {
        private string name;
        public string Hex;

        public AccentPreset(string name, string hex)
        {
            this.name = name;
            Hex = hex;
        }

        // اسم رنگ به زبان انتخاب‌شده
        public string Name { get { return Loc.T(name); } }
    }

    public static class ThemeManager
    {
        public const string DefaultAccent = "#7C3AED";

        // هفت رنگ آماده‌ی انتخاب رنگ برنامه
        public static readonly AccentPreset[] Presets = new AccentPreset[]
        {
            new AccentPreset("بنفش", "#7C3AED"),
            new AccentPreset("آبی", "#2563EB"),
            new AccentPreset("سبز", "#16A34A"),
            new AccentPreset("زرد", "#EAB308"),
            new AccentPreset("نارنجی", "#EA580C"),
            new AccentPreset("قرمز", "#DC2626"),
            new AccentPreset("صورتی", "#DB2777")
        };

        private static readonly Color DarkInk = Color.FromRgb(0x13, 0x11, 0x1C);

        public static bool IsLight
        {
            get { return AppSettings.Theme == "light"; }
        }

        public static string Accent
        {
            get { return AppSettings.Accent; }
        }

        // تم شخصیتی که الان فعاله (فقط اگه باز شده باشه). null یعنی رنگ ساده‌ی معمولی
        public static CharacterTheme ActiveCharacter
        {
            get
            {
                CharacterTheme t = CharacterThemes.Find(AppSettings.Get("CharTheme", ""));
                return RankService.IsUnlocked(t) ? t : null;
            }
        }

        // چیزی که آخرین بار روی برنامه اعمال شده (برای لایه‌ی ذرات و ...)
        public static CharacterTheme AppliedCharacter { get; private set; }

        public static bool AppliedLight { get; private set; }

        // رنگ اصلی فعلی؛ برای جاهایی مثل HTML صفحه‌ی خواندن که برس WPF نمی‌گیرن
        public static string CurrentAccentHex = DefaultAccent;

        // بعد از هر بار اعمال تم صدا زده می‌شه
        public static event EventHandler Applied;

        public static void Apply(string theme)
        {
            bool light = (theme == "light");
            AppSettings.Theme = light ? "light" : "dark";
            ApplyAll(light, AppSettings.Accent, ActiveCharacter);
        }

        // فعال کردن تم شخصیت. id خالی یعنی برگشت به رنگ ساده. تم قفل‌شده فعال نمی‌شه (false برمی‌گردونه)
        public static bool SetCharacter(string id, bool save)
        {
            CharacterTheme t = CharacterThemes.Find(id);
            if (t != null && !RankService.IsUnlocked(t))
            {
                return false;
            }
            if (save)
            {
                AppSettings.Set("CharTheme", t == null ? "" : t.Id);
            }
            ApplyAll(IsLight, AppSettings.Accent, t);
            return true;
        }

        // رنگ اصلی برنامه رو عوض می‌کنه. save=false یعنی فقط پیش‌نمایش (مثلاً وسط کشیدن موس).
        // انتخاب رنگ دلخواه جای تم شخصیت رو می‌گیره.
        public static void SetAccent(string hex, bool save)
        {
            Color c;
            if (!TryParseColor(hex, out c))
            {
                return;
            }
            string norm = ToHex(c);
            if (save)
            {
                AppSettings.Accent = norm;
                AppSettings.Set("CharTheme", "");
            }
            ApplyAll(IsLight, norm, null);
        }

        private static Color Col(string hex)
        {
            Color c;
            if (!TryParseColor(hex, out c))
            {
                c = Colors.Magenta;
            }
            return c;
        }

        private static Brush AuraBrush(Color c, double cx, double cy, double alpha)
        {
            RadialGradientBrush b = new RadialGradientBrush();
            b.Center = new Point(cx, cy);
            b.GradientOrigin = new Point(cx, cy);
            b.RadiusX = 0.85;
            b.RadiusY = 0.85;
            b.GradientStops.Add(new GradientStop(Color.FromArgb(ToByte(alpha), c.R, c.G, c.B), 0.0));
            b.GradientStops.Add(new GradientStop(Color.FromArgb(0, c.R, c.G, c.B), 1.0));
            b.Freeze();
            return b;
        }

        private static void ApplyAll(bool light, string accentHex, CharacterTheme ch)
        {
            Color accent = Colors.Transparent;
            Color accentDark = Colors.Transparent;
            Color bgDark = Colors.Transparent;
            Color bgSidebar = Colors.Transparent;
            Color bgCard = Colors.Transparent;
            Color text = Colors.Transparent;
            Color muted = Colors.Transparent;
            Color hover = Colors.Transparent;
            Color border = Colors.Transparent;
            Color soft = Colors.Transparent;
            Color hero1 = Colors.Transparent;
            Color hero2 = Colors.Transparent;
            Color overlay = Colors.Transparent;
            Color shadow = Colors.Transparent;
            Color lightBtnBg = Colors.Transparent;
            Color lightBtnFg = Colors.Transparent;
            Color gradA = Colors.Transparent;
            Color gradB = Colors.Transparent;

            if (ch != null)
            {
                // تم شخصیت: همه‌ی رنگ‌ها از پالت دست‌چین‌شده‌ی خود شخصیت میاد
                ThemePalette p = ch.Palette(light);
                bgDark = Col(p.Bg);
                bgSidebar = Col(p.Sidebar);
                bgCard = Col(p.Card);
                text = Col(p.Text);
                muted = Col(p.Muted);
                hover = Col(p.Hover);
                border = Col(p.Border);
                soft = Col(p.Soft);
                hero1 = Col(p.Hero1);
                hero2 = Col(p.Hero2);
                accent = Col(p.Accent);
                accentDark = Col(p.AccentDark);
                gradA = Col(p.Grad1);
                gradB = Col(p.Grad2);
                if (light)
                {
                    overlay = accent;
                    shadow = muted;
                    lightBtnBg = accent;
                    lightBtnFg = PickOn(accent);
                }
                else
                {
                    overlay = Colors.White;
                    shadow = Colors.Black;
                    lightBtnBg = text;
                    lightBtnFg = bgSidebar;
                }
            }
            else
            {
                Color baseColor;
                if (!TryParseColor(accentHex, out baseColor))
                {
                    TryParseColor(DefaultAccent, out baseColor);
                }
                double h, s, l;
                RgbToHsl(baseColor, out h, out s, out l);

                // رنگ‌های خاکستری/سفید/مشکی پس‌زمینه رو بی‌رنگ می‌ذارن؛ بقیه کمی به رنگ اصلی متمایل می‌شن
                double tint = (s < 0.08) ? 0.0 : Math.Min(1.0, 0.45 + s);
                double tintStrong = (s < 0.08) ? 0.0 : 1.0;

                if (light)
                {
                    double al = Clamp(l, 0.38, 0.58);
                    accent = Hsl(h, s, al);
                    accentDark = Hsl(h, s, al - 0.08);
                }
                else
                {
                    double al = Clamp(l + 0.16, 0.66, 0.76);
                    accent = Hsl(h, s, al);
                    accentDark = Hsl(h, s, al - 0.10);
                }

                if (light)
                {
                    bgDark = Hsl(h, 0.444 * tint, 0.965);
                    bgSidebar = Colors.White;
                    bgCard = Colors.White;
                    text = Hsl(h, 0.278 * tint, 0.141);
                    muted = Hsl(h, 0.108 * tint, 0.471);
                    hover = Hsl(h, 0.583 * tint, 0.953);
                    border = Hsl(h, 0.348 * tint, 0.910);
                    soft = Hsl(h, 0.920 * tintStrong, 0.951);
                    hero1 = Hsl(h, 1.000 * tintStrong, 0.959);
                    hero2 = Hsl(h, 0.902 * tintStrong, 0.920);
                    overlay = Hsl(h, 0.607 * tintStrong, 0.531);
                    shadow = Hsl(h, 0.289 * tint, 0.504);
                    lightBtnBg = accent;
                    lightBtnFg = PickOn(accent);
                }
                else
                {
                    bgDark = Hsl(h, 0.243 * tint, 0.073);
                    bgSidebar = Hsl(h, 0.240 * tint, 0.098);
                    bgCard = Hsl(h, 0.235 * tint, 0.133);
                    text = Hsl(h, 0.333 * tint, 0.912);
                    muted = Hsl(h, 0.106 * tint, 0.575);
                    hover = Hsl(h, 0.231 * tint, 0.178);
                    border = Hsl(h, 0.243 * tint, 0.202);
                    soft = Hsl(h, 0.416 * tintStrong, 0.222);
                    hero1 = Hsl(h, 0.341 * tint, 0.178);
                    hero2 = Hsl(h, 0.333 * tint, 0.124);
                    overlay = Colors.White;
                    shadow = Colors.Black;
                    lightBtnBg = Hsl(h, 1.0 * tintStrong, 0.97);
                    lightBtnFg = bgSidebar;
                }
                gradA = Hsl(h, s, Clamp(l + 0.14, 0.50, 0.80));
                gradB = Hsl(h, s, Clamp(l - 0.04, 0.34, 0.60));
            }

            Color onAccent = PickOn(accent);
            Color onAccentDark = PickOn(accentDark);

            ResourceDictionary r = Application.Current.Resources;

            SetBrush(r, "BgDark",          bgDark);
            SetBrush(r, "BgSidebar",       bgSidebar);
            SetBrush(r, "BgCard",          bgCard);
            SetBrush(r, "AccentColor",     accent);
            SetBrush(r, "AccentColorDark", accentDark);
            SetBrush(r, "TextColor",       text);
            SetBrush(r, "TextMuted",       muted);
            SetBrush(r, "HoverColor",      hover);
            SetBrush(r, "BorderColor",     border);
            SetBrush(r, "DangerColor",     light ? "#DC2626" : "#E5484D");
            SetBrush(r, "AccentSoft",      soft);
            SetBrush(r, "SuccessColor",    light ? "#059669" : "#34D399");

            SetBrush(r, "OnAccent",        onAccent);
            SetBrush(r, "OnAccentDark",    onAccentDark);
            SetBrush(r, "LightBtnBg",      lightBtnBg);
            SetBrush(r, "LightBtnFg",      lightBtnFg);
            SetBrush(r, "OverlayColor",    overlay);
            r["ShadowColor"] = shadow;
            r["AccentGlow"] = accentDark;

            SetGradient(r, "HeroGradient", hero1, hero2);
            SetGradient(r, "AccentGradient", gradA, gradB);

            // نشان و هاله‌ی تم شخصیت (وقتی تم شخصیتی فعال نیست، نامرئی‌ان)
            if (ch != null)
            {
                ThemePalette pa = ch.Palette(light);
                r["ThemeEmblem"] = ch.Emblem;
                r["ThemeArtVisibility"] = Visibility.Visible;
                r["ThemeAuraA"] = AuraBrush(Col(pa.Aura1), 0.0, 0.0, light ? 0.22 : 0.30);
                r["ThemeAuraB"] = AuraBrush(Col(pa.Aura2), 1.0, 1.0, light ? 0.18 : 0.24);
            }
            else
            {
                r["ThemeEmblem"] = Geometry.Empty;
                r["ThemeArtVisibility"] = Visibility.Collapsed;
                r["ThemeAuraA"] = Brushes.Transparent;
                r["ThemeAuraB"] = Brushes.Transparent;
            }

            CurrentAccentHex = ToHex(accent);
            AppliedCharacter = ch;
            AppliedLight = light;
            EventHandler handler = Applied;
            if (handler != null)
            {
                handler(null, EventArgs.Empty);
            }
        }

        public static bool TryParseColor(string hex, out Color color)
        {
            color = Colors.Transparent;
            if (string.IsNullOrEmpty(hex))
            {
                return false;
            }
            string t = hex.Trim().TrimStart('#');
            if (t.Length == 3)
            {
                t = "" + t[0] + t[0] + t[1] + t[1] + t[2] + t[2];
            }
            if (t.Length != 6)
            {
                return false;
            }
            int v;
            if (!int.TryParse(t, System.Globalization.NumberStyles.HexNumber, System.Globalization.CultureInfo.InvariantCulture, out v))
            {
                return false;
            }
            color = Color.FromRgb((byte)((v >> 16) & 255), (byte)((v >> 8) & 255), (byte)(v & 255));
            return true;
        }

        public static string ToHex(Color c)
        {
            return string.Format("#{0:X2}{1:X2}{2:X2}", c.R, c.G, c.B);
        }

        private static double Clamp(double v, double lo, double hi)
        {
            return v < lo ? lo : (v > hi ? hi : v);
        }

        private static byte ToByte(double v)
        {
            return (byte)Math.Round(Clamp(v, 0.0, 1.0) * 255.0);
        }

        private static Color Hsl(double h, double s, double l)
        {
            h = h % 360.0;
            if (h < 0.0)
            {
                h += 360.0;
            }
            s = Clamp(s, 0.0, 1.0);
            l = Clamp(l, 0.0, 1.0);
            double c = (1.0 - Math.Abs(2.0 * l - 1.0)) * s;
            double hp = h / 60.0;
            double x = c * (1.0 - Math.Abs(hp % 2.0 - 1.0));
            double r1 = 0.0;
            double g1 = 0.0;
            double b1 = 0.0;
            if (hp < 1.0) { r1 = c; g1 = x; }
            else if (hp < 2.0) { r1 = x; g1 = c; }
            else if (hp < 3.0) { g1 = c; b1 = x; }
            else if (hp < 4.0) { g1 = x; b1 = c; }
            else if (hp < 5.0) { r1 = x; b1 = c; }
            else { r1 = c; b1 = x; }
            double m = l - c / 2.0;
            return Color.FromRgb(ToByte(r1 + m), ToByte(g1 + m), ToByte(b1 + m));
        }

        private static void RgbToHsl(Color c, out double h, out double s, out double l)
        {
            double r = c.R / 255.0;
            double g = c.G / 255.0;
            double b = c.B / 255.0;
            double max = Math.Max(r, Math.Max(g, b));
            double min = Math.Min(r, Math.Min(g, b));
            double d = max - min;
            l = (max + min) / 2.0;
            if (d < 0.000001)
            {
                h = 0.0;
                s = 0.0;
                return;
            }
            s = d / (1.0 - Math.Abs(2.0 * l - 1.0));
            if (max == r)
            {
                h = 60.0 * (((g - b) / d) % 6.0);
            }
            else if (max == g)
            {
                h = 60.0 * (((b - r) / d) + 2.0);
            }
            else
            {
                h = 60.0 * (((r - g) / d) + 4.0);
            }
            if (h < 0.0)
            {
                h += 360.0;
            }
        }

        private static double Lin(double v)
        {
            return v <= 0.03928 ? v / 12.92 : Math.Pow((v + 0.055) / 1.055, 2.4);
        }

        private static double Lum(Color c)
        {
            return 0.2126 * Lin(c.R / 255.0) + 0.7152 * Lin(c.G / 255.0) + 0.0722 * Lin(c.B / 255.0);
        }

        // روی این پس‌زمینه، سفید بهتر خونده می‌شه یا تقریباً-مشکی؟
        private static Color PickOn(Color bg)
        {
            double lum = Lum(bg);
            double withWhite = 1.05 / (lum + 0.05);
            double withDark = (lum + 0.05) / (Lum(DarkInk) + 0.05);
            return withWhite >= withDark ? Colors.White : DarkInk;
        }

        private static void SetBrush(ResourceDictionary r, string key, string hex)
        {
            SetBrush(r, key, (Color)ColorConverter.ConvertFromString(hex));
        }

        private static void SetBrush(ResourceDictionary r, string key, Color color)
        {
            SolidColorBrush b = new SolidColorBrush(color);
            b.Freeze();
            r[key] = b;
        }

        private static void SetGradient(ResourceDictionary r, string key, Color c1, Color c2)
        {
            LinearGradientBrush g = new LinearGradientBrush();
            g.StartPoint = new Point(0, 0);
            g.EndPoint = new Point(1, 1);
            g.GradientStops.Add(new GradientStop(c1, 0));
            g.GradientStops.Add(new GradientStop(c2, 1));
            g.Freeze();
            r[key] = g;
        }
    }

    // بزرگ‌نمایی پنجره‌های بدون کادر: روی ناحیه‌ی کار (بدون پوشاندن تسک‌بار)،
    // وضعیت بزرگ/عادی بین همه پنجره‌ها مشترک و ذخیره می‌شه.
    public static class WindowMax
    {
        private const int WM_GETMINMAXINFO = 0x0024;
        private const uint MONITOR_DEFAULTTONEAREST = 2;

        [StructLayout(LayoutKind.Sequential)]
        private struct POINT
        {
            public int x;
            public int y;
        }

        [StructLayout(LayoutKind.Sequential)]
        private struct MINMAXINFO
        {
            public POINT ptReserved;
            public POINT ptMaxSize;
            public POINT ptMaxPosition;
            public POINT ptMinTrackSize;
            public POINT ptMaxTrackSize;
        }

        [StructLayout(LayoutKind.Sequential)]
        private struct RECT
        {
            public int left;
            public int top;
            public int right;
            public int bottom;
        }

        [StructLayout(LayoutKind.Sequential)]
        private struct MONITORINFO
        {
            public int cbSize;
            public RECT rcMonitor;
            public RECT rcWork;
            public uint dwFlags;
        }

        [DllImport("user32.dll")]
        private static extern IntPtr MonitorFromWindow(IntPtr hwnd, uint flags);

        [DllImport("user32.dll")]
        private static extern bool GetMonitorInfo(IntPtr hMonitor, ref MONITORINFO mi);

        private static bool applying = false;

        public static bool SavedMaximized
        {
            get { return AppSettings.Get("Maximized", "0") == "1"; }
        }

        // isImmersive: وقتی true باشه (تمام‌صفحه‌ی خواندن) پنجره کل مانیتور رو می‌گیره، نه ناحیه‌ی کار
        public static void Attach(Window w, Func<bool> isImmersive)
        {
            Func<bool> immersive = isImmersive ?? (() => false);

            w.SourceInitialized += (object s, EventArgs e) =>
            {
                HwndSource src = HwndSource.FromHwnd(new WindowInteropHelper(w).Handle);
                if (src != null)
                {
                    src.AddHook((IntPtr hwnd, int msg, IntPtr wParam, IntPtr lParam, ref bool handled) =>
                    {
                        if (msg == WM_GETMINMAXINFO)
                        {
                            FillMinMax(w, hwnd, lParam, immersive());
                            handled = true;
                        }
                        return IntPtr.Zero;
                    });
                }
            };

            w.StateChanged += (object s, EventArgs e) =>
            {
                UpdateChrome(w, immersive());
                if (applying || immersive() || w.WindowState == WindowState.Minimized)
                {
                    return;
                }
                AppSettings.Set("Maximized", w.WindowState == WindowState.Maximized ? "1" : "0");
            };

            w.IsVisibleChanged += (object s, DependencyPropertyChangedEventArgs e) =>
            {
                if (w.IsVisible)
                {
                    ApplySaved(w, immersive());
                }
            };

            ApplySaved(w, immersive());
            UpdateChrome(w, immersive());
        }

        private static void ApplySaved(Window w, bool immersive)
        {
            if (immersive || w.WindowState == WindowState.Minimized)
            {
                return;
            }
            WindowState desired = SavedMaximized ? WindowState.Maximized : WindowState.Normal;
            if (w.WindowState != desired)
            {
                SetState(w, desired);
            }
        }

        // تغییر وضعیت بدون اینکه تنظیم ذخیره‌شده عوض بشه
        public static void SetState(Window w, WindowState state)
        {
            bool old = applying;
            applying = true;
            try
            {
                w.WindowState = state;
            }
            finally
            {
                applying = old;
            }
        }

        public static void Toggle(Window w)
        {
            w.WindowState = (w.WindowState == WindowState.Maximized) ? WindowState.Normal : WindowState.Maximized;
        }

        private static void UpdateChrome(Window w, bool immersive)
        {
            bool max = (w.WindowState == WindowState.Maximized);
            Path ico = w.FindName("icoMaximize") as Path;
            if (ico != null)
            {
                ico.Data = (Geometry)Application.Current.FindResource(max ? "IcoRestore" : "IcoMaximize");
            }
            Button button = w.FindName("btnMaximize") as Button;
            if (button != null)
            {
                button.ToolTip = Loc.T(max ? "بازگرداندن" : "بزرگ‌نمایی");
            }
            if (!immersive && w.WindowState != WindowState.Minimized)
            {
                ResizeMode wanted = max ? ResizeMode.CanResize : ResizeMode.CanResizeWithGrip;
                if (w.ResizeMode != wanted)
                {
                    w.ResizeMode = wanted;
                }
            }
        }

        private static void FillMinMax(Window w, IntPtr hwnd, IntPtr lParam, bool immersive)
        {
            try
            {
                IntPtr monitor = MonitorFromWindow(hwnd, MONITOR_DEFAULTTONEAREST);
                if (monitor == IntPtr.Zero)
                {
                    return;
                }
                MONITORINFO mi = new MONITORINFO();
                mi.cbSize = Marshal.SizeOf(typeof(MONITORINFO));
                if (!GetMonitorInfo(monitor, ref mi))
                {
                    return;
                }
                MINMAXINFO mmi = (MINMAXINFO)Marshal.PtrToStructure(lParam, typeof(MINMAXINFO));
                RECT area = immersive ? mi.rcMonitor : mi.rcWork;
                mmi.ptMaxPosition.x = area.left - mi.rcMonitor.left;
                mmi.ptMaxPosition.y = area.top - mi.rcMonitor.top;
                mmi.ptMaxSize.x = area.right - area.left;
                mmi.ptMaxSize.y = area.bottom - area.top;

                double scaleX = 1.0;
                double scaleY = 1.0;
                PresentationSource source = PresentationSource.FromVisual(w);
                if (source != null && source.CompositionTarget != null)
                {
                    scaleX = source.CompositionTarget.TransformToDevice.M11;
                    scaleY = source.CompositionTarget.TransformToDevice.M22;
                }
                if (w.MinWidth > 0)
                {
                    mmi.ptMinTrackSize.x = (int)Math.Round(w.MinWidth * scaleX);
                }
                if (w.MinHeight > 0)
                {
                    mmi.ptMinTrackSize.y = (int)Math.Round(w.MinHeight * scaleY);
                }
                Marshal.StructureToPtr(mmi, lParam, true);
            }
            catch
            {
            }
        }
    }
}
