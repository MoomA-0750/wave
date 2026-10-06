using Microsoft.UI.Dispatching;
using Microsoft.UI.Xaml;
using Wave.App.Services;

namespace Wave.App;

public partial class App : Application
{
    public static MainWindow? Window { get; private set; }

    public App()
    {
        InitializeComponent();
        UnhandledException += (_, e) =>
        {
            // 例外の種類だけを残す（文に URL などが入ることがあるので、本文は書かない）
            AppPaths.Log($"unhandled type={e.Exception.GetType().Name}");
            e.Handled = true;
        };
    }

    protected override void OnLaunched(LaunchActivatedEventArgs args)
    {
        AccountState.CleanupWebViewIfRequested();
        AppServices.Initialize(DispatcherQueue.GetForCurrentThread());
        Window = new MainWindow();
        Window.Activate();
    }
}
