package xyz.mooma0750.wave.settings

import android.app.Activity
import android.app.LocaleManager
import android.content.Context
import android.content.res.Configuration
import android.content.res.Resources
import android.os.Build
import android.os.LocaleList
import java.util.Locale

/**
 * アプリの中の言語（設定の「画面の言語」: 端末に合わせる／日本語／English。Mac・Windows・iPhone と同じ）。
 * YouTube から来る文字（棚の見出しなど）の言語と地域も、ここで1か所で決める（[youTubeLocale]）。
 *
 * Android 13 以降は OS のアプリごとの言語（LocaleManager）に任せる（OS の設定の「アプリの言語」とも同じ値。変えるとすぐ画面に効く）。
 * それより前（Pixel 3 の Android 12 など）は OS に口が無いので、選んだ言語をこの端末に保存し、画面・再生サービスの文脈を
 * その言語で作り直す（[wrap]）。アプリ全体の文字（通知など）は次の起動から変わる。
 */
object AppLanguage {
    enum class Choice(
        /** 固定する言語（端末に合わせるなら null）。 */
        val tag: String?,
    ) {
        System(null),
        Japanese("ja"),
        English("en"),
    }

    private const val PREFS = "app_language"
    private const val KEY_TAG = "tag"

    /** 今の選択。 */
    fun current(context: Context): Choice {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            val locales = context.getSystemService(LocaleManager::class.java)?.applicationLocales
            return choiceOf(locales?.takeUnless { it.isEmpty }?.get(0)?.language)
        }
        return choiceOf(saved(context))
    }

    /**
     * 選んだ言語にする。Android 13 以降は OS が画面を作り直す。それより前は保存して、この画面を作り直す。
     * どちらも YouTube から来る文字は次の起動から変わる（通信の部品は起動のときに言語を決める）。
     */
    fun set(
        activity: Activity,
        choice: Choice,
    ) {
        activity.applicationContext
            .getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit()
            .apply { if (choice.tag == null) remove(KEY_TAG) else putString(KEY_TAG, choice.tag) }
            .apply()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            activity.getSystemService(LocaleManager::class.java)?.applicationLocales = choice.tag?.let { LocaleList.forLanguageTags(it) } ?: LocaleList.getEmptyLocaleList()
        } else {
            activity.recreate()
        }
    }

    /** Android 12 以前: 言語を固定していれば、その言語の文脈にする（Application・Activity・Service の attachBaseContext で使う）。 */
    fun wrap(base: Context): Context {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) return base
        val tag = saved(base) ?: return base
        val configuration = Configuration(base.resources.configuration).apply { setLocales(LocaleList.forLanguageTags(tag)) }
        return base.createConfigurationContext(configuration)
    }

    /** 画面の言語（wave の文言がある日本語か英語。端末がほかの言語なら英語の文言が出るので英語）。 */
    fun uiLanguage(context: Context): String = if ((current(context).tag ?: systemLocale().language) == "ja") "ja" else "en"

    /** YouTube に送る言語（hl。画面の言語と同じ）と地域（gl。端末の地域。アプリの言語を固定しても変えない）。 */
    fun youTubeLocale(context: Context): Pair<String, String> = uiLanguage(context) to systemLocale().country.ifBlank { "JP" }

    /** 端末の言語（アプリごとの言語の影響を受けない）。 */
    private fun systemLocale(): Locale = Resources.getSystem().configuration.locales[0] ?: Locale.getDefault()

    private fun saved(context: Context): String? = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(KEY_TAG, null)

    private fun choiceOf(language: String?): Choice = Choice.entries.firstOrNull { it.tag != null && it.tag == language } ?: Choice.System
}
