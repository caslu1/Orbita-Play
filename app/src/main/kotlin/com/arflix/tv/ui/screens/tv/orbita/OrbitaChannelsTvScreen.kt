@file:kotlin.OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)

package com.arflix.tv.ui.screens.tv.orbita

import android.view.LayoutInflater
import androidx.annotation.OptIn
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.focusable
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
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
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
import androidx.compose.ui.focus.onFocusChanged
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
import androidx.compose.ui.viewinterop.AndroidView
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.media3.common.MediaItem
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DefaultHttpDataSource
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.ui.AspectRatioFrameLayout
import androidx.media3.ui.PlayerView
import coil.compose.AsyncImage
import com.arflix.tv.R
import com.arflix.tv.data.model.IptvChannel
import com.arflix.tv.data.model.IptvNowNext
import com.arflix.tv.data.model.IptvProgram
import com.arflix.tv.data.model.Profile
import com.arflix.tv.ui.components.AppTopBar
import com.arflix.tv.ui.components.AppTopBarContentTopInset
import com.arflix.tv.ui.components.OrbitaNav
import com.arflix.tv.ui.components.SidebarItem
import com.arflix.tv.ui.components.topBarFocusedItem
import com.arflix.tv.ui.components.topBarMaxIndex
import com.arflix.tv.ui.components.topBarSelectedIndex
import com.arflix.tv.ui.screens.tv.TvViewModel
import com.arflix.tv.ui.screens.tv.live.isOrbitaFootballLabel
import com.arflix.tv.ui.screens.tv.live.isOrbitaSportsLabel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** Orbita Play palette (same as the approved TV mockups). */
private object Orbita {
    val Accent = Color(0xFF7B5CFF)
    val Cyan = Color(0xFF22D3EE)
    val Live = Color(0xFFFF3B5C)
    val Panel = Color(0xE012111F)
    val PanelBorder = Color(0xFF23203D)
    val Chip = Color(0xFF1C1A30)
    val Text = Color(0xFFF4F3FF)
    val TextDim = Color(0xFFC9C7E4)
    val TextMute = Color(0xFF8E8BB0)
    val TextFaint = Color(0xFF7C79A0)
    val Star = Color(0xFFFFCC4D)
}

private const val CAT_FAVORITES = "__orbita_fav"
private const val CAT_ALL = "__orbita_all"

private data class OrbitaCategory(val id: String, val label: String, val count: Int)

/**
 * Orbita Play "Canais" and "Futebol" screen for TV (remote control).
 *
 * Three columns: categories | channels | preview player + programme guide.
 * OK on a channel plays it in the preview; OK again opens it full screen (ARVIO's live
 * player, through [onOpenFullscreen]). Holding OK toggles the favourite.
 */
@kotlin.OptIn(ExperimentalFoundationApi::class)
@OptIn(UnstableApi::class)
@Composable
fun OrbitaChannelsTvScreen(
    viewModel: TvViewModel = hiltViewModel(),
    currentProfile: Profile? = null,
    footballMode: Boolean = false,
    onOpenFullscreen: (IptvChannel) -> Unit,
    onNavigateToHome: () -> Unit,
    onNavigateToSearch: () -> Unit,
    onNavigateToWatchlist: () -> Unit,
    onNavigateToSettings: () -> Unit,
    onNavigateToIptvSettings: () -> Unit,
    onSwitchProfile: () -> Unit,
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val scope = rememberCoroutineScope()

    // ---- data ---------------------------------------------------------------------------
    val hidden = state.snapshot.hiddenGroups.toSet()
    val locked = state.lockedGroups.toSet()
    val groups = remember(state.groups, state.snapshot.hiddenGroups, state.lockedGroups) {
        state.groups.filter { it !in hidden && it !in locked && !state.channelsByGroup[it].isNullOrEmpty() }
    }
    val favoriteIds = state.snapshot.favoriteChannels
    val categories = remember(groups, favoriteIds, state.channelsByGroup, state.snapshot.channels.size) {
        buildList {
            if (favoriteIds.isNotEmpty()) add(OrbitaCategory(CAT_FAVORITES, "Favoritos", favoriteIds.size))
            add(OrbitaCategory(CAT_ALL, "Todos os canais", state.snapshot.totalChannelCount.takeIf { it > 0 } ?: state.snapshot.channels.size))
            groups.forEach { g -> add(OrbitaCategory(g, g, state.channelsByGroup[g]?.size ?: 0)) }
        }
    }

    var selectedCategory by rememberSaveable { mutableStateOf<String?>(null) }
    // Pick the start category: football group in "Futebol", otherwise the last one used.
    LaunchedEffect(categories.size, footballMode) {
        if (categories.isEmpty()) return@LaunchedEffect
        val current = selectedCategory
        if (current != null && categories.any { it.id == current }) return@LaunchedEffect
        selectedCategory = if (footballMode) {
            groups.firstOrNull { isOrbitaFootballLabel(it) }
                ?: groups.firstOrNull { isOrbitaSportsLabel(it) }
                ?: CAT_ALL
        } else {
            state.tvSession.lastGroupName.takeIf { last -> last.isNotBlank() && groups.contains(last) } ?: CAT_ALL
        }
    }

    val channels: List<IptvChannel> = remember(selectedCategory, state.channelsByGroup, state.snapshot.channels, favoriteIds) {
        when (val cat = selectedCategory) {
            null -> emptyList()
            CAT_FAVORITES -> favoriteIds.mapNotNull { state.channelLookup[it] }
            CAT_ALL -> state.snapshot.channels.filter { it.group !in hidden && it.group !in locked }
            else -> state.channelsByGroup[cat].orEmpty()
        }
    }

    var focusedChannelId by remember { mutableStateOf<String?>(null) }
    var previewChannelId by rememberSaveable { mutableStateOf<String?>(null) }
    val previewChannel = previewChannelId?.let { state.channelLookup[it] }
    val infoChannel = previewChannel ?: focusedChannelId?.let { state.channelLookup[it] } ?: channels.firstOrNull()

    // Load the programme guide for what is on screen.
    LaunchedEffect(selectedCategory, channels.size, focusedChannelId) {
        delay(250)
        if (channels.isNotEmpty()) {
            viewModel.prefetchVisibleCategoryEpg(channels.take(200).map { it.id }, focusedChannelId)
        }
    }
    LaunchedEffect(state.isConfigured) {
        if (state.isConfigured && state.snapshot.channels.isEmpty() && !state.isLoading) {
            viewModel.refresh(force = false, showLoading = true, forceEpg = false)
        }
    }

    val now by produceState(System.currentTimeMillis()) {
        while (true) { delay(20_000); value = System.currentTimeMillis() }
    }

    // ---- preview player -----------------------------------------------------------------
    val context = LocalContext.current
    val player = remember {
        ExoPlayer.Builder(context).build().apply { playWhenReady = true }
    }
    DisposableEffect(Unit) {
        onDispose {
            viewModel.setLiveTvPlaybackActive(false)
            player.stop()
            player.release()
        }
    }
    LaunchedEffect(previewChannel?.id) {
        val ch = previewChannel
        if (ch == null) {
            player.stop()
            viewModel.setLiveTvPlaybackActive(false)
            return@LaunchedEffect
        }
        val headers = ch.requestHeaders
        val dataSource = DefaultHttpDataSource.Factory()
            .setAllowCrossProtocolRedirects(true)
            .setUserAgent(headers.entries.firstOrNull { it.key.equals("User-Agent", true) }?.value ?: "OrbitaPlay/Android")
            .setDefaultRequestProperties(headers.filterKeys { !it.equals("User-Agent", true) })
        val source = DefaultMediaSourceFactory(dataSource).createMediaSource(MediaItem.fromUri(ch.streamUrl))
        player.setMediaSource(source)
        player.prepare()
        player.play()
        viewModel.setLiveTvPlaybackActive(true)
        viewModel.refreshCurrentChannelEpg(ch.id)
        viewModel.rememberTvSession(lastChannelId = ch.id, lastGroupName = selectedCategory?.takeUnless { it.startsWith("__orbita") }, lastFocusedZone = "GUIDE")
    }

    fun openFullscreen(ch: IptvChannel) {
        // Free the provider connection before ARVIO's player opens the same stream.
        player.stop()
        previewChannelId = null
        onOpenFullscreen(ch)
    }

    // ---- focus ----------------------------------------------------------------------------
    val topBarFocus = remember { FocusRequester() }
    val categoryFocus = remember { FocusRequester() }
    val channelFocus = remember { FocusRequester() }
    val hasProfile = currentProfile != null
    val selectedMenu = if (footballMode) SidebarItem.FOOTBALL else SidebarItem.TV
    var topBarFocused by remember { mutableStateOf(false) }
    var topBarIndex by remember { mutableIntStateOf(topBarSelectedIndex(selectedMenu, hasProfile).coerceAtLeast(0)) }
    var initialFocusDone by remember { mutableStateOf(false) }
    LaunchedEffect(channels.isNotEmpty(), categories.isNotEmpty()) {
        if (initialFocusDone) return@LaunchedEffect
        delay(150)
        val ok = if (channels.isNotEmpty()) runCatching { channelFocus.requestFocus() }.isSuccess
        else categories.isNotEmpty() && runCatching { categoryFocus.requestFocus() }.isSuccess
        if (ok) initialFocusDone = true
    }

    Box(
        Modifier
            .fillMaxSize()
            .background(Color(0xFF07070F))
            .background(
                Brush.radialGradient(
                    colors = listOf(Color(0xFF1D1550), Color.Transparent),
                    center = androidx.compose.ui.geometry.Offset(250f, 0f),
                    radius = 1400f,
                )
            )
    ) {
        Row(
            Modifier
                .fillMaxSize()
                .padding(start = 32.dp, end = 32.dp, top = AppTopBarContentTopInset - 24.dp, bottom = 40.dp),
            horizontalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            // ---- column 1: categories
            Panel(Modifier.width(260.dp).fillMaxHeight()) {
                PanelHeader("CATEGORIAS", "${categories.size}")
                val listState = rememberLazyListState()
                LaunchedEffect(selectedCategory, categories.size) {
                    val idx = categories.indexOfFirst { it.id == selectedCategory }
                    if (idx > 3) listState.scrollToItem((idx - 3).coerceAtLeast(0))
                }
                LazyColumn(state = listState, modifier = Modifier.fillMaxSize()) {
                    itemsIndexed(categories, key = { _, c -> c.id }) { _, cat ->
                        val isSelected = cat.id == selectedCategory
                        CategoryRow(
                            category = cat,
                            selected = isSelected,
                            modifier = if (isSelected) Modifier.focusRequester(categoryFocus) else Modifier,
                            onFocused = {
                                if (selectedCategory != cat.id) {
                                    selectedCategory = cat.id
                                }
                            },
                            onClick = {
                                selectedCategory = cat.id
                                scope.launch { delay(80); runCatching { channelFocus.requestFocus() } }
                            },
                            onUp = if (categories.firstOrNull()?.id == cat.id) {
                                { runCatching { topBarFocus.requestFocus() } }
                            } else null,
                        )
                    }
                }
            }

            // ---- column 2: channels
            Panel(Modifier.width(370.dp).fillMaxHeight()) {
                val title = categories.firstOrNull { it.id == selectedCategory }?.label?.uppercase() ?: ""
                PanelHeader(title, "${channels.size} CANAIS")
                when {
                    !state.isConfigured -> EmptyHint(
                        "Nenhuma lista configurada",
                        "Adicione sua lista M3U ou Xtream em Ajustes › TV ao vivo.",
                        actionLabel = "Abrir ajustes",
                        onAction = onNavigateToIptvSettings,
                    )
                    channels.isEmpty() && state.isLoading -> EmptyHint(
                        state.loadingMessage ?: "Carregando canais…",
                        if (state.loadingPercent > 0) "${state.loadingPercent}%" else null,
                    )
                    channels.isEmpty() -> EmptyHint("Nenhum canal nesta categoria", null)
                    else -> {
                        val listState = rememberLazyListState()
                        LazyColumn(state = listState, modifier = Modifier.fillMaxSize().padding(horizontal = 6.dp)) {
                            itemsIndexed(channels, key = { _, c -> c.id }) { index, ch ->
                                val isFirst = index == 0
                                ChannelRowItem(
                                    number = ch.providerChannelNumber ?: "${index + 1}",
                                    channel = ch,
                                    nowNext = state.snapshot.nowNext[ch.id],
                                    now = now,
                                    favorite = ch.id in favoriteIds,
                                    playing = ch.id == previewChannelId,
                                    modifier = if (isFirst) Modifier.focusRequester(channelFocus) else Modifier,
                                    onFocused = { focusedChannelId = ch.id },
                                    onClick = {
                                        if (previewChannelId == ch.id) openFullscreen(ch) else previewChannelId = ch.id
                                    },
                                    onLongClick = { viewModel.toggleFavoriteChannel(ch.id) },
                                    onUp = if (isFirst) {
                                        { runCatching { topBarFocus.requestFocus() } }
                                    } else null,
                                )
                            }
                        }
                    }
                }
            }

            // ---- column 3: preview + guide
            Column(Modifier.weight(1f).fillMaxHeight(), verticalArrangement = Arrangement.spacedBy(14.dp)) {
                Box(
                    Modifier
                        .fillMaxWidth()
                        .aspectRatio(16f / 9f)
                        .clip(RoundedCornerShape(16.dp))
                        .background(Brush.linearGradient(listOf(Color(0xFF16123A), Color(0xFF07070F))))
                ) {
                    if (previewChannel != null) {
                        AndroidView(
                            factory = { ctx ->
                                (LayoutInflater.from(ctx).inflate(R.layout.trailer_player_view, null) as PlayerView).apply {
                                    this.player = player
                                    useController = false
                                    descendantFocusability = android.view.ViewGroup.FOCUS_BLOCK_DESCENDANTS
                                    isFocusable = false
                                    resizeMode = AspectRatioFrameLayout.RESIZE_MODE_FIT
                                    keepScreenOn = true
                                }
                            },
                            update = { v -> if (v.player !== player) v.player = player },
                            modifier = Modifier.fillMaxSize(),
                        )
                        LiveBadge(Modifier.align(Alignment.TopStart).padding(14.dp))
                    } else {
                        Column(Modifier.align(Alignment.Center), horizontalAlignment = Alignment.CenterHorizontally) {
                            infoChannel?.logo?.let {
                                AsyncImage(model = it, contentDescription = null, modifier = Modifier.size(96.dp), contentScale = ContentScale.Fit)
                                Spacer(Modifier.height(12.dp))
                            }
                            Text("Pressione OK para assistir aqui", color = Orbita.TextMute, fontSize = 14.sp)
                            Text("OK de novo abre em tela cheia", color = Orbita.TextFaint, fontSize = 12.sp)
                        }
                    }
                    // Title strip
                    infoChannel?.let { ch ->
                        val nn = state.snapshot.nowNext[ch.id]
                        Column(
                            Modifier
                                .align(Alignment.BottomStart)
                                .fillMaxWidth()
                                .background(Brush.verticalGradient(listOf(Color.Transparent, Color(0xD9000000))))
                                .padding(start = 18.dp, end = 18.dp, top = 28.dp, bottom = 14.dp)
                        ) {
                            Text(ch.name, color = Color.White, fontSize = 20.sp, fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            nn?.now?.let { p ->
                                Text(
                                    "${p.title} · ${hhmm(p.startUtcMillis)} – ${hhmm(p.endUtcMillis)}",
                                    color = Orbita.TextDim, fontSize = 13.sp, maxLines = 1, overflow = TextOverflow.Ellipsis,
                                )
                                ProgressLine(progressOf(p, now), Orbita.Cyan, Modifier.padding(top = 8.dp))
                            }
                        }
                    }
                }
                Panel(Modifier.fillMaxWidth().weight(1f)) {
                    PanelHeader("PROGRAMAÇÃO", "HOJE")
                    val programmes = remember(infoChannel?.id, state.snapshot.nowNext[infoChannel?.id]) {
                        guideFor(state.snapshot.nowNext[infoChannel?.id])
                    }
                    if (programmes.isEmpty()) {
                        EmptyHint("Sem programação para este canal", null)
                    } else {
                        Column(Modifier.fillMaxWidth()) {
                            programmes.take(7).forEach { p ->
                                val isNow = p.isLive(now)
                                Row(
                                    Modifier
                                        .fillMaxWidth()
                                        .background(if (isNow) Orbita.Cyan.copy(alpha = 0.10f) else Color.Transparent)
                                        .padding(horizontal = 18.dp, vertical = 9.dp),
                                    verticalAlignment = Alignment.CenterVertically,
                                ) {
                                    if (isNow) Box(Modifier.width(3.dp).height(18.dp).background(Orbita.Cyan))
                                    Text(
                                        hhmm(p.startUtcMillis),
                                        color = if (isNow) Orbita.Cyan else Orbita.TextMute,
                                        fontWeight = FontWeight.SemiBold, fontSize = 14.sp,
                                        modifier = Modifier.width(70.dp).padding(start = if (isNow) 8.dp else 0.dp),
                                    )
                                    Text(p.title, color = Orbita.Text, fontSize = 14.sp, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f, fill = false))
                                    if (isNow) {
                                        Text(
                                            "AGORA", color = Color(0xFF07070F), fontSize = 10.sp, fontWeight = FontWeight.Bold,
                                            modifier = Modifier.padding(start = 8.dp).clip(RoundedCornerShape(4.dp)).background(Orbita.Cyan).padding(horizontal = 6.dp, vertical = 1.dp),
                                        )
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }

        // ---- top menu (shared ARVIO top bar, driven by the remote)
        Box(
            Modifier
                .focusRequester(topBarFocus)
                .onFocusChanged { topBarFocused = it.isFocused }
                .onPreviewKeyEvent { ev ->
                    if (ev.type != KeyEventType.KeyDown) return@onPreviewKeyEvent false
                    val max = topBarMaxIndex(hasProfile)
                    when (ev.key) {
                        Key.DirectionLeft -> { topBarIndex = (topBarIndex - 1).coerceAtLeast(0); true }
                        Key.DirectionRight -> { topBarIndex = (topBarIndex + 1).coerceAtMost(max); true }
                        Key.DirectionDown -> {
                            runCatching { if (channels.isNotEmpty()) channelFocus.requestFocus() else categoryFocus.requestFocus() }
                            true
                        }
                        Key.DirectionUp -> true
                        Key.Enter, Key.DirectionCenter, Key.NumPadEnter -> {
                            when (topBarFocusedItem(topBarIndex, hasProfile)) {
                                null -> onSwitchProfile()
                                SidebarItem.TV -> if (footballMode) OrbitaNav.go(SidebarItem.TV)
                                SidebarItem.FOOTBALL -> if (!footballMode) OrbitaNav.go(SidebarItem.FOOTBALL)
                                SidebarItem.HOME -> onNavigateToHome()
                                SidebarItem.SERIES -> OrbitaNav.go(SidebarItem.SERIES)
                                SidebarItem.SEARCH -> onNavigateToSearch()
                                SidebarItem.WATCHLIST -> onNavigateToWatchlist()
                                SidebarItem.SETTINGS -> onNavigateToSettings()
                            }
                            true
                        }
                        else -> false
                    }
                }
                .focusable()
        ) {
            AppTopBar(
                selectedItem = selectedMenu,
                isFocused = topBarFocused,
                focusedIndex = if (topBarFocused) topBarIndex else -1,
                profile = currentProfile,
            )
        }

        // ---- remote hints
        Row(
            Modifier.align(Alignment.BottomStart).padding(start = 32.dp, bottom = 10.dp),
            horizontalArrangement = Arrangement.spacedBy(22.dp),
        ) {
            KeyHint("OK", "Assistir / tela cheia")
            KeyHint("▲▼", "Trocar canal")
            KeyHint("◀▶", "Categorias / canais")
            KeyHint("OK", "segurar · Favoritar")
        }
    }
}

// ---------------------------------------------------------------------------------------------

@Composable
private fun Panel(modifier: Modifier, content: @Composable () -> Unit) {
    Column(
        modifier
            .clip(RoundedCornerShape(16.dp))
            .background(Orbita.Panel)
            .border(1.dp, Orbita.PanelBorder, RoundedCornerShape(16.dp))
    ) { content() }
}

@Composable
private fun PanelHeader(left: String, right: String) {
    Row(Modifier.fillMaxWidth().padding(start = 18.dp, end = 18.dp, top = 14.dp, bottom = 10.dp)) {
        Text(left, color = Orbita.TextMute, fontSize = 11.sp, fontWeight = FontWeight.SemiBold, letterSpacing = 1.5.sp, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
        Text(right, color = Orbita.TextMute, fontSize = 11.sp, fontWeight = FontWeight.SemiBold, letterSpacing = 1.5.sp)
    }
}

@Composable
private fun CategoryRow(
    category: OrbitaCategory,
    selected: Boolean,
    modifier: Modifier,
    onFocused: () -> Unit,
    onClick: () -> Unit,
    onUp: (() -> Unit)?,
) {
    val interaction = remember { MutableInteractionSource() }
    val focused by interaction.collectIsFocusedAsState()
    LaunchedEffect(focused) { if (focused) { delay(220); onFocused() } }
    Row(
        modifier
            .fillMaxWidth()
            .onPreviewKeyEvent { ev ->
                if (onUp != null && ev.type == KeyEventType.KeyDown && ev.key == Key.DirectionUp) { onUp(); true } else false
            }
            .background(
                when {
                    focused -> Orbita.Accent.copy(alpha = 0.35f)
                    selected -> Orbita.Accent.copy(alpha = 0.18f)
                    else -> Color.Transparent
                }
            )
            .combinedClickable(interactionSource = interaction, indication = null, onClick = onClick)
            .padding(end = 16.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.width(3.dp).height(44.dp).background(if (selected || focused) Orbita.Accent else Color.Transparent))
        Text(
            category.label,
            color = if (selected || focused) Color.White else Orbita.TextDim,
            fontSize = 15.sp,
            fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal,
            maxLines = 1, overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f).padding(start = 15.dp),
        )
        Text(
            "${category.count}",
            color = if (selected) Color.White else Orbita.TextFaint,
            fontSize = 11.sp,
            modifier = Modifier
                .clip(RoundedCornerShape(8.dp))
                .background(if (selected) Orbita.Accent else Orbita.Chip)
                .padding(horizontal = 8.dp, vertical = 1.dp),
        )
    }
}

@kotlin.OptIn(ExperimentalFoundationApi::class)
@Composable
private fun ChannelRowItem(
    number: String,
    channel: IptvChannel,
    nowNext: IptvNowNext?,
    now: Long,
    favorite: Boolean,
    playing: Boolean,
    modifier: Modifier,
    onFocused: () -> Unit,
    onClick: () -> Unit,
    onLongClick: () -> Unit,
    onUp: (() -> Unit)?,
) {
    val interaction = remember { MutableInteractionSource() }
    val focused by interaction.collectIsFocusedAsState()
    LaunchedEffect(focused) { if (focused) onFocused() }
    val shape = RoundedCornerShape(10.dp)
    Row(
        modifier
            .fillMaxWidth()
            .padding(vertical = 2.dp)
            .scale(if (focused) 1.02f else 1f)
            .clip(shape)
            .background(if (focused) Orbita.Accent.copy(alpha = 0.28f) else Color.Transparent)
            .border(if (focused) 2.dp else 0.dp, if (focused) Orbita.Accent else Color.Transparent, shape)
            .onPreviewKeyEvent { ev ->
                if (onUp != null && ev.type == KeyEventType.KeyDown && ev.key == Key.DirectionUp) { onUp(); true } else false
            }
            .combinedClickable(interactionSource = interaction, indication = null, onClick = onClick, onLongClick = onLongClick)
            .padding(horizontal = 10.dp, vertical = 7.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            number, color = if (playing) Orbita.Cyan else Orbita.TextFaint, fontSize = 13.sp, fontWeight = FontWeight.SemiBold,
            modifier = Modifier.width(34.dp), maxLines = 1,
        )
        Box(
            Modifier.size(width = 50.dp, height = 34.dp).clip(RoundedCornerShape(6.dp)).background(Color(0xFF0B0A15)),
            contentAlignment = Alignment.Center,
        ) {
            if (!channel.logo.isNullOrBlank()) {
                AsyncImage(model = channel.logo, contentDescription = null, contentScale = ContentScale.Fit, modifier = Modifier.fillMaxSize().padding(3.dp))
            } else {
                Text(channel.name.take(3).uppercase(), color = Color.White, fontSize = 10.sp, fontWeight = FontWeight.Bold)
            }
        }
        Column(Modifier.weight(1f).padding(start = 12.dp)) {
            Text(channel.name, color = Color.White, fontSize = 14.sp, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
            val p = nowNext?.now?.takeIf { it.isLive(now) }
            Text(
                p?.let { "${it.title} · ${hhmm(it.startUtcMillis)}" } ?: channel.group,
                color = if (focused) Orbita.TextDim else Orbita.TextMute, fontSize = 11.sp, maxLines = 1, overflow = TextOverflow.Ellipsis,
            )
        }
        if (favorite) Text("★", color = Orbita.Star, fontSize = 14.sp, modifier = Modifier.padding(start = 6.dp))
    }
}

@Composable
private fun LiveBadge(modifier: Modifier) {
    Text(
        "● AO VIVO", color = Color.White, fontSize = 11.sp, fontWeight = FontWeight.Bold, letterSpacing = 1.sp,
        modifier = modifier.clip(RoundedCornerShape(6.dp)).background(Orbita.Live).padding(horizontal = 10.dp, vertical = 4.dp),
    )
}

@Composable
private fun ProgressLine(progress: Float, color: Color, modifier: Modifier = Modifier) {
    Box(modifier.fillMaxWidth().height(4.dp).clip(RoundedCornerShape(2.dp)).background(Color.White.copy(alpha = 0.2f))) {
        Box(Modifier.fillMaxWidth(progress.coerceIn(0f, 1f)).fillMaxHeight().background(color))
    }
}

@Composable
private fun EmptyHint(title: String, subtitle: String?, actionLabel: String? = null, onAction: (() -> Unit)? = null) {
    Column(Modifier.fillMaxWidth().padding(24.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        Text(title, color = Orbita.Text, fontSize = 15.sp, fontWeight = FontWeight.SemiBold)
        subtitle?.let { Text(it, color = Orbita.TextMute, fontSize = 12.sp, modifier = Modifier.padding(top = 6.dp)) }
        if (actionLabel != null && onAction != null) {
            val interaction = remember { MutableInteractionSource() }
            val focused by interaction.collectIsFocusedAsState()
            Text(
                actionLabel, color = Color.White, fontSize = 14.sp, fontWeight = FontWeight.SemiBold,
                modifier = Modifier
                    .padding(top = 14.dp)
                    .clip(RoundedCornerShape(10.dp))
                    .background(if (focused) Orbita.Accent else Orbita.Chip)
                    .combinedClickable(interactionSource = interaction, indication = null, onClick = onAction)
                    .padding(horizontal = 18.dp, vertical = 8.dp),
            )
        }
    }
}

@Composable
private fun KeyHint(key: String, label: String) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(
            key, color = Color.White, fontSize = 10.sp, fontWeight = FontWeight.SemiBold,
            modifier = Modifier.clip(RoundedCornerShape(4.dp)).background(Orbita.Chip).padding(horizontal = 6.dp, vertical = 1.dp),
        )
        Text(label, color = Orbita.TextMute, fontSize = 11.sp, modifier = Modifier.padding(start = 6.dp))
    }
}

private fun guideFor(nn: IptvNowNext?): List<IptvProgram> {
    if (nn == null) return emptyList()
    val all = buildList {
        addAll(nn.recent.takeLast(1))
        nn.now?.let(::add)
        nn.next?.let(::add)
        nn.later?.let(::add)
        addAll(nn.upcoming)
    }
    return all.distinctBy { it.startUtcMillis }.sortedBy { it.startUtcMillis }
}

private fun progressOf(p: IptvProgram, now: Long): Float {
    val total = (p.endUtcMillis - p.startUtcMillis).coerceAtLeast(1L)
    return ((now - p.startUtcMillis).toFloat() / total).coerceIn(0f, 1f)
}

private val timeFormat = SimpleDateFormat("HH:mm", Locale.getDefault())
private fun hhmm(ms: Long): String = synchronized(timeFormat) { timeFormat.format(Date(ms)) }
