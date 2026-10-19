import WaveCore

/// 読み込みの失敗の理由の文（Android の failureMessage・Windows の検索と同じ分け方）。Mac・iPhone の両方で使う。
/// 0件の「見つかりませんでした」とは分けて出す。
enum FailureText {
    static func message(_ failure: FailureKind) -> String {
        switch failure {
        case .notLoggedIn: L.t("ログインしていません", "You're not logged in")
        case .expired: L.t("再ログインが必要です", "Please log in again")
        case .suspect: L.t("ログイン状態を確認しています。少し待ってからもう一度試してください", "Checking your login. Wait a moment and try again")
        case .network: L.t("通信できませんでした", "Couldn't connect")
        case .http: L.t("サーバーから応答がありませんでした", "The server didn't respond")
        case .parse: L.t("応答を読み取れませんでした", "Couldn't read the response")
        }
    }

}
