package com.amusic.player.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.ArrowUpward
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.runtime.rememberCoroutineScope
import kotlinx.coroutines.launch
import androidx.compose.runtime.LaunchedEffect
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import com.amusic.player.data.media.BrowserEntry
import com.amusic.player.data.media.DeviceAudio
import com.amusic.player.data.media.FileBrowser
import java.io.File

/**
 * 按目录浏览并导入歌曲。
 *
 * 浏览时**只列当前目录、不递归**：用户先看清这个文件夹里有什么（包括哪些歌有配套歌词），
 * 再决定是整目录导入还是挑几首导入。这样才不会把系统铃声、没歌词的歌一股脑混进来。
 *
 * 另外支持**按文件夹批量导入**：歌按分类装在多个文件夹里时（/MP3/周杰伦…、/MP3/林俊杰…），
 * 勾上文件夹的复选框即可 —— 勾一个就把它**递归**（含专辑子目录）里所有音频一起算进待导入列表，
 * 多个文件夹一起勾就一次性全导。勾文件夹和勾单曲可以混着来，重复的按路径自动去重。
 */
/**
 * 导入界面的一组配色。
 *
 * 路径、选择按钮、导入按钮不能全用主题主色（橙色），
 * 一整片同色看不出哪是哪。按用途分色：
 *   路径      淡蓝
 *   选择操作  琥珀黄
 *   导入      亮绿 + 加粗 + 实心按钮，全界面最显眼的一个
 */
private val PathColor = Color(0xFF6FB3E0)
private val SelectActionColor = Color(0xFFF2C14E)
private val ImportActionColor = Color(0xFF3FB950)
private val NavActionColor = Color(0xFFBFBFC6)


@Composable
fun ImportScreen(
    listName: String,
    mediaIndex: Map<String, DeviceAudio>,
    loadingIndex: Boolean,
    hasAllFilesAccess: Boolean,
    onRequestAllFilesAccess: () -> Unit,
    onCancel: () -> Unit,
    onConfirm: (List<DeviceAudio>) -> Unit,
) {
    val root = remember { FileBrowser.storageRoot() }
    var currentDir by remember { mutableStateOf(root) }

    // 选择跨目录保留，方便从多个文件夹里各挑几首一起导入
    var selected by remember { mutableStateOf<Map<String, DeviceAudio>>(emptyMap()) }
    // 勾选的文件夹 → 里面递归找到的全部音频（扫完才有值）；还在统计中的单独放一份，
    // 这样行上能显示"统计中…"，用户也知道为啥导入按钮还点不动
    var pickedDirs by remember { mutableStateOf<Map<String, List<DeviceAudio>>>(emptyMap()) }
    var scanningDirs by remember { mutableStateOf<Set<String>>(emptySet()) }

    val canGoUp = currentDir.absolutePath != root.absolutePath
    // "全选当前目录"要列目录 + 读元数据（磁盘活），放到 IO 线程去跑
    val scope = rememberCoroutineScope()

    var entries by remember { mutableStateOf<List<BrowserEntry>>(emptyList()) }
    var listingFailed by remember { mutableStateOf(false) }
    var listing by remember { mutableStateOf(true) }
    // path → 有没有内嵌歌词。没有同名 .lrc 的文件才需要去看标签，结果在后台批量嗅探
    var embeddedFlags by remember { mutableStateOf<Map<String, Boolean>>(emptyMap()) }

    LaunchedEffect(currentDir, mediaIndex) {
        listing = true
        val listed = withContext(Dispatchers.IO) { FileBrowser.list(currentDir, mediaIndex) }
        listingFailed = withContext(Dispatchers.IO) {
            runCatching { currentDir.listFiles() }.getOrNull() == null
        }
        entries = listed
        listing = false
    }

    LaunchedEffect(entries) {
        val needCheck = entries.filter { !it.isDirectory && !it.hasLrc }
        if (needCheck.isEmpty()) {
            embeddedFlags = emptyMap()
            return@LaunchedEffect
        }
        embeddedFlags = emptyMap()
        val sniffed = withContext(Dispatchers.IO) {
            needCheck.associate { it.path to FileBrowser.hasEmbeddedLyrics(it.file) }
        }
        embeddedFlags = sniffed
    }

    val audioCount = remember(entries) { entries.count { !it.isDirectory } }
    val dirEntries = remember(entries) { entries.filter { it.isDirectory } }

    /** 手动勾的单曲 + 勾中文件夹里的全部歌，按路径去重（同一首可能两边都勾到） */
    val batch: List<DeviceAudio> = remember(selected, pickedDirs) {
        val byPath = LinkedHashMap<String, DeviceAudio>()
        selected.values.forEach { byPath[it.path] = it }
        pickedDirs.values.forEach { files -> files.forEach { byPath.putIfAbsent(it.path, it) } }
        byPath.values.toList()
    }
    val scanning = scanningDirs.isNotEmpty()

    fun enter(dir: File) {
        currentDir = dir
    }

    fun toggle(entry: BrowserEntry) {
        selected = if (entry.path in selected) {
            selected - entry.path
        } else {
            selected + (entry.path to FileBrowser.toDeviceAudio(entry.file, entry.mediaMeta))
        }
    }

    /** 勾/取消一个文件夹：勾上就递归扫一遍里面的音频（磁盘活，放 IO 线程） */
    fun toggleDir(dir: File) {
        val key = dir.absolutePath
        if (key in pickedDirs || key in scanningDirs) {
            pickedDirs = pickedDirs - key
            scanningDirs = scanningDirs - key
            return
        }
        scanningDirs = scanningDirs + key
        scope.launch {
            val files = withContext(Dispatchers.IO) {
                FileBrowser.audioFilesRecursive(dir).map { file ->
                    FileBrowser.toDeviceAudio(file, mediaIndex[file.absolutePath])
                }
            }
            // 扫描期间用户可能又点了一下取消 —— 那就别再加回来了
            if (key in scanningDirs) {
                scanningDirs = scanningDirs - key
                pickedDirs = pickedDirs + (key to files)
            }
        }
    }

    /** 本目录下的文件夹全勾上/全取消 */
    fun toggleAllDirsInCurrentDir(allPicked: Boolean) {
        val keys = dirEntries.map { it.path }.toSet()
        if (allPicked) {
            pickedDirs = pickedDirs - keys
            scanningDirs = scanningDirs - keys
        } else {
            dirEntries.forEach { if (it.path !in pickedDirs) toggleDir(it.file) }
        }
    }

    fun selectAllInCurrentDir() {
        // 列目录 + 逐个读元数据都是磁盘活：放到 IO 线程，否则大目录会把界面卡住
        scope.launch {
            val add = withContext(Dispatchers.IO) {
                FileBrowser.audioFilesIn(currentDir).associate { file ->
                    file.absolutePath to FileBrowser.toDeviceAudio(file, mediaIndex[file.absolutePath])
                }
            }
            selected = selected + add
        }
    }

    Column(
        Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
            .statusBarsPadding(),
    ) {
        // 顶栏
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 4.dp, vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            IconButton(onClick = onCancel) {
                Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "返回")
            }
            Text(
                text = "导入到「$listName」",
                style = MaterialTheme.typography.titleMedium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f),
            )
            // 导入是本界面的主操作，用实心绿色按钮 + 加粗，和其它文字明显区分开
            Button(
                onClick = { onConfirm(batch) },
                // 统计文件夹期间先别让点：这时候的"几首"还没数完，点了会漏歌
                enabled = batch.isNotEmpty() && !scanning,
                colors = ButtonDefaults.buttonColors(
                    containerColor = ImportActionColor,
                    contentColor = Color(0xFF0E0E10),
                ),
                contentPadding = PaddingValues(horizontal = 20.dp, vertical = 8.dp),
            ) {
                Text(
                    text = if (batch.isEmpty()) "导入" else "导入 ${batch.size} 首",
                    fontWeight = FontWeight.Bold,
                )
            }
        }

        // 浏览目录本身就需要"所有文件访问"，没授权时给出提示（不弹窗打断）
        if (!hasAllFilesAccess) {
            Surface(
                color = MaterialTheme.colorScheme.surfaceVariant,
                modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 4.dp),
            ) {
                Row(
                    Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        text = "需要\"所有文件访问\"权限才能在目录里看到歌曲和同名 .lrc 歌词",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.weight(1f),
                    )
                    TextButton(onClick = onRequestAllFilesAccess) { Text("去授权") }
                }
            }
        }

        // 当前路径 + 上一级
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 2.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = currentDir.absolutePath.removePrefix(root.absolutePath).ifBlank { "/内部存储" },
                style = MaterialTheme.typography.labelMedium,
                color = PathColor,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f),
            )
            TextButton(onClick = { currentDir.parentFile?.let { enter(it) } }, enabled = canGoUp) {
                Icon(
                    Icons.Filled.ArrowUpward,
                    contentDescription = null,
                    tint = NavActionColor,
                    modifier = Modifier.size(16.dp),
                )
                Spacer(Modifier.width(4.dp))
                Text("上一级", color = NavActionColor)
            }
        }


        // 选择操作
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 2.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = when {
                    batch.isEmpty() -> "本目录 $audioCount 首音频"
                    scanning -> "已选 ${batch.size} 首（统计文件夹中…）"
                    pickedDirs.isNotEmpty() -> "已选 ${batch.size} 首 · ${pickedDirs.size} 个文件夹"
                    else -> "已选 ${batch.size} 首"
                },
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.weight(1f),
            )
            // 本层有子文件夹时给一个"一键勾上全部文件夹"（按分类分文件夹的用户一次就能全选）
            if (dirEntries.isNotEmpty()) {
                val allDirsPicked = dirEntries.all { it.path in pickedDirs }
                TextButton(onClick = { toggleAllDirsInCurrentDir(allDirsPicked) }) {
                    Text(
                        text = if (allDirsPicked) "取消文件夹" else "全选文件夹",
                        color = SelectActionColor,
                    )
                }
            }
            TextButton(onClick = { selectAllInCurrentDir() }, enabled = audioCount > 0) {
                Text(
                    text = "全选本层",
                    color = if (audioCount > 0) SelectActionColor else SelectActionColor.copy(alpha = 0.35f),
                )
            }
            TextButton(
                onClick = {
                    selected = emptyMap()
                    pickedDirs = emptyMap()
                    scanningDirs = emptySet()
                },
                enabled = selected.isNotEmpty() || pickedDirs.isNotEmpty() || scanningDirs.isNotEmpty(),
            ) {
                val any = selected.isNotEmpty() || pickedDirs.isNotEmpty() || scanningDirs.isNotEmpty()
                Text(
                    text = "清空",
                    color = if (any) SelectActionColor else SelectActionColor.copy(alpha = 0.35f),
                )
            }
        }

        Box(Modifier.fillMaxSize()) {
            when {
                loadingIndex && mediaIndex.isEmpty() -> CenterBox {
                    CircularProgressIndicator()
                    Spacer(Modifier.height(12.dp))
                    Text(
                        text = "正在读取媒体库元数据 ...",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }

                listingFailed -> CenterBox {
                    Text("无法访问此目录", style = MaterialTheme.typography.titleSmall)
                    Spacer(Modifier.height(8.dp))
                    Text(
                        text = "系统限制了对该目录的访问（例如 Android/data）。" +
                            "请返回上一级，或确认已授予\"所有文件访问\"权限。",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        textAlign = TextAlign.Center,
                        modifier = Modifier.padding(horizontal = 32.dp),
                    )
                }

                listing -> CenterBox { CircularProgressIndicator() }

                entries.isEmpty() -> CenterBox {
                    Text(
                        text = "此目录下没有子目录或音频文件",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }

                else -> LazyColumn(contentPadding = PaddingValues(bottom = 32.dp)) {
                    items(entries, key = { it.path }) { entry ->
                        if (entry.isDirectory) {
                            DirectoryRow(
                                entry = entry,
                                checked = entry.path in pickedDirs,
                                scanning = entry.path in scanningDirs,
                                pickedCount = pickedDirs[entry.path]?.size,
                                onEnter = { enter(entry.file) },
                                onToggle = { toggleDir(entry.file) },
                            )
                        } else {
                            AudioRow(
                                entry = entry,
                                checked = entry.path in selected,
                                embeddedLyrics = embeddedFlags[entry.path],
                                onToggle = { toggle(entry) },
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun CenterBox(content: @Composable () -> Unit) {
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) { content() }
    }
}

/**
 * 目录行：左边复选框勾选（把整个文件夹批量算进待导入），点行本身进文件夹，右边箭头也是"进去"。
 *
 * 勾选和"进入文件夹"必须分开 —— 不然想勾一个文件夹就变成进去了、想进去又变成勾上了。
 */
@Composable
private fun DirectoryRow(
    entry: BrowserEntry,
    checked: Boolean,
    scanning: Boolean,
    /** 勾中后扫描出来的音频数；还没扫完是 null */
    pickedCount: Int?,
    onEnter: () -> Unit,
    onToggle: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onEnter)
            .padding(start = 4.dp, end = 16.dp, top = 4.dp, bottom = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Checkbox(checked = checked, onCheckedChange = { onToggle() })
        Icon(
            Icons.Filled.Folder,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.primary,
            modifier = Modifier.size(22.dp),
        )
        Spacer(Modifier.width(12.dp))
        Text(
            text = entry.name,
            style = MaterialTheme.typography.bodyLarge,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            color = if (checked) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onBackground,
            modifier = Modifier.weight(1f),
        )
        if (scanning) {
            Text(
                text = "统计中…",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.width(8.dp))
        } else if (pickedCount != null) {
            Text(
                text = if (pickedCount == 0) "没有音频" else "$pickedCount 首",
                style = MaterialTheme.typography.labelSmall,
                color = if (pickedCount == 0) {
                    MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f)
                } else {
                    MaterialTheme.colorScheme.primary
                },
            )
            Spacer(Modifier.width(8.dp))
        }
        Icon(
            Icons.AutoMirrored.Filled.KeyboardArrowRight,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.size(20.dp),
        )
    }
}

@Composable
private fun AudioRow(
    entry: BrowserEntry,
    checked: Boolean,
    /** null = 还没检测完；true/false = 有没有内嵌歌词 */
    embeddedLyrics: Boolean?,
    onToggle: () -> Unit,
) {
    // 歌词来源分三种情况标出来，避免把"有内嵌歌词但没 .lrc"的歌误当成无歌词跳过
    val lyricsLabel: String
    val hasLyrics: Boolean
    when {
        entry.hasLrc -> {
            lyricsLabel = "有 LRC"
            hasLyrics = true
        }
        embeddedLyrics == true -> {
            lyricsLabel = "有内嵌"
            hasLyrics = true
        }
        embeddedLyrics == false -> {
            lyricsLabel = "无歌词"
            hasLyrics = false
        }
        else -> {
            lyricsLabel = "检测中"
            hasLyrics = false
        }
    }
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onToggle)
            .padding(horizontal = 8.dp, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Checkbox(checked = checked, onCheckedChange = { onToggle() })
        Column(Modifier.weight(1f)) {
            Text(
                text = entry.displayName,
                style = MaterialTheme.typography.bodyMedium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                color = if (checked) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onBackground,
            )
            Row(verticalAlignment = Alignment.CenterVertically) {
                if (entry.artist.isNotBlank()) {
                    Text(
                        text = entry.artist,
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Spacer(Modifier.width(8.dp))
                }
                Text(
                    text = lyricsLabel,
                    style = MaterialTheme.typography.labelSmall,
                    color = if (hasLyrics) {
                        MaterialTheme.colorScheme.primary
                    } else {
                        MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f)
                    },
                )
            }
        }
        Spacer(Modifier.width(8.dp))
        Text(
            text = if (entry.durationMs > 0L) formatTime(entry.durationMs) else formatSize(entry.sizeBytes),
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

internal fun formatSize(bytes: Long): String = when {
    bytes <= 0L -> ""
    bytes < 1024L -> "${bytes}B"
    bytes < 1024L * 1024L -> "${bytes / 1024}KB"
    else -> "%.1fMB".format(bytes / 1024.0 / 1024.0)
}
