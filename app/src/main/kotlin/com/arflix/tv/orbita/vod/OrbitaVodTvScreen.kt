@file:kotlin.OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)

package com.arflix.tv.orbita.vod

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.itemsIndexed
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import coil.compose.AsyncImage
import com.arflix.tv.data.model.Profile
import com.arflix.tv.ui.components.AppTopBarContentTopInset
import com.arflix.tv.ui.components.SidebarItem
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

internal object OC {
    val Accent = Color(0xFF7B5CFF)
    val Cyan = Color(0xFF22D3EE)
    val Panel = Color(0xE012111F)
    val PanelBorder = Color(0xFF23203D)
    val Chip = Color(0xFF1C1A30)
    val Text = Color(0xFFF4F3FF)
    val TextDim = Color(0xFFC9C7E4)
    val TextMute = Color(0xFF8E8BB0)
    val TextFaint = Color(0xFF7C79A0)
    val Star = Color(0xFFF7C948)
    val Bg = Color(0xFF07070F)
}

private const val CAT_ALL = "__all"
private const val CAT_RECENT = "__recent"
private const val CAT_CONTINUE = "__continue"

/** One poster on the grid, movie or series. */
private data class GridItem(
    val key: String,
    val name: String,
    val poster: String?,
    val year: Int?,
    val rating: Double?,
    val tmdbId: Int?,
    val plot: String?,
    val movie: VodMovie? = null,
    val series: VodSeries? = null,
)

/**
 * Orbita Play "Filmes" / "Séries" for TV: categories from the user's list on the left,
 * the focused title on top (TMDB backdrop, rating, synopsis) and a poster grid below.
 */
@Composable
fun OrbitaVodTvScreen(
    kind: VodKind,
    currentProfile: Profile?,
    onMenu: (SidebarItem?) -> Unit,
    onOpenIptvSettings: () -> Unit,
    viewModel: OrbitaVodViewModel = hiltViewModel(),
) {
    val context = LocalContext.current
    val state by viewModel.state.collectAsStateWithLifecycle()
    val tmdb by viewModel.tmdb.collectAsStateWithLifecycle()
    val catalog = state.catalog
    val scope = rememberCoroutineScope()

    val baseCategories = if (kind == VodKind.MOVIES) catalog.movieCategories else catalog.seriesCategories
    val allItems: List<GridItem> = remember(catalog, kind) {
        if (kind == VodKind.MOVIES) catalog.movies.map {
            GridItem(it.key, it.name, it.poster, it.year, it.rating, it.tmdbId, it.plot, movie = it)
        } else catalog.series.map {
            GridItem(it.key, it.name, it.poster, it.year, it.rating, it.tmdbId, it.plot, series = it)
        }
    }
    // Refresh "continuar assistindo" whenever we come back from the player.
    var resumeTick by remember { mutableStateOf(0) }
    val lifecycleOwner = androidx.compose.ui.platform.LocalLifecycleOwner.current
    androidx.compose.runtime.DisposableEffect(lifecycleOwner) {
        val obs = androidx.lifecycle.LifecycleEventObserver { _, e ->
            if (e == androidx.lifecycle.Lifecycle.Event.ON_RESUME) resumeTick++
        }
        lifecycleOwner.lifecycle.addObserver(obs)
        onDispose { lifecycleOwner.lifecycle.removeObserver(obs) }
    }
    val continueItems = remember(allItems, resumeTick) {
        if (kind == VodKind.MOVIES) allItems.filter { m ->
            val f = viewModel.progressFraction(m.movie!!.streamUrl); f > 0.02f && f < 1f
        }.take(40) else emptyList()
    }
    val categories = remember(baseCategories, allItems, continueItems) {
        buildList {
            if (continueItems.isNotEmpty()) add(VodCategory(CAT_CONTINUE, "Continuar assistindo"))
            add(VodCategory(CAT_RECENT, "Adicionados recentemente"))
            add(VodCategory(CAT_ALL, if (kind == VodKind.MOVIES) "Todos os filmes" else "Todas as séries"))
            val used = allItems.mapTo(HashSet()) { it.movie?.categoryId ?: it.series?.categoryId }
            addAll(baseCategories.filter { it.id in used })
        }
    }
    val counts = remember(allItems, continueItems) {
        val m = HashMap<String, Int>()
        allItems.forEach { i -> val c = i.movie?.categoryId ?: i.series?.categoryId ?: ""; m[c] = (m[c] ?: 0) + 1 }
        m[CAT_ALL] = allItems.size
        m[CAT_RECENT] = minOf(allItems.size, 200)
        m[CAT_CONTINUE] = continueItems.size
        m
    }
    var selectedCat by rememberSaveable { mutableStateOf(CAT_RECENT) }
    val items = remember(selectedCat, allItems, continueItems) {
        when (selectedCat) {
            CAT_ALL -> allItems.sortedBy { it.name.lowercase() }
            CAT_RECENT -> allItems.sortedByDescending { it.movie?.addedEpochSec ?: it.series?.addedEpochSec ?: 0 }.take(200)
            CAT_CONTINUE -> continueItems
            else -> allItems.filter { (it.movie?.categoryId ?: it.series?.categoryId) == selectedCat }
        }
    }

    var focusedKey by remember { mutableStateOf<String?>(null) }
    val focused = items.firstOrNull { it.key == focusedKey } ?: items.firstOrNull()
    var openMovie by remember { mutableStateOf<VodMovie?>(null) }
    var openSeries by remember { mutableStateOf<VodSeries?>(null) }

    val menuFocus = remember { FocusRequester() }
    val catFocus = remember { FocusRequester() }
    val gridFocus = remember { FocusRequester() }
    var didInitialFocus by remember { mutableStateOf(false) }
    LaunchedEffect(items.isNotEmpty(), openMovie, openSeries) {
        if (openMovie != null || openSeries != null) return@LaunchedEffect
        delay(150)
        val ok = if (items.isNotEmpty()) runCatching { gridFocus.requestFocus() }.isSuccess
        else runCatching { catFocus.requestFocus() }.isSuccess
        if (ok) didInitialFocus = true
    }

    val menuItem = if (kind == VodKind.MOVIES) SidebarItem.HOME else SidebarItem.SERIES
    var tmdbDialog by remember { mutableStateOf(false) }
    var tmdbReady by remember { mutableStateOf(viewModel.tmdbConfigured) }

    Box(Modifier.fillMaxSize().background(OC.Bg)) {
        // Backdrop of the focused title
        val info = focused?.let { tmdb[it.key] }
        val backdrop = info?.backdropUrl ?: focused?.series?.backdrop
        if (backdrop != null) {
            AsyncImage(
                model = backdrop, contentDescription = null, contentScale = ContentScale.Crop,
                modifier = Modifier.align(Alignment.TopEnd).fillMaxWidth(0.62f).height(330.dp),
            )
        }
        Box(
            Modifier.fillMaxSize().background(
                Brush.horizontalGradient(
                    0f to OC.Bg, 0.38f to OC.Bg.copy(alpha = 0.92f), 0.7f to OC.Bg.copy(alpha = 0.35f), 1f to OC.Bg.copy(alpha = 0.2f)
                )
            )
        )
        Box(Modifier.fillMaxSize().background(Brush.verticalGradient(0f to Color.Transparent, 0.42f to OC.Bg.copy(alpha = 0.5f), 0.62f to OC.Bg)))
        Box(
            Modifier.fillMaxSize().background(
                Brush.radialGradient(listOf(Color(0x551D1550), Color.Transparent), center = androidx.compose.ui.geometry.Offset(200f, 0f), radius = 1300f)
            )
        )

        val detailOpen = openMovie != null || openSeries != null
        if (!detailOpen) Row(
            Modifier.fillMaxSize().padding(start = 32.dp, end = 32.dp, top = AppTopBarContentTopInset - 24.dp, bottom = 16.dp),
            horizontalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            // ---- categories
            Column(
                Modifier.width(230.dp).fillMaxHeight().clip(RoundedCornerShape(16.dp))
                    .background(OC.Panel).border(1.dp, OC.PanelBorder, RoundedCornerShape(16.dp))
            ) {
                Text(
                    "CATEGORIAS", color = OC.TextMute, fontSize = 11.sp, fontWeight = FontWeight.SemiBold, letterSpacing = 1.5.sp,
                    modifier = Modifier.padding(start = 18.dp, top = 14.dp, bottom = 8.dp),
                )
                val listState = rememberLazyListState()
                LazyColumn(state = listState, modifier = Modifier.fillMaxSize()) {
                    itemsIndexed(categories, key = { _, c -> c.id }) { index, cat ->
                        VodCategoryRow(
                            name = cat.name,
                            count = counts[cat.id] ?: 0,
                            selected = cat.id == selectedCat,
                            modifier = if (cat.id == selectedCat) Modifier.focusRequester(catFocus) else Modifier,
                            onFocused = { selectedCat = cat.id },
                            onClick = { selectedCat = cat.id; scope.launch { delay(80); runCatching { gridFocus.requestFocus() } } },
                            onUp = if (index == 0) ({ runCatching { menuFocus.requestFocus() } }) else null,
                        )
                    }
                }
            }

            // ---- focused title + grid
            Column(Modifier.weight(1f).fillMaxHeight()) {
                Column(Modifier.fillMaxWidth().height(150.dp).padding(start = 4.dp, top = 6.dp)) {
                    if (!tmdbReady) {
                        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(bottom = 6.dp)) {
                            Text("Capas e sinopses do TMDB desligadas", color = OC.TextMute, fontSize = 12.sp, modifier = Modifier.padding(end = 10.dp))
                            ActionButton("Adicionar chave TMDB") { tmdbDialog = true }
                        }
                    }
                    if (focused != null) {
                        Text(
                            info?.title?.takeIf { it.isNotBlank() } ?: focused.name, color = Color.White, fontSize = 26.sp,
                            fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis,
                        )
                        Row(Modifier.padding(top = 6.dp), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                            (info?.year ?: focused.year)?.let { Chip("$it") }
                            info?.runtimeMin?.let { Chip(formatRuntime(it)) }
                            (info?.rating ?: focused.rating)?.let { Chip("★ %.1f".format(it).replace('.', ','), OC.Star) }
                            info?.genres?.take(3)?.joinToString(" · ")?.takeIf { it.isNotBlank() }?.let {
                                Text(it, color = OC.TextMute, fontSize = 12.sp, maxLines = 1)
                            }
                        }
                        Text(
                            info?.overview ?: focused.plot ?: "",
                            color = OC.TextDim, fontSize = 13.sp, lineHeight = 18.sp, maxLines = 3, overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.padding(top = 8.dp).fillMaxWidth(0.62f),
                        )
                    }
                }
                when {
                    !state.hasSources -> CenterMessage("Nenhuma lista configurada", "Adicione sua lista em Ajustes › TV ao vivo.", "Abrir ajustes", onOpenIptvSettings)
                    state.loading && allItems.isEmpty() -> CenterMessage(
                        if (kind == VodKind.MOVIES) "Carregando filmes da sua lista…" else "Carregando séries da sua lista…", null,
                    )
                    state.error != null && allItems.isEmpty() -> CenterMessage("Não foi possível carregar", state.error, "Tentar de novo") { viewModel.load(force = true) }
                    items.isEmpty() -> CenterMessage(
                        if (kind == VodKind.MOVIES) "Nenhum filme nesta categoria" else "Nenhuma série nesta categoria", null,
                    )
                    else -> LazyVerticalGrid(
                        columns = GridCells.Fixed(6),
                        modifier = Modifier.fillMaxSize(),
                        horizontalArrangement = Arrangement.spacedBy(14.dp),
                        verticalArrangement = Arrangement.spacedBy(14.dp),
                        contentPadding = androidx.compose.foundation.layout.PaddingValues(top = 6.dp, bottom = 24.dp, start = 4.dp, end = 4.dp),
                    ) {
                        itemsIndexed(items, key = { _, it -> it.key }) { index, item ->
                            LaunchedEffect(item.key) {
                                viewModel.requestTmdb(kind, item.key, item.name, item.year, item.tmdbId)
                            }
                            val t = tmdb[item.key]
                            PosterCard(
                                title = item.name,
                                poster = item.poster ?: t?.posterUrl,
                                fallbackPoster = t?.posterUrl,
                                progress = item.movie?.let { viewModel.progressFraction(it.streamUrl) } ?: 0f,
                                modifier = if (index == 0) Modifier.focusRequester(gridFocus) else Modifier,
                                onFocused = { focusedKey = item.key },
                                onClick = {
                                    if (item.movie != null) openMovie = item.movie else openSeries = item.series
                                },
                                onUp = if (index < 6) ({ runCatching { menuFocus.requestFocus() } }) else null,
                            )
                        }
                    }
                }
            }
        }

        if (!detailOpen) OrbitaTopMenu(
            selected = menuItem,
            profile = currentProfile,
            focusRequester = menuFocus,
            onDown = { runCatching { if (items.isNotEmpty()) gridFocus.requestFocus() else catFocus.requestFocus() } },
            onOpen = onMenu,
        )

        if (tmdbDialog) {
            TmdbKeyDialog(
                initial = OrbitaTmdb.savedKey(context),
                onDismiss = { tmdbDialog = false },
                onSave = { key ->
                    OrbitaTmdb.saveKey(context, key)
                    tmdbDialog = false
                    tmdbReady = viewModel.tmdbConfigured
                    viewModel.resetTmdb()
                },
            )
        }

        openMovie?.let { movie ->
            BackHandler { openMovie = null }
            MovieDetail(movie, tmdb[movie.key], viewModel, onClose = { openMovie = null })
        }
        openSeries?.let { series ->
            BackHandler { openSeries = null }
            LaunchedEffect(series.key) { viewModel.requestTmdb(VodKind.SERIES, series.key, series.name, series.year, series.tmdbId) }
            SeriesDetail(series, tmdb[series.key], viewModel)
        }
    }
}

// ---------------------------------------------------------------------------------------------

@Composable
private fun MovieDetail(movie: VodMovie, info: TmdbInfo?, vm: OrbitaVodViewModel, onClose: () -> Unit) {
    val context = LocalContext.current
    var tick by remember { mutableStateOf(0) }
    val resumeMs = remember(movie.key, tick) { vm.resumePositionMs(movie.streamUrl) }
    val playFocus = remember { FocusRequester() }
    LaunchedEffect(movie.key) { delay(120); runCatching { playFocus.requestFocus() } }
    DetailBackground(info?.backdropUrl ?: info?.posterUrl ?: movie.poster)
    Column(Modifier.fillMaxSize().padding(start = 48.dp, top = 60.dp, end = 48.dp)) {
        Text("FILME", color = OC.Cyan, fontSize = 12.sp, fontWeight = FontWeight.SemiBold, letterSpacing = 2.sp)
        Text(
            info?.title?.takeIf { it.isNotBlank() } ?: movie.name, color = Color.White, fontSize = 38.sp, fontWeight = FontWeight.Bold,
            lineHeight = 42.sp, maxLines = 2, overflow = TextOverflow.Ellipsis, modifier = Modifier.fillMaxWidth(0.6f).padding(top = 6.dp),
        )
        Row(Modifier.padding(top = 12.dp), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
            (info?.year ?: movie.year)?.let { Chip("$it") }
            info?.runtimeMin?.let { Chip(formatRuntime(it)) }
            (info?.rating ?: movie.rating)?.let { Chip("★ %.1f".format(it).replace('.', ','), OC.Star) }
            info?.genres?.take(3)?.joinToString(" · ")?.let { Text(it, color = OC.TextMute, fontSize = 13.sp) }
        }
        Text(
            info?.overview ?: movie.plot ?: "Sem sinopse.", color = OC.TextDim, fontSize = 15.sp, lineHeight = 22.sp,
            maxLines = 5, overflow = TextOverflow.Ellipsis, modifier = Modifier.fillMaxWidth(0.55f).padding(top = 14.dp),
        )
        Row(Modifier.padding(top = 22.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            val item = OrbitaPlayItem(info?.title?.takeIf { it.isNotBlank() } ?: movie.name, movie.streamUrl)
            ActionButton(
                if (resumeMs > 0) "▶  Continuar de ${formatClock(resumeMs)}" else "▶  Assistir",
                primary = true, modifier = Modifier.focusRequester(playFocus),
            ) { OrbitaVodPlayerActivity.start(context, listOf(item), 0, resume = true); tick++ }
            if (resumeMs > 0) ActionButton("Do início") { OrbitaVodPlayerActivity.start(context, listOf(item), 0, resume = false); tick++ }
            ActionButton("Voltar") { onClose() }
        }
    }
}

@Composable
private fun SeriesDetail(series: VodSeries, info: TmdbInfo?, vm: OrbitaVodViewModel) {
    val context = LocalContext.current
    val episodes by produceState<List<VodEpisode>?>(null, series.key) { value = vm.episodes(series) }
    val seasons = remember(episodes) { episodes.orEmpty().map { it.season }.distinct().sorted() }
    var season by rememberSaveable(series.key) { mutableStateOf<Int?>(null) }
    val currentSeason = season ?: seasons.firstOrNull()
    val seasonEpisodes = remember(episodes, currentSeason) { episodes.orEmpty().filter { it.season == currentSeason } }
    var tick by remember { mutableStateOf(0) }
    val firstEpFocus = remember { FocusRequester() }
    LaunchedEffect(series.key, episodes != null) { delay(150); runCatching { firstEpFocus.requestFocus() } }

    DetailBackground(info?.backdropUrl ?: series.backdrop ?: info?.posterUrl ?: series.poster)
    Row(Modifier.fillMaxSize().padding(start = 40.dp, top = 52.dp, end = 32.dp, bottom = 20.dp), horizontalArrangement = Arrangement.spacedBy(28.dp)) {
        Column(Modifier.width(330.dp)) {
            Text(
                "SÉRIE" + if (seasons.isNotEmpty()) " · ${seasons.size} TEMPORADA${if (seasons.size > 1) "S" else ""}" else "",
                color = OC.Cyan, fontSize = 12.sp, fontWeight = FontWeight.SemiBold, letterSpacing = 2.sp,
            )
            Text(
                info?.title?.takeIf { it.isNotBlank() } ?: series.name, color = Color.White, fontSize = 34.sp, fontWeight = FontWeight.Bold,
                lineHeight = 38.sp, maxLines = 3, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(top = 6.dp),
            )
            Row(Modifier.padding(top = 10.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                (info?.year ?: series.year)?.let { Chip("$it") }
                (info?.rating ?: series.rating)?.let { Chip("★ %.1f".format(it).replace('.', ','), OC.Star) }
            }
            info?.genres?.take(3)?.joinToString(" · ")?.let { Text(it, color = OC.TextMute, fontSize = 13.sp, modifier = Modifier.padding(top = 8.dp)) }
            Text(
                info?.overview ?: series.plot ?: "", color = OC.TextDim, fontSize = 14.sp, lineHeight = 21.sp,
                maxLines = 8, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(top = 12.dp),
            )
        }
        Column(
            Modifier.weight(1f).fillMaxHeight().clip(RoundedCornerShape(16.dp)).background(OC.Panel)
                .border(1.dp, OC.PanelBorder, RoundedCornerShape(16.dp)).padding(12.dp)
        ) {
            if (episodes == null) {
                CenterMessage("Carregando episódios…", null)
                return@Column
            }
            if (episodes!!.isEmpty()) {
                CenterMessage("Nenhum episódio encontrado", null)
                return@Column
            }
            LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.padding(bottom = 10.dp)) {
                itemsIndexed(seasons) { _, s ->
                    SeasonTab("Temporada $s", s == currentSeason) { season = s }
                }
            }
            val queue = seasonEpisodes.map {
                OrbitaPlayItem(
                    "${info?.title?.takeIf { t -> t.isNotBlank() } ?: series.name} · T${it.season} E${it.number}", it.streamUrl, it.title,
                )
            }
            LazyColumn(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                itemsIndexed(seasonEpisodes, key = { _, e -> e.streamUrl }) { i, ep ->
                    EpisodeRow(
                        ep = ep,
                        progress = remember(ep.streamUrl, tick) { vm.progressFraction(ep.streamUrl) },
                        fallbackStill = info?.backdropUrl ?: series.poster,
                        modifier = if (i == 0) Modifier.focusRequester(firstEpFocus) else Modifier,
                    ) { OrbitaVodPlayerActivity.start(context, queue, i, resume = true); tick++ }
                }
            }
        }
    }
}

// ---------------------------------------------------------------------------------------------

@Composable
private fun DetailBackground(image: String?) {
    Box(Modifier.fillMaxSize().background(OC.Bg)) {
        if (image != null) {
            AsyncImage(image, null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
        }
        Box(Modifier.fillMaxSize().background(Brush.horizontalGradient(0f to OC.Bg, 0.45f to OC.Bg.copy(alpha = 0.88f), 1f to OC.Bg.copy(alpha = 0.35f))))
        Box(Modifier.fillMaxSize().background(Brush.verticalGradient(0.5f to Color.Transparent, 1f to OC.Bg)))
    }
}

@Composable
private fun VodCategoryRow(
    name: String, count: Int, selected: Boolean, modifier: Modifier,
    onFocused: () -> Unit, onClick: () -> Unit, onUp: (() -> Unit)?,
) {
    val interaction = remember { MutableInteractionSource() }
    val focused by interaction.collectIsFocusedAsState()
    LaunchedEffect(focused) { if (focused) { delay(250); onFocused() } }
    Row(
        modifier.fillMaxWidth()
            .onPreviewKeyEvent { ev -> if (onUp != null && ev.type == KeyEventType.KeyDown && ev.key == Key.DirectionUp) { onUp(); true } else false }
            .background(when { focused -> OC.Accent.copy(alpha = 0.35f); selected -> OC.Accent.copy(alpha = 0.18f); else -> Color.Transparent })
            .combinedClickable(interactionSource = interaction, indication = null, onClick = onClick)
            .padding(end = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.width(3.dp).height(40.dp).background(if (selected || focused) OC.Accent else Color.Transparent))
        Text(
            name, color = if (selected || focused) Color.White else OC.TextDim, fontSize = 14.sp,
            fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal, maxLines = 1, overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f).padding(start = 14.dp),
        )
        Text(
            "$count", color = if (selected) Color.White else OC.TextFaint, fontSize = 11.sp,
            modifier = Modifier.clip(RoundedCornerShape(8.dp)).background(if (selected) OC.Accent else OC.Chip).padding(horizontal = 7.dp, vertical = 1.dp),
        )
    }
}

@Composable
private fun PosterCard(
    title: String, poster: String?, fallbackPoster: String?, progress: Float, modifier: Modifier,
    onFocused: () -> Unit, onClick: () -> Unit, onUp: (() -> Unit)?,
) {
    val interaction = remember { MutableInteractionSource() }
    val focused by interaction.collectIsFocusedAsState()
    LaunchedEffect(focused) { if (focused) onFocused() }
    val shape = RoundedCornerShape(10.dp)
    Box(
        modifier
            .fillMaxWidth()
            .aspectRatio(2f / 3f)
            .scale(if (focused) 1.07f else 1f)
            .clip(shape)
            .border(if (focused) 3.dp else 0.dp, if (focused) OC.Accent else Color.Transparent, shape)
            .background(Brush.verticalGradient(listOf(Color(0xFF1B1440), Color(0xFF0A0916))))
            .onPreviewKeyEvent { ev -> if (onUp != null && ev.type == KeyEventType.KeyDown && ev.key == Key.DirectionUp) { onUp(); true } else false }
            .combinedClickable(interactionSource = interaction, indication = null, onClick = onClick),
    ) {
        Text(
            title, color = OC.TextDim, fontSize = 12.sp, fontWeight = FontWeight.SemiBold, maxLines = 3,
            modifier = Modifier.align(Alignment.Center).padding(10.dp),
        )
        val img = poster ?: fallbackPoster
        if (img != null) {
            AsyncImage(img, null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
        }
        if (focused) {
            Box(
                Modifier.align(Alignment.BottomCenter).fillMaxWidth()
                    .background(Brush.verticalGradient(listOf(Color.Transparent, Color(0xE6000000))))
                    .padding(start = 8.dp, end = 8.dp, top = 22.dp, bottom = 8.dp)
            ) { Text(title, color = Color.White, fontSize = 12.sp, fontWeight = FontWeight.Bold, maxLines = 2, overflow = TextOverflow.Ellipsis) }
        }
        if (progress > 0.02f) {
            Box(Modifier.align(Alignment.BottomStart).fillMaxWidth().height(4.dp).background(Color.White.copy(alpha = 0.25f))) {
                Box(Modifier.fillMaxWidth(progress.coerceAtMost(1f)).fillMaxHeight().background(OC.Accent))
            }
        }
    }
}

@Composable
private fun EpisodeRow(ep: VodEpisode, progress: Float, fallbackStill: String?, modifier: Modifier, onClick: () -> Unit) {
    val interaction = remember { MutableInteractionSource() }
    val focused by interaction.collectIsFocusedAsState()
    val shape = RoundedCornerShape(12.dp)
    Row(
        modifier.fillMaxWidth().clip(shape)
            .background(if (focused) OC.Accent.copy(alpha = 0.25f) else Color.Transparent)
            .border(if (focused) 2.dp else 0.dp, if (focused) OC.Accent else Color.Transparent, shape)
            .combinedClickable(interactionSource = interaction, indication = null, onClick = onClick)
            .padding(8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.width(150.dp).aspectRatio(16f / 9f).clip(RoundedCornerShape(8.dp)).background(Color(0xFF1B1440))) {
            (ep.still ?: fallbackStill)?.let { AsyncImage(it, null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize()) }
            Text(
                "${ep.number}", color = Color.White, fontSize = 11.sp, fontWeight = FontWeight.Bold,
                modifier = Modifier.padding(6.dp).clip(RoundedCornerShape(4.dp)).background(Color(0x99000000)).padding(horizontal = 6.dp, vertical = 1.dp),
            )
            if (progress > 0.02f) {
                Box(Modifier.align(Alignment.BottomStart).fillMaxWidth().height(3.dp).background(Color.White.copy(alpha = 0.25f))) {
                    Box(Modifier.fillMaxWidth(progress).fillMaxHeight().background(OC.Accent))
                }
            }
        }
        Column(Modifier.weight(1f).padding(start = 14.dp)) {
            Text(
                buildString {
                    append("Episódio ${ep.number}")
                    ep.durationSec?.takeIf { it > 0 }?.let { append(" · ${it / 60} min") }
                    if (progress >= 1f) append(" · Assistido")
                },
                color = if (progress >= 1f) OC.Cyan else OC.TextMute, fontSize = 11.sp, fontWeight = FontWeight.SemiBold,
            )
            Text(ep.title, color = Color.White, fontSize = 15.sp, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
            ep.plot?.let { Text(it, color = OC.TextMute, fontSize = 12.sp, maxLines = 2, overflow = TextOverflow.Ellipsis) }
        }
    }
}

@Composable
private fun SeasonTab(label: String, selected: Boolean, onClick: () -> Unit) {
    val interaction = remember { MutableInteractionSource() }
    val focused by interaction.collectIsFocusedAsState()
    Text(
        label, color = if (selected || focused) Color.White else OC.TextMute, fontSize = 14.sp, fontWeight = FontWeight.SemiBold,
        modifier = Modifier.clip(RoundedCornerShape(10.dp))
            .background(when { selected -> OC.Accent; focused -> Color.White.copy(alpha = 0.18f); else -> Color.White.copy(alpha = 0.06f) })
            .combinedClickable(interactionSource = interaction, indication = null, onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 7.dp),
    )
}

@Composable
private fun ActionButton(label: String, primary: Boolean = false, modifier: Modifier = Modifier, onClick: () -> Unit) {
    val interaction = remember { MutableInteractionSource() }
    val focused by interaction.collectIsFocusedAsState()
    val shape = RoundedCornerShape(12.dp)
    Text(
        label, color = Color.White, fontSize = 15.sp, fontWeight = FontWeight.SemiBold,
        modifier = modifier.scale(if (focused) 1.05f else 1f).clip(shape)
            .background(when { primary -> OC.Accent; focused -> Color.White.copy(alpha = 0.22f); else -> Color.White.copy(alpha = 0.10f) })
            .border(if (focused) 2.dp else 0.dp, if (focused) Color.White else Color.Transparent, shape)
            .combinedClickable(interactionSource = interaction, indication = null, onClick = onClick)
            .padding(horizontal = 22.dp, vertical = 11.dp),
    )
}

@Composable
private fun Chip(text: String, color: Color = OC.TextDim) {
    Text(
        text, color = color, fontSize = 12.sp, fontWeight = FontWeight.SemiBold,
        modifier = Modifier.clip(RoundedCornerShape(6.dp)).background(Color.White.copy(alpha = 0.10f)).padding(horizontal = 8.dp, vertical = 2.dp),
    )
}

@Composable
private fun CenterMessage(title: String, subtitle: String?, action: String? = null, onAction: (() -> Unit)? = null) {
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text(title, color = OC.Text, fontSize = 16.sp, fontWeight = FontWeight.SemiBold)
            subtitle?.let { Text(it, color = OC.TextMute, fontSize = 13.sp, modifier = Modifier.padding(top = 6.dp)) }
            if (action != null && onAction != null) {
                Spacer(Modifier.height(14.dp))
                ActionButton(action, primary = true, onClick = onAction)
            }
        }
    }
}

private fun formatRuntime(min: Int): String = if (min >= 60) "${min / 60}h ${"%02d".format(min % 60)}min" else "$min min"

private fun formatClock(ms: Long): String {
    val s = ms / 1000
    return if (s >= 3600) "%d:%02d:%02d".format(s / 3600, (s % 3600) / 60, s % 60) else "%d:%02d".format(s / 60, s % 60)
}

@Composable
private fun TmdbKeyDialog(initial: String, onDismiss: () -> Unit, onSave: (String) -> Unit) {
    var text by remember { mutableStateOf(initial) }
    androidx.compose.material3.AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = Color(0xFF15132A),
        title = { Text("Chave da API do TMDB", color = Color.White, fontWeight = FontWeight.Bold) },
        text = {
            Column {
                Text(
                    "Cole a chave (API Key) ou o token de leitura da sua conta em themoviedb.org › Configurações › API.",
                    color = OC.TextDim, fontSize = 13.sp,
                )
                androidx.compose.material3.OutlinedTextField(
                    value = text, onValueChange = { text = it.trim() }, singleLine = true,
                    modifier = Modifier.fillMaxWidth().padding(top = 12.dp),
                    textStyle = androidx.compose.ui.text.TextStyle(color = Color.White, fontSize = 14.sp),
                    placeholder = { Text("ex.: 1a2b3c…", color = OC.TextFaint) },
                )
            }
        },
        confirmButton = { ActionButton("Salvar", primary = true) { onSave(text) } },
        dismissButton = { ActionButton("Cancelar") { onDismiss() } },
    )
}
