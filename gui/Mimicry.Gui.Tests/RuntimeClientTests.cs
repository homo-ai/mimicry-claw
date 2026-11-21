using System;
using System.Collections.Generic;
using System.IO;
using System.Linq;
using System.Net;
using System.Net.Http;
using System.Net.Http.Headers;
using System.Text;
using System.Text.Json;
using System.Threading;
using System.Threading.Tasks;
using Mimicry.Gui.Models;
using Mimicry.Gui.Services;
using Xunit;

namespace Mimicry.Gui.Tests;

public sealed class RuntimeClientTests
{
    private const string Token = "offline-test-token";

    [Fact]
    public async Task SnapshotReadsKotlinCamelCaseAndNullableFields()
    {
        using var handler = new StubHandler((request, _) =>
        {
            Assert.Equal(HttpMethod.Get, request.Method);
            Assert.Equal("/v1/snapshot", request.RequestUri!.AbsolutePath);
            Assert.Equal("Bearer", request.Headers.Authorization!.Scheme);
            Assert.Equal(Token, request.Headers.Authorization.Parameter);
            return Task.FromResult(Json("""
                {"runtimeId":"runtime-1","startedAt":1700000000000,"projectDir":"C:/work",
                 "providerId":"anthropic","model":"test-model","permissionMode":"ask",
                 "sessions":[{"id":"s1","createdAt":10,"lastActiveAt":20,"messageCount":2,"projectDir":"C:/work"}],
                 "tasks":[{"id":"t1","sessionId":"s1","prompt":"hello","status":"WAITING_PERMISSION",
                   "providerId":"anthropic","model":"test-model","createdAt":10,"startedAt":11,"completedAt":null,
                   "result":null,"error":null,"turns":2,"inputTokens":9000000000,"outputTokens":8}],
                 "agents":[{"id":"a1","name":"primary","status":"working","currentTaskId":"t1"}],
                 "plan":{"planId":"p1","prompt":"hello","status":"running",
                   "steps":[{"title":"inspect","status":"running","detail":null}]},
                 "providers":[{"id":"anthropic","name":"Anthropic","configured":true,
                   "defaultProtocol":"messages","supportedProtocols":["messages","chat"]}],
                 "tools":[{"name":"read","description":"Read files","implemented":true}],
                 "pendingPermissions":[{"id":"r1","taskId":"t1","toolName":"read","target":"file",
                   "reason":"approval","scope":"tool","requestedAt":12}],
                 "lastEventSeq":9000000001,"runningTaskId":"t1","futureField":true}
                """));
        });
        using var client = new RuntimeClient(Info(), handler);

        var snapshot = await client.SnapshotAsync();

        Assert.Equal("runtime-1", snapshot.RuntimeId);
        Assert.Equal(1700000000000L, snapshot.StartedAt);
        Assert.Equal("ask", snapshot.PermissionMode);
        Assert.Equal(9000000001L, snapshot.LastEventSeq);
        Assert.Equal("s1", Assert.Single(snapshot.Sessions).Id);
        var task = Assert.Single(snapshot.Tasks);
        Assert.Equal("WAITING_PERMISSION", task.Status);
        Assert.Equal(9000000000L, task.InputTokens);
        Assert.Null(task.CompletedAt);
        Assert.Equal("t1", Assert.Single(snapshot.Agents).CurrentTaskId);
        Assert.Equal("inspect", Assert.Single(snapshot.Plan!.Steps).Title);
        Assert.Equal(2, Assert.Single(snapshot.Providers).SupportedProtocols.Count);
        Assert.True(Assert.Single(snapshot.Tools).Implemented);
        Assert.Equal(12L, Assert.Single(snapshot.PendingPermissions).RequestedAt);
    }

    [Fact]
    public async Task OmittedListsAreInitializedEmpty()
    {
        using var client = ClientReturning("{\"runtimeId\":\"runtime-1\"}");
        var snapshot = await client.SnapshotAsync();
        Assert.Empty(snapshot.Sessions);
        Assert.Empty(snapshot.Tasks);
        Assert.Empty(snapshot.Agents);
        Assert.Empty(snapshot.Providers);
        Assert.Empty(snapshot.Tools);
        Assert.Empty(snapshot.PendingPermissions);
        Assert.Empty(new PlanSnapshot().Steps);
        Assert.Empty(new ProviderInfo().SupportedProtocols);
        Assert.Empty(new SessionTranscript().Messages);
    }

    [Fact]
    public async Task SessionTranscriptPreservesToolMetadataAndSequence()
    {
        using var handler = new StubHandler((request, _) =>
        {
            Assert.Equal(HttpMethod.Get, request.Method);
            Assert.Equal("/v1/sessions/s1/messages", request.RequestUri!.AbsolutePath);
            return Task.FromResult(Json("""
                {"sessionId":"s1","messages":[
                 {"id":"m1","role":"user","text":"hello","timestamp":100,"isError":false},
                 {"id":"m2","role":"tool","text":"failed","timestamp":101,
                  "toolUseId":"u1","toolName":"read","isError":true}],"lastEventSeq":44}
                """));
        });
        using var client = new RuntimeClient(Info(), handler);
        var transcript = await client.SessionMessagesAsync("s1");
        Assert.Equal("s1", transcript.SessionId);
        Assert.Equal(44L, transcript.LastEventSeq);
        Assert.Null(transcript.Messages[0].ToolUseId);
        Assert.Equal(101L, transcript.Messages[1].Timestamp);
        Assert.Equal("u1", transcript.Messages[1].ToolUseId);
        Assert.Equal("read", transcript.Messages[1].ToolName);
        Assert.True(transcript.Messages[1].IsError);
    }

    [Fact]
    public async Task CreateAndResumeUseSessionEndpoints()
    {
        var paths = new List<string>();
        using var handler = new StubHandler((request, _) =>
        {
            Assert.Equal(HttpMethod.Post, request.Method);
            paths.Add(request.RequestUri!.AbsolutePath);
            return Task.FromResult(Json("{\"id\":\"s1\",\"createdAt\":1,\"lastActiveAt\":2,\"messageCount\":0,\"projectDir\":\"C:/work\"}"));
        });
        using var client = new RuntimeClient(Info(), handler);
        Assert.Equal("s1", (await client.CreateSessionAsync()).Id);
        Assert.Equal("s1", (await client.ResumeAsync("s1")).Id);
        Assert.Equal(new[] { "/v1/sessions", "/v1/sessions/s1/resume" }, paths);
    }

    [Theory]
    [InlineData(null)]
    [InlineData("s1")]
    public async Task SubmitUsesKotlinTaskRequest(string? sessionId)
    {
        using var handler = new StubHandler(async (request, cancellationToken) =>
        {
            Assert.Equal(HttpMethod.Post, request.Method);
            Assert.Equal("/v1/tasks", request.RequestUri!.AbsolutePath);
            Assert.Equal("application/json", request.Content!.Headers.ContentType!.MediaType);
            using var body = JsonDocument.Parse(await request.Content.ReadAsStringAsync(cancellationToken));
            Assert.Equal("hello", body.RootElement.GetProperty("prompt").GetString());
            Assert.Equal(sessionId, body.RootElement.GetProperty("sessionId").GetString());
            return Json("{\"id\":\"t1\",\"sessionId\":\"s1\",\"prompt\":\"hello\",\"status\":\"QUEUED\"}", HttpStatusCode.Accepted);
        });
        using var client = new RuntimeClient(Info(), handler);
        Assert.Equal("QUEUED", (await client.SubmitAsync("hello", sessionId)).Status);
    }

    [Theory]
    [InlineData(true)]
    [InlineData(false)]
    public async Task InterruptAndPermissionHonorLogicalBoolean(bool value)
    {
        using var handler = new StubHandler(async (request, cancellationToken) =>
        {
            Assert.Equal(HttpMethod.Post, request.Method);
            if (request.RequestUri!.AbsolutePath == "/v1/tasks/t1/interrupt")
                return Json("{\"interrupted\":" + value.ToString().ToLowerInvariant() + "}");
            Assert.Equal("/v1/permissions/r1", request.RequestUri.AbsolutePath);
            using var body = JsonDocument.Parse(await request.Content!.ReadAsStringAsync(cancellationToken));
            Assert.False(body.RootElement.GetProperty("approved").GetBoolean());
            return Json("{\"resolved\":" + value.ToString().ToLowerInvariant() + "}");
        });
        using var client = new RuntimeClient(Info(), handler);
        Assert.Equal(value, await client.InterruptAsync("t1"));
        Assert.Equal(value, await client.PermissionAsync("r1", false));
    }

    [Fact]
    public async Task ModelProviderAndShutdownUseProtocolShapes()
    {
        var paths = new List<string>();
        using var handler = new StubHandler(async (request, cancellationToken) =>
        {
            Assert.Equal(HttpMethod.Post, request.Method);
            var path = request.RequestUri!.AbsolutePath;
            paths.Add(path);
            if (path == "/v1/shutdown")
                return Json("{\"ok\":true}");
            using var body = JsonDocument.Parse(await request.Content!.ReadAsStringAsync(cancellationToken));
            if (path == "/v1/model")
            {
                Assert.Equal("new-model", body.RootElement.GetProperty("model").GetString());
                return Json("{\"runtimeId\":\"runtime-1\",\"model\":\"new-model\",\"providerId\":\"provider\"}");
            }
            Assert.Equal("provider", body.RootElement.GetProperty("providerId").GetString());
            return Json("{\"ok\":true,\"error\":null}");
        });
        using var client = new RuntimeClient(Info(), handler);
        await client.SetModelAsync("new-model");
        await client.SetProviderAsync("provider");
        await client.ShutdownAsync();
        Assert.Equal(new[] { "/v1/model", "/v1/provider", "/v1/shutdown" }, paths);
    }

    [Fact]
    public async Task SuccessfulHttpStatusDoesNotHideRejectedOperations()
    {
        using var client = ClientReturning("{\"ok\":false,\"error\":\"Rejected " + Token + "\"}");
        var providerError = await Assert.ThrowsAsync<InvalidOperationException>(() => client.SetProviderAsync("unknown"));
        Assert.Contains("Rejected", providerError.Message);
        Assert.DoesNotContain(Token, providerError.ToString());
        await Assert.ThrowsAsync<InvalidOperationException>(() => client.ShutdownAsync());
        await Assert.ThrowsAsync<InvalidOperationException>(() => client.SetModelAsync("model"));
    }

    [Theory]
    [InlineData("{}")]
    [InlineData("{\"interrupted\":\"false\"}")]
    public async Task InvalidBooleanResponsesAreRejected(string body)
    {
        using var client = ClientReturning(body);
        await Assert.ThrowsAsync<InvalidDataException>(() => client.InterruptAsync("t1"));
    }

    [Fact]
    public async Task UnauthorizedResponseReportsStatusAndRedactsToken()
    {
        using var client = ClientReturning("{\"error\":\"Invalid bearer token: " + Token + "\"}", HttpStatusCode.Unauthorized);
        var error = await Assert.ThrowsAsync<HttpRequestException>(() => client.SnapshotAsync());
        Assert.Equal(HttpStatusCode.Unauthorized, error.StatusCode);
        Assert.Contains("401", error.Message);
        Assert.Contains("Invalid bearer token", error.Message);
        Assert.DoesNotContain(Token, error.ToString());
    }

    [Fact]
    public async Task NonJsonErrorsAreRedacted()
    {
        using var client = ClientReturning("Failure: " + Token, HttpStatusCode.BadGateway);
        var error = await Assert.ThrowsAsync<HttpRequestException>(() => client.SnapshotAsync());
        Assert.Contains("502", error.Message);
        Assert.Contains("[redacted]", error.Message);
        Assert.DoesNotContain(Token, error.ToString());
    }

    [Fact]
    public async Task MissingSessionReportsServerError()
    {
        using var client = ClientReturning("{\"error\":\"Session not found: missing\"}", HttpStatusCode.NotFound);
        var error = await Assert.ThrowsAsync<HttpRequestException>(() => client.ResumeAsync("missing"));
        Assert.Equal(HttpStatusCode.NotFound, error.StatusCode);
        Assert.Contains("Session not found", error.Message);
    }

    [Fact]
    public async Task RedirectIsRejectedWithoutFollowingLocation()
    {
        var requests = 0;
        using var handler = new StubHandler((_, _) =>
        {
            requests++;
            var response = new HttpResponseMessage(HttpStatusCode.Found);
            response.Headers.Location = new Uri("http://192.0.2.1/token-collector");
            return Task.FromResult(response);
        });
        using var client = new RuntimeClient(Info(), handler);
        var error = await Assert.ThrowsAsync<HttpRequestException>(() => client.SnapshotAsync());
        Assert.Contains("redirects are not allowed", error.Message);
        Assert.DoesNotContain(Token, error.ToString());
        Assert.Equal(1, requests);
    }

    [Fact]
    public void InjectedTransportDisablesRedirectsAndProxies()
    {
        using var transport = new HttpClientHandler { AllowAutoRedirect = true, UseProxy = true };
        using var client = new RuntimeClient(Info(), transport);
        Assert.False(transport.AllowAutoRedirect);
        Assert.False(transport.UseProxy);
    }

    [Theory]
    [InlineData("192.0.2.1")]
    [InlineData("0.0.0.0")]
    [InlineData("::")]
    [InlineData("example.invalid")]
    [InlineData("localhost.example.invalid")]
    [InlineData("http://127.0.0.1")]
    [InlineData("127.0.0.1/unsafe")]
    [InlineData("127.0.0.1@192.0.2.1")]
    [InlineData(" localhost")]
    public void NonLoopbackMetadataIsRejectedEvenWithInjectedHandler(string host)
    {
        using var handler = new StubHandler((_, _) => throw new InvalidOperationException("Must not send."));
        Assert.Throws<ArgumentException>(() => new RuntimeClient(Info(host), handler));
    }

    [Theory]
    [InlineData("localhost", "127.0.0.1")]
    [InlineData("LOCALHOST", "127.0.0.1")]
    [InlineData("127.0.0.1", "127.0.0.1")]
    [InlineData("127.0.0.2", "127.0.0.2")]
    [InlineData("::1", "[::1]")]
    [InlineData("[::1]", "[::1]")]
    public void LoopbackMetadataHasSafeBaseUri(string host, string expectedHost)
    {
        var info = Info(host);
        Assert.Equal(expectedHost, info.BaseUri.Host);
        Assert.Equal("http", info.BaseUri.Scheme);
        Assert.Equal(12345, info.BaseUri.Port);
        using var document = JsonDocument.Parse(JsonSerializer.Serialize(info, new JsonSerializerOptions(JsonSerializerDefaults.Web)));
        Assert.False(document.RootElement.TryGetProperty("baseUri", out _));
    }

    [Theory]
    [InlineData(0)]
    [InlineData(-1)]
    [InlineData(65536)]
    public void InvalidPortsAreRejected(int port)
    {
        using var handler = new StubHandler((_, _) => throw new InvalidOperationException("Must not send."));
        Assert.Throws<ArgumentException>(() => new RuntimeClient(Info(port: port), handler));
    }

    [Theory]
    [InlineData("")]
    [InlineData(" ")]
    [InlineData("token\r\nInjected: value")]
    public void InvalidBearerTokensAreRejected(string token)
    {
        using var handler = new StubHandler((_, _) => throw new InvalidOperationException("Must not send."));
        Assert.Throws<ArgumentException>(() => new RuntimeClient(Info(token: token), handler));
    }

    [Fact]
    public async Task SessionIdentifiersAreEscapedAsSingleSegments()
    {
        using var handler = new StubHandler((request, _) =>
        {
            Assert.Equal("/v1/sessions/a%2Fb%3Fq%23fragment/messages", request.RequestUri!.AbsolutePath);
            Assert.Empty(request.RequestUri.Query);
            Assert.Empty(request.RequestUri.Fragment);
            return Task.FromResult(Json("{\"sessionId\":\"a/b?q#fragment\",\"messages\":[],\"lastEventSeq\":0}"));
        });
        using var client = new RuntimeClient(Info(), handler);
        await client.SessionMessagesAsync("a/b?q#fragment");
    }

    [Fact]
    public async Task ReadEventsHandlesMultilineCommentsRetryCrLfAndPartialUtf8Chunks()
    {
        var sse = "\uFEFF: heartbeat\r\nretry: 1000\r\nid: 7\r\nevent: future_event\r\n" +
                  "data: {\r\ndata:   \"seq\": 7,\r\ndata: \"timestamp\": 123,\r\n" +
                  "data:\r\ndata: \"event\": {\"type\":\"future_event\",\"text\":\"café\"}}\r\n\r\n";
        using var stream = new FragmentedStream(Encoding.UTF8.GetBytes(sse), 1);
        var events = await CollectAsync(RuntimeClient.ReadEventsAsync(stream));
        var envelope = Assert.Single(events);
        Assert.Equal(7L, envelope.Seq);
        Assert.Equal(123L, envelope.Timestamp);
        Assert.Equal("future_event", envelope.Event.GetProperty("type").GetString());
        Assert.Equal("café", envelope.Event.GetProperty("text").GetString());
        Assert.True(stream.CanRead);
    }

    [Fact]
    public async Task ReadEventsSuppressesReplayDuplicatesAndForwardsUnknownEvents()
    {
        var sse = Frame(3) + Frame(6) + Frame(6) + Frame(5) + Frame(9, "brand_new_event");
        using var stream = new MemoryStream(Encoding.UTF8.GetBytes(sse));
        var events = await CollectAsync(RuntimeClient.ReadEventsAsync(stream, since: 5));
        Assert.Equal(new long[] { 6, 9 }, events.Select(item => item.Seq));
        Assert.Equal("brand_new_event", events[1].Event.GetProperty("type").GetString());
    }

    [Fact]
    public async Task ReadEventsSupportsLoneCrAndDiscardsUnterminatedFinalFrame()
    {
        var sse = Frame(1).Replace("\n", "\r", StringComparison.Ordinal) + Frame(2).TrimEnd('\n');
        using var stream = new FragmentedStream(Encoding.UTF8.GetBytes(sse), 2);
        var events = await CollectAsync(RuntimeClient.ReadEventsAsync(stream));
        Assert.Equal(1L, Assert.Single(events).Seq);
    }

    [Fact]
    public async Task ReadEventsCancellationInterruptsPendingRead()
    {
        using var stream = new WaitingStream();
        using var cancellation = new CancellationTokenSource();
        var reading = CollectAsync(RuntimeClient.ReadEventsAsync(stream, cancellationToken: cancellation.Token));
        await stream.ReadStarted.Task.WaitAsync(TimeSpan.FromSeconds(5));
        cancellation.Cancel();
        await Assert.ThrowsAnyAsync<OperationCanceledException>(() => reading);
    }

    [Fact]
    public async Task EventsUsesAuthenticatedSseSinceAndDoesNotReconnectAtEof()
    {
        var requests = 0;
        using var handler = new StubHandler((request, _) =>
        {
            requests++;
            Assert.Equal("/v1/events", request.RequestUri!.AbsolutePath);
            Assert.Equal("?since=5", request.RequestUri.Query);
            Assert.Equal(Token, request.Headers.Authorization!.Parameter);
            Assert.Contains(request.Headers.Accept, header => header.MediaType == "text/event-stream");
            return Task.FromResult(Sse(new FragmentedStream(Encoding.UTF8.GetBytes(Frame(4) + Frame(6) + Frame(6)), 2)));
        });
        using var client = new RuntimeClient(Info(), handler);
        var events = await CollectAsync(client.EventsAsync(5));
        Assert.Equal(6L, Assert.Single(events).Seq);
        Assert.Equal(1, requests);
    }

    [Fact]
    public async Task EventsCancellationInterruptsPendingNetworkBodyRead()
    {
        using var stream = new WaitingStream();
        using var handler = new StubHandler((_, _) => Task.FromResult(Sse(stream)));
        using var client = new RuntimeClient(Info(), handler);
        using var cancellation = new CancellationTokenSource();
        var reading = CollectAsync(client.EventsAsync(0, cancellation.Token));
        await stream.ReadStarted.Task.WaitAsync(TimeSpan.FromSeconds(5));
        cancellation.Cancel();
        await Assert.ThrowsAnyAsync<OperationCanceledException>(() => reading);
    }

    [Fact]
    public async Task DisposingClientCancelsActiveEventStream()
    {
        using var stream = new WaitingStream();
        using var handler = new StubHandler((_, _) => Task.FromResult(Sse(stream)));
        using var client = new RuntimeClient(Info(), handler);
        var reading = CollectAsync(client.EventsAsync(0));
        await stream.ReadStarted.Task.WaitAsync(TimeSpan.FromSeconds(5));
        client.Dispose();
        await Assert.ThrowsAnyAsync<OperationCanceledException>(() => reading);
    }

    [Fact]
    public async Task OrdinaryRequestsHonorCallerCancellation()
    {
        var entered = new TaskCompletionSource<bool>(TaskCreationOptions.RunContinuationsAsynchronously);
        using var handler = new StubHandler(async (_, cancellationToken) =>
        {
            entered.SetResult(true);
            await Task.Delay(Timeout.InfiniteTimeSpan, cancellationToken);
            throw new InvalidOperationException("Unreachable.");
        });
        using var client = new RuntimeClient(Info(), handler);
        using var cancellation = new CancellationTokenSource();
        var request = client.SnapshotAsync(cancellation.Token);
        await entered.Task.WaitAsync(TimeSpan.FromSeconds(5));
        cancellation.Cancel();
        await Assert.ThrowsAnyAsync<OperationCanceledException>(() => request);
    }

    [Fact]
    public async Task SseHttpErrorsAreMeaningfulAndRedacted()
    {
        using var client = ClientReturning("{\"error\":\"Unauthorized " + Token + "\"}", HttpStatusCode.Unauthorized);
        var error = await Assert.ThrowsAsync<HttpRequestException>(() => CollectAsync(client.EventsAsync(0)));
        Assert.Contains("401", error.Message);
        Assert.DoesNotContain(Token, error.ToString());
    }

    [Fact]
    public async Task MalformedJsonAndEventEnvelopesDoNotExposePayloads()
    {
        using var client = ClientReturning("not JSON " + Token);
        var error = await Assert.ThrowsAsync<InvalidDataException>(() => client.SnapshotAsync());
        Assert.DoesNotContain(Token, error.ToString());
        using var stream = new MemoryStream(Encoding.UTF8.GetBytes("data: invalid " + Token + "\n\n"));
        var eventError = await Assert.ThrowsAsync<InvalidDataException>(() => CollectAsync(RuntimeClient.ReadEventsAsync(stream)));
        Assert.DoesNotContain(Token, eventError.ToString());
    }

    [Fact]
    public async Task LocatorMissingMetadataExplainsManualStartupWithoutCreatingFiles()
    {
        var path = Path.Combine(Path.GetTempPath(), "mimicry-missing-" + Guid.NewGuid().ToString("N") + ".json");
        var error = await Assert.ThrowsAsync<FileNotFoundException>(() => RuntimeLocator.ConnectAsync(path));
        Assert.Contains("missing", error.Message);
        Assert.Contains("mimicry serve", error.Message);
        Assert.Contains("mimicry gui", error.Message);
        Assert.False(File.Exists(path));
    }

    [Theory]
    [InlineData("not JSON offline-test-token")]
    [InlineData("{}")]
    [InlineData("{\"host\":\"192.0.2.1\",\"port\":12345,\"token\":\"offline-test-token\",\"runtimeId\":\"runtime-1\"}")]
    public async Task LocatorInvalidMetadataIsRejectedBeforeNetworkAccess(string body)
    {
        var path = Path.Combine(Path.GetTempPath(), "mimicry-invalid-" + Guid.NewGuid().ToString("N") + ".json");
        try
        {
            await File.WriteAllTextAsync(path, body);
            var error = await Assert.ThrowsAsync<InvalidDataException>(() => RuntimeLocator.ConnectAsync(path));
            Assert.Contains("invalid", error.Message);
            Assert.Contains("mimicry serve", error.Message);
            Assert.DoesNotContain(Token, error.ToString());
        }
        finally
        {
            File.Delete(path);
        }
    }

    [Fact]
    public async Task LocatorPreservesCallerCancellation()
    {
        using var cancellation = new CancellationTokenSource();
        cancellation.Cancel();
        await Assert.ThrowsAnyAsync<OperationCanceledException>(() => RuntimeLocator.ConnectAsync(cancellationToken: cancellation.Token));
    }

    private static RuntimeInfo Info(string host = "127.0.0.1", int port = 12345, string token = Token) => new()
    {
        Host = host,
        Port = port,
        Token = token,
        Pid = 123,
        RuntimeId = "runtime-1",
        ProjectDir = "C:/work",
        StartedAt = 10,
    };

    private static RuntimeClient ClientReturning(string body, HttpStatusCode status = HttpStatusCode.OK) =>
        new(Info(), new StubHandler((_, _) => Task.FromResult(Json(body, status))));

    private static HttpResponseMessage Json(string body, HttpStatusCode status = HttpStatusCode.OK) => new(status)
    {
        Content = new StringContent(body, Encoding.UTF8, "application/json"),
    };

    private static HttpResponseMessage Sse(Stream stream)
    {
        var content = new StreamContent(stream);
        content.Headers.ContentType = new MediaTypeHeaderValue("text/event-stream");
        return new HttpResponseMessage(HttpStatusCode.OK) { Content = content };
    }

    private static string Frame(long seq, string eventType = "message") =>
        $"id: {seq}\nevent: ignored-name\ndata: {{\"seq\":{seq},\"timestamp\":100,\"event\":{{\"type\":\"{eventType}\"}}}}\n\n";

    private static async Task<List<EventEnvelope>> CollectAsync(IAsyncEnumerable<EventEnvelope> source)
    {
        var events = new List<EventEnvelope>();
        await foreach (var envelope in source)
            events.Add(envelope);
        return events;
    }

    private sealed class StubHandler : HttpMessageHandler
    {
        private readonly Func<HttpRequestMessage, CancellationToken, Task<HttpResponseMessage>> respond;

        public StubHandler(Func<HttpRequestMessage, CancellationToken, Task<HttpResponseMessage>> respond) => this.respond = respond;

        protected override Task<HttpResponseMessage> SendAsync(HttpRequestMessage request, CancellationToken cancellationToken) =>
            respond(request, cancellationToken);
    }

    private sealed class FragmentedStream : MemoryStream
    {
        private readonly int chunkSize;

        public FragmentedStream(byte[] bytes, int chunkSize) : base(bytes) => this.chunkSize = chunkSize;

        public override ValueTask<int> ReadAsync(Memory<byte> buffer, CancellationToken cancellationToken = default) =>
            base.ReadAsync(buffer[..Math.Min(buffer.Length, chunkSize)], cancellationToken);

        public override Task<int> ReadAsync(byte[] buffer, int offset, int count, CancellationToken cancellationToken) =>
            base.ReadAsync(buffer, offset, Math.Min(count, chunkSize), cancellationToken);
    }

    private sealed class WaitingStream : Stream
    {
        public TaskCompletionSource<bool> ReadStarted { get; } = new(TaskCreationOptions.RunContinuationsAsynchronously);
        public override bool CanRead => true;
        public override bool CanSeek => false;
        public override bool CanWrite => false;
        public override long Length => throw new NotSupportedException();
        public override long Position { get => throw new NotSupportedException(); set => throw new NotSupportedException(); }

        public override async ValueTask<int> ReadAsync(Memory<byte> buffer, CancellationToken cancellationToken = default)
        {
            ReadStarted.TrySetResult(true);
            await Task.Delay(Timeout.InfiniteTimeSpan, cancellationToken);
            return 0;
        }

        public override Task<int> ReadAsync(byte[] buffer, int offset, int count, CancellationToken cancellationToken) =>
            ReadAsync(buffer.AsMemory(offset, count), cancellationToken).AsTask();

        public override int Read(byte[] buffer, int offset, int count) => throw new NotSupportedException();
        public override void Flush() => throw new NotSupportedException();
        public override long Seek(long offset, SeekOrigin origin) => throw new NotSupportedException();
        public override void SetLength(long value) => throw new NotSupportedException();
        public override void Write(byte[] buffer, int offset, int count) => throw new NotSupportedException();
    }
}
