using System;
using System.Collections.Generic;
using System.Windows;
using System.Windows.Controls;
using System.Windows.Media;
using System.Windows.Media.Animation;
using System.Windows.Shapes;
using System.Windows.Threading;
using EpubReader.Services;

namespace EpubReader
{

// لایه‌ی ذرات متحرک تم شخصیت: جرقه‌های سوبارو، گلبرگ رام، درخشش رم، پروانه‌ی بیاتریس، برف امیلیا.
// نامرئی برای ماوس، سبک (حدود ۲۰ تا شکل کوچک با ۳۰ فریم) و با ThemeManager.Applied خودش به‌روز می‌شه.
public sealed class ThemeParticleLayer : Canvas
{
	private sealed class Particle
	{
		public FrameworkElement Element;
		public TranslateTransform Fall;
		public TranslateTransform Sway;
		public RotateTransform Rotate;
		public ScaleTransform Scale;
	}

	private static readonly Random rng = new Random();

	private readonly List<Particle> particles = new List<Particle>();
	private string builtKey = "";
	private DispatcherTimer resizeTimer;
	private Window host;

	// کاربر می‌تونه از پنجره‌ی تم‌ها خاموشش کنه
	public static bool Enabled
	{
		get { return AppSettings.Get("ThemeParticles", "0") == "1"; }
		set { AppSettings.Set("ThemeParticles", value ? "1" : "0"); }
	}

	public ThemeParticleLayer()
	{
		IsHitTestVisible = false;
		ClipToBounds = true;
		Loaded += OnLoaded;
		Unloaded += OnUnloaded;
		SizeChanged += OnSizeChanged;
	}

	private void OnLoaded(object sender, RoutedEventArgs e)
	{
		ThemeManager.Applied -= OnThemeApplied;
		ThemeManager.Applied += OnThemeApplied;
		DetachHost();
		host = Window.GetWindow(this);
		if (host != null)
		{
			// وقتی پنجره فعال نیست یا کوچک شده، انیمیشن‌ها کاملاً متوقف می‌شن
			host.Activated += OnHostStateChanged;
			host.Deactivated += OnHostStateChanged;
			host.StateChanged += OnHostStateChanged;
		}
		Refresh(true);
	}

	private void DetachHost()
	{
		if (host != null)
		{
			host.Activated -= OnHostStateChanged;
			host.Deactivated -= OnHostStateChanged;
			host.StateChanged -= OnHostStateChanged;
			host = null;
		}
	}

	private void OnHostStateChanged(object sender, EventArgs e)
	{
		Refresh(false);
	}

	private void OnUnloaded(object sender, RoutedEventArgs e)
	{
		DetachHost();
		ThemeManager.Applied -= OnThemeApplied;
		Stop();
		builtKey = "";
	}

	private void OnThemeApplied(object sender, EventArgs e)
	{
		Refresh(false);
	}

	private void OnSizeChanged(object sender, SizeChangedEventArgs e)
	{
		if (resizeTimer == null)
		{
			resizeTimer = new DispatcherTimer();
			resizeTimer.Interval = TimeSpan.FromMilliseconds(400);
			resizeTimer.Tick += (object s, EventArgs a) =>
			{
				resizeTimer.Stop();
				Refresh(true);
			};
		}
		resizeTimer.Stop();
		resizeTimer.Start();
	}

	public void Refresh(bool force)
	{
		CharacterTheme ch = ThemeManager.AppliedCharacter;
		bool hostActive = (host == null) || (host.IsActive && host.WindowState != WindowState.Minimized);
		bool on = Enabled && ch != null && IsLoaded && hostActive && ActualWidth > 80.0 && ActualHeight > 80.0;
		string key = on ? (ch.Id + (ThemeManager.AppliedLight ? ".l" : ".d")) : "";
		if (!force && key == builtKey)
		{
			return;
		}
		Stop();
		builtKey = key;
		if (on)
		{
			Build(ch, ThemeManager.AppliedLight);
		}
	}

	private void Stop()
	{
		foreach (Particle p in particles)
		{
			if (p.Fall != null) { p.Fall.BeginAnimation(TranslateTransform.YProperty, null); }
			if (p.Sway != null) { p.Sway.BeginAnimation(TranslateTransform.XProperty, null); }
			if (p.Rotate != null) { p.Rotate.BeginAnimation(RotateTransform.AngleProperty, null); }
			if (p.Scale != null)
			{
				p.Scale.BeginAnimation(ScaleTransform.ScaleXProperty, null);
				p.Scale.BeginAnimation(ScaleTransform.ScaleYProperty, null);
			}
			p.Element.BeginAnimation(UIElement.OpacityProperty, null);
		}
		particles.Clear();
		Children.Clear();
	}

	private static SolidColorBrush MakeBrush(string hex)
	{
		Color c;
		if (!ThemeManager.TryParseColor(hex, out c))
		{
			c = Colors.White;
		}
		SolidColorBrush b = new SolidColorBrush(c);
		b.Freeze();
		return b;
	}

	private static double Rand(double min, double max)
	{
		return min + rng.NextDouble() * (max - min);
	}

	private static DoubleAnimation Anim(double from, double to, double seconds)
	{
		DoubleAnimation a = new DoubleAnimation(from, to, TimeSpan.FromSeconds(seconds));
		Timeline.SetDesiredFrameRate(a, 30);
		return a;
	}

	private void Build(CharacterTheme ch, bool light)
	{
		ThemePalette pal = ch.Palette(light);
		SolidColorBrush b1 = MakeBrush(pal.Accent);
		SolidColorBrush b2 = MakeBrush(pal.Aura2);
		SolidColorBrush b3 = MakeBrush(light ? pal.Accent : pal.Text);

		int count = 14;
		double sizeMin = 6.0;
		double sizeMax = 12.0;
		double durMin = 10.0;
		double durMax = 20.0;
		double peak = 0.5;
		bool rising = false;
		double swayAmp = 24.0;
		string kind = ch.Particle;

		switch (kind)
		{
			case "embers":
				count = 12; sizeMin = 3.0; sizeMax = 7.0; durMin = 8.0; durMax = 16.0; peak = 0.7; rising = true; swayAmp = 18.0;
				break;
			case "petals":
				count = 10; sizeMin = 11.0; sizeMax = 19.0; durMin = 13.0; durMax = 24.0; peak = 0.75; swayAmp = 46.0;
				break;
			case "sparkles":
				count = 10; sizeMin = 7.0; sizeMax = 15.0; durMin = 11.0; durMax = 22.0; peak = 0.8; rising = true; swayAmp = 14.0;
				break;
			case "butterflies":
				count = 5; sizeMin = 17.0; sizeMax = 27.0; durMin = 16.0; durMax = 28.0; peak = 0.75; rising = true; swayAmp = 60.0;
				break;
			case "leaves":
				count = 16; sizeMin = 10.0; sizeMax = 20.0; durMin = 10.0; durMax = 22.0; peak = 0.9; swayAmp = 48.0;
				break;
			case "snow":
				count = 18; sizeMin = 5.0; sizeMax = 11.0; durMin = 12.0; durMax = 24.0; peak = 0.85; swayAmp = 28.0;
				break;
		}
		if (light)
		{
			peak = peak * 0.8;
		}

		double w = ActualWidth;
		double h = ActualHeight;

		for (int i = 0; i < count; i++)
		{
			double size = Rand(sizeMin, sizeMax);
			double dur = Rand(durMin, durMax);
			double delay = -Rand(0.0, dur);

			FrameworkElement el;
			SolidColorBrush fill = (i % 3 == 0) ? b2 : ((i % 3 == 1) ? b1 : b3);
			if (kind == "embers")
			{
				Ellipse dot = new Ellipse();
				dot.Width = size;
				dot.Height = size;
				dot.Fill = (i % 4 == 0) ? b2 : b1;
				el = dot;
			}
			else
			{
				string glyph = "spark";
				Geometry data;
				if (kind == "petals") { glyph = "petal"; }
				else if (kind == "snow") { glyph = "snow"; }
				else if (kind == "leaves") { glyph = "leaf"; }
				if (kind == "butterflies")
				{
					data = ch.Emblem;
					fill = (i % 2 == 0) ? b1 : b2;
				}
				else
				{
					data = CharacterThemes.Glyph(glyph);
					if (kind == "petals" || kind == "leaves") { fill = (i % 2 == 0) ? b1 : b2; }
				}
				Path shape = new Path();
				shape.Data = data;
				shape.Stretch = Stretch.Uniform;
				shape.Width = size;
				shape.Height = size;
				shape.Fill = fill;
				el = shape;
			}

			Particle p = new Particle();
			p.Element = el;
			p.Fall = new TranslateTransform();
			p.Sway = new TranslateTransform();
			p.Rotate = new RotateTransform();
			p.Scale = new ScaleTransform(1.0, 1.0);

			TransformGroup group = new TransformGroup();
			group.Children.Add(p.Scale);
			group.Children.Add(p.Rotate);
			group.Children.Add(p.Sway);
			group.Children.Add(p.Fall);
			el.RenderTransformOrigin = new Point(0.5, 0.5);
			el.RenderTransform = group;
			el.Opacity = 0.0;
			el.CacheMode = new BitmapCache();
			el.IsHitTestVisible = false;

			SetLeft(el, Rand(0.0, w));
			SetTop(el, 0.0);
			Children.Add(el);
			particles.Add(p);

			// بالا/پایین رفتن
			DoubleAnimation fall = rising ? Anim(h + 30.0, -40.0, dur) : Anim(-40.0, h + 30.0, dur);
			fall.RepeatBehavior = RepeatBehavior.Forever;
			fall.BeginTime = TimeSpan.FromSeconds(delay);
			p.Fall.BeginAnimation(TranslateTransform.YProperty, fall);

			// پیدا و محو شدن
			DoubleAnimationUsingKeyFrames fade = new DoubleAnimationUsingKeyFrames();
			fade.KeyFrames.Add(new LinearDoubleKeyFrame(0.0, KeyTime.FromPercent(0.0)));
			fade.KeyFrames.Add(new LinearDoubleKeyFrame(peak, KeyTime.FromPercent(0.12)));
			fade.KeyFrames.Add(new LinearDoubleKeyFrame(peak, KeyTime.FromPercent(0.88)));
			fade.KeyFrames.Add(new LinearDoubleKeyFrame(0.0, KeyTime.FromPercent(1.0)));
			fade.Duration = TimeSpan.FromSeconds(dur);
			fade.RepeatBehavior = RepeatBehavior.Forever;
			fade.BeginTime = TimeSpan.FromSeconds(delay);
			Timeline.SetDesiredFrameRate(fade, 30);
			el.BeginAnimation(UIElement.OpacityProperty, fade);

			// تاب خوردن به چپ و راست
			double amp = Rand(swayAmp * 0.4, swayAmp);
			DoubleAnimation sway = Anim(-amp, amp, Rand(3.0, 6.5));
			sway.AutoReverse = true;
			sway.RepeatBehavior = RepeatBehavior.Forever;
			sway.BeginTime = TimeSpan.FromSeconds(-Rand(0.0, 5.0));
			sway.EasingFunction = new SineEase { EasingMode = EasingMode.EaseInOut };
			p.Sway.BeginAnimation(TranslateTransform.XProperty, sway);

			if (kind == "petals" || kind == "snow" || kind == "leaves")
			{
				// چرخیدن
				double dir = (rng.Next(2) == 0) ? 360.0 : -360.0;
				DoubleAnimation spin = Anim(0.0, dir, Rand(7.0, 15.0));
				spin.RepeatBehavior = RepeatBehavior.Forever;
				p.Rotate.BeginAnimation(RotateTransform.AngleProperty, spin);
			}
			else if (kind == "butterflies")
			{
				// بال‌زدن
				DoubleAnimation flap = Anim(1.0, 0.3, Rand(0.28, 0.45));
				flap.AutoReverse = true;
				flap.RepeatBehavior = RepeatBehavior.Forever;
				p.Scale.BeginAnimation(ScaleTransform.ScaleXProperty, flap);
			}
			else if (kind == "sparkles")
			{
				// چشمک‌زدن
				double twinkleSeconds = Rand(1.0, 2.2);
				DoubleAnimation twX = Anim(0.45, 1.1, twinkleSeconds);
				twX.AutoReverse = true;
				twX.RepeatBehavior = RepeatBehavior.Forever;
				p.Scale.BeginAnimation(ScaleTransform.ScaleXProperty, twX);
				DoubleAnimation twY = Anim(0.45, 1.1, twinkleSeconds);
				twY.AutoReverse = true;
				twY.RepeatBehavior = RepeatBehavior.Forever;
				p.Scale.BeginAnimation(ScaleTransform.ScaleYProperty, twY);
			}
		}
	}
}
}
