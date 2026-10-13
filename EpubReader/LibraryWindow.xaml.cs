using System;
using System.Collections.Generic;
using System.IO;
using System.Linq;
using System.Windows;
using System.Windows.Controls;
using System.Windows.Input;
using System.Windows.Markup;
using System.Windows.Media;
using System.Windows.Media.Imaging;
using System.Windows.Media.Animation;
using System.Windows.Controls.Primitives;
using System.Windows.Threading;
using System.Threading.Tasks;
using EpubReader.Models;
using EpubReader.Services;
using Microsoft.Win32;
using Microsoft.Web.WebView2.Core;
using System.Globalization;

namespace EpubReader
{

    public partial class LibraryWindow : Window
    {
        private List<Book> books = new List<Book>();

        private string currentTab = "all";

        public static bool PrivateUnlockedThisSession = false;

        private Book heroBook;

        // اندازه‌ی کارت‌ها: با عرض پنجره تنظیم می‌شه تا ردیف‌ها یکدست پر بشن
        public static readonly DependencyProperty CardWidthProperty = DependencyProperty.Register("CardWidth", typeof(double), typeof(LibraryWindow), new PropertyMetadata(176.0));

        public static readonly DependencyProperty CoverHeightProperty = DependencyProperty.Register("CoverHeight", typeof(double), typeof(LibraryWindow), new PropertyMetadata(236.0));

        public double CardWidth
        {
            get { return (double)GetValue(CardWidthProperty); }
            set { SetValue(CardWidthProperty, value); }
        }

        public double CoverHeight
        {
            get { return (double)GetValue(CoverHeightProperty); }
            set { SetValue(CoverHeightProperty, value); }
        }

        // فقط وقتی تب عوض می‌شه یا برنامه تازه باز شده، کارت‌ها با انیمیشن ظاهر می‌شن (نه موقع تایپ در جستجو)
        private bool animateCards = false;

        // حالت مرتب‌سازی و نمای کتاب‌ها (شبکه‌ای / لیستی)
        private int sortMode = 0;

        private bool listView = false;

        private DataTemplate gridTemplate;

        private ItemsPanelTemplate gridPanel;

        public LibraryWindow()
        {
            InitializeComponent();
            Loc.Prepare(this);
            WindowMax.Attach(this, null);
            LibraryService.CleanupUnusedCovers();
            RefreshGreeting();
            // اگه برنامه از یه بازه‌ی زمانی به بعدی برسه (مثلاً از عصر به شب)، متن خودش عوض بشه
            System.Windows.Threading.DispatcherTimer greetingTimer = new System.Windows.Threading.DispatcherTimer();
            greetingTimer.Interval = TimeSpan.FromMinutes(1);
            greetingTimer.Tick += (object s, EventArgs ev) => RefreshGreeting();
            greetingTimer.Start();
            Closed += (object s, EventArgs ev) =>
            {
                greetingTimer.Stop();
                try
                {
                    if (prewarmedReader != null)
                    {
                        prewarmedReader.Close();
                        prewarmedReader = null;
                    }
                }
                catch
                {
                }
            };
            contentPanel.SizeChanged += (object s, SizeChangedEventArgs ev) => UpdateCardSizes();
            gridTemplate = listBoxBooks.ItemTemplate;
            gridPanel = listBoxBooks.ItemsPanel;
            LoadViewPreferences();
            ApplyFilterAnimated(true);
            UpdateTabStyles();
            UpdateViewButtons();
            themeParticles = new ThemeParticleLayer();
            Grid.SetColumn(themeParticles, 1);
            // پشت محتوا (نه روش) تا افکت سایه‌ی کارت‌ها هر فریم دوباره محاسبه نشه
            rootGrid.Children.Insert(rootGrid.Children.IndexOf(contentPanel), themeParticles);
            RefreshAppThemeUi();
            RefreshProfileUi();
            UpdateLanguageButton();

            Loaded += LibraryWindow_Loaded;
        }
        private void Profile_MouseLeftButtonUp(object sender, MouseButtonEventArgs e)
        {
            SetBackdrop(true);

            WelcomeWindow win = new WelcomeWindow(ProfileService.HasProfile, ProfileService.UserName);
            win.Owner = this;
            win.ShowDialog();

            SetBackdrop(false);
            RefreshProfileUi();
        }
        private void Profile_MouseLeftButtonDown(object sender, MouseButtonEventArgs e)
        {
            // جلوی کشیدن پنجره را می‌گیرد تا کلیک روی پروفایل از بین نرود
            e.Handled = true;
        }
        // اندازه‌ی حلقه‌ی پیشرفت دور عکس (باید با XAML یکی باشد)
        private const double RingSize = 72.0;
        private const double RingThickness = 3.0;

        // لقب کتابخوان بر اساس تعداد کتاب‌های تمام‌شده (جدول رتبه‌ها در RankService)
        private static string RankFor(int done)
        {
            return RankService.NameFor(done);
        }

        private ThemeParticleLayer themeParticles;

        private int CompletedCount()
        {
            return books == null ? 0 : books.Count((Book b) => !b.IsPrivate && b.IsCompleted);
        }

        // اگه رتبه بالا رفته باشه تم‌های تازه باز می‌شن و جشن گرفته می‌شه
        private void CheckRankUp(int done)
        {
            int newRank = RankService.RankIndexFor(done);
            int oldMax = RankService.MaxRankReached;
            if (newRank <= oldMax)
            {
                return;
            }
            RankService.MaxRankReached = newRank;
            if (oldMax < 0 && newRank == 0)
            {
                return;
            }
            int fromRank = Math.Max(0, oldMax) + (oldMax < 0 ? 0 : 1);
            Dispatcher.BeginInvoke(new Action(() => AnnounceUnlocks(fromRank, newRank)), DispatcherPriority.ApplicationIdle);
        }

        private void AnnounceUnlocks(int fromRank, int toRank)
        {
            List<CharacterTheme> fresh = new List<CharacterTheme>();
            foreach (CharacterTheme t in CharacterThemes.All)
            {
                if (t.RequiredRank >= fromRank && t.RequiredRank <= toRank && t.RequiredRank > 0)
                {
                    fresh.Add(t);
                }
            }
            if (fresh.Count == 0)
            {
                return;
            }
            string names = "";
            for (int i = 0; i < fresh.Count; i++)
            {
                names += (i == 0 ? "" : (i == fresh.Count - 1 ? Loc.And : Loc.ListSeparator)) + Loc.Q(fresh[i].Name);
            }
            CharacterTheme best = fresh[fresh.Count - 1];
            MessageBoxResult answer = DarkMessageBox.Show(
                Loc.F("رتبه‌ات شد {0}!\nتم {1} برات باز شد.\n\nهمین الان تم {2} رو فعال کنم؟", Loc.Q(RankService.Get(toRank).Name), names, Loc.Q(best.Name)),
                Loc.T("تم جدید باز شد"), MessageBoxButton.YesNo, MessageBoxImage.Information);
            if (answer == MessageBoxResult.Yes)
            {
                ThemeManager.SetCharacter(best.Id, true);
                RefreshAppThemeUi();
            }
        }

        private void btnThemes_Click(object sender, RoutedEventArgs e)
        {
            CharacterThemeWindow.ShowFor(this, CompletedCount(), RefreshAppThemeUi);
        }

        // سلام بر اساس ساعت، لقب، تعداد کتاب‌ها و حلقه‌ی پیشرفت (تمام‌شده / همه). کتاب‌های خصوصی حساب نمی‌شوند
        private void UpdateProfileStats()
        {
            if (books == null) return;

            int total = books.Count((Book b) => !b.IsPrivate);
            int done = books.Count((Book b) => !b.IsPrivate && b.IsCompleted);

            txtProfileCount.Text = done > 0 ? Loc.FN("{0} کتاب · {1} تمام‌شده", total, total, done) : Loc.FN("{0} کتاب", total, total);
            txtRank.Text = RankFor(done);
            CheckRankUp(done);
            txtThemeCount.Text = RankService.UnlockedCount() + "/" + CharacterThemes.VisibleCount();

            double fraction = total > 0 ? (double)done / total : 0.0;
            if (fraction <= 0.0)
            {
                ringProgress.Visibility = Visibility.Collapsed;
            }
            else
            {
                double circumference = Math.PI * (RingSize - RingThickness) / RingThickness;
                ringProgress.StrokeDashArray = new DoubleCollection(new double[] { fraction * circumference, circumference });
                ringProgress.Visibility = Visibility.Visible;
            }
        }

        private static BitmapImage defaultAvatarLogo;

        private void RefreshProfileUi()
        {
            string name = ProfileService.UserName;
            txtProfileName.Text = string.IsNullOrWhiteSpace(name) ? Loc.T("کتابخوان من") : name;
            RefreshAvatar();
            UpdateProfileStats();
        }

        // عکس کاربر (اگر گذاشته) یا لوگوی برنامه را داخل قاب نشان می‌دهد
        private void RefreshAvatar()
        {
            BitmapSource image = null;
            string path = ProfileService.AvatarPath;
            bool custom = false;
            if (path != null)
            {
                image = ProfileService.LoadAvatarImage(path);
                custom = image != null;
            }
            if (image == null)
            {
                if (defaultAvatarLogo == null)
                {
                    BitmapImage logo = new BitmapImage();
                    logo.BeginInit();
                    logo.UriSource = new Uri("pack://application:,,,/Assets/AppLogo.png", UriKind.Absolute);
                    logo.CacheOption = BitmapCacheOption.OnLoad;
                    logo.EndInit();
                    logo.Freeze();
                    defaultAvatarLogo = logo;
                }
                image = defaultAvatarLogo;
            }

            ImageBrush brush = new ImageBrush(image);
            brush.Stretch = Stretch.Uniform; // مثل SizeMode = Zoom در PictureBox: کل عکس داخل قاب دیده می‌شود
            brush.AlignmentX = AlignmentX.Center;
            brush.AlignmentY = AlignmentY.Center;
            avatarFrame.Background = brush;
            // با نگه‌داشتن ماوس روی عکس، کل عکس (بدون برش دایره) بزرگ و واضح نشان داده می‌شود
            Image big = new Image();
            big.Source = image;
            big.Stretch = Stretch.Uniform;
            big.MaxWidth = 220;
            big.MaxHeight = 220;
            RenderOptions.SetBitmapScalingMode(big, BitmapScalingMode.HighQuality);

            TextBlock hint = new TextBlock();
            hint.Text = Loc.T(custom ? "کلیک: تغییر عکس  |  کلیک راست: حذف عکس" : "کلیک: انتخاب عکس پروفایل");
            hint.FontSize = 11;
            hint.TextAlignment = TextAlignment.Center;
            hint.Margin = new Thickness(0, 8, 0, 0);

            StackPanel tipPanel = new StackPanel();
            tipPanel.FlowDirection = Loc.Flow;
            tipPanel.Children.Add(big);
            tipPanel.Children.Add(hint);

            ToolTip tip = new ToolTip();
            tip.Padding = new Thickness(8);
            tip.Content = tipPanel;
            avatarHost.ToolTip = tip;
            ToolTipService.SetInitialShowDelay(avatarHost, 350);
        }

        private void Avatar_MouseLeftButtonDown(object sender, MouseButtonEventArgs e)
        {
            // جلوی کشیدن پنجره و باز شدن پنجره‌ی تغییر اسم را می‌گیرد
            e.Handled = true;
        }

        private void Avatar_MouseLeftButtonUp(object sender, MouseButtonEventArgs e)
        {
            e.Handled = true;
            ChangeAvatar();
        }

        private void Avatar_MouseRightButtonUp(object sender, MouseButtonEventArgs e)
        {
            e.Handled = true;
            RemoveAvatar();
        }

        private void Avatar_MouseEnter(object sender, MouseEventArgs e)
        {
            avatarOverlay.Visibility = Visibility.Visible;
        }

        private void Avatar_MouseLeave(object sender, MouseEventArgs e)
        {
            avatarOverlay.Visibility = Visibility.Collapsed;
        }

        private void ChangeAvatar()
        {
            OpenFileDialog dlg = new OpenFileDialog();
            dlg.Title = Loc.T("انتخاب عکس پروفایل");
            dlg.Filter = Loc.T("تصاویر") + "|*.jpg;*.jpeg;*.png;*.bmp;*.gif;*.webp";
            if (dlg.ShowDialog(this) != true)
            {
                return;
            }
            if (ProfileService.LoadAvatarImage(dlg.FileName) == null)
            {
                DarkMessageBox.Show("این عکس باز نشد. یه عکس JPG یا PNG انتخاب کن.", "خطا", MessageBoxButton.OK, MessageBoxImage.Exclamation);
                return;
            }
            if (!ProfileService.SaveAvatar(dlg.FileName))
            {
                DarkMessageBox.Show("ذخیره‌ی عکس انجام نشد. یه عکس دیگه امتحان کن.", "خطا", MessageBoxButton.OK, MessageBoxImage.Exclamation);
                return;
            }
            RefreshAvatar();
        }

        private void RemoveAvatar()
        {
            if (ProfileService.AvatarPath == null)
            {
                return;
            }
            MessageBoxResult result = DarkMessageBox.Show("عکس پروفایل حذف بشه و لوگوی برنامه برگرده؟", "حذف عکس", MessageBoxButton.YesNo, MessageBoxImage.Question);
            if (result == MessageBoxResult.Yes)
            {
                ProfileService.ClearAvatar();
                RefreshAvatar();
            }
        }
        private void LibraryWindow_Loaded(object sender, RoutedEventArgs e)
        {
            Loaded -= LibraryWindow_Loaded;

            PlayEntrance();
            Dispatcher.BeginInvoke(new Action(WarmUpReader), DispatcherPriority.ApplicationIdle);

            if (ProfileService.HasProfile)
            {
                OpenStartupFile();
                return;
            }

            SetBackdrop(true);

            WelcomeWindow win = new WelcomeWindow();
            win.Owner = this;
            bool? ok = win.ShowDialog();

            SetBackdrop(false);

            if (ok == true)
            {
                ProfileService.SaveName(win.EnteredName);
                RefreshProfileUi();
            }
            OpenStartupFile();
        }

        // ظاهر شدن نرم پنجره‌ی کتابخانه موقع باز شدن
        private void PlayEntrance()
        {
            try
            {
                QuadraticEase ease = new QuadraticEase { EasingMode = EasingMode.EaseOut };

                sidebarPanel.Opacity = 0.0;
                sidebarPanel.BeginAnimation(UIElement.OpacityProperty, new DoubleAnimation(0.0, 1.0, new Duration(TimeSpan.FromMilliseconds(420))) { EasingFunction = ease });

                TranslateTransform slide = new TranslateTransform(0.0, 16.0);
                contentPanel.RenderTransform = slide;
                contentPanel.Opacity = 0.0;
                DoubleAnimation fade = new DoubleAnimation(0.0, 1.0, new Duration(TimeSpan.FromMilliseconds(460))) { BeginTime = TimeSpan.FromMilliseconds(80), EasingFunction = ease };
                fade.Completed += (object s2, EventArgs e2) =>
                {
                    contentPanel.BeginAnimation(UIElement.OpacityProperty, null);
                    contentPanel.Opacity = 1.0;
                    contentPanel.RenderTransform = Transform.Identity;
                };
                contentPanel.BeginAnimation(UIElement.OpacityProperty, fade);
                slide.BeginAnimation(TranslateTransform.YProperty, new DoubleAnimation(16.0, 0.0, new Duration(TimeSpan.FromMilliseconds(460))) { BeginTime = TimeSpan.FromMilliseconds(80), EasingFunction = ease });
            }
            catch
            {
                sidebarPanel.BeginAnimation(UIElement.OpacityProperty, null);
                sidebarPanel.Opacity = 1.0;
                contentPanel.BeginAnimation(UIElement.OpacityProperty, null);
                contentPanel.Opacity = 1.0;
            }
        }

        // اگه برنامه با یه فایل EPUB باز شده (دابل‌کلیک توی ویندوز)، همون فایل رو باز می‌کنه
        private void OpenStartupFile()
        {
            string path = App.StartupFile;
            App.StartupFile = null;
            OpenExternalFile(path);
        }

        // فایل EPUB رو (اگه توی کتابخونه نیست اضافه می‌کنه) و باز می‌کنه
        public void OpenExternalFile(string path)
        {
            if (string.IsNullOrEmpty(path) || !File.Exists(path))
            {
                return;
            }
            try
            {
                Book target = LibraryService.LoadBooks().Find((Book b) => string.Equals(b.FilePath, path, StringComparison.OrdinalIgnoreCase));
                if (target == null)
                {
                    LibraryService.AddOrUpdateBook(ImportEpub(path));
                    LoadLibrary();
                    target = LibraryService.LoadBooks().Find((Book b) => string.Equals(b.FilePath, path, StringComparison.OrdinalIgnoreCase));
                }
                if (target != null)
                {
                    OpenSingleBook(target);
                }
            }
            catch (Exception ex)
            {
                DarkMessageBox.Show(Loc.T("خطا در باز کردن فایل:\n") + ex.Message, "خطا", MessageBoxButton.OK, MessageBoxImage.Hand);
            }
        }
        private void SetBackdrop(bool on)
        {
            dimOverlay.Visibility = on ? Visibility.Visible : Visibility.Collapsed;
            System.Windows.Media.Effects.Effect blur = on
                ? new System.Windows.Media.Effects.BlurEffect { Radius = 14 }
                : null;
            sidebarPanel.Effect = blur;
            contentPanel.Effect = blur;
        }
        // بازه‌ی روز: صبح (۵ تا ۱۲)، ظهر (۱۲ تا ۱۷)، عصر (۱۷ تا ۲۱)، شب (بقیه)
        private static string GetPeriod()
        {
            int h = DateTime.Now.Hour;
            if (h >= 5 && h < 12)
            {
                return "صبح";
            }
            if (h >= 12 && h < 17)
            {
                return "ظهر";
            }
            if (h >= 17 && h < 21)
            {
                return "عصر";
            }
            return "شب";
        }

        private void RefreshGreeting()
        {
            string period = GetPeriod();
            txtGreeting.Text = Loc.T(period + " بخیر");
            txtGreetingSub.Text = Loc.T(period + " چی می‌خونی؟");
        }

        private void UpdateHero(bool show)
        {
            heroBook = (show ? (from b in books
                                where !b.IsPrivate && b.IsInProgress
                                orderby b.LastOpenedDate descending
                                select b).FirstOrDefault() : null);
            if (heroBook == null)
            {
                heroCard.Visibility = Visibility.Collapsed;
                return;
            }
            heroCard.Visibility = Visibility.Visible;
            txtHeroTitle.Text = heroBook.DisplayTitle;
            txtHeroTitle.FlowDirection = Loc.Flow;
            Loc.FixTextDirection(txtHeroTitle);
            txtHeroChapter.Text = Loc.F("فصل {0} از {1}", heroBook.LastChapterDisplay, heroBook.TotalChapters);
            progressHero.BeginAnimation(RangeBase.ValueProperty, null);
            progressHero.SetResourceReference(Control.ForegroundProperty, heroBook.IsCompleted ? "SuccessColor" : "AccentColor");
            progressHero.Value = heroBook.ProgressPercent;
            if (animateCards && heroBook.ProgressPercent > 0.0)
            {
                DoubleAnimation fill = new DoubleAnimation(0.0, heroBook.ProgressPercent, new Duration(TimeSpan.FromMilliseconds(700)));
                fill.EasingFunction = new QuadraticEase { EasingMode = EasingMode.EaseOut };
                progressHero.BeginAnimation(RangeBase.ValueProperty, fill);
            }
            txtHeroPercent.Text = Math.Round(heroBook.ProgressPercent) + Loc.Percent;
            imgHeroCover.Source = LoadImage(heroBook.CoverImagePath, 320);
        }

        private static ImageSource LoadImage(string path, int decodeWidth = 0)
        {
            if (string.IsNullOrEmpty(path) || !File.Exists(path))
            {
                return null;
            }
            try
            {
                BitmapImage bmp = new BitmapImage();
                bmp.BeginInit();
                bmp.CacheOption = BitmapCacheOption.OnLoad;
                if (decodeWidth > 0)
                {
                    bmp.DecodePixelWidth = decodeWidth;
                }
                bmp.UriSource = new Uri(path, UriKind.Absolute);
                bmp.EndInit();
                bmp.Freeze();
                return bmp;
            }
            catch
            {
                return null;
            }
        }

        private void btnHeroContinue_Click(object sender, RoutedEventArgs e)
        {
            if (heroBook != null)
            {
                OpenSingleBook(heroBook);
            }
        }

        private void LoadLibrary()
        {
            LoadBooksOnly();
            ApplyFilter();
        }

        private void LoadBooksOnly()
        {
            books = LibraryService.LoadBooks();
            UpdateProfileStats();
        }

        // لیست رو با انیمیشن ظاهر شدن کارت‌ها می‌سازه
        private void ApplyFilterAnimated(bool reloadBooks)
        {
            if (reloadBooks)
            {
                LoadBooksOnly();
            }
            animateCards = true;
            ApplyFilter();
            Dispatcher.BeginInvoke(new Action(() => { animateCards = false; }), DispatcherPriority.ContextIdle);
        }

        private void UpdateCardSizes()
        {
            // حاشیه‌ها: ScrollViewer 8 + StackPanel 36 و 28 + کمی فضای اطمینان برای اسکرول‌بار
            double avail = contentPanel.ActualWidth - 8 - 64 - 14;
            if (avail <= 0)
            {
                return;
            }
            const double minCard = 176.0;
            const double gap = 20.0;
            int cols = Math.Max(1, (int)Math.Floor(avail / (minCard + gap)));
            double width = Math.Floor(avail / cols) - gap;
            width = Math.Max(140.0, Math.Min(260.0, width));
            CardWidth = width;
            CoverHeight = Math.Round(width * 236.0 / 176.0);
        }

        // ظاهر شدن نرم هر کارت: محو به پررنگ + کمی حرکت از پایین، با تأخیر کم بین کارت‌ها
        private void CardRoot_Loaded(object sender, RoutedEventArgs e)
        {
            FrameworkElement card = sender as FrameworkElement;
            if (card == null || card.Opacity > 0.99)
            {
                return;
            }
            try
            {
                if (!animateCards)
                {
                    card.Opacity = 1.0;
                    return;
                }
                int index = 0;
                System.Collections.IList items = listBoxBooks.ItemsSource as System.Collections.IList;
                if (items != null)
                {
                    int found = items.IndexOf(card.DataContext);
                    if (found > 0)
                    {
                        index = found;
                    }
                }
                TimeSpan delay = TimeSpan.FromMilliseconds(Math.Min(index, 14) * 35);
                Duration duration = new Duration(TimeSpan.FromMilliseconds(320));
                QuadraticEase ease = new QuadraticEase { EasingMode = EasingMode.EaseOut };

                TranslateTransform move = new TranslateTransform(0.0, 18.0);
                card.RenderTransform = move;
                move.BeginAnimation(TranslateTransform.YProperty, new DoubleAnimation(18.0, 0.0, duration) { BeginTime = delay, EasingFunction = ease });

                DoubleAnimation fade = new DoubleAnimation(0.0, 1.0, duration) { BeginTime = delay, EasingFunction = ease };
                fade.Completed += (object s2, EventArgs e2) =>
                {
                    card.BeginAnimation(UIElement.OpacityProperty, null);
                    card.Opacity = 1.0;
                    card.RenderTransform = Transform.Identity;
                };
                card.BeginAnimation(UIElement.OpacityProperty, fade);
            }
            catch
            {
                card.BeginAnimation(UIElement.OpacityProperty, null);
                card.Opacity = 1.0;
            }
        }

        private void ApplyFilter()
        {
            if (books == null || listBoxBooks == null || heroCard == null)
            {
                return;
            }
            IEnumerable<Book> filteredBooks = books;
            if (currentTab == "private")
            {
                filteredBooks = filteredBooks.Where((Book book) => book.IsPrivate);
            }
            else
            {
                filteredBooks = filteredBooks.Where((Book book) => !book.IsPrivate);
                switch (currentTab)
                {
                    case "reading":
                        filteredBooks = filteredBooks.Where((Book book) => book.IsInProgress);
                        break;
                    case "completed":
                        filteredBooks = filteredBooks.Where((Book book) => book.IsCompleted);
                        break;
                    case "favorites":
                        filteredBooks = filteredBooks.Where((Book book) => book.IsFavorite);
                        break;
                    case "recent":
                        filteredBooks = from book in filteredBooks
                                        where !string.IsNullOrEmpty(book.LastOpenedDate)
                                        orderby book.LastOpenedDate descending
                                        select book;
                        break;
                }
            }
            string query = ((txtSearch.Text != null) ? txtSearch.Text.Trim() : "");
            bool isSearching = !string.IsNullOrEmpty(query);
            if (isSearching)
            {
                filteredBooks = filteredBooks.Where((Book book) => (book.DisplayTitle != null && book.DisplayTitle.IndexOf(query, StringComparison.OrdinalIgnoreCase) >= 0) || (book.Title != null && book.Title.IndexOf(query, StringComparison.OrdinalIgnoreCase) >= 0));
            }
            List<Book> bookList = SortBooks(filteredBooks.ToList());
            List<LibraryCardItem> cardItems = new List<LibraryCardItem>();
            if (currentTab == "all" && !isSearching)
            {
                IEnumerable<IGrouping<string, Book>> grouped = from book in bookList
                                                               where !string.IsNullOrEmpty(book.CollectionName)
                                                               group book by book.CollectionName;
                HashSet<string> groupedFilePaths = new HashSet<string>();
                foreach (IGrouping<string, Book> group in grouped)
                {
                    List<Book> groupBooks = group.OrderBy((Book book) => book.DisplayTitle, NaturalStringComparer.Instance).ToList();
                    foreach (Book gb in groupBooks)
                    {
                        groupedFilePaths.Add(gb.FilePath);
                    }
                    Book coverBook = groupBooks.FirstOrDefault((Book book) => !string.IsNullOrEmpty(book.CoverImagePath)) ?? groupBooks.First();
                    int doneCount = groupBooks.Count((Book book) => book.IsCompleted);
                    cardItems.Add(new LibraryCardItem
                    {
                        IsCollection = true,
                        Title = group.Key,
                        CoverImagePath = coverBook.CoverImagePath,
                        SubtitleText = Loc.FN("{0} جلد", groupBooks.Count, groupBooks.Count),
                        ProgressText = Loc.F("{0} از {1} جلد تموم شده", doneCount, groupBooks.Count),
                        ProgressPercent = groupBooks.Average((Book book) => book.ProgressPercent),
                        IsCompleted = (doneCount == groupBooks.Count),
                        PercentText = ((doneCount == groupBooks.Count) ? 100 : (int)Math.Min(99.0, Math.Floor(groupBooks.Average((Book book) => book.ProgressPercent)))) + Loc.Percent,
                        CollectionName = group.Key,
                        CollectionBooks = groupBooks
                    });
                }
                foreach (Book b in bookList)
                {
                    if (!groupedFilePaths.Contains(b.FilePath))
                    {
                        cardItems.Add(BuildSingleCard(b));
                    }
                }
            }
            else
            {
                foreach (Book b2 in bookList)
                {
                    cardItems.Add(BuildSingleCard(b2));
                }
            }
            listBoxBooks.ItemsSource = null;
            listBoxBooks.ItemsSource = cardItems;
            txtEmpty.Visibility = ((cardItems.Count != 0) ? Visibility.Collapsed : Visibility.Visible);
            UpdateHero(currentTab == "all" && !isSearching);
            UpdateStats();
            statsStrip.Visibility = ((currentTab == "all" && !isSearching && books.Any((Book book) => !book.IsPrivate)) ? Visibility.Visible : Visibility.Collapsed);
            string section = GetSectionTitle(currentTab);
            txtSectionTitle.Text = Loc.T(isSearching ? "نتایج جستجو" : section);
            txtSectionCount.Text = Loc.FN("{0} کتاب", bookList.Count, bookList.Count);
        }

        private static string GetSectionTitle(string tab)
        {
            switch (tab)
            {
                case "reading":
                    return "در حال مطالعه";
                case "completed":
                    return "تمام\u200cشده";
                case "favorites":
                    return "علاقه\u200cمندی\u200cها";
                case "recent":
                    return "اخیرا\u064b بازشده";
                case "private":
                    return "کتاب\u200cهای خصوصی";
                default:
                    return "کتاب\u200cهای من";
            }
        }

        private LibraryCardItem BuildSingleCard(Book b)
        {
            string progress = (string.IsNullOrEmpty(b.LastOpenedDate) ? Loc.FN("{0} فصل · شروع نشده", b.TotalChapters, b.TotalChapters) : Loc.F("فصل {0} از {1}", b.LastChapterDisplay, b.TotalChapters));
            LibraryCardItem libraryCardItem = new LibraryCardItem();
            libraryCardItem.IsCollection = false;
            libraryCardItem.Title = b.DisplayTitle;
            libraryCardItem.CoverImagePath = b.CoverImagePath;
            libraryCardItem.SubtitleText = Loc.FN("{0} فصل", b.TotalChapters, b.TotalChapters);
            libraryCardItem.ProgressText = progress;
            libraryCardItem.ProgressPercent = b.ProgressPercent;
            libraryCardItem.SingleBook = b;
            libraryCardItem.IsCompleted = b.IsCompleted;
            libraryCardItem.IsFavorite = b.IsFavorite;
            libraryCardItem.PercentText = b.PercentDisplay + Loc.Percent;
            return libraryCardItem;
        }

        private void TabButton_Click(object sender, RoutedEventArgs e)
        {
            Button button = sender as Button;
            if (button == null)
            {
                return;
            }
            string targetTab = button.Tag.ToString();
            if (targetTab == "private" && !PrivateUnlockedThisSession)
            {
                if (!PinWindow.Authenticate())
                {
                    return;
                }
                PrivateUnlockedThisSession = true;
            }
            currentTab = targetTab;
            UpdateTabStyles();
            ApplyFilterAnimated(false);
        }

        private void UpdateTabStyles()
        {
            Brush selectedBrush = (Brush)FindResource("AccentColorDark");
            SolidColorBrush normalBrush = Brushes.Transparent;
            Brush selectedText = (Brush)FindResource("OnAccentDark");
            Brush normalText = (Brush)FindResource("TextMuted");
            SetTabAppearance(btnTabAll, "all", selectedBrush, normalBrush, selectedText, normalText);
            SetTabAppearance(btnTabFav, "favorites", selectedBrush, normalBrush, selectedText, normalText);
            SetTabAppearance(btnTabReading, "reading", selectedBrush, normalBrush, selectedText, normalText);
            SetTabAppearance(btnTabCompleted, "completed", selectedBrush, normalBrush, selectedText, normalText);
            SetTabAppearance(btnTabRecent, "recent", selectedBrush, normalBrush, selectedText, normalText);
            SetTabAppearance(btnTabPrivate, "private", selectedBrush, normalBrush, selectedText, normalText);
        }

        private void SetTabAppearance(Button button, string tabKey, Brush selectedBg, Brush normalBg, Brush selectedFg, Brush normalFg)
        {
            bool isSelected = currentTab == tabKey;
            button.Background = (isSelected ? selectedBg : normalBg);
            button.Foreground = (isSelected ? selectedFg : normalFg);
            button.FontWeight = (isSelected ? FontWeights.SemiBold : FontWeights.Normal);
        }

        private void btnShortcuts_Click(object sender, RoutedEventArgs e)
        {
            ShortcutsWindow.ShowFor(this);
        }

        private void btnAccent_Click(object sender, RoutedEventArgs e)
        {
            AccentWindow.ShowFor(this, RefreshAppThemeUi);
        }

        private void btnAbout_Click(object sender, RoutedEventArgs e)
        {
            AboutWindow.ShowFor(this);
        }

        // ---------- زبان برنامه ----------
        private void UpdateLanguageButton()
        {
            txtLanguage.Text = Loc.IsEnglish ? "EN" : "FA";
        }

        private void btnLanguage_Click(object sender, RoutedEventArgs e)
        {
            ContextMenu menu = new ContextMenu();
            menu.FlowDirection = FlowDirection.LeftToRight;

            MenuItem faItem = new MenuItem();
            faItem.Header = (Loc.IsEnglish ? "      " : "\u2713   ") + "\u0641\u0627\u0631\u0633\u06CC";
            faItem.Click += (object s, RoutedEventArgs args) => ChangeLanguage(Loc.Fa);
            menu.Items.Add(faItem);

            MenuItem enItem = new MenuItem();
            enItem.Header = (Loc.IsEnglish ? "\u2713   " : "      ") + "English";
            enItem.Click += (object s, RoutedEventArgs args) => ChangeLanguage(Loc.En);
            menu.Items.Add(enItem);

            menu.PlacementTarget = btnLanguage;
            menu.Placement = PlacementMode.Bottom;
            menu.IsOpen = true;
            e.Handled = true;
        }

        // زبان رو عوض و ذخیره می‌کنه و کل کتابخونه رو همون لحظه با زبان جدید دوباره می‌سازه
        private void ChangeLanguage(string lang)
        {
            if (lang == Loc.Lang)
            {
                return;
            }
            Loc.SetLanguage(lang);
            ApplyLanguage();
        }

        private void ApplyLanguage()
        {
            FlowDirection = Loc.Flow;
            // متن‌های ثابت XAML (سایدبار، دکمه‌ها، آیتم‌های مرتب‌سازی و ...)
            Loc.Translate(this);
            // متن‌هایی که کد برنامه می‌سازه
            RefreshGreeting();
            RefreshProfileUi();
            RefreshAppThemeUi();
            UpdateLanguageButton();
            if (books != null)
            {
                ApplyFilter();
            }
            // لیست مرتب‌سازی: متن انتخاب‌شده هم عوض بشه
            if (cmbSort != null)
            {
                ComboBoxItem selected = cmbSort.SelectedItem as ComboBoxItem;
                if (selected != null)
                {
                    restoringView = true;
                    try
                    {
                        int index = cmbSort.SelectedIndex;
                        cmbSort.SelectedIndex = -1;
                        cmbSort.SelectedIndex = index;
                    }
                    finally
                    {
                        restoringView = false;
                    }
                }
            }
            // پنجره‌ی خوانشِ از قبل آماده‌شده با زبان قبلی ساخته شده؛ دوباره ساخته می‌شه
            try
            {
                if (prewarmedReader != null)
                {
                    prewarmedReader.Close();
                    prewarmedReader = null;
                }
            }
            catch
            {
                prewarmedReader = null;
            }
            Dispatcher.BeginInvoke(new Action(PrewarmReaderWindow), DispatcherPriority.ApplicationIdle);
        }

        // ---------- ترفندهای مخفی کتابخانه (پشت صحنه) ----------
        // حروفی که کاربر پشت‌سرهم و به ترتیب می‌زنه توی یک حافظه‌ی پنهان نگه داشته می‌شه (حتی یکی‌یکی و با مکث)
        private string secretKeys = "";

        private static readonly string[][] secretThemeCodes = new string[][]
        {
            new string[] { "BEAKO", "beatrice" },
            new string[] { "EMT", "emilia" },
            new string[] { "TEA", "echidna" }
        };

        // اگه این کلید آخرین حرف یک ترفند باشه true برمی‌گردونه (و خودش کلید رو می‌خوره)
        private bool TrackSecretKeys(KeyEventArgs e)
        {
            Key k = e.Key;
            if (k == Key.System)
            {
                k = e.SystemKey;
            }
            else if (k == Key.ImeProcessed)
            {
                k = e.ImeProcessedKey;
            }
            else if (k == Key.DeadCharProcessed)
            {
                k = e.DeadCharProcessedKey;
            }

            switch (k)
            {
                case Key.None:
                case Key.LeftShift:
                case Key.RightShift:
                case Key.LeftCtrl:
                case Key.RightCtrl:
                case Key.LeftAlt:
                case Key.RightAlt:
                case Key.LWin:
                case Key.RWin:
                case Key.CapsLock:
                case Key.System:
                case Key.ImeProcessed:
                case Key.DeadCharProcessed:
                    return false;
            }
            if (e.IsRepeat)
            {
                return false;
            }
            ModifierKeys mods = Keyboard.Modifiers;
            if ((mods & (ModifierKeys.Control | ModifierKeys.Alt | ModifierKeys.Windows)) != 0 || k < Key.A || k > Key.Z)
            {
                // هر کلید دیگه‌ای (عدد، فاصله، فلش و ...) زنجیره رو قطع می‌کنه
                secretKeys = "";
                return false;
            }

            secretKeys += (char)('A' + (k - Key.A));
            if (secretKeys.Length > 16)
            {
                secretKeys = secretKeys.Substring(secretKeys.Length - 16);
            }
            foreach (string[] code in secretThemeCodes)
            {
                if (secretKeys.EndsWith(code[0], StringComparison.Ordinal))
                {
                    secretKeys = "";
                    CharacterTheme target = CharacterThemes.Find(code[1]);
                    if (target == null || RankService.IsUnlocked(target))
                    {
                        // این تم قبلاً باز شده: تایپ عادی ادامه پیدا می‌کنه و هیچ اتفاقی نمی‌افته
                        return false;
                    }
                    SecretTyped(code[1], code[0].Length);
                    return true;
                }
            }
            return false;
        }

        private void SecretTyped(string themeId, int letters)
        {
            // اگه حروف توی کادر جستجو تایپ شده بودن، از اونجا پاک می‌شن تا هیچ اثری نمونه
            try
            {
                int extra = letters - 1;
                if (txtSearch.IsKeyboardFocusWithin && extra > 0)
                {
                    int caret = txtSearch.CaretIndex;
                    string text = txtSearch.Text;
                    if (caret >= extra && caret <= text.Length)
                    {
                        txtSearch.Text = text.Remove(caret - extra, extra);
                        txtSearch.CaretIndex = caret - extra;
                    }
                }
            }
            catch
            {
            }

            CharacterTheme theme = CharacterThemes.Find(themeId);
            if (theme == null || RankService.IsUnlocked(theme))
            {
                return;
            }
            RankService.SecretUnlock(themeId);
            RefreshAppThemeUi();
            Dispatcher.BeginInvoke(new Action(() =>
            {
                MessageBoxResult answer = DarkMessageBox.Show(
                    Loc.F("تم {0} برات باز شد ✦\n\nهمین الان فعالش کنم؟", Loc.Q(theme.Name)),
                    Loc.T("تم جدید باز شد"), MessageBoxButton.YesNo, MessageBoxImage.Information);
                if (answer == MessageBoxResult.Yes)
                {
                    ThemeManager.SetCharacter(theme.Id, true);
                    RefreshAppThemeUi();
                }
            }), DispatcherPriority.ApplicationIdle);
        }

        // کلیدهای میانبر کتابخانه
        protected override void OnPreviewKeyDown(KeyEventArgs e)
        {
            base.OnPreviewKeyDown(e);
            if (e.Handled)
            {
                return;
            }
            if (TrackSecretKeys(e))
            {
                e.Handled = true;
                return;
            }
            string id = ShortcutService.Resolve(e, "lib.");
            if (id == null)
            {
                return;
            }
            ModifierKeys mods = Keyboard.Modifiers;
            if ((mods & (ModifierKeys.Control | ModifierKeys.Alt)) == 0 && Keyboard.FocusedElement is TextBox)
            {
                return;
            }
            e.Handled = true;
            RunLibraryAction(id);
        }

        private void RunLibraryAction(string id)
        {
            RoutedEventArgs args = new RoutedEventArgs();
            switch (id)
            {
                case "lib.add":
                    btnAddBook_Click(btnAddBook, args);
                    break;
                case "lib.search":
                    txtSearch.Focus();
                    txtSearch.SelectAll();
                    break;
                case "lib.tabAll":
                    TabButton_Click(btnTabAll, args);
                    break;
                case "lib.tabReading":
                    TabButton_Click(btnTabReading, args);
                    break;
                case "lib.tabCompleted":
                    TabButton_Click(btnTabCompleted, args);
                    break;
                case "lib.tabRecent":
                    TabButton_Click(btnTabRecent, args);
                    break;
                case "lib.tabPrivate":
                    TabButton_Click(btnTabPrivate, args);
                    break;
                case "lib.changePin":
                    btnChangePin_Click(btnChangePin, args);
                    break;
                case "lib.theme":
                    btnTheme_Click(btnTheme, args);
                    break;
                case "lib.maximize":
                    WindowMax.Toggle(this);
                    break;
                case "lib.minimize":
                    btnMinimize_Click(btnMinimize, args);
                    break;
                case "lib.close":
                    btnClose_Click(btnClose, args);
                    break;
            }
        }

        private void btnChangePin_Click(object sender, RoutedEventArgs e)
        {
            bool hadPin = SettingsService.HasPin();
            if (PinWindow.ChangePin())
            {
                DarkMessageBox.Show(hadPin ? "رمز خصوصی با موفقیت تغییر کرد." : "رمز خصوصی ساخته شد.", "تغییر رمز", MessageBoxButton.OK, MessageBoxImage.Asterisk);
            }
        }

        private void cmbSort_SelectionChanged(object sender, SelectionChangedEventArgs e)
        {
            if (cmbSort == null)
            {
                return;
            }
            sortMode = Math.Max(0, cmbSort.SelectedIndex);
            if (restoringView)
            {
                return;
            }
            AppSettings.Set("LibrarySort", sortMode.ToString());
            AppSettings.Save();
            if (books == null)
            {
                return;
            }
            ApplyFilterAnimated(false);
        }

        private void btnViewGrid_Click(object sender, RoutedEventArgs e)
        {
            SetViewMode(false);
        }

        private void btnViewList_Click(object sender, RoutedEventArgs e)
        {
            SetViewMode(true);
        }

        private void SetViewMode(bool list)
        {
            if (listView == list)
            {
                return;
            }
            listView = list;
            listBoxBooks.ItemTemplate = list ? (DataTemplate)FindResource("BookRowTemplate") : gridTemplate;
            listBoxBooks.ItemsPanel = list ? (ItemsPanelTemplate)FindResource("ListPanelTemplate") : gridPanel;
            UpdateViewButtons();
            AppSettings.Set("LibraryView", list ? "list" : "grid");
            AppSettings.Save();
            ApplyFilterAnimated(false);
        }

        private bool restoringView = false;

        // نمای کتاب‌ها (شبکه‌ای/لیستی) و مرتب‌سازی که دفعه‌ی قبل انتخاب شده بود رو برمی‌گردونه
        private void LoadViewPreferences()
        {
            restoringView = true;
            try
            {
                int savedSort;
                if (int.TryParse(AppSettings.Get("LibrarySort", "0"), out savedSort) && savedSort >= 0 && savedSort < cmbSort.Items.Count)
                {
                    sortMode = savedSort;
                    cmbSort.SelectedIndex = savedSort;
                }
                if (AppSettings.Get("LibraryView", "grid") == "list")
                {
                    listView = true;
                    listBoxBooks.ItemTemplate = (DataTemplate)FindResource("BookRowTemplate");
                    listBoxBooks.ItemsPanel = (ItemsPanelTemplate)FindResource("ListPanelTemplate");
                }
            }
            finally
            {
                restoringView = false;
            }
        }

        private void UpdateViewButtons()
        {
            ApplyViewButtonState(btnViewGrid, !listView);
            ApplyViewButtonState(btnViewList, listView);
        }

        private static void ApplyViewButtonState(Button button, bool active)
        {
            if (active)
            {
                button.SetResourceReference(Control.BackgroundProperty, "AccentSoft");
                button.SetResourceReference(Control.ForegroundProperty, "AccentColor");
            }
            else
            {
                button.Background = Brushes.Transparent;
                button.SetResourceReference(Control.ForegroundProperty, "TextMuted");
            }
        }

        private void Stat_Click(object sender, MouseButtonEventArgs e)
        {
            FrameworkElement fe = sender as FrameworkElement;
            if (fe == null || fe.Tag == null)
            {
                return;
            }
            currentTab = fe.Tag.ToString();
            UpdateTabStyles();
            ApplyFilterAnimated(false);
        }

        private void UpdateStats()
        {
            if (books == null || txtStatTotal == null)
            {
                return;
            }
            List<Book> visible = books.Where((Book b) => !b.IsPrivate).ToList();
            txtStatTotal.Text = visible.Count.ToString();
            txtStatReading.Text = visible.Count((Book b) => b.IsInProgress).ToString();
            txtStatDone.Text = visible.Count((Book b) => b.IsCompleted).ToString();
            txtStatFav.Text = visible.Count((Book b) => b.IsFavorite).ToString();
        }

        private List<Book> SortBooks(List<Book> list)
        {
            switch (sortMode)
            {
                case 1:
                    return list.OrderByDescending((Book b) => b.DateAdded ?? "").ToList();
                case 2:
                    return list.OrderByDescending((Book b) => b.LastOpenedDate ?? "").ToList();
                case 3:
                    return list.OrderBy((Book b) => b.DisplayTitle ?? "", NaturalStringComparer.Instance).ToList();
                case 4:
                    return list.OrderByDescending((Book b) => b.ProgressPercent).ToList();
                default:
                    return list;
            }
        }

        private void ToggleFavorite_Click(object sender, RoutedEventArgs e)
        {
            MenuItem tagItem = sender as MenuItem;
            Book book = (tagItem != null) ? (tagItem.Tag as Book) : null;
            if (book != null)
            {
                LibraryService.ToggleFavorite(book.FilePath);
                LoadLibrary();
            }
        }

        private void txtSearch_TextChanged(object sender, TextChangedEventArgs e)
        {
            ApplyFilter();
        }

        private void TitleBar_MouseLeftButtonDown(object sender, MouseButtonEventArgs e)
        {
            if (e.ClickCount == 2 && sender != sidebarPanel)
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
            WindowMax.Toggle(this);
        }

        private void btnTheme_Click(object sender, RoutedEventArgs e)
        {
            // فقط ظاهر خود برنامه عوض می‌شه؛ تم خوانش هر کتاب مستقل خودش می‌مونه
            ThemeManager.Apply(ThemeManager.IsLight ? "dark" : "light");
            RefreshAppThemeUi();
        }

        private void RefreshAppThemeUi()
        {
            icoTheme.Data = (Geometry)FindResource(ThemeManager.IsLight ? "IcoMoon" : "IcoSun");
            btnTheme.ToolTip = Loc.T(ThemeManager.IsLight ? "حالت تیره" : "حالت روشن");
            UpdateTabStyles();
            if (txtThemeCount != null)
            {
                txtThemeCount.Text = RankService.UnlockedCount() + "/" + CharacterThemes.VisibleCount();
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

        private bool isAddingBooks = false;

        private DispatcherTimer toastTimer;

        private DispatcherTimer dropWatchdog;

        private int lastDragOverTick;

        private bool dropOverlayVisible;

        private void btnAddBook_Click(object sender, RoutedEventArgs e)
        {
            OpenFileDialog ofd = new OpenFileDialog
            {
                Filter = Loc.T("فایل‌های EPUB (*.epub)") + "|*.epub",
                Title = Loc.T("انتخاب فایل EPUB"),
                Multiselect = true
            };
            if (ofd.ShowDialog() != true)
            {
                return;
            }
            AddEpubFiles(ofd.FileNames);
        }

        // ---------- کشیدن و رها کردن فایل ----------

        private static bool IsEpubFile(string path)
        {
            return !string.IsNullOrEmpty(path) && string.Equals(Path.GetExtension(path), ".epub", StringComparison.OrdinalIgnoreCase);
        }

        private static bool HasDroppableFiles(DragEventArgs e)
        {
            try
            {
                if (!e.Data.GetDataPresent(DataFormats.FileDrop))
                {
                    return false;
                }
                string[] paths = e.Data.GetData(DataFormats.FileDrop) as string[];
                if (paths == null)
                {
                    return false;
                }
                foreach (string p in paths)
                {
                    if (IsEpubFile(p) || Directory.Exists(p))
                    {
                        return true;
                    }
                }
            }
            catch
            {
            }
            return false;
        }

        private void Window_PreviewDragOver(object sender, DragEventArgs e)
        {
            // کشیدن متن (مثلاً داخل کادر جستجو) رو دست نمی‌زنیم
            if (!e.Data.GetDataPresent(DataFormats.FileDrop))
            {
                return;
            }
            e.Handled = true;
            if (!HasDroppableFiles(e))
            {
                e.Effects = DragDropEffects.None;
                return;
            }
            e.Effects = DragDropEffects.Copy;
            lastDragOverTick = Environment.TickCount;
            SetDropOverlay(true);
        }

        private void Window_PreviewDrop(object sender, DragEventArgs e)
        {
            if (!e.Data.GetDataPresent(DataFormats.FileDrop))
            {
                return;
            }
            e.Handled = true;
            SetDropOverlay(false);
            string[] paths = e.Data.GetData(DataFormats.FileDrop) as string[];
            if (paths == null || paths.Length == 0)
            {
                return;
            }
            // کار اصلی رو از داخل رویداد drop بیرون می‌بریم تا Explorer قفل نشه
            Dispatcher.BeginInvoke(new Action(() => AddEpubFiles(paths)), DispatcherPriority.Background);
        }

        // پوشش «رها کن» تا وقتی کشیدن ادامه داره دیده می‌شه؛ اگه کشیدن تموم یا لغو بشه خودش بسته می‌شه
        private void SetDropOverlay(bool on)
        {
            if (on)
            {
                if (dropWatchdog == null)
                {
                    dropWatchdog = new DispatcherTimer();
                    dropWatchdog.Interval = TimeSpan.FromMilliseconds(120);
                    dropWatchdog.Tick += (object s, EventArgs ev) =>
                    {
                        if (Environment.TickCount - lastDragOverTick > 350)
                        {
                            SetDropOverlay(false);
                        }
                    };
                }
                if (!dropOverlayVisible)
                {
                    dropOverlayVisible = true;
                    dropOverlay.Visibility = Visibility.Visible;
                    dropOverlay.BeginAnimation(UIElement.OpacityProperty, new DoubleAnimation(0.0, 1.0, new Duration(TimeSpan.FromMilliseconds(150))));
                    dropWatchdog.Start();
                }
            }
            else
            {
                if (dropWatchdog != null)
                {
                    dropWatchdog.Stop();
                }
                dropOverlayVisible = false;
                dropOverlay.BeginAnimation(UIElement.OpacityProperty, null);
                dropOverlay.Visibility = Visibility.Collapsed;
            }
        }

        // ---------- افزودن چند کتاب ----------

        // فایل‌ها و پوشه‌ها رو به لیست فایل‌های EPUB تبدیل می‌کنه (پوشه‌ها با زیرپوشه‌هاشون)
        private static List<string> CollectEpubPaths(IEnumerable<string> inputs, out int ignored)
        {
            List<string> result = new List<string>();
            HashSet<string> seen = new HashSet<string>(StringComparer.OrdinalIgnoreCase);
            ignored = 0;
            foreach (string p in inputs)
            {
                if (string.IsNullOrWhiteSpace(p))
                {
                    continue;
                }
                try
                {
                    if (Directory.Exists(p))
                    {
                        foreach (string f in Directory.GetFiles(p, "*.epub", SearchOption.AllDirectories))
                        {
                            if (seen.Add(f))
                            {
                                result.Add(f);
                            }
                        }
                    }
                    else if (File.Exists(p) && IsEpubFile(p))
                    {
                        if (seen.Add(p))
                        {
                            result.Add(p);
                        }
                    }
                    else
                    {
                        ignored++;
                    }
                }
                catch
                {
                    ignored++;
                }
            }
            result.Sort((string x, string y) => NaturalStringComparer.Instance.Compare(Path.GetFileName(x), Path.GetFileName(y)));
            return result;
        }

        // خوندن EPUB و ساختن Book؛ روی نخ پس‌زمینه اجرا می‌شه تا برنامه قفل نکنه
        private static Book ImportEpub(string path)
        {
            EpubBook epub = EpubParser.Load(path);
            try
            {
                Directory.Delete(epub.ExtractPath, true);
            }
            catch
            {
            }
            Book book = new Book();
            book.Id = Guid.NewGuid().ToString("N");
            book.Title = epub.Title;
            book.FilePath = path;
            book.CoverImagePath = epub.CoverImagePath;
            book.TotalChapters = epub.Chapters.Count;
            book.LastChapterIndex = 0;
            book.DateAdded = DateTime.Now.ToString("yyyy-MM-dd");
            return book;
        }

        private async void AddEpubFiles(IEnumerable<string> inputs)
        {
            if (isAddingBooks)
            {
                ShowToast("افزودن قبلی هنوز در حال انجامه…", 2200);
                return;
            }
            int ignored;
            List<string> files = CollectEpubPaths(inputs, out ignored);
            if (files.Count == 0)
            {
                if (ignored > 0)
                {
                    DarkMessageBox.Show("فقط فایل‌های EPUB (یا پوشه‌ی حاوی اون‌ها) قابل افزودن هستن.", "افزودن کتاب", MessageBoxButton.OK, MessageBoxImage.Asterisk);
                }
                return;
            }

            isAddingBooks = true;
            int added = 0;
            int duplicates = 0;
            List<string> failed = new List<string>();
            string lastError = null;
            try
            {
                HashSet<string> existing = new HashSet<string>(books.Select((Book x) => x.FilePath ?? ""), StringComparer.OrdinalIgnoreCase);
                List<string> toImport = new List<string>();
                foreach (string f in files)
                {
                    if (existing.Contains(f))
                    {
                        duplicates++;
                    }
                    else
                    {
                        toImport.Add(f);
                    }
                }
                for (int i = 0; i < toImport.Count; i++)
                {
                    string path = toImport[i];
                    ShowBusy(Loc.F("در حال افزودن {0} از {1}…", i + 1, toImport.Count), i * 100.0 / toImport.Count);
                    try
                    {
                        Book imported = await Task.Run(() => ImportEpub(path));
                        LibraryService.AddOrUpdateBook(imported);
                        added++;
                    }
                    catch (Exception ex)
                    {
                        failed.Add(Path.GetFileName(path));
                        lastError = ex.Message;
                    }
                }
            }
            finally
            {
                HideBusy();
                isAddingBooks = false;
            }

            if (added > 0)
            {
                if (currentTab != "all")
                {
                    currentTab = "all";
                    UpdateTabStyles();
                }
                ApplyFilterAnimated(true);
            }

            // فقط یک فایل انتخاب شده بود: همون پیام‌های قبلی
            if (files.Count == 1 && ignored == 0)
            {
                if (duplicates == 1)
                {
                    DarkMessageBox.Show("این کتاب از قبل توی کتابخونه هست.", "افزودن کتاب", MessageBoxButton.OK, MessageBoxImage.Asterisk);
                }
                else if (failed.Count == 1)
                {
                    DarkMessageBox.Show(Loc.T("خطا در افزودن کتاب:\n") + lastError, "خطا", MessageBoxButton.OK, MessageBoxImage.Hand);
                }
                else if (added == 1)
                {
                    ShowToast("کتاب اضافه شد", 2200);
                }
                return;
            }

            if (duplicates == 0 && failed.Count == 0 && ignored == 0)
            {
                ShowToast(Loc.FN("{0} کتاب اضافه شد", added, added), 2500);
                return;
            }

            List<string> lines = new List<string>();
            if (added > 0)
            {
                lines.Add("• " + Loc.FN("{0} کتاب اضافه شد.", added, added));
            }
            if (duplicates > 0)
            {
                lines.Add("• " + Loc.FN("{0} کتاب از قبل توی کتابخونه بود.", duplicates, duplicates));
            }
            if (failed.Count > 0)
            {
                lines.Add("• " + Loc.FN("{0} فایل باز نشد: ", failed.Count, failed.Count) + string.Join(Loc.ListSeparator, failed.Take(5)) + (failed.Count > 5 ? Loc.T(" و …") : ""));
            }
            if (ignored > 0)
            {
                lines.Add("• " + Loc.FN("{0} مورد EPUB نبود و نادیده گرفته شد.", ignored, ignored));
            }
            DarkMessageBox.Show(string.Join("\n", lines), "افزودن کتاب", MessageBoxButton.OK, failed.Count > 0 ? MessageBoxImage.Exclamation : MessageBoxImage.Asterisk);
        }

        private void ShowBusy(string text, double percent)
        {
            if (toastTimer != null)
            {
                toastTimer.Stop();
            }
            txtBusy.Text = Loc.T(text);
            progressBusy.BeginAnimation(RangeBase.ValueProperty, null);
            progressBusy.Visibility = Visibility.Visible;
            progressBusy.Value = percent;
            busyBar.Visibility = Visibility.Visible;
        }

        private void HideBusy()
        {
            progressBusy.BeginAnimation(RangeBase.ValueProperty, null);
            busyBar.Visibility = Visibility.Collapsed;
        }

        private void ShowToast(string text, int milliseconds)
        {
            txtBusy.Text = Loc.T(text);
            progressBusy.Visibility = Visibility.Collapsed;
            busyBar.Visibility = Visibility.Visible;
            if (toastTimer == null)
            {
                toastTimer = new DispatcherTimer();
                toastTimer.Tick += (object s, EventArgs ev) =>
                {
                    toastTimer.Stop();
                    busyBar.Visibility = Visibility.Collapsed;
                };
            }
            toastTimer.Stop();
            toastTimer.Interval = TimeSpan.FromMilliseconds(milliseconds);
            toastTimer.Start();
        }


        private void Card_MouseLeftButtonUp(object sender, MouseButtonEventArgs e)
        {
            FrameworkElement fe = sender as FrameworkElement;
            LibraryCardItem item = (fe != null) ? (fe.Tag as LibraryCardItem) : null;
            if (item != null)
            {
                if (item.IsCollection)
                {
                    OpenCollection(item.CollectionName);
                }
                else
                {
                    if (AskRestart(item.SingleBook))
                    {
                        OpenSingleBook(item.SingleBook);
                    }
                }
            }
        }

        private void OpenCollection(string collectionName)
        {
            CollectionWindow collectionWindow = new CollectionWindow(collectionName);
            collectionWindow.Closed += (object s, EventArgs args) =>
            {
                LoadLibrary();
                Show();
                Activate();
            };
            collectionWindow.Show();
            Hide();
        }

        // true = ادامه بده و کتاب رو باز کن، false = کاربر با ضربدر/Esc بستش و نباید کتاب باز بشه
        public static bool AskRestart(Book book)
        {
            if (book == null || book.LastChapterIndex <= 0 || LibraryService.WasOpenedThisSession(book.FilePath))
            {
                return true;
            }
            string status = (book.IsCompleted ? Loc.T("این کتاب رو تموم کردی.") : Loc.F("تا فصل {0} از {1} خوندی.", book.LastChapterDisplay, book.TotalChapters));
            MessageBoxResult result = DarkMessageBox.Show(status + Loc.F("\nمی‌خوای از اول بخونیش؟\n\nبله: از اول (پیشرفت صفر میشه)\nخیر: ادامه از فصل {0}", book.LastChapterDisplay), "شروع دوباره؟", MessageBoxButton.YesNo, MessageBoxImage.Question, true);
            if (result == MessageBoxResult.Cancel)
            {
                return false;
            }
            if (result == MessageBoxResult.Yes)
            {
                LibraryService.ResetProgress(book.FilePath);
                book.LastChapterIndex = 0;
                book.LastOpenedDate = "";
            }
            return true;
        }

        // پنجره‌ی خوانش و موتور WebView2 از قبل (وقتی برنامه بیکاره) آماده می‌شن تا باز شدن کتاب فوری باشه
        private MainWindow prewarmedReader;

        private FrameworkElement warmWebView;

        private bool openingBook = false;

        private async void WarmUpReader()
        {
            try
            {
                MainWindow.PreloadFonts();
                PrewarmReaderWindow();
                CoreWebView2Environment env = await MainWindow.GetWebViewEnvironmentAsync();
                if (warmWebView == null)
                {
                    Microsoft.Web.WebView2.Wpf.WebView2 warm = new Microsoft.Web.WebView2.Wpf.WebView2();
                    warm.Width = 1;
                    warm.Height = 1;
                    warm.HorizontalAlignment = HorizontalAlignment.Left;
                    warm.VerticalAlignment = VerticalAlignment.Bottom;
                    warm.IsHitTestVisible = false;
                    warm.Visibility = Visibility.Hidden;
                    Grid.SetColumnSpan(warm, 2);
                    rootGrid.Children.Insert(0, warm);
                    warmWebView = warm;
                    await warm.EnsureCoreWebView2Async(env);
                }
            }
            catch
            {
                // اگه WebView2 نبود یا خطا داد، موقع باز کردن کتاب پیام مناسب نشون داده می‌شه
            }
        }

        private void PrewarmReaderWindow()
        {
            if (prewarmedReader != null)
            {
                return;
            }
            try
            {
                prewarmedReader = new MainWindow();
            }
            catch
            {
                prewarmedReader = null;
            }
        }

        private async void OpenSingleBook(Book selectedBook)
        {
            if (selectedBook == null || openingBook)
            {
                return;
            }
            if (!File.Exists(selectedBook.FilePath))
            {
                DarkMessageBox.Show("فایل کتاب پیدا نشد. ممکنه جابجا یا حذف شده باشه.\nاین کتاب از کتابخانه حذف می‌شه.", "خطا", MessageBoxButton.OK, MessageBoxImage.Exclamation);
                LibraryService.RemoveBook(selectedBook.FilePath);
                LoadLibrary();
                return;
            }
            openingBook = true;
            EpubBook preloaded = null;
            try
            {
                LibraryService.LogOpen("[Library] request: " + selectedBook.DisplayTitle + " | " + selectedBook.FilePath);
                ShowBusy(Loc.F("در حال باز کردن «{0}»...", selectedBook.DisplayTitle), 0.0);
                progressBusy.BeginAnimation(RangeBase.ValueProperty, new DoubleAnimation(0.0, 90.0, new Duration(TimeSpan.FromMilliseconds(1400))) { EasingFunction = new QuadraticEase { EasingMode = EasingMode.EaseOut } });
                // اول بذار پیام روی صفحه دیده بشه، بعد کار سنگین (باز کردن فایل EPUB) توی پس‌زمینه انجام بشه
                await Dispatcher.InvokeAsync(() => { }, DispatcherPriority.Background);
                string filePath = selectedBook.FilePath;
                preloaded = await Task.Run(() => EpubParser.Load(filePath));

                LibraryService.MarkAsOpened(selectedBook.FilePath);
                selectedBook.LastOpenedDate = DateTime.Now.ToString("yyyy-MM-dd HH:mm:ss");
                MainWindow readerWindow = prewarmedReader ?? new MainWindow();
                prewarmedReader = null;
                if (!readerWindow.OpenBook(selectedBook, preloaded))
                {
                    readerWindow.Close();
                    return;
                }
                readerWindow.Closed += (object s, EventArgs args) =>
                {
                    LoadLibrary();
                    Show();
                    Activate();
                    Dispatcher.BeginInvoke(new Action(PrewarmReaderWindow), DispatcherPriority.ApplicationIdle);
                };
                readerWindow.Show();
                Hide();
            }
            catch (Exception ex)
            {
                DarkMessageBox.Show("خطا در باز کردن کتاب:\n" + ex.Message, "خطا", MessageBoxButton.OK, MessageBoxImage.Hand);
            }
            finally
            {
                HideBusy();
                openingBook = false;
            }
        }

        private void BtnMore_Click(object sender, RoutedEventArgs e)
        {
            Button button = sender as Button;
            if (button == null)
            {
                return;
            }
            LibraryCardItem item = button.Tag as LibraryCardItem;
            if (item == null)
            {
                return;
            }
            ContextMenu contextMenu = new ContextMenu();
            if (item.IsCollection)
            {
                MenuItem menuItem = new MenuItem();
                menuItem.Header = Loc.T("باز کردن مجموعه");
                MenuItem openItem = menuItem;
                openItem.Click += (object s, RoutedEventArgs args) =>
                {
                    OpenCollection(item.CollectionName);
                };
                contextMenu.Items.Add(openItem);
            }
            else
            {
                Book book = item.SingleBook;
                string collectionText = Loc.T(string.IsNullOrEmpty(book.CollectionName) ? "افزودن به مجموعه" : "تغییر مجموعه");
                MenuItem menuItem = new MenuItem();
                menuItem.Header = collectionText;
                menuItem.Tag = book;
                MenuItem collectionMenuItem = menuItem;
                collectionMenuItem.Click += AddToCollection_Click;
                contextMenu.Items.Add(collectionMenuItem);
                MenuItem favItem = new MenuItem();
                favItem.Header = Loc.T(book.IsFavorite ? "حذف از علاقه‌مندی‌ها" : "افزودن به علاقه‌مندی‌ها");
                favItem.Tag = book;
                favItem.Click += ToggleFavorite_Click;
                contextMenu.Items.Add(favItem);
                string privacyText = Loc.T(book.IsPrivate ? "حذف از خصوصی" : "افزودن به خصوصی");
                menuItem = new MenuItem();
                menuItem.Header = privacyText;
                menuItem.Tag = book;
                MenuItem toggleItem = menuItem;
                toggleItem.Click += TogglePrivate_Click;
                contextMenu.Items.Add(toggleItem);
                menuItem = new MenuItem();
                menuItem.Header = Loc.T("حذف از کتابخانه");
                menuItem.Tag = book;
                menuItem.Foreground = (Brush)FindResource("DangerColor");
                MenuItem deleteItem = menuItem;
                deleteItem.Click += DeleteBook_Click;
                contextMenu.Items.Add(deleteItem);
            }
            contextMenu.FlowDirection = Loc.Flow;
            contextMenu.PlacementTarget = button;
            contextMenu.IsOpen = true;
            e.Handled = true;
        }

        private void AddToCollection_Click(object sender, RoutedEventArgs e)
        {
            MenuItem tagItem = sender as MenuItem;
            Book book = (tagItem != null) ? (tagItem.Tag as Book) : null;
            if (book != null)
            {
                List<string> existingNames = LibraryService.GetAllCollectionNames();
                string chosen = CollectionPickerWindow.PickCollection(existingNames);
                if (chosen != null)
                {
                    LibraryService.SetCollection(book.FilePath, chosen);
                    LoadLibrary();
                }
            }
        }

        private void TogglePrivate_Click(object sender, RoutedEventArgs e)
        {
            MenuItem tagItem = sender as MenuItem;
            Book book = (tagItem != null) ? (tagItem.Tag as Book) : null;
            if (book != null)
            {
                LibraryService.TogglePrivate(book.FilePath);
                LoadLibrary();
            }
        }

        private void DeleteBook_Click(object sender, RoutedEventArgs e)
        {
            MenuItem tagItem = sender as MenuItem;
            Book bookToDelete = (tagItem != null) ? (tagItem.Tag as Book) : null;
            if (bookToDelete != null)
            {
                MessageBoxResult result = DarkMessageBox.Show(Loc.F("آیا مطمئنید می‌خواید «{0}» رو از کتابخانه حذف کنید؟\n(خود فایل EPUB پاک نمیشه)", bookToDelete.DisplayTitle), "حذف کتاب", MessageBoxButton.YesNo, MessageBoxImage.Question);
                if (result == MessageBoxResult.Yes)
                {
                    LibraryService.RemoveBook(bookToDelete.FilePath);
                    LoadLibrary();
                }
            }
        }
    }
}