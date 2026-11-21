package com.amusic.player.data

/**
 * 播放推进的纯逻辑，抽出来是为了能单独跑单元测试。
 *
 * 关键区分（别改错）：
 *   顺序 / 倒序 / 随机 —— 都在**当前列表内部**循环
 *   列表循环        —— 在**各个列表之间**轮转：当前列表放完一轮就切到下一个列表继续放
 */
object PlaybackOrder {

    /**
     * 列表循环：从 [currentListIndex] 出发找相邻的**有歌**的列表（空列表跳过）。
     *
     * 最多绕一圈找；只有一个列表时结果就是它自己 —— 也就是永远循环这一个列表；
     * 所有列表都空返回 -1（调用方据此停止播放）。
     */
    fun nextNonEmptyList(listSizes: List<Int>, currentListIndex: Int, forward: Boolean): Int {
        if (listSizes.isEmpty()) return -1
        if (listSizes.none { it > 0 }) return -1
        var index = currentListIndex.coerceIn(0, listSizes.lastIndex)
        repeat(listSizes.size) {
            index = if (forward) {
                (index + 1) % listSizes.size
            } else {
                (index - 1 + listSizes.size) % listSizes.size
            }
            if (listSizes[index] > 0) return index
        }
        return -1
    }
}
