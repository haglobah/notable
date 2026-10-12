package com.ethran.notable.details

import androidx.compose.runtime.*
import androidx.compose.ui.platform.LocalInspectionMode
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.lifecycle.MutableLiveData
import androidx.test.espresso.Espresso
import com.ethran.notable.data.AppRepository
import com.ethran.notable.data.db.Notebook
import com.ethran.notable.io.ExportEngine
import com.ethran.notable.testing.ComposeUiSupportRule
import com.ethran.notable.ui.dialogs.NotebookConfigDialog
import com.ethran.notable.ui.theme.InkaTheme
import io.mockk.*
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test

class NotebookDetailsTests {
    private val compose = createComposeRule()
    @get:Rule val gate = ComposeUiSupportRule(compose)
    private val repository = mockk<AppRepository>(relaxed = true)
    private val notebook = Notebook(id = "details-test", title = "Original", pageIds = listOf("page"))
    private val live = MutableLiveData<Notebook?>(notebook)
    private var savedTitle: String? = null
    private var closed = false

    private fun show() {
        every { repository.bookRepository.getByIdLive(notebook.id) } returns live
        coEvery { repository.bookRepository.getById(notebook.id) } returns notebook
        coEvery { repository.edits.renameNotebook(notebook.id, any()) } answers { savedTitle = secondArg<String>() }
        compose.setContent {
            CompositionLocalProvider(LocalInspectionMode provides true) {
                InkaTheme(darkTheme = false, scaleFactor = 1f) {
                    var visible by remember { mutableStateOf(true) }
                    if (visible) NotebookConfigDialog(repository, mockk<ExportEngine>(relaxed = true),
                        notebook.id) { closed = true; visible = false }
                }
            }
        }
    }

    @Test fun doneWaitsForSaveBeforeClosing() {
        show()
        val gate = kotlinx.coroutines.CompletableDeferred<Unit>()
        coEvery { repository.edits.renameNotebook(notebook.id, any()) } coAnswers {
            gate.await()
            savedTitle = secondArg<String>()
        }
        compose.onNode(hasSetTextAction()).performTextReplacement("Renamed")
        compose.onNodeWithText("Done").performClick()
        compose.runOnIdle { assertFalse(closed); assertNull(savedTitle) }
        compose.onNodeWithText("Saving…").assertExists()
        gate.complete(Unit)
        compose.waitUntil(5_000) { closed }
        compose.runOnIdle { assertEquals("Renamed", savedTitle) }
    }

    @Test fun failedSaveKeepsDraftAndRetriesBeforeClosing() {
        show()
        coEvery { repository.edits.renameNotebook(notebook.id, any()) } throws java.io.IOException("Disk full")
        compose.onNode(hasSetTextAction()).performTextReplacement("Renamed")
        compose.onNodeWithText("Done").performClick()
        compose.onNodeWithText("Could not save changes. Please try again.").assertExists()
        compose.onNode(hasSetTextAction()).assertTextEquals("Renamed")
        compose.runOnIdle { assertFalse(closed) }
        coEvery { repository.edits.renameNotebook(notebook.id, any()) } answers { savedTitle = secondArg<String>() }
        compose.onNodeWithText("Try again").performClick()
        compose.waitUntil(5_000) { closed }
        compose.runOnIdle { assertEquals("Renamed", savedTitle) }
    }

    @Test fun backSavesFocusedNameBeforeClosing() {
        show()
        compose.onNode(hasSetTextAction()).performTextReplacement("Renamed")
        Espresso.closeSoftKeyboard()
        Espresso.pressBack()
        compose.waitForIdle()
        compose.runOnIdle { assertTrue("Dialog closed", closed); assertEquals("Renamed", savedTitle) }
    }
}
