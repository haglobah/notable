package com.ethran.notable.db

import android.content.Context
import androidx.room.Room
import androidx.room.testing.MigrationTestHelper
import androidx.sqlite.db.framework.FrameworkSQLiteOpenHelperFactory
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.ethran.notable.data.db.AppDatabase
import com.ethran.notable.data.db.MIGRATION_32_33
import com.ethran.notable.data.db.MIGRATION_37_38
import com.ethran.notable.data.model.BookMeta
import junit.framework.TestCase.assertEquals
import junit.framework.TestCase.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.io.IOException
import java.util.Date

@RunWith(AndroidJUnit4::class)
class MigrationTest {

    @Test(timeout = 10000)
    fun simpleTest() {
        assertTrue(true)
    }

    private val context: Context = ApplicationProvider.getApplicationContext()

    @get:Rule
    val helper: MigrationTestHelper = MigrationTestHelper(
        InstrumentationRegistry.getInstrumentation(),
        AppDatabase::class.java,
        listOf(), // Add AutoMigrationSpecs here if any
        FrameworkSQLiteOpenHelperFactory()
    )

    @Test(timeout = 60000)
    @Throws(IOException::class)
    fun migrate30To31_autoMigration() {
        val dbName = "migration-test"

        // 1. Create DB with version 30 schema
        val db = helper.createDatabase(dbName, 30)

// Insert required parent data first
        db.execSQL(
            """
    INSERT INTO Notebook (
        id,
        title,
        openPageId,
        pageIds,
        parentFolderId,
        defaultNativeTemplate,
        createdAt,
        updatedAt
    ) VALUES (
        'notebook1',
        'Test Notebook',
        NULL,
        '[]',
        NULL,
        'blank',
        1620000000000,
        1620000000000
    )
    """.trimIndent()
        )


        db.execSQL(
            """
    INSERT INTO Folder (id, title, createdAt, updatedAt)
    VALUES ('TEST_FOLDER_ID', 'Test Folder', 1620000000, 1620000000)
    """.trimIndent()
        )

        // Insert with column name from version 30: 'nativeTemplate'
        db.execSQL(
            """
    INSERT INTO Page (
        id,
        notebookId,
        nativeTemplate,
        parentFolderId,
        scroll,
        createdAt,
        updatedAt
    ) VALUES (
        'page1',
        'notebook1',
        'grid',
        'TEST_FOLDER_ID',
        0.0,
        1620000000,
        1620000000
    )
    """.trimIndent()
        )


        db.close()

        // 2. Reopen at the latest version, with the app's manual migrations, to trigger migration
        val roomDb = Room.databaseBuilder(context, AppDatabase::class.java, dbName)
            .addMigrations(MIGRATION_32_33, MIGRATION_37_38)
            .build()
        val migratedDb = roomDb.openHelper.writableDatabase

        // 3. Verify renamed column exists with expected data
        val cursor = migratedDb.query("SELECT background FROM Page WHERE id = 'page1'")
        cursor.use {
            assertTrue(it.moveToFirst())
            val background = it.getString(it.getColumnIndexOrThrow("background"))
            assertEquals("grid", background)
        }

        roomDb.close()
    }

    /**
     * 37 -> 38 removes WebDAV sync: it drops `notebook_sync_state` and `page_sync_state`, and deletes
     * the stored WebDAV settings (server, user, encrypted password) and measured server capabilities
     * from `Kv`. Notebooks, pages, strokes, folders and the other `Kv` rows stay as they are.
     */
    @Test(timeout = 60000)
    @Throws(IOException::class)
    fun migrate37To38_dropsWebDavTablesAndSettings() {
        val dbName = "migration-test-38"

        val oldDb = helper.createDatabase(dbName, 37)
        oldDb.execSQL(
            "INSERT INTO Folder (id, title, createdAt, updatedAt) VALUES ('f1', 'Folder', 1, 1)"
        )
        oldDb.execSQL(
            """
            INSERT INTO Notebook (id, title, pageIds, parentFolderId, defaultBackground,
                defaultBackgroundType, createdAt, updatedAt)
            VALUES ('nb1', 'Book', '["p1"]', 'f1', 'blank', 'native', 1, 2)
            """.trimIndent()
        )
        oldDb.execSQL(
            """
            INSERT INTO Page (id, scroll, notebookId, background, backgroundType, createdAt, updatedAt)
            VALUES ('p1', 0, 'nb1', 'blank', 'native', 1, 2)
            """.trimIndent()
        )
        oldDb.execSQL(
            """
            INSERT INTO Stroke (id, size, pen, top, bottom, `left`, `right`, points, pageId,
                createdAt, updatedAt)
            VALUES ('s1', 2.0, 'BALLPEN', 0, 10, 0, 10, X'00', 'p1', 1, 2)
            """.trimIndent()
        )
        oldDb.execSQL("INSERT INTO Kv (key, value) VALUES ('APP_SETTINGS', '{\"version\":1}')")
        oldDb.execSQL(
            "INSERT INTO Kv (key, value) VALUES ('SYNC_SETTINGS', " +
                "'{\"syncEnabled\":true,\"serverUrl\":\"https://dav\",\"username\":\"u\",\"password\":\"enc\"}')"
        )
        oldDb.execSQL("INSERT INTO Kv (key, value) VALUES ('SYNC_SERVER_CAPABILITIES', '{}')")
        oldDb.execSQL(
            "INSERT INTO notebook_sync_state (notebookId, state, lastSyncedAt, syncedLocalUpdatedAt) " +
                "VALUES ('nb1', 'SYNCED', 1, 2)"
        )
        oldDb.execSQL(
            "INSERT INTO page_sync_state (pageId, notebookId, syncedLocalUpdatedAt, lastSyncedAt) " +
                "VALUES ('p1', 'nb1', 2, 1)"
        )
        oldDb.close()

        val db = helper.runMigrationsAndValidate(dbName, 38, true, MIGRATION_37_38)

        db.query(
            "SELECT name FROM sqlite_master WHERE type = 'table' " +
                "AND name IN ('notebook_sync_state', 'page_sync_state')"
        ).use { assertEquals(0, it.count) }
        db.query("SELECT key FROM Kv ORDER BY key").use {
            val keys = buildList { while (it.moveToNext()) add(it.getString(0)) }
            assertEquals(listOf("APP_SETTINGS"), keys)
        }
        db.query("SELECT title, pageIds, parentFolderId FROM Notebook WHERE id = 'nb1'").use {
            assertTrue(it.moveToFirst())
            assertEquals("Book", it.getString(0))
            assertEquals("[\"p1\"]", it.getString(1))
            assertEquals("f1", it.getString(2))
        }
        db.query("SELECT count(*) FROM Page WHERE notebookId = 'nb1'").use {
            assertTrue(it.moveToFirst()); assertEquals(1, it.getInt(0))
        }
        db.query("SELECT count(*) FROM Stroke WHERE pageId = 'p1'").use {
            assertTrue(it.moveToFirst()); assertEquals(1, it.getInt(0))
        }
        db.query("SELECT count(*) FROM Folder").use {
            assertTrue(it.moveToFirst()); assertEquals(1, it.getInt(0))
        }
        db.close()
    }

    /**
     * 38 -> 39 adds the book columns `bookMeta` (JSON), `readAt` and `finishedAt` to `Notebook`
     * (docs/plans/ebook-features.md). Existing rows keep their data and read as no metadata,
     * never opened, not finished; the new columns then store and read back through Room.
     */
    @Test(timeout = 60000)
    @Throws(IOException::class)
    fun migrate38To39_addsBookColumns() {
        val dbName = "migration-test-39"
        val oldDb = helper.createDatabase(dbName, 38)
        oldDb.execSQL("INSERT INTO Folder (id, title, createdAt, updatedAt) VALUES ('f1', 'Folder', 1, 1)")
        oldDb.execSQL(
            """
            INSERT INTO Notebook (id, title, openPageId, pageIds, parentFolderId, defaultBackground,
                defaultBackgroundType, createdAt, updatedAt)
            VALUES ('nb1', 'Book', 'p1', '["p1"]', 'f1', '/b/book.pdf', 'autoPdf', 1, 2)
            """.trimIndent()
        )
        oldDb.execSQL(
            """
            INSERT INTO Page (id, scroll, notebookId, background, backgroundType, createdAt, updatedAt)
            VALUES ('p1', 0, 'nb1', '/b/book.pdf', 'autoPdf', 1, 2)
            """.trimIndent()
        )
        oldDb.close()

        val db = helper.runMigrationsAndValidate(dbName, 39, true)
        db.query("SELECT title, openPageId, pageIds, defaultBackground, bookMeta, readAt, finishedAt FROM Notebook WHERE id = 'nb1'").use {
            assertTrue(it.moveToFirst())
            assertEquals("Book", it.getString(0))
            assertEquals("p1", it.getString(1))
            assertEquals("[\"p1\"]", it.getString(2))
            assertEquals("/b/book.pdf", it.getString(3))
            assertTrue(it.isNull(4)); assertTrue(it.isNull(5)); assertTrue(it.isNull(6))
        }
        db.query("SELECT count(*) FROM Page WHERE notebookId = 'nb1'").use {
            assertTrue(it.moveToFirst()); assertEquals(1, it.getInt(0))
        }
        db.close()

        val roomDb = Room.databaseBuilder(context, AppDatabase::class.java, dbName)
            .addMigrations(MIGRATION_32_33, MIGRATION_37_38).build()
        try {
            kotlinx.coroutines.runBlocking {
                val dao = roomDb.notebookDao()
                val nb = dao.getById("nb1")!!
                assertEquals(null, nb.bookMeta); assertEquals(null, nb.readAt); assertEquals(null, nb.finishedAt)
                val meta = BookMeta(authors = listOf("Ursula K. Le Guin"), seriesIndex = 4.0, subjects = listOf("Gender"))
                dao.upsert(nb.copy(bookMeta = meta))
                assertEquals(1, dao.setReadAt("nb1", Date(5)))
                assertEquals(1, dao.setFinishedAt("nb1", Date(6)))
                val back = dao.getById("nb1")!!
                assertEquals(meta, back.bookMeta)
                assertEquals(Date(5), back.readAt)
                assertEquals(Date(6), back.finishedAt)
                assertEquals(Date(2), back.updatedAt)
                assertEquals(1, dao.setFinishedAt("nb1", null))
                assertEquals(null, dao.getById("nb1")!!.finishedAt)
            }
        } finally {
            roomDb.close()
        }
    }

    /** 39 -> 40 adds `rating` and `pageCount` to `Notebook`; rows keep their book fields, both read as null. */
    @Test(timeout = 60000)
    @Throws(IOException::class)
    fun migrate39To40_addsRatingAndPageCount() {
        val dbName = "migration-test-40"
        val oldDb = helper.createDatabase(dbName, 39)
        oldDb.execSQL(
            """
            INSERT INTO Notebook (id, title, openPageId, pageIds, defaultBackground, defaultBackgroundType,
                createdAt, updatedAt, bookMeta, readAt, finishedAt)
            VALUES ('nb1', 'Book', 'p1', '["p1"]', 'blank', 'native', 1, 2, '{"authors":["A"]}', 3, 4)
            """.trimIndent()
        )
        oldDb.close()
        val db = helper.runMigrationsAndValidate(dbName, 40, true)
        db.query("SELECT bookMeta, readAt, finishedAt, rating, pageCount FROM Notebook WHERE id = 'nb1'").use {
            assertTrue(it.moveToFirst())
            assertEquals("{\"authors\":[\"A\"]}", it.getString(0))
            assertEquals(3L, it.getLong(1)); assertEquals(4L, it.getLong(2))
            assertTrue(it.isNull(3)); assertTrue(it.isNull(4))
        }
        db.close()
        val roomDb = Room.databaseBuilder(context, AppDatabase::class.java, dbName)
            .addMigrations(MIGRATION_32_33, MIGRATION_37_38).build()
        try {
            kotlinx.coroutines.runBlocking {
                val dao = roomDb.notebookDao()
                assertEquals(1, dao.setRating("nb1", 8))
                assertEquals(1, dao.setPageCount("nb1", 1))
                val nb = dao.getById("nb1")!!
                assertEquals(8, nb.rating); assertEquals(1, nb.pageCount); assertEquals(Date(2), nb.updatedAt)
            }
        } finally {
            roomDb.close()
        }
    }

    /**
     * 40 -> 41 adds `queuedAt` and `trashedAt` to `Notebook` and `trashedAt` to `Folder`
     * (docs/plans/library-features.md); rows keep their fields, the new ones read as null.
     */
    @Test(timeout = 60000)
    @Throws(IOException::class)
    fun migrate40To41_addsQueueAndTrashColumns() {
        val dbName = "migration-test-41"
        val oldDb = helper.createDatabase(dbName, 40)
        oldDb.execSQL("INSERT INTO Folder (id, title, parentFolderId, createdAt, updatedAt) VALUES ('f1', 'F', NULL, 1, 2)")
        oldDb.execSQL(
            """
            INSERT INTO Notebook (id, title, openPageId, pageIds, parentFolderId, defaultBackground, defaultBackgroundType,
                createdAt, updatedAt, rating)
            VALUES ('nb1', 'Book', 'p1', '["p1"]', 'f1', 'blank', 'native', 1, 2, 7)
            """.trimIndent()
        )
        oldDb.close()
        val db = helper.runMigrationsAndValidate(dbName, 41, true)
        db.query("SELECT rating, queuedAt, trashedAt FROM Notebook WHERE id = 'nb1'").use {
            assertTrue(it.moveToFirst())
            assertEquals(7, it.getInt(0)); assertTrue(it.isNull(1)); assertTrue(it.isNull(2))
        }
        db.query("SELECT trashedAt FROM Folder WHERE id = 'f1'").use {
            assertTrue(it.moveToFirst()); assertTrue(it.isNull(0))
        }
        db.close()
        val roomDb = Room.databaseBuilder(context, AppDatabase::class.java, dbName)
            .addMigrations(MIGRATION_32_33, MIGRATION_37_38).build()
        try {
            kotlinx.coroutines.runBlocking {
                assertEquals(1, roomDb.notebookDao().setQueuedAt("nb1", Date(5)))
                assertEquals(1, roomDb.notebookDao().setTrashedAt("nb1", Date(6)))
                assertEquals(1, roomDb.folderDao().setTrashedAt("f1", Date(7)))
                val nb = roomDb.notebookDao().getById("nb1")!!
                assertEquals(Date(5), nb.queuedAt); assertEquals(Date(6), nb.trashedAt); assertEquals(Date(2), nb.updatedAt)
                assertEquals(Date(7), roomDb.folderDao().get("f1")!!.trashedAt)
            }
        } finally {
            roomDb.close()
        }
    }
}
