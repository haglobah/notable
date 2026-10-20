package com.ethran.notable.image

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/** What the editor needs of the live code image (ADR-002): the command menu and the open page. */
interface EditorImage {
    /** Built-ins first, then the image's commands. */
    val commands: StateFlow<List<ImageCommand>>

    /** The page open in the editor, for `notable.page()`; null when none is. */
    var openPage: OpenPage?

    /** Runs command [id] off the main thread; an error becomes a snack, never an exception. */
    fun runCommand(id: String)

    /** No image: for tests that build the editor by hand. */
    object None : EditorImage {
        override val commands: StateFlow<List<ImageCommand>> = MutableStateFlow(emptyList())
        override var openPage: OpenPage? = null
        override fun runCommand(id: String) = Unit
    }
}
