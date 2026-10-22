using System.Collections.Generic;
using System.Linq;
using Newtonsoft.Json;
using Newtonsoft.Json.Linq;

namespace GenZ.Core
{
    /// <summary>Tiny JSON helper (Newtonsoft.Json, works on .NET Framework 4.8 / Windows 7).</summary>
    public static class Json
    {
        public static Dictionary<string, object> Parse(string text) => ToDict(JObject.Parse(text));
        public static T Read<T>(string text) => JsonConvert.DeserializeObject<T>(text);
        public static string Write(object o) => JsonConvert.SerializeObject(o);

        static object Plain(JToken t)
        {
            switch (t)
            {
                case JObject o: return ToDict(o);
                case JArray a: return a.Select(Plain).ToList();
                case JValue v: return v.Value;
                default: return t?.ToString();
            }
        }
        static Dictionary<string, object> ToDict(JObject o) => o.Properties().ToDictionary(p => p.Name, p => Plain(p.Value));

        public static string Str(this Dictionary<string, object> d, string k, string def = null)
            => d != null && d.TryGetValue(k, out var v) && v != null ? v.ToString() : def;
        public static bool Bool(this Dictionary<string, object> d, string k, bool def = false)
            => d != null && d.TryGetValue(k, out var v) && v is bool b ? b : def;
        public static double Num(this Dictionary<string, object> d, string k, double def = 0)
        {
            if (d == null || !d.TryGetValue(k, out var v) || v == null) return def;
            try { return System.Convert.ToDouble(v, System.Globalization.CultureInfo.InvariantCulture); } catch { return def; }
        }
        public static Dictionary<string, object> Obj(this Dictionary<string, object> d, string k)
            => d != null && d.TryGetValue(k, out var v) ? v as Dictionary<string, object> : null;
    }
}
