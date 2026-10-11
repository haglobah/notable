package com.ethran.notable.sync.automerge

import android.app.ActivityManager
import android.app.ApplicationExitInfo
import android.content.Context
import android.net.Uri
import android.os.Build
import android.os.PowerManager
import com.ethran.notable.data.db.CryptoHelper
import com.ethran.notable.data.db.FolderDao
import com.ethran.notable.data.db.NotebookDao
import com.ethran.notable.data.db.PageDao
import com.ethran.notable.utils.AppResult
import kotlinx.coroutines.runBlocking
import org.automerge.Document
import org.automerge.repo.DocHandle
import org.automerge.repo.Repo
import java.io.InputStream
import java.util.concurrent.TimeUnit

/** [SecretCipher] backed by the app's Keystore AES-GCM key ([CryptoHelper]). */
internal class KeystoreCipher(private val crypto: CryptoHelper) : SecretCipher {
    override fun encrypt(plain: String) = when (val r = crypto.encrypt(plain)) {
        is AppResult.Success -> r.data
        is AppResult.Error -> throw IllegalStateException(r.error.userMessage)
    }

    override fun decrypt(sealed: String) = when (val r = crypto.decrypt(sealed)) {
        is AppResult.Success -> r.data
        is AppResult.Error -> throw IllegalStateException(r.error.userMessage)
    }
}

/** Room as the migration's source. Blocking; runs on the migration's IO thread. */
internal class RoomLocalLibrary(
    private val folderDao: FolderDao,
    private val notebookDao: NotebookDao,
    private val pageDao: PageDao,
) : LocalLibrary {
    override fun folders() = folderDao.getAll()
    override fun notebooks() = notebookDao.getAll()
    override fun quickPages() = runBlocking { pageDao.getAllQuickPages() }
    override fun page(pageId: String) = runBlocking { pageDao.getPageWithDataById(pageId) }
    override fun pageRow(pageId: String) = runBlocking { pageDao.getById(pageId) }
    override fun notebook(id: String) = runBlocking { notebookDao.getById(id) }
}

/**
 * Opens files referenced by Room rows: absolute paths in shared storage, or `content://` /
 * `file://` URIs (linked PDFs, picked images).
 */
internal fun androidFileOpener(context: Context): (String) -> InputStream = { pathOrUri ->
    try {
        if (pathOrUri.startsWith("content://") || pathOrUri.startsWith("file://")) {
            context.contentResolver.openInputStream(Uri.parse(pathOrUri))
                ?: throw MissingFileException(pathOrUri)
        } else {
            openLocalFile(pathOrUri)
        }
    } catch (e: SecurityException) {
        throw MissingFileException("$pathOrUri (no permission: ${e.message})")
    }
}

/** The account's repo: the library doc found by URL, new notebook docs created in it. */
internal class RepoDocStore(private val repo: Repo, private val library: DocHandle) : DocStore {
    override fun library(): AmLibrary =
        library.withDocument { AutomergeSchema.readLibrary(it) }.get(TIMEOUT_S, TimeUnit.SECONDS)

    override fun createDoc(doc: Document): String =
        repo.create(doc.save()).get(TIMEOUT_S, TimeUnit.SECONDS).url.toString()

    override fun editLibrary(edit: (Document) -> Unit) {
        library.withDocument { edit(it) }.get(TIMEOUT_S, TimeUnit.SECONDS)
    }

    private companion object {
        const val TIMEOUT_S = 120L
    }
}

/** Reads what Android knows about our background limits; see [BackgroundHealth]. */
internal fun readBackgroundSignals(context: Context): BackgroundSignals {
    val am = context.getSystemService(ActivityManager::class.java)
    val pm = context.getSystemService(PowerManager::class.java)
    val exits = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
        val weekAgo = System.currentTimeMillis() - TimeUnit.DAYS.toMillis(7)
        am.getHistoricalProcessExitReasons(context.packageName, 0, 10)
            .filter { it.timestamp >= weekAgo }
            .map { if (it.reason == ApplicationExitInfo.REASON_PACKAGE_STATE_CHANGE) ExitKind.PACKAGE_STATE_CHANGE else ExitKind.OTHER }
    } else emptyList()
    return BackgroundSignals(
        backgroundRestricted = am.isBackgroundRestricted,
        ignoringBatteryOptimizations = pm.isIgnoringBatteryOptimizations(context.packageName),
        recentExits = exits,
        onyxDevice = Build.MANUFACTURER.equals("ONYX", ignoreCase = true) || Build.BRAND.equals("ONYX", ignoreCase = true),
    )
}
