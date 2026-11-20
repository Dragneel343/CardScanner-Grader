package com.inspectorade.cardscanner

import android.graphics.Bitmap
import android.os.SystemClock
import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.inspectorade.cardscanner.surface.GlareFusion
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.opencv.android.OpenCVLoader
import java.io.File

/**
 * Runs [GlareFusion] on saved tilt scans: each folder in the app's `scans/`
 * folder holds one scan's frames, as the capture saves them (copied off the
 * phone from the app's cache). Logs (tags "SurfaceScan", "GlareFusion",
 * "CardAligner") and saves the views to `fixture_out/scans/<scan>/`.
 */
@RunWith(AndroidJUnit4::class)
class SurfaceScanFixturesTest {

    @Test
    fun fuseScans() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        // Folders adb creates there can't be read by the app, so frames are pushed
        // flat, named "<scan>__<frame name>", and grouped back here.
        val pushed = File(context.getExternalFilesDir(null)!!, "scans").apply { mkdirs() }
            .listFiles { f -> f.extension == "jpg" && "__" in f.name }.orEmpty()
        val scans = pushed.groupBy { it.name.substringBefore("__") }.toSortedMap()
        assumeTrue("No scans", scans.isNotEmpty())
        check(OpenCVLoader.initLocal())

        for ((name, files) in scans) {
            val scan = File(context.cacheDir, "scan_fixtures/$name").apply { deleteRecursively(); mkdirs() }
            val frames = files.map { file -> File(scan, file.name.substringAfter("__")).also { file.copyTo(it) } }
            val start = SystemClock.elapsedRealtime()
            val fused = GlareFusion.fuse(frames)
            if (fused == null) {
                Log.i(TAG, "$name: nothing fused")
                continue
            }
            Log.i(TAG, "$name: ${fused.framesUsed} used, ${fused.framesDropped} left out of ${frames.size} " +
                "(${SystemClock.elapsedRealtime() - start} ms)")
            val out = File(context.getExternalFilesDir(null)!!, "fixture_out/scans/$name").apply { deleteRecursively(); mkdirs() }
            listOf("glare_free" to fused.glareFree, "shine" to fused.shine, "reference" to fused.reference).forEach { (name, bitmap) ->
                File(out, "$name.png").outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
            }
        }
    }

    private companion object {
        const val TAG = "SurfaceScan"
    }
}
