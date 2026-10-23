package xyz.mooma0750.wave.playback

import android.net.Uri
import androidx.annotation.OptIn
import androidx.media3.common.C
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DataSpec
import androidx.media3.datasource.FileDataSource
import androidx.media3.datasource.TransferListener
import java.io.File
import xyz.mooma0750.wave.core.music.Feed

/**
 * 鳴らすときは、取り終えた曲（ADR-0016 の 6）を最初に見る。取ってあれば通信も URL の調べ直しもせず、ファイルから鳴らす。
 * 取っていなければ、そのまま後ろ（AudioCache を通す側）で開く。
 */
@OptIn(UnstableApi::class)
class DownloadedFirstDataSource(
    private val lookup: (String) -> File?,
    private val fallback: DataSource,
) : DataSource {
    private val file = FileDataSource()
    private var current: DataSource? = null
    private var openedUri: Uri? = null

    override fun addTransferListener(transferListener: TransferListener) {
        file.addTransferListener(transferListener)
        fallback.addTransferListener(transferListener)
    }

    override fun open(dataSpec: DataSpec): Long {
        val videoId = TrackUri.videoIdOf(dataSpec.uri) ?: TrackUri.episodeUrlOf(dataSpec.uri)?.let(Feed::audioVideoId)
        val downloaded = videoId?.let(lookup)
        if (downloaded != null) {
            current = file
            openedUri = dataSpec.uri
            // getUri は wave:// のままにする（ファイルの場所を Media3 の観測経路に出さない）
            return file.open(dataSpec.buildUpon().setUri(Uri.fromFile(downloaded)).build())
        }
        current = fallback
        openedUri = null
        return fallback.open(dataSpec)
    }

    override fun read(
        buffer: ByteArray,
        offset: Int,
        length: Int,
    ): Int = current?.read(buffer, offset, length) ?: C.RESULT_END_OF_INPUT

    override fun getUri(): Uri? = openedUri ?: current?.uri

    override fun getResponseHeaders(): Map<String, List<String>> = current?.responseHeaders ?: emptyMap()

    override fun close() {
        try {
            current?.close()
        } finally {
            current = null
            openedUri = null
        }
    }

    class Factory(
        private val lookup: (String) -> File?,
        private val fallback: DataSource.Factory,
    ) : DataSource.Factory {
        override fun createDataSource(): DataSource = DownloadedFirstDataSource(lookup, fallback.createDataSource())
    }
}
