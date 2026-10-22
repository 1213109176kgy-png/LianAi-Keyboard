package com.weike.ime

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.graphics.Bitmap
import android.graphics.PixelFormat
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.Image
import android.media.ImageReader
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.Handler
import android.os.HandlerThread
import android.os.IBinder
import androidx.core.app.NotificationCompat
import androidx.core.content.FileProvider
import com.weike.ime.ime.WeikeInputMethodService
import java.io.File

/**
 * Grabs a single screenshot frame from the granted MediaProjection, crops the
 * keyboard area off the bottom, saves it and broadcasts ACTION_IMAGE_PICKED so
 * the IME's existing vision-reply pipeline picks it up.
 */
class ScreenCaptureService : Service() {

    private var projection: MediaProjection? = null
    private var virtualDisplay: VirtualDisplay? = null
    private var reader: ImageReader? = null
    private val worker = HandlerThread("screen-capture").also { it.start() }
    private val handler = Handler(worker.looper)
    private var done = false

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val resultCode = intent?.getIntExtra(EXTRA_RESULT_CODE, 0) ?: 0
        @Suppress("DEPRECATION")
        val resultData = intent?.getParcelableExtra<Intent>(EXTRA_RESULT_DATA)
        val cropBottom = intent?.getIntExtra(EXTRA_CROP_BOTTOM, 0) ?: 0
        if (resultData == null) {
            stopSelf()
            return START_NOT_STICKY
        }
        startInForeground()

        val metrics = resources.displayMetrics
        val width = metrics.widthPixels
        val height = metrics.heightPixels
        projection = getSystemService(MediaProjectionManager::class.java)
            .getMediaProjection(resultCode, resultData)
        // A single-buffer reader is enough: we take one frame and tear down.
        reader = ImageReader.newInstance(width, height, PixelFormat.RGBA_8888, 2)
        projection?.registerCallback(object : MediaProjection.Callback() {
            override fun onStop() {
                handler.post { cleanupAndStop() }
            }
        }, handler)
        virtualDisplay = projection?.createVirtualDisplay(
            "lianai-shot", width, height, metrics.densityDpi,
            DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR, reader!!.surface, null, handler
        )
        // 退回后台 + 聊天应用回到前台需要一次任务切换动画；抓早了会把过渡
        // 帧或本应用画面截进去。
        handler.postDelayed({ captureFrame(width, height, cropBottom) }, 800)
        return START_NOT_STICKY
    }

    private fun captureFrame(width: Int, height: Int, cropBottom: Int) {
        if (done) return
        val image = reader?.acquireLatestImage()
        if (image == null) {
            handler.postDelayed({ captureFrame(width, height, cropBottom) }, 120)
            return
        }
        val bitmap = image.toBitmap()
        image.close()
        if (bitmap == null) {
            cleanupAndStop()
            return
        }
        // Crop the IME window off the bottom so the chat content stays in view.
        val croppedHeight = (height - cropBottom).coerceIn(1, height)
        val cropped = Bitmap.createBitmap(bitmap, 0, 0, width, croppedHeight)
        if (cropped !== bitmap) bitmap.recycle()
        val scaled = downscale(cropped)
        val uri = saveAndBroadcast(scaled)
        scaled.recycle()
        cleanupAndStop()
        if (uri != null) {
            WeikeInputMethodService.pendingImageUri = uri
            sendBroadcast(
                Intent(WeikeInputMethodService.ACTION_IMAGE_PICKED)
                    .setPackage(packageName)
            )
        }
    }

    private fun Image.toBitmap(): Bitmap? = runCatching {
        val plane = planes[0]
        val buffer = plane.buffer
        val pixelStride = plane.pixelStride
        val rowStride = plane.rowStride
        val rowPadding = rowStride - pixelStride * width
        val source = Bitmap.createBitmap(
            width + rowPadding / pixelStride, height, Bitmap.Config.ARGB_8888
        )
        source.copyPixelsFromBuffer(buffer)
        Bitmap.createBitmap(source, 0, 0, width, height)
    }.getOrNull()

    /** Same long-edge cap as the gallery pipeline so request bodies stay small. */
    private fun downscale(source: Bitmap): Bitmap {
        val longest = maxOf(source.width, source.height)
        if (longest <= 1280) return source
        val scale = 1280f / longest
        return Bitmap.createScaledBitmap(
            source, (source.width * scale).toInt(), (source.height * scale).toInt(), true
        )
    }

    private fun saveAndBroadcast(bitmap: Bitmap): android.net.Uri? = runCatching {
        val dir = getExternalFilesDir("screenshots") ?: return@runCatching null
        val file = File(dir, "shot-${System.currentTimeMillis()}.jpg")
        file.outputStream().use { out -> bitmap.compress(Bitmap.CompressFormat.JPEG, 80, out) }
        FileProvider.getUriForFile(this, "$packageName.fileprovider", file)
    }.getOrNull()

    private fun startInForeground() {
        val manager = getSystemService(NotificationManager::class.java)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            manager.createNotificationChannel(
                NotificationChannel(CHANNEL_ID, "截图识别", NotificationManager.IMPORTANCE_LOW)
            )
        }
        val builder = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            NotificationCompat.Builder(this, CHANNEL_ID)
        } else {
            @Suppress("DEPRECATION")
            NotificationCompat.Builder(this)
        }
        val notification: Notification = builder
            .setSmallIcon(R.drawable.logo_lianai_app)
            .setContentText("正在截取屏幕…")
            .setOngoing(true)
            .build()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            startForeground(NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION)
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }
    }

    private fun cleanupAndStop() {
        if (done) return
        done = true
        runCatching { virtualDisplay?.release() }
        runCatching { reader?.close() }
        runCatching { projection?.stop() }
        stopSelf()
    }

    override fun onDestroy() {
        worker.quitSafely()
        super.onDestroy()
    }

    companion object {
        const val EXTRA_RESULT_CODE = "resultCode"
        const val EXTRA_RESULT_DATA = "resultData"
        const val EXTRA_CROP_BOTTOM = "cropBottom"
        private const val CHANNEL_ID = "screen_capture"
        private const val NOTIFICATION_ID = 7002
    }
}
