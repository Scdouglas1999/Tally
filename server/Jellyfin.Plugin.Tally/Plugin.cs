using System;
using System.Collections.Generic;
using MediaBrowser.Common.Configuration;
using MediaBrowser.Common.Plugins;
using MediaBrowser.Model.Plugins;
using MediaBrowser.Model.Serialization;

namespace Jellyfin.Plugin.Tally;

public class Plugin : BasePlugin<PluginConfiguration>, IHasWebPages
{
    public static readonly Guid PluginGuid = Guid.Parse("91920c7b-e920-46ee-b4d3-421f05d3761b");

    public Plugin(IApplicationPaths applicationPaths, IXmlSerializer xmlSerializer)
        : base(applicationPaths, xmlSerializer)
    {
        Instance = this;

        if (string.IsNullOrEmpty(Configuration.ProxySecret))
        {
            Configuration.ProxySecret = Convert.ToHexString(System.Security.Cryptography.RandomNumberGenerator.GetBytes(32));
            SaveConfiguration();
        }
    }

    public static Plugin? Instance { get; private set; }

    public override string Name => "Tally";

    public override string Description => "Live channels with a guide, live scores matched to the channels showing each game, and the Tally TV app's install page.";

    public override Guid Id => PluginGuid;

    public IEnumerable<PluginPageInfo> GetPages()
    {
        yield return new PluginPageInfo
        {
            Name = "JellyTV",
            EmbeddedResourcePath = GetType().Namespace + ".Web.tally.html",
            EnableInMainMenu = true,
            MenuSection = "server",
            MenuIcon = "live_tv",
            DisplayName = "Tally"
        };

        // ES module loaded by jellyfin-web via data-controller="__plugin/JellyTV.js"
        yield return new PluginPageInfo
        {
            Name = "JellyTV.js",
            EmbeddedResourcePath = GetType().Namespace + ".Web.tally-page.js"
        };
    }
}
