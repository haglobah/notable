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
import junit.framework.TestCase.assertEquals
import junit.framework.TestCase.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.io.IOException

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
}
