package com.amusic.player.ui

import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.wrapContentHeight
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.SkipNext
import androidx.compose.material.icons.filled.SkipPrevious
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.draw.clip
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.text.PlatformTextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.amusic.player.data.PlayMode
import com.amusic.player.data.Song
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.mutableFloatStateOf
import kotlin.math.roundToInt
import androidx.compose.runtime.setValue

/**
 * 底部共用播放栏，两个页面上都不动。
 *
 * 三行布局：
 *   1. 歌名（左） + 进度时间（**正中，正好在播放按钮正上方**） + 「在线搜索歌词」按钮（右）
 *   2. 上一首 / 音量- / 播放暂停 / 音量+ / 下一首
 *   3. 播放模式 / 进度条 / 歌词偏移
 * 第 2、3 行之间留了间隙，避免误触到相邻按钮。
 *
 * 第 1 行左边**只放歌名**（没歌时留空）：这一行要靠它认出当前在放什么歌，
 * 任何提示语都不许塞进那个位置。
 * 时间放正中是因为"播放按钮在正中"，这样一眼就能对上"放到哪儿了"。
 * 右上角那个「词」按钮是在线搜歌词的唯一入口，位置就是第 1 行右边原属于时间显示的那块；
 * 图标用「词」而不是放大镜，免得和顶栏的搜索图标重复。
 *
 * 关于进度：位置和时长用**函数**传进来，而不是直接传 Long 值。
 * 这样读取发生在下面两个小组件内部，播放时每 200ms 的刷新只会重绘它们，
 * 不会把整个界面（含主界面）一起重组重绘。
 * 若在 MainScreen 里直接读位置值，等于整屏 5Hz 刷新——白天看不出来，
 * 夜间低亮度下 OLED 上能看到界面有规律地一闪一闪。
 */
@Composable
fun PlayerBar(
    song: Song?,
    isPlaying: Boolean,
    /** 当前播放模式：第四个按钮直接显示它的名字，一眼看出当前是哪种模式 */
    playMode: PlayMode,
    /** 调音量时上方浮出的小提示（音量百分比，-1 = 不显示）；替代系统那根"点别处就关"的音量条 */
    volumeHintPercent: Int,
    positionProvider: () -> Long,
    durationProvider: () -> Long,
    onPrev: () -> Unit,
    onNext: () -> Unit,
    onTogglePlay: () -> Unit,
    onVolumeDown: () -> Unit,
    onVolumeUp: () -> Unit,
    onSeek: (Long) -> Unit,
    // 松手/点击结束时**必须带上目标位置**：不能只回调「结束了」——
    // 调用方那时读到的还是没更新的状态（回调里不带目标位置的话，点进度条中点只会跳到 5 秒）
    onSeekFinished: (Long) -> Unit,
    /** 拖动被系统打断（来电、手势被抢）时调用：调用方要借此清掉"正在拖动"状态 */
    onSeekCancelled: () -> Unit,
    onOpenPlayMode: () -> Unit,
    onOpenOffset: () -> Unit,
    /** 点右上角「词」按钮：打开在线搜歌词 */
    onOpenOnlineSearch: () -> Unit,
    /** 只有当前这首歌确实有歌词显示时才允许点「歌词偏移」 */
    offsetEnabled: Boolean,
    modifier: Modifier = Modifier,
) {
    // 播放栏做成**圆角悬浮卡片 + 毛玻璃**：
    // - 左右下留边、四角 20dp 圆角、1px 描边 —— 一眼看出它是浮在页面之上的一块面板
    // - 底色半透明，把后面那层已经模糊好的背景透出来（背景本来就是预模糊位图，
    //   所以这等于免费的 backdrop-filter，不需要每帧实时模糊）
    // - 顶部一条 1px 高光当玻璃的上边缘
    // 没有封面时背景是纯色，玻璃会自然退化成"比页面略亮的一块"，靠描边和高光区分。
    val shape = RoundedCornerShape(20.dp)
    Box(modifier = modifier.fillMaxWidth()) {
    Surface(
        // 玻璃底：**低透明度（20%）黑**，只把背景压暗一点点，色相和亮度都跟着背景走 ——
        // 这样才是"浑然一体"。用 50% 黑的话，虽然文字对比度很高，但栏内比页面暗 27 级、
        // 彩色背景下就是一块发黑的板子，和背景格格不入。
        // 代价是文字对比度会降，所以下面把栏内文字整体提亮（歌名/时间）来补回来。
        color = Color.Black.copy(alpha = 0.20f),
        shape = shape,
        // 主题的 surface 色和页面底几乎一样暗（实测只差 6 级），光靠底色看不出"这是一张卡片"，
        // 所以靠**描边 + 投影**把卡片托起来：描边 22% 白（约 1dp），投影 8dp。
        // 1px 白色描边：这块卡片的边界。注意"白色方框"那种观感**不是**这里造成的，
        // 它来自播放模式弹窗那边的描边（见 PlayModeSheet），这里的描边要保留。
        border = BorderStroke(1.dp, Color.White.copy(alpha = 0.22f)),
        // ⚠ 这里**刻意不加 shadowElevation**：Compose 的阴影会画一层半透明黑**垫在卡片下面**，
        // 而卡片底色本身是半透明的 —— 那层阴影会从玻璃里透出来，整块播放栏就变成"一团黑"
        // （实测：栏内亮度 7~12，而它背后的页面本该有 ~30）。玻璃靠描边定形就够了。
        modifier = Modifier
            .padding(start = 12.dp, end = 12.dp, bottom = 6.dp)   // 尽量靠底，给内容区腾地方
            .fillMaxWidth(),
    ) {
        Box {
        Column(Modifier.padding(horizontal = 12.dp, vertical = 6.dp)) {
            // ── 第一行：歌名占满整行 ──
            // 太长就**滚动显示**（走马灯），不再用省略号把歌名吃掉；
            // 放得下时它不会动（basicMarquee 只在内容超出容器时才滚）。
            // 歌名用黄色：在这层深色玻璃上比白色更醒目，
            // 也把"歌名"和第四行那几个蓝色入口按钮区分开。
            // 和列表里"正在播放"那一行一样：**不管名字长短都滚动**
            // （两处同时动起来，看着才有"在播"的感觉）
            MarqueeTitle(
                text = song?.title.orEmpty(),
                style = MaterialTheme.typography.bodyMedium,
                color = SongNameYellow,
                alwaysScroll = true,
                initialDelayMillis = 1200,
                modifier = Modifier.fillMaxWidth(),
            )

            // ── 第二行：上一首 / 下一首 / 播放（正中）/ 音量− / 音量+ ──
            // 顺序：左右各两个按钮，播放居中
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceEvenly,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                IconButton(onClick = onPrev) {
                    Icon(Icons.Filled.SkipPrevious, contentDescription = "上一首")
                }
                IconButton(onClick = onNext) {
                    Icon(Icons.Filled.SkipNext, contentDescription = "下一首")
                }
                // 两种状态用两种颜色，一眼就能分辨：
                //   显示"两条竖线"（正在播放，点了是暂停）→ 红色
                //   显示"三角形"（已暂停，点了是播放）  → 绿色
                FilledIconButton(
                    onClick = onTogglePlay,
                    // 没歌在播时置灰：一个"看得见、点了没反应"的按钮会被当成卡死
                    enabled = song != null,
                    modifier = Modifier.size(56.dp),
                    colors = IconButtonDefaults.filledIconButtonColors(
                        containerColor = if (isPlaying) PauseRed else PlayGreen,
                        contentColor = if (isPlaying) Color.White else Color(0xFF0E0E10),
                    ),
                ) {
                    Icon(
                        imageVector = if (isPlaying) Icons.Filled.Pause else Icons.Filled.PlayArrow,
                        contentDescription = if (isPlaying) "暂停" else "播放",
                        modifier = Modifier.size(28.dp),
                    )
                }
                VolumeButton(increase = false, onClick = onVolumeDown)
                VolumeButton(increase = true, onClick = onVolumeUp)
            }

            // ── 第三行：时间贴进度条两端 ──
            // 时间本来就属于进度条（业界都是这么排的），放在这里也顺便把第一行让给了歌名
            Row(verticalAlignment = Alignment.CenterVertically) {
                TimeLabel(provider = positionProvider)

                ProgressSlider(
                    positionProvider = positionProvider,
                    durationProvider = durationProvider,
                    onSeek = onSeek,
                    onSeekFinished = onSeekFinished,
                    onSeekCancelled = onSeekCancelled,
                    modifier = Modifier.weight(1f).padding(horizontal = 8.dp),
                )

                TimeLabel(provider = durationProvider)
            }

            // ── 第四行：播放模式（左）/ 歌词偏移（正中）/ 在线搜歌词·词（右） ──
            // 三个按钮**都用蓝色**，和黄色的歌名分工明确。
            // 三等分 + 各自对齐，中间的"歌词偏移"才会真的落在中线上（不是靠两个 Spacer 猜）。
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.weight(1f), contentAlignment = Alignment.CenterStart) {
                    TextButton(
                        onClick = onOpenPlayMode,
                        contentPadding = PaddingValues(horizontal = 8.dp),
                    ) {
                        // 直接显示当前模式名（"顺序循环"/"单曲循环"…），一眼看出现在是什么模式
                        Text(playMode.label, style = MaterialTheme.typography.labelMedium, color = ActionBlue)
                    }
                }

                Box(Modifier.weight(1f), contentAlignment = Alignment.Center) {
                    TextButton(
                        onClick = onOpenOffset,
                        enabled = offsetEnabled,
                        contentPadding = PaddingValues(horizontal = 8.dp),
                    ) {
                        Text(
                            "歌词偏移",
                            style = MaterialTheme.typography.labelMedium,
                            // 没歌词时禁用：用主题的禁用色，别用蓝色假装还能点
                            color = if (offsetEnabled) ActionBlue else MaterialTheme.colorScheme.onSurface.copy(alpha = 0.38f),
                        )
                    }
                }

                Box(Modifier.weight(1f), contentAlignment = Alignment.CenterEnd) {
                    // 用文字按钮而不是那个圆形「词」图标：和左边两个按钮风格统一
                    TextButton(
                        onClick = onOpenOnlineSearch,
                        contentPadding = PaddingValues(horizontal = 8.dp),
                    ) {
                        Text("在线搜词", style = MaterialTheme.typography.labelMedium, color = ActionBlue)
                    }
                }
            }
        }
    }
    }

    // 音量提示：浮在卡片**上方**（不影响布局，也不碰歌名那一行 —— 那行只显示歌名）。
    // ⚠ 必须放在 Surface **外面**：Surface 会按 20dp 圆角裁剪自己的内容，
    // 摆在里面的话这个往上偏移的小卡片会被整块裁掉（实测截图上完全找不到它）。
    if (volumeHintPercent >= 0) {
        VolumeHintPill(
            percent = volumeHintPercent,
            modifier = Modifier
                .align(Alignment.TopCenter)
                .offset(y = (-44).dp),
        )
    }
}
}

/**
 * 音量提示胶囊：**整块就是一条胶囊**，
 * 按当前音量从左往右填一段青绿色，百分比居中压在填充上 —— 所以文字和图形**两种**都能读数。
 *
 * ```
 * 尺寸   160×22dp，圆角 = 高度的一半（胶囊）。高度刻意压到 22dp —— 它浮在页面内容之上，
 *        做厚就会挡住更多歌词/列表；文字反而加大（15sp 加粗），矮 + 字大 = 一眼能读。
 * 半透明 底 = 黑 55%（和播放模式上拉框那张卡片同值）+ 1px 白 22% 描边 —— 看起来是块玻璃；
 *        填充 = 青绿 34%（不是实色，透出背后的内容）
 * 颜色   青绿 #4DB6AC：别处没用过（橙=主色、淡紫=进度条、黄=歌名、蓝=入口按钮）
 * ```
 * ⚠ 它浮在**页面内容**（歌词 / 列表文字）之上，所以黑底比播放栏的 20% 重得多 ——
 * 实测 55% 时白字对比度 11:1 左右，背后是纯白封面也还有 6:1 以上，够用。
 * 自绘而不用系统音量条：系统那根"点别处就关"，连续点会让它闪断（见 MainScreen.adjustVolume）。
 */
@Composable
private fun VolumeHintPill(percent: Int, modifier: Modifier = Modifier) {
    val p = percent.coerceIn(0, 100)
    Surface(
        shape = RoundedCornerShape(percent = 50),
        color = Color.Black.copy(alpha = 0.55f),
        border = BorderStroke(1.dp, Color.White.copy(alpha = 0.22f)),
        modifier = modifier
            .width(160.dp)
            .height(22.dp),
    ) {
        Box(contentAlignment = Alignment.Center) {
            // 已调到的部分：从左往右一段胶囊（右端也是圆的，和进度条的已播段同一套画法）
            Box(
                Modifier
                    .fillMaxWidth(p / 100f)
                    .fillMaxHeight()
                    .align(Alignment.CenterStart)
                    .clip(RoundedCornerShape(percent = 50))
                    .background(VolumeAccent.copy(alpha = 0.34f)),
            )
            Text(
                text = "音量 $p%",
                // ⚠ 这行是"文字挨着边框线"的真正修法：15sp 中文的 TextBox 自然高度比 22dp 的
                // 胶囊还高，不给 unbounded 的话它会被**压到 22dp**，字的下缘直接顶到（并被裁在）
                // 胶囊的下边框上 —— 实测上边留 20px、下边只剩 0px，看起来就是"挨着边框线"。
                // unbounded = true 让它按自然高度排版、再用 align 居中：多出来的空白上下平分，
                // 空白被 Surface 裁掉无所谓，墨迹就居中了（同一个 Text 放在 32dp 胶囊里是 28/25）。
                modifier = Modifier.wrapContentHeight(
                    align = Alignment.CenterVertically,
                    unbounded = true,
                ),
                style = MaterialTheme.typography.labelLarge.copy(
                    fontSize = 15.sp,
                    lineHeight = 24.sp,
                    // 默认的字体额外行距只在**上方**加空白，会把基线整体压低，一起关掉
                    platformStyle = PlatformTextStyle(includeFontPadding = false),
                ),
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.onSurface,
            )
        }
    }
}

/**
 * 进度条一端的单个时间（位置 / 总时长各一个）。
 *
 * 单独抽出来 + derivedStateOf：位置每 200ms 都在变，但显示的文字每秒才变一次，
 * 5 次刷新里有 4 次是不必要的重绘，能省就省。
 */
@Composable
private fun TimeLabel(provider: () -> Long) {
    // ⚠ remember 要带上 provider 当键：derivedStateOf 的 lambda 只在第一次组合时捕获 provider，
    //   传进来一个"捕获了普通值"的 lambda 的话会永远显示旧时间
    val text by remember(provider) { derivedStateOf { formatTime(provider()) } }
    Text(
        text = text,
        style = MaterialTheme.typography.labelSmall,
        color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.85f),
    )
}

/**
 * 进度条：**很细的轨道 + 稍大的圆点**。
 *
 * 为什么手写而不用 Material3 的 Slider：
 * 它把拖点固定成 20dp、轨道固定成 4dp，比例改不了 —— 而这里需要的是
 * "轨道细、圆点反而大一点"（播放器大部分时间在听，进度条不该占那么高，
 * 但想拖的时候得看得见、够得着）。所以这里自己画：
 *
 * ```
 * 轨道    3dp（圆角），已播＝淡紫、未播＝淡白
 * 圆点    18dp 白色圆（视觉主体，一眼知道播到哪）
 * 触摸区  48dp（关键：视觉可以很细，但手指按下去必须够得着）
 * ```
 *
 * 拖动时用本地状态显示"拖到哪儿"，不被 200ms 的位置轮询拽回去。
 */
@Composable
private fun ProgressSlider(
    positionProvider: () -> Long,
    durationProvider: () -> Long,
    onSeek: (Long) -> Unit,
    // 结束时要带上目标位置（见 PlayerBar 里的说明）
    onSeekFinished: (Long) -> Unit,
    /** 拖动被系统打断（来电、手势被抢）时调用：调用方要借此清掉"正在拖动"状态 */
    onSeekCancelled: () -> Unit,
    modifier: Modifier = Modifier,
) {
    var dragging by remember { mutableStateOf(false) }
    var dragX by remember { mutableFloatStateOf(0f) }
    // 轨道实际宽度：由 onSizeChanged 写入（**不能**在合成里赋值 —— 那是 Compose 的反模式）
    var trackWidth by remember { mutableFloatStateOf(1f) }

    // 同上：键要带上两个 provider，否则换歌后可能一直按旧的算
    val fraction by remember(durationProvider, positionProvider) {
        derivedStateOf {
            val duration = durationProvider()
            if (dragging) {
                (dragX / trackWidth).coerceIn(0f, 1f)
            } else if (duration > 0L) {
                (positionProvider().toFloat() / duration).coerceIn(0f, 1f)
            } else {
                0f
            }
        }
    }

    // 宽度由 onSizeChanged 记下来（不要用 BoxWithConstraints 在合成里写状态 —— 那是反模式）
    Box(
        modifier = modifier
            .height(TouchHeight)
            .onSizeChanged { trackWidth = it.width.toFloat().coerceAtLeast(1f) }
            // 点一下轨道：直接跳到那个位置
            .pointerInput(Unit) {
                detectTapGestures { offset ->
                    val duration = durationProvider()
                    if (duration > 0L) {
                        val target = (duration * (offset.x / size.width).coerceIn(0f, 1f)).toLong()
                        onSeek(target)
                        onSeekFinished(target)
                    }
                }
            }
            // 按住拖动
            .pointerInput(Unit) {
                detectHorizontalDragGestures(
                    onDragStart = { offset ->
                        dragging = true
                        dragX = offset.x
                    },
                    onDragEnd = {
                        val duration = durationProvider()
                        val target = if (duration > 0L) {
                            (duration * (dragX / trackWidth).coerceIn(0f, 1f)).toLong()
                        } else {
                            positionProvider()
                        }
                        if (duration > 0L) onSeek(target)
                        onSeekFinished(target)
                        dragging = false
                    },
                    onDragCancel = {
                        // ⚠ 必须告诉调用方：它那边 seeking=true 只能靠回调清掉，
                        // 否则进度条和时间会一直冻住
                        dragging = false
                        onSeekCancelled()
                    },
                ) { change, _ ->
                    dragX = change.position.x
                    change.consume()
                }
            },
    ) {
        val shape = RoundedCornerShape(percent = 50)
        // 轨道（未播部分）
        Box(
            Modifier
                .fillMaxWidth()
                .height(TrackHeight)
                .align(Alignment.Center)
                .clip(shape)
                .background(Color.White.copy(alpha = 0.16f)),
        )
        // 已播部分
        // ⚠ 必须 CenterStart：fillMaxWidth(比例) 在"居中"的容器里会变成左右各一半、
        // 看起来"从中间开始播"（实测紫色会出现在 25%~75% 区间）
        Box(
            Modifier
                .fillMaxWidth(fraction)
                .height(TrackHeight)
                .align(Alignment.CenterStart)
                .clip(shape)
                .background(ProgressPurple),
        )
        // 圆点（拖动时稍微放大一点，给个"抓住了"的反馈）
        val size = if (dragging) ThumbSize + 2.dp else ThumbSize
        Box(
            Modifier
                .align(Alignment.CenterStart)
                .offset { IntOffset((fraction * trackWidth).roundToInt(), 0) }
                .size(size)
                .offset(x = -size / 2)
                .clip(CircleShape)
                .background(Color.White),
        )
    }
}


/** 进度条：轨道 3dp、圆点 18dp、触摸区 48dp */
private val TrackHeight = 3.dp
private val ThumbSize = 18.dp
private val TouchHeight = 48.dp

/** 歌名：黄色。深色玻璃上比白色更醒目，也不跟第四行的蓝色按钮混 */
private val SongNameYellow = Color(0xFFFFD54F)

/** 第四行那三个入口按钮（播放模式 / 歌词偏移 / 词）：统一用蓝色 */
private val ActionBlue = Color(0xFF64B5F6)

/** 进度条：淡紫色，和播放控制按钮的橙色区分开 */
private val ProgressPurple = Color(0xFFB39DDB)

/**
 * 音量提示胶囊的强调色：**青绿** —— 不能再用已经在其它按钮上用过的颜色。
 * 别处已经用掉的色：橙 #E08030（歌词胶囊 / 正在播放行）、淡紫 #B39DDB（进度条）、
 * 黄 #FFD54F（歌名）、蓝 #64B5F6（第四行三个入口）—— 青绿跟这四个都不撞。
 */
private val VolumeAccent = Color(0xFF4DB6AC)

/** 已暂停：显示三角形，绿色；正在播放：显示两条竖线，红色 */
private val PlayGreen = Color(0xFF3FB950)
private val PauseRed = Color(0xFFE5534B)

internal fun formatTime(ms: Long): String {
    if (ms <= 0L) return "0:00"
    val totalSeconds = (ms / 1000L).toInt()
    return "%d:%02d".format(totalSeconds / 60, totalSeconds % 60)
}

internal fun formatOffset(ms: Long): String = "%+.1fs".format(ms / 1000.0)

