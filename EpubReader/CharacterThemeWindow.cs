using System;
using System.Windows;
using System.Windows.Controls;
using System.Windows.Input;
using System.Windows.Media;
using System.Windows.Media.Animation;
using System.Windows.Media.Effects;
using System.Windows.Shapes;
using System.Windows.Threading;
using EpubReader.Services;

namespace EpubReader
{

// پنجره‌ی تم‌های شخصیت‌ها (ری‌زیرو): هر شخصیت با یک رتبه‌ی کتابخوانی قفل شده.
// تم‌های بازشده با یک کلیک روی کل برنامه (کتابخانه + صفحه‌ی خواندن) اعمال می‌شن.
public sealed class CharacterThemeWindow : Window
{
	private readonly Action onChanged;
	private readonly int doneBooks;
	private StackPanel cardsPanel;
	private TextBlock toast;
	private DispatcherTimer toastTimer;
	private Button particlesButton;

	public static void ShowFor(Window owner, int doneBooks, Action onChanged)
	{
		CharacterThemeWindow win = new CharacterThemeWindow(doneBooks, onChanged);
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

	private CharacterThemeWindow(int doneBooks, Action onChanged)
	{
		this.onChanged = onChanged;
		this.doneBooks = doneBooks;

		WindowStyle = WindowStyle.None;
		AllowsTransparency = true;
		Background = Brushes.Transparent;
		ResizeMode = ResizeMode.NoResize;
		ShowInTaskbar = false;
		Width = 580.0;
		SizeToContent = SizeToContent.Height;
		MaxHeight = Math.Max(420.0, SystemParameters.WorkArea.Height - 20.0);
		FlowDirection = Loc.Flow;
		FontFamily = (FontFamily)Application.Current.FindResource("AppFont");
		Title = Loc.T("تم‌های ری‌زیرو");

		Border root = new Border();
		root.SetResourceReference(Border.BackgroundProperty, "BgDark");
		root.SetResourceReference(Border.BorderBrushProperty, "BorderColor");
		root.BorderThickness = new Thickness(1.0);
		root.CornerRadius = new CornerRadius(14.0);
		root.Margin = new Thickness(16.0);
		root.Padding = new Thickness(22.0, 20.0, 22.0, 18.0);
		root.Effect = new DropShadowEffect
		{
			Color = Colors.Black,
			BlurRadius = 24.0,
			ShadowDepth = 4.0,
			Opacity = 0.6
		};

		Grid layout = new Grid();
		layout.RowDefinitions.Add(new RowDefinition { Height = GridLength.Auto });
		layout.RowDefinitions.Add(new RowDefinition { Height = new GridLength(1.0, GridUnitType.Star) });
		layout.RowDefinitions.Add(new RowDefinition { Height = GridLength.Auto });

		StackPanel header = BuildHeader();
		Grid.SetRow(header, 0);
		layout.Children.Add(header);

		cardsPanel = new StackPanel();
		ScrollViewer scroll = new ScrollViewer();
		scroll.VerticalScrollBarVisibility = ScrollBarVisibility.Auto;
		scroll.HorizontalScrollBarVisibility = ScrollBarVisibility.Disabled;
		scroll.Content = cardsPanel;
		Style scrollStyle = Application.Current.TryFindResource("ModernScrollViewer") as Style;
		if (scrollStyle != null)
		{
			scroll.Style = scrollStyle;
		}
		Grid.SetRow(scroll, 1);
		layout.Children.Add(scroll);

		StackPanel footer = BuildFooter();
		Grid.SetRow(footer, 2);
		layout.Children.Add(footer);

		root.Child = layout;
		Content = root;

		RebuildCards();

		Closed += (object s, EventArgs e) =>
		{
			if (toastTimer != null)
			{
				toastTimer.Stop();
			}
		};
	}

	private static TextBlock MakeText(string text, string brushKey, double size, FontWeight weight, Thickness margin)
	{
		TextBlock tb = new TextBlock();
		tb.Text = text;
		tb.FontSize = size;
		tb.FontWeight = weight;
		tb.Margin = margin;
		tb.TextWrapping = TextWrapping.Wrap;
		tb.SetResourceReference(TextBlock.ForegroundProperty, brushKey);
		return tb;
	}

	private static Color Col(string hex)
	{
		Color c;
		if (!ThemeManager.TryParseColor(hex, out c))
		{
			c = Colors.Magenta;
		}
		return c;
	}

	private static Brush Diagonal(string hex1, string hex2)
	{
		LinearGradientBrush b = new LinearGradientBrush();
		b.StartPoint = new Point(0.0, 0.0);
		b.EndPoint = new Point(1.0, 1.0);
		b.GradientStops.Add(new GradientStop(Col(hex1), 0.0));
		b.GradientStops.Add(new GradientStop(Col(hex2), 1.0));
		b.Freeze();
		return b;
	}

	private static Brush Horizontal(string hex1, string hex2)
	{
		LinearGradientBrush b = new LinearGradientBrush();
		b.StartPoint = new Point(0.0, 0.5);
		b.EndPoint = new Point(1.0, 0.5);
		b.GradientStops.Add(new GradientStop(Col(hex1), 0.0));
		b.GradientStops.Add(new GradientStop(Col(hex2), 1.0));
		b.Freeze();
		return b;
	}

	private StackPanel BuildHeader()
	{
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

		header.Children.Add(MakeText("تم‌های ری‌زیرو", "TextColor", 18.0, FontWeights.Bold, new Thickness(0.0)));
		header.Children.Add(MakeText("هر شخصیت با یک رتبه‌ی کتابخوانی قفل شده. کتاب رو تا آخر بخون، رتبه بگیر و تمِ شخصیت بعدی رو باز کن. تم روی کتابخونه و صفحه‌ی خواندن اعمال می‌شه.",
			"TextMuted", 12.0, FontWeights.Normal, new Thickness(0.0, 6.0, 0.0, 14.0)));

		int rankIndex = RankService.RankIndexFor(doneBooks);
		RankInfo current = RankService.Get(rankIndex);
		RankInfo next = (rankIndex + 1 < RankService.Ranks.Length) ? RankService.Ranks[rankIndex + 1] : null;

		Border box = new Border();
		box.CornerRadius = new CornerRadius(12.0);
		box.Padding = new Thickness(14.0, 12.0, 14.0, 12.0);
		box.Margin = new Thickness(0.0, 0.0, 0.0, 14.0);
		box.SetResourceReference(Border.BackgroundProperty, "AccentSoft");

		StackPanel inner = new StackPanel();
		Grid top = new Grid();
		top.ColumnDefinitions.Add(new ColumnDefinition { Width = new GridLength(1.0, GridUnitType.Star) });
		top.ColumnDefinitions.Add(new ColumnDefinition { Width = GridLength.Auto });
		TextBlock rankText = MakeText(Loc.F("رتبه‌ی فعلی: «{0}»", current.Name), "AccentColor", 14.0, FontWeights.Bold, new Thickness(0.0));
		Grid.SetColumn(rankText, 0);
		top.Children.Add(rankText);
		TextBlock countText = MakeText(Loc.FN("{0} کتاب تمام‌شده", doneBooks, doneBooks), "TextMuted", 11.5, FontWeights.Normal, new Thickness(0.0));
		countText.VerticalAlignment = VerticalAlignment.Center;
		Grid.SetColumn(countText, 1);
		top.Children.Add(countText);
		inner.Children.Add(top);

		if (next != null)
		{
			double span = Math.Max(1.0, next.MinBooks - current.MinBooks);
			double value = Math.Max(0.0, Math.Min(100.0, (doneBooks - current.MinBooks) * 100.0 / span));
			ProgressBar bar = new ProgressBar();
			bar.Style = (Style)Application.Current.FindResource("ThinProgress");
			bar.Height = 6.0;
			bar.Value = value;
			bar.Margin = new Thickness(0.0, 10.0, 0.0, 0.0);
			inner.Children.Add(bar);

			int left = Math.Max(1, next.MinBooks - doneBooks);
			CharacterTheme reward = CharacterThemes.ForRank(next.Index);
			string text = Loc.FN("{0} کتاب دیگه تا رتبه‌ی «{1}»", left, left, next.Name);
			if (reward != null)
			{
				text += Loc.F(" و باز شدن تم «{0}»", reward.Name);
			}
			inner.Children.Add(MakeText(text, "TextColor", 11.5, FontWeights.Normal, new Thickness(0.0, 8.0, 0.0, 0.0)));
		}
		else
		{
			inner.Children.Add(MakeText("به بالاترین رتبه رسیدی؛ همه‌ی تم‌ها مال توئه ✦", "TextColor", 11.5, FontWeights.Normal, new Thickness(0.0, 8.0, 0.0, 0.0)));
		}
		box.Child = inner;
		header.Children.Add(box);
		return header;
	}

	private StackPanel BuildFooter()
	{
		StackPanel footer = new StackPanel();

		toast = MakeText("", "AccentColor", 12.0, FontWeights.SemiBold, new Thickness(0.0, 6.0, 0.0, 6.0));
		toast.MinHeight = 34.0;
		footer.Children.Add(toast);

		WrapPanel buttons = new WrapPanel();
		buttons.HorizontalAlignment = HorizontalAlignment.Left;

		Style modern = (Style)Application.Current.FindResource("ModernButton");
		Style soft = (Style)Application.Current.FindResource("SoftButton");

		Button closeButton = new Button();
		closeButton.Content = "بستن";
		closeButton.MinWidth = 90.0;
		closeButton.Margin = new Thickness(0.0, 0.0, 8.0, 0.0);
		closeButton.Style = modern;
		closeButton.IsCancel = true;
		closeButton.Click += (object s, RoutedEventArgs e) => { Close(); };
		buttons.Children.Add(closeButton);

		Button plainButton = new Button();
		plainButton.Content = "بدون تم شخصیت";
		plainButton.Margin = new Thickness(0.0, 0.0, 8.0, 0.0);
		plainButton.Style = soft;
		plainButton.ToolTip = "برگشت به رنگ ساده‌ی برنامه";
		plainButton.Click += (object s, RoutedEventArgs e) =>
		{
			ThemeManager.SetCharacter(null, true);
			NotifyChanged();
			RebuildCards();
			ShowToast("به رنگ ساده‌ی برنامه برگشتی. رنگ دلخواه رو می‌تونی از «رنگ برنامه» بذاری.", false);
		};
		buttons.Children.Add(plainButton);

		particlesButton = new Button();
		particlesButton.Style = soft;
		particlesButton.ToolTip = "جرقه، گلبرگ، برف و پروانه‌ی متحرک روی کتابخونه";
		particlesButton.Click += (object s, RoutedEventArgs e) =>
		{
			ThemeParticleLayer.Enabled = !ThemeParticleLayer.Enabled;
			UpdateParticlesButton();
			// دوباره اعمال می‌کنه تا لایه‌ی ذرات روشن/خاموش بشه
			CharacterTheme active = ThemeManager.ActiveCharacter;
			ThemeManager.SetCharacter(active == null ? null : active.Id, false);
			NotifyChanged();
		};
		buttons.Children.Add(particlesButton);
		UpdateParticlesButton();

		footer.Children.Add(buttons);
		return footer;
	}

	private void UpdateParticlesButton()
	{
		particlesButton.Content = Loc.T(ThemeParticleLayer.Enabled ? "ذرات متحرک: روشن" : "ذرات متحرک: خاموش");
	}

	private void NotifyChanged()
	{
		if (onChanged != null)
		{
			onChanged();
		}
	}

	private void ShowToast(string message, bool warning)
	{
		toast.Text = Loc.T(message);
		toast.SetResourceReference(TextBlock.ForegroundProperty, warning ? "TextColor" : "AccentColor");
		if (toastTimer == null)
		{
			toastTimer = new DispatcherTimer();
			toastTimer.Interval = TimeSpan.FromSeconds(5.0);
			toastTimer.Tick += (object s, EventArgs e) =>
			{
				toastTimer.Stop();
				toast.Text = "";
			};
		}
		toastTimer.Stop();
		toastTimer.Start();
	}

	private void RebuildCards()
	{
		cardsPanel.Children.Clear();
		foreach (CharacterTheme t in CharacterThemes.All)
		{
			if (t.SecretOnly && !RankService.IsUnlocked(t))
			{
				continue;
			}
			cardsPanel.Children.Add(BuildCard(t));
		}
	}

	private UIElement BuildCard(CharacterTheme t)
	{
		bool unlocked = RankService.IsUnlocked(t);
		CharacterTheme activeNow = ThemeManager.ActiveCharacter;
		bool active = activeNow != null && activeNow.Id == t.Id;
		ThemePalette p = t.Palette(ThemeManager.IsLight);
		RankInfo need = RankService.Get(t.RequiredRank);

		Border card = new Border();
		card.CornerRadius = new CornerRadius(14.0);
		card.Padding = new Thickness(12.0);
		card.Margin = new Thickness(0.0, 0.0, 0.0, 10.0);
		card.BorderThickness = new Thickness(active ? 2.0 : 1.0);
		card.SetResourceReference(Border.BackgroundProperty, "BgCard");
		card.SetResourceReference(Border.BorderBrushProperty, active ? "AccentColor" : "BorderColor");
		card.Cursor = unlocked ? Cursors.Hand : Cursors.No;
		TranslateTransform shake = new TranslateTransform();
		card.RenderTransform = shake;

		Grid g = new Grid();
		g.ColumnDefinitions.Add(new ColumnDefinition { Width = GridLength.Auto });
		g.ColumnDefinitions.Add(new ColumnDefinition { Width = new GridLength(1.0, GridUnitType.Star) });
		g.ColumnDefinitions.Add(new ColumnDefinition { Width = GridLength.Auto });

		// پیش‌نمایش: زمینه‌ی هیرو + نشان شخصیت + نوار رنگ
		Grid preview = new Grid();
		preview.Width = 98.0;
		preview.Height = 74.0;
		Border pbg = new Border();
		pbg.CornerRadius = new CornerRadius(12.0);
		pbg.Background = Diagonal(p.Hero1, p.Hero2);
		pbg.BorderThickness = new Thickness(1.0);
		pbg.BorderBrush = new SolidColorBrush(Col(p.Border));
		preview.Children.Add(pbg);

		Path emblem = new Path();
		emblem.Data = t.Emblem;
		emblem.Stretch = Stretch.Uniform;
		emblem.Width = 46.0;
		emblem.Height = 46.0;
		emblem.Fill = Diagonal(p.Grad1, p.Grad2);
		emblem.HorizontalAlignment = HorizontalAlignment.Center;
		emblem.VerticalAlignment = VerticalAlignment.Center;
		emblem.Margin = new Thickness(0.0, 0.0, 0.0, 6.0);
		emblem.Opacity = unlocked ? 1.0 : 0.4;
		preview.Children.Add(emblem);

		Border strip = new Border();
		strip.Height = 5.0;
		strip.Margin = new Thickness(10.0, 0.0, 10.0, 8.0);
		strip.CornerRadius = new CornerRadius(3.0);
		strip.VerticalAlignment = VerticalAlignment.Bottom;
		strip.Background = Horizontal(p.Grad1, p.Grad2);
		strip.Opacity = unlocked ? 1.0 : 0.35;
		preview.Children.Add(strip);

		if (!unlocked)
		{
			Border dim = new Border();
			dim.CornerRadius = new CornerRadius(12.0);
			dim.Background = new SolidColorBrush(Color.FromArgb(0x66, 0x00, 0x00, 0x00));
			preview.Children.Add(dim);

			Border lockBadge = new Border();
			lockBadge.Width = 30.0;
			lockBadge.Height = 30.0;
			lockBadge.CornerRadius = new CornerRadius(15.0);
			lockBadge.Background = new SolidColorBrush(Color.FromArgb(0xC8, 0x0B, 0x0B, 0x12));
			lockBadge.HorizontalAlignment = HorizontalAlignment.Center;
			lockBadge.VerticalAlignment = VerticalAlignment.Center;
			Path lockIcon = new Path();
			lockIcon.Style = (Style)Application.Current.FindResource("Ico");
			lockIcon.Data = (Geometry)Application.Current.FindResource("IcoLock");
			lockIcon.Stroke = Brushes.White;
			lockIcon.HorizontalAlignment = HorizontalAlignment.Center;
			lockBadge.Child = lockIcon;
			preview.Children.Add(lockBadge);
		}
		Grid.SetColumn(preview, 0);
		g.Children.Add(preview);

		// نام، توضیح و شرط باز شدن
		StackPanel info = new StackPanel();
		info.VerticalAlignment = VerticalAlignment.Center;
		info.Margin = new Thickness(14.0, 0.0, 10.0, 0.0);

		StackPanel nameRow = new StackPanel();
		nameRow.Orientation = Orientation.Horizontal;
		nameRow.Children.Add(MakeText(t.Name, "TextColor", 16.0, FontWeights.Bold, new Thickness(0.0)));
		if (!string.Equals(t.FullName, t.Name, StringComparison.Ordinal))
		{
			TextBlock full = MakeText(t.FullName, "TextMuted", 11.0, FontWeights.Normal, new Thickness(8.0, 0.0, 0.0, 0.0));
			full.VerticalAlignment = VerticalAlignment.Center;
			nameRow.Children.Add(full);
		}
		info.Children.Add(nameRow);
		info.Children.Add(MakeText(t.Tagline, "TextMuted", 11.5, FontWeights.Normal, new Thickness(0.0, 3.0, 0.0, 0.0)));

		if (unlocked)
		{
			string line;
			if (!RankService.IsUnlockedByRank(t))
			{
				line = Loc.T("باز شد · با یک راز ✦");
			}
			else
			{
				line = (t.RequiredRank == 0) ? Loc.F("از همون اول باز بود · رتبه‌ی «{0}»", need.Name) : Loc.F("باز شد · رتبه‌ی «{0}»", need.Name);
			}
			info.Children.Add(MakeText(line, "SuccessColor", 11.0, FontWeights.SemiBold, new Thickness(0.0, 8.0, 0.0, 0.0)));
		}
		else
		{
			info.Children.Add(MakeText(Loc.FN("چالش: {0} کتاب رو تا آخر بخون ← رتبه‌ی «{1}»", need.MinBooks, need.MinBooks, need.Name),
				"TextColor", 11.5, FontWeights.SemiBold, new Thickness(0.0, 8.0, 0.0, 0.0)));
			ProgressBar bar = new ProgressBar();
			bar.Style = (Style)Application.Current.FindResource("ThinProgress");
			bar.Height = 5.0;
			bar.Value = Math.Max(0.0, Math.Min(100.0, doneBooks * 100.0 / Math.Max(1, need.MinBooks)));
			bar.Margin = new Thickness(0.0, 7.0, 0.0, 0.0);
			info.Children.Add(bar);
			info.Children.Add(MakeText(Loc.F("{0} از {1} کتاب", Math.Min(doneBooks, need.MinBooks), need.MinBooks),
				"TextMuted", 10.5, FontWeights.Normal, new Thickness(0.0, 4.0, 0.0, 0.0)));
		}
		Grid.SetColumn(info, 1);
		g.Children.Add(info);

		// وضعیت / دکمه
		FrameworkElement status;
		if (active)
		{
			status = MakeText("✓ فعال", "AccentColor", 13.0, FontWeights.Bold, new Thickness(6.0, 0.0, 6.0, 0.0));
			status.VerticalAlignment = VerticalAlignment.Center;
		}
		else if (unlocked)
		{
			Button apply = new Button();
			apply.Content = "فعال‌سازی";
			apply.Style = (Style)Application.Current.FindResource("SoftButton");
			apply.Padding = new Thickness(14.0, 8.0, 14.0, 8.0);
			apply.VerticalAlignment = VerticalAlignment.Center;
			CharacterTheme chosen = t;
			apply.Click += (object s, RoutedEventArgs e) => { Apply(chosen); };
			status = apply;
		}
		else
		{
			TextBlock lockedText = MakeText("قفل", "TextMuted", 12.0, FontWeights.SemiBold, new Thickness(6.0, 0.0, 6.0, 0.0));
			lockedText.VerticalAlignment = VerticalAlignment.Center;
			status = lockedText;
		}
		Grid.SetColumn(status, 2);
		g.Children.Add(status);

		card.Child = g;

		CharacterTheme theme = t;
		card.MouseLeftButtonUp += (object s, MouseButtonEventArgs e) =>
		{
			if (e.Handled)
			{
				return;
			}
			if (RankService.IsUnlocked(theme))
			{
				Apply(theme);
			}
			else
			{
				Deny(theme, shake);
			}
		};
		return card;
	}

	private void Apply(CharacterTheme t)
	{
		if (!ThemeManager.SetCharacter(t.Id, true))
		{
			return;
		}
		NotifyChanged();
		RebuildCards();
		ShowToast(Loc.F("تم «{0}» فعال شد.", t.Name), false);
	}

	private void Deny(CharacterTheme t, TranslateTransform shake)
	{
		RankInfo need = RankService.Get(t.RequiredRank);
		int left = Math.Max(1, need.MinBooks - doneBooks);
		ShowToast(Loc.FN("برای باز شدن «{0}» باید به رتبه‌ی «{1}» برسی؛ {2} کتاب دیگه تا آخر بخون.", left, t.Name, need.Name, left), true);

		DoubleAnimationUsingKeyFrames anim = new DoubleAnimationUsingKeyFrames();
		anim.KeyFrames.Add(new LinearDoubleKeyFrame(0.0, KeyTime.FromTimeSpan(TimeSpan.Zero)));
		anim.KeyFrames.Add(new LinearDoubleKeyFrame(-7.0, KeyTime.FromTimeSpan(TimeSpan.FromMilliseconds(60))));
		anim.KeyFrames.Add(new LinearDoubleKeyFrame(7.0, KeyTime.FromTimeSpan(TimeSpan.FromMilliseconds(140))));
		anim.KeyFrames.Add(new LinearDoubleKeyFrame(-5.0, KeyTime.FromTimeSpan(TimeSpan.FromMilliseconds(220))));
		anim.KeyFrames.Add(new LinearDoubleKeyFrame(5.0, KeyTime.FromTimeSpan(TimeSpan.FromMilliseconds(290))));
		anim.KeyFrames.Add(new LinearDoubleKeyFrame(0.0, KeyTime.FromTimeSpan(TimeSpan.FromMilliseconds(360))));
		shake.BeginAnimation(TranslateTransform.XProperty, anim);
	}
}
}
