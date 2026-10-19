package com.amusic.player.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.Checkbox
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.amusic.player.data.lyrics.LyricsCandidate
import com.amusic.player.data.lyrics.LyricsSearchResult
import com.amusic.player.data.lyrics.OnlineLyricsClient
import kotlinx.coroutines.launch
import kotlin.math.abs

/**
 * 在线搜索歌词。
 *
 * 只用公开的在线歌词接口：拿歌名搜，结果列表里按时长最接近标注哪条最像本地这首歌，
 * 点开预览，保存成同名 .lrc（可选同时写进音频的内嵌标签）。没有浏览器、没有剪贴板那套。
 *
 * 保存统一走 `LyricsRepository.saveLyrics`：同名 .lrc **直接覆盖**（不留 .bak 备份），
 * 繁体歌词先自动转成简体。
 */
@Composable
fun OnlineLyricsDialog(
    /** 当前播放区域里的歌名；为空表示没有正在播放的歌，用户得自己输关键词 */
    songTitle: String,
    localDurationMs: Long,
    /** 有正在播放的歌才能保存（得有文件可以挂） */
    canSave: Boolean,
    /** 这首歌的容器支持写内嵌歌词吗（FLAC / MP3 支持，MP4 等不支持） */
    embeddedSupported: Boolean,
    onSave: (text: String, alsoEmbedded: Boolean) -> Unit,
    onDismiss: () -> Unit,
) {
    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false),
    ) {
        // 这个弹窗**故意保持全屏**（搜索框、键盘、结果列表都要地方），
        // 但底色换成和别的弹窗同一套的玻璃黑，风格统一
        Surface(color = GlassMenuFill, modifier = Modifier.fillMaxSize()) {
            OnlineLyricsSearchView(
                songTitle = songTitle,
                localDurationMs = localDurationMs,
                canSave = canSave,
                embeddedSupported = embeddedSupported,
                onDismiss = onDismiss,
                onSave = onSave,
            )
        }
    }
}

/** 主路径：应用内搜索 + 预览 + 保存 */
@Composable
private fun OnlineLyricsSearchView(
    songTitle: String,
    localDurationMs: Long,
    canSave: Boolean,
    embeddedSupported: Boolean,
    onDismiss: () -> Unit,
    onSave: (String, Boolean) -> Unit,
) {
    val client = remember { OnlineLyricsClient() }
    val scope = rememberCoroutineScope()

    // 默认就拿歌名去搜（这个入口的用法就是"输入音频文件名去搜索"）
    var query by remember { mutableStateOf(songTitle) }
    var loading by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    var results by remember { mutableStateOf<List<LyricsCandidate>>(emptyList()) }
    var picked by remember { mutableStateOf<LyricsCandidate?>(null) }
    var searched by remember { mutableStateOf(false) }
    // 默认勾上"同时写内嵌"：这个入口的价值就在"歌词跟着文件走"，能写就写
    var alsoEmbedded by remember { mutableStateOf(true) }

    fun runSearch() {
        loading = true
        error = null
        picked = null
        scope.launch {
            when (val r = client.search(query)) {
                is LyricsSearchResult.Ok -> {
                    results = r.candidates
                    searched = true
                }
                is LyricsSearchResult.Failed -> {
                    error = r.message
                    results = emptyList()
                    searched = true
                }
            }
            loading = false
        }
    }

    // 有歌名（也就是播放区域有歌）才自动搜一次；没有就让用户自己输入后点搜索
    LaunchedEffect(songTitle) { if (query.isNotBlank()) runSearch() }

    Column(
        Modifier
            .fillMaxSize()
            .statusBarsPadding()
            .imePadding(),
    ) {
        // ---------------- 顶栏 ----------------
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 4.dp, vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            IconButton(onClick = onDismiss) {
                Icon(Icons.Filled.Close, contentDescription = "关闭")
            }
            OutlinedTextField(
                value = query,
                onValueChange = { query = it },
                singleLine = true,
                // 没有正在播放的歌时，这个框是空的——提示语要说清"为什么这里是空的"，
                // 否则用户会奇怪"平时显示歌名的地方怎么变成一句提示了"
                placeholder = {
                    Text(
                        if (songTitle.isBlank()) {
                            "没有正在播放的歌曲，输入歌名或歌手"
                        } else {
                            "歌名 / 歌手，可修改后重新搜"
                        },
                    )
                },
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                keyboardActions = KeyboardActions(onSearch = { runSearch() }),
                textStyle = MaterialTheme.typography.bodySmall,
                modifier = Modifier.weight(1f),
            )
            TextButton(onClick = { runSearch() }, enabled = query.isNotBlank() && !loading) {
                Text("搜索")
            }
        }

        // 这里不放"歌词来自在线搜索 · 选一条保存（…）"这类小字：
        // 搜索框和结果本身已经说明一切，那行字只是噪音。
        // 没有正在播放的歌时也不用在这里解释 —— 搜索框的占位文字已经写明了。

        Spacer(Modifier.height(4.dp))

        Box(Modifier.weight(1f)) {
            when {
                loading -> CircularProgressIndicator(
                    modifier = Modifier.align(Alignment.TopCenter).padding(top = 20.dp).size(22.dp),
                    strokeWidth = 2.dp,
                )

                error != null -> CenteredHint(
                    title = error.orEmpty(),
                    action = { OutlinedButton(onClick = { runSearch() }) { Text("重试") } },
                )

                searched && results.isEmpty() -> CenteredHint(
                    title = "没搜到歌词",
                    subtitle = "换个关键词试试（比如只留歌名，去掉「伴奏」「现场」这类后缀）",
                )

                else -> LazyColumn(Modifier.fillMaxSize()) {
                    itemsIndexed(results) { index, item ->
                        CandidateRow(
                            candidate = item,
                            localDurationMs = localDurationMs,
                            expanded = picked?.id == item.id,
                            canSave = canSave,
                            embeddedSupported = embeddedSupported,
                            alsoEmbedded = alsoEmbedded,
                            onToggleEmbedded = { alsoEmbedded = it },
                            onClick = { picked = if (picked?.id == item.id) null else item },
                            // 传"真的会写内嵌"的那个值：这个格式不支持写内嵌时勾选框是禁用的，
                            // 再按 alsoEmbedded（默认 true）传下去，保存后会报一句"内嵌没写入"，
                            // 用户看着自己根本没勾过的选项会觉得莫名其妙
                            onSave = { onSave(item.lyrics, alsoEmbedded && embeddedSupported) },
                        )
                    }
                }
            }
        }

        Spacer(Modifier.navigationBarsPadding())
    }
}

/**
 * 一条搜索结果。
 *
 * 展开后显示歌词预览 + 保存按钮——先给用户看一眼再存，别让人盲存。
 */
@Composable
private fun CandidateRow(
    candidate: LyricsCandidate,
    localDurationMs: Long,
    expanded: Boolean,
    canSave: Boolean,
    embeddedSupported: Boolean,
    alsoEmbedded: Boolean,
    onToggleEmbedded: (Boolean) -> Unit,
    onClick: () -> Unit,
    onSave: () -> Unit,
) {
    val primary = MaterialTheme.colorScheme.primary
    val durationMatches = localDurationMs > 0L &&
        abs(candidate.durationSec * 1000L - localDurationMs) <= DURATION_TOLERANCE_MS

    Column(
        Modifier
            .fillMaxWidth()
            .background(if (expanded) primary.copy(alpha = 0.10f) else Color.Transparent)
            .clickable { onClick() }
            .padding(horizontal = 16.dp, vertical = 10.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(
                    text = candidate.trackName.ifBlank { "（无标题）" },
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.onBackground,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    text = listOfNotNull(
                        candidate.artistName.takeIf { it.isNotBlank() },
                        candidate.albumName.takeIf { it.isNotBlank() },
                    ).joinToString(" · "),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            Spacer(Modifier.width(8.dp))
            Column(horizontalAlignment = Alignment.End) {
                Text(
                    text = formatSeconds(candidate.durationSec),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Text(
                    text = if (candidate.hasTimeline) "有时间轴" else "纯歌词",
                    style = MaterialTheme.typography.labelSmall,
                    color = if (candidate.hasTimeline) primary else MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }

        // 时长和本地文件一致 → 大概率就是同一个版本，标出来省得挑错
        if (durationMatches) {
            Text(
                text = "★ 时长和这首歌一致（${formatSeconds(candidate.durationSec)}），最可能就是这一条",
                style = MaterialTheme.typography.labelSmall,
                color = primary,
                modifier = Modifier.padding(top = 4.dp),
            )
        }
        if (candidate.instrumental) {
            Text(
                text = "站点标记为纯音乐（可能没有歌词）",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 4.dp),
            )
        }

        if (expanded) {
            Spacer(Modifier.height(8.dp))
            Surface(
                color = MaterialTheme.colorScheme.surfaceVariant,
                shape = RoundedCornerShape(8.dp),
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text(
                    text = candidate.lyrics.take(1200),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier
                        .heightIn(max = 220.dp)
                        .verticalScroll(rememberScrollState())
                        .padding(10.dp),
                )
            }
            Spacer(Modifier.height(8.dp))

            if (canSave) {
                // 内嵌歌词：能写就默认勾上，写了歌词就跟着音频文件走（换设备、拷来拷去都在）
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Checkbox(
                        // 勾选框显示的是**真正会被写进去的**那个值，别出现"勾着但没写"的错觉
                        checked = alsoEmbedded && embeddedSupported,
                        onCheckedChange = { onToggleEmbedded(it) },
                        enabled = embeddedSupported,
                    )
                    Column(Modifier.weight(1f)) {
                        Text(
                            text = "同时写入内嵌歌词",
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.onSurface,
                        )
                        // 正常情况不加说明（这里只要勾选框和那句话）；
                        // 只有格式不支持时保留一行，解释为什么这个勾是灰的
                        if (!embeddedSupported) Text(
                            text = "这个格式（M4A / MP4 / OGG）不支持写内嵌，只会存同名 .lrc",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
                Spacer(Modifier.height(6.dp))
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                    Button(onClick = onSave) { Text("保存为这首歌的歌词") }
                }
            } else {
                Text(
                    text = "没有正在播放的歌曲，暂时没法保存；先在歌曲列表里点一首歌再回来",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

@Composable
private fun CenteredHint(
    title: String,
    subtitle: String? = null,
    action: (@Composable () -> Unit)? = null,
) {
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(12.dp),
            modifier = Modifier.padding(horizontal = 32.dp),
        ) {
            Text(
                text = title,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = androidx.compose.ui.text.style.TextAlign.Center,
            )
            if (subtitle != null) {
                Text(
                    text = subtitle,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = androidx.compose.ui.text.style.TextAlign.Center,
                )
            }
            action?.invoke()
        }
    }
}

/** 秒 → m:ss */
private fun formatSeconds(seconds: Int): String {
    if (seconds <= 0) return "--:--"
    return "%d:%02d".format(seconds / 60, seconds % 60)
}

/** 本地时长和候选时长差在这个范围内，就认为"是同一条歌"（编曲、静音头尾会有几秒差异） */
private const val DURATION_TOLERANCE_MS = 5_000L
