using System;
using System.Collections.Generic;
using System.Diagnostics;
using System.Linq;
using System.Runtime.InteropServices;
using System.Windows;
using System.Windows.Controls;
using System.Windows.Input;
using System.Windows.Interop;
using System.Windows.Media;
using System.Windows.Media.Animation;
using System.Windows.Media.Effects;
using System.Windows.Threading;
using EpubReader.Services;

namespace EpubReader
{

    public static class DarkMessageBox
    {
        private static Brush Res(string key)
        {
            return (Brush)Application.Current.FindResource(key);
        }

        private static Window CreateShell(string title, UIElement body, out StackPanel buttonsPanel)
        {
            Button unusedClose;
            return CreateShell(title, body, out buttonsPanel, false, out unusedClose);
        }

        private static Window CreateShell(string title, UIElement body, out StackPanel buttonsPanel, bool withClose, out Button closeButton)
        {
            closeButton = null;
            Window win = new Window
            {
                WindowStyle = WindowStyle.None,
                AllowsTransparency = true,
                Background = Brushes.Transparent,
                ResizeMode = ResizeMode.NoResize,
                SizeToContent = SizeToContent.WidthAndHeight,
                ShowInTaskbar = false,
                FlowDirection = Loc.Flow,
                FontFamily = (FontFamily)Application.Current.FindResource("AppFont"),
                Title = Loc.T(title ?? "")
            };
            Window owner = Application.Current.Windows.OfType<Window>().FirstOrDefault((Window w) => w.IsActive && w.IsVisible);
            if (owner != null)
            {
                win.Owner = owner;
                win.WindowStartupLocation = WindowStartupLocation.CenterOwner;
            }
            else
            {
                win.WindowStartupLocation = WindowStartupLocation.CenterScreen;
            }
            Border border = new Border();
            border.Background = Res("BgDark");
            border.BorderBrush = Res("BorderColor");
            border.BorderThickness = new Thickness(1.0);
            border.CornerRadius = new CornerRadius(14.0);
            border.Padding = new Thickness(24.0, 20.0, 24.0, 20.0);
            border.Margin = new Thickness(16.0);
            border.MinWidth = 340.0;
            border.MaxWidth = 480.0;
            border.Effect = new DropShadowEffect
            {
                Color = Colors.Black,
                BlurRadius = 24.0,
                ShadowDepth = 4.0,
                Opacity = 0.6
            };
            Border root = border;
            root.MouseLeftButtonDown += (object s, MouseButtonEventArgs e) =>
            {
                try
                {
                    win.DragMove();
                }
                catch
                {
                }
            };
            StackPanel stack = new StackPanel();
            TextBlock titleText = new TextBlock
            {
                Text = (title ?? ""),
                Foreground = Res("TextColor"),
                FontSize = 15.0,
                FontWeight = FontWeights.Bold,
                VerticalAlignment = VerticalAlignment.Center
            };
            Grid titleRow = new Grid();
            titleRow.Margin = new Thickness(0.0, 0.0, 0.0, 12.0);
            titleRow.ColumnDefinitions.Add(new ColumnDefinition { Width = new GridLength(1.0, GridUnitType.Star) });
            titleRow.ColumnDefinitions.Add(new ColumnDefinition { Width = GridLength.Auto });
            Grid.SetColumn(titleText, 0);
            titleRow.Children.Add(titleText);
            if (withClose)
            {
                Button xButton = new Button();
                xButton.Content = "×";
                xButton.Width = 28.0;
                xButton.Height = 28.0;
                xButton.Padding = new Thickness(0.0);
                xButton.FontSize = 18.0;
                xButton.Background = Brushes.Transparent;
                xButton.BorderThickness = new Thickness(0.0);
                xButton.Foreground = Res("TextMuted");
                xButton.Cursor = Cursors.Hand;
                xButton.Focusable = false;
                xButton.ToolTip = "بستن";
                Grid.SetColumn(xButton, 1);
                titleRow.Children.Add(xButton);
                closeButton = xButton;
            }
            stack.Children.Add(titleRow);
            stack.Children.Add(body);
            buttonsPanel = new StackPanel
            {
                Orientation = Orientation.Horizontal,
                HorizontalAlignment = HorizontalAlignment.Left,
                Margin = new Thickness(0.0, 20.0, 0.0, 0.0)
            };
            stack.Children.Add(buttonsPanel);
            root.Child = stack;
            win.Content = root;
            return win;
        }

        private static Button AddButton(StackPanel panel, string text, bool primary)
        {
            Button button = new Button();
            button.Content = text;
            button.MinWidth = 90.0;
            button.Margin = new Thickness(0.0, 0.0, 8.0, 0.0);
            Button btn = button;
            if (primary)
            {
                btn.Style = (Style)Application.Current.FindResource("ModernButton");
            }
            else
            {
                btn.Padding = new Thickness(16.0, 10.0, 16.0, 10.0);
            }
            panel.Children.Add(btn);
            return btn;
        }

        public static MessageBoxResult Show(string text, string title = "", MessageBoxButton buttons = MessageBoxButton.OK, MessageBoxImage icon = MessageBoxImage.None, bool showCloseX = false)
        {
            string glyph = null;
            Brush glyphBrush = Res("AccentColor");
            switch (icon)
            {
                case MessageBoxImage.Hand:
                    glyph = "×";
                    glyphBrush = Res("DangerColor");
                    break;
                case MessageBoxImage.Exclamation:
                    glyph = "!";
                    glyphBrush = new SolidColorBrush(Color.FromRgb(251, 191, 36));
                    break;
                case MessageBoxImage.Question:
                    glyph = "?";
                    break;
                case MessageBoxImage.Asterisk:
                    glyph = "i";
                    break;
            }
            DockPanel body = new DockPanel();
            if (glyph != null)
            {
                Border border = new Border();
                border.Width = 34.0;
                border.Height = 34.0;
                border.CornerRadius = new CornerRadius(17.0);
                border.Background = glyphBrush;
                border.Margin = new Thickness(0.0, 0.0, 14.0, 0.0);
                border.VerticalAlignment = VerticalAlignment.Top;
                border.Child = new TextBlock
                {
                    Text = glyph,
                    Foreground = Res("BgDark"),
                    FontWeight = FontWeights.Bold,
                    FontSize = 16.0,
                    HorizontalAlignment = HorizontalAlignment.Center,
                    VerticalAlignment = VerticalAlignment.Center
                };
                Border circle = border;
                DockPanel.SetDock(circle, Dock.Left);
                body.Children.Add(circle);
            }
            body.Children.Add(new TextBlock
            {
                Text = (text ?? ""),
                Foreground = Res("TextColor"),
                FontSize = 13.0,
                TextWrapping = TextWrapping.Wrap,
                LineHeight = 22.0,
                VerticalAlignment = VerticalAlignment.Center
            });
            StackPanel panel;
            Button closeX;
            Window win = CreateShell(title, body, out panel, showCloseX, out closeX);
            MessageBoxResult result;
            if (buttons == MessageBoxButton.YesNo)
            {
                result = MessageBoxResult.No;
            }
            else if (buttons == MessageBoxButton.OKCancel || buttons == MessageBoxButton.YesNoCancel)
            {
                result = MessageBoxResult.Cancel;
            }
            else
            {
                result = MessageBoxResult.OK;
            }
            if (showCloseX)
            {
                result = MessageBoxResult.Cancel;
            }
            MessageBoxResult cancelResult = result;
            if (closeX != null)
            {
                closeX.Click += (object s, RoutedEventArgs e) =>
                {
                    result = MessageBoxResult.Cancel;
                    win.Close();
                };
            }
            Action<string, MessageBoxResult, bool> add = (string label, MessageBoxResult res, bool primary) =>
            {
                Button button = AddButton(panel, label, primary);
                button.Click += (object s, RoutedEventArgs e) =>
                {
                    result = res;
                    win.Close();
                };
                if (primary)
                {
                    button.IsDefault = true;
                }
            };
            switch (buttons)
            {
                case MessageBoxButton.YesNo:
                    add("بله", MessageBoxResult.Yes, arg3: true);
                    add("خیر", MessageBoxResult.No, arg3: false);
                    break;
                case MessageBoxButton.YesNoCancel:
                    add("بله", MessageBoxResult.Yes, arg3: true);
                    add("خیر", MessageBoxResult.No, arg3: false);
                    add("انصراف", MessageBoxResult.Cancel, arg3: false);
                    break;
                case MessageBoxButton.OKCancel:
                    add("تایید", MessageBoxResult.OK, arg3: true);
                    add("انصراف", MessageBoxResult.Cancel, arg3: false);
                    break;
                default:
                    add("باشه", MessageBoxResult.OK, arg3: true);
                    break;
            }
            win.PreviewKeyDown += (object s, KeyEventArgs e) =>
            {
                if (e.Key == Key.Escape)
                {
                    result = cancelResult;
                    win.Close();
                }
            };
            win.ShowDialog();
            return result;
        }

        public static string Prompt(string label, string title, string defaultValue = "")
        {
            StackPanel body = new StackPanel();
            body.Children.Add(new TextBlock
            {
                Text = (label ?? ""),
                Foreground = Res("TextMuted"),
                FontSize = 12.0,
                Margin = new Thickness(0.0, 0.0, 0.0, 6.0)
            });
            TextBox box = new TextBox
            {
                Text = (defaultValue ?? ""),
                Background = Res("BgCard"),
                BorderBrush = Res("BorderColor"),
                BorderThickness = new Thickness(1.0),
                Padding = new Thickness(10.0, 8.0, 10.0, 8.0),
                FontSize = 13.0
            };
            body.Children.Add(box);
            StackPanel panel;
            Window win = CreateShell(title, body, out panel);
            string result = null;
            Button ok = AddButton(panel, "تایید", primary: true);
            ok.IsDefault = true;
            ok.Click += (object s, RoutedEventArgs e) =>
            {
                result = box.Text;
                win.Close();
            };
            Button cancel = AddButton(panel, "انصراف", primary: false);
            cancel.IsCancel = true;
            cancel.Click += (object s, RoutedEventArgs e) =>
            {
                result = null;
                win.Close();
            };
            win.Loaded += (object s, RoutedEventArgs e) =>
            {
                box.Focus();
                box.SelectAll();
            };
            win.ShowDialog();
            return result;
        }
    }

    public sealed class ShortcutsWindow : Window
    {
        private readonly Dictionary<string, Button> keyButtons = new Dictionary<string, Button>();

        private string capturingId = null;

        private static Brush Res(string key)
        {
            return (Brush)Application.Current.FindResource(key);
        }

        public static void ShowFor(Window owner)
        {
            ShortcutsWindow win = new ShortcutsWindow();
            if (owner != null)
            {
                win.Owner = owner;
                win.WindowStartupLocation = WindowStartupLocation.CenterOwner;
            }
            else
            {
                win.WindowStartupLocation = WindowStartupLocation.CenterScreen;
            }
            win.ShowDialog();
        }

        private ShortcutsWindow()
        {
            WindowStyle = WindowStyle.None;
            AllowsTransparency = true;
            Background = Brushes.Transparent;
            ResizeMode = ResizeMode.NoResize;
            ShowInTaskbar = false;
            Width = 560.0;
            Height = 660.0;
            FlowDirection = Loc.Flow;
            FontFamily = (FontFamily)Application.Current.FindResource("AppFont");
            Title = "شخصی‌سازی کلیدهای میانبر";

            Border root = new Border();
            root.Background = Res("BgDark");
            root.BorderBrush = Res("BorderColor");
            root.BorderThickness = new Thickness(1.0);
            root.CornerRadius = new CornerRadius(14.0);
            root.Margin = new Thickness(16.0);
            root.Padding = new Thickness(24.0, 20.0, 24.0, 20.0);
            root.Effect = new DropShadowEffect
            {
                Color = Colors.Black,
                BlurRadius = 24.0,
                ShadowDepth = 4.0,
                Opacity = 0.6
            };

            Grid grid = new Grid();
            grid.RowDefinitions.Add(new RowDefinition { Height = GridLength.Auto });
            grid.RowDefinitions.Add(new RowDefinition { Height = new GridLength(1.0, GridUnitType.Star) });
            grid.RowDefinitions.Add(new RowDefinition { Height = GridLength.Auto });

            StackPanel header = new StackPanel();
            header.Background = Brushes.Transparent;
            header.MouseLeftButtonDown += (object s, MouseButtonEventArgs e) =>
            {
                try
                {
                    DragMove();
                }
                catch
                {
                }
            };
            header.Children.Add(new TextBlock
            {
                Text = "شخصی‌سازی کلیدهای میانبر",
                Foreground = Res("TextColor"),
                FontSize = 16.0,
                FontWeight = FontWeights.Bold
            });
            header.Children.Add(new TextBlock
            {
                Text = "روی کادر کلید بزن، بعد کلید یا ترکیب دلخواه را فشار بده (مثلاً Ctrl+N). Esc = انصراف. پیش‌فرض همه خالی است.",
                Foreground = Res("TextMuted"),
                FontSize = 12.0,
                TextWrapping = TextWrapping.Wrap,
                Margin = new Thickness(0.0, 6.0, 0.0, 14.0)
            });
            Grid.SetRow(header, 0);
            grid.Children.Add(header);

            StackPanel list = new StackPanel();
            string lastGroup = null;
            foreach (ShortcutAction action in ShortcutService.Actions)
            {
                if (action.Group != lastGroup)
                {
                    lastGroup = action.Group;
                    list.Children.Add(new TextBlock
                    {
                        Text = action.Group,
                        Foreground = Res("AccentColor"),
                        FontSize = 12.0,
                        FontWeight = FontWeights.SemiBold,
                        Margin = new Thickness(0.0, (list.Children.Count == 0 ? 0.0 : 16.0), 0.0, 8.0)
                    });
                }
                AddRow(list, action);
            }
            list.Margin = new Thickness(0.0, 0.0, 8.0, 0.0);
            ScrollViewer scroll = new ScrollViewer();
            scroll.Style = (Style)Application.Current.FindResource("ModernScrollViewer");
            scroll.VerticalScrollBarVisibility = ScrollBarVisibility.Auto;
            scroll.Content = list;
            Grid.SetRow(scroll, 1);
            grid.Children.Add(scroll);

            StackPanel buttons = new StackPanel
            {
                Orientation = Orientation.Horizontal,
                HorizontalAlignment = HorizontalAlignment.Left,
                Margin = new Thickness(0.0, 16.0, 0.0, 0.0)
            };
            Button closeButton = new Button();
            closeButton.Content = "بستن";
            closeButton.MinWidth = 90.0;
            closeButton.Margin = new Thickness(0.0, 0.0, 8.0, 0.0);
            closeButton.Style = (Style)Application.Current.FindResource("ModernButton");
            closeButton.Click += (object s, RoutedEventArgs e) => { Close(); };
            buttons.Children.Add(closeButton);
            Button resetButton = new Button();
            resetButton.Content = "پاک کردن همه";
            resetButton.Padding = new Thickness(16.0, 10.0, 16.0, 10.0);
            resetButton.Click += ResetAll_Click;
            buttons.Children.Add(resetButton);
            Grid.SetRow(buttons, 2);
            grid.Children.Add(buttons);

            root.Child = grid;
            Content = root;
        }

        private void AddRow(StackPanel parent, ShortcutAction action)
        {
            Grid row = new Grid();
            row.Margin = new Thickness(0.0, 0.0, 0.0, 6.0);
            row.ColumnDefinitions.Add(new ColumnDefinition { Width = new GridLength(1.0, GridUnitType.Star) });
            row.ColumnDefinitions.Add(new ColumnDefinition { Width = GridLength.Auto });
            row.ColumnDefinitions.Add(new ColumnDefinition { Width = GridLength.Auto });

            TextBlock title = new TextBlock
            {
                Text = action.Title,
                Foreground = Res("TextColor"),
                FontSize = 13.0,
                VerticalAlignment = VerticalAlignment.Center
            };
            Grid.SetColumn(title, 0);
            row.Children.Add(title);

            Button keyButton = new Button();
            keyButton.Tag = action.Id;
            keyButton.MinWidth = 170.0;
            keyButton.Padding = new Thickness(10.0, 6.0, 10.0, 6.0);
            keyButton.Margin = new Thickness(8.0, 0.0, 0.0, 0.0);
            keyButton.FlowDirection = FlowDirection.LeftToRight;
            keyButton.Focusable = false;
            keyButton.Click += KeyButton_Click;
            Grid.SetColumn(keyButton, 1);
            row.Children.Add(keyButton);
            keyButtons[action.Id] = keyButton;

            Button clearButton = new Button();
            clearButton.Tag = action.Id;
            clearButton.Content = "×";
            clearButton.Width = 30.0;
            clearButton.Padding = new Thickness(0.0, 4.0, 0.0, 4.0);
            clearButton.Margin = new Thickness(6.0, 0.0, 0.0, 0.0);
            clearButton.ToolTip = "پاک کردن کلید";
            clearButton.Focusable = false;
            clearButton.Click += ClearButton_Click;
            Grid.SetColumn(clearButton, 2);
            row.Children.Add(clearButton);

            parent.Children.Add(row);
            RefreshButton(action.Id);
        }

        private void RefreshButton(string id)
        {
            Button button;
            if (!keyButtons.TryGetValue(id, out button))
            {
                return;
            }
            if (capturingId == id)
            {
                button.Content = Loc.T("کلید را بزن…");
                button.Background = Res("AccentColorDark");
                button.Foreground = Brushes.White;
                return;
            }
            string combo = ShortcutService.Get(id);
            if (string.IsNullOrEmpty(combo))
            {
                button.Content = Loc.T("تنظیم نشده");
                button.Foreground = Res("TextMuted");
            }
            else
            {
                button.Content = combo;
                button.Foreground = Res("TextColor");
            }
            button.Background = Res("HoverColor");
        }

        private void RefreshAll()
        {
            foreach (ShortcutAction a in ShortcutService.Actions)
            {
                RefreshButton(a.Id);
            }
        }

        private void KeyButton_Click(object sender, RoutedEventArgs e)
        {
            string previous = capturingId;
            capturingId = (string)((Button)sender).Tag;
            if (previous != null)
            {
                RefreshButton(previous);
            }
            RefreshButton(capturingId);
        }

        private void ClearButton_Click(object sender, RoutedEventArgs e)
        {
            string id = (string)((Button)sender).Tag;
            if (capturingId != null)
            {
                string previous = capturingId;
                capturingId = null;
                RefreshButton(previous);
            }
            ShortcutService.Set(id, "");
            RefreshButton(id);
        }

        private void ResetAll_Click(object sender, RoutedEventArgs e)
        {
            MessageBoxResult result = DarkMessageBox.Show("همه کلیدهای میانبر پاک شوند؟", "پاک کردن همه", MessageBoxButton.YesNo, MessageBoxImage.Question);
            if (result == MessageBoxResult.Yes)
            {
                capturingId = null;
                ShortcutService.ClearAll();
                RefreshAll();
            }
        }

        private void EndCapture()
        {
            string id = capturingId;
            capturingId = null;
            if (id != null)
            {
                RefreshButton(id);
            }
        }

        protected override void OnPreviewKeyDown(KeyEventArgs e)
        {
            if (capturingId == null)
            {
                if (e.Key == Key.Escape)
                {
                    e.Handled = true;
                    Close();
                    return;
                }
                base.OnPreviewKeyDown(e);
                return;
            }

            e.Handled = true;
            if (e.Key == Key.Escape)
            {
                EndCapture();
                return;
            }
            string combo = ShortcutService.FromEvent(e);
            if (combo == null)
            {
                return;
            }
            string id = capturingId;
            if (ShortcutService.IsReserved(combo))
            {
                EndCapture();
                DarkMessageBox.Show(Loc.F("ترکیب «{0}» برای کپی، چسباندن و ویرایش متن رزرو است. ترکیب دیگری انتخاب کن.", combo), "ترکیب رزرو", MessageBoxButton.OK, MessageBoxImage.Exclamation);
                return;
            }
            ShortcutAction owner = ShortcutService.FindOwner(combo, id);
            if (owner != null)
            {
                EndCapture();
                DarkMessageBox.Show(Loc.F("کلید «{0}» قبلاً برای «{1}» تنظیم شده. اول آن را پاک کن یا کلید دیگری انتخاب کن.", combo, owner.Title), "کلید تکراری", MessageBoxButton.OK, MessageBoxImage.Exclamation);
                return;
            }
            ShortcutService.Set(id, combo);
            EndCapture();
        }
    }

    public sealed class AboutWindow : Window
    {
        private const string ChannelUrl = "https://t.me/NoveIFa";
        private const string DonateUrl = "http://Daramet.com/NovelFa";
        private const string BoostUrl = "https://t.me/NoveIFa?boost";

        private static Brush Res(string key)
        {
            return (Brush)Application.Current.FindResource(key);
        }

        public static void ShowFor(Window owner)
        {
            AboutWindow win = new AboutWindow();
            if (owner != null)
            {
                win.Owner = owner;
                win.WindowStartupLocation = WindowStartupLocation.CenterOwner;
            }
            else
            {
                win.WindowStartupLocation = WindowStartupLocation.CenterScreen;
            }
            win.ShowDialog();
        }

        private AboutWindow()
        {
            WindowStyle = WindowStyle.None;
            AllowsTransparency = true;
            Background = Brushes.Transparent;
            ResizeMode = ResizeMode.NoResize;
            ShowInTaskbar = false;
            SizeToContent = SizeToContent.Height;
            Width = 520.0;
            FlowDirection = Loc.Flow;
            FontFamily = (FontFamily)Application.Current.FindResource("AppFont");
            Title = Loc.T("درباره ما");

            Border root = new Border();
            root.Background = Res("BgDark");
            root.BorderBrush = Res("BorderColor");
            root.BorderThickness = new Thickness(1.0);
            root.CornerRadius = new CornerRadius(14.0);
            root.Margin = new Thickness(16.0);
            root.Padding = new Thickness(24.0, 20.0, 24.0, 20.0);
            root.Effect = new DropShadowEffect
            {
                Color = Colors.Black,
                BlurRadius = 24.0,
                ShadowDepth = 4.0,
                Opacity = 0.6
            };

            StackPanel panel = new StackPanel();

            // سربرگ: عنوان + دکمه ضربدر (قابل کشیدن)
            Grid header = new Grid();
            header.Background = Brushes.Transparent;
            header.MouseLeftButtonDown += (object s, MouseButtonEventArgs e) =>
            {
                try
                {
                    DragMove();
                }
                catch
                {
                }
            };
            StackPanel titleRow = new StackPanel();
            titleRow.Orientation = Orientation.Horizontal;
            titleRow.HorizontalAlignment = HorizontalAlignment.Right;
            titleRow.VerticalAlignment = VerticalAlignment.Center;
            titleRow.Children.Add(MakeBadge(IcoInfo, 38.0, 19.0));
            titleRow.Children.Add(new TextBlock
            {
                Text = "درباره ما",
                Foreground = Res("TextColor"),
                FontSize = 18.0,
                FontWeight = FontWeights.Bold,
                VerticalAlignment = VerticalAlignment.Center,
                Margin = new Thickness(12.0, 0.0, 0.0, 0.0)
            });
            header.Children.Add(titleRow);
            Button xButton = new Button();
            xButton.Content = "×";
            xButton.Style = (Style)Application.Current.FindResource("GhostButton");
            xButton.FontSize = 18.0;
            xButton.Padding = new Thickness(10.0, 0.0, 10.0, 2.0);
            xButton.HorizontalAlignment = HorizontalAlignment.Left;
            xButton.Focusable = false;
            xButton.Click += (object s, RoutedEventArgs e) => { Close(); };
            header.Children.Add(xButton);
            panel.Children.Add(header);

            // توضیحات برنامه
            panel.Children.Add(new TextBlock
            {
                Text = "این برنامه برای بچه‌های گل ناول‌فا و کسایی که نیاز دارن ساخته شده. به‌خوبی از این برنامه لذت ببرید.",
                Foreground = Res("TextMuted"),
                FontSize = 13.0,
                LineHeight = 22.0,
                TextWrapping = TextWrapping.Wrap,
                Margin = new Thickness(0.0, 12.0, 0.0, 6.0)
            });

            AddSection(panel, "لینک‌ها");
            AddRow(panel, "کانال", IcoSend, "t.me/NoveIFa", ChannelUrl, ChannelUrl, "باز کردن");
            AddRow(panel, "دونیت", IcoHeart, "Daramet.com/NovelFa", DonateUrl, DonateUrl, "باز کردن");
            AddRow(panel, "بوست", IcoBolt, "t.me/NoveIFa?boost", BoostUrl, BoostUrl, "باز کردن");

            AddSection(panel, "مدیریت");
            AddRow(panel, "مدیر", IcoUser, "@mehialz", "https://t.me/mehialz", "@mehialz", "پیام");
            AddRow(panel, "مدیر", IcoUser, "@ali138453a", "https://t.me/ali138453a", "@ali138453a", "پیام");

            AddSection(panel, "تبلیغات");
            AddRow(panel, "تبلیغات", IcoMegaphone, "@Its_2nte", "https://t.me/Its_2nte", "@Its_2nte", "پیام");

            Button closeButton = new Button();
            closeButton.Content = "بستن";
            closeButton.MinWidth = 90.0;
            closeButton.HorizontalAlignment = HorizontalAlignment.Left;
            closeButton.Margin = new Thickness(0.0, 22.0, 0.0, 0.0);
            closeButton.Style = (Style)Application.Current.FindResource("ModernButton");
            closeButton.Click += (object s, RoutedEventArgs e) => { Close(); };
            panel.Children.Add(closeButton);

            root.Child = panel;
            Content = root;
        }

        private const string IcoInfo = "M8,1.5 A6.5,6.5 0 1 1 7.99,1.5 Z M8,7 V11.5 M8,4.6 V4.8";
        private const string IcoSend = "M14.5,1.8 L1.8,7 L6,8.8 L7.2,13.8 L9.6,10.8 L12.2,12.8 Z M6,8.8 L14.5,1.8";
        private const string IcoHeart = "M8,13.5 C3,10 1.8,7.5 1.8,5.5 A3,3 0 0 1 8,4.6 A3,3 0 0 1 14.2,5.5 C14.2,7.5 13,10 8,13.5 Z";
        private const string IcoBolt = "M9,1.5 L3.5,9 H8 L7,14.5 L12.5,7 H8 Z";
        private const string IcoUser = "M8,2.2 A2.8,2.8 0 1 1 7.99,2.2 Z M2.5,14 C2.5,10.8 5,9.2 8,9.2 C11,9.2 13.5,10.8 13.5,14";
        private const string IcoMegaphone = "M2,6 H4.5 L10.5,3 V13 L4.5,10 H2 Z M4.5,10 L5.5,13.5 H7.2 L6.5,10.5 M12.5,6 C13.5,6.8 13.5,9.2 12.5,10";

        // مربع گوشه‌گرد با رنگ ملایم و آیکون هم‌رنگ با رنگ اصلی برنامه
        private static Border MakeBadge(string iconData, double box, double iconSize)
        {
            System.Windows.Shapes.Path icon = new System.Windows.Shapes.Path();
            icon.Data = Geometry.Parse(iconData);
            icon.Stroke = Res("AccentColor");
            icon.StrokeThickness = 1.6;
            icon.StrokeStartLineCap = PenLineCap.Round;
            icon.StrokeEndLineCap = PenLineCap.Round;
            icon.StrokeLineJoin = PenLineJoin.Round;
            icon.Stretch = Stretch.Uniform;
            icon.Width = iconSize;
            icon.Height = iconSize;
            icon.HorizontalAlignment = HorizontalAlignment.Center;
            icon.VerticalAlignment = VerticalAlignment.Center;

            Border badge = new Border();
            badge.Width = box;
            badge.Height = box;
            badge.CornerRadius = new CornerRadius(box / 3.2);
            badge.Background = Res("AccentSoft");
            badge.Child = icon;
            return badge;
        }

        private static void AddSection(StackPanel parent, string text)
        {
            parent.Children.Add(new TextBlock
            {
                Text = text,
                Foreground = Res("AccentColor"),
                FontSize = 12.0,
                FontWeight = FontWeights.SemiBold,
                Margin = new Thickness(0.0, 16.0, 0.0, 8.0)
            });
        }

        private void AddRow(StackPanel parent, string label, string iconData, string display, string url, string copyText, string openText)
        {
            Border card = new Border();
            card.Background = Res("BgCard");
            card.BorderBrush = Res("BorderColor");
            card.BorderThickness = new Thickness(1.0);
            card.CornerRadius = new CornerRadius(10.0);
            card.Padding = new Thickness(12.0, 8.0, 8.0, 8.0);
            card.Margin = new Thickness(0.0, 0.0, 0.0, 6.0);

            Grid row = new Grid();
            row.ColumnDefinitions.Add(new ColumnDefinition { Width = GridLength.Auto });
            row.ColumnDefinitions.Add(new ColumnDefinition { Width = new GridLength(1.0, GridUnitType.Star) });
            row.ColumnDefinitions.Add(new ColumnDefinition { Width = GridLength.Auto });
            row.ColumnDefinitions.Add(new ColumnDefinition { Width = GridLength.Auto });

            StackPanel labelPanel = new StackPanel();
            labelPanel.Orientation = Orientation.Horizontal;
            labelPanel.VerticalAlignment = VerticalAlignment.Center;
            labelPanel.Margin = new Thickness(0.0, 0.0, 12.0, 0.0);
            labelPanel.Children.Add(MakeBadge(iconData, 30.0, 15.0));
            labelPanel.Children.Add(new TextBlock
            {
                Text = label,
                Foreground = Res("TextMuted"),
                FontSize = 12.0,
                MinWidth = 44.0,
                VerticalAlignment = VerticalAlignment.Center,
                Margin = new Thickness(10.0, 0.0, 0.0, 0.0)
            });
            Grid.SetColumn(labelPanel, 0);
            row.Children.Add(labelPanel);

            TextBlock linkBlock = new TextBlock
            {
                Text = display,
                Foreground = Res("TextColor"),
                FontSize = 13.0,
                FlowDirection = FlowDirection.LeftToRight,
                HorizontalAlignment = HorizontalAlignment.Right,
                VerticalAlignment = VerticalAlignment.Center,
                TextTrimming = TextTrimming.CharacterEllipsis,
                Cursor = Cursors.Hand,
                ToolTip = url,
                Margin = new Thickness(0.0, 0.0, 8.0, 0.0)
            };
            linkBlock.MouseLeftButtonUp += (object s, MouseButtonEventArgs e) => { OpenUrl(url); };
            Grid.SetColumn(linkBlock, 1);
            row.Children.Add(linkBlock);

            Button openButton = new Button();
            openButton.Content = openText;
            openButton.Style = (Style)Application.Current.FindResource("SoftButton");
            openButton.Padding = new Thickness(12.0, 5.0, 12.0, 5.0);
            openButton.FontSize = 12.0;
            openButton.Margin = new Thickness(0.0, 0.0, 6.0, 0.0);
            openButton.Focusable = false;
            openButton.Click += (object s, RoutedEventArgs e) => { OpenUrl(url); };
            Grid.SetColumn(openButton, 2);
            row.Children.Add(openButton);

            Button copyButton = new Button();
            copyButton.Content = "کپی";
            copyButton.Style = (Style)Application.Current.FindResource("GhostButton");
            copyButton.Padding = new Thickness(12.0, 5.0, 12.0, 5.0);
            copyButton.FontSize = 12.0;
            copyButton.MinWidth = 62.0;
            copyButton.Focusable = false;
            copyButton.Click += (object s, RoutedEventArgs e) => { CopyText(copyText, copyButton); };
            Grid.SetColumn(copyButton, 3);
            row.Children.Add(copyButton);

            card.Child = row;
            parent.Children.Add(card);
        }

        private static void OpenUrl(string url)
        {
            try
            {
                Process.Start(new ProcessStartInfo(url) { UseShellExecute = true });
            }
            catch
            {
                DarkMessageBox.Show("باز کردن لینک ممکن نشد. می‌تونی دکمه «کپی» رو بزنی و توی مرورگر بچسبونی.", "خطا", MessageBoxButton.OK, MessageBoxImage.Exclamation);
            }
        }

        private static void CopyText(string text, Button button)
        {
            try
            {
                Clipboard.SetText(text);
            }
            catch
            {
                return;
            }
            button.Content = Loc.T("کپی شد ✓");
            DispatcherTimer timer = new DispatcherTimer();
            timer.Interval = TimeSpan.FromMilliseconds(1500.0);
            timer.Tick += (object s, EventArgs e) =>
            {
                timer.Stop();
                button.Content = Loc.T("کپی");
            };
            timer.Start();
        }

        protected override void OnPreviewKeyDown(KeyEventArgs e)
        {
            if (e.Key == Key.Escape)
            {
                e.Handled = true;
                Close();
                return;
            }
            base.OnPreviewKeyDown(e);
        }
    }

    public sealed class SupportToast : Window
    {
        public const string DonateUrl = "http://Daramet.com/NovelFa";

        private const string MessageText = "از اینکه با حمایت‌های گرمتون باعث رشد تیم ما میشید صمیمانه سپاسگزاریم ❤️";
        private const double Dot = 26.0;
        private const double Full = 250.0;
        private const double Pad = 14.0;
        private const double SlideSeconds = 8.0;

        [DllImport("user32.dll")]
        private static extern int GetWindowLong(IntPtr hWnd, int nIndex);

        [DllImport("user32.dll")]
        private static extern int SetWindowLong(IntPtr hWnd, int nIndex, int dwNewLong);

        public static SupportToast Current;

        private readonly Window ownerWindow;
        private readonly FrameworkElement anchor;
        private readonly bool rightSide;
        private Border pill;
        private Border closeBox;
        private TextBlock textBlock;
        private ScaleTransform scale;
        private double textWidth;
        private double areaWidth;
        private bool closing;

        private static Brush Res(string key)
        {
            return (Brush)Application.Current.FindResource(key);
        }

        // rightSide = true برای کتاب‌های چپ‌به‌راست (انگلیسی): پایین‌راست، اسلاید از راست به چپ
        public static SupportToast ShowFor(Window owner, FrameworkElement anchorElement, bool rightSide)
        {
            if (Current != null || owner == null)
            {
                return Current;
            }
            SupportToast toast = new SupportToast(owner, anchorElement, rightSide);
            Current = toast;
            toast.Reposition();
            toast.Show();
            return toast;
        }

        private SupportToast(Window owner, FrameworkElement anchorElement, bool rightSideFlag)
        {
            ownerWindow = owner;
            rightSide = rightSideFlag;
            anchor = anchorElement ?? owner;

            Owner = owner;
            WindowStyle = WindowStyle.None;
            AllowsTransparency = true;
            Background = Brushes.Transparent;
            ResizeMode = ResizeMode.NoResize;
            ShowInTaskbar = false;
            ShowActivated = false;
            Topmost = false;
            Focusable = false;
            WindowStartupLocation = WindowStartupLocation.Manual;
            FlowDirection = FlowDirection.LeftToRight;
            FontFamily = (FontFamily)Application.Current.FindResource("AppFont");
            Width = Full + Pad * 2.0;
            Height = Dot + Pad * 2.0;
            Title = "حمایت";

            BuildUi();

            // پنجره فوکوس کیبورد را از خواننده نگیرد و توی Alt+Tab نیاید
            SourceInitialized += (object s, EventArgs e) =>
            {
                try
                {
                    IntPtr handle = new WindowInteropHelper(this).Handle;
                    int style = GetWindowLong(handle, -20);
                    SetWindowLong(handle, -20, style | 0x08000000 | 0x00000080);
                }
                catch
                {
                }
            };

            owner.LocationChanged += OwnerMoved;
            owner.SizeChanged += OwnerResized;
            owner.StateChanged += OwnerMoved;
            owner.Closed += OwnerClosed;
            anchor.SizeChanged += OwnerResized;
            Closed += (object s, EventArgs e) =>
            {
                owner.LocationChanged -= OwnerMoved;
                owner.SizeChanged -= OwnerResized;
                owner.StateChanged -= OwnerMoved;
                owner.Closed -= OwnerClosed;
                anchor.SizeChanged -= OwnerResized;
                if (Current == this)
                {
                    Current = null;
                }
            };
            Loaded += (object s, RoutedEventArgs e) =>
            {
                Reposition();
                StartIntro();
            };
        }

        private void OwnerMoved(object sender, EventArgs e)
        {
            Reposition();
        }

        private void OwnerResized(object sender, SizeChangedEventArgs e)
        {
            Reposition();
        }

        private void OwnerClosed(object sender, EventArgs e)
        {
            Close();
        }

        private void BuildUi()
        {
            Color c1 = ((SolidColorBrush)Res("AccentColor")).Color;
            Color c2 = ((SolidColorBrush)Res("AccentColorDark")).Color;
            Brush fore = Res("OnAccent");

            scale = new ScaleTransform(0.0, 0.0);

            pill = new Border();
            pill.Width = Dot;
            pill.Height = Dot;
            pill.CornerRadius = new CornerRadius(Dot / 2.0);
            pill.HorizontalAlignment = rightSide ? HorizontalAlignment.Right : HorizontalAlignment.Left;
            pill.VerticalAlignment = VerticalAlignment.Center;
            pill.Margin = rightSide ? new Thickness(0.0, 0.0, Pad, 0.0) : new Thickness(Pad, 0.0, 0.0, 0.0);
            pill.ClipToBounds = true;
            pill.Opacity = 0.0;
            pill.Cursor = Cursors.Hand;
            pill.ToolTip = "حمایت از ناول‌فا";
            pill.RenderTransformOrigin = new Point(rightSide ? 1.0 : 0.0, 0.5);
            pill.RenderTransform = scale;
            pill.Background = new LinearGradientBrush(c1, c2, new Point(0.0, 0.0), new Point(1.0, 1.0));
            pill.Effect = new DropShadowEffect
            {
                Color = c2,
                BlurRadius = 14.0,
                ShadowDepth = 3.0,
                Opacity = 0.5
            };
            pill.MouseLeftButtonUp += (object s, MouseButtonEventArgs e) =>
            {
                OpenUrl(DonateUrl);
                Dismiss();
            };

            Grid inner = new Grid();
            inner.Width = Full;
            inner.HorizontalAlignment = rightSide ? HorizontalAlignment.Right : HorizontalAlignment.Left;
            if (rightSide)
            {
                inner.ColumnDefinitions.Add(new ColumnDefinition { Width = GridLength.Auto });
                inner.ColumnDefinitions.Add(new ColumnDefinition { Width = new GridLength(1.0, GridUnitType.Star) });
            }
            else
            {
                inner.ColumnDefinitions.Add(new ColumnDefinition { Width = new GridLength(1.0, GridUnitType.Star) });
                inner.ColumnDefinitions.Add(new ColumnDefinition { Width = GridLength.Auto });
            }

            Canvas area = new Canvas();
            area.ClipToBounds = true;
            area.Margin = rightSide ? new Thickness(0.0, 0.0, 12.0, 0.0) : new Thickness(12.0, 0.0, 0.0, 0.0);
            area.Background = Brushes.Transparent;
            Grid.SetColumn(area, rightSide ? 1 : 0);

            textBlock = new TextBlock();
            textBlock.Text = Loc.T(MessageText);
            textBlock.FontSize = 12.0;
            textBlock.FontWeight = FontWeights.Medium;
            textBlock.Foreground = fore;
            textBlock.FlowDirection = Loc.Flow;
            textBlock.TextWrapping = TextWrapping.NoWrap;
            textBlock.Measure(new Size(double.PositiveInfinity, double.PositiveInfinity));
            textWidth = textBlock.DesiredSize.Width + 4.0;
            areaWidth = Full - 12.0 - 26.0;
            // متن فارسی همیشه از چپ شروع می‌شود و به راست اسلاید می‌شود (اول جمله زودتر دیده می‌شود)
            Canvas.SetLeft(textBlock, -textWidth);
            Canvas.SetTop(textBlock, (Dot - textBlock.DesiredSize.Height) / 2.0);
            area.Children.Add(textBlock);
            inner.Children.Add(area);

            closeBox = new Border();
            closeBox.Width = 22.0;
            closeBox.Height = 22.0;
            closeBox.CornerRadius = new CornerRadius(11.0);
            closeBox.Margin = rightSide ? new Thickness(4.0, 0.0, 0.0, 0.0) : new Thickness(0.0, 0.0, 4.0, 0.0);
            closeBox.VerticalAlignment = VerticalAlignment.Center;
            closeBox.Background = Brushes.Transparent;
            closeBox.Opacity = 0.0;
            closeBox.IsHitTestVisible = false;
            closeBox.Cursor = Cursors.Hand;
            closeBox.ToolTip = "بستن";
            closeBox.Child = new TextBlock
            {
                Text = "×",
                Foreground = fore,
                FontSize = 15.0,
                HorizontalAlignment = HorizontalAlignment.Center,
                VerticalAlignment = VerticalAlignment.Center,
                Margin = new Thickness(0.0, -2.0, 0.0, 0.0)
            };
            closeBox.MouseEnter += (object s, MouseEventArgs e) => { closeBox.Background = new SolidColorBrush(Color.FromArgb(60, 255, 255, 255)); };
            closeBox.MouseLeave += (object s, MouseEventArgs e) => { closeBox.Background = Brushes.Transparent; };
            closeBox.MouseLeftButtonUp += (object s, MouseButtonEventArgs e) =>
            {
                e.Handled = true;
                Dismiss();
            };
            Grid.SetColumn(closeBox, rightSide ? 0 : 1);
            inner.Children.Add(closeBox);

            pill.Child = inner;
            Content = pill;
        }

        // پایین‌سمت‌چپِ کارت خواندن (بیرون از دکمه‌های پایین صفحه)
        private void Reposition()
        {
            try
            {
                if (anchor == null || !anchor.IsLoaded)
                {
                    return;
                }
                PresentationSource source = PresentationSource.FromVisual(anchor);
                if (source == null || source.CompositionTarget == null)
                {
                    return;
                }
                double w = anchor.ActualWidth;
                double h = anchor.ActualHeight;
                Point p1 = anchor.PointToScreen(new Point(0.0, h));
                Point p2 = anchor.PointToScreen(new Point(w, h));
                Point dev = new Point(rightSide ? Math.Max(p1.X, p2.X) : Math.Min(p1.X, p2.X), Math.Max(p1.Y, p2.Y));
                Point dip = source.CompositionTarget.TransformFromDevice.Transform(dev);
                if (rightSide)
                {
                    Left = dip.X - 22.0 - Full - Pad;
                }
                else
                {
                    Left = dip.X + 22.0 - Pad;
                }
                Top = dip.Y - 14.0 - Dot - Pad;
            }
            catch
            {
            }
        }

        private void StartIntro()
        {
            pill.BeginAnimation(UIElement.OpacityProperty, new DoubleAnimation(0.0, 1.0, TimeSpan.FromSeconds(0.25)));

            DoubleAnimation pop = new DoubleAnimation(0.0, 1.0, TimeSpan.FromSeconds(0.4));
            pop.EasingFunction = new BackEase { EasingMode = EasingMode.EaseOut, Amplitude = 0.6 };
            scale.BeginAnimation(ScaleTransform.ScaleXProperty, pop);
            scale.BeginAnimation(ScaleTransform.ScaleYProperty, pop);

            DoubleAnimation expand = new DoubleAnimation(Dot, Full, TimeSpan.FromSeconds(0.5));
            expand.BeginTime = TimeSpan.FromSeconds(0.4);
            expand.EasingFunction = new CubicEase { EasingMode = EasingMode.EaseOut };
            pill.BeginAnimation(FrameworkElement.WidthProperty, expand);

            DoubleAnimation showX = new DoubleAnimation(0.0, 1.0, TimeSpan.FromSeconds(0.3));
            showX.BeginTime = TimeSpan.FromSeconds(0.8);
            showX.Completed += (object s, EventArgs e) =>
            {
                if (!closing)
                {
                    closeBox.IsHitTestVisible = true;
                }
            };
            closeBox.BeginAnimation(UIElement.OpacityProperty, showX);

            DoubleAnimation slide = new DoubleAnimation(-textWidth, areaWidth, TimeSpan.FromSeconds(SlideSeconds));
            slide.BeginTime = TimeSpan.FromSeconds(0.6);
            slide.Completed += (object s, EventArgs e) => { StartOutro(); };
            textBlock.BeginAnimation(Canvas.LeftProperty, slide);
        }

        private void Dismiss()
        {
            StartOutro();
        }

        private void StartOutro()
        {
            if (closing)
            {
                return;
            }
            closing = true;
            closeBox.IsHitTestVisible = false;
            pill.IsHitTestVisible = false;

            closeBox.BeginAnimation(UIElement.OpacityProperty, new DoubleAnimation(0.0, TimeSpan.FromSeconds(0.15)));
            textBlock.BeginAnimation(UIElement.OpacityProperty, new DoubleAnimation(0.0, TimeSpan.FromSeconds(0.15)));

            DoubleAnimation shrink = new DoubleAnimation(Dot, TimeSpan.FromSeconds(0.45));
            shrink.BeginTime = TimeSpan.FromSeconds(0.1);
            shrink.EasingFunction = new CubicEase { EasingMode = EasingMode.EaseInOut };
            pill.BeginAnimation(FrameworkElement.WidthProperty, shrink);

            DoubleAnimation vanish = new DoubleAnimation(0.0, TimeSpan.FromSeconds(0.35));
            vanish.BeginTime = TimeSpan.FromSeconds(0.55);
            vanish.EasingFunction = new BackEase { EasingMode = EasingMode.EaseIn, Amplitude = 0.6 };
            vanish.Completed += (object s, EventArgs e) => { Close(); };
            scale.BeginAnimation(ScaleTransform.ScaleXProperty, vanish);
            scale.BeginAnimation(ScaleTransform.ScaleYProperty, new DoubleAnimation(0.0, TimeSpan.FromSeconds(0.35)) { BeginTime = TimeSpan.FromSeconds(0.55), EasingFunction = new BackEase { EasingMode = EasingMode.EaseIn, Amplitude = 0.6 } });
            pill.BeginAnimation(UIElement.OpacityProperty, new DoubleAnimation(0.0, TimeSpan.FromSeconds(0.2)) { BeginTime = TimeSpan.FromSeconds(0.7) });
        }

        private static void OpenUrl(string url)
        {
            try
            {
                Process.Start(new ProcessStartInfo(url) { UseShellExecute = true });
            }
            catch
            {

            }
        }
    }
}