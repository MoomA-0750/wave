package xyz.mooma0750.wave.wear

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.res.stringResource
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.wear.compose.foundation.lazy.items
import xyz.mooma0750.wave.R
import xyz.mooma0750.wave.ai.AiChat
import xyz.mooma0750.wave.ai.AiMessage
import xyz.mooma0750.wave.core.music.Song

/**
 * Claude に聞く（スマホの「Claude」のタブと同じ会話のしかた）。頼みは声かキーボードで入れる。
 * 時計は Claude の中継（tailnet の中）に届かないので、スマホに中継してもらう。曲を流すなどの操作は時計の上で行う。
 */
@Composable
fun AiScreen(
    chat: AiChat,
    onPlay: (Song) -> Unit,
) {
    val state by chat.state.collectAsStateWithLifecycle()
    val ask = rememberTextInput(stringResource(R.string.ai_placeholder)) { chat.send(it) }
    val suggestions = listOf(R.string.ai_suggest_meaning, R.string.ai_suggest_similar, R.string.ai_suggest_playlist).map { stringResource(it) }
    // 新しい頼み・答えが来たら、いちばん下（新しいもの）へ送る
    val listState = androidx.wear.compose.foundation.lazy.rememberTransformingLazyColumnState()
    val last = state.messages.lastOrNull()
    androidx.compose.runtime.LaunchedEffect(state.messages.size, (last as? AiMessage.Assistant)?.text?.length?.div(200), state.running) {
        val count = listState.layoutInfo.totalItemsCount
        if (state.messages.isNotEmpty() && count > 0) listState.animateScrollToItem(count - 1)
    }
    ListScreen(
        stringResource(R.string.ai_title),
        state = listState,
        edgeButton = {
            if (state.running) MainEdgeButton(R.drawable.ic_close, stringResource(R.string.ai_stop), onClick = chat::stop) else MainEdgeButton(R.drawable.ic_ai, stringResource(R.string.ai_send), onClick = ask)
        },
    ) { look ->
        if (state.messages.isEmpty()) {
            item(key = "intro") { MessageRow(look, stringResource(R.string.ai_intro)) }
            items(suggestions, key = { "s-$it" }) { text -> ActionRow(look, R.drawable.ic_ai, text) { chat.send(text) } }
        } else {
            item(key = "new") { ActionRow(look, R.drawable.ic_refresh, stringResource(R.string.ai_new_chat), enabled = !state.running, onClick = chat::clear) }
        }
        // 曲の候補は1曲ずつの行にする（1つの行に並べると、スクロールの縮みが崩れる）
        val rows = state.messages.flatMapIndexed { index, message -> if (message is AiMessage.Songs) message.songs.mapIndexed { songIndex, song -> Triple(index, songIndex, AiMessage.Songs(listOf(song))) } else listOf(Triple(index, 0, message)) }
        items(rows, key = { (index, songIndex, _) -> "m-$index-$songIndex" }) { (_, _, message) ->
            when (message) {
                is AiMessage.User -> EntryRow(look, message.text, null, null, icon = R.drawable.ic_person) {}
                is AiMessage.Assistant -> MessageRow(look, message.text)
                is AiMessage.Action -> MessageRow(look, "・" + message.text)
                is AiMessage.Songs -> message.songs.first().let { song -> EntryRow(look, song.title, song.subtitle, song.thumbnailUrl) { onPlay(song) } }
                is AiMessage.Failure ->
                    MessageRow(
                        look,
                        when (message.text) {
                            AiChat.NOT_CONFIGURED -> stringResource(R.string.wear_ai_not_configured)
                            AiChat.UNREACHABLE -> stringResource(R.string.wear_ai_unreachable)
                            AiChat.UNAUTHORIZED -> stringResource(R.string.ai_unauthorized)
                            else -> stringResource(R.string.ai_failed, message.text)
                        },
                    )
            }
        }
        if (state.running) item(key = "running") { LoadingRow(look) }
    }
}
