package xyz.mooma0750.wave.navigation

import org.junit.Test
import xyz.mooma0750.wave.core.music.AlbumRef
import xyz.mooma0750.wave.core.music.ArtistRef
import xyz.mooma0750.wave.core.music.Song
import kotlin.test.assertEquals

class SongActionsTest {
    @Test
    fun closingClosesTheSheetFirstOnlyForNavigatingActions() {
        val calls = mutableListOf<String>()
        val actions =
            SongActions(
                playNext = { calls += "playNext" },
                addToQueue = { calls += "addToQueue" },
                openArtist = { calls += "artist" },
                openAlbum = { calls += "album" },
                addToPlaylist = { calls += "addToPlaylist" },
                startRadio = { calls += "radio" },
            )
        val closing = actions.closing { calls += "close" }
        val song = Song("v", "t", emptyList(), null, null, null)
        closing.playNext(song)
        closing.addToQueue(song)
        closing.openArtist(ArtistRef("a", "UC"))
        closing.openAlbum(AlbumRef("b", "MP"))
        closing.addToPlaylist(song)
        closing.startRadio(song)
        // キューに足す・プレイリストに追加する（ダイアログが上に重なる）のはシートの上のまま。画面を開くのは先に閉じる
        assertEquals(listOf("playNext", "addToQueue", "close", "artist", "close", "album", "addToPlaylist", "radio"), calls)
    }
}
