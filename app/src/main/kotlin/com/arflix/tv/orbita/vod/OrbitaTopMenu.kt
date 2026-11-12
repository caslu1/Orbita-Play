package com.arflix.tv.orbita.vod

import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.Box
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import com.arflix.tv.data.model.Profile
import com.arflix.tv.ui.components.AppTopBar
import com.arflix.tv.ui.components.SidebarItem
import com.arflix.tv.ui.components.topBarFocusedItem
import com.arflix.tv.ui.components.topBarMaxIndex
import com.arflix.tv.ui.components.topBarSelectedIndex

/**
 * The shared ARVIO top bar, driven by the remote: ◀ ▶ move, OK opens, ▼ goes back to the
 * content. [onOpen] receives null for the profile avatar.
 */
@Composable
fun OrbitaTopMenu(
    selected: SidebarItem,
    profile: Profile?,
    focusRequester: FocusRequester,
    onDown: () -> Unit,
    onOpen: (SidebarItem?) -> Unit,
) {
    val hasProfile = profile != null
    var focused by remember { mutableStateOf(false) }
    var index by remember { mutableIntStateOf(topBarSelectedIndex(selected, hasProfile).coerceAtLeast(0)) }
    Box(
        Modifier
            .focusRequester(focusRequester)
            .onFocusChanged { focused = it.isFocused }
            .onPreviewKeyEvent { ev ->
                if (ev.type != KeyEventType.KeyDown) return@onPreviewKeyEvent false
                when (ev.key) {
                    Key.DirectionLeft -> { index = (index - 1).coerceAtLeast(0); true }
                    Key.DirectionRight -> { index = (index + 1).coerceAtMost(topBarMaxIndex(hasProfile)); true }
                    Key.DirectionDown -> { onDown(); true }
                    Key.DirectionUp -> true
                    Key.Enter, Key.DirectionCenter, Key.NumPadEnter -> {
                        val item = topBarFocusedItem(index, hasProfile)
                        if (item != selected) onOpen(item)
                        true
                    }
                    else -> false
                }
            }
            .focusable()
    ) {
        AppTopBar(
            selectedItem = selected,
            isFocused = focused,
            focusedIndex = if (focused) index else -1,
            profile = profile,
        )
    }
}
