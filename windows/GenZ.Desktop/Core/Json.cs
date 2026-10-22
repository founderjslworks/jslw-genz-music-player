using System.Collections.Generic;
using System.Web.Script.Serialization;

namespace GenZ.Core
{
    /// <summary>Tiny JSON helper on top of the built-in .NET Framework serializer.</summary>
    public static class Json
    {
        static JavaScriptSerializer Make() => new JavaScriptSerializer { MaxJsonLength = int.MaxValue, RecursionLimit = 64 };
        public static Dictionary<string, object> Parse(string text) => Make().Deserialize<Dictionary<string, object>>(text);
        public static T Read<T>(string text) => Make().Deserialize<T>(text);
        public static string Write(object o) => Make().Serialize(o);

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
