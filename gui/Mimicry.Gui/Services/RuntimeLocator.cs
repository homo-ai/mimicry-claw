using System;
using System.IO;
using System.Text.Json;
using System.Threading;
using System.Threading.Tasks;
using Mimicry.Gui.Models;

namespace Mimicry.Gui.Services;

public static class RuntimeLocator
{
    private const string StartupHint = "Start the runtime with 'mimicry serve' or launch 'mimicry gui', then connect again.";

    public static async Task<RuntimeClient> ConnectAsync(string? runtimeFile = null, CancellationToken cancellationToken = default)
    {
        cancellationToken.ThrowIfCancellationRequested();
        var path = runtimeFile;
        if (string.IsNullOrWhiteSpace(path))
            path = Environment.GetEnvironmentVariable("MIMICRY_RUNTIME_FILE");
        if (string.IsNullOrWhiteSpace(path))
            path = Path.Combine(Environment.GetFolderPath(Environment.SpecialFolder.UserProfile), ".mimicry", "runtime.json");

        RuntimeInfo info;
        try
        {
            var json = await File.ReadAllTextAsync(path, cancellationToken).ConfigureAwait(false);
            info = JsonSerializer.Deserialize<RuntimeInfo>(json, new JsonSerializerOptions(JsonSerializerDefaults.Web))
                ?? throw new JsonException();
        }
        catch (FileNotFoundException)
        {
            throw new FileNotFoundException("Mimicry runtime metadata is missing. " + StartupHint, path);
        }
        catch (DirectoryNotFoundException)
        {
            throw new FileNotFoundException("Mimicry runtime metadata is missing. " + StartupHint, path);
        }
        catch (JsonException)
        {
            throw new InvalidDataException("Mimicry runtime metadata is invalid or stale. " + StartupHint);
        }
        catch (UnauthorizedAccessException)
        {
            throw new IOException("Mimicry runtime metadata cannot be read. Check the runtime file permissions. " + StartupHint);
        }
        catch (IOException)
        {
            throw new IOException("Mimicry runtime metadata cannot be read. " + StartupHint);
        }

        RuntimeClient client;
        try
        {
            if (string.IsNullOrWhiteSpace(info.RuntimeId))
                throw new ArgumentException("Runtime metadata has no runtimeId.");
            client = new RuntimeClient(info);
        }
        catch (ArgumentException)
        {
            throw new InvalidDataException("Mimicry runtime metadata is invalid: a loopback host, valid port, token, and runtimeId are required. " + StartupHint);
        }

        try
        {
            await client.VerifyHealthAsync(cancellationToken).ConfigureAwait(false);
            return client;
        }
        catch (OperationCanceledException) when (cancellationToken.IsCancellationRequested)
        {
            client.Dispose();
            throw;
        }
        catch (Exception error) when (error is System.Net.Http.HttpRequestException or InvalidDataException or IOException or InvalidOperationException or OperationCanceledException)
        {
            client.Dispose();
            throw new InvalidOperationException("Mimicry runtime metadata is stale or the runtime is unavailable: " + error.Message + " " + StartupHint);
        }
    }
}
