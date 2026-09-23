package com.amusic.player.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.graphics.Color
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.amusic.player.data.Playlist
import com.amusic.player.data.Song
import com.amusic.player.data.media.DeviceAudio
import com.amusic.player.data.media.FileBrowser
import com.amusic.player.data.search.LocalFileHit
import com.amusic.player.data.search.LocalSearch
import kotlinx.coroutines.delay

/**
 * 搜索弹窗。
 *
 * 用全屏弹窗而不是第三个可滑动页面：本软件只有"歌曲列表"和"歌词"两个水平滑动的界面，
 * 其它功能一律用弹窗，避免把主界面变成多页导航。
 *
 * 结果分两级，优先级如下：
 *   1. 已导入的歌曲（所有列表里的，内存里匹配，立刻出结果）
 *   2. 手机里的音频文件（先扫 Music / MP3 这类优先目录，再扫其余目录）
 */
@Composable
fun SearchDialog(
    playlists: List<Playlist>,
    mediaIndex: Map<String, DeviceAudio>,
    hasAllFilesAccess: Boolean,
    onRequestAllFilesAccess: () -> Unit,
    onPlayImported: (Song) -> Unit,
    onImportFile: (DeviceAudio) -> Unit,
    onDismiss: () -> Unit,
) {
    var query by remember { mutableStateOf("") }
    var fileHits by remember { mutableStateOf<List<LocalFileHit>>(emptyList()) }
    var scanning by remember { mutableStateOf(false) }
    var scanFinished by remember { mutableStateOf(false) }

    val focusRequester = remember { FocusRequester() }
    LaunchedEffect(Unit) {
        delay(120L)
        focusRequester.requestFocus()
    }

    // 已导入的歌曲：直接在内存里过滤，不需要等扫描
    val importedHits: List<Pair<Playlist, Song>> = remember(query, playlists) {
        val keyword = query.trim().lowercase()
        if (keyword.isEmpty()) {
            emptyList()
        } else {
            playlists.flatMap { list -> list.songs.map { list to it } }
                .filter { (_, song) ->
                    song.title.lowercase().contains(keyword) ||
                        song.artist.lowercase().contains(keyword)
                }
        }
    }

    // 本地文件：输入停顿一下再扫，避免每敲一个字就整盘遍历
    LaunchedEffect(query) {
        val keyword = query.trim()
        if (keyword.isEmpty()) {
            fileHits = emptyList()
            scanning = false
            scanFinished = false
            return@LaunchedEffect
        }
        delay(300L)
        scanning = true
        scanFinished = false
        fileHits = emptyList()
        val collected = mutableListOf<LocalFileHit>()
        LocalSearch.searchAudioFiles(keyword).collect { hit ->
            collected += hit
            fileHits = collected.toList()
        }
        scanning = false
        scanFinished = true
    }

    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false),
    ) {
        Surface(
            shape = GlassShape,
            color = GlassFill,
            border = GlassBorder,
        ) {
            Column(
                Modifier
                    .fillMaxSize()
                    .statusBarsPadding()
                    .navigationBarsPadding(),
            ) {
                // 搜索框：打开就自动聚焦，输入法直接弹出来
                Row(
                    Modifier.fillMaxWidth().padding(horizontal = 4.dp, vertical = 4.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    IconButton(onClick = onDismiss) {
                        Icon(Icons.Filled.Close, contentDescription = "关闭搜索")
                    }
                    OutlinedTextField(
                        value = query,
                        onValueChange = { query = it },
                        singleLine = true,
                        // 胶囊形（和全应用其它控件同一套）
                        shape = PillShape,
                        leadingIcon = {
                            Icon(Icons.Filled.Search, contentDescription = null, modifier = Modifier.size(18.dp))
                        },
                        placeholder = { Text("搜索歌曲，或手机里的音频文件") },
                        modifier = Modifier.weight(1f).focusRequester(focusRequester),
                    )
                    Spacer(Modifier.width(8.dp))
                }

                // 状态行
                Row(
                    Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 2.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        text = buildString {
                            append("已导入 ${importedHits.size} 首")
                            if (query.isNotBlank()) {
                                append("　本地文件 ${fileHits.size} 个")
                                if (scanning) append("（扫描中…）")
                            }
                        },
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.weight(1f),
                    )
                    if (scanning) {
                        CircularProgressIndicator(modifier = Modifier.size(16.dp), strokeWidth = 2.dp)
                    }
                }

                if (!hasAllFilesAccess) {
                    Surface(
                        shape = RoundedCornerShape(14.dp),
                        color = Color.White.copy(alpha = 0.06f),
                        border = GlassBorder,
                        modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 4.dp),
                    ) {
                        Row(
                            Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Text(
                                text = "没有\"所有文件访问\"权限，只能搜到已导入的歌曲，扫不到手机里的音频文件",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.weight(1f),
                            )
                            androidx.compose.material3.TextButton(onClick = onRequestAllFilesAccess) {
                                Text("去授权")
                            }
                        }
                    }
                }

                Box(Modifier.fillMaxSize()) {
                    when {
                        query.isBlank() -> CenterHint(
                            "输入歌名或文件名开始搜索\n先查已导入的歌曲，再扫描手机里的音频文件",
                        )

                        importedHits.isEmpty() && fileHits.isEmpty() && !scanning ->
                            CenterHint("没有找到匹配的歌曲")

                        else -> LazyColumn(contentPadding = PaddingValues(bottom = 32.dp)) {
                            if (importedHits.isNotEmpty()) {
                                item { SectionHeader("已导入的歌曲") }
                                items(importedHits, key = { "${it.first.id}-${it.second.id}" }) { (list, song) ->
                                    ImportedRow(song = song, listName = list.name) { onPlayImported(song) }
                                }
                            }
                            if (fileHits.isNotEmpty()) {
                                item { SectionHeader("手机里的音频文件") }
                                items(fileHits, key = { it.file.absolutePath }) { hit ->
                                    LocalFileRow(
                                        hit = hit,
                                        mediaMeta = mediaIndex[hit.file.absolutePath],
                                    ) { onImportFile(it) }
                                }
                            }
                            if (scanFinished && fileHits.isEmpty() && hasAllFilesAccess) {
                                item {
                                    Text(
                                        text = "手机存储里没有找到匹配的音频文件（最多列出 200 条）",
                                        style = MaterialTheme.typography.labelSmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                        modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun CenterHint(text: String) {
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Text(
            text = text,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
            modifier = Modifier.padding(horizontal = 40.dp),
        )
    }
}

@Composable
private fun SectionHeader(title: String) {
    Text(
        text = title,
        style = MaterialTheme.typography.labelMedium,
        color = MaterialTheme.colorScheme.primary,
        modifier = Modifier
            .fillMaxWidth()
            .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.4f))
            .padding(horizontal = 16.dp, vertical = 6.dp),
    )
}

@Composable
private fun ImportedRow(song: Song, listName: String, onPlay: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onPlay)
            .padding(horizontal = 16.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(
                text = song.title,
                style = MaterialTheme.typography.bodyMedium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                text = buildString {
                    if (song.artist.isNotBlank()) append(song.artist).append("　")
                    append("在「").append(listName).append("」")
                },
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        Icon(
            Icons.Filled.PlayArrow,
            contentDescription = "播放",
            tint = MaterialTheme.colorScheme.primary,
            modifier = Modifier.size(22.dp),
        )
    }
}

@Composable
private fun LocalFileRow(
    hit: LocalFileHit,
    mediaMeta: DeviceAudio?,
    onImport: (DeviceAudio) -> Unit,
) {
    val file = hit.file
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable { onImport(FileBrowser.toDeviceAudio(file, mediaMeta)) }
            .padding(horizontal = 16.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(
                text = file.name.substringBeforeLast('.', file.name),
                style = MaterialTheme.typography.bodyMedium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                text = buildString {
                    val artist = mediaMeta?.artist.orEmpty()
                    if (artist.isNotBlank()) append(artist).append("　")
                    append(file.parentFile?.name.orEmpty())
                    val size = formatSize(file.length())
                    if (size.isNotBlank()) append("　").append(size)
                },
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        IconButton(onClick = { onImport(FileBrowser.toDeviceAudio(file, mediaMeta)) }) {
            Icon(
                Icons.Filled.Add,
                contentDescription = "导入到当前列表",
                tint = MaterialTheme.colorScheme.primary,
            )
        }
    }
}
