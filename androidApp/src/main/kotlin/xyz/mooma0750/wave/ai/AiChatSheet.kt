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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.AssistChip
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.LinearProgressIndicator
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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import xyz.mooma0750.wave.R
import xyz.mooma0750.wave.core.music.Song
import xyz.mooma0750.wave.ui.IconAction

/**
 * Claude に聞く（再生画面から開く）。今の曲と歌詞を添えて頼み、答えを流しながら出す。
 * Claude が並べた曲はカードで出し、押すと再生する。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AiChatSheet(
    chat: AiChat,
    onPlay: (Song) -> Unit,
    onOpenSettings: () -> Unit,
    onDismiss: () -> Unit,
) {
    val state by chat.state.collectAsStateWithLifecycle()
    var input by remember { mutableStateOf("") }
    val listState = rememberLazyListState()
    LaunchedEffect(state.messages.size, (state.messages.lastOrNull() as? AiMessage.Assistant)?.text?.length) {
        if (state.messages.isNotEmpty()) listState.animateScrollToItem(state.messages.lastIndex)
    }
    val send = { text: String ->
        chat.send(text.trim())
        input = ""
    }
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)) {
        Column(Modifier.fillMaxWidth().fillMaxHeight(0.9f).imePadding().navigationBarsPadding()) {
            Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp), verticalAlignment = Alignment.CenterVertically) {
                Text(stringResource(R.string.ai_title), Modifier.weight(1f), style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
                if (state.messages.isNotEmpty()) TextButton(onClick = chat::clear) { Text(stringResource(R.string.ai_new_chat)) }
            }
            if (state.running) LinearProgressIndicator(Modifier.fillMaxWidth().padding(horizontal = 16.dp))
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
                                    Modifier.widthIn(max = 320.dp).clip(RoundedCornerShape(16.dp)).background(MaterialTheme.colorScheme.primaryContainer).padding(horizontal = 14.dp, vertical = 8.dp),
                                    color = MaterialTheme.colorScheme.onPrimaryContainer,
                                )
                            }
                        is AiMessage.Assistant -> Text(markdownText(message.text), style = MaterialTheme.typography.bodyLarge)
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
                    shape = RoundedCornerShape(24.dp),
                )
                if (state.running) {
                    IconAction(R.drawable.ic_close, stringResource(R.string.ai_stop), chat::stop)
                } else {
                    IconAction(R.drawable.ic_arrow_upward, stringResource(R.string.ai_send), { send(input) }, enabled = input.isNotBlank())
                }
            }
        }
    }
}

/** Claude の答えの Markdown のうち、太字（**…**）と見出し（# …）だけを見た目にする（ほかの記号はそのまま）。 */
internal fun markdownText(text: String): androidx.compose.ui.text.AnnotatedString =
    androidx.compose.ui.text.buildAnnotatedString {
        text.lines().forEachIndexed { index, raw ->
            if (index > 0) append('\n')
            val heading = Regex("^#{1,6}\\s+").find(raw)
            val line = if (heading != null) raw.substring(heading.value.length) else raw
            if (heading != null) pushStyle(androidx.compose.ui.text.SpanStyle(fontWeight = FontWeight.Bold))
            line.split("**").forEachIndexed { part, piece ->
                if (part % 2 == 1) withStyle(androidx.compose.ui.text.SpanStyle(fontWeight = FontWeight.Bold)) { append(piece) } else append(piece)
            }
            if (heading != null) pop()
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
            modifier = Modifier.size(120.dp).clip(RoundedCornerShape(6.dp)),
            contentScale = ContentScale.Crop,
        )
        Text(song.title, Modifier.padding(top = 4.dp), maxLines = 2, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.bodyMedium)
        Text(song.artists.joinToString(", ") { it.name }, maxLines = 1, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}
