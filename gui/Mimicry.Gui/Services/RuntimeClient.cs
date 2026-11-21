using System;
using System.Collections.Generic;
using System.Globalization;
using System.IO;
using System.Net.Http;
using System.Net.Http.Headers;
using System.Runtime.CompilerServices;
using System.Text;
using System.Text.Json;
using System.Text.RegularExpressions;
using System.Threading;
using System.Threading.Tasks;
using Mimicry.Gui.Models;

namespace Mimicry.Gui.Services;

public sealed class RuntimeClient : IDisposable
{
    private static readonly JsonSerializerOptions JsonOptions = new(JsonSerializerDefaults.Web);
    private static readonly TimeSpan RequestTimeout = TimeSpan.FromSeconds(30);
    private readonly HttpClient http;
    private readonly CancellationTokenSource lifetime = new();
    private int disposed;

    public RuntimeInfo Info { get; }

    public RuntimeClient(RuntimeInfo info, HttpMessageHandler? handler = null)
    {
        ArgumentNullException.ThrowIfNull(info);
        var baseUri = info.BaseUri;
        if (string.IsNullOrEmpty(info.Token) || !Regex.IsMatch(info.Token, @"\A[A-Za-z0-9\-._~+/]+=*\z"))
            throw new ArgumentException("Runtime metadata must contain a valid bearer token.", nameof(info));

        Info = info;
        handler ??= new SocketsHttpHandler { ConnectTimeout = TimeSpan.FromSeconds(3) };
        DisableRedirectsAndProxy(handler);
        http = new HttpClient(handler, disposeHandler: true)
        {
            BaseAddress = baseUri,
            Timeout = Timeout.InfiniteTimeSpan,
        };
        http.DefaultRequestHeaders.Authorization = new AuthenticationHeaderValue("Bearer", info.Token);
    }

    public Task<AppSnapshot> SnapshotAsync(CancellationToken cancellationToken = default) =>
        SendJsonAsync<AppSnapshot>(HttpMethod.Get, "v1/snapshot", null, cancellationToken);

    public Task<SessionTranscript> SessionMessagesAsync(string sessionId, CancellationToken cancellationToken = default) =>
        SendJsonAsync<SessionTranscript>(HttpMethod.Get, $"v1/sessions/{Segment(sessionId)}/messages", null, cancellationToken);

    public Task<SessionSummary> CreateSessionAsync(CancellationToken cancellationToken = default) =>
        SendJsonAsync<SessionSummary>(HttpMethod.Post, "v1/sessions", null, cancellationToken);

    public Task<SessionSummary> ResumeAsync(string sessionId, CancellationToken cancellationToken = default) =>
        SendJsonAsync<SessionSummary>(HttpMethod.Post, $"v1/sessions/{Segment(sessionId)}/resume", null, cancellationToken);

    public Task<TaskInfo> SubmitAsync(string prompt, string? sessionId, CancellationToken cancellationToken = default)
    {
        ArgumentException.ThrowIfNullOrWhiteSpace(prompt);
        return SendJsonAsync<TaskInfo>(HttpMethod.Post, "v1/tasks", new { prompt, sessionId }, cancellationToken);
    }

    public async Task<bool> InterruptAsync(string taskId, CancellationToken cancellationToken = default)
    {
        var result = await SendJsonAsync<JsonElement>(HttpMethod.Post, $"v1/tasks/{Segment(taskId)}/interrupt", null, cancellationToken).ConfigureAwait(false);
        return ReadBoolean(result, "interrupted");
    }

    public async Task<bool> PermissionAsync(string id, bool approved, CancellationToken cancellationToken = default)
    {
        var result = await SendJsonAsync<JsonElement>(HttpMethod.Post, $"v1/permissions/{Segment(id)}", new { approved }, cancellationToken).ConfigureAwait(false);
        return ReadBoolean(result, "resolved");
    }

    public async Task SetModelAsync(string model, CancellationToken cancellationToken = default)
    {
        ArgumentException.ThrowIfNullOrWhiteSpace(model);
        var result = await SendJsonAsync<JsonElement>(HttpMethod.Post, "v1/model", new { model }, cancellationToken).ConfigureAwait(false);
        EnsureLogicalSuccess(result);
    }

    public async Task SetProviderAsync(string providerId, CancellationToken cancellationToken = default)
    {
        ArgumentException.ThrowIfNullOrWhiteSpace(providerId);
        var result = await SendJsonAsync<JsonElement>(HttpMethod.Post, "v1/provider", new { providerId }, cancellationToken).ConfigureAwait(false);
        EnsureLogicalSuccess(result, requireOk: true);
    }

    public async Task ShutdownAsync(CancellationToken cancellationToken = default)
    {
        var result = await SendJsonAsync<JsonElement>(HttpMethod.Post, "v1/shutdown", null, cancellationToken).ConfigureAwait(false);
        EnsureLogicalSuccess(result, requireOk: true);
    }

    public async IAsyncEnumerable<EventEnvelope> EventsAsync(long since, [EnumeratorCancellation] CancellationToken cancellationToken = default)
    {
        using var cancellation = CancellationTokenSource.CreateLinkedTokenSource(cancellationToken, lifetime.Token);
        using var request = new HttpRequestMessage(HttpMethod.Get, "v1/events?since=" + since.ToString(CultureInfo.InvariantCulture));
        request.Headers.Accept.Add(new MediaTypeWithQualityHeaderValue("text/event-stream"));
        using var response = await http.SendAsync(request, HttpCompletionOption.ResponseHeadersRead, cancellation.Token).ConfigureAwait(false);
        await EnsureSuccessAsync(response, cancellation.Token).ConfigureAwait(false);
        if (!string.Equals(response.Content.Headers.ContentType?.MediaType, "text/event-stream", StringComparison.OrdinalIgnoreCase))
            throw new InvalidDataException("Runtime event response must be text/event-stream.");

        using var stream = await response.Content.ReadAsStreamAsync(cancellation.Token).ConfigureAwait(false);
        await foreach (var envelope in ReadEventsAsync(stream, since, cancellation.Token).ConfigureAwait(false))
            yield return envelope;
    }

    public static async IAsyncEnumerable<EventEnvelope> ReadEventsAsync(
        Stream stream, long since = 0, [EnumeratorCancellation] CancellationToken cancellationToken = default)
    {
        ArgumentNullException.ThrowIfNull(stream);
        using var reader = new StreamReader(stream, Encoding.UTF8, detectEncodingFromByteOrderMarks: true, bufferSize: 4096, leaveOpen: true);
        var data = new StringBuilder();
        var lastSeq = since;
        while (true)
        {
            cancellationToken.ThrowIfCancellationRequested();
            var line = await reader.ReadLineAsync(cancellationToken).ConfigureAwait(false);
            if (line is null)
                yield break; // SSE requires a blank line to dispatch; an incomplete final event is discarded.

            if (line.Length == 0)
            {
                if (data.Length == 0)
                    continue;
                data.Length--; // Remove only the newline appended after the last data field.
                var envelope = ParseEnvelope(data.ToString());
                data.Clear();
                if (envelope.Seq <= lastSeq)
                    continue;
                lastSeq = envelope.Seq;
                yield return envelope;
                continue;
            }

            if (line[0] == ':')
                continue;
            var colon = line.IndexOf(':');
            var field = colon < 0 ? line : line[..colon];
            if (field != "data")
                continue; // id, event and retry do not change envelope sequencing or trigger reconnects.
            var value = colon < 0 ? string.Empty : line[(colon + 1)..];
            if (value.StartsWith(" ", StringComparison.Ordinal))
                value = value[1..];
            data.Append(value).Append('\n');
        }
    }

    internal async Task VerifyHealthAsync(CancellationToken cancellationToken)
    {
        var health = await SendJsonAsync<JsonElement>(HttpMethod.Get, "health", null, cancellationToken).ConfigureAwait(false);
        if (!ReadBoolean(health, "ok") ||
            !health.TryGetProperty("runtimeId", out var runtimeId) ||
            runtimeId.ValueKind != JsonValueKind.String ||
            !string.Equals(runtimeId.GetString(), Info.RuntimeId, StringComparison.Ordinal))
            throw new InvalidOperationException("Runtime metadata is stale: health runtimeId does not match.");
    }

    public void Dispose()
    {
        if (Interlocked.Exchange(ref disposed, 1) != 0)
            return;
        lifetime.Cancel();
        http.Dispose();
        lifetime.Dispose();
    }

    private static void DisableRedirectsAndProxy(HttpMessageHandler handler)
    {
        while (handler is DelegatingHandler delegating && delegating.InnerHandler is not null)
            handler = delegating.InnerHandler;
        if (handler is HttpClientHandler clientHandler)
        {
            clientHandler.AllowAutoRedirect = false;
            clientHandler.UseProxy = false;
        }
        else if (handler is SocketsHttpHandler socketsHandler)
        {
            socketsHandler.AllowAutoRedirect = false;
            socketsHandler.UseProxy = false;
        }
    }

    private async Task<T> SendJsonAsync<T>(HttpMethod method, string path, object? body, CancellationToken cancellationToken)
    {
        using var cancellation = CancellationTokenSource.CreateLinkedTokenSource(cancellationToken, lifetime.Token);
        cancellation.CancelAfter(RequestTimeout);
        using var request = new HttpRequestMessage(method, path);
        request.Headers.Accept.Add(new MediaTypeWithQualityHeaderValue("application/json"));
        if (body is not null)
            request.Content = new StringContent(JsonSerializer.Serialize(body, JsonOptions), Encoding.UTF8, "application/json");
        using var response = await http.SendAsync(request, HttpCompletionOption.ResponseHeadersRead, cancellation.Token).ConfigureAwait(false);
        await EnsureSuccessAsync(response, cancellation.Token).ConfigureAwait(false);
        using var stream = await response.Content.ReadAsStreamAsync(cancellation.Token).ConfigureAwait(false);
        try
        {
            var result = await JsonSerializer.DeserializeAsync<T>(stream, JsonOptions, cancellation.Token).ConfigureAwait(false);
            if (result is null)
                throw new InvalidDataException("Runtime returned an empty JSON response.");
            return result;
        }
        catch (JsonException)
        {
            throw new InvalidDataException("Runtime returned an invalid JSON response.");
        }
    }

    private async Task EnsureSuccessAsync(HttpResponseMessage response, CancellationToken cancellationToken)
    {
        var status = (int)response.StatusCode;
        if (status is >= 300 and < 400)
            throw new HttpRequestException($"Runtime request failed (HTTP {status}): redirects are not allowed.", null, response.StatusCode);
        if (response.IsSuccessStatusCode)
            return;

        // Bound diagnostic reads even for streaming endpoints with an unbounded success lifetime.
        using var cancellation = CancellationTokenSource.CreateLinkedTokenSource(cancellationToken);
        cancellation.CancelAfter(RequestTimeout);
        using var stream = await response.Content.ReadAsStreamAsync(cancellation.Token).ConfigureAwait(false);
        using var reader = new StreamReader(stream, Encoding.UTF8);
        var buffer = new char[4096];
        var length = 0;
        while (length < buffer.Length)
        {
            var count = await reader.ReadAsync(buffer.AsMemory(length), cancellation.Token).ConfigureAwait(false);
            if (count == 0)
                break;
            length += count;
        }
        var message = new string(buffer, 0, length);
        try
        {
            using var document = JsonDocument.Parse(message);
            if (document.RootElement.ValueKind == JsonValueKind.Object &&
                document.RootElement.TryGetProperty("error", out var error) && error.ValueKind == JsonValueKind.String)
                message = error.GetString() ?? string.Empty;
        }
        catch (JsonException)
        {
            // Non-JSON diagnostics are still useful, but are redacted below.
        }
        message = Redact(message);
        if (string.IsNullOrWhiteSpace(message))
            message = "No error detail was supplied.";
        throw new HttpRequestException($"Runtime request failed (HTTP {status}): {message}", null, response.StatusCode);
    }

    private void EnsureLogicalSuccess(JsonElement result, bool requireOk = false)
    {
        if (result.ValueKind != JsonValueKind.Object)
            throw new InvalidDataException("Runtime returned an invalid operation response.");
        if (!result.TryGetProperty("ok", out _))
        {
            if (requireOk)
                throw new InvalidDataException("Runtime response is missing the 'ok' flag.");
            return; // /v1/model returns RuntimeConfig rather than OkResponse.
        }
        if (ReadBoolean(result, "ok"))
            return;
        var detail = result.TryGetProperty("error", out var error) && error.ValueKind == JsonValueKind.String
            ? error.GetString() : "The operation was rejected.";
        throw new InvalidOperationException("Runtime operation failed: " + Redact(detail ?? "The operation was rejected."));
    }

    private static bool ReadBoolean(JsonElement result, string name)
    {
        if (result.ValueKind != JsonValueKind.Object || !result.TryGetProperty(name, out var value) ||
            value.ValueKind is not (JsonValueKind.True or JsonValueKind.False))
            throw new InvalidDataException($"Runtime response is missing the '{name}' boolean flag.");
        return value.GetBoolean();
    }

    private static EventEnvelope ParseEnvelope(string data)
    {
        try
        {
            var envelope = JsonSerializer.Deserialize<EventEnvelope>(data, JsonOptions);
            if (envelope is null || envelope.Event.ValueKind != JsonValueKind.Object)
                throw new InvalidDataException("Runtime returned an invalid event envelope.");
            return envelope;
        }
        catch (JsonException)
        {
            throw new InvalidDataException("Runtime returned an invalid event envelope.");
        }
    }

    private string Redact(string message)
    {
        var redacted = message.Replace(Info.Token, "[redacted]", StringComparison.Ordinal);
        return redacted.Length <= 500 ? redacted : redacted[..500];
    }

    private static string Segment(string value)
    {
        ArgumentException.ThrowIfNullOrWhiteSpace(value);
        if (value is "." or "..")
            throw new ArgumentException("A runtime identifier cannot be a relative path.", nameof(value));
        return Uri.EscapeDataString(value);
    }
}
