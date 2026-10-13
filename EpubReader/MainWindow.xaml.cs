using System;
using System.Collections;
using System.Collections.Generic;
using System.Diagnostics;
using System.IO;
using System.Net;
using System.Text;
using System.Text.RegularExpressions;
using System.Threading.Tasks;
using System.Windows;
using System.Windows.Controls;
using System.Windows.Input;
using System.Windows.Markup;
using System.Windows.Media;
using System.Windows.Media.Animation;
using System.Windows.Threading;
using EpubReader.Models;
using EpubReader.Services;
using Microsoft.Web.WebView2.Core;
using Microsoft.Win32;

namespace EpubReader
{

    public partial class MainWindow : Window
    {
        private EpubBook currentBook;

        private Book currentLibraryBook;

        private bool continuousMode = false;

        private bool isProgrammaticSelection = false;

        private bool isAppendingChapter = false;

        // شماره‌ی نسخه‌ی صفحه‌ی فعلی + اولین و آخرین فصلی که توی صفحه بارگذاری شده (برای حالت پیوسته)
        private int docGen = 0;

        private int contFirst = 0;

        private int contLast = 0;

        private DispatcherTimer scrollCheckTimer;

        // یادآوری حمایت: هر ۳۰ دقیقه خواندن (ثانیه‌ها بین دفعه‌ها ذخیره می‌شود)
        private const int SupportIntervalSeconds = 30 * 60;

        private DispatcherTimer supportTimer;

        private int supportSeconds = 0;

        // true یعنی متن فصلِ در حال خواندن لاتین/چپ‌به‌راست است (برای جهت ویجت حمایت)
        private bool readerTextIsLtr = false;

        private int supportSaveTick = 0;

        private int currentFontSize = 18;

        private string currentFontFamily = "Tahoma";

        private string currentTheme = "dark";

        private System.Windows.Media.Effects.Effect savedReaderEffect;

        private class ReaderTheme
        {
            public string Key;
            private Func<string> nameFn;
            public string Bg;
            public string Text;
            public string ScrollThumb;
            public string ScrollTrack;
            public string Divider;
            public bool IsDark;

            // اسم تم به زبان انتخاب‌شده
            public string Name { get { return nameFn(); } }

            public ReaderTheme(string key, string name, string bg, string text, string thumb, string track, string divider, bool isDark)
                : this(key, () => Loc.T(name), bg, text, thumb, track, divider, isDark)
            {
            }

            public ReaderTheme(string key, Func<string> nameFn, string bg, string text, string thumb, string track, string divider, bool isDark)
            {
                Key = key;
                this.nameFn = nameFn;
                Bg = bg;
                Text = text;
                ScrollThumb = thumb;
                ScrollTrack = track;
                Divider = divider;
                IsDark = isDark;
            }
        }

        // ---- رنگ‌های پس‌زمینه خوانش: از اینجا می‌تونی رنگ اضافه/کم کنی ----
        private static readonly ReaderTheme[] Themes = new ReaderTheme[]
        {
        new ReaderTheme("dark",  "تیره",     "#1C1A2A", "#E4E1F0", "#A78BFA", "#1C1A29", "#2A2838", true),
        new ReaderTheme("white", "سفید",     "#FFFFFF", "#000000", "#C0C0C0", "#F0F0F0", "#EEEEEE", false),
        new ReaderTheme("cream", "کرمی",     "#F4ECD8", "#3B2F1E", "#C9B48A", "#EADFC4", "#D9CCAA", false),
        new ReaderTheme("black", "مشکی",     "#000000", "#C8C8C8", "#555555", "#111111", "#222222", true),
        new ReaderTheme("gray",  "خاکستری",  "#2F3237", "#E0E0E0", "#7A7F87", "#26282C", "#41454B", true),
        new ReaderTheme("green", "سبز ملایم", "#DCE8D5", "#1F2D1F", "#9DB594", "#CFDDC7", "#BCCFB2", false)
        };

        // تم صفحه‌ی خواندن برای هر شخصیت (شب و روز) از روی پالت خودش ساخته می‌شه
        private static readonly ReaderTheme[] CharacterPages = BuildCharacterPages();

        private static ReaderTheme[] BuildCharacterPages()
        {
            List<ReaderTheme> list = new List<ReaderTheme>();
            foreach (CharacterTheme c in CharacterThemes.All)
            {
                list.Add(new ReaderTheme(c.PageKeyNight, () => Loc.F("{0} (شب)", c.Name), c.Dark.Card, c.Dark.Text, c.Dark.Accent, c.Dark.Sidebar, c.Dark.Border, true));
                list.Add(new ReaderTheme(c.PageKeyDay, () => Loc.F("{0} (روز)", c.Name), c.Light.Hero1, c.Light.Text, c.Light.Accent, c.Light.Hover, c.Light.Border, false));
            }
            return list.ToArray();
        }

        private static ReaderTheme FindTheme(string key)
        {
            foreach (ReaderTheme t in Themes)
            {
                if (t.Key == key)
                {
                    return t;
                }
            }
            foreach (ReaderTheme t in CharacterPages)
            {
                if (t.Key == key)
                {
                    return t;
                }
            }
            return null;
        }

        private ReaderTheme GetCurrentTheme()
        {
            ReaderTheme found = FindTheme(currentTheme);
            return found != null ? found : Themes[0];
        }

        // وقتی تم شخصیت فعاله، صفحه‌ی «تیره/سفید» به شب/روزِ همون شخصیت تبدیل می‌شه؛ بقیه‌ی رنگ‌ها دست نمی‌خورن
        private ReaderTheme GetThemeForBook(Book book)
        {
            string key = book.Theme;
            CharacterTheme ch = ThemeManager.ActiveCharacter;
            if (string.IsNullOrEmpty(key))
            {
                key = ThemeManager.IsLight ? "white" : "dark";
            }
            if (ch != null)
            {
                if (key == "dark" || (key.StartsWith("rz.") && key.EndsWith(".night")))
                {
                    key = ch.PageKeyNight;
                }
                else if (key == "white" || (key.StartsWith("rz.") && key.EndsWith(".day")))
                {
                    key = ch.PageKeyDay;
                }
            }
            else if (key.StartsWith("rz."))
            {
                key = key.EndsWith(".night") ? "dark" : "white";
            }
            ReaderTheme found = FindTheme(key);
            return found != null ? found : Themes[0];
        }

        private static Brush HexBrush(string hex)
        {
            return (Brush)new BrushConverter().ConvertFromString(hex);
        }

        private void BuildThemeButtons()
        {
            ThemePanel.Children.Clear();
            List<ReaderTheme> dots = new List<ReaderTheme>();
            CharacterTheme activeChar = ThemeManager.ActiveCharacter;
            if (activeChar != null)
            {
                dots.Add(FindTheme(activeChar.PageKeyNight));
                dots.Add(FindTheme(activeChar.PageKeyDay));
            }
            foreach (ReaderTheme bt in Themes)
            {
                if (activeChar != null && (bt.Key == "dark" || bt.Key == "white"))
                {
                    continue;
                }
                dots.Add(bt);
            }
            foreach (ReaderTheme t in dots)
            {
                System.Windows.Shapes.Ellipse dot = new System.Windows.Shapes.Ellipse();
                dot.Width = 22.0;
                dot.Height = 22.0;
                dot.Margin = new Thickness(3.0, 0.0, 3.0, 0.0);
                dot.Fill = HexBrush(t.Bg);
                dot.Stroke = HexBrush("#4C4766");
                dot.StrokeThickness = 1.5;
                dot.Tag = t.Key;
                dot.ToolTip = t.Name;
                dot.Cursor = Cursors.Hand;
                dot.MouseLeftButtonUp += ThemeDot_MouseLeftButtonUp;
                ThemePanel.Children.Add(dot);
            }
        }

        private void ThemeDot_MouseLeftButtonUp(object sender, MouseButtonEventArgs e)
        {
            FrameworkElement el = sender as FrameworkElement;
            string key = (el != null) ? (el.Tag as string) : null;
            if (key == null || key == currentTheme)
            {
                return;
            }
            currentTheme = key;
            UpdateDarkModeButton();
            ReloadCurrentChapterWithNewStyle();
            SaveBookSettings();
        }

        // کلیدهای میانبر شخصی وقتی فوکوس داخل مرورگر کتاب باشه از اینجا میاد
        public bool HandleBrowserCombo(int keyCode, bool ctrl, bool shift, bool alt)
        {
            ModifierKeys mods = ModifierKeys.None;
            if (ctrl) mods |= ModifierKeys.Control;
            if (shift) mods |= ModifierKeys.Shift;
            if (alt) mods |= ModifierKeys.Alt;
            string combo = ShortcutService.FromKey(KeyInterop.KeyFromVirtualKey(keyCode), mods);
            string id = ShortcutService.ResolveCombo(combo, "rd.");
            if (id == null)
            {
                return false;
            }
            Dispatcher.BeginInvoke(new Action(() => RunReaderAction(id)));
            return true;
        }

        private void RunReaderAction(string id)
        {
            RoutedEventArgs args = new RoutedEventArgs();
            switch (id)
            {
                case "rd.next":
                    btnNextChapter_Click(btnNextChapter, args);
                    break;
                case "rd.prev":
                    btnPrevChapter_Click(btnPrevChapter, args);
                    break;
                case "rd.fontUp":
                    btnFontIncrease_Click(btnFontIncrease, args);
                    break;
                case "rd.fontDown":
                    btnFontDecrease_Click(btnFontDecrease, args);
                    break;
                case "rd.addFont":
                    btnAddFont_Click(btnAddFont, args);
                    break;
                case "rd.spacing":
                    popupSpacing.IsOpen = !popupSpacing.IsOpen;
                    break;
                case "rd.fullscreen":
                    ToggleFullScreen();
                    break;
                case "rd.continuous":
                    btnContinuousMode_Click(btnContinuousMode, args);
                    break;
                case "rd.scrollUp":
                    ScrollReaderPage(-1);
                    break;
                case "rd.scrollDown":
                    ScrollReaderPage(1);
                    break;
                case "rd.openFile":
                    btnOpen_Click(btnOpen, args);
                    break;
                case "rd.maximize":
                    if (!isFullScreen)
                    {
                        WindowMax.Toggle(this);
                    }
                    break;
                case "rd.minimize":
                    btnMinimize_Click(btnMinimize, args);
                    break;
                case "rd.close":
                    btnClose_Click(btnClose, args);
                    break;
            }
        }

        private void ScrollReaderPage(int direction)
        {
            try
            {
                string js = "(function(){var d=document.documentElement,b=document.body;var h=d.clientHeight||b.clientHeight||0;var v=Math.max(d.scrollTop||0,b.scrollTop||0)+(" + direction + ")*Math.round(h*0.8);if(v<0)v=0;d.scrollTop=v;b.scrollTop=v;})();";
                RunScriptAsync(js);
            }
            catch
            {
            }
        }

        // کلید F11 / Esc وقتی فوکوس داخل خود مرورگر کتاب باشه از اینجا میاد
        public void HandleBrowserKey(int keyCode)
        {
            if (keyCode == 122)
            {
                ToggleFullScreen();
            }
            else if (keyCode == 27 && isFullScreen)
            {
                ToggleFullScreen();
            }
        }

        private const int MinFontSize = 10;

        private const int MaxFontSize = 72;

        private bool isLoadingBookSettings = false;

        private bool isFullScreen = false;

        private WindowState previousWindowState = WindowState.Normal;

        public MainWindow()
        {
            InitializeComponent();
            Loc.Prepare(this);
            WindowMax.Attach(this, () => isFullScreen);
            StateChanged += (object s, EventArgs e) => ApplySpacingLive();
            PopulateFontList();
            LoadSpacingSettings();

            InitWebViewHooks();
            BuildThemeButtons();
            UpdateDarkModeButton();
            StartSupportReminder();
            Closed += (object s, EventArgs e) =>
            {
                StopScrollWatcher();
                StopSupportReminder();
                ShutdownWebView();
                CleanupCurrentBook();
            };
        }

        private void StartSupportReminder()
        {
            int saved;
            if (!int.TryParse(AppSettings.Get("supportSeconds", "0"), out saved) || saved < 0)
            {
                saved = 0;
            }
            supportSeconds = saved;
            supportTimer = new DispatcherTimer();
            supportTimer.Interval = TimeSpan.FromSeconds(10);
            supportTimer.Tick += SupportTimer_Tick;
            supportTimer.Start();
        }

        private void StopSupportReminder()
        {
            if (supportTimer != null)
            {
                supportTimer.Stop();
                supportTimer = null;
            }
            SaveSupportSeconds();
        }

        private void SaveSupportSeconds()
        {
            AppSettings.Set("supportSeconds", supportSeconds.ToString());
            AppSettings.Save();
        }

        // فقط وقتی کتاب باز است، پنجره فعال است و کوچک نشده، زمان خواندن حساب می‌شود
        private void SupportTimer_Tick(object sender, EventArgs e)
        {
            if (currentBook == null || !IsActive || WindowState == WindowState.Minimized || SupportToast.Current != null)
            {
                return;
            }
            supportSeconds += 10;
            if (supportSeconds >= SupportIntervalSeconds)
            {
                supportSeconds = 0;
                SaveSupportSeconds();
                supportSaveTick = 0;
                SupportToast.ShowFor(this, ReaderCard, readerTextIsLtr);
                return;
            }
            supportSaveTick++;
            if (supportSaveTick >= 6)
            {
                supportSaveTick = 0;
                SaveSupportSeconds();
            }
        }

        private void CleanupCurrentBook()
        {
            if (currentBook != null && !string.IsNullOrEmpty(currentBook.ExtractPath))
            {
                try
                {
                    Directory.Delete(currentBook.ExtractPath, recursive: true);
                }
                catch
                {
                }
            }
        }

        public bool OpenBook(Book book, EpubBook preloaded = null)
        {
            try
            {
                CleanupCurrentBook();
                currentLibraryBook = book;
                currentBook = preloaded ?? EpubParser.Load(book.FilePath);
                LibraryService.LogOpen("[Reader] loaded: " + book.FilePath + " | extract=" + currentBook.ExtractPath + " | chapters=" + currentBook.Chapters.Count + " | startIndex=" + book.LastChapterIndex);
                if (!string.IsNullOrEmpty(currentBook.CoverImagePath))
                {
                    currentLibraryBook.CoverImagePath = currentBook.CoverImagePath;
                }
                string shownTitle = string.IsNullOrEmpty(book.DisplayTitle) ? currentBook.Title : book.DisplayTitle;
                txtBookTitle.Text = shownTitle;
                ApplyTitleDirection(txtBookTitle, shownTitle ?? "");
                listBoxChapters.Items.Clear();
                foreach (ChapterItem chapter in currentBook.Chapters)
                {
                    ListBoxItem chapterEntry = new ListBoxItem();
                    chapterEntry.Content = chapter.Title;
                    chapterEntry.FlowDirection = IsLatinText(chapter.Title ?? "") ? FlowDirection.LeftToRight : FlowDirection.RightToLeft;
                    listBoxChapters.Items.Add(chapterEntry);
                }
                LoadBookSettings(book);
                int startIndex = book.LastChapterIndex;
                if (startIndex < 0 || startIndex >= listBoxChapters.Items.Count)
                {
                    startIndex = 0;
                }
                if (listBoxChapters.Items.Count > 0)
                {
                    listBoxChapters.SelectedIndex = startIndex;
                }
                return true;
            }
            catch (Exception ex)
            {
                DarkMessageBox.Show(Loc.T("خطا در باز کردن کتاب:\n") + ex.Message, "خطا", MessageBoxButton.OK, MessageBoxImage.Hand);
                return false;
            }
        }

        private void LoadBookSettings(Book book)
        {
            isLoadingBookSettings = true;
            currentFontSize = ((book.FontSize > 0) ? book.FontSize : 18);
            currentFontFamily = (string.IsNullOrEmpty(book.FontFamily) ? "Tahoma" : book.FontFamily);
            currentTheme = GetThemeForBook(book).Key;
            BuildThemeButtons();
            txtFontSize.Text = currentFontSize.ToString();
            UpdateDarkModeButton();
            RefreshFontList();
            LoadSpacingForBook(book);
            isLoadingBookSettings = false;
        }

        private void SaveBookSettings()
        {
            if (currentLibraryBook != null)
            {
                currentLibraryBook.FontFamily = currentFontFamily;
                currentLibraryBook.FontSize = currentFontSize;
                currentLibraryBook.Theme = currentTheme;
                currentLibraryBook.IsDarkMode = GetCurrentTheme().IsDark;
                LibraryService.AddOrUpdateBook(currentLibraryBook);
            }
        }

        // کتابی که از داخل صفحه‌ی خوانش باز می‌شه هم باید توی کتابخونه ثبت بشه
        private Book GetOrCreateLibraryBook(string filePath)
        {
            System.Collections.Generic.List<Book> all = LibraryService.LoadBooks();
            Book existing = all.Find((Book b) => string.Equals(b.FilePath, filePath, StringComparison.OrdinalIgnoreCase));
            if (existing != null)
            {
                return existing;
            }

            EpubBook epub = EpubParser.Load(filePath);
            try
            {
                Book book = new Book();
                book.Id = Guid.NewGuid().ToString("N");
                book.Title = epub.Title;
                book.FilePath = filePath;
                book.CoverImagePath = epub.CoverImagePath;
                book.TotalChapters = epub.Chapters.Count;
                book.LastChapterIndex = 0;
                book.DateAdded = DateTime.Now.ToString("yyyy-MM-dd");
                LibraryService.AddOrUpdateBook(book);
            }
            finally
            {
                try
                {
                    Directory.Delete(epub.ExtractPath, true);
                }
                catch
                {
                }
            }

            // دوباره از فایل بخون تا مقدارهای پیش‌فرض (فونت، سایز، رنگ) درست پر بشن
            all = LibraryService.LoadBooks();
            Book saved = all.Find((Book b) => string.Equals(b.FilePath, filePath, StringComparison.OrdinalIgnoreCase));
            if (saved == null)
            {
                throw new InvalidOperationException("افزودن کتاب به کتابخونه انجام نشد.");
            }
            return saved;
        }

        private void btnOpen_Click(object sender, RoutedEventArgs e)
        {
            OpenFileDialog openFileDialog = new OpenFileDialog();
            openFileDialog.Filter = Loc.T("فایل\u200cهای EPUB (*.epub)") + "|*.epub";
            openFileDialog.Title = Loc.T("انتخاب فایل EPUB");
            OpenFileDialog ofd = openFileDialog;
            if (ofd.ShowDialog() != true)
            {
                return;
            }
            try
            {
                Book libraryBook = GetOrCreateLibraryBook(ofd.FileName);
                LibraryService.MarkAsOpened(libraryBook.FilePath);
                libraryBook.LastOpenedDate = DateTime.Now.ToString("yyyy-MM-dd HH:mm:ss");
                OpenBook(libraryBook);
            }
            catch (Exception ex)
            {
                DarkMessageBox.Show(Loc.T("خطا در باز کردن فایل:\n") + ex.Message, "خطا", MessageBoxButton.OK, MessageBoxImage.Hand);
            }
        }

        private void TitleBar_MouseLeftButtonDown(object sender, MouseButtonEventArgs e)
        {
            if (isFullScreen)
            {
                return;
            }
            if (e.ClickCount == 2)
            {
                WindowMax.Toggle(this);
                return;
            }
            if (WindowState == WindowState.Maximized)
            {
                return;
            }
            DragMove();
        }

        private void btnMaximize_Click(object sender, RoutedEventArgs e)
        {
            if (!isFullScreen)
            {
                WindowMax.Toggle(this);
            }
        }

        private void btnMinimize_Click(object sender, RoutedEventArgs e)
        {
            WindowState = WindowState.Minimized;
        }

        private void btnClose_Click(object sender, RoutedEventArgs e)
        {
            Close();
        }

        private void btnNextChapter_Click(object sender, RoutedEventArgs e)
        {
            if (currentBook != null && listBoxChapters.SelectedIndex < listBoxChapters.Items.Count - 1)
            {
                listBoxChapters.SelectedIndex++;
            }
        }

        private void btnPrevChapter_Click(object sender, RoutedEventArgs e)
        {
            if (currentBook != null && listBoxChapters.SelectedIndex > 0)
            {
                listBoxChapters.SelectedIndex--;
            }
        }

        private void listBoxChapters_SelectionChanged(object sender, SelectionChangedEventArgs e)
        {
            if (currentBook != null && listBoxChapters.SelectedIndex >= 0 && !isProgrammaticSelection)
            {
                ChapterItem chapter = currentBook.Chapters[listBoxChapters.SelectedIndex];
                txtChapterTitle.Text = chapter.Title;
                txtChapterTitle.FlowDirection = Loc.Flow;
                Loc.FixTextDirection(txtChapterTitle);
                ApplyTitleDirection(txtChapterTitle, chapter.Title ?? "");
                LoadChapterContent(chapter.FilePath);
                txtChapterPosition.Text = Loc.F("{0} از {1}", listBoxChapters.SelectedIndex + 1, listBoxChapters.Items.Count);
                SaveReadingProgress(listBoxChapters.SelectedIndex);
            }
        }

        private void SaveReadingProgress(int chapterIndex)
        {
            if (currentLibraryBook != null)
            {
                currentLibraryBook.LastChapterIndex = chapterIndex;
                LibraryService.AddOrUpdateBook(currentLibraryBook);
            }
        }

        private void LoadChapterContent(string filePath)
        {
            try
            {
                LibraryService.LogOpen("[Reader] chapter file: " + filePath);
                string html;
                using (StreamReader reader = new StreamReader(filePath, Encoding.UTF8, detectEncodingFromByteOrderMarks: true))
                {
                    html = reader.ReadToEnd();
                }
                string bodyContent = PrepareChapterBody(html, null);
                int chapterIdx = listBoxChapters.SelectedIndex;
                docGen++;
                contFirst = chapterIdx;
                contLast = chapterIdx;
                bodyContent = "<div class=\"rdr-chap\" data-idx=\"" + chapterIdx + "\"" + ChapterDirAttr(bodyContent) + ">" + bodyContent + "</div>";
                string folderPath = Path.GetDirectoryName(filePath);
                string baseUri = new Uri(folderPath + Path.DirectorySeparatorChar).AbsoluteUri;
                string styledHtml = BuildStyledHtml(baseUri, bodyContent);
                ShowHtmlPage(styledHtml);
                AnimateContentFadeIn();
            }
            catch (Exception ex)
            {
                DarkMessageBox.Show(Loc.T("خطا در نمایش فصل:\n") + ex.Message, "خطا", MessageBoxButton.OK, MessageBoxImage.Hand);
            }
        }

        // آخرین فونتی که توی صفحه‌ی فعلی تعریف شده (برای اینکه موقع تغییر اندازه‌ی فونت، فایل فونت دوباره فرستاده نشه)
        private string pageFontFamily = "";

        private static string CssQuote(string s)
        {
            return (s ?? "").Replace("\\", "\\\\").Replace("'", "\\'");
        }

        private string BuildStyledHtml(string baseUri, string bodyContent)
        {
            ReaderTheme theme = GetCurrentTheme();
            string bgColor = theme.Bg;
            string textColor = theme.Text;
            pageFontFamily = currentFontFamily;
            StringBuilder sb = new StringBuilder();
            sb.Append("<!DOCTYPE html><html><head><meta charset=\"utf-8\"><base href=\"").Append(baseUri).Append("\">");
            sb.Append("<style>html,body{margin:0;padding:0;background-color:").Append(bgColor).Append(";scrollbar-width:none;overflow-anchor:none;}");
            sb.Append("html::-webkit-scrollbar,body::-webkit-scrollbar{width:0;height:0;display:none;}");
            sb.Append(Loc.IsEnglish ? "html{direction:ltr;}body{direction:ltr;text-align:left;font-family:'" : "body{direction:rtl;text-align:right;font-family:'").Append(CssQuote(currentFontFamily)).Append("',Tahoma,sans-serif;font-size:").Append(currentFontSize);
            sb.Append("px;line-height:1.8;padding:16px 24px;background-color:").Append(bgColor).Append(";color:").Append(textColor).Append(";animation:rdrFade .3s ease-out;}");
            sb.Append("@keyframes rdrFade{from{opacity:.3;}to{opacity:1;}}");
            sb.Append("body * { color:inherit !important; }[data-rdr=\"l\"]{direction:ltr;text-align:left;}[data-rdr=\"r\"]{direction:rtl;text-align:right;}");
            sb.Append("img{max-width:95% !important;width:auto !important;height:auto !important;display:block;margin:12px auto;}</style>");
            sb.Append("<style id=\"rdrFontFace\">").Append(BuildFontFaceCss(currentFontFamily)).Append("</style>");
            sb.Append("<style id=\"readerSpacing\">").Append(BuildSpacingCss()).Append("</style>");
            sb.Append("<script>window.__rdrGen=").Append(docGen).Append(";window.__rdrKeys=").Append(BuildShortcutKeyMapJs()).Append(";</script>");
            sb.Append("<script>(function(){");
            sb.Append("function post(m){try{window.chrome.webview.postMessage(m);}catch(x){}}");
            sb.Append("document.addEventListener('keydown',function(e){var k=e.keyCode;");
            sb.Append("if(k==27||k==122){e.preventDefault();post('k|'+k);return;}");
            sb.Append("if(k==16||k==17||k==18){return;}");
            sb.Append("var cc=e.ctrlKey?1:0,ss=e.shiftKey?1:0,aa=e.altKey?1:0;");
            sb.Append("if(window.__rdrKeys&&window.__rdrKeys[k+':'+cc+ss+aa]){e.preventDefault();post('c|'+k+'|'+cc+'|'+ss+'|'+aa);}");
            sb.Append("},true);");
            sb.Append("var lt=-1,lh=-1,lc=-1,lr=-1;");
            sb.Append("function r(){try{var d=document.documentElement,b=document.body;var t=Math.round(Math.max(d.scrollTop||0,b.scrollTop||0)),h=Math.max(d.scrollHeight||0,b.scrollHeight||0),c=d.clientHeight||b.clientHeight||0;");
            sb.Append("if(t!=lt||h!=lh||c!=lc){lt=t;lh=h;lc=c;post('s|'+t+'|'+h+'|'+c);}else if(h>c){lr=t/(h-c);}}catch(x){}}");
            sb.Append("function z(){try{if(lr>=0){var d=document.documentElement,b=document.body;var h=Math.max(d.scrollHeight||0,b.scrollHeight||0),c=d.clientHeight||b.clientHeight||0;var nt=Math.round(lr*Math.max(h-c,0));d.scrollTop=nt;b.scrollTop=nt;}}catch(x){}r();}");
            sb.Append("window.addEventListener('scroll',r);window.addEventListener('resize',z);setInterval(r,200);");
            sb.Append("})();</script></head><body>").Append(bodyContent).Append("</body></html>");
            return sb.ToString();
        }

        // انیمیشن ظاهر شدن حالا داخل خود صفحه (CSS) انجام می‌شه؛ روی WebView2 انیمیشن Opacity اثر نداره
        private void AnimateContentFadeIn()
        {
        }

        // اسکریپت‌های داخل خود کتاب اجرا نشن (امنیت و جلوگیری از پنجره‌ی خطای اسکریپت)
        private static string StripScripts(string html)
        {
            return Regex.Replace(html, "<script[\\s\\S]*?</script\\s*>", "", RegexOptions.IgnoreCase);
        }

        // ---- آماده‌سازی متن فصل: عکس‌ها و اندازه‌ها ----
        private static readonly System.Collections.Generic.HashSet<string> svgTagNames = new System.Collections.Generic.HashSet<string>(StringComparer.OrdinalIgnoreCase)
        {
            "svg", "image", "use", "symbol", "pattern", "mask", "g", "defs", "rect", "circle", "ellipse",
            "line", "polygon", "polyline", "path", "clippath", "filter", "foreignobject"
        };

        private string PrepareChapterBody(string html, string absoluteFolder)
        {
            string body = StripScripts(ExtractBody(html));
            body = ConvertSvgImages(body);
            body = Regex.Replace(body, "<(?![/!?])([a-zA-Z][a-zA-Z0-9:_-]*)[^>]*>", StripSizeAttributes);
            if (absoluteFolder != null)
            {
                body = MakeImageSourcesAbsolute(body, absoluteFolder);
            }
            int bodyDir = DetectDirection(HtmlToPlainText(body));
            if (bodyDir != 0)
            {
                readerTextIsLtr = (bodyDir == 1);
            }
            body = ApplyTextDirection(body);
            return body;
        }

        // از همه‌ی تگ‌ها width و height رو برمی‌داره، به جز تگ‌های SVG (وگرنه عکس‌های SVG محو می‌شن)
        private static string StripSizeAttributes(Match m)
        {
            string name = m.Groups[1].Value;
            if (svgTagNames.Contains(name))
            {
                return m.Value;
            }
            return Regex.Replace(m.Value, "\\s(width|height)\\s*=\\s*(\"[^\"]*\"|'[^']*'|[^\\s>]+)", "", RegexOptions.IgnoreCase);
        }

        // جلد یا عکس‌هایی که با <svg><image/></svg> گذاشته شدن رو به <img> ساده تبدیل می‌کنه
        private static string ConvertSvgImages(string html)
        {
            return Regex.Replace(
                html,
                "<svg\\b[^>]*>\\s*(?:<g\\b[^>]*>\\s*)?<image\\b[^>]*?>\\s*(?:</image>\\s*)?(?:</g>\\s*)?</svg>",
                delegate (Match m)
                {
                    Match href = Regex.Match(m.Value, "<image\\b[^>]*?\\b(?:xlink:)?href\\s*=\\s*[\"']([^\"']+)[\"']", RegexOptions.IgnoreCase);
                    if (!href.Success)
                    {
                        return m.Value;
                    }
                    return "<img src=\"" + href.Groups[1].Value + "\" />";
                },
                RegexOptions.IgnoreCase | RegexOptions.Singleline);
        }

        // مسیر عکس‌ها رو کامل می‌کنه تا فصل‌های پوشه‌های مختلف توی حالت پیوسته عکسشون گم نشه
        private static string MakeImageSourcesAbsolute(string html, string folder)
        {
            Uri baseFolder;
            try
            {
                baseFolder = new Uri(folder + Path.DirectorySeparatorChar);
            }
            catch
            {
                return html;
            }
            return Regex.Replace(
                html,
                "(<img\\b[^>]*?\\ssrc\\s*=\\s*)([\"'])([^\"']*)\\2",
                delegate (Match m)
                {
                    string src = m.Groups[3].Value;
                    string lower = src.ToLowerInvariant();
                    if (lower.Length == 0 || lower.StartsWith("data:") || lower.StartsWith("http:") || lower.StartsWith("https:")
                        || lower.StartsWith("file:") || lower.StartsWith("//") || lower.StartsWith("about:"))
                    {
                        return m.Value;
                    }
                    try
                    {
                        string abs = new Uri(baseFolder, src).AbsoluteUri;
                        return m.Groups[1].Value + m.Groups[2].Value + abs + m.Groups[2].Value;
                    }
                    catch
                    {
                        return m.Value;
                    }
                },
                RegexOptions.IgnoreCase);
        }

        private string ExtractBody(string html)
        {
            int bodyStart = html.IndexOf("<body", StringComparison.OrdinalIgnoreCase);
            int bodyEnd = html.IndexOf("</body>", StringComparison.OrdinalIgnoreCase);
            if (bodyStart >= 0 && bodyEnd > bodyStart)
            {
                int tagCloseIndex = html.IndexOf('>', bodyStart);
                if (tagCloseIndex >= 0 && tagCloseIndex < bodyEnd)
                {
                    return html.Substring(tagCloseIndex + 1, bodyEnd - tagCloseIndex - 1);
                }
            }
            return html;
        }

        private static System.Collections.Generic.List<string> persianFontNames = null;

        private static System.Collections.Generic.List<string> otherFontNames = null;

        // حداکثر تعداد فونتی که توی لیست نشون داده می‌شه (برای سنگین نشدن برنامه). بقیه با جستجو پیدا می‌شن.
        private const int MaxFontItems = 100;

        private void PopulateFontList()
        {
            RefreshFontList();
        }

        // فهرست فونت‌های سیستم یک‌بار ساخته و بین همه‌ی پنجره‌های خوانش نگه داشته می‌شه (تا باز شدن کتاب‌ها سریع‌تر بشه)
        public static void PreloadFonts()
        {
            try
            {
                LoadFontNames();
            }
            catch
            {
            }
        }

        private static void LoadFontNames()
        {
            if (persianFontNames != null)
            {
                return;
            }
            persianFontNames = new System.Collections.Generic.List<string>();
            otherFontNames = new System.Collections.Generic.List<string>();

            string[] persianHints = new string[]
            {
            "Nazanin", "Koodak", "Titr", "Mitra", "Yekan", "Lotus", "Traffic", "Zar", "Homa",
            "Vazir", "IRAN", "Sahel", "Samim", "Shabnam", "Tanha", "Parastoo", "Estedad",
            "Lalezar", "Arabic", "Naskh", "Nastaliq", "Amiri", "Scheherazade", "Lateef",
            "Majalla", "Tahoma"
            };

            foreach (FontFamily family in Fonts.SystemFontFamilies)
            {
                string name = family.Source;
                bool isPersian = name.StartsWith("B ", StringComparison.Ordinal);
                if (!isPersian)
                {
                    foreach (string hint in persianHints)
                    {
                        if (name.IndexOf(hint, StringComparison.OrdinalIgnoreCase) >= 0)
                        {
                            isPersian = true;
                            break;
                        }
                    }
                }
                if (isPersian)
                {
                    persianFontNames.Add(name);
                }
                else
                {
                    otherFontNames.Add(name);
                }
            }

            persianFontNames.Sort(StringComparer.CurrentCultureIgnoreCase);
            otherFontNames.Sort(StringComparer.CurrentCultureIgnoreCase);
        }

        private void RefreshFontList()
        {
            LoadFontNames();

            bool previousLoadingFlag = isLoadingBookSettings;
            isLoadingBookSettings = true;

            string filter = (txtFontSearch == null) ? "" : txtFontSearch.Text.Trim();
            cmbFontFamily.Items.Clear();

            // اول فونت‌های پیشنهادی، بعد فونت‌های شخصی، بعد فونت‌های سیستمی
            bool recommendedSectionShown = AddRecommendedFontsSection(filter);
            bool customSectionShown = AddCustomFontsSection(filter);
            if (customSectionShown || recommendedSectionShown)
            {
                cmbFontFamily.Items.Add(MakeInfoItem("فونت‌های سیستمی", true));
            }

            int count = 0;
            AddFontsToList(persianFontNames, filter, ref count);
            AddFontsToList(otherFontNames, filter, ref count);

            foreach (object entry in (IEnumerable)cmbFontFamily.Items)
            {
                ComboBoxItem item = entry as ComboBoxItem;
                if (item == null || item.Tag == null)
                {
                    continue; // عنوان بخش‌ها و پیام خالی بودن
                }
                if (item.Tag.ToString() == currentFontFamily)
                {
                    cmbFontFamily.SelectedItem = item;
                    break;
                }
            }

            isLoadingBookSettings = previousLoadingFlag;
        }

        /// <summary>
        /// بخش «فونت شخصی» رو بالای لیست می‌ذاره. اگه کاربر فونت شخصی نداشته باشه پیام «فونتی اضافه نشده» نشون می‌ده.
        /// وقتی جستجو فعاله و هیچ فونت شخصی‌ای نمی‌خونه، بخش کلاً نمایش داده نمی‌شه. اگه بخش نشون داده بشه true برمی‌گردونه.
        /// </summary>
        private bool AddCustomFontsSection(string filter)
        {
            System.Collections.Generic.List<CustomFontInfo> available = new System.Collections.Generic.List<CustomFontInfo>();
            foreach (CustomFontInfo f in CustomFontService.GetAll())
            {
                if (f.IsAvailable)
                {
                    available.Add(f);
                }
            }

            if (available.Count == 0)
            {
                if (filter.Length > 0)
                {
                    return false;
                }
                cmbFontFamily.Items.Add(MakeInfoItem("فونت شخصی", true));
                cmbFontFamily.Items.Add(MakeInfoItem("فونتی اضافه نشده", false));
                return true;
            }

            System.Collections.Generic.List<CustomFontInfo> matches = new System.Collections.Generic.List<CustomFontInfo>();
            foreach (CustomFontInfo f in available)
            {
                if (filter.Length == 0 || f.Name.IndexOf(filter, StringComparison.OrdinalIgnoreCase) >= 0)
                {
                    matches.Add(f);
                }
            }
            if (matches.Count == 0)
            {
                return false;
            }

            cmbFontFamily.Items.Add(MakeInfoItem("فونت شخصی", true));
            foreach (CustomFontInfo f in matches)
            {
                cmbFontFamily.Items.Add(MakeCustomFontItem(f));
            }
            return true;
        }

        /// <summary>بخش «فونت پیشنهادی» (بی کودک و وزیرمتن) بالای لیست. اگه بخش نشون داده بشه true برمی‌گردونه.</summary>
        private bool AddRecommendedFontsSection(string filter)
        {
            System.Collections.Generic.List<RecommendedFontInfo> shown = new System.Collections.Generic.List<RecommendedFontInfo>();
            foreach (RecommendedFontInfo f in RecommendedFontService.GetAll())
            {
                // فونت همراه برنامه نیست و روی سیستم هم نصب نیست: نشون نده
                if (f.PreviewFolder == null && !IsSystemFontName(f.FamilyName))
                {
                    continue;
                }
                if (filter.Length > 0
                    && f.DisplayName.IndexOf(filter, StringComparison.OrdinalIgnoreCase) < 0
                    && f.FamilyName.IndexOf(filter, StringComparison.OrdinalIgnoreCase) < 0)
                {
                    continue;
                }
                shown.Add(f);
            }
            if (shown.Count == 0)
            {
                return false;
            }
            cmbFontFamily.Items.Add(MakeInfoItem("فونت پیشنهادی", true));
            foreach (RecommendedFontInfo f in shown)
            {
                cmbFontFamily.Items.Add(MakeRecommendedFontItem(f));
            }
            return true;
        }

        private ComboBoxItem MakeRecommendedFontItem(RecommendedFontInfo info)
        {
            ComboBoxItem item = new ComboBoxItem();
            item.Content = info.DisplayName;
            item.Tag = info.FamilyName;
            try
            {
                if (info.PreviewFolder != null)
                {
                    item.FontFamily = new FontFamily(new Uri(info.PreviewFolder), "./#" + info.FamilyName);
                }
                else
                {
                    item.FontFamily = new FontFamily(info.FamilyName);
                }
            }
            catch { }
            return item;
        }

        private bool IsSystemFontName(string name)
        {
            LoadFontNames();
            foreach (string n in persianFontNames)
            {
                if (string.Equals(n, name, StringComparison.OrdinalIgnoreCase)) return true;
            }
            foreach (string n in otherFontNames)
            {
                if (string.Equals(n, name, StringComparison.OrdinalIgnoreCase)) return true;
            }
            return false;
        }

        /// <summary>آیتم غیرقابل‌انتخاب: عنوان بخش یا پیام توضیحی.</summary>
        private ComboBoxItem MakeInfoItem(string text, bool isHeader)
        {
            ComboBoxItem item = new ComboBoxItem();
            item.Content = text;
            item.IsEnabled = false;
            item.Focusable = false;
            item.Cursor = Cursors.Arrow;
            item.FontSize = isHeader ? 11 : 12;
            item.FontWeight = isHeader ? FontWeights.SemiBold : FontWeights.Normal;
            item.SetResourceReference(Control.ForegroundProperty, isHeader ? "AccentColor" : "TextMuted");
            return item;
        }

        /// <summary>آیتم فونت شخصی: اسم دلخواه کاربر نمایش داده می‌شه و اسم داخلی فونت توی Tag می‌مونه.</summary>
        private ComboBoxItem MakeCustomFontItem(CustomFontInfo info)
        {
            ComboBoxItem item = new ComboBoxItem();
            item.Content = info.Name;
            item.Tag = info.FamilyName;
            item.DataContext = info; // برای پیدا کردن فونتی که باید حذف بشه

            Style deleteStyle = TryFindResource("CustomFontItemStyle") as Style;
            if (deleteStyle != null)
            {
                item.Style = deleteStyle;
            }
            item.AddHandler(Button.ClickEvent, new RoutedEventHandler(CustomFontDelete_Click));

            try
            {
                item.FontFamily = new FontFamily(new Uri(CustomFontService.GetPreviewFolder(info)), "./#" + info.FamilyName);
            }
            catch { }
            return item;
        }
        private void CustomFontDelete_Click(object sender, RoutedEventArgs e)
        {
            Button btn = e.OriginalSource as Button;
            if (btn == null || btn.Name != "PART_Delete")
            {
                return;
            }
            e.Handled = true;

            ComboBoxItem item = sender as ComboBoxItem;
            CustomFontInfo info = (item == null) ? null : item.DataContext as CustomFontInfo;
            if (info == null)
            {
                return;
            }

            cmbFontFamily.IsDropDownOpen = false;
            DeleteCustomFont(info);
        }

        /// <summary>آیا موس الان روی آیکون حذف (سطل زباله) کنار یک فونت شخصی هست؟</summary>
        private bool IsMouseOverDeleteButton()
        {
            DependencyObject node = Mouse.DirectlyOver as DependencyObject;
            while (node != null)
            {
                Button b = node as Button;
                if (b != null && b.Name == "PART_Delete")
                {
                    return true;
                }
                if (node is Visual)
                {
                    node = VisualTreeHelper.GetParent(node);
                }
                else
                {
                    node = LogicalTreeHelper.GetParent(node);
                }
            }
            return false;
        }

        private void DeleteCustomFont(CustomFontInfo info)
        {
            MessageBoxResult answer = DarkMessageBox.Show(
                Loc.F("فونت «{0}» حذف بشه؟\nاین کار قابل برگشت نیست.", info.Name),
                "حذف فونت شخصی", MessageBoxButton.YesNo, MessageBoxImage.Exclamation);
            if (answer != MessageBoxResult.Yes)
            {
                RefreshFontList(); // انتخاب فونت فعلی رو دوباره درست نشون بده
                return;
            }

            bool wasInUse = string.Equals(currentFontFamily, info.FamilyName, StringComparison.OrdinalIgnoreCase);
            string previousFamily = currentFontFamily;

            // اول متن کتاب رو از این فونت جدا می‌کنیم تا مرورگر داخلی فایل رو آزاد کنه
            if (wasInUse)
            {
                currentFontFamily = "Tahoma";
                ReloadCurrentChapterWithNewStyle();
            }

            try
            {
                CustomFontService.Remove(info.Name);
            }
            catch (Exception ex)
            {
                if (wasInUse)
                {
                    currentFontFamily = previousFamily;
                    ReloadCurrentChapterWithNewStyle();
                }
                DarkMessageBox.Show(Loc.T("حذف فونت انجام نشد:\n") + ex.Message, "فونت شخصی", MessageBoxButton.OK, MessageBoxImage.Hand);
                RefreshFontList();
                return;
            }

            // اگه یک فونت شخصی دیگه با همین اسم داخلی هست، فونت فعال هنوز قابل استفاده‌ست
            if (wasInUse)
            {
                foreach (CustomFontInfo other in CustomFontService.GetAll())
                {
                    if (other.IsAvailable && string.Equals(other.FamilyName, info.FamilyName, StringComparison.OrdinalIgnoreCase))
                    {
                        currentFontFamily = previousFamily;
                        ReloadCurrentChapterWithNewStyle();
                        wasInUse = false;
                        break;
                    }
                }
            }

            RefreshFontList();
            if (wasInUse)
            {
                SaveBookSettings();
            }
        }

        private bool IsFontAvailable(string name)
        {
            LoadFontNames();
            foreach (string n in persianFontNames)
            {
                if (string.Equals(n, name, StringComparison.OrdinalIgnoreCase)) return true;
            }
            foreach (string n in otherFontNames)
            {
                if (string.Equals(n, name, StringComparison.OrdinalIgnoreCase)) return true;
            }
            foreach (CustomFontInfo f in CustomFontService.GetAll())
            {
                if (f.IsAvailable && string.Equals(f.FamilyName, name, StringComparison.OrdinalIgnoreCase)) return true;
            }
            foreach (RecommendedFontInfo r in RecommendedFontService.GetAll())
            {
                if (r.PreviewFolder != null && string.Equals(r.FamilyName, name, StringComparison.OrdinalIgnoreCase)) return true;
            }
            return false;
        }
        private void AddFontsToList(System.Collections.Generic.List<string> names, string filter, ref int count)
        {
            foreach (string fontName in names)
            {
                bool matches = (filter.Length == 0) || (fontName.IndexOf(filter, StringComparison.OrdinalIgnoreCase) >= 0);
                if (!matches)
                {
                    continue;
                }
                // فونت فعلی کتاب همیشه نشون داده می‌شه، حتی اگه از سقف رد بشه
                if (count >= MaxFontItems && fontName != currentFontFamily)
                {
                    continue;
                }
                cmbFontFamily.Items.Add(MakeFontItem(fontName));
                count++;
            }
        }

        private ComboBoxItem MakeFontItem(string fontName)
        {
            ComboBoxItem item = new ComboBoxItem();
            item.Content = fontName;
            item.Tag = fontName;
            item.FontFamily = new FontFamily(fontName);
            return item;
        }

        private void txtFontSearch_TextChanged(object sender, TextChangedEventArgs e)
        {
            if (cmbFontFamily == null)
            {
                return;
            }
            if (!IsFontAvailable(currentFontFamily))
            {
                currentFontFamily = "Tahoma";
            }
            RefreshFontList();
            if (txtFontSearch.Text.Trim().Length > 0)
            {
                cmbFontFamily.IsDropDownOpen = true;
            }
        }
        private void btnAddFont_Click(object sender, RoutedEventArgs e)
        {
            OpenFileDialog dlg = new OpenFileDialog();
            dlg.Title = Loc.T("انتخاب فایل فونت");
            dlg.Filter = Loc.T("فایل فونت") + " (*.ttf;*.otf)|*.ttf;*.otf";
            if (dlg.ShowDialog() != true)
            {
                return;
            }

            // پیشنهاد اولیه برای اسم: همون اسم فایل
            string suggestedName = Path.GetFileNameWithoutExtension(dlg.FileName);

            while (true)
            {
                string name = DarkMessageBox.Prompt("اسم فونت رو وارد کن (فارسی یا انگلیسی):", "افزودن فونت شخصی", suggestedName);
                if (name == null)
                {
                    return; // انصراف
                }

                try
                {
                    CustomFontInfo added = CustomFontService.Add(dlg.FileName, name);
                    currentFontFamily = added.FamilyName;
                    RefreshFontList();
                    ReloadCurrentChapterWithNewStyle();
                    SaveBookSettings();
                    DarkMessageBox.Show(Loc.F("فونت «{0}» ذخیره شد.", added.Name), "فونت شخصی");
                    return;
                }
                catch (FontNameException ex)
                {
                    // مشکل از اسم بود: خطا رو نشون بده و دوباره همون اسم رو برای اصلاح باز کن
                    DarkMessageBox.Show(ex.Message, "فونت شخصی");
                    suggestedName = name;
                }
                catch (ArgumentException ex)
                {
                    // مشکل از خود فایل بود: اسم رو دوباره نپرس
                    DarkMessageBox.Show(ex.Message, "فونت شخصی");
                    return;
                }
            }
        }
        private void cmbFontFamily_SelectionChanged(object sender, SelectionChangedEventArgs e)
        {
            ComboBoxItem selected = cmbFontFamily.SelectedItem as ComboBoxItem;
            if (!isLoadingBookSettings && selected != null && selected.Tag != null)
            {
                // اگه کاربر روی آیکون حذف کلیک کرده، این «انتخاب» نیست؛ نباید فونت عوض بشه
                if (IsMouseOverDeleteButton())
                {
                    return;
                }
                currentFontFamily = selected.Tag.ToString();
                ReloadCurrentChapterWithNewStyle();
                SaveBookSettings();
            }
        }

        // سایز فونت رو (بین حداقل و حداکثر) اعمال و ذخیره می‌کنه
        private void ApplyFontSize(int size)
        {
            if (size < MinFontSize)
            {
                size = MinFontSize;
            }
            if (size > MaxFontSize)
            {
                size = MaxFontSize;
            }
            txtFontSize.Text = size.ToString();
            if (size == currentFontSize)
            {
                return;
            }
            currentFontSize = size;
            ReloadCurrentChapterWithNewStyle();
            SaveBookSettings();
        }

        private void btnFontIncrease_Click(object sender, RoutedEventArgs e)
        {
            ApplyFontSize(currentFontSize + 2);
        }

        private void btnFontDecrease_Click(object sender, RoutedEventArgs e)
        {
            ApplyFontSize(currentFontSize - 2);
        }

        // فقط رقم‌ها رو نگه می‌داره (هر نوع رقمی: انگلیسی، فارسی، عربی) و به رقم انگلیسی تبدیل می‌کنه
        private static string NormalizeDigits(string text)
        {
            StringBuilder sb = new StringBuilder();
            foreach (char c in text)
            {
                if (char.IsDigit(c))
                {
                    sb.Append((int)char.GetNumericValue(c));
                }
            }
            return sb.ToString();
        }

        // عددی که کاربر توی کادر سایز نوشته رو اعمال می‌کنه؛ اگه عدد نبود، سایز قبلی برمی‌گرده
        private void CommitFontSizeText()
        {
            int value;
            if (int.TryParse(NormalizeDigits(txtFontSize.Text.Trim()), out value))
            {
                ApplyFontSize(value);
            }
            else
            {
                txtFontSize.Text = currentFontSize.ToString();
            }
        }

        private void txtFontSize_PreviewTextInput(object sender, TextCompositionEventArgs e)
        {
            foreach (char c in e.Text)
            {
                if (!char.IsDigit(c))
                {
                    e.Handled = true;
                    return;
                }
            }
        }

        private void txtFontSize_PreviewKeyDown(object sender, KeyEventArgs e)
        {
            if (e.Key == Key.Enter)
            {
                CommitFontSizeText();
                Keyboard.ClearFocus();
                e.Handled = true;
            }
            else if (e.Key == Key.Escape)
            {
                txtFontSize.Text = currentFontSize.ToString();
                Keyboard.ClearFocus();
                e.Handled = true;
            }
        }

        private void txtFontSize_LostFocus(object sender, RoutedEventArgs e)
        {
            CommitFontSizeText();
        }

        // ---------- تنظیمات فاصله‌ها (فاز ۳) ----------

        private double lineHeight = 1.8;

        private int sideMargin = 24;

        private double paragraphSpacing = 0;

        private bool isLoadingSpacing = true;

        private DateTime spacingPopupClosedAt = DateTime.MinValue;

        private double ReadSpacingSetting(string key, double defaultValue, double min, double max)
        {
            double v;
            string text = AppSettings.Get(key, defaultValue.ToString(System.Globalization.CultureInfo.InvariantCulture));
            if (!double.TryParse(text, System.Globalization.NumberStyles.Float, System.Globalization.CultureInfo.InvariantCulture, out v))
            {
                return defaultValue;
            }
            if (v < min) return min;
            if (v > max) return max;
            return v;
        }

        private void LoadSpacingSettings()
        {
            isLoadingSpacing = true;
            lineHeight = ReadSpacingSetting("LineHeight", 1.8, 1.2, 3.0);
            sideMargin = (int)Math.Round(ReadSpacingSetting("SideMargin", 24, 0, 160));
            paragraphSpacing = ReadSpacingSetting("ParagraphSpacing", 0, 0, 2.5);
            sldLineHeight.Value = lineHeight;
            sldSideMargin.Value = sideMargin;
            sldParagraph.Value = paragraphSpacing;
            UpdateSpacingLabels();
            isLoadingSpacing = false;
        }

        // فاصله‌های متن برای هر کتاب جدا ذخیره می‌شه
        private void SaveSpacingSettings()
        {
            if (currentLibraryBook == null)
            {
                return;
            }
            currentLibraryBook.LineHeight = lineHeight;
            currentLibraryBook.SideMargin = sideMargin;
            currentLibraryBook.ParagraphSpacing = paragraphSpacing;
            LibraryService.AddOrUpdateBook(currentLibraryBook);
        }

        // فاصله‌های ذخیره‌شده‌ی همین کتاب رو می‌خونه؛ اگه هنوز چیزی ذخیره نشده، از مقدارهای قبلی برنامه شروع می‌کنه
        private void LoadSpacingForBook(Book book)
        {
            if (book == null || book.LineHeight <= 0.0)
            {
                LoadSpacingSettings();
                return;
            }
            isLoadingSpacing = true;
            lineHeight = Math.Max(1.2, Math.Min(3.0, book.LineHeight));
            sideMargin = Math.Max(0, Math.Min(160, book.SideMargin));
            paragraphSpacing = Math.Max(0.0, Math.Min(2.5, book.ParagraphSpacing));
            sldLineHeight.Value = lineHeight;
            sldSideMargin.Value = sideMargin;
            sldParagraph.Value = paragraphSpacing;
            UpdateSpacingLabels();
            isLoadingSpacing = false;
        }

        private void UpdateSpacingLabels()
        {
            System.Globalization.CultureInfo inv = System.Globalization.CultureInfo.InvariantCulture;
            txtLineHeightVal.Text = lineHeight.ToString("0.0", inv);
            txtSideMarginVal.Text = sideMargin.ToString(inv);
            txtParagraphVal.Text = (paragraphSpacing <= 0) ? Loc.T("پیش‌فرض") : paragraphSpacing.ToString("0.0", inv);
        }

        /// <summary>CSS فاصله‌ها: هم موقع ساخت صفحه و هم موقع تغییر زنده استفاده می‌شه.</summary>
        // ---- جهت متن (فارسی راست‌به‌چپ / انگلیسی چپ‌به‌راست) ----
        // 0 = حرفی نیست، 1 = لاتین بیشتره (LTR)، 2 = عربی/فارسی بیشتره (RTL)
        private static int DetectDirection(string text)
        {
            int latin = 0;
            int rtl = 0;
            foreach (char c in text)
            {
                if ((c >= 'A' && c <= 'Z') || (c >= 'a' && c <= 'z') || (c >= '\u00C0' && c <= '\u024F' && c != '\u00D7' && c != '\u00F7'))
                {
                    latin++;
                }
                else if ((c >= '\u0590' && c <= '\u08FF') || (c >= '\uFB1D' && c <= '\uFDFF') || (c >= '\uFE70' && c <= '\uFEFF'))
                {
                    rtl++;
                }
            }
            if (latin == 0 && rtl == 0)
            {
                return 0;
            }
            return (latin > rtl) ? 1 : 2;
        }

        private static string HtmlToPlainText(string html)
        {
            string t = Regex.Replace(html, "<(style|script)[\\s\\S]*?</\\1\\s*>", " ", RegexOptions.IgnoreCase);
            t = Regex.Replace(t, "<[^>]*>", " ");
            return WebUtility.HtmlDecode(t);
        }

        private static bool IsLatinText(string text)
        {
            return DetectDirection(text) == 1;
        }

        // برای کل فصل: جهت اصلی رو از روی اکثریت حروف تعیین می‌کنه
        private static string ChapterDirAttr(string bodyHtml)
        {
            int d = DetectDirection(HtmlToPlainText(bodyHtml));
            if (d == 1)
            {
                return " dir=\"ltr\" data-rdr=\"l\"";
            }
            return " dir=\"rtl\" data-rdr=\"r\"";
        }

        private static string StampDirection(Match m)
        {
            string tag = m.Groups[1].Value;
            string attrs = m.Groups[2].Value;
            string inner = m.Groups[3].Value;
            int d = DetectDirection(HtmlToPlainText(inner));
            if (d == 0)
            {
                return m.Value;
            }
            attrs = Regex.Replace(attrs, "\\s(dir|data-rdr)\\s*=\\s*(\"[^\"]*\"|'[^']*'|[^\\s>]+)", "", RegexOptions.IgnoreCase);
            string stamp = (d == 1) ? " dir=\"ltr\" data-rdr=\"l\"" : " dir=\"rtl\" data-rdr=\"r\"";
            return "<" + tag + attrs + stamp + ">" + inner + "</" + tag + ">";
        }

        // به هر پاراگراف/تیتر/لیست جهت درست خودش رو می‌ده؛ پس متن انگلیسی چپ‌چین و LTR می‌شه
        // و متن فارسی راست‌چین می‌مونه، حتی وقتی توی یه کتاب قاطی باشن
        private static string ApplyTextDirection(string body)
        {
            // اول بلاک‌های داخلی
            body = Regex.Replace(body, "<(p|h[1-6]|dd|dt|figcaption|caption)\\b([^>]*?)(?<!/)>([\\s\\S]*?)</\\1\\s*>", new MatchEvaluator(StampDirection), RegexOptions.IgnoreCase);
            // بعد div‌هایی که بلاک دیگه‌ای داخلشون نیست
            body = Regex.Replace(body, "<(div)\\b([^>]*?)(?<!/)>((?:(?!<div\\b|</div|<p\\b|<h[1-6]\\b|<ul\\b|<ol\\b|<table\\b|<blockquote\\b)[\\s\\S])*?)</div\\s*>", new MatchEvaluator(StampDirection), RegexOptions.IgnoreCase);
            // بعد ظرف‌ها
            body = Regex.Replace(body, "<(ul|ol|blockquote)\\b([^>]*?)(?<!/)>([\\s\\S]*?)</\\1\\s*>", new MatchEvaluator(StampDirection), RegexOptions.IgnoreCase);
            return body;
        }

        private static void ApplyTitleDirection(TextBlock tb, string text)
        {
            tb.FlowDirection = IsLatinText(text) ? FlowDirection.LeftToRight : FlowDirection.RightToLeft;
        }

        // ---- تایپ توی کادر جستجوی فونت وقتی موس روی لیست بازشده است ----
        private void FocusFontSearchAndInsert(string text)
        {
            txtFontSearch.Focus();
            txtFontSearch.CaretIndex = txtFontSearch.Text.Length;
            txtFontSearch.SelectedText = text;
            txtFontSearch.CaretIndex = txtFontSearch.Text.Length;
        }

        private void cmbFontFamily_PreviewTextInput(object sender, TextCompositionEventArgs e)
        {
            if (txtFontSearch.IsKeyboardFocused || string.IsNullOrEmpty(e.Text))
            {
                return;
            }
            FocusFontSearchAndInsert(e.Text);
            e.Handled = true;
        }

        private void cmbFontFamily_PreviewKeyDown(object sender, KeyEventArgs e)
        {
            if (txtFontSearch.IsKeyboardFocused)
            {
                return;
            }
            if (e.Key == Key.Space)
            {
                FocusFontSearchAndInsert(" ");
                e.Handled = true;
            }
            else if (e.Key == Key.Back)
            {
                txtFontSearch.Focus();
                if (txtFontSearch.Text.Length > 0)
                {
                    txtFontSearch.Text = txtFontSearch.Text.Substring(0, txtFontSearch.Text.Length - 1);
                }
                txtFontSearch.CaretIndex = txtFontSearch.Text.Length;
                e.Handled = true;
            }
        }

        // بیشینه‌ی عرض متن (پیکسل) وقتی پنجره بزرگ شده
        private const int MaximizedTextWidth = 1000;

        private string BuildSpacingCss()
        {
            System.Globalization.CultureInfo inv = System.Globalization.CultureInfo.InvariantCulture;
            string css = "body{line-height:" + lineHeight.ToString("0.0", inv) + " !important;padding:16px " + sideMargin.ToString(inv) + "px !important;}body *{line-height:inherit !important;}";
            if (paragraphSpacing > 0)
            {
                css += "p{margin-top:0 !important;margin-bottom:" + paragraphSpacing.ToString("0.0", inv) + "em !important;}";
            }
            // پنجره‌ی بزرگ/تمام‌صفحه: عرض متن محدود و وسط‌چین می‌شه تا خط‌ها زیادی کشیده نشن
            if (WindowState == WindowState.Maximized)
            {
                css += "body{max-width:" + MaximizedTextWidth + "px !important;margin-left:auto !important;margin-right:auto !important;}";
            }
            return css;
        }

        private void ApplySpacingLive()
        {
            if (currentBook == null)
            {
                return;
            }
            string css = BuildSpacingCss().Replace("\\", "\\\\").Replace("'", "\\'");
            string js = "(function(){var s=document.getElementById('readerSpacing');if(!s){return 'no';}if(s.styleSheet){s.styleSheet.cssText='" + css + "';}else{s.innerHTML='" + css + "';}return 'ok';})();";
            RunScriptAsync(js);
        }

        private void btnSpacing_Click(object sender, RoutedEventArgs e)
        {
            // اگه همین الان با کلیک روی دکمه بسته شده، دوباره باز نکن
            if ((DateTime.UtcNow - spacingPopupClosedAt).TotalMilliseconds > 250)
            {
                popupSpacing.IsOpen = true;
            }
        }

        private void popupSpacing_Closed(object sender, EventArgs e)
        {
            spacingPopupClosedAt = DateTime.UtcNow;
            SaveSpacingSettings();
        }

        private void Spacing_ValueChanged(object sender, RoutedPropertyChangedEventArgs<double> e)
        {
            if (isLoadingSpacing)
            {
                return;
            }
            lineHeight = sldLineHeight.Value;
            sideMargin = (int)Math.Round(sldSideMargin.Value);
            paragraphSpacing = sldParagraph.Value;
            UpdateSpacingLabels();
            ApplySpacingLive();
        }

        private void btnSpacingReset_Click(object sender, RoutedEventArgs e)
        {
            isLoadingSpacing = true;
            sldLineHeight.Value = 1.8;
            sldSideMargin.Value = 24;
            sldParagraph.Value = 0;
            isLoadingSpacing = false;
            lineHeight = 1.8;
            sideMargin = 24;
            paragraphSpacing = 0;
            UpdateSpacingLabels();
            ApplySpacingLive();
            SaveSpacingSettings();
        }

        private async void ReloadCurrentChapterWithNewStyle()
        {
            if (currentBook == null || listBoxChapters.SelectedIndex < 0)
            {
                return;
            }
            ReaderTheme theme = GetCurrentTheme();
            string bgColor = theme.Bg;
            string textColor = theme.Text;
            // اگه فونت عوض شده و فونت شخصی/پیشنهادیه، فایل فونت هم داخل صفحه تعریف می‌شه
            string fontFaceJs = "";
            if (!string.Equals(pageFontFamily, currentFontFamily, StringComparison.OrdinalIgnoreCase))
            {
                pageFontFamily = currentFontFamily;
                fontFaceJs = "var fs=document.getElementById('rdrFontFace');if(fs){fs.textContent='" + EscapeForJs(BuildFontFaceCss(currentFontFamily)) + "';}";
            }
            string js = "(function(){var d=document.documentElement,b=document.body;" + fontFaceJs
                + "var st=Math.max(d.scrollTop||0,b.scrollTop||0);var sh=Math.max(d.scrollHeight||0,b.scrollHeight||0);var r=sh>0?st/sh:0;"
                + "b.style.fontFamily=\"'" + currentFontFamily + "',Tahoma,sans-serif\";b.style.fontSize='" + currentFontSize + "px';"
                + "b.style.color='" + textColor + "';b.style.backgroundColor='" + bgColor + "';d.style.backgroundColor='" + bgColor + "';"
                + "var nh=Math.max(d.scrollHeight||0,b.scrollHeight||0);var ns=Math.round(r*nh);d.scrollTop=ns;b.scrollTop=ns;return 'ok';})();";
            string result = await RunScriptAsync(js);
            if (result == null || result == "null")
            {
                // صفحه هنوز آماده نیست یا خطا داد: فصل دوباره بارگذاری می‌شه
                if (currentBook != null && listBoxChapters.SelectedIndex >= 0 && listBoxChapters.SelectedIndex < currentBook.Chapters.Count)
                {
                    ChapterItem chapter = currentBook.Chapters[listBoxChapters.SelectedIndex];
                    LoadChapterContent(chapter.FilePath);
                }
            }
        }

        // ---- نوار اسکرول سفارشی (نوار خود IE مخفی شده) ----
        private double sbTop;

        private double sbHeight;

        private double sbClient;

        private bool sbDragging;

        private double sbDragOffset;

        public void OnScrollInfo(int top, int height, int client)
        {
            sbTop = top;
            sbHeight = height;
            sbClient = client;
            if (!sbDragging)
            {
                UpdateScrollThumb();
            }
        }

        private void UpdateScrollThumb()
        {
            double trackH = sbTrack.ActualHeight;
            if (sbClient <= 0 || sbHeight <= sbClient + 1 || trackH <= 0)
            {
                sbThumb.Visibility = Visibility.Collapsed;
                return;
            }
            double thumbH = Math.Max(36.0, trackH * sbClient / sbHeight);
            if (thumbH > trackH)
            {
                thumbH = trackH;
            }
            double maxTop = trackH - thumbH;
            double ratio = sbTop / (sbHeight - sbClient);
            if (ratio < 0.0)
            {
                ratio = 0.0;
            }
            if (ratio > 1.0)
            {
                ratio = 1.0;
            }
            sbThumb.Height = thumbH;
            sbThumb.Margin = new Thickness(0.0, maxTop * ratio, 0.0, 0.0);
            sbThumb.Visibility = Visibility.Visible;
        }

        private void SetPageScrollTop(double value)
        {
            if (value < 0.0)
            {
                value = 0.0;
            }
            string v = Math.Round(value).ToString(System.Globalization.CultureInfo.InvariantCulture);
            RunScriptAsync("(function(){document.documentElement.scrollTop=" + v + ";document.body.scrollTop=" + v + ";})();");
        }

        private void sbTrack_SizeChanged(object sender, SizeChangedEventArgs e)
        {
            UpdateScrollThumb();
        }

        private void sbTrack_MouseLeftButtonDown(object sender, MouseButtonEventArgs e)
        {
            if (sbThumb.Visibility != Visibility.Visible)
            {
                return;
            }
            Point p = e.GetPosition(sbTrack);
            double thumbTop = sbThumb.Margin.Top;
            if (p.Y >= thumbTop && p.Y <= thumbTop + sbThumb.Height)
            {
                sbDragging = true;
                sbDragOffset = p.Y - thumbTop;
                sbTrack.CaptureMouse();
            }
            else
            {
                SetPageScrollTop(p.Y < thumbTop ? sbTop - sbClient * 0.9 : sbTop + sbClient * 0.9);
            }
            e.Handled = true;
        }

        private void sbTrack_MouseMove(object sender, MouseEventArgs e)
        {
            if (!sbDragging)
            {
                return;
            }
            double maxTop = sbTrack.ActualHeight - sbThumb.Height;
            if (maxTop <= 0.0)
            {
                return;
            }
            Point p = e.GetPosition(sbTrack);
            double newTop = p.Y - sbDragOffset;
            if (newTop < 0.0)
            {
                newTop = 0.0;
            }
            if (newTop > maxTop)
            {
                newTop = maxTop;
            }
            sbThumb.Margin = new Thickness(0.0, newTop, 0.0, 0.0);
            SetPageScrollTop(newTop / maxTop * (sbHeight - sbClient));
        }

        private void sbTrack_MouseLeftButtonUp(object sender, MouseButtonEventArgs e)
        {
            if (sbDragging)
            {
                sbTrack.ReleaseMouseCapture();
            }
        }

        private void sbTrack_LostMouseCapture(object sender, MouseEventArgs e)
        {
            sbDragging = false;
            UpdateScrollThumb();
        }

        private void sbTrack_MouseEnter(object sender, MouseEventArgs e)
        {
            sbThumb.Opacity = 1.0;
        }

        private void sbTrack_MouseLeave(object sender, MouseEventArgs e)
        {
            if (!sbDragging)
            {
                sbThumb.Opacity = 0.75;
            }
        }

        private void UpdateDarkModeButton()
        {
            ReaderTheme theme = GetCurrentTheme();
            ReaderCard.Background = HexBrush(theme.Bg);
            sbThumb.Background = HexBrush(theme.ScrollThumb);
            Brush accent = (Brush)FindResource("AccentColor");
            Brush idle = HexBrush("#4C4766");
            foreach (UIElement child in ThemePanel.Children)
            {
                System.Windows.Shapes.Ellipse dot = child as System.Windows.Shapes.Ellipse;
                if (dot == null)
                {
                    continue;
                }
                bool selected = (dot.Tag as string) == theme.Key;
                dot.Stroke = (selected ? accent : idle);
                dot.StrokeThickness = (selected ? 3.0 : 1.5);
            }
        }

        private void btnFullScreen_Click(object sender, RoutedEventArgs e)
        {
            ToggleFullScreen();
        }

        private void ToggleFullScreen()
        {
            isFullScreen = !isFullScreen;
            if (isFullScreen)
            {
                previousWindowState = WindowState;
                savedReaderEffect = ReaderCard.Effect;
                TitleRow.Height = new GridLength(0.0);
                TitleBar.Visibility = Visibility.Collapsed;
                SidebarColumn.Width = new GridLength(0.0);
                SidebarBorder.Visibility = Visibility.Collapsed;
                TopBar.Visibility = Visibility.Collapsed;
                BottomBar.Visibility = Visibility.Collapsed;
                ContentGrid.Margin = new Thickness(0.0);
                ReaderCard.CornerRadius = new CornerRadius(0.0);
                ReaderCard.BorderThickness = new Thickness(0.0);
                ReaderCard.Padding = new Thickness(0.0);
                ReaderCard.Effect = null;
                ResizeMode = ResizeMode.NoResize;
                WindowState = WindowState.Normal;
                WindowState = WindowState.Maximized;
            }
            else
            {
                TitleRow.Height = new GridLength(52.0);
                TitleBar.Visibility = Visibility.Visible;
                SidebarColumn.Width = new GridLength(280.0);
                SidebarBorder.Visibility = Visibility.Visible;
                TopBar.Visibility = Visibility.Visible;
                BottomBar.Visibility = Visibility.Visible;
                ContentGrid.Margin = new Thickness(28.0, 20.0, 28.0, 18.0);
                ReaderCard.CornerRadius = new CornerRadius(18.0);
                ReaderCard.BorderThickness = new Thickness(1.0);
                ReaderCard.Padding = new Thickness(6.0);
                ReaderCard.Effect = savedReaderEffect;
                ResizeMode = ResizeMode.CanResizeWithGrip;
                WindowMax.SetState(this, WindowState.Normal);
                WindowMax.SetState(this, previousWindowState);
            }
            txtFullScreen.Text = Loc.T(isFullScreen ? "خروج از تمام صفحه" : "تمام صفحه");
            icoFullScreen.Data = (Geometry)FindResource(isFullScreen ? "IcoExitFullscreen" : "IcoFullscreen");
        }

        private void Window_PreviewKeyDown(object sender, KeyEventArgs e)
        {
            if (e.Key == Key.F11)
            {
                ToggleFullScreen();
                e.Handled = true;
            }
            else if (e.Key == Key.Escape && isFullScreen)
            {
                ToggleFullScreen();
                e.Handled = true;
            }
            else
            {
                string id = ShortcutService.Resolve(e, "rd.");
                if (id != null)
                {
                    ModifierKeys mods = Keyboard.Modifiers;
                    bool typing = Keyboard.FocusedElement is TextBox;
                    if (typing && (mods & (ModifierKeys.Control | ModifierKeys.Alt)) == 0)
                    {
                        return;
                    }
                    e.Handled = true;
                    RunReaderAction(id);
                }
            }
        }

        private void btnContinuousMode_Click(object sender, RoutedEventArgs e)
        {
            continuousMode = !continuousMode;
            txtContinuous.Text = Loc.T(continuousMode ? "حالت پیوسته: روشن" : "حالت پیوسته: خاموش");
            btnContinuousMode.Background = (Brush)FindResource(continuousMode ? "AccentColorDark" : "HoverColor");
            btnContinuousMode.Foreground = (continuousMode ? Brushes.White : ((Brush)FindResource("TextMuted")));
            if (continuousMode)
            {
                StartScrollWatcher();
            }
            else
            {
                StopScrollWatcher();
            }
        }

        private void StartScrollWatcher()
        {
            if (scrollCheckTimer == null)
            {
                scrollCheckTimer = new DispatcherTimer();
                scrollCheckTimer.Interval = TimeSpan.FromMilliseconds(600.0);
                scrollCheckTimer.Tick += ScrollCheckTimer_Tick;
            }
            scrollCheckTimer.Start();
        }

        private void StopScrollWatcher()
        {
            if (scrollCheckTimer != null)
            {
                scrollCheckTimer.Stop();
            }
        }

        private async void ScrollCheckTimer_Tick(object sender, EventArgs e)
        {
            if (!continuousMode || isAppendingChapter || currentBook == null)
            {
                return;
            }
            try
            {
                string checkScript = "(function(){if(window.__rdrGen!==" + docGen + ")return 'x';var d=document.documentElement,b=document.body;var st=Math.max(d.scrollTop||0,b.scrollTop||0);var sh=Math.max(d.scrollHeight||0,b.scrollHeight||0);var ch=d.clientHeight||window.innerHeight;var idx=-1;var cs=b.getElementsByTagName('div');for(var i=0;i<cs.length;i++){if(cs[i].className=='rdr-chap'&&cs[i].offsetTop<=st+ch/3){idx=parseInt(cs[i].getAttribute('data-idx'),10);}}return (((st+ch)>=(sh-300))?'1':'0')+'|'+((st<=300)?'1':'0')+'|'+idx;})();";
                string text = JsonToString(await RunScriptAsync(checkScript));
                if (string.IsNullOrEmpty(text) || text == "x")
                {
                    return;
                }
                string[] parts = text.Split('|');
                if (parts.Length != 3)
                {
                    return;
                }
                int idx;
                if (int.TryParse(parts[2], out idx))
                {
                    UpdateCurrentChapterFromScroll(idx);
                }
                if (parts[0] == "1")
                {
                    AppendNextChapter();
                }
                else if (parts[1] == "1")
                {
                    PrependPreviousChapter();
                }
            }
            catch
            {
            }
        }

        // فصلی که الان توی صفحه دیده می‌شه رو توی لیست، عنوان و پیشرفت ذخیره‌شده هماهنگ می‌کنه
        private void UpdateCurrentChapterFromScroll(int idx)
        {
            if (currentBook == null || idx < contFirst || idx > contLast)
            {
                return;
            }
            if (idx < 0 || idx >= currentBook.Chapters.Count || idx == listBoxChapters.SelectedIndex)
            {
                return;
            }
            ChapterItem chapter = currentBook.Chapters[idx];
            isProgrammaticSelection = true;
            try
            {
                listBoxChapters.SelectedIndex = idx;
                listBoxChapters.ScrollIntoView(listBoxChapters.SelectedItem);
            }
            finally
            {
                isProgrammaticSelection = false;
            }
            txtChapterTitle.Text = chapter.Title;
                txtChapterTitle.FlowDirection = Loc.Flow;
                Loc.FixTextDirection(txtChapterTitle);
                ApplyTitleDirection(txtChapterTitle, chapter.Title ?? "");
            txtChapterPosition.Text = Loc.F("{0} از {1}", idx + 1, listBoxChapters.Items.Count);
            SaveReadingProgress(idx);
        }

        private string BuildChapterDivider(string title)
        {
            string dividerBorderColor = GetCurrentTheme().Divider;
            return "<div style='margin:40px 0 24px 0;padding-top:20px;border-top:2px solid " + dividerBorderColor + ";text-align:center;color:" + (GetCurrentTheme().Key.StartsWith("rz.") ? GetCurrentTheme().ScrollThumb : "#A78BFA") + ";font-weight:bold;font-size:16px;'>" + WebUtility.HtmlEncode(title) + "</div>";
        }

        private string ReadChapterBody(ChapterItem chapter)
        {
            string html;
            using (StreamReader reader = new StreamReader(chapter.FilePath, Encoding.UTF8, detectEncodingFromByteOrderMarks: true))
            {
                html = reader.ReadToEnd();
            }
            return PrepareChapterBody(html, Path.GetDirectoryName(chapter.FilePath));
        }

        private async void AppendNextChapter()
        {
            if (currentBook == null)
            {
                return;
            }
            if (isAppendingChapter)
            {
                return;
            }
            int nextIndex = contLast + 1;
            if (nextIndex >= currentBook.Chapters.Count)
            {
                return;
            }
            isAppendingChapter = true;
            try
            {
                ChapterItem nextChapter = currentBook.Chapters[nextIndex];
                string bodyContent = ReadChapterBody(nextChapter);
                string chunk = "<div class=\"rdr-chap\" data-idx=\"" + nextIndex + "\"" + ChapterDirAttr(bodyContent) + ">" + BuildChapterDivider(nextChapter.Title) + bodyContent + "</div>";
                string jsAppend = "(function(){if(window.__rdrGen!==" + docGen + ")return 0;document.body.insertAdjacentHTML('beforeend', '" + EscapeForJs(chunk) + "');return 1;})();";
                string result = await RunScriptAsync(jsAppend);
                if (result == "1")
                {
                    contLast = nextIndex;
                }
            }
            catch (Exception ex)
            {
                DarkMessageBox.Show(Loc.T("خطا در بارگذاری فصل بعدی:\n") + ex.Message, "خطا", MessageBoxButton.OK, MessageBoxImage.Hand);
            }
            finally
            {
                isAppendingChapter = false;
            }
        }

        // فصل قبلی رو بالای صفحه اضافه می‌کنه و جای اسکرول رو طوری تنظیم می‌کنه که متن نپره
        private async void PrependPreviousChapter()
        {
            if (currentBook == null)
            {
                return;
            }
            if (isAppendingChapter)
            {
                return;
            }
            int prevIndex = contFirst - 1;
            if (prevIndex < 0 || contFirst >= currentBook.Chapters.Count)
            {
                return;
            }
            isAppendingChapter = true;
            try
            {
                ChapterItem prevChapter = currentBook.Chapters[prevIndex];
                ChapterItem followingChapter = currentBook.Chapters[contFirst];
                string bodyContent = ReadChapterBody(prevChapter);
                string chunk = "<div class=\"rdr-chap\" data-idx=\"" + prevIndex + "\"" + ChapterDirAttr(bodyContent) + ">" + bodyContent + "</div>";
                string divider = BuildChapterDivider(followingChapter.Title);
                string js = "(function(){if(window.__rdrGen!==" + docGen + ")return 0;"
                    + "var d=document.documentElement,b=document.body;"
                    + "function H(){return Math.max(d.scrollHeight||0,b.scrollHeight||0);}"
                    + "function T(){return Math.max(d.scrollTop||0,b.scrollTop||0);}"
                    + "function S(v){d.scrollTop=v;b.scrollTop=v;}"
                    + "var cs=b.getElementsByTagName('div'),f=null;"
                    + "for(var i=0;i<cs.length;i++){if(cs[i].className=='rdr-chap'){f=cs[i];break;}}"
                    + "var oh=H(),ot=T();"
                    + "if(f){f.insertAdjacentHTML('afterbegin','" + EscapeForJs(divider) + "');}"
                    + "b.insertAdjacentHTML('afterbegin','" + EscapeForJs(chunk) + "');"
                    + "var ph=H();S(ot+(ph-oh));"
                    + "var until=new Date().getTime()+3000;"
                    + "var c=null;cs=b.getElementsByTagName('div');"
                    + "for(var j=0;j<cs.length;j++){if(cs[j].className=='rdr-chap'){c=cs[j];break;}}"
                    + "if(c){var im=c.getElementsByTagName('img');"
                    + "for(var k=0;k<im.length;k++){if(!im[k].complete){im[k].onload=function(){if(new Date().getTime()>until)return;var h2=H(),df=h2-ph;ph=h2;if(df!==0){S(T()+df);}};}}}"
                    + "return 1;})();";
                string result = await RunScriptAsync(js);
                if (result == "1")
                {
                    contFirst = prevIndex;
                }
            }
            catch (Exception ex)
            {
                DarkMessageBox.Show(Loc.T("خطا در بارگذاری فصل قبلی:\n") + ex.Message, "خطا", MessageBoxButton.OK, MessageBoxImage.Hand);
            }
            finally
            {
                isAppendingChapter = false;
            }
        }

        // ================= نمایشگر کتاب (WebView2) =================

        private bool webViewInitStarted = false;

        private bool navAllowed = false;

        private string pagesFolder;

        private string currentPageFile;

        private readonly List<string> stalePageFiles = new List<string>();

        private static readonly Dictionary<string, string> recommendedFamilyCache = new Dictionary<string, string>(StringComparer.OrdinalIgnoreCase);

        private readonly Dictionary<string, string> fontFaceCache = new Dictionary<string, string>(StringComparer.OrdinalIgnoreCase);

        private void InitWebViewHooks()
        {
            Loaded += (object s, RoutedEventArgs e) =>
            {
                if (currentPageFile != null && webBrowser1.CoreWebView2 == null)
                {
                    StartWebViewInit();
                }
            };
            // اگه کلیدهای میانبر توی پنجره‌ی دیگه عوض شده باشن، به صفحه‌ی کتاب هم خبر داده می‌شه
            Activated += (object s, EventArgs e) => PushShortcutsToPage();
        }

        // متن فصل رو توی یک فایل موقت می‌نویسه و نمایشگر رو بهش می‌بره
        // (تا عکس‌های داخل کتاب با آدرس فایل درست دیده بشن و محدودیت حجم هم نداشته باشیم)
        private void ShowHtmlPage(string html)
        {
            try
            {
                if (pagesFolder == null)
                {
                    pagesFolder = Path.Combine(Path.GetTempPath(), "EpubReaderApp_pages", Guid.NewGuid().ToString("N"));
                }
                Directory.CreateDirectory(pagesFolder);
                string file = Path.Combine(pagesFolder, "page_" + docGen + ".html");
                File.WriteAllText(file, html, new UTF8Encoding(false));
                if (currentPageFile != null && !string.Equals(currentPageFile, file, StringComparison.OrdinalIgnoreCase))
                {
                    stalePageFiles.Add(currentPageFile);
                }
                currentPageFile = file;
                if (webBrowser1.CoreWebView2 != null)
                {
                    NavigateToCurrentPage();
                }
                else
                {
                    // تا اولین صفحه آماده نشده، نمایشگر مخفی می‌مونه که سفیدی بیخودی دیده نشه
                    webBrowser1.Visibility = Visibility.Hidden;
                    StartWebViewInit();
                }
            }
            catch (Exception ex)
            {
                DarkMessageBox.Show(Loc.T("خطا در نمایش فصل:\n") + ex.Message, "خطا", MessageBoxButton.OK, MessageBoxImage.Hand);
            }
        }

        private void NavigateToCurrentPage()
        {
            if (currentPageFile == null || webBrowser1.CoreWebView2 == null)
            {
                return;
            }
            navAllowed = true;
            webBrowser1.CoreWebView2.Navigate(new Uri(currentPageFile).AbsoluteUri);
        }

        // محیط WebView2 فقط یک‌بار ساخته می‌شه و بین کتابخونه (گرم‌کردن) و پنجره‌ی خوانش مشترکه
        private static Task<CoreWebView2Environment> webViewEnvTask;

        public static Task<CoreWebView2Environment> GetWebViewEnvironmentAsync()
        {
            if (webViewEnvTask == null || webViewEnvTask.IsFaulted || webViewEnvTask.IsCanceled)
            {
                string dataFolder = Path.Combine(Environment.GetFolderPath(Environment.SpecialFolder.ApplicationData), "EpubReaderApp", "WebView2");
                Directory.CreateDirectory(dataFolder);
                webViewEnvTask = CoreWebView2Environment.CreateAsync(null, dataFolder);
            }
            return webViewEnvTask;
        }

        private async void StartWebViewInit()
        {
            if (webViewInitStarted || !IsLoaded)
            {
                return;
            }
            webViewInitStarted = true;
            try
            {
                CoreWebView2Environment env = await GetWebViewEnvironmentAsync();
                await webBrowser1.EnsureCoreWebView2Async(env);

                CoreWebView2 core = webBrowser1.CoreWebView2;
                core.Settings.AreDefaultContextMenusEnabled = false;
                core.Settings.AreDevToolsEnabled = false;
                core.Settings.IsStatusBarEnabled = false;
                core.Settings.IsZoomControlEnabled = false;
                core.Settings.AreBrowserAcceleratorKeysEnabled = false;
                core.Settings.IsSwipeNavigationEnabled = false;
                core.WebMessageReceived += Core_WebMessageReceived;
                core.NavigationStarting += Core_NavigationStarting;
                core.NavigationCompleted += Core_NavigationCompleted;
                core.NewWindowRequested += (object s, CoreWebView2NewWindowRequestedEventArgs e) => { e.Handled = true; };
                core.ProcessFailed += (object s, CoreWebView2ProcessFailedEventArgs e) =>
                {
                    Dispatcher.BeginInvoke(new Action(() =>
                    {
                        try
                        {
                            if (webBrowser1.CoreWebView2 != null)
                            {
                                NavigateToCurrentPage();
                            }
                        }
                        catch
                        {
                        }
                    }));
                };
                NavigateToCurrentPage();
            }
            catch (Exception ex)
            {
                webViewInitStarted = false;
                webBrowser1.Visibility = Visibility.Visible;
                LibraryService.LogOpen("[Reader] WebView2 init failed: " + ex);
                if (!(ex is WebView2RuntimeNotFoundException))
                {
                    // Runtime هست ولی چیز دیگه‌ای خراب شده؛ علت واقعی رو نشون بده
                    DarkMessageBox.Show(
                        Loc.T("نمایشگر کتاب شروع نشد.\n\n") + ex.GetType().Name + ":\n" + ex.Message + Loc.T("\n\n(جزئیات در فایل open.log داخل پوشه‌ی %APPDATA%\\EpubReaderApp ذخیره شد.)"),
                        "نمایشگر کتاب", MessageBoxButton.OK, MessageBoxImage.Warning);
                    return;
                }
                MessageBoxResult answer = DarkMessageBox.Show(
                    "برای نمایش کتاب، برنامه‌ی «Microsoft Edge WebView2 Runtime» لازم است و روی این سیستم پیدا نشد یا اجرا نشد.\n\nصفحه‌ی دانلود آن باز شود؟",
                    "نمایشگر کتاب", MessageBoxButton.YesNo, MessageBoxImage.Warning);
                if (answer == MessageBoxResult.Yes)
                {
                    try
                    {
                        Process.Start("https://developer.microsoft.com/microsoft-edge/webview2/");
                    }
                    catch
                    {
                    }
                }
            }
        }

        // فقط خود صفحه‌ی فصل اجازه‌ی بارگذاری داره (لینک‌های بیرونی و لینک به فایل‌های دیگه بلاک می‌شن)
        private void Core_NavigationStarting(object sender, CoreWebView2NavigationStartingEventArgs e)
        {
            if (navAllowed)
            {
                navAllowed = false;
                return;
            }
            string uri = e.Uri ?? "";
            int hash = uri.IndexOf('#');
            string withoutHash = (hash >= 0) ? uri.Substring(0, hash) : uri;
            try
            {
                if (currentPageFile != null && string.Equals(withoutHash, new Uri(currentPageFile).AbsoluteUri, StringComparison.OrdinalIgnoreCase))
                {
                    return;
                }
            }
            catch
            {
            }
            e.Cancel = true;
        }

        private void Core_NavigationCompleted(object sender, CoreWebView2NavigationCompletedEventArgs e)
        {
            if (webBrowser1.Visibility != Visibility.Visible)
            {
                webBrowser1.Visibility = Visibility.Visible;
            }
            for (int i = stalePageFiles.Count - 1; i >= 0; i--)
            {
                try
                {
                    if (File.Exists(stalePageFiles[i]))
                    {
                        File.Delete(stalePageFiles[i]);
                    }
                    stalePageFiles.RemoveAt(i);
                }
                catch
                {
                }
            }
        }

        // پیام‌هایی که از داخل صفحه‌ی کتاب می‌رسن: s = اسکرول، k = کلید F11/Esc، c = کلید میانبر
        private void Core_WebMessageReceived(object sender, CoreWebView2WebMessageReceivedEventArgs e)
        {
            string msg;
            try
            {
                msg = e.TryGetWebMessageAsString();
            }
            catch
            {
                return;
            }
            if (string.IsNullOrEmpty(msg))
            {
                return;
            }
            string[] p = msg.Split('|');
            int a;
            int b;
            int c;
            switch (p[0])
            {
                case "s":
                    if (p.Length == 4 && int.TryParse(p[1], out a) && int.TryParse(p[2], out b) && int.TryParse(p[3], out c))
                    {
                        OnScrollInfo(a, b, c);
                    }
                    break;
                case "k":
                    if (p.Length == 2 && int.TryParse(p[1], out a))
                    {
                        HandleBrowserKey(a);
                    }
                    break;
                case "c":
                    if (p.Length == 5 && int.TryParse(p[1], out a))
                    {
                        HandleBrowserCombo(a, p[2] == "1", p[3] == "1", p[4] == "1");
                    }
                    break;
            }
        }

        private void ShutdownWebView()
        {
            try
            {
                webBrowser1.Dispose();
            }
            catch
            {
            }
            try
            {
                if (pagesFolder != null && Directory.Exists(pagesFolder))
                {
                    Directory.Delete(pagesFolder, true);
                }
            }
            catch
            {
            }
        }

        // اجرای یک اسکریپت توی صفحه‌ی کتاب. اگه نمایشگر آماده نباشه یا خطا بده null برمی‌گردونه
        private async Task<string> RunScriptAsync(string js)
        {
            try
            {
                if (webBrowser1.CoreWebView2 == null)
                {
                    return null;
                }
                return await webBrowser1.ExecuteScriptAsync(js);
            }
            catch
            {
                return null;
            }
        }

        // نتیجه‌ی اسکریپت به شکل JSON برمی‌گرده؛ اینجا به متن ساده تبدیل می‌شه
        private static string JsonToString(string json)
        {
            if (string.IsNullOrEmpty(json) || json == "null")
            {
                return null;
            }
            if (json.Length >= 2 && json[0] == '"' && json[json.Length - 1] == '"')
            {
                return json.Substring(1, json.Length - 2);
            }
            return json;
        }

        // ---- کلیدهای میانبر داخل صفحه‌ی کتاب ----
        private static bool TryParseCombo(string combo, out int vk, out bool ctrl, out bool shift, out bool alt)
        {
            vk = 0;
            ctrl = false;
            shift = false;
            alt = false;
            if (string.IsNullOrEmpty(combo))
            {
                return false;
            }
            string[] parts = combo.Split('+');
            for (int i = 0; i < parts.Length - 1; i++)
            {
                if (parts[i] == "Ctrl") ctrl = true;
                else if (parts[i] == "Shift") shift = true;
                else if (parts[i] == "Alt") alt = true;
            }
            string name = parts[parts.Length - 1];
            if (name.Length == 1 && name[0] >= '0' && name[0] <= '9')
            {
                name = "D" + name;
            }
            else if (name.StartsWith("Num", StringComparison.Ordinal) && !name.StartsWith("NumPad", StringComparison.Ordinal) && name != "NumLock")
            {
                name = "NumPad" + name.Substring(3);
            }
            Key key;
            if (!Enum.TryParse<Key>(name, true, out key) || key == Key.None)
            {
                return false;
            }
            vk = KeyInterop.VirtualKeyFromKey(key);
            return vk > 0;
        }

        private string BuildShortcutKeyMapJs()
        {
            StringBuilder sb = new StringBuilder("{");
            foreach (ShortcutAction action in ShortcutService.Actions)
            {
                if (!action.Id.StartsWith("rd.", StringComparison.Ordinal))
                {
                    continue;
                }
                int vk;
                bool ctrl;
                bool shift;
                bool alt;
                if (!TryParseCombo(ShortcutService.Get(action.Id), out vk, out ctrl, out shift, out alt))
                {
                    continue;
                }
                sb.Append("'").Append(vk).Append(":").Append(ctrl ? 1 : 0).Append(shift ? 1 : 0).Append(alt ? 1 : 0).Append("':1,");
            }
            sb.Append("}");
            return sb.ToString();
        }

        private void PushShortcutsToPage()
        {
            if (currentBook == null || webBrowser1.CoreWebView2 == null)
            {
                return;
            }
            RunScriptAsync("window.__rdrKeys=" + BuildShortcutKeyMapJs() + ";");
        }

        // ---- فونت‌های شخصی و پیشنهادی ----
        // این فونت‌ها فقط برای خود برنامه ثبت می‌شن و موتور نمایش (WebView2) اون‌ها رو نمی‌بینه؛
        // برای همین فایل فونتِ انتخاب‌شده به صورت @font-face داخل صفحه گذاشته می‌شه.
        private string BuildFontFaceCss(string family)
        {
            if (string.IsNullOrEmpty(family))
            {
                return "";
            }
            string cached;
            if (fontFaceCache.TryGetValue(family, out cached))
            {
                return cached;
            }
            StringBuilder css = new StringBuilder();
            try
            {
                foreach (CustomFontInfo f in CustomFontService.GetAll())
                {
                    if (string.Equals(f.FamilyName, family, StringComparison.OrdinalIgnoreCase) && File.Exists(f.FullPath))
                    {
                        AppendFontFace(css, f.FamilyName, f.FullPath);
                    }
                }
                string recommendedFolder = Path.Combine(Environment.GetFolderPath(Environment.SpecialFolder.ApplicationData), "EpubReaderApp", "RecommendedFonts");
                if (css.Length == 0 && Directory.Exists(recommendedFolder))
                {
                    foreach (string file in Directory.GetFiles(recommendedFolder))
                    {
                        string ext = Path.GetExtension(file).ToLowerInvariant();
                        if (ext != ".ttf" && ext != ".otf")
                        {
                            continue;
                        }
                        string fileFamily;
                        if (!recommendedFamilyCache.TryGetValue(file, out fileFamily))
                        {
                            fileFamily = CustomFontService.ReadFamilyName(file);
                            recommendedFamilyCache[file] = fileFamily;
                        }
                        if (string.Equals(fileFamily, family, StringComparison.OrdinalIgnoreCase))
                        {
                            AppendFontFace(css, fileFamily, file);
                        }
                    }
                }
            }
            catch
            {
            }
            string result = css.ToString();
            if (result.Length > 0)
            {
                fontFaceCache[family] = result;
            }
            return result;
        }

        private static void AppendFontFace(StringBuilder css, string family, string path)
        {
            byte[] data = File.ReadAllBytes(path);
            string lowerName = Path.GetFileNameWithoutExtension(path).ToLowerInvariant();
            string weight = (lowerName.IndexOf("bold", StringComparison.Ordinal) >= 0) ? "700" : "400";
            string mime = path.EndsWith(".otf", StringComparison.OrdinalIgnoreCase) ? "font/otf" : "font/ttf";
            css.Append("@font-face{font-family:'").Append(CssQuote(family)).Append("';font-weight:").Append(weight)
                .Append(";src:url(data:").Append(mime).Append(";base64,").Append(Convert.ToBase64String(data)).Append(");}");
        }

        private string EscapeForJs(string s)
        {
            if (string.IsNullOrEmpty(s))
            {
                return "";
            }
            return s.Replace("\\", "\\\\").Replace("'", "\\'").Replace("\r", "")
                .Replace("\n", "\\n")
                .Replace("\u2028", "\\u2028")
                .Replace("\u2029", "\\u2029");
        }
    }

}