using System;
using System.Windows;
using System.Windows.Threading;
using GenZ.Core;

namespace GenZ
{
    public partial class App : Application
    {
        protected override void OnStartup(StartupEventArgs e)
        {
            DispatcherUnhandledException += OnError;
            Settings.Load();
            ThemeManager.Load();
            var theme = ThemeManager.All.Find(t => t.Id == Settings.Current.ThemeId) ?? ThemeManager.All.Find(t => t.Id == "vivid_genz");
            if (theme != null) ThemeManager.Apply(theme);
            base.OnStartup(e);
            new MainWindow().Show();
        }

        protected override void OnExit(ExitEventArgs e)
        {
            Settings.Save();
            Player.Stop();
            base.OnExit(e);
        }

        void OnError(object sender, DispatcherUnhandledExceptionEventArgs e)
        {
            // Never crash on a bad file or a missing device: show what happened and keep going.
            e.Handled = true;
            try { MessageBox.Show(e.Exception.Message, "GEN-Z Music", MessageBoxButton.OK, MessageBoxImage.Warning); } catch { }
        }
    }
}
