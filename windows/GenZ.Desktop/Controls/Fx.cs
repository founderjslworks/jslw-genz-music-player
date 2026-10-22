using System;
using System.Windows;
using System.Windows.Media;
using System.Windows.Media.Animation;
using GenZ.Core;

namespace GenZ.Controls
{
    /// <summary>Click animations for the transport buttons: squish, spring back, and a sideways kick for next / previous.</summary>
    public static class Fx
    {
        public static void Pop(FrameworkElement b, int dir = 0)
        {
            if (b == null || Settings.Current.ReduceMotion) return;
            var st = new ScaleTransform(1, 1);
            var tt = new TranslateTransform();
            var tg = new TransformGroup();
            tg.Children.Add(st); tg.Children.Add(tt);
            b.RenderTransformOrigin = new Point(0.5, 0.5);
            b.RenderTransform = tg;

            var pop = new DoubleAnimationUsingKeyFrames { Duration = Settings.Ms(560) };
            pop.KeyFrames.Add(new EasingDoubleKeyFrame(0.74, KeyTime.FromTimeSpan(Settings.Ms(90)), new QuadraticEase { EasingMode = EasingMode.EaseOut }));
            pop.KeyFrames.Add(new EasingDoubleKeyFrame(1.16, KeyTime.FromTimeSpan(Settings.Ms(230)), new QuadraticEase { EasingMode = EasingMode.EaseOut }));
            pop.KeyFrames.Add(new EasingDoubleKeyFrame(1.0, KeyTime.FromTimeSpan(Settings.Ms(560)), new ElasticEase { Oscillations = 1, Springiness = 4, EasingMode = EasingMode.EaseOut }));
            st.BeginAnimation(ScaleTransform.ScaleXProperty, pop);
            st.BeginAnimation(ScaleTransform.ScaleYProperty, pop);

            if (dir != 0)
            {
                var kick = new DoubleAnimationUsingKeyFrames { Duration = Settings.Ms(560) };
                kick.KeyFrames.Add(new EasingDoubleKeyFrame(dir * 12, KeyTime.FromTimeSpan(Settings.Ms(120)), new QuadraticEase { EasingMode = EasingMode.EaseOut }));
                kick.KeyFrames.Add(new EasingDoubleKeyFrame(0, KeyTime.FromTimeSpan(Settings.Ms(560)), new ElasticEase { Oscillations = 1, Springiness = 5, EasingMode = EasingMode.EaseOut }));
                tt.BeginAnimation(TranslateTransform.XProperty, kick);
            }
        }
    }
}
