package com.arflix.tv.orbita.vod

/** Filmes or Séries. */
enum class VodKind { MOVIES, SERIES }

data class VodCategory(val id: String, val name: String)

/** A movie from the user's IPTV list. */
data class VodMovie(
    val key: String,
    val name: String,
    val poster: String?,
    val categoryId: String,
    val streamUrl: String,
    val year: Int? = null,
    val rating: Double? = null,
    val tmdbId: Int? = null,
    val plot: String? = null,
    val addedEpochSec: Long = 0,
)

/** A series from the user's IPTV list. Episodes come from [episodes] (M3U) or are fetched on demand (Xtream). */
data class VodSeries(
    val key: String,
    val name: String,
    val poster: String?,
    val categoryId: String,
    val year: Int? = null,
    val rating: Double? = null,
    val tmdbId: Int? = null,
    val plot: String? = null,
    val backdrop: String? = null,
    val xtreamSeriesId: String? = null,
    val sourceId: String,
    val episodes: List<VodEpisode> = emptyList(),
    val addedEpochSec: Long = 0,
)

data class VodEpisode(
    val season: Int,
    val number: Int,
    val title: String,
    val streamUrl: String,
    val plot: String? = null,
    val still: String? = null,
    val durationSec: Int? = null,
)

/** Everything loaded from the user's lists. */
data class VodCatalog(
    val movieCategories: List<VodCategory> = emptyList(),
    val movies: List<VodMovie> = emptyList(),
    val seriesCategories: List<VodCategory> = emptyList(),
    val series: List<VodSeries> = emptyList(),
) {
    val isEmpty: Boolean get() = movies.isEmpty() && series.isEmpty()
}

/** Extra information from TMDB, used to organise and decorate the catalogue. */
data class TmdbInfo(
    val tmdbId: Int,
    val title: String,
    val overview: String?,
    val posterUrl: String?,
    val backdropUrl: String?,
    val rating: Double?,
    val year: Int?,
    val genres: List<String>,
    val runtimeMin: Int? = null,
)
