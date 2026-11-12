package com.arflix.tv.ui.screens.tv.live

import java.text.Normalizer

private val footballWords = listOf("futebol", "football", "soccer", "premiere", "brasileirao", "libertadores", "champions")
private val sportWords = listOf("esporte", "sport", "espn", "dazn")

private fun normalizeLabel(s: String): String =
    Normalizer.normalize(s, Normalizer.Form.NFD).replace(Regex("\\p{Mn}+"), "").lowercase()

/**
 * Orbita Play "Futebol" menu: picks the best live category for football.
 * 1. A group whose name mentions football (e.g. "FUTEBOL", "PREMIERE").
 * 2. A group whose name mentions sports (e.g. "ESPORTES", "SPORTV").
 * 3. ARVIO's built-in "Sports · Global" category, or any sport-icon category.
 */
internal fun findOrbitaFootballCategoryId(tree: LiveCategoryTree): String? {
    val all = ArrayList<LiveCategory>()
    fun collect(c: LiveCategory) { all += c; c.children.forEach(::collect) }
    tree.top.forEach(::collect)
    tree.allSections.forEach { section -> section.categories.forEach(::collect) }
    val candidates = all.filter { it.count > 0 && it.id !in tree.hidden.categories.map { h -> h.id } }
    fun label(c: LiveCategory) = normalizeLabel(c.playlistGroupName ?: c.label)
    return candidates.firstOrNull { c -> footballWords.any { it in label(c) } }?.id
        ?: candidates.firstOrNull { c -> sportWords.any { it in label(c) } && c.id != "g-sports" }?.id
        ?: candidates.firstOrNull { it.id == "g-sports" }?.id
        ?: candidates.firstOrNull { it.iconToken == CategoryIcon.Sport }?.id
}

/** True when a playlist group name looks like a football group. */
fun isOrbitaFootballLabel(label: String): Boolean = normalizeLabel(label).let { l -> footballWords.any { it in l } }

/** True when a playlist group name looks like a sports group. */
fun isOrbitaSportsLabel(label: String): Boolean = normalizeLabel(label).let { l -> sportWords.any { it in l } }
