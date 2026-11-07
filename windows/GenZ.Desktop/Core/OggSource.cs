using System;
using System.IO;
using CSCore;

namespace GenZ.Core
{
    /// <summary>
    /// Plays .opus and .ogg files (Ogg Opus and Ogg Vorbis) on every Windows version, without any Windows codec:
    /// decoded in GEN-Z with Concentus (Opus) and NVorbis (Vorbis). Output is 32-bit float PCM for the sound engine.
    /// </summary>
    public sealed class OggSource : IWaveSource
    {
        readonly Stream file;
        readonly Concentus.Oggfile.OpusOggReadStream opus;
        readonly NVorbis.VorbisReader vorbis;
        readonly WaveFormat format;
        readonly long length;          // bytes
        long position;                 // bytes
        float[] pending = new float[0];
        int pendingAt;

        public static bool Handles(string path)
        {
            var e = System.IO.Path.GetExtension(path).ToLowerInvariant();
            return e == ".opus" || e == ".ogg" || e == ".oga";
        }

        public OggSource(string path)
        {
            file = File.OpenRead(path);
            if (IsOpus(file))
            {
                var dec = Concentus.Structs.OpusDecoder.Create(48000, 2);
                opus = new Concentus.Oggfile.OpusOggReadStream(dec, file);
                format = new WaveFormat(48000, 32, 2, AudioEncoding.IeeeFloat);
                length = ToBytes(opus.TotalTime);
            }
            else
            {
                vorbis = new NVorbis.VorbisReader(file, false);
                format = new WaveFormat(vorbis.SampleRate, 32, vorbis.Channels, AudioEncoding.IeeeFloat);
                length = ToBytes(vorbis.TotalTime);
            }
        }

        static bool IsOpus(Stream s)
        {
            // the first Ogg page carries "OpusHead" for Opus, "\x01vorbis" for Vorbis
            var b = new byte[64];
            int n = s.Read(b, 0, b.Length);
            s.Position = 0;
            var text = System.Text.Encoding.ASCII.GetString(b, 0, n);
            return text.Contains("OpusHead");
        }

        long ToBytes(TimeSpan t) => (long)(t.TotalSeconds * format.SampleRate) * format.BlockAlign;

        public WaveFormat WaveFormat => format;
        public bool CanSeek => true;
        public long Length => length;

        public long Position
        {
            get => position;
            set
            {
                var t = TimeSpan.FromSeconds((double)Math.Max(0, value) / format.BytesPerSecond);
                if (opus != null) opus.SeekTo(t); else vorbis.TimePosition = t;
                pending = new float[0]; pendingAt = 0;
                position = value - value % format.BlockAlign;
            }
        }

        public int Read(byte[] buffer, int offset, int count)
        {
            int written = 0;
            count -= count % 4;
            while (written < count)
            {
                if (pendingAt >= pending.Length && !Fill()) break;
                int floats = Math.Min(pending.Length - pendingAt, (count - written) / 4);
                Buffer.BlockCopy(pending, pendingAt * 4, buffer, offset + written, floats * 4);
                pendingAt += floats; written += floats * 4;
            }
            position += written;
            return written;
        }

        bool Fill()
        {
            pendingAt = 0;
            if (opus != null)
            {
                if (!opus.HasNextPacket) return false;
                var pcm = opus.DecodeNextPacket();
                if (pcm == null) { pending = new float[0]; return opus.HasNextPacket; }
                if (pending.Length != pcm.Length) pending = new float[pcm.Length];
                for (int i = 0; i < pcm.Length; i++) pending[i] = pcm[i] / 32768f;
                return true;
            }
            var buf = new float[4096 * format.Channels];
            int n = vorbis.ReadSamples(buf, 0, buf.Length);
            if (n <= 0) return false;
            if (n < buf.Length) Array.Resize(ref buf, n);
            pending = buf;
            return true;
        }

        public void Dispose()
        {
            try { vorbis?.Dispose(); } catch { }
            try { file.Dispose(); } catch { }
        }
    }
}
