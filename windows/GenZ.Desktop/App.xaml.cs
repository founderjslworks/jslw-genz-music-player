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

        /// <summary>Writes unexpected errors to %APPDATA%\GEN-Z Music\crash.log so problems can be found and fixed.</summary>
        public static void Log(Exception ex, string where)
        {
            try
            {
                var dir = System.IO.Path.Combine(Environment.GetFolderPath(Environment.SpecialFolder.ApplicationData), "GEN-Z Music");
                Directory.CreateDirectory(dir);
                File.AppendAllText(System.IO.Path.Combine(dir, "crash.log"), DateTime.Now.ToString("u") + " [" + where + "] " + ex + Environment.NewLine + Environment.NewLine);
            }
            catch { }
        }

        public App()
        {
            AppDomain.CurrentDomain.UnhandledException += (s, a) =>
            {
                if (a.ExceptionObject is Exception ex)
                {
                    Log(ex, "fatal");
                    try { MessageBox.Show("GEN-Z Music hit a problem and has to close:\n\n" + ex.GetBaseException().Message + "\n\nDetails were saved to crash.log in %APPDATA%\\GEN-Z Music.", "GEN-Z Music", MessageBoxButton.OK, MessageBoxImage.Error); } catch { }
                }
            };
            TaskScheduler.UnobservedTaskException += (s, a) => { Log(a.Exception, "task"); a.SetObserved(); };
        }

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
            bool tour = Array.IndexOf(e.Args, "--tour") >= 0;
            if (Array.IndexOf(e.Args, "--safe") >= 0) Settings.Current.SoftwareRendering = true;
            if (Settings.Current.SoftwareRendering && !tour)
                try { System.Windows.Media.RenderOptions.ProcessRenderMode = System.Windows.Interop.RenderMode.SoftwareOnly; } catch { }
            Core.Perf.Decide(tour);
            // WPF animations run at 60 fps by default – follow the monitor instead (120 / 144 / 165 / 240 Hz)
            try
            {
                int hz = Core.Display.RefreshRate();
                if (Core.Perf.Light) hz = Math.Min(hz, Settings.Current.SoftwareRendering ? 30 : 60);
                System.Windows.Media.Animation.Timeline.DesiredFrameRateProperty.OverrideMetadata(
                    typeof(System.Windows.Media.Animation.Timeline), new FrameworkPropertyMetadata { DefaultValue = hz });
            }
            catch { }
            // multicore JIT: the second and later starts compile the startup code in parallel
            try { System.Runtime.ProfileOptimization.SetProfileRoot(Settings.Dir); System.Runtime.ProfileOptimization.StartProfile("startup.jitprofile"); } catch { }
            ThemeManager.Load();
            var theme = ThemeManager.All.Find(t => t.Id == Settings.Current.ThemeId) ?? ThemeManager.All.Find(t => t.Id == "vivid_genz");
            if (theme != null) ThemeManager.Apply(theme);
            base.OnStartup(e);
            // terms of use: accepted in Setup, or here on the first run of a portable copy
            ShutdownMode = ShutdownMode.OnExplicitShutdown;
            if (!TermsWindow.EnsureAccepted()) { Shutdown(); return; }
            try { main = new MainWindow(); }
            catch (Exception ex)
            {
                Log(ex, "startup");
                MessageBox.Show("GEN-Z Music could not start:\n\n" + ex.GetBaseException().Message + "\n\nDetails were saved to crash.log in %APPDATA%\\GEN-Z Music.", "GEN-Z Music", MessageBoxButton.OK, MessageBoxImage.Error);
                Shutdown(); return;
            }
            MainWindow = main;
            ShutdownMode = ShutdownMode.OnMainWindowClose;
            main.Show();
            int tour = Array.IndexOf(e.Args, "--tour");
            if (tour >= 0 && tour + 1 < e.Args.Length) { var dir = e.Args[tour + 1]; main.ContentRendered += (s2, a2) => main.RunTour(dir); }
            else if (e.Args.Length > 0) main.OpenFiles(e.Args);
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
            Log(e.Exception, "ui");
            try { MessageBox.Show(e.Exception.Message, "GEN-Z Music", MessageBoxButton.OK, MessageBoxImage.Warning); } catch { }
        }
    }
}
