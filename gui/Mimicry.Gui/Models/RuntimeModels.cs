using System;
using System.Collections.Generic;
using System.Net;
using System.Text.Json;
using System.Text.Json.Serialization;

namespace Mimicry.Gui.Models;

public sealed class RuntimeInfo
{
    public string Host { get; init; } = string.Empty;
    public int Port { get; init; }
    public string Token { get; init; } = string.Empty;
    public long Pid { get; init; }
    public string RuntimeId { get; init; } = string.Empty;
    public string ProjectDir { get; init; } = string.Empty;
    public long StartedAt { get; init; }

    [JsonIgnore]
    public Uri BaseUri
    {
        get
        {
            if (string.IsNullOrWhiteSpace(Host) || Host != Host.Trim() || Port is < 1 or > 65535)
                throw new ArgumentException("Runtime host and port must identify a loopback endpoint.");

            var host = Host;
            if (string.Equals(host, "localhost", StringComparison.OrdinalIgnoreCase))
                host = "127.0.0.1";
            else
            {
                if (host.StartsWith("[", StringComparison.Ordinal) && host.EndsWith("]", StringComparison.Ordinal))
                    host = host[1..^1];
                if (!IPAddress.TryParse(host, out var address) || !IPAddress.IsLoopback(address))
                    throw new ArgumentException("Runtime host must be localhost or a loopback IP address.");
                host = address.ToString();
            }

            return new UriBuilder(Uri.UriSchemeHttp, host, Port).Uri;
        }
    }
}

public sealed class AppSnapshot
{
    public string RuntimeId { get; init; } = string.Empty;
    public long StartedAt { get; init; }
    public string ProjectDir { get; init; } = string.Empty;
    public string ProviderId { get; init; } = string.Empty;
    public string Model { get; init; } = string.Empty;
    public string PermissionMode { get; init; } = string.Empty;
    public List<SessionSummary> Sessions { get; init; } = new();
    public List<TaskInfo> Tasks { get; init; } = new();
    public List<AgentInfo> Agents { get; init; } = new();
    public PlanSnapshot? Plan { get; init; }
    public List<ProviderInfo> Providers { get; init; } = new();
    public List<ToolInfo> Tools { get; init; } = new();
    public List<PermissionRequestInfo> PendingPermissions { get; init; } = new();
    public long LastEventSeq { get; init; }
    public string? RunningTaskId { get; init; }
}

public sealed class SessionSummary
{
    public string Id { get; init; } = string.Empty;
    public long CreatedAt { get; init; }
    public long LastActiveAt { get; init; }
    public int MessageCount { get; init; }
    public string ProjectDir { get; init; } = string.Empty;
}

public sealed class TaskInfo
{
    public string Id { get; init; } = string.Empty;
    public string SessionId { get; init; } = string.Empty;
    public string Prompt { get; init; } = string.Empty;
    public string Status { get; init; } = string.Empty;
    public string ProviderId { get; init; } = string.Empty;
    public string Model { get; init; } = string.Empty;
    public long CreatedAt { get; init; }
    public long? StartedAt { get; init; }
    public long? CompletedAt { get; init; }
    public string? Result { get; init; }
    public string? Error { get; init; }
    public int Turns { get; init; }
    public long InputTokens { get; init; }
    public long OutputTokens { get; init; }
}

public sealed class PermissionRequestInfo
{
    public string Id { get; init; } = string.Empty;
    public string TaskId { get; init; } = string.Empty;
    public string ToolName { get; init; } = string.Empty;
    public string Target { get; init; } = string.Empty;
    public string Reason { get; init; } = string.Empty;
    public string Scope { get; init; } = "tool";
    public long RequestedAt { get; init; }
}

public sealed class ProviderInfo
{
    public string Id { get; init; } = string.Empty;
    public string Name { get; init; } = string.Empty;
    public bool Configured { get; init; }
    public string DefaultProtocol { get; init; } = string.Empty;
    public List<string> SupportedProtocols { get; init; } = new();
}

public sealed class ToolInfo
{
    public string Name { get; init; } = string.Empty;
    public string Description { get; init; } = string.Empty;
    public bool Implemented { get; init; }
}

public sealed class AgentInfo
{
    public string Id { get; init; } = string.Empty;
    public string Name { get; init; } = string.Empty;
    public string Status { get; init; } = string.Empty;
    public string? CurrentTaskId { get; init; }
}

public sealed class PlanSnapshot
{
    public string PlanId { get; init; } = string.Empty;
    public string Prompt { get; init; } = string.Empty;
    public string Status { get; init; } = string.Empty;
    public List<PlanStepInfo> Steps { get; init; } = new();
}

public sealed class PlanStepInfo
{
    public string Title { get; init; } = string.Empty;
    public string Status { get; init; } = "pending";
    public string? Detail { get; init; }
}

public sealed class TranscriptMessage
{
    public string Id { get; init; } = string.Empty;
    public string Role { get; init; } = string.Empty;
    public string Text { get; init; } = string.Empty;
    public long Timestamp { get; init; }
    public string? ToolUseId { get; init; }
    public string? ToolName { get; init; }
    public bool IsError { get; init; }
}

public sealed class SessionTranscript
{
    public string SessionId { get; init; } = string.Empty;
    public List<TranscriptMessage> Messages { get; init; } = new();
    public long LastEventSeq { get; init; }
}

public sealed class EventEnvelope
{
    public long Seq { get; init; }
    public long Timestamp { get; init; }
    public JsonElement Event { get; init; }
}
