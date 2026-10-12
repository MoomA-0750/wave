package xyz.mooma0750.wave.ai

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.AssistChip
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.LinearWavyProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.LinkAnnotation
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextLinkStyles
import androidx.compose.ui.text.withLink
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import xyz.mooma0750.wave.core.ai.AiText
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import xyz.mooma0750.wave.R
import xyz.mooma0750.wave.core.music.Song
import xyz.mooma0750.wave.ui.IconAction

/**
 * Claude に聞く。再生画面の ✨ からはシートで、下のバーの「Claude」のタブからは全面で開く（会話は同じものを続ける）。
 * 今の曲と歌詞を添えて頼み、答えを流しながら出す。Claude が並べた曲はカードで出し、押すと再生する。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AiChatSheet(
    chat: AiChat,
    onPlay: (Song) -> Unit,
    onOpenSettings: () -> Unit,
    onDismiss: () -> Unit,
) {
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)) {
        AiChatPanel(chat, onPlay, onOpenSettings, Modifier.fillMaxWidth().fillMaxHeight(0.9f).imePadding().navigationBarsPadding(), MaterialTheme.typography.titleLargeEmphasized)
    }
}

/** 下のバーの「Claude」のタブ。見出しはほかのタブと同じ大きさにする。 */
@Composable
fun AiChatScreen(
    chat: AiChat,
    onPlay: (Song) -> Unit,
    onOpenSettings: () -> Unit,
) {
    AiChatPanel(chat, onPlay, onOpenSettings, Modifier.fillMaxSize().statusBarsPadding().imePadding().padding(top = 16.dp), MaterialTheme.typography.headlineMediumEmphasized)
}

@Composable
private fun AiChatPanel(
    chat: AiChat,
    onPlay: (Song) -> Unit,
    onOpenSettings: () -> Unit,
    modifier: Modifier,
    titleStyle: androidx.compose.ui.text.TextStyle,
) {
    val state by chat.state.collectAsStateWithLifecycle()
    val playingVideoId by chat.playingVideoId.collectAsStateWithLifecycle()
    var input by remember { mutableStateOf("") }
    var showHistory by remember { mutableStateOf(false) }
    if (showHistory) AiHistorySheet(chat, onDismiss = { showHistory = false })
    val listState = rememberLazyListState()
    LaunchedEffect(state.messages.size, (state.messages.lastOrNull() as? AiMessage.Assistant)?.text?.length) {
        if (state.messages.isNotEmpty()) listState.animateScrollToItem(state.messages.lastIndex)
    }
    val send = { text: String ->
        chat.send(text.trim())
        input = ""
    }
    Column(modifier) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(stringResource(R.string.ai_title), Modifier.weight(1f), style = titleStyle)
            IconAction(R.drawable.ic_history, stringResource(R.string.ai_history), { showHistory = true })
            if (state.messages.isNotEmpty()) TextButton(onClick = chat::clear) { Text(stringResource(R.string.ai_new_chat)) }
        }
        if (state.running) LinearWavyProgressIndicator(Modifier.fillMaxWidth().padding(horizontal = 16.dp))
        LazyColumn(Modifier.weight(1f).fillMaxWidth(), state = listState, contentPadding = androidx.compose.foundation.layout.PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            if (state.messages.isEmpty()) {
                item { Text(stringResource(R.string.ai_intro), color = MaterialTheme.colorScheme.onSurfaceVariant) }
            }
            items(state.messages.size) { index ->
                when (val message = state.messages[index]) {
                    is AiMessage.User ->
                        Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.CenterEnd) {
                            Text(
                                message.text,
                                Modifier.widthIn(max = 320.dp).clip(MaterialTheme.shapes.large).background(MaterialTheme.colorScheme.primaryContainer).padding(horizontal = 14.dp, vertical = 8.dp),
                                color = MaterialTheme.colorScheme.onPrimaryContainer,
                            )
                        }
                    is AiMessage.Assistant -> {
                        // 答えの中の [mm:ss] は、話している曲がまだ鳴っていれば押せる（その位置へ飛ぶ）
                        val videoId = message.videoId?.takeIf { it == playingVideoId }
                        val linkStyle = TextLinkStyles(SpanStyle(color = MaterialTheme.colorScheme.onSecondaryContainer, background = MaterialTheme.colorScheme.secondaryContainer))
                        Text(markdownText(message.text, linkStyle.takeIf { videoId != null }) { positionMs -> videoId?.let { chat.seek(it, positionMs) } }, style = MaterialTheme.typography.bodyLarge)
                    }
                    is AiMessage.Action -> Text("・" + message.text, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    is AiMessage.Songs ->
                        LazyRow(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                            items(message.songs, key = { it.videoId }) { song -> SongCard(song) { onPlay(song) } }
                        }
                    is AiMessage.Failure ->
                        Column {
                            Text(failureText(message.text), color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
                            if (message.text == AiChat.NOT_CONFIGURED || message.text == AiChat.UNAUTHORIZED) {
                                TextButton(onClick = onOpenSettings) { Text(stringResource(R.string.ai_open_settings)) }
                            }
                        }
                }
            }
        }
        // よく頼むこと
        val suggestions = listOf(stringResource(R.string.ai_suggest_meaning), stringResource(R.string.ai_suggest_similar), stringResource(R.string.ai_suggest_playlist))
        LazyRow(contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            items(suggestions) { suggestion -> AssistChip(onClick = { send(suggestion) }, label = { Text(suggestion) }, enabled = !state.running) }
        }
        Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            OutlinedTextField(
                value = input,
                onValueChange = { input = it },
                modifier = Modifier.weight(1f),
                placeholder = { Text(stringResource(R.string.ai_placeholder)) },
                maxLines = 4,
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Send),
                keyboardActions = KeyboardActions(onSend = { if (input.isNotBlank() && !state.running) send(input) }),
                shape = MaterialTheme.shapes.extraLarge,
            )
            if (state.running) {
                IconAction(R.drawable.ic_close, stringResource(R.string.ai_stop), chat::stop)
            } else {
                IconAction(R.drawable.ic_arrow_upward, stringResource(R.string.ai_send), { send(input) }, enabled = input.isNotBlank())
            }
        }
    }
}

/**
 * Claude の答えの Markdown のうち、太字（**…**）と見出し（# …）だけを見た目にする（ほかの記号はそのまま）。
 * linkStyle があれば、歌詞の時刻 [mm:ss] を押せる札（▶ mm:ss）にし、押すと onSeek にその位置を渡す。
 */
internal fun markdownText(
    text: String,
    linkStyle: TextLinkStyles? = null,
    onSeek: (Long) -> Unit = {},
): androidx.compose.ui.text.AnnotatedString =
    androidx.compose.ui.text.buildAnnotatedString {
        text.lines().forEachIndexed { index, raw ->
            if (index > 0) append('\n')
            val heading = Regex("^#{1,6}\\s+").find(raw)
            val line = if (heading != null) raw.substring(heading.value.length) else raw
            if (heading != null) pushStyle(SpanStyle(fontWeight = FontWeight.Bold)) // m3e-allow: AI の返答の Markdown の見出し（文の中の一部の太字）
            line.split("**").forEachIndexed { part, piece ->
                if (part % 2 == 1) pushStyle(SpanStyle(fontWeight = FontWeight.Bold)) // m3e-allow: AI の返答の Markdown の **太字**
                appendWithTimestamps(piece, linkStyle, onSeek)
                if (part % 2 == 1) pop()
            }
            if (heading != null) pop()
        }
    }

private fun androidx.compose.ui.text.AnnotatedString.Builder.appendWithTimestamps(
    text: String,
    linkStyle: TextLinkStyles?,
    onSeek: (Long) -> Unit,
) {
    if (linkStyle == null) {
        append(text)
        return
    }
    var at = 0
    AiText.timestamps(text).forEach { stamp ->
        append(text.substring(at, stamp.range.first))
        withLink(LinkAnnotation.Clickable("seek:${stamp.positionMs}", linkStyle) { onSeek(stamp.positionMs) }) { append("\u00A0▶\u00A0${stamp.label}\u00A0") }
        at = stamp.range.last + 1
    }
    append(text.substring(at))
}

/** これまでの会話（端末の中だけ）。押すと開いて続け、ゴミ箱で消す。 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun AiHistorySheet(
    chat: AiChat,
    onDismiss: () -> Unit,
) {
    val history by chat.history.collectAsStateWithLifecycle()
    val state by chat.state.collectAsStateWithLifecycle()
    ModalBottomSheet(onDismissRequest = onDismiss) {
        Text(stringResource(R.string.ai_history), Modifier.padding(horizontal = 24.dp, vertical = 8.dp), style = MaterialTheme.typography.titleLargeEmphasized)
        if (history.isEmpty()) {
            Text(stringResource(R.string.ai_history_empty), Modifier.padding(24.dp), color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        val format = remember { java.text.DateFormat.getDateTimeInstance(java.text.DateFormat.MEDIUM, java.text.DateFormat.SHORT) }
        LazyColumn(Modifier.fillMaxWidth().navigationBarsPadding()) {
            items(history, key = { it.id }) { conversation ->
                ListItem(
                    headlineContent = { Text(conversation.title.ifEmpty { stringResource(R.string.ai_history_untitled) }, maxLines = 2, overflow = TextOverflow.Ellipsis) },
                    supportingContent = { Text(format.format(java.util.Date(conversation.updatedAtMs))) },
                    trailingContent = { IconAction(R.drawable.ic_delete, stringResource(R.string.ai_history_delete), { chat.delete(conversation.id) }) },
                    colors = if (conversation.id == state.conversationId) ListItemDefaults.colors(containerColor = MaterialTheme.colorScheme.secondaryContainer) else ListItemDefaults.colors(),
                    modifier =
                        Modifier.clickable {
                            chat.open(conversation.id)
                            onDismiss()
                        },
                )
            }
        }
    }
}

@Composable
private fun failureText(code: String): String =
    when (code) {
        AiChat.NOT_CONFIGURED -> stringResource(R.string.ai_not_configured)
        AiChat.UNREACHABLE -> stringResource(R.string.ai_unreachable)
        AiChat.UNAUTHORIZED -> stringResource(R.string.ai_unauthorized)
        else -> stringResource(R.string.ai_failed, code)
    }

@Composable
private fun SongCard(
    song: Song,
    onClick: () -> Unit,
) {
    Column(Modifier.widthIn(max = 120.dp).clickable(onClick = onClick)) {
        coil3.compose.AsyncImage(
            model = song.thumbnailUrl,
            contentDescription = null,
            modifier = Modifier.size(120.dp).clip(MaterialTheme.shapes.small),
            contentScale = ContentScale.Crop,
        )
        Text(song.title, Modifier.padding(top = 4.dp), maxLines = 2, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.bodyMedium)
        Text(song.artists.joinToString(", ") { it.name }, maxLines = 1, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}
