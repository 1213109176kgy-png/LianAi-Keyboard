package com.weike.ime.knowledge

/** App-owned identities: imported documents with the same title remain user-editable. */
object SystemKnowledge {
    const val GOUTOU_ID = "builtin-goutoujunshi-v1"
    const val TONG_ID = "builtin-tong-jincheng-v1"
    data class Entry(val id: String, val title: String, val asset: String, val revision: Int)
    val entries = listOf(
        Entry(GOUTOU_ID, "狗头军师知识库", "knowledge/goutoujunshi.md", 3),
        Entry(TONG_ID, "童锦程知识库", "knowledge/tong-jincheng.md", 1)
    )
    fun contains(id: String) = entries.any { it.id == id }
}
