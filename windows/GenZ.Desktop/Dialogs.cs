using System;
using System.Windows;
using System.Windows.Controls;
using System.Windows.Input;
using System.Windows.Media;

namespace GenZ
{
    /// <summary>Small dialogs in the current theme: ask for a name, confirm, or show a message.</summary>
    public static class Dialogs
    {
        static Window Frame(Window owner, string title, out StackPanel body, out StackPanel buttons)
        {
            var w = new Window
            {
                Title = title, SizeToContent = SizeToContent.Height, Width = 440, ResizeMode = ResizeMode.NoResize,
                WindowStyle = WindowStyle.None, AllowsTransparency = true, Background = Brushes.Transparent,
                ShowInTaskbar = false, Owner = owner, WindowStartupLocation = owner != null ? WindowStartupLocation.CenterOwner : WindowStartupLocation.CenterScreen
            };
            var card = new Border { CornerRadius = new CornerRadius(18), Padding = new Thickness(24, 20, 24, 18), Margin = new Thickness(14), BorderThickness = new Thickness(1) };
            card.SetResourceReference(Border.BackgroundProperty, "SurfaceBrush");
            card.SetResourceReference(Border.BorderBrushProperty, "DividerBrush");
            card.Effect = new System.Windows.Media.Effects.DropShadowEffect { BlurRadius = 24, ShadowDepth = 4, Opacity = 0.45 };
            var root = new StackPanel();
            var h = new TextBlock { Text = title, FontSize = 18, FontWeight = FontWeights.Bold, Margin = new Thickness(0, 0, 0, 12) };
            h.SetResourceReference(TextBlock.ForegroundProperty, "TextBrush");
            h.SetResourceReference(TextBlock.FontFamilyProperty, "DisplayFont");
            root.Children.Add(h);
            body = new StackPanel(); root.Children.Add(body);
            buttons = new StackPanel { Orientation = Orientation.Horizontal, HorizontalAlignment = HorizontalAlignment.Right, Margin = new Thickness(0, 18, 0, 0) };
            root.Children.Add(buttons);
            card.Child = root;
            card.MouseLeftButtonDown += (s, e) => { if (e.ButtonState == MouseButtonState.Pressed) try { w.DragMove(); } catch { } };
            w.Content = card;
            w.SetResourceReference(Window.FontFamilyProperty, "BodyFont");
            return w;
        }

        static Button Btn(string text, bool primary)
        {
            var b = new Button { Content = text, Margin = new Thickness(10, 0, 0, 0), IsDefault = primary, IsCancel = !primary };
            b.SetResourceReference(FrameworkElement.StyleProperty, primary ? "PillButton" : "GhostPill");
            return b;
        }

        public static string Prompt(Window owner, string title, string label, string initial, string ok = "Save")
        {
            var w = Frame(owner, title, out var body, out var buttons);
            var l = new TextBlock { Text = label, FontSize = 13, Margin = new Thickness(0, 0, 0, 8) };
            l.SetResourceReference(TextBlock.ForegroundProperty, "Text2Brush");
            var box = new TextBox { Text = initial ?? "", FontSize = 15, Padding = new Thickness(10, 8, 10, 8), BorderThickness = new Thickness(1) };
            box.SetResourceReference(Control.BackgroundProperty, "BgBrush");
            box.SetResourceReference(Control.ForegroundProperty, "TextBrush");
            box.SetResourceReference(Control.BorderBrushProperty, "DividerBrush");
            box.SetResourceReference(TextBox.CaretBrushProperty, "AccentBrush");
            body.Children.Add(l); body.Children.Add(box);
            string result = null;
            var cancel = Btn("Cancel", false);
            var save = Btn(ok, true);
            save.Click += (s, e) => { if (!string.IsNullOrWhiteSpace(box.Text)) { result = box.Text.Trim(); w.DialogResult = true; } };
            buttons.Children.Add(cancel); buttons.Children.Add(save);
            w.Loaded += (s, e) => { box.Focus(); box.SelectAll(); };
            w.ShowDialog();
            return result;
        }

        public static bool Confirm(Window owner, string title, string text, string ok = "OK")
        {
            var w = Frame(owner, title, out var body, out var buttons);
            var t = new TextBlock { Text = text, FontSize = 13.5, TextWrapping = TextWrapping.Wrap, LineHeight = 20 };
            t.SetResourceReference(TextBlock.ForegroundProperty, "Text2Brush");
            body.Children.Add(t);
            var cancel = Btn("Cancel", false);
            var yes = Btn(ok, true);
            yes.Click += (s, e) => w.DialogResult = true;
            buttons.Children.Add(cancel); buttons.Children.Add(yes);
            return w.ShowDialog() == true;
        }

        public static void Message(Window owner, string title, string text)
        {
            var w = Frame(owner, title, out var body, out var buttons);
            var t = new TextBlock { Text = text, FontSize = 13.5, TextWrapping = TextWrapping.Wrap, LineHeight = 20 };
            t.SetResourceReference(TextBlock.ForegroundProperty, "Text2Brush");
            body.Children.Add(t);
            var ok = Btn("OK", true); ok.IsCancel = true;
            ok.Click += (s, e) => w.DialogResult = true;
            buttons.Children.Add(ok);
            w.ShowDialog();
        }
    }
}
