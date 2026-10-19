package com.amusic.player.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties

/**
 * 全应用统一的"悬浮卡片 + 胶囊"样式，所有弹窗都走这一套。
 *
 * 参数照抄自播放栏和播放模式上拉框：
 *
 * ```
 * 卡片   20dp 圆角 + 半透明黑底 + 1px 白 22% 描边，**绝不加投影**
 *        （Compose 的投影垫在半透明底下面，会从玻璃里透出来变成一团黑）
 * 胶囊   菜单项/选中项：圆角 = 高度一半，选中时主色底 16% + 主色描边 45%
 * 菜单   比卡片略重（黑 72%）：弹窗有 72% 的遮罩垫底，而菜单直接浮在页面上，
 *        底色轻了会和背后的歌曲列表糊在一起（等效算下来 72% 才对得上弹窗的观感）
 * ```
 */
internal val GlassShape = RoundedCornerShape(20.dp)

/** 胶囊形状（菜单项、按钮） */
internal val PillShape = RoundedCornerShape(percent = 50)

/**
 * 弹窗卡片底色：黑 42%。
 *
 * 比播放栏（20%）重、比上拉框那张卡（55%）轻 —— 弹窗的遮罩是 55%，卡片太实就会"看不出是玻璃"
 * （实测视觉检查直接判成"不透明的黑板"）。42% 下背后的列表还能隐约透出来，才有悬浮玻璃的感觉。
 */
internal val GlassFill = Color.Black.copy(alpha = 0.42f)

/**
 * 菜单/全屏弹窗的底色：黑 88%。
 *
 * ⚠ 这个值必须够重：轻了会"能看到后面的歌名，和菜单里的字叠在一起"。
 *
 * 算一下就明白为什么必须这么重：播放模式上拉框那张卡是 55%，看着也很实 ——
 * 因为它背后垫了 72% 的**遮罩**，页面透进来的只有 0.55×0.28 ≈ 13%，而且是被压暗过的。
 * 菜单没有遮罩（DropdownMenu 加不了），直接浮在亮色歌名上，想达到同样的观感就得让
 * 页面只透进来 ≈3%，也就是 alpha ≈ 0.95。
 * （反过来，同一个值在别的背景下观感会完全不同：背后是顶栏那片空白时，72% 看着就已经挺好。）
 */
internal val GlassMenuFill = Color.Black.copy(alpha = 0.95f)

/** 1px 白 22% 描边 —— 深色主题下卡片只能靠描边定形 */
internal val GlassBorder = BorderStroke(1.dp, Color.White.copy(alpha = 0.22f))

/**
 * 弹窗遮罩透明度。
 *
 * 上拉框要 72%（它正下方就是播放栏，淡了会和栏内文字重叠）；弹窗是居中的，55% 既能压暗背景、
 * 又留得住背景的明暗层次，好让玻璃卡片"透"得出来。
 */
internal const val GlassScrimAlpha = 0.55f

/**
 * 统一的弹窗外壳：自己画遮罩 + 居中的悬浮卡片，点遮罩关掉。
 *
 * 为什么自己画遮罩而不用系统默认的：默认遮罩偏淡，玻璃卡片浮不起来。
 * 透明度见 [GlassScrimAlpha]（0.55）：上拉框是 0.72（它正下方就是播放栏，淡了会和栏内
 * 文字重叠），弹窗居中、留点层次反而让玻璃更透。
 */
@Composable
internal fun GlassDialog(
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
    /**
     * 点遮罩（卡片外面）时：true = **只让输入框失焦并收起键盘**，不关弹窗。
     *
     * ⚠ 必须由 GlassDialog 自己在 `Dialog { }` **里面**读 `LocalFocusManager`：
     * `Dialog` 是新的窗口/composition root，会注入属于它自己的 FocusManager，
     * 在 Dialog 外面拿到的那个属于 Activity 窗口 —— 用它 clearFocus() 对弹窗里的
     * 输入框毫无作用（而且不报错，表现为"点了没反应"），必须遵守。
     */
    clearFocusOnScrimTap: Boolean = false,
    content: @Composable ColumnScope.() -> Unit,
) {
    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false),
    ) {
        // ★ 关键：这两个必须在 Dialog 内容**里面**取（见 clearFocusOnScrimTap 的说明）
        val focusManagerInDialog = LocalFocusManager.current
        val keyboardInDialog = LocalSoftwareKeyboardController.current
        val clearFocusAndIme = {
            focusManagerInDialog.clearFocus(force = true)
            keyboardInDialog?.hide()
        }
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(Color.Black.copy(alpha = GlassScrimAlpha))
                .clickable(
                    interactionSource = remember { MutableInteractionSource() },
                    indication = null,
                    onClick = { if (clearFocusOnScrimTap) clearFocusAndIme() else onDismiss() },
                ),
            contentAlignment = Alignment.Center,
        ) {
            Surface(
                shape = GlassShape,
                color = GlassFill,
                border = GlassBorder,
                // pointerInput 只用来"吃掉"落在卡片上的点击，免得点到卡片也会关掉弹窗
                modifier = modifier
                    .padding(horizontal = 22.dp)
                    .pointerInput(Unit) { detectTapGestures { } },
            ) {
                Column(content = content)
            }
        }
    }
}

/**
 * 玻璃卡片菜单（顶栏的☰菜单、列表选择、＋菜单都用它）。
 *
 * DropdownMenu 自带底色和投影，这里全部换成和弹窗一致的一套：圆角 20dp、黑 72%、
 * 白 22% 描边、**无投影**。
 */
@Composable
internal fun GlassMenu(
    expanded: Boolean,
    onDismissRequest: () -> Unit,
    modifier: Modifier = Modifier,
    content: @Composable ColumnScope.() -> Unit,
) {
    DropdownMenu(
        expanded = expanded,
        onDismissRequest = onDismissRequest,
        modifier = modifier,
        shape = GlassShape,
        containerColor = GlassMenuFill,
        tonalElevation = 0.dp,
        shadowElevation = 0.dp,
        border = GlassBorder,
        content = content,
    )
}

/**
 * 菜单项：胶囊样式。选中项用主色胶囊（和播放模式上拉框里选中的模式一个样子）。
 *
 * @param leading 左侧图标槽（可选）
 * @param selected 是否是"当前选中"的那一项
 * @param tint 文字颜色（不传就用 onSurface）
 */
@Composable
internal fun GlassMenuItem(
    text: String,
    leading: (@Composable () -> Unit)? = null,
    selected: Boolean = false,
    tint: Color? = null,
    enabled: Boolean = true,
    onClick: () -> Unit,
) {
    val primary = MaterialTheme.colorScheme.primary
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 6.dp, vertical = 1.dp)
            .clip(PillShape)
            .then(
                if (selected) {
                    Modifier
                        .background(primary.copy(alpha = 0.16f))
                        .border(1.dp, primary.copy(alpha = 0.45f), PillShape)
                } else {
                    Modifier
                },
            )
            .clickable(enabled = enabled, onClick = onClick)
            .padding(horizontal = 14.dp, vertical = 9.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (leading != null) {
            leading()
            Spacer(Modifier.width(10.dp))
        }
        Text(
            text = text,
            style = MaterialTheme.typography.bodyMedium,
            color = when {
                !enabled -> MaterialTheme.colorScheme.onSurface.copy(alpha = 0.38f)
                else -> tint ?: MaterialTheme.colorScheme.onSurface
            },
        )
    }
}

/** 弹窗底部的按钮：主要动作用实心胶囊，其余用文字胶囊 —— 和播放栏第四行同一套配色 */
@Composable
internal fun GlassPrimaryButton(
    text: String,
    onClick: () -> Unit,
    enabled: Boolean = true,
) {
    TextButton(
        onClick = onClick,
        enabled = enabled,
        shape = PillShape,
    ) {
        Text(
            text = text,
            color = MaterialTheme.colorScheme.primary,
            style = MaterialTheme.typography.labelLarge,
        )
    }
}

/** 弹窗底部的次要按钮（取消、关闭这类） */
@Composable
internal fun GlassSecondaryButton(
    text: String,
    onClick: () -> Unit,
    enabled: Boolean = true,
) {
    TextButton(
        onClick = onClick,
        enabled = enabled,
        shape = PillShape,
    ) {
        Text(
            text = text,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            style = MaterialTheme.typography.labelLarge,
        )
    }
}
