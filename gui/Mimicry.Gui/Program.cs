using Avalonia;

namespace Mimicry.Gui;

public static class Program
{
    public static string? RuntimeFile { get; private set; }
    public static string? InitialSession { get; private set; }

    [STAThread]
    public static int Main(string[] args)
    {
        if (args.Contains("--help") || args.Contains("-h"))
        {
            Console.WriteLine("Mimicry GUI\nUsage: Mimicry.Gui [--runtime-file PATH] [--session ID]\nConnects to an existing runtime; closing the GUI leaves it running.");
            return 0;
        }
        for (var i = 0; i < args.Length; i++)
        {
            switch (args[i])
            {
                case "--runtime-file" when i + 1 < args.Length:
                    RuntimeFile = args[++i];
                    break;
                case "--session" when i + 1 < args.Length:
                    InitialSession = args[++i];
                    break;
                default:
                    Console.Error.WriteLine("Unknown or incomplete option. Use --help.");
                    return 2;
            }
        }
        BuildAvaloniaApp().StartWithClassicDesktopLifetime(Array.Empty<string>());
        return 0;
    }

    public static AppBuilder BuildAvaloniaApp() => AppBuilder.Configure<App>()
        .UsePlatformDetect().WithInterFont().LogToTrace();
}
