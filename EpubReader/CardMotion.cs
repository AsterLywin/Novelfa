using System;
using System.Windows;
using System.Windows.Controls.Primitives;
using System.Windows.Input;
using System.Windows.Media;
using System.Windows.Media.Animation;
using System.Windows.Media.Media3D;

namespace EpubReader
{
    /// <summary>
    /// حرکت نرم کارت‌ها: بالا آمدن موقع hover و کمی فشرده‌شدن موقع کلیک.
    /// با یک Setter توی استایل فعال می‌شه (local:CardMotion.Enabled="True").
    /// </summary>
    public static class CardMotion
    {
        private const double LiftY = -4.0;

        public static readonly DependencyProperty EnabledProperty =
            DependencyProperty.RegisterAttached("Enabled", typeof(bool), typeof(CardMotion), new PropertyMetadata(false, OnEnabledChanged));

        public static bool GetEnabled(DependencyObject obj)
        {
            return (bool)obj.GetValue(EnabledProperty);
        }

        public static void SetEnabled(DependencyObject obj, bool value)
        {
            obj.SetValue(EnabledProperty, value);
        }

        private static void OnEnabledChanged(DependencyObject d, DependencyPropertyChangedEventArgs e)
        {
            FrameworkElement el = d as FrameworkElement;
            if (el == null)
            {
                return;
            }

            el.MouseEnter -= Card_MouseEnter;
            el.MouseLeave -= Card_MouseLeave;
            el.PreviewMouseLeftButtonDown -= Card_PreviewMouseLeftButtonDown;
            el.PreviewMouseLeftButtonUp -= Card_PreviewMouseLeftButtonUp;

            if (!(bool)e.NewValue)
            {
                return;
            }

            TransformGroup group = new TransformGroup();
            group.Children.Add(new ScaleTransform(1.0, 1.0));
            group.Children.Add(new TranslateTransform(0.0, 0.0));
            el.RenderTransformOrigin = new Point(0.5, 0.5);
            el.RenderTransform = group;

            el.MouseEnter += Card_MouseEnter;
            el.MouseLeave += Card_MouseLeave;
            el.PreviewMouseLeftButtonDown += Card_PreviewMouseLeftButtonDown;
            el.PreviewMouseLeftButtonUp += Card_PreviewMouseLeftButtonUp;
        }

        private static void Card_MouseEnter(object sender, MouseEventArgs e)
        {
            Animate(sender as FrameworkElement, LiftY, 1.0, 180);
        }

        private static void Card_MouseLeave(object sender, MouseEventArgs e)
        {
            Animate(sender as FrameworkElement, 0.0, 1.0, 220);
        }

        private static void Card_PreviewMouseLeftButtonDown(object sender, MouseButtonEventArgs e)
        {
            FrameworkElement el = sender as FrameworkElement;
            if (el == null || IsFromButton(e.OriginalSource as DependencyObject, el))
            {
                return;
            }
            Animate(el, -1.0, 0.97, 90);
        }

        private static void Card_PreviewMouseLeftButtonUp(object sender, MouseButtonEventArgs e)
        {
            FrameworkElement el = sender as FrameworkElement;
            if (el == null)
            {
                return;
            }
            Animate(el, el.IsMouseOver ? LiftY : 0.0, 1.0, 140);
        }

        // کلیک روی دکمه‌ی «⋯» نباید کل کارت رو فشرده کنه
        private static bool IsFromButton(DependencyObject source, FrameworkElement root)
        {
            DependencyObject current = source;
            while (current != null && current != root)
            {
                if (current is ButtonBase)
                {
                    return true;
                }
                if (current is Visual || current is Visual3D)
                {
                    current = VisualTreeHelper.GetParent(current);
                }
                else
                {
                    current = LogicalTreeHelper.GetParent(current);
                }
            }
            return false;
        }

        private static void Animate(FrameworkElement el, double y, double scale, int milliseconds)
        {
            if (el == null)
            {
                return;
            }
            TransformGroup group = el.RenderTransform as TransformGroup;
            if (group == null || group.Children.Count < 2)
            {
                return;
            }
            ScaleTransform scaleTransform = group.Children[0] as ScaleTransform;
            TranslateTransform translate = group.Children[1] as TranslateTransform;
            if (scaleTransform == null || translate == null || group.IsFrozen)
            {
                return;
            }

            Duration duration = new Duration(TimeSpan.FromMilliseconds(milliseconds));
            QuadraticEase ease = new QuadraticEase { EasingMode = EasingMode.EaseOut };

            translate.BeginAnimation(TranslateTransform.YProperty, new DoubleAnimation(y, duration) { EasingFunction = ease });
            scaleTransform.BeginAnimation(ScaleTransform.ScaleXProperty, new DoubleAnimation(scale, duration) { EasingFunction = ease });
            scaleTransform.BeginAnimation(ScaleTransform.ScaleYProperty, new DoubleAnimation(scale, duration) { EasingFunction = ease });
        }
    }
}
