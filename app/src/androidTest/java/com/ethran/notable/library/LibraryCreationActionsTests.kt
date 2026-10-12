package com.ethran.notable.library

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.width
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.unit.dp
import com.ethran.notable.testing.ComposeUiSupportRule
import com.ethran.notable.ui.theme.InkaTheme
import com.ethran.notable.ui.views.LibraryCreationActions
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test

class LibraryCreationActionsTests {
    private val compose = createComposeRule()
    @get:Rule val gate = ComposeUiSupportRule(compose)

    @Test fun allCreationActionsRemainVisibleAndIndependentOnNarrowLayout() {
        val actions = mutableListOf<String>()
        compose.setContent {
            InkaTheme(darkTheme = false, scaleFactor = 1f) {
                Box(Modifier.width(280.dp)) {
                    LibraryCreationActions(
                        onCreateNewNotebook = { actions += "notebook" },
                        onCreateNewQuickPage = { actions += "page" },
                        onCreateNewFolder = { actions += "folder" },
                        onImport = { actions += "import" }
                    )
                }
            }
        }
        listOf("New notebook", "Quick page", "New folder", "Import").forEach {
            compose.onNodeWithText(it).assertIsDisplayed().performClick()
        }
        compose.runOnIdle { assertEquals(listOf("notebook", "page", "folder", "import"), actions) }
    }
}
