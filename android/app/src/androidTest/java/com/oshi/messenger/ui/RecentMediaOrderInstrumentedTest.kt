package com.oshi.messenger.ui

import android.content.ContentUris
import android.content.ContentValues
import android.graphics.Bitmap
import android.net.Uri
import android.os.Build
import android.provider.MediaStore
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.oshi.messenger.ui.components.attach.RecentMedia
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * The attachment panel's "Recent" grid must open on the NEWEST photo.
 *
 * Regression (user report 21/11, 1.7.x): the panel showed photos from a month ago.
 * The query asked MediaStore for `QUERY_ARG_SORT_COLUMNS = [date_added, _id]` with
 * `QUERY_ARG_SORT_DIRECTION = DESCENDING`; the platform turns that into
 * `date_added, _id DESC` — the direction lands on the LAST column only, so the grid
 * was sorted OLDEST first. This test inserts its own photos (no gallery permission
 * needed for an app's own rows) and reads them back through the panel's real query.
 */
@RunWith(AndroidJUnit4::class)
class RecentMediaOrderInstrumentedTest {

    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private val inserted = mutableListOf<Uri>()

    private fun insertPhoto(name: String, takenMs: Long?): Long {
        val cr = context.contentResolver
        val values = ContentValues().apply {
            put(MediaStore.Images.Media.DISPLAY_NAME, name)
            put(MediaStore.Images.Media.MIME_TYPE, "image/png")
            put(MediaStore.Images.Media.RELATIVE_PATH, "Pictures/OshiRecentTest")
            put(MediaStore.Images.Media.IS_PENDING, 1)
            if (takenMs != null) put(MediaStore.Images.Media.DATE_TAKEN, takenMs)
        }
        val uri = cr.insert(MediaStore.Images.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY), values)!!
        inserted += uri
        cr.openOutputStream(uri)!!.use {
            Bitmap.createBitmap(4, 4, Bitmap.Config.ARGB_8888).compress(Bitmap.CompressFormat.PNG, 100, it)
        }
        cr.update(uri, ContentValues().apply {
            put(MediaStore.Images.Media.IS_PENDING, 0)
            if (takenMs != null) put(MediaStore.Images.Media.DATE_TAKEN, takenMs)
        }, null, null)
        return ContentUris.parseId(uri)
    }

    /** Read the WHOLE gallery, as a user with full access does — not only our own rows. */
    @Before
    fun grantGallery() {
        if (Build.VERSION.SDK_INT >= 33) {
            val ua = InstrumentationRegistry.getInstrumentation().uiAutomation
            ua.grantRuntimePermission(context.packageName, "android.permission.READ_MEDIA_IMAGES")
            ua.grantRuntimePermission(context.packageName, "android.permission.READ_MEDIA_VIDEO")
        }
    }

    @After
    fun cleanUp() {
        inserted.forEach { runCatching { context.contentResolver.delete(it, null, null) } }
    }

    @Test
    fun recentGridOpensOnTheNewestPhoto() = runBlocking {
        // Inserted in time order; DATE_ADDED is whole seconds, hence the waits.
        val older = insertPhoto("oshi_recent_old_${System.nanoTime()}.png", null)
        Thread.sleep(1_100)
        val middle = insertPhoto("oshi_recent_mid_${System.nanoTime()}.png", null)
        Thread.sleep(1_100)
        val newest = insertPhoto("oshi_recent_new_${System.nanoTime()}.png", null)

        val ours = setOf(older, middle, newest)
        val page = RecentMedia.page(context, 0, limit = 500).map { it.id }.filter { it in ours }
        assertEquals(listOf(newest, middle, older), page)
        // And they are the very first tiles of the whole gallery: nothing else is newer.
        assertEquals(newest, RecentMedia.page(context, 0, limit = 1).single().id)
    }
}
