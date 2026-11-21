using CommunityToolkit.Mvvm.ComponentModel;

namespace Mimicry.Gui.ViewModels;

public sealed class MessageItem : ObservableObject
{
    public string Id { get; init; } = "";
    public string Role { get; init; } = "assistant";
    public long Timestamp { get; init; }
    private string _text = "";
    public string Text { get => _text; set => SetProperty(ref _text, value); }
}

public sealed class ToolActivityItem : ObservableObject
{
    public string Id { get; init; } = "";
    public string TaskId { get; init; } = "";
    public string Name { get; init; } = "";
    public string Summary { get; set; } = "";
    public string Input { get; set; } = "";
    private string _status = "Running";
    public string Status { get => _status; set { if (SetProperty(ref _status, value)) OnPropertyChanged(nameof(Header)); } }
    private string _output = "";
    public string Output { get => _output; set => SetProperty(ref _output, value); }
    public string Header => $"{Name} · {Status}";
}

public sealed class PermissionItem : ObservableObject
{
    public string Id { get; init; } = "";
    public string TaskId { get; init; } = "";
    public string ToolName { get; init; } = "";
    public string Target { get; init; } = "";
    public string Reason { get; init; } = "";
    public string Scope { get; init; } = "tool";
    private string _status = "Awaiting explicit approval";
    public string Status { get => _status; set { if (SetProperty(ref _status, value)) OnPropertyChanged(nameof(CanRespond)); } }
    public bool CanRespond => Status == "Awaiting explicit approval";
}
