using Jellyfin.Plugin.Tally.Scores;
using Jellyfin.Plugin.Tally.Services;
using SkiaSharp;
using Xunit;

namespace Tally.Tests;

public class GameArtTests
{
    [Fact]
    public void Backdrop_Path_Escapes_The_Game_Id()
    {
        Assert.Equal("/JellyTV/Backdrop/401872933.png", GameArtService.BackdropPath(new GameInfo { Id = "401872933" }));
        Assert.Equal("/JellyTV/Backdrop/a%20b%2Fc.png", GameArtService.BackdropPath(new GameInfo { Id = "a b/c" }));
    }

    [Fact]
    public void Plain_Frame_Is_A_Ground_Colored_1920x1080()
    {
        var png = GameArtService.RenderPlain();
        using var bmp = Decode(png);
        Assert.Equal(GameArtService.Width, bmp.Width);
        Assert.Equal(GameArtService.Height, bmp.Height);
        Assert.Equal(SKColor.Parse("#0e0f0e"), bmp.GetPixel(0, 0));
        Assert.Equal(SKColor.Parse("#0e0f0e"), bmp.GetPixel(960, 540));
        Assert.Equal(SKColor.Parse("#0e0f0e"), bmp.GetPixel(1919, 1079));
    }

    [Fact]
    public void Missing_Colors_Use_The_Neutral_Field()
    {
        using var bmp = Decode(GameArtService.Render(Game(home: "", homeAlt: "", away: "", awayAlt: ""), null, null));
        // 58% #0e0f0e + 42% #1b1c1a, away from both the raw neutral and the ground.
        AssertRgb(Mode(bmp, 1760, 1000, 1860, 1050), 19, 20, 19);
        Assert.Equal(SKColor.Parse("#0e0f0e"), bmp.GetPixel(40, 200));
    }

    [Fact]
    public void Primary_Color_Is_Kept_When_The_Logo_Contrasts()
    {
        var white = Solid(SKColors.White);
        using var bmp = Decode(GameArtService.Render(Game(home: "e81828", homeAlt: "003278"), null, white));
        // white mark does not swallow #e81828, so the home field is that red, not the navy alternate.
        AssertRgb(Mode(bmp, 1760, 1000, 1860, 1050), 106, 19, 25);
    }

    [Fact]
    public void Navy_Logo_On_Navy_Uses_The_Alternate_Color()
    {
        // Yankees: navy mark on navy primary. Average color is the primary, so the silver alternate is used.
        var navy = Solid(new SKColor(0x13, 0x24, 0x48));
        using var bmp = Decode(GameArtService.Render(Game(home: "132448", homeAlt: "c4ced4"), null, navy));
        AssertRgb(Mode(bmp, 1760, 1000, 1860, 1050), 90, 95, 97);
    }

    [Fact]
    public void Dark_Logo_On_A_Different_Dark_Primary_Uses_The_Alternate()
    {
        // Black mark vs #000080 is 128 away in RGB (outside 90) but both luminances are under 0.12.
        var black = Solid(SKColors.Black);
        using var bmp = Decode(GameArtService.Render(Game(home: "000080", homeAlt: "ffcc00"), null, black));
        AssertRgb(Mode(bmp, 1760, 1000, 1860, 1050), 115, 94, 8);
    }

    [Fact]
    public void Near_Black_Primary_Is_Lifted_When_Nothing_Else_Is_Available()
    {
        using var bmp = Decode(GameArtService.Render(Game(home: "000000", homeAlt: ""), null, null));
        // 12% white mixed in before the field mix — not the unlifted black field (about 8, 9, 8).
        AssertRgb(Mode(bmp, 1760, 1000, 1860, 1050), 21, 22, 21);
    }

    [Fact]
    public void Only_The_Alternate_Is_Used_When_The_Primary_Is_Missing()
    {
        using var bmp = Decode(GameArtService.Render(Game(home: "", homeAlt: "e81828"), null, null));
        AssertRgb(Mode(bmp, 1760, 1000, 1860, 1050), 106, 19, 25);
    }

    [Fact]
    public void Fixture_Art_Without_Logos_Is_1920x1080_And_Under_400KB()
    {
        var games = EspnScoreboardParser.Parse(ReadFixture("gameart-mlb.json"), "baseball/mlb");
        var game = Assert.Single(games.Where(g => g.Away.Abbr == "TB" && g.Home.Abbr == "NYY").Take(1));
        var png = GameArtService.Render(game, null, null);
        using var bmp = Decode(png);

        Assert.Equal(1920, bmp.Width);
        Assert.Equal(1080, bmp.Height);
        Assert.True(png.Length < 400 * 1024, $"png is {png.Length} bytes");
        Assert.Equal(SKColor.Parse("#0e0f0e"), bmp.GetPixel(40, 200));
        var home = Mode(bmp, 1760, 1000, 1860, 1050);
        Assert.NotEqual(SKColor.Parse("#0e0f0e"), home);
    }

    [Fact]
    public async Task Writes_Screen_Simulations_When_Art_Out_Is_Set()
    {
        var dir = Environment.GetEnvironmentVariable("JELLYTV_ART_OUT");
        if (string.IsNullOrWhiteSpace(dir))
        {
            return;
        }

        Directory.CreateDirectory(dir);
        using var http = new HttpClient();
        http.DefaultRequestHeaders.TryAddWithoutValidation("User-Agent", "JellyTV/0.1");
        http.Timeout = TimeSpan.FromSeconds(20);
        var cache = new Dictionary<string, byte[]?>(StringComparer.Ordinal);

        var boards = new (string File, string League)[]
        {
            ("gameart-mlb.json", "baseball/mlb"),
            ("gameart-nfl.json", "football/nfl"),
            ("gameart-nhl.json", "hockey/nhl"),
            ("gameart-nba.json", "basketball/nba")
        };

        foreach (var (file, league) in boards)
        {
            foreach (var game in EspnScoreboardParser.Parse(ReadFixture(file), league))
            {
                var away = await FetchLogoAsync(http, cache, game.Away.Logo);
                var home = await FetchLogoAsync(http, cache, game.Home.Logo);
                var png = GameArtService.Render(game, away, home);
                var stem = FileStem(dir, game);
                await File.WriteAllBytesAsync(Path.Combine(dir, stem + ".png"), png);
                await File.WriteAllBytesAsync(Path.Combine(dir, stem + "-screen.png"), Screen(png, $"{game.Away.Abbr} at {game.Home.Abbr}"));
            }
        }
    }

    private static string FileStem(string dir, GameInfo game)
    {
        var name = $"{game.League}-{game.Away.Abbr}-{game.Home.Abbr}".ToLowerInvariant();
        var plain = Path.Combine(dir, name + ".png");
        return File.Exists(plain) ? $"{name}-{game.Id}" : name;
    }

    private static async Task<byte[]?> FetchLogoAsync(HttpClient http, Dictionary<string, byte[]?> cache, string url)
    {
        if (string.IsNullOrEmpty(url))
        {
            return null;
        }

        if (cache.TryGetValue(url, out var hit))
        {
            return hit;
        }

        byte[]? bytes = null;
        if (Uri.TryCreate(url, UriKind.Absolute, out var uri) && uri.Host.EndsWith(".espncdn.com", StringComparison.OrdinalIgnoreCase))
        {
            try
            {
                bytes = await http.GetByteArrayAsync(uri);
                if (bytes.Length is 0 or >= 2_000_000)
                {
                    bytes = null;
                }
            }
            catch (HttpRequestException)
            {
                bytes = null;
            }
        }

        cache[url] = bytes;
        return bytes;
    }

    /// <summary>The app's top-right placement and mask, plus a stand-in for the page title.</summary>
#pragma warning disable CS0618 // SkiaSharp 3 obsoletes the text and resize API that 2.88 requires
    private static byte[] Screen(byte[] art, string title)
    {
        using var artBmp = Decode(art);
        using var scaled = artBmp.Resize(new SKImageInfo(1344, 756, SKColorType.Rgba8888, SKAlphaType.Unpremul), SKFilterQuality.High);
        Assert.NotNull(scaled);

        const int boxX = 576;
        const int boxW = 1344;
        const int boxH = 756;
        var ground = SKColor.Parse("#0e0f0e");
        var info = new SKImageInfo(1920, 1080, SKColorType.Rgba8888, SKAlphaType.Premul);
        using var screen = new SKBitmap(info);
        var pixels = new SKColor[1920 * 1080];
        for (var i = 0; i < pixels.Length; i++)
        {
            pixels[i] = ground;
        }

        var denom = 0.6 * boxW;
        for (var by = 0; by < boxH; by++)
        {
            var fadeY = 1.0 - (by / (double)boxH);
            for (var bx = 0; bx < boxW; bx++)
            {
                var alpha = Math.Min(bx / denom, 1.0) * fadeY;
                var src = scaled.GetPixel(bx, by);
                pixels[(by * 1920) + boxX + bx] = new SKColor(
                    (byte)Math.Round((src.Red * alpha) + (ground.Red * (1.0 - alpha))),
                    (byte)Math.Round((src.Green * alpha) + (ground.Green * (1.0 - alpha))),
                    (byte)Math.Round((src.Blue * alpha) + (ground.Blue * (1.0 - alpha))));
            }
        }

        screen.Pixels = pixels;
        using var canvas = new SKCanvas(screen);
        using var face = SKTypeface.FromFile("/usr/share/fonts/TTF/DejaVuSans-Bold.ttf") ?? SKTypeface.Default;
        using var paint = new SKPaint
        {
            Typeface = face,
            TextSize = 60,
            Color = SKColors.White,
            IsAntialias = true,
            TextAlign = SKTextAlign.Left
        };
        canvas.DrawText(title, 160, 140, paint);

        using var image = SKImage.FromBitmap(screen);
        using var data = image.Encode(SKEncodedImageFormat.Png, 100);
        return data.ToArray();
    }
#pragma warning restore CS0618

    private static GameInfo Game(string home, string homeAlt, string away = "00aa55", string awayAlt = "")
        => new()
        {
            Id = "t",
            League = "MLB",
            Home = new GameTeam { Abbr = "H", Color = home, AltColor = homeAlt },
            Away = new GameTeam { Abbr = "A", Color = away, AltColor = awayAlt }
        };

    private static byte[] Solid(SKColor color)
    {
        using var bmp = new SKBitmap(64, 64, SKColorType.Rgba8888, SKAlphaType.Unpremul);
        using var canvas = new SKCanvas(bmp);
        canvas.Clear(color);
        using var image = SKImage.FromBitmap(bmp);
        using var data = image.Encode(SKEncodedImageFormat.Png, 100);
        return data.ToArray();
    }

    private static SKBitmap Decode(byte[] png)
    {
        var bmp = SKBitmap.Decode(png);
        Assert.NotNull(bmp);
        return bmp;
    }

    private static SKColor Mode(SKBitmap bmp, int x0, int y0, int x1, int y1)
    {
        var counts = new Dictionary<uint, int>();
        uint best = 0;
        var bestN = -1;
        for (var y = y0; y < y1; y++)
        {
            for (var x = x0; x < x1; x++)
            {
                var c = bmp.GetPixel(x, y);
                var key = (uint)((c.Red << 16) | (c.Green << 8) | c.Blue);
                var n = counts.TryGetValue(key, out var have) ? have + 1 : 1;
                counts[key] = n;
                if (n > bestN)
                {
                    bestN = n;
                    best = key;
                }
            }
        }

        return new SKColor((byte)(best >> 16), (byte)(best >> 8), (byte)best);
    }

    private static void AssertRgb(SKColor actual, byte r, byte g, byte b)
    {
        // Encoder snaps blend pixels to even channels, so a field can sit one level off the ideal mix.
        var ok = Math.Abs(actual.Red - r) <= 1 && Math.Abs(actual.Green - g) <= 1 && Math.Abs(actual.Blue - b) <= 1;
        Assert.True(ok, $"expected {(r, g, b)} but was {(actual.Red, actual.Green, actual.Blue)}");
    }

    private static string ReadFixture(string name)
    {
        var dir = new DirectoryInfo(AppContext.BaseDirectory);
        while (dir != null)
        {
            foreach (var rel in new[] { Path.Combine("Fixtures", name), Path.Combine("Tally.Tests", "Fixtures", name) })
            {
                var path = Path.Combine(dir.FullName, rel);
                if (File.Exists(path))
                {
                    return File.ReadAllText(path);
                }
            }

            dir = dir.Parent;
        }

        throw new FileNotFoundException(name);
    }
}
