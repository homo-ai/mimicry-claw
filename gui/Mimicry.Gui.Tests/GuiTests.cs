using System.Text.Json;
using Avalonia.Controls;
using Avalonia.Headless;
using Avalonia.Headless.XUnit;
using Avalonia.Input;
using Avalonia.VisualTree;
using Mimicry.Gui.Models;
using Mimicry.Gui.ViewModels;
using Mimicry.Gui.Views;
using Xunit;

[assembly: AvaloniaTestApplication(typeof(Mimicry.Gui.Program))]
[assembly: AvaloniaTestFramework]

namespace Mimicry.Gui.Tests;

public class GuiTests
{
    [Fact]
    public void ReducersKeepSessionMessagesAndToolActivitySeparate()
    {
        using var vm = new MainViewModel();
        vm.ApplyEvent(Event(1, """
            {"type":"task_created","taskId":"t1","sessionId":"s1","prompt":"first"}
            """));
        vm.ApplyEvent(Event(2, """
            {"type":"message","taskId":"t1","messageId":"m1","role":"user","text":"hello","streaming":false}
            """));
        vm.ApplyEvent(Event(3, """
            {"type":"task_created","taskId":"t2","sessionId":"s2","prompt":"second"}
            """));
        vm.ApplyEvent(Event(4, """
            {"type":"tool_started","taskId":"t2","toolUseId":"tool-1","toolName":"Read",
             "summary":"read file","input":{"file_path":"a.txt"}}
            """));

        vm.ActivateSession("s1");
        Assert.Equal("hello", Assert.Single(vm.Messages).Text);
        Assert.Empty(vm.ToolActivity);

        vm.ActivateSession("s2");
        Assert.Empty(vm.Messages);
        var tool = Assert.Single(vm.ToolActivity);
        Assert.Equal("Read", tool.Name);
        Assert.Contains("a.txt", tool.Input);

        vm.ApplyEvent(Event(5, """
            {"type":"tool_completed","taskId":"t2","toolUseId":"tool-1","toolName":"Read",
             "output":"body","isError":false}
            """));
        Assert.Equal("Completed", tool.Status);
        Assert.Equal("body", tool.Output);
    }

    [Fact]
    public void ReducersMergeStreamingTextWithoutDuplicatingReplay()
    {
        using var vm = new MainViewModel();
        vm.ApplyEvent(Event(1, """
            {"type":"task_created","taskId":"t1","sessionId":"s1","prompt":"first"}
            """));
        vm.ApplyEvent(Event(2, """
            {"type":"text_delta","taskId":"t1","messageId":"m2","text":"Hel"}
            """));
        vm.ApplyEvent(Event(3, """
            {"type":"text_delta","taskId":"t1","messageId":"m2","text":"lo"}
            """));
        vm.ApplyEvent(Event(3, """
            {"type":"text_delta","taskId":"t1","messageId":"m2","text":"ignored"}
            """));
        vm.ApplyEvent(Event(4, """
            {"type":"message","taskId":"t1","messageId":"m2","role":"assistant","text":"Hello"}
            """));

        var message = Assert.Single(vm.Messages);
        Assert.Equal("Hello", message.Text);
    }

    [Fact]
    public void ReducersMarkSnapshotPermissionStaleInsteadOfAssumingSuccess()
    {
        using var vm = new MainViewModel();
        vm.ApplySnapshot(new AppSnapshot
        {
            RuntimeId = "r1",
            PendingPermissions =
            {
                new PermissionRequestInfo { Id = "p1", TaskId = "t1", ToolName = "Write", Target = "a.txt", Reason = "approval" },
            },
        });
        vm.ApplySnapshot(new AppSnapshot
        {
            RuntimeId = "r1",
            PendingPermissions =
            {
                new PermissionRequestInfo { Id = "p1", TaskId = "t1", ToolName = "Write", Target = "a.txt", Reason = "approval" },
            },
        });
        Assert.Equal(1, vm.PendingPermissionCount);

        vm.ApplySnapshot(new AppSnapshot { RuntimeId = "r1" });
        var permission = Assert.Single(vm.Permissions);
        Assert.Equal("No longer pending in runtime", permission.Status);
        Assert.False(permission.CanRespond);
    }

    [Fact]
    public void RuntimeIdentityChangeClearsAllPreviousState()
    {
        using var vm = new MainViewModel();
        vm.ApplySnapshot(new AppSnapshot { RuntimeId = "r1" });
        vm.ApplyEvent(Event(1, """
            {"type":"task_created","taskId":"t1","sessionId":"s1","prompt":"old"}
            """));
        vm.ApplyEvent(Event(2, """
            {"type":"message","taskId":"t1","messageId":"m1","role":"user","text":"old message"}
            """));
        Assert.Single(vm.Messages);

        vm.ApplySnapshot(new AppSnapshot { RuntimeId = "r2" });
        Assert.Empty(vm.Messages);
        Assert.Equal(0, vm.LastEventSeq);
    }

    [AvaloniaFact]
    public void WindowLoadsCustomChromeSeparateWindowActionsAndInputBindings()
    {
        var vm = new MainViewModel();
        var window = new MainWindow { DataContext = vm };
        try
        {
            window.Show();
            var close = window.FindControl<Button>("CloseButton");
            var minimize = window.FindControl<Button>("MinimizeButton");
            var maximize = window.FindControl<Button>("MaximizeButton");
            var shutdown = window.FindControl<MenuItem>("ShutdownRuntimeMenu");
            var fullscreen = window.FindControl<MenuItem>("FullscreenMenu");
            var prompt = window.FindControl<TextBox>("PromptBox");
            var send = window.FindControl<Button>("SendButton");

            Assert.NotNull(close);
            Assert.NotNull(minimize);
            Assert.NotNull(maximize);
            Assert.NotNull(shutdown);
            Assert.NotNull(fullscreen);
            Assert.NotNull(prompt);
            Assert.NotNull(send);
            Assert.False(vm.IsConnected, "Opening a window must not auto-start or stop a runtime.");
            Assert.Contains("Exit", "Exit GUI (keep runtime running)");

            prompt!.KeyDown += (_, _) => { };
            prompt.RaiseEvent(new KeyEventArgs
            {
                Key = Key.Enter,
                RoutedEvent = InputElement.KeyDownEvent,
            });
            Assert.Contains("disconnected", vm.Notice, StringComparison.OrdinalIgnoreCase);
        }
        finally
        {
            window.Close();
            vm.Dispose();
        }
    }

    private static EventEnvelope Event(long seq, string eventJson, long timestamp = 1000) => new()
    {
        Seq = seq,
        Timestamp = timestamp,
        Event = JsonDocument.Parse(eventJson).RootElement.Clone(),
    };
}
