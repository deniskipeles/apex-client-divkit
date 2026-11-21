package com.example

import engine.fs.FileOpenWith
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.io.File

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class FileOpenWithTest {

    @Test
    fun mapsKnownExtensionsToMimeTypes() {
        assertEquals("text/plain", FileOpenWith.mimeTypeFor("notes.txt"))
        assertEquals("application/json", FileOpenWith.mimeTypeFor("card.JSON"))
        assertEquals("image/png", FileOpenWith.mimeTypeFor("code_ab12.png"))
        assertEquals("text/csv", FileOpenWith.mimeTypeFor("export.csv"))
    }

    @Test
    fun fallsBackToWildcardForUnknownExtensions() {
        assertEquals("*/*", FileOpenWith.mimeTypeFor("archive.qqq"))
        assertEquals("*/*", FileOpenWith.mimeTypeFor("noextension"))
    }

    /**
     * Provider roots are resolved once per authority, so both the shareable and the private
     * case are asserted against the same application instance.
     */
    @Test
    fun sharesWorkspaceFilesButNotPrivateCaches() {
        val context = RuntimeEnvironment.getApplication()

        val mediaDir = File(context.filesDir, "workspaces/acme_pos/media").apply { mkdirs() }
        val capture = File(mediaDir, "capture.png").apply { writeBytes(byteArrayOf(1, 2, 3)) }
        val shared = FileOpenWith.contentUri(context, capture)
        assertNotNull("workspace files must be shareable", shared)
        assertEquals("content", shared!!.scheme)
        assertEquals("${context.packageName}.fileprovider", shared.authority)

        val cached = File(context.cacheDir, "scratch.bin").apply { writeBytes(byteArrayOf(9)) }
        assertNull("caches must stay private", FileOpenWith.contentUri(context, cached))
    }

    @Test
    fun missingFileReportsFailureInsteadOfLaunching() {
        val context = RuntimeEnvironment.getApplication()
        val missing = File(context.filesDir, "workspaces/acme_pos/media/gone.png")
        assertEquals(true, FileOpenWith.open(context, missing) is FileOpenWith.Outcome.Failed)
    }
}
