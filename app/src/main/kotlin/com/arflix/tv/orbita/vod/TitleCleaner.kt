package com.arflix.tv.orbita.vod

import java.text.Normalizer

/** Cleans IPTV titles like "PT | Sertão Vermelho (2026) [4K] LEG" so they read well and match on TMDB. */
object TitleCleaner {

    private val yearRegex = Regex("""(?:^|[\s(\[\-.])((?:19|20)\d{2})(?:$|[\s)\].\-])""")
    private val episodeRegexes = listOf(
        Regex("""(?i)\bS(\d{1,2})\s*[._-]?\s*E(\d{1,4})\b"""),
        Regex("""(?i)\bT(\d{1,2})\s*[._-]?\s*E(\d{1,4})\b"""),
        Regex("""(?i)\b(\d{1,2})x(\d{1,4})\b"""),
    )
    private val bracketRegex = Regex("""[\[(][^\])]*[\])]""")
    private val prefixRegex = Regex("""^\s*([A-Z]{2,4}|[A-Z]{2}\s?-\s?[A-Z]{2})\s*[|:\-–]\s+""")
    private val tagWords = Regex(
        """(?i)\b(4k|uhd|fhd|hd|sd|1080p|720p|2160p|hdr|dual|dub|dublado|leg|legendado|nacional|""" +
            """multi|audio|áudio|h265|hevc|x264|x265|web-?dl|bluray|cam|ts|vip|lançamento|lancamento)\b"""
    )

    fun year(title: String): Int? = yearRegex.findAll(title).lastOrNull()?.groupValues?.get(1)?.toIntOrNull()

    /** (series name, season, episode) when the title marks an episode. */
    fun episode(title: String): Triple<String, Int, Int>? {
        for (r in episodeRegexes) {
            val m = r.find(title) ?: continue
            val s = m.groupValues[1].toIntOrNull() ?: continue
            val e = m.groupValues[2].toIntOrNull() ?: continue
            val name = title.substring(0, m.range.first).trim().trimEnd('-', '|', ':', '.', '_', '(', '[').trim()
            return Triple(name.ifBlank { title }, s, e)
        }
        return null
    }

    /** Title for display: no country prefix, no quality tags, no trailing year in brackets. */
    fun display(raw: String): String {
        var t = raw.trim()
        t = prefixRegex.replace(t, "")
        t = bracketRegex.replace(t, " ")
        t = tagWords.replace(t, " ")
        t = t.replace(Regex("""\s*[|•]\s*$"""), "")
        t = t.replace(Regex("""\s{2,}"""), " ").trim().trim('-', '|', ':', '.').trim()
        return t.ifBlank { raw.trim() }
    }

    /** Text used to search TMDB: display name without the year. */
    fun query(raw: String): String {
        val d = display(raw)
        return d.replace(Regex("""\b(19|20)\d{2}\b"""), "").replace(Regex("""\s{2,}"""), " ").trim()
    }

    fun normalize(s: String): String =
        Normalizer.normalize(s, Normalizer.Form.NFD).replace(Regex("\\p{Mn}+"), "").lowercase()
}
