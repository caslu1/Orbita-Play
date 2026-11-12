package com.arflix.tv.orbita.vod

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.arflix.tv.data.repository.IptvRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class OrbitaVodViewModel @Inject constructor(
    @ApplicationContext private val context: Context,
    private val iptvRepository: IptvRepository,
) : ViewModel() {

    val state: StateFlow<VodLoadState> = OrbitaVodRepository.state

    /** TMDB info by item key, filled lazily for what is on screen. */
    private val _tmdb = MutableStateFlow<Map<String, TmdbInfo>>(emptyMap())
    val tmdb: StateFlow<Map<String, TmdbInfo>> = _tmdb.asStateFlow()
    private val requested = HashSet<String>()

    val tmdbConfigured: Boolean get() = OrbitaTmdb.isConfigured(context)

    init {
        load(force = false)
    }

    fun load(force: Boolean) {
        viewModelScope.launch { OrbitaVodRepository.ensureLoaded(context, iptvRepository, force) }
    }

    fun requestTmdb(kind: VodKind, key: String, name: String, year: Int?, tmdbId: Int?) {
        if (!tmdbConfigured) return
        synchronized(requested) { if (!requested.add(key)) return }
        OrbitaTmdb.cached(kind, name, year, tmdbId)?.let { info -> _tmdb.update { it + (key to info) }; return }
        viewModelScope.launch {
            OrbitaTmdb.lookup(context, kind, name, year, tmdbId)?.let { info -> _tmdb.update { it + (key to info) } }
        }
    }

    /** Called after the TMDB key changes, so every title is looked up again. */
    fun resetTmdb() {
        synchronized(requested) { requested.clear() }
        _tmdb.value = emptyMap()
    }

    suspend fun episodes(series: VodSeries): List<VodEpisode> = OrbitaVodRepository.episodes(series)

    fun resumePositionMs(url: String): Long = OrbitaWatchProgress.position(context, url)
    fun progressFraction(url: String): Float = OrbitaWatchProgress.fraction(context, url)
}
