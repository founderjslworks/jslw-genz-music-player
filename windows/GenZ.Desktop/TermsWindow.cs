using System;
using System.IO;
using System.Windows;
using System.Windows.Controls;
using System.Windows.Media;
using Microsoft.Win32;

namespace GenZ
{
    /// <summary>
    /// Shows the End-User Licence Agreement (and the open-source notices). On first run of a copy that was not
    /// installed with Setup (e.g. the portable zip) the user must accept the terms before the player opens.
    /// </summary>
    public class TermsWindow : Window
    {
        public static string LegalDir => Path.Combine(AppDomain.CurrentDomain.BaseDirectory, "legal");

        public static string ReadEula() => Read("EULA.txt", "GEN-Z Music End-User Licence Agreement\n\n© 2026 JSL WORKS PVT LTD. All rights reserved.\nhttps://www.jslworks.in/");
        public static string ReadNotices()
        {
            var text = Read("THIRD-PARTY-NOTICES.txt", "");
            try
            {
                var dir = Path.Combine(AppDomain.CurrentDomain.BaseDirectory, "licenses");
                foreach (var f in Directory.GetFiles(dir, "*.txt"))
                    text += "\n\n──────────────────────────────────────────\n" + Path.GetFileNameWithoutExtension(f).ToUpperInvariant() + "\n\n" + File.ReadAllText(f);
            }
            catch { }
            return text;
        }
        static string Read(string name, string fallback)
        {
            try { return File.ReadAllText(Path.Combine(LegalDir, name)); } catch { return fallback; }
        }

        /// <summary>True when the terms were accepted here or in the installer.</summary>
        public static bool Accepted()
        {
            if (Core.Settings.Current.TermsAccepted) return true;
            try
            {
                using (var k = Registry.CurrentUser.OpenSubKey(@"Software\JSL WORKS\GEN-Z Music"))
                    if (k?.GetValue("TermsAccepted") is int v && v == 1) return true;
            }
            catch { }
            return false;
        }

        readonly bool needAccept;

        public TermsWindow(string title, string text, bool needAccept)
        {
            this.needAccept = needAccept;
            Title = title;
            Width = 760; Height = 620; MinWidth = 480; MinHeight = 360;
            WindowStartupLocation = WindowStartupLocation.CenterScreen;
            SetResourceReference(BackgroundProperty, "BgBrush");
            SetResourceReference(FontFamilyProperty, "BodyFont");
            try { Icon = new System.Windows.Media.Imaging.BitmapImage(new Uri("pack://application:,,,/Assets/genz.png")); } catch { }

            var root = new Grid { Margin = new Thickness(22) };
            root.RowDefinitions.Add(new RowDefinition { Height = GridLength.Auto });
            root.RowDefinitions.Add(new RowDefinition());
            root.RowDefinitions.Add(new RowDefinition { Height = GridLength.Auto });

            var head = new StackPanel { Margin = new Thickness(0, 0, 0, 14) };
            var h = new TextBlock { Text = title, FontSize = 22, FontWeight = FontWeights.Bold };
            h.SetResourceReference(TextBlock.ForegroundProperty, "TextBrush");
            var sub = new TextBlock
            {
                Text = needAccept ? "Please read and accept the terms to start using GEN-Z Music." : "GEN-Z Music · © 2026 JSL WORKS PVT LTD · www.jslworks.in",
                FontSize = 13, Margin = new Thickness(0, 4, 0, 0)
            };
            sub.SetResourceReference(TextBlock.ForegroundProperty, "Text2Brush");
            head.Children.Add(h); head.Children.Add(sub);
            root.Children.Add(head);

            var card = new Border { CornerRadius = new CornerRadius(14), Padding = new Thickness(4) };
            card.SetResourceReference(Border.BackgroundProperty, "SurfaceBrush");
            var box = new TextBox
            {
                Text = text, IsReadOnly = true, TextWrapping = TextWrapping.Wrap, BorderThickness = new Thickness(0),
                Background = Brushes.Transparent, VerticalScrollBarVisibility = ScrollBarVisibility.Auto,
                FontFamily = new FontFamily("Consolas, Segoe UI"), FontSize = 12.5, Padding = new Thickness(14)
            };
            box.SetResourceReference(TextBox.ForegroundProperty, "TextBrush");
            card.Child = box;
            Grid.SetRow(card, 1);
            root.Children.Add(card);

            var bar = new StackPanel { Orientation = Orientation.Horizontal, HorizontalAlignment = HorizontalAlignment.Right, Margin = new Thickness(0, 16, 0, 0) };
            if (needAccept)
            {
                var decline = new Button { Content = "Decline", Margin = new Thickness(0, 0, 10, 0) };
                decline.SetResourceReference(StyleProperty, "GhostPill");
                decline.Click += (s, e) => { DialogResult = false; };
                var accept = new Button { Content = "I accept the terms", IsDefault = true };
                accept.SetResourceReference(StyleProperty, "PillButton");
                accept.Click += (s, e) => { DialogResult = true; };
                bar.Children.Add(decline); bar.Children.Add(accept);
            }
            else
            {
                var close = new Button { Content = "Close", IsDefault = true, IsCancel = true };
                close.SetResourceReference(StyleProperty, "PillButton");
                close.Click += (s, e) => Close();
                bar.Children.Add(close);
            }
            Grid.SetRow(bar, 2);
            root.Children.Add(bar);
            Content = root;
        }

        /// <summary>Shows the agreement on first run; returns false if the user declines.</summary>
        public static bool EnsureAccepted()
        {
            if (Accepted()) return true;
            var w = new TermsWindow("Terms of use", ReadEula(), true);
            if (w.ShowDialog() == true)
            {
                Core.Settings.Current.TermsAccepted = true;
                Core.Settings.Save();
                return true;
            }
            return false;
        }
    }
}
