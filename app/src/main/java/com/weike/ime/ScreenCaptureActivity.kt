package com.weike.ime

import android.app.Activity
import android.content.Intent
import android.media.projection.MediaProjectionManager
import android.os.Bundle

/**
 * One-tap screenshot trampoline for 截图识别回复: requests the system
 * projection consent (once per tap), then hands the grant to
 * [ScreenCaptureService], which grabs a single frame and returns it through
 * the same ACTION_IMAGE_PICKED plumbing the gallery picker uses.
 */
class ScreenCaptureActivity : Activity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val cropBottom = intent.getIntExtra(EXTRA_CROP_BOTTOM, 0)
        val manager = getSystemService(MediaProjectionManager::class.java)
        startActivityForResult(manager.createScreenCaptureIntent(), REQUEST_CODE)
        pendingCropBottom = cropBottom
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == REQUEST_CODE && resultCode == RESULT_OK && data != null) {
            val service = Intent(this, ScreenCaptureService::class.java)
                .putExtra(ScreenCaptureService.EXTRA_RESULT_CODE, resultCode)
                .putExtra(ScreenCaptureService.EXTRA_RESULT_DATA, data)
                .putExtra(ScreenCaptureService.EXTRA_CROP_BOTTOM, pendingCropBottom)
            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O) {
                startForegroundService(service)
            } else {
                startService(service)
            }
        }
        finish()
    }

    companion object {
        private const val REQUEST_CODE = 7001
        private var pendingCropBottom = 0
        /** IME passes its own window height so the keyboard area can be cropped out. */
        const val EXTRA_CROP_BOTTOM = "cropBottom"
    }
}
