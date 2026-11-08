package com.weike.ime.ime

/** Common symbols are grouped by writing context; unusual symbols stay on their own page. */
internal object KeyboardSymbols {
    const val NUMERIC = 0
    const val ENGLISH = 1
    const val CHINESE = 2

    data class Page(val title: String, val rows: List<List<String>>)

    val pages = listOf(
        Page("数字", listOf(
            listOf("1", "2", "3", "4", "5", "6", "7", "8", "9", "0"),
            listOf(".", ",", "+", "-", "×", "÷", "=", "%", "/", ":"),
            listOf("(", ")", "[", "]", "¥", "$", "€", "£", "°", "℃")
        )),
        Page("英文", listOf(
            listOf("1", "2", "3", "4", "5", "6", "7", "8", "9", "0"),
            listOf("@", "#", "$", "&", "_", "-", "/", ":", ";", "\""),
            listOf(".", ",", "?", "!", "'", "(", ")", "[", "]", "\\")
        )),
        Page("中文", listOf(
            listOf("，", "。", "？", "！", "、", "：", "；", "“", "”", "…"),
            listOf("（", "）", "【", "】", "《", "》", "「", "」", "『", "』"),
            listOf("￥", "——", "……", "·", "～", "〈", "〉", "〔", "〕", "—")
        )),
        Page("扩展", listOf(
            listOf("‖", "*", "=", "{", "}", "|", "~", "<", ">", "^"),
            listOf("`", "•", "√", "π", "§", "¢", "©", "®", "™", "№"),
            listOf("＾", "＿", "〃", "♪", "♡", "☆", "★", "→", "※", "〓")
        ))
    )
}
