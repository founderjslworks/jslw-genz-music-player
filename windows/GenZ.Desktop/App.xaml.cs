using System;
using System.IO;
using System.IO.Pipes;
using System.Linq;
using System.Threading;
using System.Threading.Tasks;
using System.Windows;
using System.Windows.Threading;
using GenZ.Core;

namespace GenZ
{
    public partial class App : Application
    {
        const string PipeName = "GENZ-Music-JSLW-pipe";
        static Mutex single;
        MainWindow main;

        protected override void OnStartup(StartupEventArgs e)
        {
            // one GEN-Z at a time: a second launch (e.g. "Open with") hands its file to the running window
            single = new Mutex(true, "GENZ-Music-JSLW-single", out bool first);
            if (!first)
            {
                try
                {
                    using (var c = new NamedPipeClientStream(".", PipeName, PipeDirection.Out))
                    {
                        c.Connect(2000);
                        using (var w = new StreamWriter(c)) { w.Write(string.Join("\n", e.Args)); }
                    }
                }
                catch { }
                Shutdown();
                return;
            }

            DispatcherUnhandledException += OnError;
            Settings.Load();
            ThemeManager.Load();
            var theme = ThemeManager.All.Find(t => t.Id == Settings.Current.ThemeId) ?? ThemeManager.All.Find(t => t.Id == "vivid_genz");
            if (theme != null) ThemeManager.Apply(theme);
            base.OnStartup(e);
            main = new MainWindow();
            main.Show();
            if (e.Args.Length > 0) main.OpenFiles(e.Args);
            Listen();
        }

        void Listen()
        {
            Task.Run(() =>
            {
                while (true)
                {
                    try
                    {
                        using (var server = new NamedPipeServerStream(PipeName, PipeDirection.In))
                        {
                            server.WaitForConnection();
                            string text;
                            using (var r = new StreamReader(server)) text = r.ReadToEnd();
                            var files = text.Split(new[] { '\n' }, StringSplitOptions.RemoveEmptyEntries);
                            Dispatcher.BeginInvoke(new Action(() => main?.BringBack(files)));
                        }
                    }
                    catch { Thread.Sleep(500); }
                }
            });
        }

        protected override void OnExit(ExitEventArgs e)
        {
            if (main != null) { Settings.Save(); Player.Stop(); }
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
