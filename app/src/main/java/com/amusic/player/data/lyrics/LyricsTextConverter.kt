package com.amusic.player.data.lyrics

/**
 * 繁体中文 → 简体中文。
 *
 * 用在**歌词保存**那一步：网上搜来的歌词常常是繁体（台湾那边的版本），直接存下来
 * 大陆用户读着别扭，所以保存前自动转一遍。本来就是简体时原样返回，一个字符都不动。
 *
 * 实现是内置对照表（[T2STable]，数据来自 OpenCC，离线、不联网、所有机型行为一致），
 * 走"最长匹配优先"：
 * ```
 * 1. 先试词组（2~10 字，如「一目瞭然」→「一目了然」）——歧义字靠它才不出错
 * 2. 没命中词组再查单字表（如「這」→「这」）
 * 3. 都不命中就原样保留（英文、数字、时间戳、生僻字都不受影响）
 * ```
 *
 * 为什么不用 `android.icu.text.Transliterator`：minSdk 24，ICU 的繁简转换在旧机型或
 * 被裁剪过的 ICU 数据上不保证存在；歌词转换只在保存时跑一次，内置表更确定。
 *
 * 注意：[T2STable] 只做"简繁有别的字"，所以「著」不会被盲目转成「着」
 * （否则「著名/著作」会变成「着名/着作」），只对歌词里必定读 zhe/zhuó/zháo 的搭配
 * （看著/想著/著急…）做了补充规则。
 */
object LyricsTextConverter {

    /** 单字表：繁体字 → 简体字。懒加载，只在第一次真需要转换时建表 */
    private val charMap: Map<Char, Char> by lazy {
        val pairs = T2STable.CHAR_PAIRS
        HashMap<Char, Char>(pairs.length / 2 * 2).apply {
            var i = 0
            while (i + 1 < pairs.length) {
                put(pairs[i], pairs[i + 1])
                i += 2
            }
        }
    }

    /** 词组表：短语 → 简体短语（只收"逐字转换会转错"的那些）。懒加载 */
    private val phraseMap: Map<String, String> by lazy {
        HashMap<String, String>(256).apply {
            if (T2STable.PHRASE_DATA.isEmpty()) return@apply
            for (entry in T2STable.PHRASE_DATA.split(ENTRY_SEP)) {
                val split = entry.indexOf(KEY_VALUE_SEP)
                if (split > 0) {
                    // ⚠ OpenCC 的词组表里有 9 条是"多个候选、空格分隔"（例如
                    //   「想像 → 想像 想象」「龍鍾 → 龙钟 龙锺」）。整串当值用的话，
                    //   保存歌词时会把「想像」写成「想像 想象」——凭空多出两个字。
                    //   约定取**第一个**候选（OpenCC 的偏好），生成脚本也同步取第一个。
                    val value = entry.substring(split + 1).substringBefore(' ')
                    put(entry.substring(0, split), value)
                }
            }
        }
    }

    /**
     * 转了才返回新字符串；本来就是简体（或没有可转的字）时**原样返回同一个对象**，
     * 调用方用 `converted !== original` 或字符串比较就能知道"到底转没转"。
     */
    fun toSimplified(text: String): String {
        if (text.isEmpty()) return text
        val sb = StringBuilder(text.length + 8)
        var changed = false
        var i = 0
        while (i < text.length) {
            var matched = false
            // 最长匹配：从可能的最高长度往下试，命中就整段替换
            var len = minOf(T2STable.MAX_PHRASE_LEN, text.length - i)
            while (len >= 2) {
                val hit = phraseMap[text.substring(i, i + len)]
                if (hit != null) {
                    sb.append(hit)
                    i += len
                    changed = true
                    matched = true
                    break
                }
                len--
            }
            if (matched) continue

            val ch = text[i]
            val simplified = charMap[ch]
            if (simplified != null && simplified != ch) {
                sb.append(simplified)
                changed = true
            } else {
                sb.append(ch)
            }
            i++
        }
        return if (changed) sb.toString() else text
    }

    private const val ENTRY_SEP = '\u0001'
    private const val KEY_VALUE_SEP = '\u0002'

    /** 单字表条目数（单元测试用它防止生成脚本出错、把表生成成空的） */
    internal val tableSize: Int get() = T2STable.CHAR_PAIRS.length / 2

    /** 词组表条目数，同上 */
    internal val phraseCount: Int get() = phraseMap.size
}
