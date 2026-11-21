using System.ComponentModel;
using Avalonia;
using Avalonia.Controls;
using Avalonia.Input;
using Avalonia.Interactivity;
using Avalonia.Markup.Xaml;
using Avalonia.VisualTree;
using Mimicry.Gui.Services;
using Mimicry.Gui.ViewModels;

namespace Mimicry.Gui.Views;

public partial class MainWindow : Window
{
    public CommandRunner Runner { get; } = new();
    private MainViewModel? _observed;
    private GridLength _bottomHeight = new(220);
    private WindowState _beforeFullscreen = WindowState.Normal;
    private bool _confirmationOpen;

    public MainWindow()
    {
        AvaloniaXamlLoader.Load(this);
        AddHandler(KeyDownEvent, WindowKeyDown, RoutingStrategies.Tunnel);
        DataContextChanged += (_, _) => ObserveViewModel();
        Closed += (_, _) =>
        {
            if (_observed is not null) _observed.PropertyChanged -= ViewModelChanged;
            Runner.Dispose();
        };
    }

    private MainViewModel? ViewModel => DataContext as MainViewModel;

    private void ObserveViewModel()
    {
        if (_observed is not null) _observed.PropertyChanged -= ViewModelChanged;
        _observed = ViewModel;
        if (_observed is not null) _observed.PropertyChanged += ViewModelChanged;
        ApplyBottomVisibility();
    }

    private void ViewModelChanged(object? sender, PropertyChangedEventArgs e)
    {
        if (e.PropertyName == nameof(MainViewModel.BottomVisible)) ApplyBottomVisibility();
    }

    private void ApplyBottomVisibility()
    {
        var grid = this.FindControl<Grid>("BodyGrid");
        if (grid is null) return;
        if (ViewModel?.BottomVisible != false)
        {
            grid.RowDefinitions[1].Height = new GridLength(5);
            grid.RowDefinitions[2].Height = _bottomHeight;
        }
        else
        {
            if (grid.RowDefinitions[2].Height.Value > 0) _bottomHeight = grid.RowDefinitions[2].Height;
            grid.RowDefinitions[1].Height = new GridLength(0);
            grid.RowDefinitions[2].Height = new GridLength(0);
        }
    }

    private void CloseClicked(object? sender, RoutedEventArgs e) => Close();
    private void MinimizeClicked(object? sender, RoutedEventArgs e) => RequestWindowState(WindowState.Minimized, "minimize");
    private void MaximizeClicked(object? sender, RoutedEventArgs e)
    {
        if (WindowState == WindowState.FullScreen)
        {
            if (ViewModel is { } vm) vm.Notice = "Leave fullscreen using the View menu before maximizing or restoring.";
            return;
        }
        RequestWindowState(WindowState == WindowState.Maximized ? WindowState.Normal : WindowState.Maximized, "maximize / restore");
    }

    private void FullscreenClicked(object? sender, RoutedEventArgs e)
    {
        if (WindowState == WindowState.FullScreen) RequestWindowState(_beforeFullscreen, "leave fullscreen");
        else
        {
            _beforeFullscreen = WindowState == WindowState.Minimized ? WindowState.Normal : WindowState;
            RequestWindowState(WindowState.FullScreen, "enter fullscreen");
        }
    }

    private void RequestWindowState(WindowState state, string action)
    {
        try
        {
            WindowState = state;
            if (ViewModel is { } vm)
                vm.Notice = $"Requested {action}. Window-manager support is platform-dependent and cannot be verified by this GUI.";
        }
        catch (NotSupportedException)
        {
            if (ViewModel is { } vm) vm.Notice = $"The current window manager does not support {action}.";
        }
        catch (InvalidOperationException)
        {
            if (ViewModel is { } vm) vm.Notice = $"Unable to request {action} in the current window state.";
        }
    }

    private void TitleBarPointerPressed(object? sender, PointerPressedEventArgs e)
    {
        if (!e.GetCurrentPoint(this).Properties.IsLeftButtonPressed) return;
        if (e.Source is Visual source && (source is Button or MenuItem or Menu || source.GetVisualAncestors().Any(v => v is Button or MenuItem or Menu))) return;
        if (e.ClickCount == 2) { MaximizeClicked(sender, e); return; }
        try { BeginMoveDrag(e); }
        catch (NotSupportedException)
        {
            if (ViewModel is { } vm) vm.Notice = "Window dragging is not supported by the current window manager.";
        }
    }

    public bool HasTextSelection()
    {
        return this.GetVisualDescendants().Any(v =>
            v is TextBox box && box.SelectionStart != box.SelectionEnd ||
            v is SelectableTextBlock text && text.SelectionStart != text.SelectionEnd);
    }

    private void WindowKeyDown(object? sender, KeyEventArgs e)
    {
        if (e.Key == Key.Escape)
        {
            FocusManager?.ClearFocus();
            e.Handled = true;
            return;
        }
        if (e.Key == Key.C && (e.KeyModifiers & (KeyModifiers.Control | KeyModifiers.Meta)) != 0 && !HasTextSelection() && ViewModel?.RunningTaskId is not null)
        {
            ViewModel.InterruptCommand.Execute(null);
            e.Handled = true;
        }
    }

    private void PromptKeyDown(object? sender, KeyEventArgs e)
    {
        if (e.Key != Key.Enter || e.KeyModifiers.HasFlag(KeyModifiers.Shift)) return;
        if (ViewModel is { } vm) vm.SendCommand.Execute(null);
        e.Handled = true;
    }

    private async void RunCommandClicked(object? sender, RoutedEventArgs e)
    {
        if (ViewModel is not { } vm) return;
        if (!vm.IsConnected) { vm.Notice = "Reconnect to the runtime before running an explicit command in its project directory."; return; }
        await Runner.RunAsync(vm.ProjectDir);
    }

    private void StopCommandClicked(object? sender, RoutedEventArgs e) => Runner.Stop();

    private async void ShutdownClicked(object? sender, RoutedEventArgs e)
    {
        if (_confirmationOpen || ViewModel is not { } vm) return;
        if (!vm.IsConnected) { vm.Notice = "Runtime is disconnected. No shutdown was requested."; return; }
        _confirmationOpen = true;
        try
        {
            var confirm = new Button { Content = "Shutdown Runtime", Margin = new Thickness(0, 0, 8, 0) };
            var cancel = new Button { Content = "Cancel" };
            var dialog = new Window
            {
                Title = "Confirm runtime shutdown",
                Width = 420,
                Height = 220,
                CanResize = false,
                WindowStartupLocation = WindowStartupLocation.CenterOwner,
                Content = new StackPanel
                {
                    Margin = new Thickness(22),
                    Spacing = 18,
                    Children =
                    {
                        new TextBlock { Text = "Shutdown Runtime?", FontSize = 20 },
                        new TextBlock { Text = "This stops the shared runtime and interrupts its work. To leave it running, choose Exit GUI instead.", TextWrapping = Avalonia.Media.TextWrapping.Wrap },
                        new StackPanel { Orientation = Avalonia.Layout.Orientation.Horizontal, Children = { confirm, cancel } }
                    }
                }
            };
            confirm.Click += (_, _) => dialog.Close(true);
            cancel.Click += (_, _) => dialog.Close(false);
            if (await dialog.ShowDialog<bool>(this)) await vm.ShutdownRuntimeAsync();
        }
        finally { _confirmationOpen = false; }
    }
}
