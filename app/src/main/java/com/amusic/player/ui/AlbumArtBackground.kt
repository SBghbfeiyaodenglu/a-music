package com.amusic.player.ui

import androidx.compose.animation.Crossfade
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import com.amusic.player.AppGraph

/**
 * 当前歌曲的磨砂背景图，用作整个界面的底色。
 *
 * **全自动，没有开关**：当前歌曲有内嵌封面就模糊后铺上；
 * 没有封面时用轮换色调合成一张（风格和真封面一致，见 `PaletteTable`）；
 * 连索引都没有才返回 null，界面保持主题纯色背景。
 */
@Composable
fun rememberAlbumArtBackground(songPath: String?, paletteIndex: Int?): ImageBitmap? {
    val source = remember { AppGraph.get().albumArt }
    var art by remember { mutableStateOf<ImageBitmap?>(null) }

    LaunchedEffect(songPath, paletteIndex) {
        art = source.blurredBackground(songPath, paletteIndex)?.asImageBitmap()
    }
    return art
}

/**
 * 背景层：全屏模糊封面 + **按页面不同的深色蒙版**。
 *
 * 做法是行业最主流那一套（全屏高斯模糊 + 深色蒙版），三个界面靠蒙版结构区分开：
 *
 * ```
 * 列表页   均匀压 0.46，再往下渐暗到 0.62 —— 信息最多，尾巴要压住
 * 歌词页   均匀压 0.55，上下各再压一点、中间留亮 —— 高亮那句像被一束光照着
 * 播放栏   不在这里画：它是半透明的"毛玻璃"层（见 PlayerBar），
 *          把这一层已经模糊好的背景透出来，天然就是 backdrop-filter 效果，零额外开销
 * ```
 *
 * 峰值由两道保证：`AlbumArtSource` 里把封面峰值钉在 CAP，这里再乘一次蒙版。
 * 真机实测（v23，歌曲《花田错》）：列表页 上31/中37/下30，歌词页 上28/中45/下40，
 * 播放栏 42 —— 两个页面明暗结构明显不同、播放栏比页面亮一档并带高光线；
 * 按背景最亮 45 计算，白字对比度约 14:1、橙色高亮行约 6.5:1。
 */
@Composable
fun AlbumArtBackground(
    art: ImageBitmap?,
    /** 当前在歌词页：蒙版换成"中间留亮"的版本，两个页面一眼能看出区别 */
    onLyricsPage: Boolean,
    modifier: Modifier = Modifier,
) {
    Crossfade(
        targetState = art,
        animationSpec = tween(durationMillis = 600),
        label = "album-art-background",
    ) { image ->
        if (image == null) return@Crossfade
        Box(modifier.fillMaxSize()) {
            Image(
                bitmap = image,
                contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize(),
            )
            // 均匀的那层：页面上所有文字都压在这层之上
            Box(
                Modifier
                    .fillMaxSize()
                    .background(
                        Color.Black.copy(alpha = if (onLyricsPage) 0.55f else 0.46f),
                    ),
            )
            // 方向性的那层：决定"光从哪来"，也就是页面之间的区别
            Box(
                Modifier
                    .fillMaxSize()
                    .background(
                        if (onLyricsPage) {
                            // 上下压、中间留亮 → 一束光落在歌词中部
                            Brush.verticalGradient(
                                0.00f to Color.Black.copy(alpha = 0.30f),
                                0.28f to Color.Black.copy(alpha = 0.05f),
                                0.72f to Color.Black.copy(alpha = 0.05f),
                                1.00f to Color.Black.copy(alpha = 0.45f),
                            )
                        } else {
                            // 从上到下渐暗 → 列表越长，越往下的行越沉
                            Brush.verticalGradient(
                                0.00f to Color.Black.copy(alpha = 0.06f),
                                0.45f to Color.Black.copy(alpha = 0.16f),
                                1.00f to Color.Black.copy(alpha = 0.55f),
                            )
                        },
                    ),
            )
        }
    }
}
