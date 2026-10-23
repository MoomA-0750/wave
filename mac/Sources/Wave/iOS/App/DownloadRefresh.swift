#if os(iOS)
import BackgroundTasks
import Foundation

/// アプリを閉じていても、1日1回ダウンロードを入れ替える（ADR-0016 の 7: 一覧の読み直し・スマートダウンロードの選び直し・取り残しを取る）。
/// 裏の処理（BGProcessingTask）で頼み、いつ動くかは OS が決める。時間切れになったら止め、次に開いたときか次の回に続ける。
enum DownloadRefresh {
    static let identifier = "xyz.mooma0750.wave.downloads"
    private static let interval: TimeInterval = 24 * 60 * 60

    /// 起動の終わる前に呼ぶ（OS の決まり）。
    static func register() {
        BGTaskScheduler.shared.register(forTaskWithIdentifier: identifier, using: nil) { task in
            Task { @MainActor in run(task) }
        }
    }

    /// 次の入れ替えを頼む（取っているものもスマートダウンロードも無ければ取り消す）。
    @MainActor
    static func schedule() {
        let downloads = AppServices.downloads!
        guard downloads.settings.smartDownloads || !downloads.manifest.wanted.isEmpty else {
            BGTaskScheduler.shared.cancel(taskRequestWithIdentifier: identifier)
            return
        }
        let request = BGProcessingTaskRequest(identifier: identifier)
        request.earliestBeginDate = Date(timeIntervalSinceNow: interval)
        request.requiresNetworkConnectivity = true
        do {
            try BGTaskScheduler.shared.submit(request)
        } catch {
            AppPaths.log("downloads refresh schedule failed error=\((error as NSError).domain)/\((error as NSError).code)")
        }
    }

    @MainActor
    private static func run(_ task: BGTask) {
        AppPaths.log("downloads refresh start")
        schedule()
        if !AppServices.account.isLoggedIn && AppServices.account.savedLoginUnreadable { AppServices.account.restore() }
        let work = Task { @MainActor in
            let downloads = AppServices.downloads!
            await downloads.refresh(collections: true)
            // 取り残しを取り終えるまで待つ（時間切れなら止める）
            while !Task.isCancelled, downloads.active != nil || (downloads.allowed && !downloads.pending.isEmpty && downloads.isWorking) {
                try? await Task.sleep(for: .seconds(2))
            }
            AppPaths.log("downloads refresh done cancelled=\(Task.isCancelled) pending=\(downloads.pending.count)")
            task.setTaskCompleted(success: !Task.isCancelled)
        }
        task.expirationHandler = {
            Task { @MainActor in AppServices.downloads.pause() }
            work.cancel()
        }
    }
}
#endif
