package com.ethran.notable.ui.dialogs


import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.result.contract.ActivityResultContracts.PickVisualMedia
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyGridScope
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.painter.Painter
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.core.graphics.createBitmap
import androidx.core.net.toUri
import com.ethran.notable.R
import com.ethran.notable.data.copyBackgroundToDatabase
import com.ethran.notable.data.ensureBackgroundsFolder
import com.ethran.notable.data.model.BackgroundType
import com.ethran.notable.editor.canvas.CanvasEventBus
import com.ethran.notable.editor.drawing.drawDottedBg
import com.ethran.notable.editor.drawing.drawHexedBg
import com.ethran.notable.editor.drawing.drawLinedBg
import com.ethran.notable.editor.drawing.drawSquaredBg
import com.ethran.notable.editor.utils.autoEInkAnimationOnScroll
import com.ethran.notable.io.getPdfPageCount
import com.ethran.notable.ui.design.NButton
import com.ethran.notable.ui.design.NButtonStyle
import com.ethran.notable.ui.design.NDialog
import com.ethran.notable.ui.design.NIcon
import com.ethran.notable.ui.design.NIconButton
import com.ethran.notable.ui.design.NSegmented
import com.ethran.notable.ui.design.NTextField
import com.ethran.notable.ui.design.NToggleRow
import com.ethran.notable.ui.design.NotableShape
import com.ethran.notable.ui.design.NotableSize
import com.ethran.notable.ui.design.NotableSpace
import com.ethran.notable.ui.design.NotableTheme
import com.ethran.notable.ui.design.inkClickable
import compose.icons.FeatherIcons
import compose.icons.feathericons.FileText
import compose.icons.feathericons.Image
import compose.icons.feathericons.Loader
import compose.icons.feathericons.Minus
import compose.icons.feathericons.Plus
import compose.icons.feathericons.RefreshCw
import io.shipbook.shipbooksdk.ShipBook
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import java.io.File
import java.io.FileOutputStream

private val log = ShipBook.getLogger("BackgroundSelector")

@Composable
fun BackgroundSelector(
    initialPageBackgroundType: String,
    initialPageBackground: String,
    initialPageNumberInPdf: Int = 0,
    isNotebookBgSelector: Boolean = false, // for notebook default background
    notebookId: String? = null,
    pageNumberInBook: Int = -1,
    onChange: (backgroundType: String, background: String?) -> Unit,
    onClose: () -> Unit
) {
    val scope = rememberCoroutineScope()
    val context = LocalContext.current
    var pageBackground by remember { mutableStateOf(initialPageBackground) }
    var maxPages: Int? by remember { mutableStateOf(getPdfPageCount(pageBackground)) }
    val currentPage: Int? by remember { mutableIntStateOf(initialPageNumberInPdf) }

    var pageBackgroundType: BackgroundType by remember {
        mutableStateOf(
            BackgroundType.fromKey(
                initialPageBackgroundType
            )
        )
    }

    var selectedBackgroundMode by remember {
        mutableStateOf(
            when (pageBackgroundType) {
                is BackgroundType.CoverImage -> "Cover"
                is BackgroundType.Image, is BackgroundType.ImageRepeating -> "Image"
                is BackgroundType.Pdf, is BackgroundType.AutoPdf -> "PDF"
                else -> "Native"
            }
        )
    }

    fun selectedToType(): BackgroundType {
        return when (selectedBackgroundMode) {
            "Cover" -> BackgroundType.CoverImage
            "Image" -> BackgroundType.Image
            "PDF" -> BackgroundType.Pdf(1)
            else -> {
                throw Exception("Unknown BackgroundType for selection $selectedBackgroundMode")
            }

        }
    }

    // Create an activity result launcher for picking visual media (images in this case)
    val pickMedia =
        rememberLauncherForActivityResult(contract = PickVisualMedia()) { uri ->
            if (uri == null) {
                log.w("PickVisualMedia: uri is null (user cancelled or provider returned null)")
                return@rememberLauncherForActivityResult
            }

            val currentType = selectedToType()
            log.d("PickVisualMedia: will copy to subfolder=\"$currentType\"")

            scope.launch(Dispatchers.IO) {
                try {
                    val copiedFile = copyBackgroundToDatabase(context, uri, currentType.folderName)

                    log.i("PickVisualMedia: copied -> ${copiedFile.absolutePath}")
                    onChange(currentType.key, copiedFile.toString())
                    scope.launch { CanvasEventBus.refreshUi.emit(Unit) }
                    pageBackground = copiedFile.toString()
                    log.d("PickVisualMedia: UI updated, pageBackground=$pageBackground, type=${currentType.key}")

                } catch (e: Exception) {
                    log.e("PickVisualMedia: copy failed: ${e.message}", e)
                }
            }
        }
    // PDF picker for backgrounds
    val pickPdf = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocument()
    ) { uri: Uri? ->
        if (uri == null) {
            log.w("PickPdf: uri is null (user cancelled or provider returned null)")
            return@rememberLauncherForActivityResult
        }

        val flag = Intent.FLAG_GRANT_READ_URI_PERMISSION
        context.contentResolver.takePersistableUriPermission(uri, flag)

        val currentType = selectedToType()
        log.d("PickPdf: will copy to subfolder=\"$currentType\"")
        scope.launch(Dispatchers.IO) {
            try {
                val copiedFile = copyBackgroundToDatabase(context, uri, currentType.folderName)
                onChange(currentType.key, copiedFile.toString())
                scope.launch { CanvasEventBus.refreshUi.emit(Unit) }
                pageBackground = copiedFile.toString()
                pageBackgroundType = currentType
                log.i("PDF was received and copied, it is now at:${copiedFile.toUri()}")
                log.i("PageSettingsModal: $pageBackgroundType")
            } catch (e: Exception) {
                log.e("PdfPicker: copy failed: ${e.message}", e)
            }
        }
    }

    val modes = listOf(
        "Native" to stringResource(R.string.bg_mode_native),
        "Image" to stringResource(R.string.bg_mode_image),
        "Cover" to stringResource(R.string.bg_mode_cover),
        "PDF" to stringResource(R.string.bg_mode_pdf),
    )
    NDialog(
        title = stringResource(R.string.details_template),
        onDismiss = { onClose() },
        maxWidth = 720.dp,
        titleAction = { NButton(stringResource(R.string.details_done), { onClose() }, style = NButtonStyle.Primary) },
    ) {
        NSegmented(modes, selectedBackgroundMode, { selectedBackgroundMode = it })
        Spacer(Modifier.height(NotableSpace.xl))
        // A steady height, so switching modes doesn't resize the dialog.
        Column(Modifier.fillMaxWidth().heightIn(min = 420.dp)) {
            when (selectedBackgroundMode) {
                "Image" -> {
                    val currentBackgroundType =
                        if (pageBackgroundType == BackgroundType.ImageRepeating || pageBackgroundType == BackgroundType.Image)
                            pageBackgroundType else BackgroundType.Image
                    ShowImageOption(
                        currentBackground = pageBackground,
                        currentBackgroundType = currentBackgroundType,
                        onBackgroundChange = { background, type ->
                            onChange(type.key, background)
                            pageBackground = background
                            pageBackgroundType = type
                        },
                        onRequestFilePicker = {
                            pickMedia.launch(PickVisualMediaRequest(PickVisualMedia.ImageOnly))
                        }
                    )
                    if (pageBackgroundType == BackgroundType.ImageRepeating || pageBackgroundType == BackgroundType.Image) {
                        Spacer(Modifier.height(NotableSpace.md))
                        NToggleRow(
                            title = stringResource(R.string.repeat_background),
                            checked = pageBackgroundType == BackgroundType.ImageRepeating,
                            onCheckedChange = { isChecked ->
                                pageBackgroundType =
                                    if (isChecked) BackgroundType.ImageRepeating else BackgroundType.Image

                                onChange(pageBackgroundType.key, null)
                            },
                            divider = false,
                        )
                    }
                }

                "Cover" -> {
                    ShowImageOption(
                        currentBackground = pageBackground,
                        currentBackgroundType = BackgroundType.CoverImage,
                        onBackgroundChange = { background, type ->
                            onChange(type.key, background)
                            pageBackground = background
                            log.e("onBackgroundChange: $type")
                            pageBackgroundType = type
                        },
                        onRequestFilePicker = {
                            log.e("onRequestFilePicker: $pageBackgroundType")
                            pickMedia.launch(PickVisualMediaRequest(PickVisualMedia.ImageOnly))
                        }
                    )
                }

                "Native" -> {
                    ShowNativeOption(
                        currentBackground = pageBackground,
                        currentBackgroundType = BackgroundType.Native,
                        onBackgroundChange = { background, type ->
                            onChange(type.key, background)
                            pageBackground = background
                            pageBackgroundType = type
                        },
                    )
                }

                "PDF" -> {
                    val currentBackgroundType =
                        if (pageBackgroundType == BackgroundType.AutoPdf || pageBackgroundType is BackgroundType.Pdf)
                            pageBackgroundType else BackgroundType.Pdf(1)

                    fun onBackgroundChange(type: BackgroundType, background: String) {
                        onChange(type.key, background)
                        pageBackground = background
                        pageBackgroundType = type
                        maxPages = getPdfPageCount(background)
                    }
                    ShowPdfOption(
                        currentBackground = pageBackground,
                        currentBackgroundType = currentBackgroundType,
                        onBackgroundChange = ::onBackgroundChange,
                        onRequestFilePicker = {
                            log.e("onRequestFilePicker: $pageBackgroundType")
                            pickPdf.launch(arrayOf("application/pdf"))
                        }
                    )
                    PageNumberSelector(
                        currentBackground = pageBackground,
                        currentBackgroundType = pageBackgroundType,
                        maxPages = maxPages,
                        currentPage = currentPage,
                        onBackgroundChange = ::onBackgroundChange,
                        showAutoPdfOption = (notebookId != null) && ((maxPages
                            ?: 0) > pageNumberInBook),
                        isNotebookBgSelector = isNotebookBgSelector
                    )

                }

            }
        }
    }
}


@Composable
fun ShowNativeOption(
    currentBackground: String,
    currentBackgroundType: BackgroundType,
    onBackgroundChange: (String, BackgroundType) -> Unit
) {
    val nativeOptions = listOf(
        "blank" to stringResource(R.string.blank_page),
        "dotted" to stringResource(R.string.dot_grid),
        "lined" to stringResource(R.string.lines),
        "squared" to stringResource(R.string.small_squares_grid),
        "hexed" to stringResource(R.string.hexagon_grid)
    )

    val context = LocalContext.current
    val backgroundFolder = File(context.filesDir, "native_backgrounds")

    val bitmaps = remember {
        mutableStateMapOf<String, Bitmap?>()
    }

    LaunchedEffect(Unit) {
        if (!backgroundFolder.exists()) {
            backgroundFolder.mkdirs()
        }

        nativeOptions.forEach { (key, _) ->
            val file = File(backgroundFolder, "$key.png")
            if (!file.exists()) {
                val bitmap = createBitmap(300, 550)
                val canvas = Canvas(bitmap)

                when (key) {
                    "blank" -> canvas.drawColor(Color.White.toArgb())
                    "dotted" -> drawDottedBg(canvas, Offset.Zero, 1f)
                    "lined" -> drawLinedBg(canvas, Offset.Zero, 1f)
                    "squared" -> drawSquaredBg(canvas, Offset.Zero, 1f)
                    "hexed" -> drawHexedBg(canvas, Offset.Zero, 0.4f)
                }

                FileOutputStream(file).use { out ->
                    bitmap.compress(Bitmap.CompressFormat.PNG, 100, out)
                }

                bitmaps[key] = bitmap
            } else {
                val bitmap = BitmapFactory.decodeFile(file.absolutePath)
                bitmaps[key] = bitmap
            }
        }
    }

    OptionHeading(stringResource(R.string.choose_native_template))

    OptionGrid(minCell = 120.dp, maxHeight = 400.dp) {
        items(nativeOptions) { (value, label) ->
            val bitmap = bitmaps[value]

            OptionTile(
                label = label,
                selected = value == currentBackground,
                onClick = { onBackgroundChange(value, currentBackgroundType) },
            ) {
                if (bitmap != null) {
                    Image(
                        bitmap = bitmap.asImageBitmap(),
                        contentDescription = "Preview for $label",
                        contentScale = ContentScale.None,
                        modifier = Modifier
                            .fillMaxWidth()
                            .aspectRatio(0.75f) // maintain image proportion
                            .background(Color.White) // the page itself, not chrome
                    )
                } else {
                    PlaceholderFace(0.75f, FeatherIcons.Loader, stringResource(R.string.bg_preview_loading))
                }
            }
        }
    }
}


@Composable
fun ShowImageOption(
    currentBackground: String,
    currentBackgroundType: BackgroundType,
    onBackgroundChange: (String, BackgroundType) -> Unit,
    onRequestFilePicker: () -> Unit
) {
    val heading = when (currentBackgroundType) {
        BackgroundType.CoverImage -> R.string.bg_choose_cover
        BackgroundType.ImageRepeating -> R.string.bg_choose_repeating
        else -> R.string.bg_choose_image
    }
    OptionHeading(stringResource(heading))

    val baseOptions = listOf(
        Triple("iris", "Iris", painterResource(id = R.drawable.iris)),
    )

    val folderName = currentBackgroundType.folderName
    val folder = File(ensureBackgroundsFolder(), folderName)

    val uriOptions = folder.listFiles()?.filter { it.isFile }?.map { file ->
        Triple(file.absolutePath, file.nameWithoutExtension, null as Painter?)
    } ?: emptyList()

    val chooseFileOption = listOf(Triple("file", stringResource(R.string.bg_from_file), null))

    val options = baseOptions + uriOptions + chooseFileOption

    OptionGrid(minCell = 100.dp, maxHeight = 400.dp) {
        items(options) { (value, label, painter) ->
            OptionTile(
                label = label,
                selected = value == currentBackground,
                onClick = {
                    if (value == "file") {
                        onRequestFilePicker()
                    } else {
                        onBackgroundChange(value, currentBackgroundType)
                    }
                }) {
                if (painter != null) {
                    Image(
                        painter = painter,
                        contentDescription = label,
                        contentScale = ContentScale.Crop,
                        modifier = Modifier
                            .fillMaxWidth()
                            .aspectRatio(1f)
                    )
                } else if (value != "file") {
                    val bitmap = remember(value) {
                        BitmapFactory.decodeFile(value)?.asImageBitmap()
                    }

                    if (bitmap != null) {
                        Image(
                            bitmap = bitmap,
                            contentDescription = label,
                            contentScale = ContentScale.Crop,
                            modifier = Modifier
                                .fillMaxWidth()
                                .aspectRatio(1f)
                        )
                    } else {
                        PlaceholderFace(1f, FeatherIcons.Image, null)
                    }
                } else {
                    PlaceholderFace(1f, FeatherIcons.Plus, null)
                }
            }
        }
    }
}


@Composable
fun ShowPdfOption(
    currentBackground: String,
    currentBackgroundType: BackgroundType,
    onBackgroundChange: (BackgroundType, String) -> Unit,
    onRequestFilePicker: () -> Unit,
) {
    OptionHeading(stringResource(R.string.choose_pdf_background))

    val folderName = currentBackgroundType.folderName
    val folder = File(ensureBackgroundsFolder(), folderName)

    val uriOptions = folder.listFiles()?.filter { it.isFile }?.map { file ->
        Pair(file.absolutePath, file.nameWithoutExtension)
    } ?: emptyList()
    val pdfOptions = listOf(
        "file" to stringResource(R.string.bg_import_pdf)
    ) + uriOptions

    OptionGrid(minCell = 100.dp, maxHeight = 300.dp) {
        items(pdfOptions) { (value, label) ->
            OptionTile(
                label = label,
                selected = value == currentBackground,
                onClick = {
                    if (value == "file") {
                        onRequestFilePicker()
                    } else {
                        onBackgroundChange(currentBackgroundType, value)
                    }
                },
            ) {
                PlaceholderFace(1f, if (value == "file") FeatherIcons.Plus else FeatherIcons.FileText, null)
            }
        }
    }
}

@Composable
fun PageNumberSelector(
    currentBackground: String,
    currentBackgroundType: BackgroundType,
    maxPages: Int?,
    currentPage: Int?,
    onBackgroundChange: (BackgroundType, String) -> Unit,
    showAutoPdfOption: Boolean,
    isNotebookBgSelector: Boolean
) {
    val pdfSelectorEnabled =
        (currentBackground.endsWith(".pdf") || com.ethran.notable.io.EpubBackground.isEpub(currentBackground)) && maxPages != null && currentPage != null

    Box(
        modifier = Modifier
            .fillMaxWidth()
            .graphicsLayer(alpha = if (pdfSelectorEnabled) 1f else 0.5f)
    ) {
        Column {
            Spacer(Modifier.height(NotableSpace.lg))
            Text(stringResource(R.string.select_pdf_page), style = NotableTheme.type.label, color = NotableTheme.colors.ink)
            Spacer(Modifier.height(NotableSpace.sm))

            var pageText by remember {
                mutableStateOf(
                    ((currentPage ?: (0 + 1))).toString()
                )
            }
            val pageNumber = pageText.toIntOrNull() ?: 1

            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(NotableSpace.sm),
                modifier = Modifier
                    .fillMaxWidth()
                    .autoEInkAnimationOnScroll()
            ) {
                NIconButton(
                    FeatherIcons.Minus,
                    stringResource(R.string.bg_previous_page),
                    outlined = true,
                    onClick = {
                        if (pageNumber > 1) {
                            val newPage = (pageNumber - 1).coerceAtMost(maxPages ?: 1)
                            pageText = newPage.toString()
                            onBackgroundChange(
                                BackgroundType.Pdf(newPage - 1), currentBackground,

                                )
                        }
                    })

                NTextField(
                    value = pageText,
                    onValueChange = {
                        val input = it.toIntOrNull()
                        if (input != null && input in 1..(maxPages ?: 1)) {
                            pageText = input.toString()
                            onBackgroundChange(
                                BackgroundType.Pdf(input - 1),
                                currentBackground,

                                )
                        } else {
                            pageText = it
                        }
                    },
                    placeholder = stringResource(R.string.page_number),
                    keyboardOptions = KeyboardOptions.Default.copy(
                        keyboardType = KeyboardType.Number, imeAction = ImeAction.Done
                    ),
                    modifier = Modifier.width(96.dp)
                )

                NIconButton(
                    FeatherIcons.Plus,
                    stringResource(R.string.bg_next_page),
                    outlined = true,
                    onClick = {
                        if (pageNumber < (maxPages ?: 1)) {
                            val newPage = (pageNumber + 1).coerceAtLeast(1)
                            pageText = newPage.toString()
                            onBackgroundChange(
                                BackgroundType.Pdf(newPage - 1),
                                currentBackground,

                                )
                        }
                    })
                if (showAutoPdfOption) {
                    Spacer(Modifier.width(NotableSpace.sm))

                    var isSelected = currentBackgroundType == BackgroundType.AutoPdf

                    val name =
                        if (isNotebookBgSelector) stringResource(R.string.observe_pdf) else stringResource(
                            R.string.current_page
                        )
                    NButton(
                        name,
                        onClick = {
                            if (!isSelected) {
                                onBackgroundChange(
                                    BackgroundType.AutoPdf,
                                    currentBackground,
                                )
                                isSelected = true
                            } else {
                                onBackgroundChange(
                                    BackgroundType.Pdf(pageNumber),
                                    currentBackground,
                                )
                                isSelected = false
                            }
                        },
                        icon = FeatherIcons.RefreshCw,
                        selected = isSelected,
                    )
                    log.d("PageNumberSelector: $isSelected, $currentBackgroundType")
                }
            }

            Text(
                pluralStringResource(
                    R.plurals.pdf_has_pages,
                    maxPages ?: 0,
                    maxPages ?: 0
                ),
                style = NotableTheme.type.meta,
                color = NotableTheme.colors.secondary,
                modifier = Modifier.padding(top = NotableSpace.sm)
            )

        }
    }
}

// ───────────────────────────── Option tiles ─────────────────────────────

@Composable
private fun OptionHeading(text: String) {
    Text(text, style = NotableTheme.type.label, color = NotableTheme.colors.ink,
        modifier = Modifier.padding(bottom = NotableSpace.sm))
}

@Composable
private fun OptionGrid(minCell: Dp, maxHeight: Dp, content: LazyGridScope.() -> Unit) {
    LazyVerticalGrid(
        columns = GridCells.Adaptive(minCell),
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(max = maxHeight),
        horizontalArrangement = Arrangement.spacedBy(NotableSpace.md),
        verticalArrangement = Arrangement.spacedBy(NotableSpace.md),
        content = content,
    )
}

/**
 * A template, image or PDF to choose: its face as paper (square corners), a 2 dp ink outline when
 * chosen and a hairline otherwise, the name under it.
 */
@Composable
private fun OptionTile(label: String, selected: Boolean, onClick: () -> Unit, face: @Composable () -> Unit) {
    val c = NotableTheme.colors
    Column(
        Modifier.inkClickable(role = Role.RadioButton, onClick = onClick)
            .semantics { this.selected = selected },
    ) {
        Box(
            Modifier.fillMaxWidth()
                .border(if (selected) NotableSize.strong else NotableSize.hairline, if (selected) c.ink else c.rule, NotableShape.paper)
                .padding(if (selected) NotableSize.strong else NotableSize.hairline),
            contentAlignment = Alignment.Center,
        ) { face() }
        Text(
            label,
            style = if (selected) NotableTheme.type.label else NotableTheme.type.meta,
            color = if (selected) c.ink else c.secondary,
            maxLines = 2, overflow = TextOverflow.Ellipsis,
            modifier = Modifier.fillMaxWidth().padding(top = NotableSpace.xs),
        )
    }
}

/** A quiet inset face for options without a preview (a file to import, a PDF, a preview still loading). */
@Composable
private fun PlaceholderFace(aspect: Float, icon: ImageVector, description: String?) {
    Box(
        Modifier.fillMaxWidth().aspectRatio(aspect).background(NotableTheme.colors.inset),
        contentAlignment = Alignment.Center,
    ) {
        NIcon(icon, contentDescription = description, tint = NotableTheme.colors.secondary, size = NotableSize.iconButtonIcon)
    }
}
