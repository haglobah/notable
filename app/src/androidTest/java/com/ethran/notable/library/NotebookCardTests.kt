package com.ethran.notable.library

import androidx.compose.foundation.layout.width
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.platform.LocalInspectionMode
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.v2.createComposeRule
import com.ethran.notable.testing.ComposeUiSupportRule
import com.ethran.notable.ui.components.NotebookCard
import com.ethran.notable.ui.theme.InkaTheme
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test

class NotebookCardTests {
    private val compose = createComposeRule()
    @get:Rule val gate = ComposeUiSupportRule(compose)
    private var opens = 0
    private var details = 0

    private fun card() {
        compose.setContent {
            CompositionLocalProvider(LocalInspectionMode provides true) {
                InkaTheme(darkTheme = false, scaleFactor = 1f) {
                    NotebookCard(modifier = Modifier.width(220.dp), bookId = "book", title = "Meeting notes",
                        pageIds = listOf("cover", "last"), openPageId = "last",
                        onOpen = { book, page ->
                            assertEquals("book", book)
                            assertEquals("last", page)
                            opens++
                        }, onOpenSettings = { details++ })
                }
            }
        }
    }

    @Test fun titleOpensNotebookExactlyOnce() {
        card()
        compose.onNodeWithText("Meeting notes", useUnmergedTree = true).performTouchInput { click() }
        compose.runOnIdle { assertEquals(1, opens); assertEquals(0, details) }
    }

    @Test fun visibleDetailsDoesNotOpenNotebook() {
        card()
        compose.onNodeWithContentDescription("Details for Meeting notes").performClick()
        compose.runOnIdle { assertEquals(0, opens); assertEquals(1, details) }
    }

    @Test fun longPressTitleOpensDetailsOnly() {
        card()
        compose.onNodeWithText("Meeting notes", useUnmergedTree = true).performTouchInput { longClick() }
        compose.runOnIdle { assertEquals(0, opens); assertEquals(1, details) }
    }
}
