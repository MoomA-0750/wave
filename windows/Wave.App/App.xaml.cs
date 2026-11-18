using Microsoft.UI.Dispatching;
using Microsoft.UI.Xaml;
using Microsoft.Windows.AppLifecycle;
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

    protected override async void OnLaunched(LaunchActivatedEventArgs args)
    {
        // 1つだけ動かす。もう動いていれば（窓を閉じて裏で作業を続けている間など）そちらの窓を出して終わる
        var main = AppInstance.FindOrRegisterForKey("main");
        if (!main.IsCurrent)
        {
            await main.RedirectActivationToAsync(AppInstance.GetCurrent().GetActivatedEventArgs());
            Exit();
            return;
        }
        var ui = DispatcherQueue.GetForCurrentThread();
        main.Activated += (_, _) => ui.TryEnqueue(() => Window?.ShowFromBackground());
        AccountState.CleanupWebViewIfRequested();
        AppServices.Initialize(ui);
        Window = new MainWindow();
        Window.Activate();
    }
}
