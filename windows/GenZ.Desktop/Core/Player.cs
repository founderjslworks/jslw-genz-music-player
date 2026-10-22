using System;
using System.Collections.Generic;
using System.Linq;
using System.Windows;
using System.Windows.Threading;
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
        public static bool IsPlaying => output != null && !fadingOut && output.PlaybackState == PlaybackState.Playing;
        public static bool HasOutput => output != null;
        public static string Error { get; private set; }

        public static event Action TrackChanged;
        public static event Action StateChanged;
        /// <summary>The queue was edited (play next, add, remove, reorder).</summary>
        public static event Action QueueChanged;
        /// <summary>The sleep timer's "end of this song" stop happened.</summary>
        public static event Action SleepEnded;
        /// <summary>When true, playback stops (paused on the next song) when the current song ends.</summary>
        public static bool StopAfterCurrent { get; set; }

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
                ApplyVolume();
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
            if (IsPlaying) Pause(Settings.Current.FadePlayPause);
            else Resume(Settings.Current.FadePlayPause);
        }

        public static void Pause(bool fade)
        {
            if (output == null || !IsPlaying) return;
            if (!fade) { StopFade(); fadeGain = 1; try { output.Pause(); } catch { } ApplyVolume(); StateChanged?.Invoke(); return; }
            fadingOut = true;
            StateChanged?.Invoke();
            var o = output;
            Fade(0, 220, () =>
            {
                fadingOut = false;
                if (o != output) return;
                try { output.Pause(); } catch { }
                fadeGain = 1; ApplyVolume();
                StateChanged?.Invoke();
            });
        }

        public static void Resume(bool fade)
        {
            if (output == null) return;
            if (fadingOut) { fadingOut = false; Fade(1, 200, null); StateChanged?.Invoke(); return; }
            if (fade) { StopFade(); fadeGain = 0; ApplyVolume(); }
            try
            {
                if (output.PlaybackState == PlaybackState.Paused) output.Resume();
                else if (output.PlaybackState != PlaybackState.Playing) output.Play();
            }
            catch { }
            if (fade) Fade(1, 320, null);
            StateChanged?.Invoke();
        }

        // ---------------------------------------------------------------- volume, fades, sleep fade
        static double fadeGain = 1, sleepGain = 1;
        static bool fadingOut;
        static DispatcherTimer fadeTimer;
        static double fadeFrom, fadeTo, fadeMs;
        static DateTime fadeStart;
        static Action fadeDone;

        /// <summary>Extra volume factor used by the sleep timer to fade the music out (1 = normal).</summary>
        public static double SleepGain { get => sleepGain; set { sleepGain = Math.Max(0, Math.Min(1, value)); ApplyVolume(); } }

        static void ApplyVolume()
        {
            try { if (output != null) output.Volume = (float)Math.Max(0, Math.Min(1, Settings.Current.Volume * fadeGain * sleepGain)); } catch { }
        }

        static void Fade(double to, double ms, Action done)
        {
            if (fadeTimer == null)
            {
                fadeTimer = new DispatcherTimer(DispatcherPriority.Render) { Interval = TimeSpan.FromMilliseconds(15) };
                fadeTimer.Tick += (s, e) =>
                {
                    double t = (DateTime.Now - fadeStart).TotalMilliseconds / Math.Max(1, fadeMs);
                    if (t >= 1)
                    {
                        fadeGain = fadeTo; ApplyVolume(); fadeTimer.Stop();
                        var d = fadeDone; fadeDone = null; d?.Invoke();
                    }
                    else
                    {
                        double e2 = t * t * (3 - 2 * t); // smoothstep
                        fadeGain = fadeFrom + (fadeTo - fadeFrom) * e2; ApplyVolume();
                    }
                };
            }
            fadeFrom = fadeGain; fadeTo = to; fadeMs = ms; fadeStart = DateTime.Now; fadeDone = done;
            fadeTimer.Start();
        }

        static void StopFade() { fadeTimer?.Stop(); fadeDone = null; fadingOut = false; }

        // ---------------------------------------------------------------- queue editing
        /// <summary>Insert songs right after the current one.</summary>
        public static void PlayNext(IList<Song> songs)
        {
            if (songs == null || songs.Count == 0) return;
            if (Current == null) { PlayList(songs, 0); return; }
            Queue.InsertRange(Index + 1, songs);
            var cur = Current; int oi = original.IndexOf(cur);
            original.InsertRange(oi >= 0 ? oi + 1 : original.Count, songs);
            QueueChanged?.Invoke();
        }

        /// <summary>Add songs to the end of the queue.</summary>
        public static void Enqueue(IList<Song> songs)
        {
            if (songs == null || songs.Count == 0) return;
            if (Current == null) { PlayList(songs, 0); return; }
            Queue.AddRange(songs); original.AddRange(songs);
            QueueChanged?.Invoke();
        }

        public static void RemoveAt(IEnumerable<int> indexes)
        {
            bool removedCurrent = false;
            var cur = Current;
            foreach (var i in indexes.Where(i => i >= 0 && i < Queue.Count).Distinct().OrderByDescending(i => i))
            {
                var s = Queue[i];
                Queue.RemoveAt(i);
                original.Remove(s);
                if (i < Index) Index--;
                else if (i == Index) removedCurrent = true;
            }
            if (Queue.Count == 0) { Index = -1; Release(); if (cur != null) cur.IsPlaying = false; TrackChanged?.Invoke(); StateChanged?.Invoke(); QueueChanged?.Invoke(); return; }
            if (removedCurrent)
            {
                bool was = IsPlaying;
                if (cur != null) cur.IsPlaying = false;
                if (Index >= Queue.Count) Index = 0;
                Load(was);
            }
            QueueChanged?.Invoke();
        }

        public static void Move(int from, int to)
        {
            if (from < 0 || from >= Queue.Count) return;
            var cur = Current;
            var s = Queue[from]; Queue.RemoveAt(from);
            to = Math.Max(0, Math.Min(to, Queue.Count));
            Queue.Insert(to, s);
            Index = Queue.IndexOf(cur);
            QueueChanged?.Invoke();
        }

        public static void ClearQueue()
        {
            var cur = Current;
            if (cur == null) { Queue = new List<Song>(); original = new List<Song>(); Index = -1; QueueChanged?.Invoke(); return; }
            Queue = new List<Song> { cur }; original = new List<Song> { cur }; Index = 0;
            QueueChanged?.Invoke();
        }

        /// <summary>Restore the last session: queue, song and position, paused.</summary>
        public static void Restore(List<Song> queue, int index, TimeSpan position)
        {
            if (queue == null || queue.Count == 0) return;
            original = queue.ToList(); Queue = queue.ToList();
            Index = Math.Max(0, Math.Min(index, Queue.Count - 1));
            Load(false, false);
            if (position > TimeSpan.Zero && position < Duration - TimeSpan.FromSeconds(2)) Position = position;
        }

        /// <summary>Runs an action (e.g. saving tags) while this file is not held open by playback.</summary>
        public static void WithFileReleased(string path, Action action)
        {
            bool isCur = output != null && Current != null && string.Equals(Current.Path, path, StringComparison.OrdinalIgnoreCase);
            if (!isCur) { action(); return; }
            var pos = Position; bool was = IsPlaying;
            Release();
            try { action(); }
            finally { Load(was, false); Position = pos; }
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
            StopFade(); fadeGain = 1;
            try { output?.Stop(); } catch { }
            try { output?.Dispose(); } catch { }
            try { source?.Dispose(); } catch { }
            output = null; source = null; eq = null;
            stopping = false;
        }

        static void Load(bool play, bool count = true)
        {
            var song = Current;
            Release();
            if (song == null) return;
            Error = null;
            foreach (var s in Queue) s.IsPlaying = false;
            song.IsPlaying = true;
            Settings.Current.LastSong = song.Path;
            if (play && count) Settings.Current.Played(song.Path);
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
                ApplyVolume();
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
                if (StopAfterCurrent)
                {
                    // sleep timer "end of this song": move on to the next song but stay paused
                    StopAfterCurrent = false; sleepGain = 1;
                    if (Index + 1 < Queue.Count) Index++;
                    Load(false, false);
                    SleepEnded?.Invoke();
                    return;
                }
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
