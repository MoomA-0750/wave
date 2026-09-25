package xyz.mooma0750.wave.playback

import androidx.media3.common.Player

/** 画面の再生ボタン: エラーで止まっていたら準備し直してから再生する。 */
fun Player.playFromButton() {
    if (playerError != null) prepare()
    play()
}

/** 画面の再生・一時停止ボタン。 */
fun Player.togglePlayPause() {
    if (isPlaying) pause() else playFromButton()
}

