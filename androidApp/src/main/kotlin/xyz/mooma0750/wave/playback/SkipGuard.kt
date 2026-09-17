package xyz.mooma0750.wave.playback

/** 再生エラーの種類。曲ごとの失敗だけを飛ばし、アカウントや仕組み全体の失敗では止める。 */
enum class PlaybackFailure {
    /** この曲だけの失敗（再生不可・フォーマット無し・拒否・パース・デコード）。飛ばして次へ。 */
    ThisSong,

    /** ネットワークの一時的な失敗。飛ばさずに止める（利用者が再生を押せば再開する）。 */
    Transient,

    /** ログインの失効・疑い・解読の失敗など、どの曲でも起きる失敗。飛ばすと /player を連打するので止める。 */
    Everywhere,
}

/**
 * 連続スキップの上限を数える。飛ばすためのシークで起きる曲の遷移では数えを戻さない
 * （戻すと上限に届かず、失敗する曲を飛ばし続ける）。戻すのは再生が始まったときと、利用者が選んだとき。
 */
class SkipGuard(
    private val maxConsecutiveSkips: Int = 3,
) {
    private var consecutiveSkips = 0

    /** @return 飛ばしてよいか */
    fun onError(
        failure: PlaybackFailure,
        hasNext: Boolean,
    ): Boolean {
        if (failure != PlaybackFailure.ThisSong || !hasNext) return false
        if (consecutiveSkips >= maxConsecutiveSkips) return false
        consecutiveSkips++
        return true
    }

    /** 曲の再生が始まった（READY）。 */
    fun onPlaying() {
        consecutiveSkips = 0
    }

    /** 利用者が曲を選んだ・キューを置き換えた。 */
    fun onUserSelection() {
        consecutiveSkips = 0
    }
}
