package xyz.mooma0750.wave.connect

import android.content.Context
import android.util.AtomicFile
import android.util.Log
import xyz.mooma0750.wave.WaveApplication
import java.io.File
import java.security.KeyStore

/**
 * 連携の古い方式（v1、ADR-0009。2026-10-14 に削除）が端末に残したものを消す。起動のたびに呼び、無ければ何もしない。
 *
 * - `pairs.bin`（noBackupFilesDir）: ペアごとの封筒の鍵を入れたペアの一覧。v2 は読まない
 * - Keystore の `wave-pairs-v1`: `pairs.bin` を包んだ AES 鍵。v2 は `wave-connect-v2` を使う
 * - `pair-offer.txt`・`pair-code.txt`（filesDir）: v1 の debug の確認で書いた QR の文字と6桁（期限切れ）
 *
 * 身元の鍵（Keystore の `wave-device-identity-v1`、[DeviceIdentity]）は v2 も同じ鍵を使うので消さない。
 * `connect-pubkey.txt` も、v2 の中継の家を作り直すときに debug の `connect_info` で書き出す身元の公開鍵なので消さない。
 */
object LegacyConnectCleanup {
    private const val PAIRS_FILE = "pairs.bin"
    private const val PAIRS_KEY_ALIAS = "wave-pairs-v1"
    private val DEBUG_FILES = listOf("pair-offer.txt", "pair-code.txt")

    fun run(context: Context) {
        runCatching {
            val pairs = File(context.noBackupFilesDir, PAIRS_FILE)
            val hadPairs = pairs.exists()
            // AtomicFile の delete は書きかけ・控えのファイルも消す
            AtomicFile(pairs).delete()
            val keyStore = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
            val hadKey = keyStore.containsAlias(PAIRS_KEY_ALIAS)
            if (hadKey) keyStore.deleteEntry(PAIRS_KEY_ALIAS)
            DEBUG_FILES.forEach { File(context.filesDir, it).delete() }
            if (hadPairs || hadKey) Log.i(WaveApplication.LOG_TAG, "connect v1 leftovers removed pairs=$hadPairs key=$hadKey")
        }.onFailure { Log.w(WaveApplication.LOG_TAG, "connect v1 cleanup failed ${it::class.simpleName}") }
    }
}
