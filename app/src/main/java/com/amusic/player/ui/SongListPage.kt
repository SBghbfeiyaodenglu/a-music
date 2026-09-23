package com.amusic.player.ui

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.border
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.MusicNote
import androidx.compose.material3.Checkbox
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.material.icons.filled.ArrowUpward
import androidx.compose.material.icons.filled.MyLocation
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import android.os.SystemClock
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.util.concurrent.atomic.AtomicLong
import kotlin.math.abs
import com.amusic.player.data.AutoCenterTimer
import com.amusic.player.data.Song

/**
 * 歌曲列表页。
 *
 * 四种状态：一个列表都没有、当前列表没有歌曲（§8.2）、
 * 正常列表、以及**多选状态**。
 *
 * **长按任意一行进入多选**：点行 = 勾选/取消，顶部出现操作栏（已选几首 / 全选 / 移除所选 / 取消）。
 * 移除只删列表里的引用，绝不动手机上的音频文件和歌词文件。
 *
 * **自动居中**（行为学歌词页）：正在播放的那行会自动滚到屏幕中间，
 * 但只要用户在列表区域有触摸（滑动/点按/长按）就绝不打扰 —— 从最后一次触摸起连续安静
 * [autoCenterIdleMs] 才滚一次；中途再碰就重新计时。已经居中时不滚（省得每次安静期都抖一下）。
 *
 * 这个"安静时长"由用户在左上角菜单里设置（设置项「列表自动居中」，默认 5 秒）：
 * 列表页的主要用途是翻找歌曲，所以宽限时间要比歌词页回滚长得多，用户才能从容地翻。
 */
@Composable
fun SongListPage(
    songs: List<Song>,
    hasAnyList: Boolean,
    currentSongId: Long?,
    /** 当前列表 id。切列表时用它把多选状态清掉，免得把上一个列表的勾选带过来 */
    listKey: Long?,
    onSongClick: (Int) -> Unit,
    /** 批量移除：把选中的歌曲 id 交给上层去确认并执行 */
    onRemoveSelected: (Set<Long>) -> Unit,
    /** 点「定位」时上层算出来的目标歌曲；本页负责滚动过去并回报已到达 */
    scrollToSongId: Long?,
    onScrolledToSong: () -> Unit,
    /** 定位后的临时高亮歌曲（几秒后由上层清掉），和"正在播放"的常驻高亮是两回事 */
    locatedSongId: Long?,
    /** 点右下角「定位」：上层负责找"正在播放的歌在哪个列表"，必要时切列表 */
    onLocateCurrent: () -> Unit,
    /**
     * 本列表的滚动状态，由上层按列表 id 持有。
     * 必须放在上层：列表页在切到歌词页后可能被回收，状态放这里面会一起丢掉。
     */
    listState: LazyListState,
    /**
     * 自动居中的"安静时长"（毫秒）。用户在左上角菜单里设置（默认 5 秒，见
     * [com.amusic.player.data.SettingsRepository.listAutoCenterSeconds]）。
     * 传进来而不是写死：这个宽限时间可调，好按自己的翻找习惯来定。
     */
    autoCenterIdleMs: Long,
    onImport: () -> Unit,
    onNewList: () -> Unit,
    modifier: Modifier = Modifier,
) {
    // 多选状态。key 用 listKey：换列表时自动清零
    var selecting by remember(listKey) { mutableStateOf(false) }
    var selected by remember(listKey) { mutableStateOf(emptySet<Long>()) }

    // 滚动位置由调用方传进来（MainScreen 的 listScrollStates 按列表 id 缓存 LazyListState）：
    // 放在这里的话，切到歌词页时列表页被回收，位置就丢了。
    val scope = rememberCoroutineScope()
    val density = LocalDensity.current

    // 序号列的宽度：拿"和序号同字号的几位最宽数字"**实测**出来，再留 6dp 余量。
    // ⚠ 不能用固定 dp：系统字体放大（或换字体）后同样 28dp 里塞不下三位数，
    //   而 Text 默认允许换行 → 就会断成 "26 / 4"；同一列表里数字宽度略有差别，
    //   于是出现"263 一行、264 两行"这种忽上忽下的现象。
    //   按最长序号统一预留宽度，还能保证每行标题左边缘对齐。
    // 行高**从布局实测**，不要写死：字体大小一改，写死的值就不准，
    // 「定位」和自动居中的落点都会偏（写死 76dp 时实测只有 66.6dp）。
    // 列表还没量出来时退回 RowHeightDp 这个经验值。
    fun actualRowHeightPx(): Float =
        listState.layoutInfo.visibleItemsInfo.firstOrNull()?.size?.toFloat()
            ?: with(density) { RowHeightDp.dp.toPx() }

    val indexMeasurer = rememberTextMeasurer()
    val indexTextStyle = MaterialTheme.typography.labelMedium
    val fontScale = density.fontScale
    val indexColumnWidth = remember(songs.size, indexTextStyle, fontScale) {
        val digits = songs.size.toString().length.coerceAtLeast(2)
        with(density) {
            indexMeasurer.measure("8".repeat(digits), indexTextStyle).size.width.toDp() + 6.dp
        }
    }

    // 定位：滚动到目标歌曲再回报。
    // 注意"找到了才回报"：刚点定位时列表可能还没切过去（歌不在当前 songs 里），
    // 这时如果也回报，请求就被清掉了，切过去之后反而不会滚动。
    //
    // 停的位置是**屏幕中间**而不是顶部：定位是为了"找到这首歌"，
    // 停在中间能看到它前后的歌；停在顶部就只能看到它后面的了。
    LaunchedEffect(scrollToSongId, songs) {
        val id = scrollToSongId ?: return@LaunchedEffect
        val index = songs.indexOfFirst { it.id == id }
        if (index >= 0) {
            val viewport = listState.layoutInfo.viewportSize.height
            val rowPx = actualRowHeightPx()
            // 负的 scrollOffset 把这行往下推，推到视口中间
            val centering = if (viewport > 0) -(viewport / 2 - rowPx / 2).toInt() else 0
            listState.animateScrollToItem(index, scrollOffset = centering)
            onScrolledToSong()
        }
    }

    // ---------------- 自动居中 ----------------
    // 列表区域里任何触摸（滑动/点按/长按，包括多选和角落按钮）都会刷新这个时间戳。
    // 用 AtomicLong 而不是 state：它在指针协程里被高频写、在下面的循环里被读，
    // 不需要触发重组，只要线程安全就行。
    // 初值取"现在"而不是 0：否则进应用 0.5 秒后就会自己滚一次，
    // 把"每个列表各记各的滚动位置"直接冲掉。从页面出现那一刻开始倒数才合理。
    val lastListTouch = remember { AtomicLong(SystemClock.elapsedRealtime()) }

    LaunchedEffect(songs, currentSongId, autoCenterIdleMs) {
        val id = currentSongId ?: return@LaunchedEffect
        // 这一轮"安静期"里已经滚过一次了吗（用时间戳比大小判断）：
        // 判断本身在 AutoCenterTimer 里（纯函数，有单元测试兜着），这里只负责循环和滚动。
        var lastAttemptAt = 0L
        while (true) {
            delay(AUTO_CENTER_POLL_MS)
            val now = SystemClock.elapsedRealtime()
            val lastTouch = lastListTouch.get()
            // 还没安静够（用户正在操作）或这轮已经滚过 → 静静等下一次
            if (!AutoCenterTimer.shouldCenter(now, lastTouch, autoCenterIdleMs, lastAttemptAt)) continue

            val index = songs.indexOfFirst { it.id == id }
            if (index < 0) continue            // 正在播的不在这个列表里，不动
            val viewport = listState.layoutInfo.viewportSize.height
            if (viewport <= 0) continue
            val rowPx = actualRowHeightPx()
            // 和「定位」同一套算法：这一行停在屏幕中间
            val targetOffset = viewport / 2 - rowPx / 2
            val visible = listState.layoutInfo.visibleItemsInfo.firstOrNull { it.key == id }
            val alreadyCentered = visible != null && abs(visible.offset - targetOffset) <= rowPx / 2
            if (alreadyCentered) continue      // 已居中就别滚，免得每次安静期都自己抖一下

            lastAttemptAt = now
            listState.animateScrollToItem(index, scrollOffset = -targetOffset.toInt())
        }
    }

    fun toggle(id: Long) {
        selected = if (id in selected) selected - id else selected + id
    }

    // 移除成功后自动退出多选：勾中的歌已经不在列表里了，操作栏留着没意义。
    // 用户在确认框上点"取消"时歌曲还在，勾选会原样保留，不会白丢。
    LaunchedEffect(songs, selecting) {
        if (selecting && selected.isNotEmpty() && songs.none { it.id in selected }) {
            selecting = false
            selected = emptySet()
        }
    }

    when {
        !hasAnyList -> CenteredHint(
            title = "无任何列表，可点 + 号新建",
            action = {
                OutlinedButton(onClick = onNewList) {
                    Icon(Icons.Filled.Add, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(6.dp))
                    Text("新建列表")
                }
            },
            modifier = modifier,
        )

        songs.isEmpty() -> CenteredHint(
            title = "暂无歌曲",
            action = {
                OutlinedButton(onClick = onImport) {
                    Icon(Icons.Filled.Add, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(6.dp))
                    Text("导入歌曲")
                }
            },
            modifier = modifier,
        )

        else -> Box(
            modifier
                .fillMaxSize()
                // 只观察、不消费：在 Initial 阶段先看一眼所有指针事件，
                // 列表区域的任何按下/移动都算"用户在操作"，自动居中的计时从最后一次触摸重新起算。
                // 不消费事件，所以行点击/长按/滑动/角落按钮完全不受影响。
                .pointerInput(Unit) {
                    awaitPointerEventScope {
                        while (true) {
                            val event = awaitPointerEvent(PointerEventPass.Initial)
                            if (event.changes.any { it.pressed }) {
                                lastListTouch.set(SystemClock.elapsedRealtime())
                            }
                        }
                    }
                },
        ) {
            Column(Modifier.fillMaxSize()) {
                if (selecting) {
                    SelectionBar(
                        selectedCount = selected.size,
                        totalCount = songs.size,
                        allSelected = selected.size == songs.size && songs.isNotEmpty(),
                        onToggleAll = {
                            selected = if (selected.size == songs.size) emptySet() else songs.map { it.id }.toSet()
                        },
                        onRemove = { onRemoveSelected(selected) },
                        onCancel = {
                            // 取消多选：清空勾选，回到普通列表（不影响正在播放的歌）
                            selecting = false
                            selected = emptySet()
                        },
                    )
                }

                LazyColumn(state = listState, modifier = Modifier.fillMaxSize()) {
                    itemsIndexed(songs, key = { _, song -> song.id }) { index, song ->
                        SongRow(
                            index = index,
                            indexColumnWidth = indexColumnWidth,
                            song = song,
                            isCurrent = song.id == currentSongId,
                            selecting = selecting,
                            checked = song.id in selected,
                            located = song.id == locatedSongId,
                            onClick = {
                                if (selecting) toggle(song.id) else onSongClick(index)
                            },
                            onLongClick = {
                                if (selecting) {
                                    toggle(song.id)
                                } else {
                                    selecting = true
                                    selected = setOf(song.id)
                                }
                            },
                        )
                        HorizontalDivider(
                            modifier = Modifier.padding(start = 52.dp),
                            color = MaterialTheme.colorScheme.outlineVariant,
                        )
                    }
                }
            }

        // ---------------- 列表左下角：置顶 ----------------
        // 放在列表区域内部（不是底部播放栏），任何列表下点它都是"回到本列表第一名"
        if (!selecting) {
            CornerButton(
                icon = Icons.Filled.ArrowUpward,
                label = "置顶",
                onClick = { scope.launch { listState.animateScrollToItem(0) } },
                modifier = Modifier.align(Alignment.BottomStart).padding(start = 16.dp, bottom = 16.dp),
            )
        }

        // ---------------- 列表右下角：定位到正在播放 ----------------
        if (!selecting) {
            CornerButton(
                icon = Icons.Filled.MyLocation,
                label = "定位",
                onClick = onLocateCurrent,
                modifier = Modifier.align(Alignment.BottomEnd).padding(end = 16.dp, bottom = 16.dp),
            )
        }
        }
    }
}


/**
 * 多选状态下的操作栏。
 *
 * 压在列表上方，不做成弹窗：勾选时还要能滚动列表看，弹窗会挡住要勾的行。
 */
@Composable
private fun SelectionBar(
    selectedCount: Int,
    totalCount: Int,
    allSelected: Boolean,
    onToggleAll: () -> Unit,
    onRemove: () -> Unit,
    onCancel: () -> Unit,
) {
    val primary = MaterialTheme.colorScheme.primary
    val danger = Color(0xFFE5534B)
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 8.dp, vertical = 4.dp)
            .background(primary.copy(alpha = 0.14f), RoundedCornerShape(12.dp))
            .padding(horizontal = 8.dp, vertical = 2.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = "已选 $selectedCount / $totalCount 首",
            style = MaterialTheme.typography.labelLarge,
            color = MaterialTheme.colorScheme.onBackground,
            modifier = Modifier.weight(1f),
        )
        TextButton(onClick = onToggleAll) {
            Text(if (allSelected) "取消全选" else "全选", style = MaterialTheme.typography.labelMedium)
        }
        TextButton(onClick = onRemove, enabled = selectedCount > 0) {
            Text(
                text = "移除所选",
                style = MaterialTheme.typography.labelMedium,
                fontWeight = FontWeight.SemiBold,
                color = if (selectedCount > 0) danger else MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        TextButton(onClick = onCancel) {
            Text("取消", style = MaterialTheme.typography.labelMedium)
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun SongRow(
    index: Int,
    /** 序号列宽度，由上层按"最长序号"统一算好传进来（见 indexColumnWidth） */
    indexColumnWidth: Dp,
    song: Song,
    isCurrent: Boolean,
    selecting: Boolean,
    checked: Boolean,
    /** 定位后的临时高亮（几秒），比"正在播放"的底色更亮，好找 */
    located: Boolean,
    onClick: () -> Unit,
    onLongClick: () -> Unit,
) {
    val primary = MaterialTheme.colorScheme.primary
    // 只有"正在播放"和"定位高亮"这两种行做成**圆角卡片**（带描边）：
    // 列表是要快速扫的，每行都做成卡片会明显变花、每屏能看到的歌也变少，
    // 所以卡片只留给"需要一眼找到"的那一行：取舍是"不花"优先于"每行都标出来"。
    val cardShape = RoundedCornerShape(14.dp)
    val accent = when {
        located -> primary.copy(alpha = 0.26f)
        isCurrent -> primary.copy(alpha = 0.14f)
        else -> null
    }
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = if (accent != null) 8.dp else 0.dp, vertical = 2.dp)
            .let { base ->
                if (accent != null) {
                    base
                        .background(accent, cardShape)
                        .border(
                            1.dp,
                            // 边框用**明显的橙色**：30% 时在深背景上偏灰，会被当成"多余的白色线框"
                            // ——加重后就一眼看出是"正在播放"的高亮
                            primary.copy(alpha = if (located) 0.70f else 0.50f),
                            cardShape,
                        )
                } else {
                    base.background(
                        if (selecting && checked) primary.copy(alpha = 0.18f) else Color.Transparent,
                    )
                }
            }
            .combinedClickable(onClick = onClick, onLongClick = onLongClick)
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (selecting) {
            Checkbox(
                checked = checked,
                onCheckedChange = { onClick() },
                modifier = Modifier.size(28.dp),
            )
            Spacer(Modifier.width(12.dp))
        } else {
            Text(
                text = "${index + 1}",
                // 和上层算宽度时用的是同一个 style（labelMedium），别改成别的
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
                // 宽度已经按最长序号算足了；这里再把换行彻底关掉，
                // 免得任何字号/字体下又断成两行（这行数字永远只占一行）
                maxLines = 1,
                softWrap = false,
                modifier = Modifier.width(indexColumnWidth),
            )
        }
        Column(Modifier.weight(1f)) {
            // 歌名只占一行：放不下就滚动显示；**正在播放这一行不管长短都滚**
            // （光靠颜色高亮不够显眼，让歌名动起来一眼就能认出在放哪首）
            MarqueeTitle(
                text = song.title,
                style = MaterialTheme.typography.bodyLarge,
                color = if (isCurrent) primary else MaterialTheme.colorScheme.onBackground,
                fontWeight = if (isCurrent) FontWeight.SemiBold else FontWeight.Normal,
                alwaysScroll = isCurrent,
                initialDelayMillis = if (isCurrent) 600 else 900,
            )
        }
        if (isCurrent) {
            Icon(
                Icons.Filled.MusicNote,
                contentDescription = "正在播放",
                tint = primary,
                modifier = Modifier.size(18.dp),
            )
            Spacer(Modifier.width(8.dp))
        }
        Text(
            text = formatTime(song.durationMs),
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun CenteredHint(
    title: String,
    modifier: Modifier = Modifier,
    action: (@Composable () -> Unit)? = null,
) {
    Box(modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Text(
                text = title,
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            action?.invoke()
        }
    }
}

/** 列表行的估算高度，只用来把定位到的歌曲滚到屏幕中间（不需要精确） */
private const val RowHeightDp = 76

/**
 * 列表自动居中检查的节奏：半秒看一次足够了 —— 这里只是"到点了没"的轮询，
 * 真正的触发条件是用户停止操作满 autoCenterIdleMs（默认 5 秒），
 * 所以半秒的粒度对体感没有影响。
 */
private const val AUTO_CENTER_POLL_MS = 500L
