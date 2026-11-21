using Avalonia;
using Avalonia.Controls.ApplicationLifetimes;
using Avalonia.Markup.Xaml;
using Mimicry.Gui.ViewModels;
using Mimicry.Gui.Views;

namespace Mimicry.Gui;

public partial class App : Application
{
    public override void Initialize() => AvaloniaXamlLoader.Load(this);

    public override void OnFrameworkInitializationCompleted()
    {
        if (ApplicationLifetime is IClassicDesktopStyleApplicationLifetime desktop)
        {
            var vm = new MainViewModel(Program.RuntimeFile, Program.InitialSession);
            var window = new MainWindow { DataContext = vm };
            window.Opened += async (_, _) => await vm.ConnectAsync();
            window.Closed += (_, _) => vm.Dispose();
            desktop.MainWindow = window;
        }
        base.OnFrameworkInitializationCompleted();
    }
}
