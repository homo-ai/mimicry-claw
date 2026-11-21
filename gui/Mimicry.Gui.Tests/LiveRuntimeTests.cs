using System.Text.Json;
using Mimicry.Gui.Models;
using Mimicry.Gui.Services;
using Xunit;

namespace Mimicry.Gui.Tests;

public class LiveRuntimeTests
{
    [Fact]
    public async Task LiveRuntime_JavaServerAndDesktopClientShareSessionsAndSse()
    {
        var file = Environment.GetEnvironmentVariable("MIMICRY_TEST_RUNTIME_FILE");
        if (string.IsNullOrWhiteSpace(file)) return; // Dedicated CI integration pass supplies this.
        using var timeout = new CancellationTokenSource(TimeSpan.FromSeconds(15));
        using var client = await RuntimeLocator.ConnectAsync(file, timeout.Token);
        var snapshot = await client.SnapshotAsync(timeout.Token);
        Assert.NotEmpty(snapshot.RuntimeId);
        Assert.Contains(snapshot.Tools, tool => tool.Name == "Write" && tool.Implemented);
        var session = await client.CreateSessionAsync(timeout.Token);
        Assert.Equal(0, session.MessageCount);
        var transcript = await client.SessionMessagesAsync(session.Id, timeout.Token);
        Assert.Empty(transcript.Messages);
        var resumed = await client.ResumeAsync(session.Id, timeout.Token);
        Assert.Equal(session.Id, resumed.Id);
        var events = client.EventsAsync(snapshot.LastEventSeq, timeout.Token);
        var found = false;
        await foreach (var envelope in events)
        {
            if (envelope.Event.GetProperty("type").GetString() == "session_created" &&
                envelope.Event.GetProperty("sessionId").GetString() == session.Id)
            { found = true; break; }
        }
        Assert.True(found, "Expected real Kotlin SSE session_created event");
        var refreshed = await client.SnapshotAsync(timeout.Token);
        Assert.Contains(refreshed.Sessions, item => item.Id == session.Id);
    }
}
