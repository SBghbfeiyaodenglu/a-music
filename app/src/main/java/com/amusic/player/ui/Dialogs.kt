package com.amusic.player.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.Icon
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.material3.Slider
import androidx.compose.ui.unit.sp
import kotlin.math.roundToInt
import com.amusic.player.data.LyricsFontScale
import com.amusic.player.data.LyricsOffsetStepMs
import com.amusic.player.data.SettingsRepository
import kotlinx.coroutines.delay

/**
 * 新建 / 重命名列表的输入框。
 *
 * 弹出后自动把焦点放进输入框，输入法直接弹出来，用户只需要输入名称再确认。
 */
@Composable
fun ListNameDialog(
    title: String,
    initialName: String,
    confirmLabel: String,
    onDismiss: () -> Unit,
    onConfirm: (String) -> Unit,
) {
    var name by remember { mutableStateOf(initialName) }
    val focusRequester = remember { FocusRequester() }

    LaunchedEffect(Unit) {
        // 等一帧让输入框挂载完成，否则 requestFocus 会落空
        delay(120L)
        focusRequester.requestFocus()
    }

    // 悬浮卡片样式（和播放栏、播放模式上拉框同一套）
    GlassDialog(onDismiss = onDismiss) {
        Text(
            text = title,
            style = MaterialTheme.typography.titleMedium,
            modifier = Modifier.padding(start = 20.dp, end = 20.dp, top = 18.dp),
        )
        OutlinedTextField(
            value = name,
            onValueChange = { name = it },
            singleLine = true,
            shape = GlassShape,
            placeholder = { Text("请输入列表名称") },
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 20.dp, vertical = 14.dp)
                .focusRequester(focusRequester),
        )
        Row(
            modifier = Modifier.fillMaxWidth().padding(end = 10.dp, bottom = 8.dp),
            horizontalArrangement = Arrangement.End,
        ) {
            GlassSecondaryButton(text = "取消", onClick = onDismiss)
            GlassPrimaryButton(
                text = confirmLabel,
                enabled = name.isNotBlank(),
                onClick = { onConfirm(name.trim()) },
            )
        }
    }
}

/**
 * 列表自动居中的延迟设置（左上角菜单 →「列表自动居中」）。
 *
 * 用户在这里填"停止操作多少秒之后，自动把正在播放的歌曲滚到列表中间"。
 * 只接受 >= 1 的正整数：0 或负数等于每时每刻都在滚，会一直跟用户抢列表，
 * 所以输入框只放行数字，且解析出来必须够大才让确定。
 *
 * 用文本输入而不是滑杆/固定档位：这里需要能随意填写秒数。
 */
@Composable
fun ListAutoCenterDialog(
    seconds: Int,
    onConfirm: (Int) -> Unit,
    onDismiss: () -> Unit,
) {
    var text by remember(seconds) { mutableStateOf(seconds.toString()) }
    val focusRequester = remember { FocusRequester() }
    // 解析用 toIntOrNull：填了过大的数（超过 Int 范围）会得到 null，按无效处理，
    // 不会溢出成一个负数或奇怪的秒数。
    val parsed = text.toIntOrNull()
    val valid = parsed != null && parsed >= SettingsRepository.MinListAutoCenterSeconds

    LaunchedEffect(Unit) {
        // 等一帧让输入框挂载完成，否则 requestFocus 会落空
        delay(120L)
        focusRequester.requestFocus()
    }

    // 悬浮卡片样式（和播放栏、播放模式上拉框同一套）
    GlassDialog(onDismiss = onDismiss) {
        Text(
            text = "列表自动居中",
            style = MaterialTheme.typography.titleMedium,
            modifier = Modifier.padding(start = 20.dp, end = 20.dp, top = 18.dp),
        )
        Text(
            text = "停止操作这么多秒后，自动把正在播放的歌曲滚动到列表中间。",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(start = 20.dp, end = 20.dp, top = 6.dp),
        )
        OutlinedTextField(
            value = text,
            // 只留数字，并限长 6 位（最多 999999 秒，够用且不会溢出）
            onValueChange = { input -> text = input.filter { it.isDigit() }.take(6) },
            singleLine = true,
            shape = GlassShape,
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
            suffix = { Text("秒") },
            isError = text.isNotEmpty() && !valid,
            supportingText = {
                if (text.isEmpty() || !valid) {
                    Text(
                        text = if (text.isEmpty()) {
                            "请输入秒数"
                        } else {
                            "只能填 ≥ ${SettingsRepository.MinListAutoCenterSeconds} 的整数"
                        },
                        style = MaterialTheme.typography.labelSmall,
                    )
                }
            },
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 20.dp, vertical = 14.dp)
                .focusRequester(focusRequester),
        )
        Row(
            modifier = Modifier.fillMaxWidth().padding(end = 10.dp, bottom = 8.dp),
            horizontalArrangement = Arrangement.End,
        ) {
            GlassSecondaryButton(text = "取消", onClick = onDismiss)
            GlassPrimaryButton(
                text = "确定",
                enabled = valid,
                onClick = { parsed?.let(onConfirm) },
            )
        }
    }
}

/** 删除、清空、移除等操作的二次确认。 */
@Composable
fun ConfirmDialog(    title: String,
    message: String,
    confirmLabel: String,
    onDismiss: () -> Unit,
    onConfirm: () -> Unit,
) {
    GlassDialog(onDismiss = onDismiss) {
        Text(
            text = title,
            style = MaterialTheme.typography.titleMedium,
            modifier = Modifier.padding(start = 20.dp, end = 20.dp, top = 18.dp),
        )
        Text(
            text = message,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(horizontal = 20.dp, vertical = 12.dp),
        )
        Row(
            modifier = Modifier.fillMaxWidth().padding(end = 10.dp, bottom = 8.dp),
            horizontalArrangement = Arrangement.End,
        ) {
            GlassSecondaryButton(text = "取消", onClick = onDismiss)
            GlassPrimaryButton(text = confirmLabel, onClick = onConfirm)
        }
    }
}

/**
 * 歌词偏移调整。
 *
 * 布局：左侧 -0.2s、中间"重置"、右侧 +0.2s，"重置"下方是"确认"。
 * 点 -0.2s / +0.2s 只改待应用的值，点"确认"才真正生效；直接关掉弹窗等于放弃调整。
 *
 * 确认后会同时改写**同名 .lrc**和**文件里的内嵌歌词**（有哪个改哪个，两个都有就都改）：
 *   改一次，在任何设备、任何播放器里播这首歌时间轴都是对的，然后重新加载歌词立即可见效果。
 *   两者都是**直接覆盖**，不留 .bak 备份；内嵌歌词是等长原地改写，不动音频数据。
 */
@Composable
fun LyricsOffsetDialog(
    offsetMs: Long,
    onDismiss: () -> Unit,
    onApply: (Long) -> Unit,
    onEditLyrics: () -> Unit,
) {
    var pending by remember { mutableLongStateOf(offsetMs) }

    // 悬浮卡片样式（和播放栏、播放模式上拉框同一套）
    GlassDialog(onDismiss = onDismiss) {
            Column(
                modifier = Modifier.padding(horizontal = 24.dp, vertical = 20.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Text("歌词显示提前或延后", style = MaterialTheme.typography.titleMedium)

                Spacer(Modifier.height(6.dp))
                Text(
                    text = formatOffset(pending),
                    style = MaterialTheme.typography.headlineMedium,
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.primary,
                )

                Spacer(Modifier.height(18.dp))

                // 左"提前"、右"延后"，各自文字在按钮上方；中间是重置
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceEvenly,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Text(
                            text = "提前",
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        Spacer(Modifier.height(6.dp))
                        OutlinedButton(onClick = { pending -= LyricsOffsetStepMs }) {
                            Text("− 0.2s")
                        }
                    }

                    TextButton(
                        onClick = { pending = 0L },
                        enabled = pending != 0L,
                        modifier = Modifier.padding(horizontal = 4.dp),
                    ) {
                        Text("重置")
                    }

                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Text(
                            text = "延后",
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        Spacer(Modifier.height(6.dp))
                        OutlinedButton(onClick = { pending += LyricsOffsetStepMs }) {
                            Text("+ 0.2s")
                        }
                    }
                }

                Spacer(Modifier.height(18.dp))

                Button(
                    onClick = {
                        onApply(pending)
                        onDismiss()
                    },
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text("确认")
                }

                Spacer(Modifier.height(10.dp))

                Text(
                    text = "确认后将同时修改歌曲LRC文件和内置歌词(都是直接覆盖,不备份)",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = TextAlign.Center,
                )

                Spacer(Modifier.height(8.dp))

                // 歌词文字本身写错了也能改：进编辑器直接改文字和时间戳
                TextButton(onClick = onEditLyrics) {
                    Text("编辑歌词内容", style = MaterialTheme.typography.labelLarge)
                }
            }
        
    }
}

/**
 * 歌词内容编辑器：直接改歌词文字和时间戳。
 *
 * 保存目标**只能是同名 .lrc**：
 *   - 有同名 .lrc → **直接覆盖**（不留 .bak 备份）；
 *   - 只有内嵌歌词 → 新建同名 .lrc 来存（同名 LRC 优先级本来就高于内嵌，
 *     所以改完立即生效，内嵌歌词原样不动）。
 * 歌词是繁体时会自动转成简体再保存。
 */
@Composable
fun LyricsEditorDialog(
    initialText: String,
    /** true 表示这首歌只有内嵌歌词、保存后会新建同名 .lrc */
    willCreateLrc: Boolean,
    onDismiss: () -> Unit,
    onSave: (String) -> Unit,
) {
    var text by remember { mutableStateOf(initialText) }

    // 悬浮卡片样式（和播放栏、播放模式上拉框同一套）
    GlassDialog(onDismiss = onDismiss) {
            Column(Modifier.fillMaxWidth().padding(20.dp)) {
                Text("编辑歌词内容", style = MaterialTheme.typography.titleMedium)
                Spacer(Modifier.height(6.dp))
                Text(
                    text = if (willCreateLrc) {
                        "这首歌本来只有内嵌歌词，保存后会新建同名 .lrc 来存放（优先级更高，改完立即生效）"
                    } else {
                        "保存会覆盖同名 .lrc（不备份原文件）"
                    },
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )

                Spacer(Modifier.height(10.dp))

                OutlinedTextField(
                    value = text,
                    onValueChange = { text = it },
                    modifier = Modifier.fillMaxWidth().heightIn(min = 220.dp, max = 380.dp),
                    textStyle = MaterialTheme.typography.bodySmall,
                    placeholder = { Text("[00:12.34]歌词文字") },
                )

                Spacer(Modifier.height(8.dp))
                Text(
                    text = "每行一句，时间戳写成 [分:秒.毫秒]，例如 [01:23.45]；" +
                        "没有时间戳的行会当静态歌词显示。",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )

                Spacer(Modifier.height(14.dp))

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.End,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    TextButton(onClick = onDismiss) { Text("取消") }
                    Spacer(Modifier.width(8.dp))
                    Button(
                        onClick = { onSave(text) },
                        enabled = text.isNotBlank(),
                    ) {
                        Text("保存")
                    }
                }
            }
        
    }
}

@Composable
fun LyricsFontDialog(
    level: Int,
    onLevelChange: (Int) -> Unit,
    onDismiss: () -> Unit,
) {
    // 悬浮卡片样式（和播放栏、播放模式上拉框同一套）
    GlassDialog(onDismiss = onDismiss) {
            Column(
                modifier = Modifier.padding(horizontal = 24.dp, vertical = 20.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Text("歌词文字大小", style = MaterialTheme.typography.titleLarge)

                Spacer(Modifier.height(16.dp))

                // 预览：三行，中间一行按当前行样式显示，和歌词页保持一致
                Column(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    Text(
                        text = "没有言语能够说明",
                        fontSize = LyricsFontScale.textSp(level).sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                    )
                    Text(
                        text = "像埋伏在街头的某种气息",
                        fontSize = LyricsFontScale.textSp(level).sp,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.primary,
                        maxLines = 1,
                    )
                    Text(
                        text = "无意间经过把往日笑与泪勾起",
                        fontSize = LyricsFontScale.textSp(level).sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                    )
                }

                Spacer(Modifier.height(20.dp))

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        text = "小",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Slider(
                        value = level.toFloat(),
                        onValueChange = { onLevelChange(it.roundToInt()) },
                        valueRange = 0f..LyricsFontScale.MaxLevel.toFloat(),
                        steps = LyricsFontScale.MaxLevel - 1,
                        modifier = Modifier.weight(1f).padding(horizontal = 8.dp),
                    )
                    Text(
                        text = "大",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }

                Text(
                    text = "第 ${level + 1} / ${LyricsFontScale.MaxLevel + 1} 档",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )

                Spacer(Modifier.height(12.dp))

                Button(onClick = onDismiss, modifier = Modifier.fillMaxWidth()) {
                    Text("完成")
                }
            }
        
    }
}

/**
 * 首次运行的权限提示。
 *
 * 为什么要在启动时就弹（而不是等用户点到"导入"才问）：
 * 「所有文件访问」缺了，导入进来的歌读不到同名 .lrc 歌词，歌词偏移也写不回文件——
 * 这两件事恰恰是本应用最核心的功能，等用户撞上再解释体验很差。
 *
 * 说明一下两类权限的区别（实现上也确实不同）：
 *   「音乐和音频」是运行时权限，系统弹窗可以直接问；
 *   「所有文件访问」没有运行时弹窗，**只能跳到系统设置页让用户自己打开开关**，
 *   所以按钮文案会跟着当前缺哪个而变化。
 *
 * 这里同时承担"自我介绍"的职责：第一次打开的用户最需要知道的是**这个应用会不会联网、
 * 会不会动他的文件**。注意不能笼统写成"不联网、不上传" —— 「在线搜词」要联网，
 * 所以说清"唯一联网的是哪一项"反而更可信。
 */
@Composable
fun PermissionPromptDialog(
    audioGranted: Boolean,
    allFilesGranted: Boolean,
    onRequest: () -> Unit,
    onDismiss: () -> Unit,
) {
    // 悬浮卡片样式（和播放栏、播放模式上拉框同一套）
    GlassDialog(onDismiss = onDismiss) {
            Column(
                modifier = Modifier.padding(start = 20.dp, end = 20.dp, top = 18.dp),
            ) {
                Text("A-Music", style = MaterialTheme.typography.titleMedium)
                Spacer(Modifier.height(8.dp))
                Text(
                    text = "一个纯粹的本地音乐播放器：只播放你手机里的歌曲，不做在线歌单，" +
                        "也不会自动获取任何在线歌曲。",
                    style = MaterialTheme.typography.bodySmall,
                )
                Spacer(Modifier.height(6.dp))
                Text(
                    text = "唯一需要联网的是「在线搜词」，其余功能全部离线，" +
                        "也不采集、不上传任何数据。",
                    style = MaterialTheme.typography.bodySmall,
                )
                Spacer(Modifier.height(6.dp))
                Text(
                    text = "只有你自己点「保存歌词」或「歌词偏移」时，才会改写那首歌的歌词文件" +
                        "（直接覆盖，不留备份）。",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.height(14.dp))
                Text("先授予两个权限", style = MaterialTheme.typography.titleSmall)
                Spacer(Modifier.height(8.dp))
                PermissionLine(
                    granted = audioGranted,
                    title = "音乐和音频",
                    detail = "读取手机里的歌曲，没有它无法导入",
                )
                Spacer(Modifier.height(10.dp))
                PermissionLine(
                    granted = allFilesGranted,
                    title = "所有文件访问",
                    detail = "读取歌曲旁边的 .lrc 歌词；没有它歌词显示不出来，歌词偏移也写不回文件",
                )
            }
            Row(
                modifier = Modifier.fillMaxWidth().padding(end = 10.dp, bottom = 8.dp),
                horizontalArrangement = Arrangement.End,
            ) {
                GlassSecondaryButton(text = "稍后", onClick = onDismiss)
                GlassPrimaryButton(
                    text = when {
                        !audioGranted -> "授予音乐和音频"
                        !allFilesGranted -> "去打开「所有文件访问」"
                        else -> "完成"
                    },
                    onClick = onRequest,
                )
            }
    }
}

@Composable
private fun PermissionLine(granted: Boolean, title: String, detail: String) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Icon(
            imageVector = if (granted) Icons.Filled.Check else Icons.Filled.Close,
            contentDescription = if (granted) "已授予" else "未授予",
            tint = if (granted) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.size(18.dp),
        )
        Spacer(Modifier.size(8.dp))
        Column {
            Text(text = title, style = MaterialTheme.typography.bodyMedium)
            Text(
                text = detail,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}
