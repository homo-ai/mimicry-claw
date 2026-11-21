using System.Collections.ObjectModel;
using System.Text.Json;
using Avalonia.Threading;
using CommunityToolkit.Mvvm.ComponentModel;
using CommunityToolkit.Mvvm.Input;
using Mimicry.Gui.Models;
using Mimicry.Gui.Services;

namespace Mimicry.Gui.ViewModels;

public sealed class MainViewModel : ObservableObject, IDisposable
{
    private sealed class SessionState
    {
        public List<MessageItem> Messages { get; } = new();
        public List<ToolActivityItem> Tools { get; } = new();
        public Dictionary<string, long> MessageVersions { get; } = new();
        public long TranscriptSeq;
    }

    private readonly string? _runtimeFile;
    private string? _requestedSession;
    private readonly CancellationTokenSource _lifetime = new();
    private readonly SemaphoreSlim _connectGate = new(1, 1);
    private CancellationTokenSource? _connection;
    private RuntimeClient? _client;
    private Task? _consumer;
    private readonly Dictionary<string, string> _taskSessions = new();
    private readonly Dictionary<string, SessionState> _sessionStates = new();
    private readonly Dictionary<string, PermissionItem> _permissions = new();
    private long _snapshotSeq;
    private long _lastSeq;
    private string _runtimeId = "";
    private bool _disposed;

    public MainViewModel(string? runtimeFile = null, string? initialSession = null)
    {
        _runtimeFile = runtimeFile;
        _requestedSession = initialSession;
        ReconnectCommand = new AsyncRelayCommand(ConnectAsync);
        SendCommand = new AsyncRelayCommand(() => RequestAsync(SendAsync));
        NewSessionCommand = new AsyncRelayCommand(() => RequestAsync(NewSessionAsync));
        ResumeSessionCommand = new AsyncRelayCommand(() => RequestAsync(ResumeSelectedAsync));
        InterruptCommand = new AsyncRelayCommand(() => RequestAsync(InterruptAsync));
        ApplyProviderCommand = new AsyncRelayCommand(() => RequestAsync(ApplyProviderAsync));
        ApplyModelCommand = new AsyncRelayCommand(() => RequestAsync(ApplyModelAsync));
        ApproveCommand = new AsyncRelayCommand<PermissionItem>(p => RespondAsync(p, true));
        DenyCommand = new AsyncRelayCommand<PermissionItem>(p => RespondAsync(p, false));
        NavigateCommand = new RelayCommand<string>(p => CurrentPage = p ?? "Overview");
        ToggleBottomCommand = new RelayCommand(() => BottomVisible = !BottomVisible);
    }

    public ObservableCollection<SessionSummary> Sessions { get; } = new();
    public ObservableCollection<TaskInfo> Tasks { get; } = new();
    public ObservableCollection<AgentInfo> Agents { get; } = new();
    public ObservableCollection<ProviderInfo> Providers { get; } = new();
    public ObservableCollection<ToolInfo> AvailableTools { get; } = new();
    public ObservableCollection<MessageItem> Messages { get; } = new();
    public ObservableCollection<ToolActivityItem> ToolActivity { get; } = new();
    public ObservableCollection<PermissionItem> Permissions { get; } = new();
    public ObservableCollection<string> Logs { get; } = new();
    public IAsyncRelayCommand ReconnectCommand { get; }
    public IAsyncRelayCommand SendCommand { get; }
    public IAsyncRelayCommand NewSessionCommand { get; }
    public IAsyncRelayCommand ResumeSessionCommand { get; }
    public IAsyncRelayCommand InterruptCommand { get; }
    public IAsyncRelayCommand ApplyProviderCommand { get; }
    public IAsyncRelayCommand ApplyModelCommand { get; }
    public IAsyncRelayCommand<PermissionItem> ApproveCommand { get; }
    public IAsyncRelayCommand<PermissionItem> DenyCommand { get; }
    public IRelayCommand<string> NavigateCommand { get; }
    public IRelayCommand ToggleBottomCommand { get; }

    private string _connectionStatus = "Disconnected · runtime not discovered";
    public string ConnectionStatus { get => _connectionStatus; private set => SetProperty(ref _connectionStatus, value); }
    private bool _isConnected;
    public bool IsConnected { get => _isConnected; private set => SetProperty(ref _isConnected, value); }
    private string _notice = "Start or discover a runtime with the Mimicry CLI, then reconnect.";
    public string Notice { get => _notice; set => SetProperty(ref _notice, value); }
    private string _projectDir = "";
    public string ProjectDir { get => _projectDir; private set => SetProperty(ref _projectDir, value); }
    private string _providerId = "";
    public string ProviderId { get => _providerId; private set => SetProperty(ref _providerId, value); }
    private string _model = "";
    public string Model { get => _model; private set => SetProperty(ref _model, value); }
    private string _modelInput = "";
    public string ModelInput { get => _modelInput; set => SetProperty(ref _modelInput, value); }
    private ProviderInfo? _selectedProvider;
    public ProviderInfo? SelectedProvider { get => _selectedProvider; set => SetProperty(ref _selectedProvider, value); }
    private string _apiKeyStatus = "Provider configuration is unavailable while disconnected.";
    public string ApiKeyStatus { get => _apiKeyStatus; private set => SetProperty(ref _apiKeyStatus, value); }
    private string _permissionMode = "Unknown";
    public string PermissionMode { get => _permissionMode; private set => SetProperty(ref _permissionMode, value); }
    private string? _runningTaskId;
    public string? RunningTaskId { get => _runningTaskId; private set => SetProperty(ref _runningTaskId, value); }
    private string? _activeSessionId;
    public string? ActiveSessionId { get => _activeSessionId; private set => SetProperty(ref _activeSessionId, value); }
    private SessionSummary? _selectedSession;
    public SessionSummary? SelectedSession { get => _selectedSession; set => SetProperty(ref _selectedSession, value); }
    private string _prompt = "";
    public string Prompt { get => _prompt; set => SetProperty(ref _prompt, value); }
    private string _currentPage = "Overview";
    public string CurrentPage
    {
        get => _currentPage;
        set
        {
            if (!SetProperty(ref _currentPage, value)) return;
            OnPropertyChanged(nameof(IsOverview));
            OnPropertyChanged(nameof(IsConversation));
            OnPropertyChanged(nameof(IsActivity));
        }
    }
    public bool IsOverview => CurrentPage == "Overview";
    public bool IsConversation => CurrentPage == "Conversation";
    public bool IsActivity => CurrentPage == "Tool Activity";
    private bool _bottomVisible = true;
    public bool BottomVisible { get => _bottomVisible; set => SetProperty(ref _bottomVisible, value); }
    private string _planText = "No plan reported by the runtime.";
    public string PlanText { get => _planText; private set => SetProperty(ref _planText, value); }
    public long LastEventSeq => _lastSeq;
    public string RuntimeId => _runtimeId;
    public int PendingPermissionCount => _permissions.Values.Count(p => p.CanRespond);

    public async Task ConnectAsync()
    {
        if (_disposed) return;
        try
        {
            await _connectGate.WaitAsync(_lifetime.Token);
            try
            {
                _connection?.Cancel();
                if (_consumer is not null)
                {
                    try { await _consumer; } catch (OperationCanceledException) { }
                }
                _client?.Dispose();
                _client = null;
                _connection?.Dispose();
                _connection = CancellationTokenSource.CreateLinkedTokenSource(_lifetime.Token);
                var ct = _connection.Token;
                IsConnected = false;
                ConnectionStatus = "Connecting to local runtime…";
                var client = await RuntimeLocator.ConnectAsync(_runtimeFile, ct);
                _client = client;
                await SynchronizeAsync(client, ct);
                _consumer = ConsumeAsync(client, ct);
            }
            finally { _connectGate.Release(); }
        }
        catch (OperationCanceledException) { }
        catch (Exception ex)
        {
            IsConnected = false;
            ConnectionStatus = "Disconnected · discovery or connection failed";
            Notice = $"Unable to connect ({ex.GetType().Name}). Check the CLI runtime and runtime-file path. No runtime was started.";
        }
    }

    private static async Task OnUiAsync(Action action, CancellationToken ct)
    {
        if (ct.IsCancellationRequested) return;
        await Dispatcher.UIThread.InvokeAsync(() => { if (!ct.IsCancellationRequested) action(); });
    }

    private async Task SynchronizeAsync(RuntimeClient client, CancellationToken ct)
    {
        var snapshot = await client.SnapshotAsync(ct);
        await OnUiAsync(() => ApplySnapshot(snapshot), ct);
        if (_requestedSession is { } requested)
        {
            await client.ResumeAsync(requested, ct);
            await OnUiAsync(() => { ActivateSession(requested); _requestedSession = null; }, ct);
        }
        var session = ActiveSessionId;
        if (session is not null)
        {
            var transcript = await client.SessionMessagesAsync(session, ct);
            await OnUiAsync(() => ApplyTranscript(transcript), ct);
        }
        await OnUiAsync(() =>
        {
            IsConnected = true;
            ConnectionStatus = "Connected · local runtime";
            Notice = "GUI exit leaves the runtime running. Runtime shutdown requires separate confirmation.";
        }, ct);
    }

    private async Task ConsumeAsync(RuntimeClient client, CancellationToken ct)
    {
        var retry = 0;
        while (!ct.IsCancellationRequested)
        {
            try
            {
                await foreach (var envelope in client.EventsAsync(_lastSeq, ct))
                {
                    await OnUiAsync(() => ApplyEvent(envelope), ct);
                    retry = 0;
                    var type = String(envelope.Event, "type");
                    if (type is "task_completed" or "task_failed" or "task_cancelled")
                    {
                        var snapshot = await client.SnapshotAsync(ct);
                        await OnUiAsync(() => ApplySnapshot(snapshot), ct);
                    }
                }
                if (ct.IsCancellationRequested) break;
                await OnUiAsync(() => IsConnected = false, ct);
            }
            catch (OperationCanceledException) when (ct.IsCancellationRequested) { break; }
            catch (Exception ex)
            {
                await OnUiAsync(() =>
                {
                    IsConnected = false;
                    AddLog($"Event connection lost ({ex.GetType().Name}); reconnecting without dropping the event watermark.");
                }, ct);
            }
            try
            {
                var seconds = Math.Min(30, 1 << Math.Min(retry++, 5));
                await OnUiAsync(() => ConnectionStatus = $"Reconnecting · retry in {seconds}s", ct);
                await Task.Delay(TimeSpan.FromSeconds(seconds), ct);
                await SynchronizeAsync(client, ct);
            }
            catch (OperationCanceledException) when (ct.IsCancellationRequested) { break; }
            catch (Exception ex)
            {
                await OnUiAsync(() => AddLog($"Runtime still unavailable ({ex.GetType().Name})."), ct);
            }
        }
    }

    public void ApplySnapshot(AppSnapshot snapshot)
    {
        if (_runtimeId != snapshot.RuntimeId)
        {
            _runtimeId = snapshot.RuntimeId;
            _lastSeq = 0;
            _snapshotSeq = 0;
            _taskSessions.Clear();
            _sessionStates.Clear();
            _permissions.Clear();
            Messages.Clear();
            ToolActivity.Clear();
            Permissions.Clear();
            ActiveSessionId = null;
            OnPropertyChanged(nameof(RuntimeId));
            OnPropertyChanged(nameof(LastEventSeq));
        }
        _snapshotSeq = Math.Max(_snapshotSeq, snapshot.LastEventSeq);
        ProjectDir = snapshot.ProjectDir;
        ProviderId = snapshot.ProviderId;
        Model = snapshot.Model;
        ModelInput = snapshot.Model;
        PermissionMode = snapshot.PermissionMode;
        RunningTaskId = snapshot.RunningTaskId;
        Replace(Sessions, snapshot.Sessions);
        Replace(Tasks, snapshot.Tasks);
        Replace(Agents, snapshot.Agents);
        Replace(Providers, snapshot.Providers);
        Replace(AvailableTools, snapshot.Tools);
        SelectedProvider = Providers.FirstOrDefault(p => p.Id == ProviderId);
        ApiKeyStatus = SelectedProvider is null ? "Provider configuration was not reported." :
            SelectedProvider.Configured ? "API key configured in the runtime (not exposed to the GUI)." :
            "API key missing: configure this provider in the runtime before sending.";
        foreach (var task in snapshot.Tasks) _taskSessions[task.Id] = task.SessionId;
        var pendingIds = snapshot.PendingPermissions.Select(p => p.Id).ToHashSet();
        foreach (var permission in _permissions.Values.Where(p => p.CanRespond && !pendingIds.Contains(p.Id)))
            permission.Status = "No longer pending in runtime";
        foreach (var p in snapshot.PendingPermissions)
        {
            if (!_permissions.TryGetValue(p.Id, out var item))
                _permissions[p.Id] = new PermissionItem { Id = p.Id, TaskId = p.TaskId, ToolName = p.ToolName, Target = p.Target, Reason = p.Reason, Scope = p.Scope };
            else if (item.Status == "No longer pending in runtime") item.Status = "Awaiting explicit approval";
        }
        if (ActiveSessionId is null)
        {
            var initial = _requestedSession ?? snapshot.Tasks.FirstOrDefault(t => t.Id == snapshot.RunningTaskId)?.SessionId
                ?? snapshot.Sessions.OrderByDescending(s => s.LastActiveAt).FirstOrDefault()?.Id;
            if (initial is not null) ActivateSession(initial);
        }
        SelectedSession = Sessions.FirstOrDefault(s => s.Id == ActiveSessionId);
        if (snapshot.Plan is { } plan)
            PlanText = $"{plan.Status}\n" + string.Join("\n", plan.Steps.Select(s => $"{s.Status} · {s.Title}\n{s.Detail}"));
        else PlanText = "No plan reported by the runtime.";
        RefreshPermissions();
        // Snapshot/transcript seqs are not the SSE watermark: replay still carries live tool activity.
    }

    private SessionState State(string id)
    {
        if (!_sessionStates.TryGetValue(id, out var state)) _sessionStates[id] = state = new SessionState();
        return state;
    }

    public void ActivateSession(string id)
    {
        ActiveSessionId = id;
        var state = State(id);
        Replace(Messages, state.Messages);
        Replace(ToolActivity, state.Tools);
        SelectedSession = Sessions.FirstOrDefault(s => s.Id == id);
        RefreshPermissions();
    }

    public void ApplyTranscript(SessionTranscript transcript)
    {
        var state = State(transcript.SessionId);
        foreach (var message in transcript.Messages)
        {
            var existing = state.Messages.FirstOrDefault(m => m.Id == message.Id);
            if (existing is null)
            {
                existing = new MessageItem { Id = message.Id, Role = message.Role, Text = message.Text, Timestamp = message.Timestamp };
                state.Messages.Add(existing);
            }
            else if (state.MessageVersions.GetValueOrDefault(message.Id) <= transcript.LastEventSeq)
                existing.Text = message.Text;
            state.MessageVersions[message.Id] = Math.Max(state.MessageVersions.GetValueOrDefault(message.Id), transcript.LastEventSeq);
        }
        state.TranscriptSeq = Math.Max(state.TranscriptSeq, transcript.LastEventSeq);
        if (ActiveSessionId == transcript.SessionId) Replace(Messages, state.Messages);
    }

    public void ApplyEvent(EventEnvelope envelope)
    {
        if (envelope.Seq <= _lastSeq) return;
        var e = envelope.Event;
        var type = String(e, "type");
        var taskId = String(e, "taskId");
        var sessionId = String(e, "sessionId");
        if (taskId.Length > 0 && sessionId.Length > 0) _taskSessions[taskId] = sessionId;
        if (sessionId.Length == 0) sessionId = _taskSessions.GetValueOrDefault(taskId, "");
        SessionState? state = sessionId.Length > 0 ? State(sessionId) : null;
        switch (type)
        {
            case "task_created":
            case "task_started":
            case "task_queued":
                if (envelope.Seq > _snapshotSeq && type == "task_started") RunningTaskId = taskId;
                break;
            case "message":
            case "text_delta":
                if (state is not null)
                {
                    var id = String(e, "messageId");
                    var message = state.Messages.FirstOrDefault(m => m.Id == id);
                    if (message is null)
                    {
                        message = new MessageItem { Id = id, Role = type == "message" ? String(e, "role") : "assistant", Timestamp = envelope.Timestamp };
                        state.Messages.Add(message);
                        if (sessionId == ActiveSessionId) Messages.Add(message);
                    }
                    if (envelope.Seq > state.MessageVersions.GetValueOrDefault(id))
                    {
                        message.Text = type == "text_delta" ? message.Text + String(e, "text") : String(e, "text");
                        state.MessageVersions[id] = envelope.Seq;
                    }
                }
                break;
            case "tool_started":
            case "tool_progress":
            case "tool_completed":
            case "tool_failed":
                if (state is not null)
                {
                    var id = String(e, "toolUseId");
                    var tool = state.Tools.FirstOrDefault(t => t.Id == id);
                    if (tool is null)
                    {
                        tool = new ToolActivityItem { Id = id, TaskId = taskId, Name = String(e, "toolName"), Summary = String(e, "summary"), Input = e.TryGetProperty("input", out var input) ? input.ToString() : "" };
                        state.Tools.Add(tool);
                        if (sessionId == ActiveSessionId) ToolActivity.Add(tool);
                    }
                    if (type == "tool_progress") tool.Output += String(e, "message") + "\n";
                    if (type == "tool_completed") { tool.Output = String(e, "output"); tool.Status = Bool(e, "isError") ? "Error" : "Completed"; }
                    if (type == "tool_failed") { tool.Output = String(e, "error"); tool.Status = "Failed"; }
                }
                break;
            case "permission_requested":
                if (envelope.Seq > _snapshotSeq)
                {
                    var id = String(e, "requestId");
                    _permissions.TryAdd(id, new PermissionItem { Id = id, TaskId = taskId, ToolName = String(e, "toolName"), Target = String(e, "target"), Reason = String(e, "reason"), Scope = String(e, "scope") });
                    RefreshPermissions();
                }
                break;
            case "permission_resolved":
                if (_permissions.TryGetValue(String(e, "requestId"), out var permission) && envelope.Seq > _snapshotSeq)
                {
                    permission.Status = Bool(e, "approved") ? "Approved by runtime" : "Denied by runtime";
                    RefreshPermissions();
                }
                break;
            case "task_completed":
            case "task_failed":
            case "task_cancelled":
                if (envelope.Seq > _snapshotSeq && RunningTaskId == taskId) RunningTaskId = null;
                // Results are task metadata. Keep the full message stream instead of replacing it with result.
                AddLog($"{type} · {taskId}");
                break;
            case "runtime_status":
                ConnectionStatus = $"Runtime · {String(e, "status")}";
                break;
            case "plan_updated":
                if (envelope.Seq > _snapshotSeq)
                    PlanText = String(e, "status") + "\n" + (e.TryGetProperty("steps", out var steps) ? steps.ToString() : "");
                break;
            case "file_changed":
                if (sessionId == ActiveSessionId) AddLog($"File {String(e, "operation")} · {String(e, "path")}");
                break;
            case "log":
            case "error":
                if (taskId.Length == 0 || sessionId == ActiveSessionId) AddLog(String(e, "message"));
                break;
        }
        _lastSeq = envelope.Seq;
        OnPropertyChanged(nameof(LastEventSeq));
    }

    private void RefreshPermissions()
    {
        Replace(Permissions, _permissions.Values.Where(p => _taskSessions.GetValueOrDefault(p.TaskId) == ActiveSessionId));
        OnPropertyChanged(nameof(PendingPermissionCount));
    }

    private void AddLog(string text)
    {
        Logs.Add(text);
        if (Logs.Count > 2000) Logs.RemoveAt(0);
    }

    private static string String(JsonElement e, string key) => e.ValueKind == JsonValueKind.Object && e.TryGetProperty(key, out var value) && value.ValueKind == JsonValueKind.String ? value.GetString() ?? "" : "";
    private static bool Bool(JsonElement e, string key) => e.ValueKind == JsonValueKind.Object && e.TryGetProperty(key, out var value) && value.ValueKind == JsonValueKind.True;
    private static void Replace<T>(ObservableCollection<T> target, IEnumerable<T> values)
    {
        target.Clear();
        foreach (var item in values) target.Add(item);
    }

    private async Task RequestAsync(Func<RuntimeClient, CancellationToken, Task> request)
    {
        var client = _client;
        var ct = _connection?.Token ?? _lifetime.Token;
        if (!IsConnected || client is null) { Notice = "Runtime is disconnected. Reconnect before performing this action."; return; }
        try { await request(client, ct); }
        catch (OperationCanceledException) { }
        catch (Exception ex) { Notice = $"Runtime request failed ({ex.GetType().Name}). No success was assumed; reconnect to verify state."; }
    }

    private async Task SendAsync(RuntimeClient client, CancellationToken ct)
    {
        if (string.IsNullOrWhiteSpace(Prompt)) return;
        var prompt = Prompt;
        var task = await client.SubmitAsync(prompt, ActiveSessionId, ct);
        _taskSessions[task.Id] = task.SessionId;
        ActivateSession(task.SessionId);
        RunningTaskId = task.Id;
        if (Prompt == prompt) Prompt = "";
        CurrentPage = "Conversation";
        ApplySnapshot(await client.SnapshotAsync(ct));
        ApplyTranscript(await client.SessionMessagesAsync(task.SessionId, ct));
    }

    private async Task NewSessionAsync(RuntimeClient client, CancellationToken ct)
    {
        var session = await client.CreateSessionAsync(ct);
        ActivateSession(session.Id);
        ApplySnapshot(await client.SnapshotAsync(ct));
        ApplyTranscript(await client.SessionMessagesAsync(session.Id, ct));
        CurrentPage = "Conversation";
    }

    private async Task ResumeSelectedAsync(RuntimeClient client, CancellationToken ct)
    {
        var id = SelectedSession?.Id;
        if (id is null) return;
        await client.ResumeAsync(id, ct);
        // Fetch before switching so an unavailable history never appears as an empty successful resume.
        var transcript = await client.SessionMessagesAsync(id, ct);
        ApplyTranscript(transcript);
        ActivateSession(id);
        CurrentPage = "Conversation";
        ApplySnapshot(await client.SnapshotAsync(ct));
    }

    private async Task InterruptAsync(RuntimeClient client, CancellationToken ct)
    {
        if (RunningTaskId is not { } id) { Notice = "No running task reported by the runtime."; return; }
        Notice = await client.InterruptAsync(id, ct) ? "Interrupt requested; waiting for runtime task status." : "Runtime did not accept the interrupt; task may already have finished.";
    }

    private async Task ApplyProviderAsync(RuntimeClient client, CancellationToken ct)
    {
        if (SelectedProvider is not { } provider) return;
        await client.SetProviderAsync(provider.Id, ct);
        ApplySnapshot(await client.SnapshotAsync(ct));
    }

    private async Task ApplyModelAsync(RuntimeClient client, CancellationToken ct)
    {
        if (string.IsNullOrWhiteSpace(ModelInput)) return;
        await client.SetModelAsync(ModelInput.Trim(), ct);
        ApplySnapshot(await client.SnapshotAsync(ct));
    }

    public async Task RespondAsync(PermissionItem? permission, bool approved)
    {
        if (permission is null || !permission.CanRespond) return;
        await RequestAsync(async (client, ct) =>
        {
            permission.Status = "Submitting response…";
            try
            {
                var resolved = await client.PermissionAsync(permission.Id, approved, ct);
                permission.Status = resolved ? (approved ? "Approval accepted by runtime" : "Denial accepted by runtime") : "Expired · runtime did not resolve this request";
                Notice = resolved ? "Permission response accepted by runtime." : "Permission expired or no longer pending. No approval was assumed.";
                OnPropertyChanged(nameof(PendingPermissionCount));
                ApplySnapshot(await client.SnapshotAsync(ct));
            }
            catch
            {
                permission.Status = "Awaiting explicit approval";
                throw;
            }
        });
    }

    public Task ShutdownRuntimeAsync() => RequestAsync(async (client, ct) =>
    {
        await client.ShutdownAsync(ct);
        IsConnected = false;
        ConnectionStatus = "Shutdown requested · waiting for runtime exit";
        Notice = "Runtime shutdown was explicitly requested. GUI exit is a separate action.";
        _connection?.Cancel();
    });

    public void Dispose()
    {
        if (_disposed) return;
        _disposed = true;
        _lifetime.Cancel();
        _connection?.Cancel();
        _client?.Dispose();
        // Closing the GUI deliberately never calls ShutdownAsync.
    }
}
