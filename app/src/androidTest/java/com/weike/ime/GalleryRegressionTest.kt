package com.weike.ime

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.SystemClock
import android.view.View
import android.view.ViewGroup
import android.widget.GridView
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.runner.AndroidJUnit4
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith

/** Read-only gallery regression; never selects or uploads any photo. */
@RunWith(AndroidJUnit4::class)
class GalleryRegressionTest {
    @Test fun largeGalleryRecyclesCellsAndBackClosesPicker() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        val permission = if (Build.VERSION.SDK_INT >= 33) Manifest.permission.READ_MEDIA_IMAGES
            else Manifest.permission.READ_EXTERNAL_STORAGE
        assumeTrue(context.checkSelfPermission(permission) == PackageManager.PERMISSION_GRANTED)
        // MIUI blocks a background instrumentation process from opening a
        // private activity. Bring the normal launcher activity forward first.
        val launch = instrumentation.uiAutomation.executeShellCommand(
            "am start -W -n ${context.packageName}/com.weike.ime.MainActivity"
        )
        android.os.ParcelFileDescriptor.AutoCloseInputStream(launch).use { it.readBytes() }
        SystemClock.sleep(1_000)
        val activity = instrumentation.startActivitySync(
            Intent(context, ImagePickerActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        ) as ImagePickerActivity
        fun findGrid(view: View): GridView? {
            if (view is GridView) return view
            if (view is ViewGroup) for (i in 0 until view.childCount) {
                findGrid(view.getChildAt(i))?.let { return it }
            }
            return null
        }
        var grid: GridView? = null
        try {
            val deadline = SystemClock.uptimeMillis() + 10_000
            while (SystemClock.uptimeMillis() < deadline) {
                var ready = false
                instrumentation.runOnMainSync {
                    grid = findGrid(activity.window.decorView)
                    ready = grid?.adapter != null
                }
                if (ready) break
                SystemClock.sleep(100)
            }
            instrumentation.waitForIdleSync()
            instrumentation.runOnMainSync {
                val gallery = requireNotNull(grid)
                val count = gallery.adapter.count
                assumeTrue("Needs a large photo library to exercise recycling", count > 64)
                assertTrue("Create visible cells rather than all photos", gallery.childCount in 1..64)
                gallery.setSelection(count - 1)
            }
            instrumentation.waitForIdleSync()
            instrumentation.runOnMainSync {
                assertTrue("Scrolled cells must also stay bounded", requireNotNull(grid).childCount in 1..64)
                activity.onBackPressedDispatcher.onBackPressed()
                assertTrue("Back must close the picker", activity.isFinishing)
            }
        } finally {
            instrumentation.runOnMainSync { if (!activity.isFinishing) activity.finish() }
        }
    }
}
