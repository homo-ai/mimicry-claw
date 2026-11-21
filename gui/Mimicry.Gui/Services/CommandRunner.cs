using System.Diagnostics;
using Avalonia.Threading;
using CommunityToolkit.Mvvm.ComponentModel;

namespace Mimicry.Gui.Services;

// Explicit user-command output only. This is not a PTY or an agent tool executor.
public sealed class CommandRunner : ObservableObject, IDisposable
{
    private Process? _process;
    private CancellationTokenSource? _cancellation;
    private string _output = "No command executed. Enter a command and click Run. No PTY is available.";
    public string Output { get => _output; private set => SetProperty(ref _output, value); }
    private bool _isRunning;
    public bool IsRunning { get => _isRunning; private set => SetProperty(ref _isRunning, value); }
    private string _command = "";
    public string Command { get => _command; set => SetProperty(ref _command, value); }

    public async Task RunAsync(string projectDir)
    {
        if (IsRunning || string.IsNullOrWhiteSpace(Command)) return;
        if (string.IsNullOrWhiteSpace(projectDir) || !Directory.Exists(projectDir))
        {
            Output = "Cannot run: a valid runtime project directory has not been reported.";
            return;
        }
        var start = new ProcessStartInfo
        {
            FileName = OperatingSystem.IsWindows() ? Environment.GetEnvironmentVariable("COMSPEC") ?? "cmd.exe" : "/bin/sh",
            WorkingDirectory = projectDir,
            UseShellExecute = false,
            RedirectStandardOutput = true,
            RedirectStandardError = true,
            CreateNoWindow = true
        };
        if (OperatingSystem.IsWindows())
        {
            start.ArgumentList.Add("/d");
            start.ArgumentList.Add("/s");
            start.ArgumentList.Add("/c");
        }
        else start.ArgumentList.Add("-c");
        start.ArgumentList.Add(Command);
        _cancellation = new CancellationTokenSource();
        var ct = _cancellation.Token;
        var process = new Process { StartInfo = start };
        _process = process;
        IsRunning = true;
        Output = $"Working directory: {projectDir}\n$ {Command}\n";
        try
        {
            process.Start();
            await Task.WhenAll(ReadAsync(process.StandardOutput, "", ct), ReadAsync(process.StandardError, "[stderr] ", ct), process.WaitForExitAsync(ct));
            await AppendAsync($"\nProcess exited with code {process.ExitCode}.\n");
        }
        catch (OperationCanceledException) { await AppendAsync("\nCommand stopped by user / GUI close.\n"); }
        catch (Exception ex) { await AppendAsync($"\nCommand failed ({ex.GetType().Name}).\n"); }
        finally
        {
            process.Dispose();
            if (_process == process) _process = null;
            _cancellation?.Dispose();
            _cancellation = null;
            IsRunning = false;
        }
    }

    private async Task ReadAsync(StreamReader reader, string prefix, CancellationToken ct)
    {
        while (await reader.ReadLineAsync(ct) is { } line) await AppendAsync(prefix + line + "\n");
    }

    private async Task AppendAsync(string text)
    {
        await Dispatcher.UIThread.InvokeAsync(() =>
        {
            var output = Output + text;
            Output = output.Length > 1_000_000 ? "[Earlier command output truncated]\n" + output[^900_000..] : output;
        });
    }

    public void Stop()
    {
        if (_process is not { } process) return;
        try { if (!process.HasExited) process.Kill(entireProcessTree: true); }
        catch (InvalidOperationException) { }
        catch (System.ComponentModel.Win32Exception) { }
        _cancellation?.Cancel();
    }

    public void Dispose() => Stop();
}
