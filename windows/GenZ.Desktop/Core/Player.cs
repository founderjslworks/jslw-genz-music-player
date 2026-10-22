using System;
using System.Collections.Generic;
using System.Linq;
using System.Windows;
using CSCore;
using CSCore.Codecs;
using CSCore.CoreAudioAPI;
using CSCore.SoundOut;
using CSCore.Streams.Effects;

namespace GenZ.Core
{
    /// <summary>
    /// Playback engine: CSCore decoders (MP3, AAC, WMA via Windows Media Foundation; FLAC, WAV, AIFF built in),
    /// a 10-band equalizer and WASAPI output (DirectSound fallback). Works on Windows 7 and later.
    /// </summary>
    public static class Player
    {
        public static readonly double[] Bands = { 31, 62, 125, 250, 500, 1000, 2000, 4000, 8000, 16000 };
        public static readonly Dictionary<string, double[]> Presets = new Dictionary<string, double[]>
        {
            ["Flat"] = new double[] { 0, 0, 0, 0, 0, 0, 0, 0, 0, 0 },
            ["Bass Boost"] = new double[] { 7, 6, 5, 3, 1, 0, 0, 0, 0, 0 },
            ["Rock"] = new double[] { 5, 4, 3, 1, -1, -1, 1, 3, 4, 5 },
            ["Pop"] = new double[] { -1, 1, 3, 4, 3, 1, 0, 1, 2, 2 },
            ["Jazz"] = new double[] { 3, 2, 1, 2, -1, -1, 0, 1, 2, 3 },
            ["Classical"] = new double[] { 4, 3, 2, 1, 0, 0, 0, 1, 2, 3 },
            ["Vocal"] = new double[] { -2, -2, -1, 1, 3, 4, 3, 1, 0, -1 },
            ["Electronic"] = new double[] { 5, 4, 1, 0, -2, 1, 0, 1, 4, 5 },
            ["Hip-Hop"] = new double[] { 6, 5, 2, 3, -1, -1, 1, 0, 2, 3 },
            ["Acoustic"] = new double[] { 4, 3, 2, 1, 1, 1, 2, 2, 3, 2 },
            ["Treble"] = new double[] { 0, 0, 0, 0, 0, 1, 3, 5, 6, 7 },
            ["Late Night"] = new double[] { 2, 2, 1, 0, 0, 0, -1, -2, -2, -3 },
        };

        static ISoundOut output;
        static IWaveSource source;
        static Equalizer eq;
        static bool stopping;

        public static List<Song> Queue { get; private set; } = new List<Song>();
        static List<Song> original = new List<Song>();
        public static int Index { get; private set; } = -1;
        public static Song Current => Index >= 0 && Index < Queue.Count ? Queue[Index] : null;
        public static bool IsPlaying => output != null && output.PlaybackState == PlaybackState.Playing;
        public static string Error { get; private set; }

        public static event Action TrackChanged;
        public static event Action StateChanged;

        public static TimeSpan Position
        {
            get { try { return source != null ? source.GetPosition() : TimeSpan.Zero; } catch { return TimeSpan.Zero; } }
            set { try { if (source != null && source.CanSeek) source.SetPosition(value < TimeSpan.Zero ? TimeSpan.Zero : value); } catch { } }
        }

        public static TimeSpan Duration
        {
            get
            {
                try { if (source != null && source.Length > 0) return source.GetLength(); } catch { }
                return TimeSpan.FromMilliseconds(Current?.DurationMs ?? 0);
            }
        }

        public static double Volume
        {
            get => Settings.Current.Volume;
            set
            {
                Settings.Current.Volume = Math.Max(0, Math.Min(1, value));
                try { if (output != null) output.Volume = (float)Settings.Current.Volume; } catch { }
            }
        }

        public static void PlayList(IList<Song> list, int index)
        {
            if (list == null || list.Count == 0) return;
            original = list.ToList();
            var start = original[Math.Max(0, Math.Min(index, original.Count - 1))];
            if (Settings.Current.Shuffle)
            {
                var rnd = new Random();
                Queue = original.Where(s => s != start).OrderBy(_ => rnd.Next()).ToList();
                Queue.Insert(0, start);
                Index = 0;
            }
            else { Queue = original.ToList(); Index = Queue.IndexOf(start); }
            Load(true);
        }

        public static void PlayAt(int queueIndex)
        {
            if (queueIndex < 0 || queueIndex >= Queue.Count) return;
            Index = queueIndex; Load(true);
        }

        public static void Toggle()
        {
            if (output == null) { if (Current != null) Load(true); else if (Library.Songs.Count > 0) PlayList(Library.Songs, 0); return; }
            if (output.PlaybackState == PlaybackState.Playing) output.Pause();
            else if (output.PlaybackState == PlaybackState.Paused) output.Resume();
            else output.Play();
            StateChanged?.Invoke();
        }

        public static void Next(bool auto = false)
        {
            if (Queue.Count == 0) return;
            if (auto && Settings.Current.Repeat == 2) { Load(true); return; }
            if (Index + 1 < Queue.Count) Index++;
            else if (Settings.Current.Repeat == 1 || !auto) Index = 0;
            else { Stop(); return; }
            Load(true);
        }

        public static void Prev()
        {
            if (Queue.Count == 0) return;
            if (Position.TotalSeconds > 3) { Position = TimeSpan.Zero; return; }
            Index = Index > 0 ? Index - 1 : Queue.Count - 1;
            Load(true);
        }

        public static void SetShuffle(bool on)
        {
            Settings.Current.Shuffle = on;
            var cur = Current;
            if (cur == null) return;
            if (on)
            {
                var rnd = new Random();
                Queue = Queue.Where(s => s != cur).OrderBy(_ => rnd.Next()).ToList();
                Queue.Insert(0, cur); Index = 0;
            }
            else { Queue = original.ToList(); Index = Math.Max(0, Queue.IndexOf(cur)); }
            StateChanged?.Invoke();
        }

        public static void CycleRepeat() { Settings.Current.Repeat = (Settings.Current.Repeat + 1) % 3; StateChanged?.Invoke(); }

        public static void Stop()
        {
            Release();
            StateChanged?.Invoke();
        }

        static void Release()
        {
            stopping = true;
            try { output?.Stop(); } catch { }
            try { output?.Dispose(); } catch { }
            try { source?.Dispose(); } catch { }
            output = null; source = null; eq = null;
            stopping = false;
        }

        static void Load(bool play)
        {
            var song = Current;
            Release();
            if (song == null) return;
            Error = null;
            foreach (var s in Queue) s.IsPlaying = false;
            song.IsPlaying = true;
            Settings.Current.LastSong = song.Path;
            try
            {
                source = CodecFactory.Instance.GetCodec(song.Path);
                var samples = source.ToSampleSource();
                eq = Equalizer.Create10BandEqualizer(samples);
                ApplyEq();
                // 32-bit float all the way to the device; the EQ keeps automatic headroom so boosts never clip.
                var wave = eq.ToWaveSource();
                output = CreateOutput();
                output.Initialize(wave);
                output.Volume = (float)Settings.Current.Volume;
                output.Stopped += OnStopped;
                if (play) output.Play();
            }
            catch (Exception e)
            {
                Error = "Can't play " + System.IO.Path.GetFileName(song.Path) + ": " + e.Message;
                Release();
            }
            TrackChanged?.Invoke();
            StateChanged?.Invoke();
        }

        static ISoundOut CreateOutput()
        {
            try { if (WasapiOut.IsSupportedOnCurrentPlatform) return new WasapiOut(false, AudioClientShareMode.Shared, 100); } catch { }
            return new DirectSoundOut();
        }

        static void OnStopped(object sender, PlaybackStoppedEventArgs e)
        {
            if (stopping) return;
            Application.Current?.Dispatcher.BeginInvoke(new Action(() =>
            {
                if (stopping || sender != output) return;
                Next(auto: true);
            }));
        }

        public static void ApplyEq()
        {
            if (eq == null) return;
            var gains = Settings.Current.EqEnabled ? Settings.Current.EqGains : new List<double>(new double[10]);
            double max = Math.Max(0, gains.Max());
            for (int i = 0; i < 10 && i < eq.SampleFilters.Count; i++)
                eq.SampleFilters[i].AverageGainDB = gains[i] - max * 0.75; // automatic headroom
        }
    }
}
