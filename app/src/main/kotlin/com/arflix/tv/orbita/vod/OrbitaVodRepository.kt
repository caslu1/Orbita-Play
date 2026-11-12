package com.arflix.tv.orbita.vod

import android.content.Context
import com.arflix.tv.data.repository.IptvRepository
import com.arflix.tv.data.repository.OrbitaVodSource
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONArray
import org.json.JSONObject
import java.io.BufferedReader
import java.io.File
import java.io.IOException
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit

data class VodLoadState(
    val loading: Boolean = false,
    val catalog: VodCatalog = VodCatalog(),
    val error: String? = null,
    val hasSources: Boolean = true,
    val loadedAt: Long = 0,
)

/**
 * Orbita Play: reads Filmes and Séries straight from the user's IPTV lists.
 *
 * - Xtream lists (get.php / player_api login): categories, movies and series via player_api.php.
 * - Plain M3U lists: the #EXTINF entries that point at movies (/movie/, .mp4, .mkv…) or
 *   series (/series/, S01E02…) are grouped by their group-title.
 */
object OrbitaVodRepository {

    private const val USER_AGENT = "OrbitaPlay/Android"
    private val http: OkHttpClient by lazy {
        OkHttpClient.Builder()
            .connectTimeout(20, TimeUnit.SECONDS)
            .readTimeout(90, TimeUnit.SECONDS)
            .followRedirects(true)
            .followSslRedirects(true)
            .build()
    }

    private val _state = MutableStateFlow(VodLoadState(loading = false))
    val state: StateFlow<VodLoadState> = _state.asStateFlow()
    private val loadMutex = Mutex()
    private var sources: List<OrbitaVodSource> = emptyList()
    private val episodeCache = ConcurrentHashMap<String, List<VodEpisode>>()

    /** Loads the catalogue once (or again when [force]). Safe to call from several screens. */
    suspend fun ensureLoaded(context: Context, iptvRepository: IptvRepository, force: Boolean = false) {
        loadMutex.withLock {
            val current = _state.value
            val fresh = System.currentTimeMillis() - current.loadedAt < 6 * 60 * 60 * 1000L
            if (!force && !current.catalog.isEmpty && fresh) return
            val config = iptvRepository.observeConfig().first()
            val list = iptvRepository.orbitaVodSources(config).filter { it.m3uUrl.isNotBlank() || it.isXtream }
            sources = list
            if (list.isEmpty()) {
                _state.value = VodLoadState(hasSources = false)
                return
            }
            _state.value = current.copy(loading = true, error = null, hasSources = true)
            try {
                val catalog = withContext(Dispatchers.IO) { loadAll(context, list) }
                _state.value = VodLoadState(catalog = catalog, loadedAt = System.currentTimeMillis())
            } catch (e: Exception) {
                _state.value = current.copy(loading = false, error = e.message ?: e.javaClass.simpleName)
            }
        }
    }

    private suspend fun loadAll(context: Context, list: List<OrbitaVodSource>): VodCatalog = coroutineScope {
        val parts = list.map { src ->
            async(Dispatchers.IO) {
                runCatching { if (src.isXtream) loadXtream(src) else loadM3u(context, src) }.getOrNull()
            }
        }.awaitAll().filterNotNull()
        if (parts.isEmpty()) throw IOException("Não foi possível ler os filmes e séries da lista")
        VodCatalog(
            movieCategories = parts.flatMap { it.movieCategories }.distinctBy { it.id },
            movies = parts.flatMap { it.movies },
            seriesCategories = parts.flatMap { it.seriesCategories }.distinctBy { it.id },
            series = parts.flatMap { it.series },
        )
    }

    // ---------------------------------------------------------------------------------------
    // Xtream
    // ---------------------------------------------------------------------------------------

    private fun api(src: OrbitaVodSource, action: String, extra: String = ""): String =
        "${src.xtreamBaseUrl!!.trimEnd('/')}/player_api.php?username=${enc(src.xtreamUsername!!)}" +
            "&password=${enc(src.xtreamPassword!!)}&action=$action$extra"

    private fun enc(v: String) = java.net.URLEncoder.encode(v, "UTF-8")

    private fun loadXtream(src: OrbitaVodSource): VodCatalog {
        val base = src.xtreamBaseUrl!!.trimEnd('/')
        val user = src.xtreamUsername!!
        val pass = src.xtreamPassword!!
        val prefix = "${src.id}:"

        val movieCats = if (src.importMovies) categories(api(src, "get_vod_categories"), prefix) else emptyList()
        val movies = if (!src.importMovies) emptyList() else jsonArray(api(src, "get_vod_streams")).mapObjects { o ->
            val id = o.optString("stream_id").ifBlank { return@mapObjects null }
            val ext = o.optString("container_extension").ifBlank { "mp4" }
            val name = o.optString("name").trim()
            VodMovie(
                key = "x:${src.id}:m:$id",
                name = cleanDisplayName(name),
                poster = o.optString("stream_icon").takeIf { it.startsWith("http") },
                categoryId = prefix + o.optString("category_id"),
                streamUrl = "$base/movie/$user/$pass/$id.$ext",
                year = o.optString("year").toIntOrNull() ?: TitleCleaner.year(name),
                rating = o.optString("rating").replace(',', '.').toDoubleOrNull()?.takeIf { it > 0 },
                tmdbId = o.optString("tmdb").toIntOrNull()?.takeIf { it > 0 },
                addedEpochSec = o.optString("added").toLongOrNull() ?: 0,
            )
        }
        val seriesCats = if (src.importSeries) categories(api(src, "get_series_categories"), prefix) else emptyList()
        val series = if (!src.importSeries) emptyList() else jsonArray(api(src, "get_series")).mapObjects { o ->
            val id = o.optString("series_id").ifBlank { return@mapObjects null }
            val name = o.optString("name").trim()
            val backdrop = o.optJSONArray("backdrop_path")?.optString(0)?.takeIf { it.startsWith("http") }
            VodSeries(
                key = "x:${src.id}:s:$id",
                name = cleanDisplayName(name),
                poster = o.optString("cover").takeIf { it.startsWith("http") },
                categoryId = prefix + o.optString("category_id"),
                year = o.optString("releaseDate").take(4).toIntOrNull()
                    ?: o.optString("release_date").take(4).toIntOrNull()
                    ?: TitleCleaner.year(name),
                rating = o.optString("rating").replace(',', '.').toDoubleOrNull()?.takeIf { it > 0 },
                tmdbId = o.optString("tmdb").toIntOrNull()?.takeIf { it > 0 },
                plot = o.optString("plot").takeIf { it.isNotBlank() && it != "null" },
                backdrop = backdrop,
                xtreamSeriesId = id,
                sourceId = src.id,
                addedEpochSec = o.optString("last_modified").toLongOrNull() ?: 0,
            )
        }
        return VodCatalog(movieCats, movies, seriesCats, series)
    }

    private fun categories(url: String, prefix: String): List<VodCategory> =
        jsonArray(url).mapObjects { o ->
            val id = o.optString("category_id").ifBlank { return@mapObjects null }
            VodCategory(prefix + id, o.optString("category_name").trim().ifBlank { "Sem nome" })
        }

    /** Episodes of a series (Xtream: fetched once and cached; M3U: already in the series). */
    suspend fun episodes(series: VodSeries): List<VodEpisode> {
        if (series.xtreamSeriesId == null) return series.episodes
        episodeCache[series.key]?.let { return it }
        val src = sources.firstOrNull { it.id == series.sourceId && it.isXtream } ?: return emptyList()
        return withContext(Dispatchers.IO) {
            val base = src.xtreamBaseUrl!!.trimEnd('/')
            val root = jsonObject(api(src, "get_series_info", "&series_id=${series.xtreamSeriesId}"))
            val eps = ArrayList<VodEpisode>()
            val episodes = root.opt("episodes")
            fun addSeason(seasonKey: String, arr: JSONArray) {
                for (i in 0 until arr.length()) {
                    val e = arr.optJSONObject(i) ?: continue
                    val id = e.optString("id")
                    if (id.isBlank()) continue
                    val ext = e.optString("container_extension").ifBlank { "mp4" }
                    val info = e.optJSONObject("info")
                    eps += VodEpisode(
                        season = e.optString("season").toIntOrNull() ?: seasonKey.toIntOrNull() ?: 1,
                        number = e.optString("episode_num").toIntOrNull() ?: (i + 1),
                        title = e.optString("title").trim().ifBlank { "Episódio ${i + 1}" },
                        streamUrl = "$base/series/${src.xtreamUsername}/${src.xtreamPassword}/$id.$ext",
                        plot = info?.optString("plot")?.takeIf { it.isNotBlank() && it != "null" },
                        still = info?.optString("movie_image")?.takeIf { it.startsWith("http") },
                        durationSec = info?.optString("duration_secs")?.toIntOrNull(),
                    )
                }
            }
            when (episodes) {
                is JSONObject -> episodes.keys().forEach { k -> episodes.optJSONArray(k)?.let { addSeason(k, it) } }
                is JSONArray -> for (i in 0 until episodes.length()) episodes.optJSONArray(i)?.let { addSeason("${i + 1}", it) }
            }
            val sorted = eps.sortedWith(compareBy({ it.season }, { it.number }))
            if (sorted.isNotEmpty()) episodeCache[series.key] = sorted
            sorted
        }
    }

    // ---------------------------------------------------------------------------------------
    // Plain M3U
    // ---------------------------------------------------------------------------------------

    private val attrRegex = Regex("""([\w-]+)="([^"]*)"""")
    private val vodExtensions = listOf(".mp4", ".mkv", ".avi", ".mov", ".m4v", ".wmv")

    private fun loadM3u(context: Context, src: OrbitaVodSource): VodCatalog {
        val tmp = File(context.cacheDir, "orbita_vod_${src.id.hashCode()}.m3u")
        val req = Request.Builder().url(src.m3uUrl).header("User-Agent", USER_AGENT).build()
        http.newCall(req).execute().use { res ->
            if (!res.isSuccessful) throw IOException("HTTP ${res.code}")
            tmp.outputStream().use { out -> res.body!!.byteStream().copyTo(out) }
        }
        val movies = ArrayList<VodMovie>()
        val movieCats = LinkedHashMap<String, VodCategory>()
        val seriesCats = LinkedHashMap<String, VodCategory>()
        // group -> show -> episodes
        val shows = LinkedHashMap<String, LinkedHashMap<String, MutableList<Pair<VodEpisode, String?>>>>()
        val prefix = "${src.id}:m3u:"
        var info: String? = null
        tmp.bufferedReader().use { reader: BufferedReader ->
            reader.lineSequence().forEach { raw ->
                val line = raw.trim()
                when {
                    line.startsWith("#EXTINF", true) -> info = line
                    line.isEmpty() || line.startsWith("#") -> Unit
                    else -> {
                        val inf = info ?: return@forEach
                        info = null
                        val attrs = HashMap<String, String>()
                        attrRegex.findAll(inf).forEach { attrs[it.groupValues[1].lowercase()] = it.groupValues[2] }
                        val lastQuote = inf.lastIndexOf('"')
                        val comma = inf.indexOf(',', if (lastQuote >= 0) lastQuote else 0)
                        val title = (if (comma >= 0) inf.substring(comma + 1).trim() else "").ifBlank { attrs["tvg-name"].orEmpty() }
                        val group = attrs["group-title"]?.trim()?.ifBlank { null } ?: "Sem categoria"
                        val logo = attrs["tvg-logo"]?.takeIf { it.startsWith("http") }
                        val url = line
                        val lower = url.lowercase().substringBefore('?')
                        val ep = TitleCleaner.episode(title)
                        val isSeries = "/series/" in lower || (ep != null && (vodExtensions.any { lower.endsWith(it) } || "/movie/" !in lower))
                        val isMovie = !isSeries && ("/movie/" in lower || vodExtensions.any { lower.endsWith(it) })
                        if (isSeries) {
                            val catId = prefix + "s:" + group
                            seriesCats.getOrPut(catId) { VodCategory(catId, group) }
                            val showName = ep?.first ?: title
                            shows.getOrPut(group) { LinkedHashMap() }
                                .getOrPut(showName.lowercase()) { ArrayList() }
                                .add(
                                    VodEpisode(
                                        season = ep?.second ?: 1,
                                        number = ep?.third ?: 0,
                                        title = title,
                                        streamUrl = url,
                                        still = logo,
                                    ) to logo
                                )
                        } else if (isMovie) {
                            val catId = prefix + "m:" + group
                            movieCats.getOrPut(catId) { VodCategory(catId, group) }
                            movies += VodMovie(
                                key = "m3u:${src.id}:${url.hashCode()}",
                                name = cleanDisplayName(title),
                                poster = logo,
                                categoryId = catId,
                                streamUrl = url,
                                year = TitleCleaner.year(title),
                            )
                        }
                    }
                }
            }
        }
        tmp.delete()
        val series = shows.flatMap { (group, byShow) ->
            byShow.values.map { eps ->
                var n = 0
                val episodes = eps.map { (e, _) -> n++; if (e.number == 0) e.copy(number = n) else e }
                    .sortedWith(compareBy({ it.season }, { it.number }))
                val first = eps.first().first
                val showName = TitleCleaner.episode(first.title)?.first ?: first.title
                VodSeries(
                    key = "m3u:${src.id}:s:${group.hashCode()}:${showName.lowercase().hashCode()}",
                    name = cleanDisplayName(showName),
                    poster = eps.firstNotNullOfOrNull { it.second },
                    categoryId = prefix + "s:" + group,
                    sourceId = src.id,
                    episodes = episodes,
                )
            }
        }
        return VodCatalog(movieCats.values.toList(), movies, seriesCats.values.toList(), series)
    }

    // ---------------------------------------------------------------------------------------
    // helpers
    // ---------------------------------------------------------------------------------------

    private fun fetch(url: String): String {
        val req = Request.Builder().url(url).header("User-Agent", USER_AGENT).build()
        http.newCall(req).execute().use { res ->
            if (!res.isSuccessful) throw IOException("HTTP ${res.code}")
            return res.body?.string().orEmpty()
        }
    }

    private fun jsonArray(url: String): JSONArray {
        val body = fetch(url).trim()
        return when {
            body.startsWith("[") -> JSONArray(body)
            body.startsWith("{") -> JSONObject(body).let { o ->
                // Some panels answer with an object keyed by id instead of an array.
                JSONArray().also { arr -> o.keys().forEach { k -> o.optJSONObject(k)?.let(arr::put) } }
            }
            else -> JSONArray()
        }
    }

    private fun jsonObject(url: String): JSONObject {
        val body = fetch(url).trim()
        return if (body.startsWith("{")) JSONObject(body) else JSONObject()
    }

    private inline fun <T> JSONArray.mapObjects(block: (JSONObject) -> T?): List<T> {
        val out = ArrayList<T>(length())
        for (i in 0 until length()) {
            val o = optJSONObject(i) ?: continue
            block(o)?.let(out::add)
        }
        return out
    }

    private fun cleanDisplayName(raw: String): String = TitleCleaner.display(raw)
}
