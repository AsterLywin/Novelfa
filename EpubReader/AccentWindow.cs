using System;
using System.Windows;
using System.Windows.Controls;
using System.Windows.Input;
using System.Windows.Media;
using System.Windows.Media.Effects;
using System.Windows.Shapes;
using EpubReader.Services;

namespace EpubReader
{

// پنجره‌ی انتخاب رنگ برنامه: هفت رنگ آماده + ساختن هر رنگ دلخواه.
// هر تغییر همون لحظه روی کل برنامه اعمال و ذخیره می‌شه.
public sealed class AccentWindow : Window
{
	private const double PickerWidth = 380.0;
	private const double PickerHeight = 150.0;
	private const double BarHeight = 18.0;

	private readonly Action onChanged;
	private readonly Border[] swatches;
	private readonly TextBlock[] swatchChecks;
	private readonly string[] presetHex;

	private Grid area;
	private Grid bar;
	private Ellipse areaDot;
	private Border barKnob;
	private GradientStop barEndStop;
	private Border preview;
	private TextBox hexBox;
	private bool suppressHex = false;
	private bool changed = false;

	private double hue;
	private double sat;
	private double val;
	private Color current;
	private string currentHex;

	public static void ShowFor(Window owner, Action onChanged)
	{
		AccentWindow win = new AccentWindow(onChanged);
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

	private AccentWindow(Action onChanged)
	{
		this.onChanged = onChanged;

		WindowStyle = WindowStyle.None;
		AllowsTransparency = true;
		Background = Brushes.Transparent;
		ResizeMode = ResizeMode.NoResize;
		ShowInTaskbar = false;
		Width = 464.0;
		SizeToContent = SizeToContent.Height;
		FlowDirection = Loc.Flow;
		FontFamily = (FontFamily)Application.Current.FindResource("AppFont");
		Title = "رنگ برنامه";

		AccentPreset[] presets = ThemeManager.Presets;
		swatches = new Border[presets.Length];
		swatchChecks = new TextBlock[presets.Length];
		presetHex = new string[presets.Length];

		Color start;
		if (!ThemeManager.TryParseColor(ThemeManager.Accent, out start))
		{
			ThemeManager.TryParseColor(ThemeManager.DefaultAccent, out start);
		}
		current = start;
		currentHex = ThemeManager.ToHex(start);
		ColorToHsv(start, out hue, out sat, out val);

		Border root = new Border();
		root.SetResourceReference(Border.BackgroundProperty, "BgDark");
		root.SetResourceReference(Border.BorderBrushProperty, "BorderColor");
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

		StackPanel stack = new StackPanel();

		// سرتیتر: با کشیدنش پنجره جابه‌جا می‌شه
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
		header.Children.Add(MakeText("رنگ برنامه", "TextColor", 16.0, FontWeights.Bold, new Thickness(0.0)));
		header.Children.Add(MakeText("یکی از رنگ‌های آماده رو بزن، یا پایین‌تر هر رنگی که دوست داری بساز. همون لحظه روی کل برنامه اعمال می‌شه.", "TextMuted", 12.0, FontWeights.Normal, new Thickness(0.0, 6.0, 0.0, 18.0)));
		stack.Children.Add(header);

		// رنگ‌های آماده
		stack.Children.Add(MakeText("رنگ‌های آماده", "TextMuted", 11.0, FontWeights.SemiBold, new Thickness(0.0, 0.0, 0.0, 10.0)));
		WrapPanel presetPanel = new WrapPanel();
		for (int i = 0; i < presets.Length; i++)
		{
			AccentPreset preset = presets[i];
			Color pc;
			ThemeManager.TryParseColor(preset.Hex, out pc);
			presetHex[i] = ThemeManager.ToHex(pc);

			TextBlock check = new TextBlock();
			check.Text = "✓";
			check.FontSize = 16.0;
			check.FontWeight = FontWeights.Bold;
			check.HorizontalAlignment = HorizontalAlignment.Center;
			check.VerticalAlignment = VerticalAlignment.Center;
			check.Foreground = (0.299 * pc.R + 0.587 * pc.G + 0.114 * pc.B > 150.0) ? Brushes.Black : Brushes.White;
			check.Visibility = Visibility.Collapsed;
			swatchChecks[i] = check;

			Border sw = new Border();
			sw.Width = 38.0;
			sw.Height = 38.0;
			sw.CornerRadius = new CornerRadius(19.0);
			sw.Background = new SolidColorBrush(pc);
			sw.BorderThickness = new Thickness(3.0);
			sw.BorderBrush = Brushes.Transparent;
			sw.Margin = new Thickness(0.0, 0.0, 12.0, 12.0);
			sw.Cursor = Cursors.Hand;
			sw.ToolTip = preset.Name;
			sw.Child = check;
			Color chosen = pc;
			sw.MouseLeftButtonUp += (object s, MouseButtonEventArgs e) =>
			{
				SetColor(chosen, false);
			};
			swatches[i] = sw;
			presetPanel.Children.Add(sw);
		}
		stack.Children.Add(presetPanel);

		// رنگ دلخواه
		stack.Children.Add(MakeText("رنگ دلخواه", "TextMuted", 11.0, FontWeights.SemiBold, new Thickness(0.0, 8.0, 0.0, 10.0)));
		area = BuildArea();
		stack.Children.Add(area);
		bar = BuildBar();
		stack.Children.Add(bar);

		area.MouseLeftButtonDown += (object s, MouseButtonEventArgs e) =>
		{
			area.CaptureMouse();
			PickArea(e);
		};
		area.MouseMove += (object s, MouseEventArgs e) =>
		{
			if (area.IsMouseCaptured)
			{
				PickArea(e);
			}
		};
		area.MouseLeftButtonUp += (object s, MouseButtonEventArgs e) =>
		{
			if (area.IsMouseCaptured)
			{
				area.ReleaseMouseCapture();
				Commit();
			}
		};
		bar.MouseLeftButtonDown += (object s, MouseButtonEventArgs e) =>
		{
			bar.CaptureMouse();
			PickBar(e);
		};
		bar.MouseMove += (object s, MouseEventArgs e) =>
		{
			if (bar.IsMouseCaptured)
			{
				PickBar(e);
			}
		};
		bar.MouseLeftButtonUp += (object s, MouseButtonEventArgs e) =>
		{
			if (bar.IsMouseCaptured)
			{
				bar.ReleaseMouseCapture();
				Commit();
			}
		};

		// کد رنگ
		StackPanel hexRow = new StackPanel();
		hexRow.Orientation = Orientation.Horizontal;

		preview = new Border();
		preview.Width = 38.0;
		preview.Height = 38.0;
		preview.CornerRadius = new CornerRadius(10.0);
		preview.BorderThickness = new Thickness(1.0);
		preview.SetResourceReference(Border.BorderBrushProperty, "BorderColor");
		preview.Margin = new Thickness(0.0, 0.0, 12.0, 0.0);
		hexRow.Children.Add(preview);

		TextBlock hexLabel = MakeText("کد رنگ", "TextMuted", 12.0, FontWeights.Normal, new Thickness(0.0, 0.0, 10.0, 0.0));
		hexLabel.VerticalAlignment = VerticalAlignment.Center;
		hexRow.Children.Add(hexLabel);

		hexBox = new TextBox();
		hexBox.Width = 120.0;
		hexBox.MaxLength = 7;
		hexBox.FlowDirection = FlowDirection.LeftToRight;
		hexBox.TextAlignment = TextAlignment.Center;
		hexBox.FontSize = 14.0;
		hexBox.Padding = new Thickness(8.0, 7.0, 8.0, 7.0);
		hexBox.BorderThickness = new Thickness(1.0);
		hexBox.VerticalContentAlignment = VerticalAlignment.Center;
		hexBox.SetResourceReference(TextBox.BackgroundProperty, "BgCard");
		hexBox.SetResourceReference(TextBox.BorderBrushProperty, "BorderColor");
		hexBox.SetResourceReference(TextBox.ForegroundProperty, "TextColor");
		hexBox.TextChanged += (object s, TextChangedEventArgs e) =>
		{
			if (suppressHex)
			{
				return;
			}
			Color typed;
			if (!ThemeManager.TryParseColor(hexBox.Text, out typed))
			{
				return;
			}
			SetColor(typed, true);
		};
		hexRow.Children.Add(hexBox);
		stack.Children.Add(hexRow);

		// دکمه‌ها
		StackPanel buttons = new StackPanel();
		buttons.Orientation = Orientation.Horizontal;
		buttons.HorizontalAlignment = HorizontalAlignment.Left;
		buttons.Margin = new Thickness(0.0, 22.0, 0.0, 0.0);

		Button closeButton = new Button();
		closeButton.Content = "بستن";
		closeButton.MinWidth = 90.0;
		closeButton.Margin = new Thickness(0.0, 0.0, 8.0, 0.0);
		closeButton.Style = (Style)Application.Current.FindResource("ModernButton");
		closeButton.IsCancel = true;
		closeButton.Click += (object s, RoutedEventArgs e) => { Close(); };
		buttons.Children.Add(closeButton);

		Button resetButton = new Button();
		resetButton.Content = "رنگ پیش‌فرض";
		resetButton.Padding = new Thickness(16.0, 10.0, 16.0, 10.0);
		resetButton.Click += (object s, RoutedEventArgs e) =>
		{
			Color def;
			if (ThemeManager.TryParseColor(ThemeManager.DefaultAccent, out def))
			{
				SetColor(def, false);
			}
		};
		buttons.Children.Add(resetButton);
		stack.Children.Add(buttons);

		root.Child = stack;
		Content = root;

		Closed += (object s, EventArgs e) => { Commit(); };

		UpdateColor(false, false);
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

	private static DropShadowEffect KnobShadow()
	{
		return new DropShadowEffect
		{
			Color = Colors.Black,
			BlurRadius = 4.0,
			ShadowDepth = 0.0,
			Opacity = 0.8
		};
	}

	// صفحه‌ی انتخاب: افقی = رنگ (Hue)، عمودی = پُررنگی (Saturation)
	private Grid BuildArea()
	{
		Grid g = new Grid();
		g.Width = PickerWidth;
		g.Height = PickerHeight;
		g.FlowDirection = FlowDirection.LeftToRight;
		g.Background = Brushes.Transparent;
		g.Cursor = Cursors.Cross;
		g.HorizontalAlignment = HorizontalAlignment.Center;
		g.Margin = new Thickness(0.0, 0.0, 0.0, 14.0);

		LinearGradientBrush hueBrush = new LinearGradientBrush();
		hueBrush.StartPoint = new Point(0.0, 0.0);
		hueBrush.EndPoint = new Point(1.0, 0.0);
		Color[] stops = new Color[]
		{
			Color.FromRgb(255, 0, 0),
			Color.FromRgb(255, 255, 0),
			Color.FromRgb(0, 255, 0),
			Color.FromRgb(0, 255, 255),
			Color.FromRgb(0, 0, 255),
			Color.FromRgb(255, 0, 255),
			Color.FromRgb(255, 0, 0)
		};
		for (int i = 0; i < stops.Length; i++)
		{
			hueBrush.GradientStops.Add(new GradientStop(stops[i], i / 6.0));
		}
		Rectangle hueRect = new Rectangle();
		hueRect.RadiusX = 8.0;
		hueRect.RadiusY = 8.0;
		hueRect.Fill = hueBrush;

		LinearGradientBrush satBrush = new LinearGradientBrush();
		satBrush.StartPoint = new Point(0.0, 0.0);
		satBrush.EndPoint = new Point(0.0, 1.0);
		satBrush.GradientStops.Add(new GradientStop(Color.FromArgb(0, 255, 255, 255), 0.0));
		satBrush.GradientStops.Add(new GradientStop(Color.FromArgb(255, 255, 255, 255), 1.0));
		Rectangle satRect = new Rectangle();
		satRect.RadiusX = 8.0;
		satRect.RadiusY = 8.0;
		satRect.Fill = satBrush;

		Canvas layer = new Canvas();
		layer.IsHitTestVisible = false;
		areaDot = new Ellipse();
		areaDot.Width = 18.0;
		areaDot.Height = 18.0;
		areaDot.Stroke = Brushes.White;
		areaDot.StrokeThickness = 3.0;
		areaDot.Fill = Brushes.Transparent;
		areaDot.Effect = KnobShadow();
		layer.Children.Add(areaDot);

		g.Children.Add(hueRect);
		g.Children.Add(satRect);
		g.Children.Add(layer);
		return g;
	}

	// نوار روشنی: از تیره تا رنگ انتخاب‌شده
	private Grid BuildBar()
	{
		Grid g = new Grid();
		g.Width = PickerWidth;
		g.Height = BarHeight;
		g.FlowDirection = FlowDirection.LeftToRight;
		g.Background = Brushes.Transparent;
		g.Cursor = Cursors.SizeWE;
		g.HorizontalAlignment = HorizontalAlignment.Center;
		g.Margin = new Thickness(0.0, 0.0, 0.0, 18.0);

		LinearGradientBrush brush = new LinearGradientBrush();
		brush.StartPoint = new Point(0.0, 0.0);
		brush.EndPoint = new Point(1.0, 0.0);
		brush.GradientStops.Add(new GradientStop(Colors.Black, 0.0));
		barEndStop = new GradientStop(Colors.White, 1.0);
		brush.GradientStops.Add(barEndStop);
		Rectangle track = new Rectangle();
		track.RadiusX = 9.0;
		track.RadiusY = 9.0;
		track.Fill = brush;

		Canvas layer = new Canvas();
		layer.IsHitTestVisible = false;
		barKnob = new Border();
		barKnob.Width = 8.0;
		barKnob.Height = BarHeight + 8.0;
		barKnob.CornerRadius = new CornerRadius(4.0);
		barKnob.BorderBrush = Brushes.White;
		barKnob.BorderThickness = new Thickness(2.5);
		barKnob.Background = Brushes.Transparent;
		barKnob.Effect = KnobShadow();
		layer.Children.Add(barKnob);

		g.Children.Add(track);
		g.Children.Add(layer);
		return g;
	}

	private void PickArea(MouseEventArgs e)
	{
		Point p = e.GetPosition(area);
		double x = Math.Max(0.0, Math.Min(PickerWidth, p.X));
		double y = Math.Max(0.0, Math.Min(PickerHeight, p.Y));
		hue = x / PickerWidth * 360.0;
		sat = 1.0 - y / PickerHeight;
		current = HsvToColor(hue, sat, val);
		UpdateColor(true, false);
	}

	private void PickBar(MouseEventArgs e)
	{
		Point p = e.GetPosition(bar);
		double x = Math.Max(0.0, Math.Min(PickerWidth, p.X));
		val = x / PickerWidth;
		current = HsvToColor(hue, sat, val);
		UpdateColor(true, false);
	}

	// رنگ دقیق از یکی از رنگ‌های آماده یا کد رنگ تایپ‌شده
	private void SetColor(Color c, bool fromHex)
	{
		current = c;
		ColorToHsv(c, out hue, out sat, out val);
		UpdateColor(true, fromHex);
		Commit();
	}

	private void UpdateColor(bool apply, bool fromHex)
	{
		currentHex = ThemeManager.ToHex(current);

		Canvas.SetLeft(areaDot, hue / 360.0 * PickerWidth - 9.0);
		Canvas.SetTop(areaDot, (1.0 - sat) * PickerHeight - 9.0);
		Canvas.SetLeft(barKnob, val * PickerWidth - 4.0);
		Canvas.SetTop(barKnob, -4.0);
		barEndStop.Color = HsvToColor(hue, sat, 1.0);
		preview.Background = new SolidColorBrush(current);

		if (!fromHex)
		{
			suppressHex = true;
			hexBox.Text = currentHex;
			suppressHex = false;
		}

		for (int i = 0; i < swatches.Length; i++)
		{
			bool selected = string.Equals(presetHex[i], currentHex, StringComparison.OrdinalIgnoreCase);
			if (selected)
			{
				swatches[i].SetResourceReference(Border.BorderBrushProperty, "TextColor");
			}
			else
			{
				swatches[i].BorderBrush = Brushes.Transparent;
			}
			swatchChecks[i].Visibility = selected ? Visibility.Visible : Visibility.Collapsed;
		}

		if (apply)
		{
			changed = true;
			ThemeManager.SetAccent(currentHex, false);
			if (onChanged != null)
			{
				onChanged();
			}
		}
	}

	private void Commit()
	{
		if (!changed)
		{
			return;
		}
		ThemeManager.SetAccent(currentHex, true);
	}

	private static byte ToByte(double v)
	{
		return (byte)Math.Round(Math.Max(0.0, Math.Min(1.0, v)) * 255.0);
	}

	private static Color HsvToColor(double h, double s, double v)
	{
		h = h % 360.0;
		if (h < 0.0)
		{
			h += 360.0;
		}
		double c = v * s;
		double hp = h / 60.0;
		double x = c * (1.0 - Math.Abs(hp % 2.0 - 1.0));
		double m = v - c;
		double r = 0.0;
		double g = 0.0;
		double b = 0.0;
		if (hp < 1.0) { r = c; g = x; }
		else if (hp < 2.0) { r = x; g = c; }
		else if (hp < 3.0) { g = c; b = x; }
		else if (hp < 4.0) { g = x; b = c; }
		else if (hp < 5.0) { r = x; b = c; }
		else { r = c; b = x; }
		return Color.FromRgb(ToByte(r + m), ToByte(g + m), ToByte(b + m));
	}

	private static void ColorToHsv(Color c, out double h, out double s, out double v)
	{
		double r = c.R / 255.0;
		double g = c.G / 255.0;
		double b = c.B / 255.0;
		double max = Math.Max(r, Math.Max(g, b));
		double min = Math.Min(r, Math.Min(g, b));
		double d = max - min;
		v = max;
		s = (max <= 0.0) ? 0.0 : d / max;
		if (d < 0.000001)
		{
			h = 0.0;
			return;
		}
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
}

}
