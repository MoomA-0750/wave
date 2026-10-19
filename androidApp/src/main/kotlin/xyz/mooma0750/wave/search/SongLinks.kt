package xyz.mooma0750.wave.search

import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import xyz.mooma0750.wave.core.music.Song

/** YouTube Music の曲の URL。 */
fun songUrl(song: Song): String = "https://music.youtube.com/watch?v=${song.videoId}"

/** 曲を共有する（端末の共有の画面を出す。中身は「曲名 - アーティスト」と YouTube Music の URL）。 */
fun shareSong(
    context: android.content.Context,
    song: Song,
) {
    val text = listOfNotNull("${song.title} - ${song.artistsText}".takeIf { song.artists.isNotEmpty() } ?: song.title, songUrl(song)).joinToString("\n")
    val send =
        android.content.Intent(android.content.Intent.ACTION_SEND)
            .setType("text/plain")
            .putExtra(android.content.Intent.EXTRA_TEXT, text)
            .putExtra(android.content.Intent.EXTRA_TITLE, song.title)
    context.startActivity(android.content.Intent.createChooser(send, null).addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK))
}
