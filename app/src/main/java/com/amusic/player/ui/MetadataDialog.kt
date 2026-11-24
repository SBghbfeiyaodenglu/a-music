package com.amusic.player.ui

import android.content.Context
import android.view.inputmethod.InputMethodManager
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.LocalTextStyle
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.amusic.player.data.media.MetadataItem

/**
 * 歌曲元数据（顶栏感叹号打开）。**点字段内容就地编辑，点别处自动保存**。
 *
 * ## 这个文件的三条硬设计（都是为了让"点别处一定失焦"成立）
 *
 * ```
 * ① FocusManager / SoftwareKeyboardController 必须在 Dialog 作用域内取
 *     Compose 的 Dialog 会开**新窗口 / 新 composition root**，并注入属于它自己的这一对
 *     CompositionLocal。在 Dialog 外面取到的是 Activity 窗口的实例 —— 用它的 clearFocus()
 *     对弹窗里的输入框毫无作用，而且**不报错**，表现就是"点哪里都没反应"。
 *
 * ② 不依赖 clearFocus()：编辑结束就把输入框**从界面上卸载**
 *     非编辑态那一行渲染的是普通 Text，只有正在编辑的那一行才挂 BasicTextField。
 *     节点离开 composition，焦点是**必然**释放的 —— 不依赖任何 API 生不生效。
 *
 * ③ 编辑值提升到父层（drafts）
 *     输入框卸载后 TextFieldValue 不会跟着丢，重新点进来光标仍在文字末尾。
 * ```
 *
 * 哪些能改、哪些不能改，按"**绝对不能碰音频数据**"这条铁律：
 *
 * ```
 * 能改    标题 / 艺术家 / 专辑 / 日期 / 风格 / 音轨号 / 碟号（文件里的文本标签）
 * 不能改  时长 / 码率 / MIME / 文件名 / 歌词来源 / 音轨总数
 *         （时长码率是从音频帧里算出来的；文件名要重命名文件；音轨总数各家播放器支持度不一）
 * ```
 * 只支持 FLAC / MP3（写入走 TagWriter：只改标签区，音频帧一个字节不动）。
 */
@Composable
fun MetadataDialog(
    songTitle: String,
    loading: Boolean,
    metadata: List<MetadataItem>,
    fileInfo: List<MetadataItem>,
    /** 可编辑标签的当前值（规范名→值）；null＝这个格式不支持改 */
    editable: Map<String, String>?,
    /** 改完一个字段：值就是原文（可以留空 —— 留空＝写空标签，不会删字段） */
    /** 返回 false = 这次编辑没收下（上一个字段还在写盘），要把输入框和内容留着 */
    onSaveField: (String, String?) -> Boolean,
    onDismiss: () -> Unit,
) {
    // 正在编辑哪个字段（规范名）。null＝没有
    // ⚠ 这两个不能以 editable 为 key：父层每保存一个字段都会重建那个 map，
    //   键一变就把「正在编辑的字段」清掉（异步回来时正好撞上就丢字）
    var editingKey by remember { mutableStateOf<String?>(null) }
    val drafts = remember { mutableStateMapOf<String, TextFieldValue>() }
    // 进入编辑时的**显示值**：判断「到底改没改」用它，
    // 不能拿 TagWriter 的原始标签值比（两个来源，点进点出都会误写一次）
    var editOriginal by remember { mutableStateOf("") }

    GlassDialog(
        onDismiss = onDismiss,
        // 点卡片外面：只让输入框失焦（＝保存），不关弹窗。
        // 由 GlassDialog 自己在 Dialog 作用域内取 FocusManager 处理（类注释 ①）
        clearFocusOnScrimTap = true,
    ) {
        // ⚠⚠ 必须在 Dialog 内容里面取（类注释 ①）
        val focusManager = LocalFocusManager.current
        val keyboard = LocalSoftwareKeyboardController.current
        val view = LocalView.current

        /** 结束编辑：先保存，再把输入框卸载、清焦点、收键盘 */
        fun endEditing(force: Boolean = false) {
            val key = editingKey ?: return
            val text = drafts[key]?.text?.trim()
            // 编辑态用的是**标签名**，写盘用的是规范名（"年份"/"日期" 都写成 year）
            val saveKey = LABEL_TO_KEY[key] ?: key
            // 留空就写空 —— **不删字段**（字段内容允许留空，留空即写空标签）
            if (text != null && text != editOriginal.trim()) {
                // 父层说"这次没收下"（上一个字段还在写盘）→ 保持编辑态，把刚敲的内容留在框里，
                // 等下次失焦再存；点关闭按钮时 force=true，不拦。
                if (!onSaveField(saveKey, text) && !force) return
            }
            drafts.remove(key)
            editingKey = null
            focusManager.clearFocus(force = true)
            keyboard?.hide()
            // 平台兜底：Compose 的 hide() 不保证一定生效
            runCatching {
                (view.context.getSystemService(Context.INPUT_METHOD_SERVICE) as? InputMethodManager)
                    ?.hideSoftInputFromWindow(view.windowToken, 0)
            }
        }

        Column(
            modifier = Modifier
                // ⚠ pointerInput 必须放在 padding **前面**：修饰符是"由外到内"生效的，
                //   写在 padding 后面的话手势只覆盖内容区，点卡片的内边距（比如标题上方、
                //   左右留白）就不会触发 —— 表现就是"只有点下面才失焦"。
                // 卡片里任何"没被输入框吃掉"的点击都算"点别处"；输入框自己会消费点击，
                // 所以点输入框不会结束编辑（正是想要的行为）。
                .pointerInput(Unit) {
                    detectTapGestures(onTap = { endEditing() })
                }
                .padding(horizontal = 20.dp, vertical = 18.dp),
        ) {
            Text(text = "歌曲元数据", style = MaterialTheme.typography.titleLarge)
            Text(
                text = songTitle,
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.primary,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.padding(top = 4.dp),
            )

            Spacer(Modifier.height(14.dp))

            Column(
                modifier = Modifier
                    .heightIn(max = 460.dp)
                    .verticalScroll(rememberScrollState()),
            ) {
                SectionTitle("标签元数据", onTap = { endEditing() })
                if (loading) {
                    PlainRow("读取中 …", "", dim = true, onTap = { endEditing() })
                } else if (metadata.isEmpty()) {
                    PlainRow("无元数据", "这个文件里没有可读的标签信息", dim = true, onTap = { endEditing() })
                } else {
                    metadata.forEach { item ->
                        val saveKey = editable?.let { LABEL_TO_KEY[item.label] }
                        if (saveKey == null) {
                            PlainRow(item.label, item.value, onTap = { endEditing() })
                        } else {
                            // ⚠ 编辑态/草稿的键用**标签名**，不能用规范名：
                            //   一个文件同时有「年份」和「日期」两行时，两行都映射到 year，
                            //   用规范名会出现"点一个、两个一起变输入框"（还共用同一份草稿）。
                            val key = item.label
                            EditRow(
                                label = item.label,
                                original = item.value,
                                editing = editingKey == key,
                                value = drafts[key] ?: TextFieldValue(
                                    item.value,
                                    TextRange(item.value.length),   // 光标默认落在文字最后
                                ),
                                onChange = { drafts[key] = it },
                                onBegin = {
                                    endEditing()                       // 换字段：先把上一个存了
                                    editOriginal = item.value
                                    drafts[key] = TextFieldValue(
                                        item.value,
                                        TextRange(item.value.length),
                                    )
                                    editingKey = key
                                },
                                onEnd = { endEditing() },
                            )
                        }
                    }
                }

                Spacer(Modifier.height(12.dp))
                SectionTitle("文件信息", onTap = { endEditing() })
                fileInfo.forEach { item -> PlainRow(item.label, item.value, onTap = { endEditing() }) }
            }

            Spacer(Modifier.height(16.dp))

            // 关闭：正下方居中、做大一点方便点（不放额外的文字提示）
            Box(
                modifier = Modifier.fillMaxWidth(),
                contentAlignment = Alignment.Center,
            ) {
                Surface(
                    onClick = {
                        endEditing(force = true)   // 先保存再关；点关闭时不再拦（双保险）
                        onDismiss()
                    },
                    shape = PillShape,
                    color = MaterialTheme.colorScheme.primary.copy(alpha = 0.16f),
                    border = BorderStroke(1.5.dp, MaterialTheme.colorScheme.primary.copy(alpha = 0.45f)),
                    modifier = Modifier
                        .fillMaxWidth(0.62f)
                        .height(50.dp),
                ) {
                    Box(contentAlignment = Alignment.Center) {
                        Text(
                            text = "关闭",
                            style = MaterialTheme.typography.titleMedium,
                            color = MaterialTheme.colorScheme.primary,
                        )
                    }
                }
            }
        }
    }
}

/** 标签中文名 → TagWriter 的规范名（只列能改的） */
private val LABEL_TO_KEY = mapOf(
    "标题" to "title",
    "艺术家" to "artist",
    "专辑" to "album",
    "年份" to "year",      // 整轨年份在 MediaMetadataRetriever 里叫「年份」
    "日期" to "year",      // 有些容器会以「日期」出现
    "风格" to "genre",
    "音轨号" to "track",
    "碟号" to "disc",
)

/**
 * 可编辑的一行：非编辑态是**普通文字**（点了才开始编辑）；编辑态才挂 BasicTextField。
 * 输入框卸载＝焦点必然释放（类注释 ②）。
 */
@Composable
private fun EditRow(
    label: String,
    original: String,
    editing: Boolean,
    value: TextFieldValue,
    onChange: (TextFieldValue) -> Unit,
    onBegin: () -> Unit,
    onEnd: () -> Unit,
) {
    val focusRequester = remember { FocusRequester() }
    var everFocused by remember(editing) { mutableStateOf(false) }

    if (editing) {
        LaunchedEffect(Unit) {
            runCatching { focusRequester.requestFocus() }
        }
    }

    Row(
        modifier = Modifier.fillMaxWidth().padding(vertical = 5.dp),
        verticalAlignment = Alignment.Top,
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.width(88.dp).padding(top = 2.dp),
        )
        if (editing) {
            BasicTextField(
                value = value,
                onValueChange = onChange,
                singleLine = true,
                textStyle = LocalTextStyle.current.merge(MaterialTheme.typography.bodySmall)
                    .copy(color = MaterialTheme.colorScheme.onSurface),
                cursorBrush = SolidColor(MaterialTheme.colorScheme.primary),
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                keyboardActions = KeyboardActions(onDone = { onEnd() }),
                modifier = Modifier
                    .weight(1f)
                    .padding(vertical = 2.dp)
                    .focusRequester(focusRequester)
                    .onFocusChanged { state ->
                        if (state.isFocused) {
                            everFocused = true
                        } else if (everFocused) {
                            // 真正失焦（点别处 / 系统收键盘）→ 结束编辑并保存
                            onEnd()
                        }
                    },
            )
        } else {
            Box(
                modifier = Modifier
                    .weight(1f)
                    .clickable(
                        interactionSource = remember { MutableInteractionSource() },
                        indication = null,
                        onClick = onBegin,
                    )
                    .padding(vertical = 5.dp),
            ) {
                Text(
                    text = original.ifBlank { "（空，点这里填）" },
                    style = MaterialTheme.typography.bodySmall,
                    color = if (original.isBlank()) {
                        MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f)
                    } else {
                        MaterialTheme.colorScheme.onSurface
                    },
                    textAlign = TextAlign.Start,
                )
            }
        }
    }
    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f))
}

/** 只读的一行（点它＝点到别处了，结束编辑） */
@Composable
private fun PlainRow(label: String, value: String, dim: Boolean = false, onTap: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
                onClick = onTap,
            )
            .padding(vertical = 5.dp),
        verticalAlignment = Alignment.Top,
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.width(88.dp),
        )
        Box(Modifier.weight(1f)) {
            Text(
                text = value,
                style = MaterialTheme.typography.bodySmall,
                color = if (dim) {
                    MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f)
                } else {
                    MaterialTheme.colorScheme.onSurface
                },
                textAlign = TextAlign.Start,
            )
        }
    }
    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f))
}

@Composable
private fun SectionTitle(text: String, onTap: () -> Unit) {
    Text(
        text = text,
        style = MaterialTheme.typography.labelMedium,
        color = MaterialTheme.colorScheme.primary,
        modifier = Modifier
            .fillMaxWidth()
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
                onClick = onTap,
            )
            .padding(top = 6.dp, bottom = 6.dp),
    )
}
