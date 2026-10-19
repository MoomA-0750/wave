package xyz.mooma0750.wave.api

import java.io.File
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.nio.file.attribute.PosixFilePermissions

/**
 * wave-api のデータの置き場（WAVE_API_DATA）。身元の鍵・家の記録・ログイン・トークンを、ほかの人が読めない権限（600）で置く。
 * 書くときは一時ファイルに書いてから置き換える（途中で落ちても前の中身が残る）。
 */
class DataDir(
    val root: File,
) {
    init {
        root.mkdirs()
        runCatching { Files.setPosixFilePermissions(root.toPath(), PosixFilePermissions.fromString("rwx------")) }
    }

    fun file(name: String): File = File(root, name)

    fun read(name: String): String? = file(name).takeIf { it.isFile }?.let { runCatching { it.readText() }.getOrNull() }

    fun write(
        name: String,
        text: String,
    ) {
        val target = file(name)
        val temp = File(root, ".$name.tmp")
        temp.delete()
        temp.createNewFile()
        runCatching { Files.setPosixFilePermissions(temp.toPath(), PosixFilePermissions.fromString("rw-------")) }
        temp.writeText(text)
        Files.move(temp.toPath(), target.toPath(), StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE)
    }

    fun delete(name: String) {
        file(name).delete()
    }

    companion object {
        const val KEYS = "keys.json"
        const val CLIENT = "client.json"
        const val LABELS = "labels.json"
        const val LOGIN = "login.json"
        const val TOKEN = "token"
    }
}
