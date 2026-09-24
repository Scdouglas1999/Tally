using System;
using System.Globalization;
using System.IO;
using System.Linq;
using System.Text;
using System.Xml;

namespace Jellyfin.Plugin.Tally.Dvr;

/// <summary>
/// The NFO next to a recording (Kodi/Jellyfin movie format, read by Jellyfin's NFO reader): title "Away at Home", the
/// date, league and teams. It is built from a <see cref="GameSnapshot"/>, which has no score, so no result can reach
/// the metadata. <c>lockdata</c> keeps a library refresh from replacing it.
/// </summary>
public static class DvrNfo
{
    public static string Build(GameSnapshot g, TimeZoneInfo zone)
    {
        var date = DvrNaming.LocalDate(g, zone);
        var day = date.ToString("yyyy-MM-dd", CultureInfo.InvariantCulture);
        var league = string.IsNullOrWhiteSpace(g.League) ? "Sports" : g.League;
        var plot = $"{league}: {g.Away.DisplayName} at {g.Home.DisplayName}, {date.ToString("dddd, MMMM d, yyyy", CultureInfo.InvariantCulture)}.";

        var settings = new XmlWriterSettings { Indent = true, Encoding = new UTF8Encoding(false), OmitXmlDeclaration = false };
        using var ms = new MemoryStream();
        using (var w = XmlWriter.Create(ms, settings))
        {
            w.WriteStartDocument(true);
            w.WriteStartElement("movie");
            w.WriteElementString("title", g.Title);
            w.WriteElementString("sorttitle", day + " " + g.Title);
            w.WriteElementString("plot", plot);
            w.WriteElementString("outline", plot);
            w.WriteElementString("premiered", day);
            w.WriteElementString("releasedate", day);
            w.WriteElementString("year", date.Year.ToString(CultureInfo.InvariantCulture));
            w.WriteElementString("genre", "Sports");
            w.WriteElementString("genre", league);
            w.WriteElementString("tag", league);
            foreach (var team in new[] { g.Away, g.Home }.Select(t => t.DisplayName).Where(n => n.Length > 0).Distinct())
            {
                w.WriteElementString("tag", team);
            }

            foreach (var network in g.Broadcasts.Where(b => !string.IsNullOrWhiteSpace(b)).Distinct().Take(3))
            {
                w.WriteElementString("studio", network);
            }

            w.WriteElementString("lockdata", "true");
            w.WriteEndElement();
            w.WriteEndDocument();
        }

        return Encoding.UTF8.GetString(ms.ToArray());
    }
}
