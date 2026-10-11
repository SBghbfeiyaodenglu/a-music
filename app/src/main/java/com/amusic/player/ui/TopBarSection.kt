package com.amusic.player.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.Menu
import androidx.compose.material.icons.filled.PriorityHigh
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.amusic.player.data.Playlist

/**
 * 顶部栏：菜单 / 列表下拉选择框 / 搜索 / ＋。
 *
 * ＋ 号下面的菜单同时提供"新建列表"和"为当前列表导入歌曲"——
 * ＋ 本身就很容被理解成"添加东西"，比先进菜单再点更直观。
 *
 * 注意：这里显式指定图标和文字的颜色，不依赖 LocalContentColor 继承。
 * Material3 里 LocalContentColor 的默认值是黑色，只有在 Surface 内部才会被改成
 * 跟随主题的前景色；这块布局不在 Surface 里，一旦漏掉颜色就会在深色背景上完全看不见。
 */
@Composable
fun TopBarSection(
    playlists: List<Playlist>,
    currentListId: Long?,
    currentListName: String?,
    currentListSongCount: Int,
    onSelectList: (Playlist) -> Unit,
    onNewList: () -> Unit,
    onRenameList: () -> Unit,
    onClearList: () -> Unit,
    onLyricsFont: () -> Unit,
    /** 列表自动居中的延迟秒数，显示在菜单项上让用户知道当前设的是多少 */
    listAutoCenterSeconds: Int,
    onListAutoCenter: () -> Unit,
    onDeleteList: () -> Unit,
    onDeleteAllLists: () -> Unit,
    onImport: () -> Unit,
    onSleepTimer: () -> Unit,
    /** 睡眠定时器剩余毫秒，<=0 表示没在跑。传函数而不是值，避免顶层每秒重组 */
    sleepRemainingProvider: () -> Long,
    hasCurrentSong: Boolean,
    onShowMetadata: () -> Unit,
    onSearch: () -> Unit,
    modifier: Modifier = Modifier,
) {
    var menuExpanded by remember { mutableStateOf(false) }
    var listExpanded by remember { mutableStateOf(false) }
    var plusExpanded by remember { mutableStateOf(false) }

    // 顶栏这五处用**别人没用过的**两个颜色：
    //   列表名 + 下拉箭头 → 樱粉；菜单/感叹号/搜索/＋ 四个图标 → 亮青。
    // 为什么单独分色：整排都用近白就和正文一个颜色，看不出"这几个是能点的"。
    // 选色时的约束：必须绕开已经在用的 11 个颜色（主题橙、歌名黄、入口蓝、进度条淡紫、
    // 音量青绿、播放绿、危险红、导入页的淡蓝/琥珀/灰、正文近白）。
    val listNameColor = TopBarListName
    val iconColor = TopBarIcon
    val danger = Color(0xFFE5534B)
    val hasCurrentList = currentListName != null

    Row(
        modifier = modifier.fillMaxWidth().padding(horizontal = 4.dp, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        // ---------------- 菜单 ----------------
        IconButton(onClick = { menuExpanded = true }) {
            Icon(Icons.Filled.Menu, contentDescription = "菜单设置", tint = iconColor)
        }
        // 菜单：玻璃卡片 + 胶囊菜单项（和播放模式上拉框、播放栏同一套）
        GlassMenu(expanded = menuExpanded, onDismissRequest = { menuExpanded = false }) {
            GlassMenuItem(
                text = "重命名当前列表",
                enabled = hasCurrentList,
                onClick = {
                    menuExpanded = false
                    onRenameList()
                },
            )
            GlassMenuItem(
                text = "清空当前列表的歌曲",
                enabled = hasCurrentList && currentListSongCount > 0,
                onClick = {
                    menuExpanded = false
                    onClearList()
                },
            )
            GlassMenuItem(
                text = run {
                    val left = sleepRemainingProvider()
                    if (left > 0L) "睡眠定时器 · ${formatRemaining(left)}" else "睡眠定时器"
                },
                tint = if (sleepRemainingProvider() > 0L) MaterialTheme.colorScheme.primary else null,
                onClick = {
                    menuExpanded = false
                    onSleepTimer()
                },
            )
            GlassMenuItem(
                text = "歌词文字大小",
                onClick = {
                    menuExpanded = false
                    onLyricsFont()
                },
            )
            // 和「睡眠定时器」一样把当前值显示在菜单项上：这种"延迟多少秒"的设置
            // 不显示当前值的话，用户想知道自己设过多少就得点进去看。
            GlassMenuItem(
                text = "列表自动居中 · ${listAutoCenterSeconds} 秒",
                onClick = {
                    menuExpanded = false
                    onListAutoCenter()
                },
            )
            HorizontalDivider(color = Color.White.copy(alpha = 0.12f), modifier = Modifier.padding(horizontal = 10.dp, vertical = 3.dp))
            GlassMenuItem(
                text = "删除当前列表",
                enabled = hasCurrentList,
                onClick = {
                    menuExpanded = false
                    onDeleteList()
                },
            )
            GlassMenuItem(
                text = "删除所有列表",
                tint = danger,
                enabled = playlists.isNotEmpty(),
                onClick = {
                    menuExpanded = false
                    onDeleteAllLists()
                },
            )
        }

        // ---------------- 列表下拉选择框 ----------------
        // 一个列表都没有时不可点开：里面只有列表项，没有新建入口（新建由 + 号负责）
        // ⚠ 和 + 号菜单同理，必须用 Box 把"列表名 + 菜单"绑在一起：
        //   直接把 DropdownMenu 放在 Row 里，它会锚到整行的左边缘（跑到汉堡按钮下面去）
        Box {
        Row(
            modifier = Modifier
                .clip(RoundedCornerShape(8.dp))
                .clickable(enabled = playlists.isNotEmpty()) { listExpanded = true }
                .padding(horizontal = 10.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = currentListName ?: "无列表",
                style = MaterialTheme.typography.titleMedium,
                color = listNameColor,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.widthIn(max = 180.dp),
            )
            Icon(
                Icons.Filled.KeyboardArrowDown,
                contentDescription = "展开列表",
                tint = listNameColor,
                modifier = Modifier.size(20.dp),
            )
        }
        GlassMenu(expanded = listExpanded, onDismissRequest = { listExpanded = false }) {
            playlists.forEach { playlist ->
                GlassMenuItem(
                    text = "${playlist.name}（${playlist.songs.size}）",
                    // 当前列表用主色胶囊标出来（和播放模式里选中的模式一个样子）
                    // ⚠ 按 id 判选中，不能按名字：列表允许重名，
                    //   重名时两个都会显示"选中胶囊 + 对勾"，也分不出点的是哪一个。
                    selected = playlist.id == currentListId,
                    leading = {
                        if (playlist.id == currentListId) {
                            Icon(
                                Icons.Filled.Check,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.primary,
                                modifier = Modifier.size(18.dp),
                            )
                        } else {
                            Spacer(Modifier.size(18.dp))
                        }
                    },
                    onClick = {
                        listExpanded = false
                        onSelectList(playlist)
                    },
                )
            }
        }
        }   // 结束"列表名 + 菜单"那个 Box

        // 睡眠定时器跑着的时候，顶栏常驻一个倒计时，免得忘了它还开着
        // （读的是 provider，所以只有这一小块每秒重组，顶栏其它部分不动）
        val sleepLeft = sleepRemainingProvider()
        if (sleepLeft > 0L) {
            Row(
                modifier = Modifier
                    .clip(RoundedCornerShape(8.dp))
                    .background(MaterialTheme.colorScheme.primary.copy(alpha = 0.16f))
                    .padding(horizontal = 8.dp, vertical = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = "睡眠 ${formatRemaining(sleepLeft)}",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.primary,
                )
            }
        }

        // 撑开左边空白，把右侧这组按钮顶到右边。
        // 感叹号不能单独居中：列表名一长（比如"伴奏-周杰伦"这种）下拉框会把它盖住，
        // 所以和搜索/＋ 挨在一起、整体靠右。
        Spacer(Modifier.weight(1f))

        // 感叹号：弹窗看当前播放歌曲的元数据
        IconButton(onClick = onShowMetadata, enabled = hasCurrentSong) {
            Icon(
                Icons.Filled.PriorityHigh,
                contentDescription = "歌曲元数据",
                tint = if (hasCurrentSong) iconColor else iconColor.copy(alpha = 0.35f),
            )
        }

        IconButton(onClick = onSearch) {
            Icon(Icons.Filled.Search, contentDescription = "搜索", tint = iconColor)
        }

        // ---------------- ＋：新建列表 / 导入歌曲 ----------------
        // 用 Box 把按钮和它的菜单绑在一起，菜单才会落在 + 的正下方；
        // 直接放在 Row 里的话菜单会锚到整行的左边缘，跑到左边去。
        Box {
            IconButton(onClick = { plusExpanded = true }) {
                Icon(Icons.Filled.Add, contentDescription = "添加", tint = iconColor)
            }
            GlassMenu(expanded = plusExpanded, onDismissRequest = { plusExpanded = false }) {
                GlassMenuItem(
                    text = "新建列表",
                    onClick = {
                        plusExpanded = false
                        onNewList()
                    },
                )
                GlassMenuItem(
                    text = "为当前列表导入歌曲",
                    enabled = hasCurrentList,
                    onClick = {
                        plusExpanded = false
                        onImport()
                    },
                )
            }
        }
    }
}

/**
 * 顶栏专属配色。
 *
 * ```
 * 列表名 + 下拉箭头  #F8BBD0 樱粉   —— 当前列表是顶栏上最该被看到的信息
 * 四个图标          #4DD0E1 亮青   —— 菜单 / 感叹号 / 搜索 / ＋
 * ```
 *
 * 这两个色**全局没在别处用过**，
 * 所以顶栏一眼就能和播放栏（黄/蓝/淡紫/青绿）、导入页（淡蓝/琥珀/绿/灰）区分开。
 */
private val TopBarListName = Color(0xFFF8BBD0)
private val TopBarIcon = Color(0xFF4DD0E1)
