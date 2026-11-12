package com.arflix.tv.orbita.vod

import android.content.Context
import com.arflix.tv.util.Constants
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit

/**
 * Orbita Play: TMDB lookups used to organise the IPTV catalogue (poster, backdrop, synopsis,
 * rating, genres) in Portuguese.
 *
 * Key: the one typed in Ajustes (stored in "orbita" prefs) or TMDB_API_KEY from the build.
 * Accepts both the v3 "API key" and the v4 "read access token".
 */
object OrbitaTmdb {
    private const val PREFS = "orbita"
    private const val KEY_PREF = "tmdb_key"
    private const val IMG = "https://image.tmdb.org/t/p/"

    private val http by lazy {
        OkHttpClient.Builder().connectTimeout(10, TimeUnit.SECONDS).readTimeout(15, TimeUnit.SECONDS).build()
    }
    private val cache = ConcurrentHashMap<String, TmdbInfo>()
    private val misses = ConcurrentHashMap.newKeySet<String>()
    private val gate = Semaphore(4)

    fun savedKey(context: Context): String =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(KEY_PREF, "").orEmpty()

    fun saveKey(context: Context, key: String) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putString(KEY_PREF, key.trim()).apply()
        misses.clear()
    }

    private fun key(context: Context): String = savedKey(context).ifBlank { Constants.TMDB_API_KEY }

    fun isConfigured(context: Context): Boolean = key(context).isNotBlank()

    fun cached(kind: VodKind, name: String, year: Int?, tmdbId: Int?): TmdbInfo? =
        cache[cacheKey(kind, name, year, tmdbId)]

    private fun cacheKey(kind: VodKind, name: String, year: Int?, tmdbId: Int?) =
        if (tmdbId != null) "$kind:id:$tmdbId" else "$kind:${TitleCleaner.normalize(TitleCleaner.query(name))}:${year ?: ""}"

    /** Finds a title on TMDB (by id when the list has it, otherwise by name and year). */
    suspend fun lookup(context: Context, kind: VodKind, name: String, year: Int?, tmdbId: Int?): TmdbInfo? {
        val k = cacheKey(kind, name, year, tmdbId)
        cache[k]?.let { return it }
        if (k in misses) return null
        val apiKey = key(context)
        if (apiKey.isBlank()) return null
        return gate.withPermit {
            withContext(Dispatchers.IO) {
                val result = runCatching {
                    val type = if (kind == VodKind.MOVIES) "movie" else "tv"
                    val id = tmdbId ?: search(apiKey, type, TitleCleaner.query(name), year)
                    id?.let { details(apiKey, type, it) }
                }.getOrNull()
                if (result != null) cache[k] = result else misses += k
                result
            }
        }
    }

    private fun search(apiKey: String, type: String, query: String, year: Int?): Int? {
        if (query.isBlank()) return null
        val yearParam = when {
            year == null -> ""
            type == "movie" -> "&year=$year"
            else -> "&first_air_date_year=$year"
        }
        val q = java.net.URLEncoder.encode(query, "UTF-8")
        var json = get(apiKey, "search/$type?query=$q&language=pt-BR&include_adult=false$yearParam")
        var results = json.optJSONArray("results")
        if ((results == null || results.length() == 0) && yearParam.isNotEmpty()) {
            json = get(apiKey, "search/$type?query=$q&language=pt-BR&include_adult=false")
            results = json.optJSONArray("results")
        }
        return results?.optJSONObject(0)?.optInt("id")?.takeIf { it > 0 }
    }

    private fun details(apiKey: String, type: String, id: Int): TmdbInfo? {
        val o = get(apiKey, "$type/$id?language=pt-BR")
        if (!o.has("id")) return null
        val genres = buildList {
            val arr = o.optJSONArray("genres")
            if (arr != null) for (i in 0 until arr.length()) arr.optJSONObject(i)?.optString("name")?.let(::add)
        }
        val date = o.optString(if (type == "movie") "release_date" else "first_air_date")
        val runtime = if (type == "movie") o.optInt("runtime") else o.optJSONArray("episode_run_time")?.optInt(0) ?: 0
        return TmdbInfo(
            tmdbId = id,
            title = o.optString(if (type == "movie") "title" else "name"),
            overview = o.optString("overview").takeIf { it.isNotBlank() },
            posterUrl = o.optString("poster_path").takeIf { it.startsWith("/") }?.let { IMG + "w500" + it },
            backdropUrl = o.optString("backdrop_path").takeIf { it.startsWith("/") }?.let { IMG + "w1280" + it },
            rating = o.optDouble("vote_average").takeIf { !it.isNaN() && it > 0 },
            year = date.take(4).toIntOrNull(),
            genres = genres,
            runtimeMin = runtime.takeIf { it > 0 },
        )
    }

    private fun get(apiKey: String, path: String): JSONObject {
        val isToken = apiKey.length > 40
        val url = "https://api.themoviedb.org/3/$path" + if (isToken) "" else "&api_key=$apiKey"
        val fixedUrl = if (!url.contains('?')) url.replaceFirst('&', '?') else url
        val req = Request.Builder().url(fixedUrl).apply {
            if (isToken) header("Authorization", "Bearer $apiKey")
            header("Accept", "application/json")
        }.build()
        http.newCall(req).execute().use { res ->
            if (!res.isSuccessful) return JSONObject()
            return JSONObject(res.body?.string().orEmpty().ifBlank { "{}" })
        }
    }
}
