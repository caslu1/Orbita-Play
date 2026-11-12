package com.arflix.tv.ui.screens.home

import androidx.compose.runtime.staticCompositionLocalOf
import com.arflix.tv.data.model.Category
import com.arflix.tv.data.model.MediaItem
import com.arflix.tv.data.model.MediaType
import com.arflix.tv.data.model.SportsAddonCapabilities
import com.arflix.tv.ui.components.SidebarItem

/**
 * Orbita Play: the home catalog is shown in two menu sections.
 * "Filmes" shows only movies and "Séries" shows only series.
 */
enum class HomeSection(val mediaType: MediaType, val menuItem: SidebarItem) {
    MOVIES(MediaType.MOVIE, SidebarItem.HOME),
    SERIES(MediaType.TV, SidebarItem.SERIES),
}

val LocalHomeSection = staticCompositionLocalOf { HomeSection.MOVIES }

/** Rows that belong to live TV / football, not to the Filmes or Séries catalog. */
private val liveRowIds = setOf(
    HomeViewModel.FAVORITE_TV_CATEGORY_ID,
    SportsAddonCapabilities.SPORTS_CATEGORY_ROW_ID,
    SportsAddonCapabilities.POPULAR_LIVE_TV_ROW_ID,
)

internal fun filterCategoriesForSection(categories: List<Category>, section: HomeSection): List<Category> =
    categories.mapNotNull { category ->
        if (category.id in liveRowIds) return@mapNotNull null
        val items = category.items.filter { it.mediaType == section.mediaType }
        when {
            items.isEmpty() -> null
            items.size == category.items.size -> category
            else -> category.copy(items = items)
        }
    }

internal fun MediaItem?.matchesSection(section: HomeSection): Boolean =
    this != null && mediaType == section.mediaType
