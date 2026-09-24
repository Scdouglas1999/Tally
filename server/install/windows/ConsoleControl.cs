using System.Runtime.InteropServices;

namespace Tally.ServerSetup;

/// <summary>Sends Ctrl+C to a console process (how Jellyfin is asked to shut down cleanly), and lets the quiet mode
/// write to the console it was started from.</summary>
internal static partial class ConsoleControl
{
    private const uint AttachParentProcess = 0xFFFFFFFF;
    private static readonly object Gate = new();
    private static bool _parentAttached;

    public static void AttachToParent()
    {
        _parentAttached = AttachConsole(AttachParentProcess);
    }

    public static bool SendCtrlC(int processId)
    {
        lock (Gate)
        {
            FreeConsole();
            try
            {
                if (!AttachConsole((uint)processId))
                {
                    return false;
                }

                // ignore the event ourselves: it goes to every process on that console
                SetConsoleCtrlHandler(IntPtr.Zero, true);
                var sent = GenerateConsoleCtrlEvent(0, 0);
                Thread.Sleep(300);
                return sent;
            }
            finally
            {
                FreeConsole();
                if (_parentAttached && AttachConsole(AttachParentProcess))
                {
                    // the writers still hold the handles of the console that was just freed
                    Console.SetOut(new StreamWriter(Console.OpenStandardOutput()) { AutoFlush = true });
                    Console.SetError(new StreamWriter(Console.OpenStandardError()) { AutoFlush = true });
                }
            }
        }
    }

    [LibraryImport("kernel32.dll", SetLastError = true)]
    [return: MarshalAs(UnmanagedType.Bool)]
    private static partial bool AttachConsole(uint processId);

    [LibraryImport("kernel32.dll", SetLastError = true)]
    [return: MarshalAs(UnmanagedType.Bool)]
    private static partial bool FreeConsole();

    [LibraryImport("kernel32.dll", SetLastError = true)]
    [return: MarshalAs(UnmanagedType.Bool)]
    private static partial bool SetConsoleCtrlHandler(IntPtr handler, [MarshalAs(UnmanagedType.Bool)] bool add);

    [LibraryImport("kernel32.dll", SetLastError = true)]
    [return: MarshalAs(UnmanagedType.Bool)]
    private static partial bool GenerateConsoleCtrlEvent(uint ctrlEvent, uint processGroupId);
}
