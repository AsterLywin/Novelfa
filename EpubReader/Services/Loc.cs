using System;
using System.Collections.Generic;
using System.Text;
using System.Windows;
using System.Windows.Controls;
using System.Windows.Data;
using System.Windows.Documents;
using System.Windows.Media;

namespace EpubReader.Services
{
    // دو زبانه بودن برنامه (فارسی و انگلیسی).
    // همه‌ی متن‌های برنامه به فارسی نوشته شدن؛ Loc.T اگه زبان انتخاب‌شده انگلیسی باشه، ترجمه‌ی همون متن رو از
    // جدول LocStrings برمی‌گردونه (اگه ترجمه‌ای نباشه همون فارسی می‌مونه).
    // زبان انتخاب‌شده توی appsettings.txt (کلید Language) ذخیره می‌شه و دفعه‌ی بعد همون باز می‌شه.
    // متن‌های ثابتِ XAML هم خودکار (بدون دست‌بردن توی XAML) موقع باز شدن هر کنترل ترجمه می‌شن.
    public static class Loc
    {
        public const string Fa = "fa";
        public const string En = "en";

        private static string current;
        private static Dictionary<string, string> map;
        private static bool inited;

        // کلیدِ اصلیِ (فارسی) هر متنی که ترجمه کردیم روی خودِ کنترل نگه داشته می‌شه تا بشه دوباره به فارسی برگشت
        private static readonly DependencyProperty TextKeyProperty =
            DependencyProperty.RegisterAttached("TextKey", typeof(string), typeof(Loc), new PropertyMetadata(null));

        private static readonly DependencyProperty TipKeyProperty =
            DependencyProperty.RegisterAttached("TipKey", typeof(string), typeof(Loc), new PropertyMetadata(null));

        // "fa" یا "en"
        public static string Lang
        {
            get
            {
                if (current == null)
                {
                    current = (AppSettings.Get("Language", Fa) == En) ? En : Fa;
                }
                return current;
            }
        }

        public static bool IsEnglish
        {
            get { return Lang == En; }
        }

        // جهت صفحه: فارسی راست‌به‌چپ، انگلیسی چپ‌به‌راست
        public static FlowDirection Flow
        {
            get { return IsEnglish ? FlowDirection.LeftToRight : FlowDirection.RightToLeft; }
        }

        // علامت درصد (فارسی ٪)
        public static string Percent
        {
            get { return IsEnglish ? "%" : "\u066A"; }
        }

        // وقتی زبان عوض بشه صدا زده می‌شه
        public static event Action LanguageChanged;

        // زبان رو عوض و ذخیره می‌کنه
        public static void SetLanguage(string lang)
        {
            string value = (lang == En) ? En : Fa;
            if (value == Lang)
            {
                return;
            }
            current = value;
            AppSettings.Set("Language", value);
            if (LanguageChanged != null)
            {
                LanguageChanged();
            }
        }

        // یک بار موقع شروع برنامه صدا زده می‌شه
        public static void Init()
        {
            if (inited)
            {
                return;
            }
            inited = true;
            // هر کنترلی که لود می‌شه (حتی داخل قالب کارت‌ها) اگه انگلیسی باشه ترجمه می‌شه
            EventManager.RegisterClassHandler(typeof(FrameworkElement), FrameworkElement.LoadedEvent, new RoutedEventHandler(OnAnyLoaded));
        }

        // ترجمه‌ی یک متن (کلید = همون متن فارسی)
        public static string T(string fa)
        {
            if (!IsEnglish || string.IsNullOrEmpty(fa))
            {
                return fa;
            }
            EnsureMap();
            string en;
            return map.TryGetValue(Norm(fa), out en) ? en : fa;
        }

        // ترجمه‌ی یک قالب که جاهای {0} و {1} داره، بعد پر کردنش
        public static string F(string fa, params object[] args)
        {
            return string.Format(T(fa), args);
        }

        // مثل F ولی برای شمارش (انگلیسی مفرد/جمع داره). توی ترجمه‌ی انگلیسی "مفرد|جمع" نوشته می‌شه
        public static string FN(string fa, int n, params object[] args)
        {
            string s = T(fa);
            if (IsEnglish)
            {
                int bar = s.IndexOf('|');
                if (bar >= 0)
                {
                    s = (n == 1) ? s.Substring(0, bar) : s.Substring(bar + 1);
                }
            }
            return string.Format(s, args);
        }

        // نقل‌قول: «متن» در فارسی و “text” در انگلیسی
        public static string Q(string s)
        {
            return IsEnglish ? "\u201C" + s + "\u201D" : "\u00AB" + s + "\u00BB";
        }

        // جداکننده‌ی لیست‌ها: «، » در فارسی و ", " در انگلیسی
        public static string ListSeparator
        {
            get { return IsEnglish ? ", " : "\u060C "; }
        }

        // «و» بین دو بخش (با فاصله‌ی دو طرفش)
        public static string And
        {
            get { return IsEnglish ? " and " : " \u0648 "; }
        }

        // آماده‌سازی یک پنجره‌ی XAML بعد از InitializeComponent: جهت صفحه و متن‌های ثابت
        public static void Prepare(Window window)
        {
            if (window == null || !IsEnglish)
            {
                return;
            }
            window.FlowDirection = FlowDirection.LeftToRight;
            Translate(window);
        }

        // همه‌ی متن‌های درخت رو (به زبان فعلی) دوباره تنظیم می‌کنه؛ موقع عوض شدن زبان هم از همین استفاده می‌شه
        public static void Translate(DependencyObject root)
        {
            if (root == null)
            {
                return;
            }
            EnsureMap();
            Walk(root, new HashSet<DependencyObject>(), 0);
        }

        private static void EnsureMap()
        {
            if (map == null)
            {
                Dictionary<string, string> built = new Dictionary<string, string>();
                foreach (KeyValuePair<string, string> kv in LocStrings.Build())
                {
                    built[Norm(kv.Key)] = kv.Value;
                }
                map = built;
            }
        }

        // فرق نیم‌فاصله، ی و ک عربی و اعراب رو نادیده می‌گیره تا کلیدها دقیقاً یکی بشن
        private static string Norm(string s)
        {
            if (string.IsNullOrEmpty(s))
            {
                return s;
            }
            StringBuilder sb = new StringBuilder(s.Length);
            foreach (char ch in s)
            {
                if (ch == '\u200c' || ch == '\u200d' || (ch >= '\u064B' && ch <= '\u065F'))
                {
                    continue;
                }
                if (ch == '\u064A')
                {
                    sb.Append('\u06CC');
                }
                else if (ch == '\u0643')
                {
                    sb.Append('\u06A9');
                }
                else
                {
                    sb.Append(ch);
                }
            }
            return sb.ToString().Trim();
        }

        // ترجمه‌ی انگلیسیِ یک کلید، بدون توجه به زبان فعلی
        private static string English(string fa)
        {
            string en;
            return map.TryGetValue(Norm(fa), out en) ? en : fa;
        }

        private static bool HasKey(string text)
        {
            return !string.IsNullOrEmpty(text) && map.ContainsKey(Norm(text));
        }

        private static void OnAnyLoaded(object sender, RoutedEventArgs e)
        {
            if (!IsEnglish)
            {
                return;
            }
            DependencyObject d = sender as DependencyObject;
            if (d == null)
            {
                return;
            }
            try
            {
                EnsureMap();
                TranslateOne(d);
                TextBlock tbox = d as TextBlock;
                if (tbox != null)
                {
                    FixTextDirection(tbox);
                }
                Window w = d as Window;
                if (w != null && w.FlowDirection == FlowDirection.RightToLeft)
                {
                    w.FlowDirection = FlowDirection.LeftToRight;
                }
            }
            catch
            {
            }
        }

        private static void Walk(DependencyObject d, HashSet<DependencyObject> seen, int depth)
        {
            if (d == null || depth > 250 || !seen.Add(d))
            {
                return;
            }
            TranslateOne(d);
            if (d is Visual)
            {
                int count = VisualTreeHelper.GetChildrenCount(d);
                for (int i = 0; i < count; i++)
                {
                    Walk(VisualTreeHelper.GetChild(d, i), seen, depth + 1);
                }
            }
            // کنترل‌هایی که فقط توی درخت منطقی هستن (مثل آیتم‌های ComboBox)
            if (d is FrameworkElement || d is FrameworkContentElement)
            {
                foreach (object child in LogicalTreeHelper.GetChildren(d))
                {
                    DependencyObject dc = child as DependencyObject;
                    if (dc != null)
                    {
                        Walk(dc, seen, depth + 1);
                    }
                }
            }
        }

        // متن مناسب زبان فعلی برای یک متن. اگه این متن قابل ترجمه نباشه null برمی‌گردونه
        private static string Resolve(DependencyObject d, DependencyProperty keyProperty, string currentText)
        {
            string key = (string)d.GetValue(keyProperty);
            if (key != null && currentText != key && currentText != English(key))
            {
                // متن بعداً از جای دیگه‌ای (کد برنامه) عوض شده؛ دیگه به کلید قبلی ربطی نداره
                key = null;
                d.ClearValue(keyProperty);
            }
            if (key == null)
            {
                if (!HasKey(currentText))
                {
                    return null;
                }
                key = currentText;
                d.SetValue(keyProperty, key);
            }
            return T(key);
        }

        private static void TranslateOne(DependencyObject d)
        {
            Window window = d as Window;
            if (window != null)
            {
                string title = Resolve(window, TextKeyProperty, window.Title);
                if (title != null && title != window.Title)
                {
                    window.Title = title;
                }
            }

            TextBlock tb = d as TextBlock;
            if (tb != null)
            {
                if (!BindingOperations.IsDataBound(tb, TextBlock.TextProperty) && IsPlainText(tb))
                {
                    string text = Resolve(tb, TextKeyProperty, tb.Text);
                    if (text != null && text != tb.Text)
                    {
                        tb.Text = text;
                    }
                }
            }
            else
            {
                ContentControl cc = d as ContentControl;
                if (cc != null && !(d is Window))
                {
                    string content = cc.Content as string;
                    if (content != null && !BindingOperations.IsDataBound(cc, ContentControl.ContentProperty))
                    {
                        string text = Resolve(cc, TextKeyProperty, content);
                        if (text != null && text != content)
                        {
                            cc.Content = text;
                        }
                    }
                }
                HeaderedItemsControl hic = d as HeaderedItemsControl;
                if (hic != null)
                {
                    string header = hic.Header as string;
                    if (header != null && !BindingOperations.IsDataBound(hic, HeaderedItemsControl.HeaderProperty))
                    {
                        string text = Resolve(hic, TextKeyProperty, header);
                        if (text != null && text != header)
                        {
                            hic.Header = text;
                        }
                    }
                }
            }

            FrameworkElement fe = d as FrameworkElement;
            if (fe != null)
            {
                string tip = fe.ToolTip as string;
                if (tip != null && !BindingOperations.IsDataBound(fe, FrameworkElement.ToolTipProperty))
                {
                    string text = Resolve(fe, TipKeyProperty, tip);
                    if (text != null && text != tip)
                    {
                        fe.ToolTip = text;
                    }
                }
            }
        }

        // توی حالت انگلیسی صفحه چپ‌به‌راسته؛ ولی متن فارسی (اسم کتاب، نویسنده، فصل و ...) باید راست‌به‌چپ نمایش داده بشه
        public static void FixTextDirection(TextBlock tb)
        {
            if (tb == null || !IsEnglish)
            {
                return;
            }
            string text = tb.Text;
            if (string.IsNullOrEmpty(text))
            {
                return;
            }
            int persian = 0;
            int latin = 0;
            foreach (char ch in text)
            {
                if (ch >= '\u0600' && ch <= '\u06FF')
                {
                    persian++;
                }
                else if ((ch >= 'A' && ch <= 'Z') || (ch >= 'a' && ch <= 'z'))
                {
                    latin++;
                }
            }
            if (persian > latin)
            {
                tb.FlowDirection = FlowDirection.RightToLeft;
            }
        }

        // TextBlock فقط یک Run ساده داشته باشه (نه چند تکه‌ی رنگی)
        private static bool IsPlainText(TextBlock tb)
        {
            if (tb.Inlines.Count == 0)
            {
                return true;
            }
            return tb.Inlines.Count == 1 && tb.Inlines.FirstInline is Run;
        }
    }
}
