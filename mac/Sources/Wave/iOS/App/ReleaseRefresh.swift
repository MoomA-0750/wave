#if os(iOS)
import BackgroundTasks
import Foundation

/// アプリを閉じていても新作を確かめる（Android の6時間おきの確かめに当たる。PC は起動中だけ）。
/// 確かめは登録したアーティストを順に見るので数分かかり、裏の更新（BGAppRefreshTask、30 秒ほど）では終わらない。
/// そのため裏の処理（BGProcessingTask）で頼む。いつ動くかは OS が決める（使っていない間・つながっている間など）
enum ReleaseRefresh {
    static let identifier = "xyz.mooma0750.wave.releases"
    private static let interval: TimeInterval = 6 * 60 * 60

    /// 起動の終わる前に呼ぶ（OS の決まり）。
    static func register() {
        BGTaskScheduler.shared.register(forTaskWithIdentifier: identifier, using: nil) { task in
            Task { @MainActor in run(task) }
        }
    }

    /// 次の確かめを頼む（新作の通知が切なら取り消す）。
    @MainActor
    static func schedule() {
        guard AppServices.settings.releaseNotices else {
            BGTaskScheduler.shared.cancel(taskRequestWithIdentifier: identifier)
            return
        }
        let request = BGProcessingTaskRequest(identifier: identifier)
        request.earliestBeginDate = Date(timeIntervalSinceNow: interval)
        request.requiresNetworkConnectivity = true
        do {
            try BGTaskScheduler.shared.submit(request)
        } catch {
            AppPaths.log("release refresh schedule failed error=\((error as NSError).domain)/\((error as NSError).code)")
        }
    }

    @MainActor
    private static func run(_ task: BGTask) {
        AppPaths.log("release refresh start")
        schedule()
        // 裏で起きたときに端末がロック中でログインを読めなかったら、読み直す
        if !AppServices.account.isLoggedIn && AppServices.account.savedLoginUnreadable { AppServices.account.restore() }
        let work = Task { @MainActor in
            await AppServices.releases.check()
            AppPaths.log("release refresh done cancelled=\(Task.isCancelled)")
            task.setTaskCompleted(success: !Task.isCancelled)
        }
        // 時間切れ: 途中で止める（見られた分までは覚える）
        task.expirationHandler = { work.cancel() }
    }

    /// 開発用: 頼んである確かめを記録に書く。
    static func logPending() async {
        let pending = await BGTaskScheduler.shared.pendingTaskRequests()
        let text = pending.map { "\($0.identifier)@\($0.earliestBeginDate.map { Int($0.timeIntervalSinceNow) } ?? -1)s" }.joined(separator: ",")
        AppPaths.log("release refresh pending=[\(text)]")
    }
}
#endif
