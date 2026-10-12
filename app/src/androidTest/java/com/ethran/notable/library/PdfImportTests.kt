package com.ethran.notable.library

import android.net.Uri
import androidx.activity.compose.LocalActivityResultRegistryOwner
import androidx.activity.result.ActivityResultRegistry
import androidx.activity.result.ActivityResultRegistryOwner
import androidx.activity.result.contract.ActivityResultContract
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.core.app.ActivityOptionsCompat
import com.ethran.notable.testing.ComposeUiSupportRule
import com.ethran.notable.ui.theme.InkaTheme
import com.ethran.notable.ui.views.NotebookImportPanel
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test

class PdfImportTests {
    private val compose = createComposeRule()
    @get:Rule val gate = ComposeUiSupportRule(compose)
    private val pdfImports = mutableListOf<Uri>()
    private val xoppImports = mutableListOf<Uri>()

    private fun pick(result: Uri?) {
        val owner = object : ActivityResultRegistryOwner {
            override val activityResultRegistry = object : ActivityResultRegistry() {
                override fun <I, O> onLaunch(requestCode: Int, contract: ActivityResultContract<I, O>,
                    input: I, options: ActivityOptionsCompat?) {
                    dispatchResult(requestCode, result)
                }
            }
        }
        compose.setContent {
            CompositionLocalProvider(LocalActivityResultRegistryOwner provides owner) {
                InkaTheme(darkTheme = false, scaleFactor = 1f) {
                    NotebookImportPanel(onCreateNewNotebook = {}, onCreateNewQuickPage = {},
                        onCreateNewFolder = {},
                        onImportPdf = { uri -> pdfImports += uri },
                        onImportXopp = { xoppImports += it })
                }
            }
        }
        compose.onNodeWithText("Import").performClick()
        compose.waitForIdle()
    }

    @Test fun selectedPdfImportsImmediatelyWithoutQuestion() {
        val uri = Uri.parse("file:///preview/document.PDF")
        pick(uri)
        compose.runOnIdle { assertEquals(listOf(uri), pdfImports); assertEquals(emptyList<Uri>(), xoppImports) }
        compose.onNodeWithText("Do you want to copy or observe the PDF?").assertDoesNotExist()
    }

    @Test fun xoppStillUsesItsOwnImporter() {
        val uri = Uri.parse("file:///preview/notebook.xopp")
        pick(uri)
        compose.runOnIdle { assertEquals(listOf(uri), xoppImports); assertEquals(0, pdfImports.size) }
    }

    @Test fun cancelledPickerDoesNotImport() {
        pick(null)
        compose.runOnIdle { assertEquals(0, xoppImports.size); assertEquals(0, pdfImports.size) }
    }
}
