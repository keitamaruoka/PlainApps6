package com.example.plain.camera

import android.Manifest
import android.app.AlertDialog
import android.content.ClipData
import android.content.ClipboardManager
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.res.ColorStateList
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import android.media.MediaActionSound
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.provider.MediaStore
import android.util.Size
import android.view.Gravity
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.OrientationEventListener
import android.view.ScaleGestureDetector
import android.view.Surface
import android.view.View
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.activity.ComponentActivity
import androidx.camera.core.Camera
import androidx.camera.core.CameraSelector
import androidx.camera.core.FocusMeteringAction
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageCapture
import androidx.camera.core.ImageCaptureException
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.video.FallbackStrategy
import androidx.camera.video.MediaStoreOutputOptions
import androidx.camera.video.Quality
import androidx.camera.video.QualitySelector
import androidx.camera.video.Recorder
import androidx.camera.video.Recording
import androidx.camera.video.VideoCapture
import androidx.camera.video.VideoRecordEvent
import androidx.camera.view.PreviewView
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/**
 * カメラ。写真と動画を撮って、端末の「DCIM/Camera」に保存するだけのシンプルなもの。
 * 位置情報の権限を持たないので、写真に撮影場所が記録されることはありません。
 */
class MainActivity : ComponentActivity() {

    private lateinit var preview: PreviewView
    private lateinit var shutter: View
    private lateinit var shutterInner: View
    private lateinit var thumb: ImageView
    private lateinit var flip: ImageView
    private lateinit var flashBtn: TextView
    private lateinit var modePhoto: TextView
    private lateinit var modeVideo: TextView
    private lateinit var modeQr: TextView
    private lateinit var qrFrame: View
    private lateinit var qrHint: TextView
    private lateinit var recTime: TextView
    private lateinit var focusRing: View
    private lateinit var flashOverlay: View

    private var provider: ProcessCameraProvider? = null
    private var camera: Camera? = null
    private var imageCapture: ImageCapture? = null
    private var videoCapture: VideoCapture<Recorder>? = null
    private var recording: Recording? = null

    private var lensFacing = CameraSelector.LENS_FACING_BACK
    private var videoMode = false
    private var qrMode = false
    private val qrAnalyzer = QrAnalyzer { text -> runOnUiThread { showQrResult(text) } }
    private var flashMode = ImageCapture.FLASH_MODE_OFF
    private var torchOn = false
    private var rotation = Surface.ROTATION_0
    private var busy = false

    private val sound = MediaActionSound()
    private val io = Executors.newSingleThreadExecutor()
    private val analysisExecutor = Executors.newSingleThreadExecutor()

    /** 他のアプリから「写真を撮って」と頼まれたとき */
    private val isCaptureRequest get() = intent.action == MediaStore.ACTION_IMAGE_CAPTURE

    private val orientationListener by lazy {
        object : OrientationEventListener(this) {
            override fun onOrientationChanged(degrees: Int) {
                if (degrees == OrientationEventListener.ORIENTATION_UNKNOWN) return
                val r = when (degrees) {
                    in 45 until 135 -> Surface.ROTATION_270
                    in 135 until 225 -> Surface.ROTATION_180
                    in 225 until 315 -> Surface.ROTATION_90
                    else -> Surface.ROTATION_0
                }
                if (r != rotation) {
                    rotation = r
                    imageCapture?.targetRotation = r
                    videoCapture?.targetRotation = r
                }
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        edgeToEdge()
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        val prefs = getSharedPreferences("camera", Context.MODE_PRIVATE)
        flashMode = prefs.getInt("flash", ImageCapture.FLASH_MODE_OFF)
        if (intent.action == MediaStore.INTENT_ACTION_VIDEO_CAMERA) videoMode = true
        sound.load(MediaActionSound.SHUTTER_CLICK)

        val root = FrameLayout(this).apply { setBackgroundColor(Color.BLACK) }
        preview = PreviewView(this)
        root.addView(preview, FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT))

        focusRing = View(this).apply {
            background = rounded(Color.TRANSPARENT, 36, Color.WHITE, 2)
            visibility = View.GONE
        }
        root.addView(focusRing, FrameLayout.LayoutParams(dp(72), dp(72)))

        // QRコード用の枠
        qrFrame = View(this).apply {
            background = rounded(Color.TRANSPARENT, 24, Color.WHITE, 3)
            visibility = View.GONE
        }
        root.addView(qrFrame, FrameLayout.LayoutParams(dp(240), dp(240), Gravity.CENTER))
        qrHint = label(getString(R.string.qr_hint), 15f, true, Color.WHITE).apply {
            gravity = Gravity.CENTER
            setShadowLayer(6f, 0f, 1f, Color.BLACK)
            translationY = dp(150).toFloat()
            visibility = View.GONE
        }
        root.addView(qrHint, FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.WRAP_CONTENT, Gravity.CENTER))

        flashOverlay = View(this).apply {
            setBackgroundColor(Color.BLACK)
            alpha = 0f
        }
        root.addView(flashOverlay, FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT))

        // 上：フラッシュと録画時間
        val top = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(12), dp(8), dp(12), dp(8))
        }
        flashBtn = label("", 14f, true, Color.WHITE).apply {
            setCompoundDrawablesRelativeWithIntrinsicBounds(R.drawable.ic_flash, 0, 0, 0)
            compoundDrawablePadding = dp(6)
            gravity = Gravity.CENTER_VERTICAL
            background = pressable(0x55000000, 20)
            setPadding(dp(12), dp(8), dp(14), dp(8))
            setOnClickListener { cycleFlash() }
        }
        top.addView(flashBtn)
        top.addView(View(this), LinearLayout.LayoutParams(0, 1, 1f))
        recTime = label("", 15f, true, Color.WHITE).apply {
            background = rounded(0x99000000.toInt(), 14)
            setPadding(dp(12), dp(6), dp(12), dp(6))
            visibility = View.GONE
        }
        top.addView(recTime)
        top.padForSystemBars(bottom = false)
        root.addView(top, FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.WRAP_CONTENT, Gravity.TOP))

        // 下：モード切替・シャッター・サムネイル・カメラ切替
        val bottom = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            setBackgroundColor(0x66000000)
            setPadding(dp(16), dp(10), dp(16), dp(20))
        }
        val modes = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
        }
        modePhoto = modeLabel(getString(R.string.photo)) { setQrMode(false); setVideoMode(false) }
        modeVideo = modeLabel(getString(R.string.video)) { setQrMode(false); setVideoMode(true) }
        modeQr = modeLabel(getString(R.string.qr)) { setQrMode(true) }
        modes.addView(modePhoto)
        modes.addView(modeVideo)
        modes.addView(modeQr)
        if (!isCaptureRequest) bottom.addView(modes)

        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, dp(12), 0, 0)
        }
        thumb = ImageView(this).apply {
            scaleType = ImageView.ScaleType.CENTER_CROP
            background = rounded(0x33FFFFFF, 28)
            clipToOutline = true
            contentDescription = getString(R.string.last_capture)
            setOnClickListener { openLast() }
        }
        shutter = FrameLayout(this).apply {
            background = rounded(Color.TRANSPARENT, 40, Color.WHITE, 4)
            contentDescription = getString(R.string.shutter)
            setOnClickListener { onShutter() }
        }
        shutterInner = View(this).apply { background = rounded(Color.WHITE, 30) }
        (shutter as FrameLayout).addView(shutterInner, FrameLayout.LayoutParams(dp(60), dp(60), Gravity.CENTER))
        flip = ImageView(this).apply {
            setImageResource(R.drawable.ic_flip)
            imageTintList = ColorStateList.valueOf(Color.WHITE)
            background = pressable(0x33FFFFFF, 28)
            setPadding(dp(14), dp(14), dp(14), dp(14))
            contentDescription = getString(R.string.switch_camera)
            setOnClickListener { switchCamera() }
        }
        row.addView(thumb, LinearLayout.LayoutParams(dp(56), dp(56)))
        row.addView(View(this), LinearLayout.LayoutParams(0, 1, 1f))
        row.addView(shutter, LinearLayout.LayoutParams(dp(80), dp(80)))
        row.addView(View(this), LinearLayout.LayoutParams(0, 1, 1f))
        row.addView(flip, LinearLayout.LayoutParams(dp(56), dp(56)))
        bottom.addView(row, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT))
        bottom.padForSystemBars(top = false)
        root.addView(bottom, FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.WRAP_CONTENT, Gravity.BOTTOM))
        setContentView(root)

        setupGestures()
        loadLastThumb()
        updateUi()

        if (checkSelfPermission(Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED) {
            startCamera()
        } else {
            requestPermissions(arrayOf(Manifest.permission.CAMERA), REQ_CAMERA)
        }
    }

    override fun onResume() {
        super.onResume()
        if (orientationListener.canDetectOrientation()) orientationListener.enable()
    }

    override fun onPause() {
        orientationListener.disable()
        recording?.stop()
        super.onPause()
    }

    override fun onDestroy() {
        sound.release()
        io.shutdown()
        analysisExecutor.shutdown()
        super.onDestroy()
    }

    @Deprecated("Activity API")
    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        when (requestCode) {
            REQ_CAMERA -> {
                if (checkSelfPermission(Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED) {
                    startCamera()
                } else {
                    toast(getString(R.string.need_camera))
                    finish()
                }
            }
            REQ_AUDIO -> bind()
        }
    }

    /** 音量ボタンでもシャッターを切れる */
    override fun onKeyDown(keyCode: Int, event: KeyEvent?): Boolean {
        if (keyCode == KeyEvent.KEYCODE_VOLUME_DOWN || keyCode == KeyEvent.KEYCODE_VOLUME_UP || keyCode == KeyEvent.KEYCODE_CAMERA) {
            if (event?.repeatCount == 0) onShutter()
            return true
        }
        return super.onKeyDown(keyCode, event)
    }

    private fun modeLabel(text: String, onClick: () -> Unit): TextView = label(text, 15f, true, Color.WHITE).apply {
        setPadding(dp(16), dp(6), dp(16), dp(6))
        setOnClickListener { onClick() }
    }

    // ---- カメラの起動 ----

    private fun startCamera() {
        val future = ProcessCameraProvider.getInstance(this)
        future.addListener({
            provider = try { future.get() } catch (e: Exception) { null }
            bind()
        }, mainExecutor)
    }

    private fun bind() {
        val p = provider ?: return
        var selector = CameraSelector.Builder().requireLensFacing(lensFacing).build()
        try {
            if (!p.hasCamera(selector)) {
                lensFacing = if (lensFacing == CameraSelector.LENS_FACING_BACK) CameraSelector.LENS_FACING_FRONT else CameraSelector.LENS_FACING_BACK
                selector = CameraSelector.Builder().requireLensFacing(lensFacing).build()
            }
            val prev = Preview.Builder().build()
            prev.setSurfaceProvider(preview.surfaceProvider)
            p.unbindAll()
            imageCapture = null
            videoCapture = null
            camera = if (qrMode) {
                val analysis = ImageAnalysis.Builder()
                    .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                    .build()
                analysis.setAnalyzer(analysisExecutor, qrAnalyzer)
                qrAnalyzer.paused = false
                p.bindToLifecycle(this, selector, prev, analysis)
            } else if (videoMode) {
                val recorder = Recorder.Builder()
                    .setQualitySelector(QualitySelector.from(Quality.FHD, FallbackStrategy.lowerQualityOrHigherThan(Quality.SD)))
                    .build()
                val vc = VideoCapture.withOutput(recorder)
                vc.targetRotation = rotation
                videoCapture = vc
                p.bindToLifecycle(this, selector, prev, vc)
            } else {
                val ic = ImageCapture.Builder()
                    .setCaptureMode(ImageCapture.CAPTURE_MODE_MINIMIZE_LATENCY)
                    .setFlashMode(flashMode)
                    .setTargetRotation(rotation)
                    .build()
                imageCapture = ic
                p.bindToLifecycle(this, selector, prev, ic)
            }
            torchOn = false
        } catch (e: Exception) {
            toast(getString(R.string.camera_error))
        }
        updateUi()
    }

    // ---- 撮影 ----

    private fun onShutter() {
        if (qrMode) return
        if (videoMode) toggleRecording() else takePhoto()
    }

    private fun takePhoto() {
        val ic = imageCapture ?: return
        if (busy) return
        busy = true
        sound.play(MediaActionSound.SHUTTER_CLICK)
        flashOverlay.alpha = 0.8f
        flashOverlay.animate().alpha(0f).setDuration(200).start()

        if (isCaptureRequest) {
            captureForOtherApp(ic)
            return
        }
        val values = ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, "IMG_" + stamp())
            put(MediaStore.MediaColumns.MIME_TYPE, "image/jpeg")
            put(MediaStore.MediaColumns.RELATIVE_PATH, Environment.DIRECTORY_DCIM + "/Camera")
        }
        val opts = ImageCapture.OutputFileOptions.Builder(contentResolver, MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values).build()
        ic.takePicture(opts, mainExecutor, object : ImageCapture.OnImageSavedCallback {
            override fun onImageSaved(output: ImageCapture.OutputFileResults) {
                busy = false
                output.savedUri?.let { setLast(it, false) }
            }

            override fun onError(exception: ImageCaptureException) {
                busy = false
                toast(getString(R.string.save_failed))
            }
        })
    }

    /**
     * 他のアプリ（メールアプリなど）に頼まれて撮る。
     * 1. いったんこのアプリ専用の一時ファイルに撮る
     * 2. 頼んだアプリが保存先（EXTRA_OUTPUT）を指定していれば、そこへ書き写して、その場所を結果として返す
     * 3. 指定が無ければ、小さな画像を結果として返す（Androidの決まり）
     * 書き込みに失敗したときは「成功」と返さない（空のファイルを渡すと相手のアプリが落ちることがあるため）。
     */
    private fun captureForOtherApp(ic: ImageCapture) {
        val out: Uri? = (if (Build.VERSION.SDK_INT >= 33) {
            intent.getParcelableExtra(MediaStore.EXTRA_OUTPUT, Uri::class.java)
        } else {
            intent.getParcelableExtra<Uri>(MediaStore.EXTRA_OUTPUT)
        }) ?: intent.clipData?.takeIf { it.itemCount > 0 }?.getItemAt(0)?.uri

        val tmp = File(cacheDir, "capture.jpg")
        tmp.delete()
        ic.takePicture(ImageCapture.OutputFileOptions.Builder(tmp).build(), mainExecutor, object : ImageCapture.OnImageSavedCallback {
            override fun onImageSaved(output: ImageCapture.OutputFileResults) {
                io.execute {
                    val result: Intent? = try {
                        if (out != null) {
                            copyTo(tmp, out)
                            Intent().setData(out).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION)
                        } else {
                            Intent().putExtra("data", thumbnail(tmp))
                        }
                    } catch (e: Exception) {
                        null
                    } finally {
                        tmp.delete()
                    }
                    runOnUiThread {
                        busy = false
                        if (result != null) {
                            setResult(RESULT_OK, result)
                            finish()
                        } else {
                            toast(getString(R.string.save_failed))
                        }
                    }
                }
            }

            override fun onError(exception: ImageCaptureException) {
                busy = false
                tmp.delete()
                toast(getString(R.string.save_failed))
            }
        })
    }

    /** 撮った写真を、頼んだアプリが用意した場所に書き写す（中身を上書き） */
    private fun copyTo(src: File, dst: Uri) {
        val os = try {
            contentResolver.openOutputStream(dst, "wt")
        } catch (e: Exception) {
            null
        } ?: contentResolver.openOutputStream(dst, "w") ?: throw java.io.IOException("cannot open $dst")
        os.use { o -> src.inputStream().use { i -> i.copyTo(o) } }
    }

    /** 保存先の指定がないときに返す小さな画像（向きを直して、長い辺を約320pxに） */
    private fun thumbnail(f: File): Bitmap {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(f.absolutePath, bounds)
        var sample = 1
        while (maxOf(bounds.outWidth, bounds.outHeight) / (sample * 2) >= 320) sample *= 2
        val bmp = BitmapFactory.decodeFile(f.absolutePath, BitmapFactory.Options().apply { inSampleSize = sample })
            ?: throw java.io.IOException("decode failed")
        val scale = 320f / maxOf(bmp.width, bmp.height)
        val small = if (scale < 1f) Bitmap.createScaledBitmap(bmp, (bmp.width * scale).toInt().coerceAtLeast(1), (bmp.height * scale).toInt().coerceAtLeast(1), true) else bmp
        val degrees = when (android.media.ExifInterface(f.absolutePath).getAttributeInt(
            android.media.ExifInterface.TAG_ORIENTATION, android.media.ExifInterface.ORIENTATION_NORMAL,
        )) {
            android.media.ExifInterface.ORIENTATION_ROTATE_90 -> 90f
            android.media.ExifInterface.ORIENTATION_ROTATE_180 -> 180f
            android.media.ExifInterface.ORIENTATION_ROTATE_270 -> 270f
            else -> 0f
        }
        if (degrees == 0f) return small
        val m = android.graphics.Matrix().apply { postRotate(degrees) }
        return Bitmap.createBitmap(small, 0, 0, small.width, small.height, m, true)
    }

    private fun toggleRecording() {
        val vc = videoCapture ?: return
        val current = recording
        if (current != null) {
            current.stop()
            return
        }
        val values = ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, "VID_" + stamp())
            put(MediaStore.MediaColumns.MIME_TYPE, "video/mp4")
            put(MediaStore.MediaColumns.RELATIVE_PATH, Environment.DIRECTORY_DCIM + "/Camera")
        }
        val opts = MediaStoreOutputOptions.Builder(contentResolver, MediaStore.Video.Media.EXTERNAL_CONTENT_URI)
            .setContentValues(values)
            .build()
        try {
            var pending = vc.output.prepareRecording(this, opts)
            if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) {
                pending = pending.withAudioEnabled()
            }
            recording = pending.start(mainExecutor) { event ->
                when (event) {
                    is VideoRecordEvent.Start -> {
                        sound.play(MediaActionSound.START_VIDEO_RECORDING)
                        updateUi()
                    }
                    is VideoRecordEvent.Status -> {
                        recTime.text = "● " + timeText(TimeUnit.NANOSECONDS.toMillis(event.recordingStats.recordedDurationNanos))
                    }
                    is VideoRecordEvent.Finalize -> {
                        sound.play(MediaActionSound.STOP_VIDEO_RECORDING)
                        recording = null
                        if (event.hasError() && event.error != VideoRecordEvent.Finalize.ERROR_NONE) {
                            toast(getString(R.string.save_failed))
                        } else {
                            setLast(event.outputResults.outputUri, true)
                        }
                        updateUi()
                    }
                }
            }
        } catch (e: Exception) {
            recording = null
            toast(getString(R.string.camera_error))
        }
    }

    // ---- 設定の切り替え ----

    /** QRコード読み取りモードの切り替え */
    private fun setQrMode(on: Boolean) {
        if (recording != null || qrMode == on) return
        qrMode = on
        if (on) {
            videoMode = false
            bind()
        } else if (!videoMode) {
            bind()
        }
    }

    private fun setVideoMode(on: Boolean) {
        if (recording != null) return
        if (videoMode == on) {
            if (!qrMode) updateUi()
            return
        }
        videoMode = on
        if (on && checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED &&
            !getSharedPreferences("camera", Context.MODE_PRIVATE).getBoolean("asked_audio", false)
        ) {
            // 音声付きで録画するためにマイクの許可をお願いする（断られても音なしで録画できる）
            getSharedPreferences("camera", Context.MODE_PRIVATE).edit().putBoolean("asked_audio", true).apply()
            requestPermissions(arrayOf(Manifest.permission.RECORD_AUDIO), REQ_AUDIO)
        } else {
            bind()
        }
    }

    private fun switchCamera() {
        if (recording != null) return
        lensFacing = if (lensFacing == CameraSelector.LENS_FACING_BACK) CameraSelector.LENS_FACING_FRONT else CameraSelector.LENS_FACING_BACK
        bind()
    }

    private fun cycleFlash() {
        val cam = camera ?: return
        if (videoMode || qrMode) {
            torchOn = !torchOn
            cam.cameraControl.enableTorch(torchOn)
        } else {
            flashMode = when (flashMode) {
                ImageCapture.FLASH_MODE_OFF -> ImageCapture.FLASH_MODE_AUTO
                ImageCapture.FLASH_MODE_AUTO -> ImageCapture.FLASH_MODE_ON
                else -> ImageCapture.FLASH_MODE_OFF
            }
            imageCapture?.flashMode = flashMode
            getSharedPreferences("camera", Context.MODE_PRIVATE).edit().putInt("flash", flashMode).apply()
        }
        updateUi()
    }

    private fun updateUi() {
        val recordingNow = recording != null
        val photoSel = !videoMode && !qrMode
        val videoSel = videoMode && !qrMode
        modePhoto.alpha = if (photoSel) 1f else 0.5f
        modeVideo.alpha = if (videoSel) 1f else 0.5f
        modeQr.alpha = if (qrMode) 1f else 0.5f
        modePhoto.background = if (photoSel) rounded(0x33FFFFFF, 16) else null
        modeVideo.background = if (videoSel) rounded(0x33FFFFFF, 16) else null
        modeQr.background = if (qrMode) rounded(0x33FFFFFF, 16) else null
        shutter.visibility = if (qrMode) View.INVISIBLE else View.VISIBLE
        qrFrame.visibility = if (qrMode) View.VISIBLE else View.GONE
        qrHint.visibility = if (qrMode) View.VISIBLE else View.GONE
        flip.alpha = if (recordingNow) 0.3f else 1f
        recTime.visibility = if (recordingNow) View.VISIBLE else View.GONE
        if (!recordingNow) recTime.text = ""

        // シャッターの見た目：写真＝白い丸、動画＝赤い丸、録画中＝赤い四角
        val lp = shutterInner.layoutParams as FrameLayout.LayoutParams
        if (videoMode && recordingNow) {
            shutterInner.background = rounded(0xFFE53935.toInt(), 6)
            lp.width = dp(30); lp.height = dp(30)
        } else {
            shutterInner.background = rounded(if (videoMode) 0xFFE53935.toInt() else Color.WHITE, 30)
            lp.width = dp(60); lp.height = dp(60)
        }
        shutterInner.layoutParams = lp
        shutter.contentDescription = getString(
            when {
                !videoMode -> R.string.shutter
                recordingNow -> R.string.stop_recording
                else -> R.string.start_recording
            },
        )

        val hasFlash = camera?.cameraInfo?.hasFlashUnit() ?: false
        flashBtn.visibility = if (hasFlash) View.VISIBLE else View.INVISIBLE
        flashBtn.text = if (videoMode || qrMode) {
            getString(if (torchOn) R.string.light_on else R.string.light_off)
        } else {
            getString(
                when (flashMode) {
                    ImageCapture.FLASH_MODE_AUTO -> R.string.flash_auto
                    ImageCapture.FLASH_MODE_ON -> R.string.flash_on
                    else -> R.string.flash_off
                },
            )
        }
    }

    // ---- ピンチでズーム・タップでピント合わせ ----

    private fun setupGestures() {
        val scaler = ScaleGestureDetector(this, object : ScaleGestureDetector.SimpleOnScaleGestureListener() {
            override fun onScale(detector: ScaleGestureDetector): Boolean {
                val cam = camera ?: return false
                val now = cam.cameraInfo.zoomState.value?.zoomRatio ?: 1f
                cam.cameraControl.setZoomRatio(now * detector.scaleFactor)
                return true
            }
        })
        preview.setOnTouchListener { v, event ->
            scaler.onTouchEvent(event)
            if (event.actionMasked == MotionEvent.ACTION_UP && !scaler.isInProgress && event.pointerCount == 1) {
                focusAt(event.x, event.y)
                v.performClick()
            }
            true
        }
    }

    private fun focusAt(x: Float, y: Float) {
        val cam = camera ?: return
        val point = preview.meteringPointFactory.createPoint(x, y)
        val action = FocusMeteringAction.Builder(point).setAutoCancelDuration(3, TimeUnit.SECONDS).build()
        cam.cameraControl.startFocusAndMetering(action)
        val size = dp(72)
        focusRing.translationX = x - size / 2f
        focusRing.translationY = y - size / 2f
        focusRing.visibility = View.VISIBLE
        focusRing.alpha = 1f
        focusRing.animate().alpha(0f).setStartDelay(600).setDuration(300).withEndAction { focusRing.visibility = View.GONE }.start()
    }

    // ---- QRコードの結果 ----

    /**
     * 読み取った内容を表示する。安全のため、リンクは自動では開かず、URLを確認してから開いてもらう。
     */
    private fun showQrResult(raw: String) {
        if (isFinishing || !qrMode) {
            qrAnalyzer.paused = false
            return
        }
        sound.play(MediaActionSound.FOCUS_COMPLETE)
        val b = AlertDialog.Builder(this)
        when (val c = QrContent.parse(raw)) {
            is QrContent.Link -> {
                b.setTitle(R.string.qr_link)
                b.setMessage(getString(R.string.qr_link_message, c.url))
                b.setPositiveButton(R.string.qr_open) { _, _ -> openLink(c.url) }
                b.setNeutralButton(R.string.qr_copy) { _, _ -> copy(c.url) }
            }
            is QrContent.Wifi -> {
                b.setTitle(R.string.qr_wifi)
                b.setMessage(getString(R.string.qr_wifi_message, c.ssid, c.password.ifEmpty { "—" }))
                if (c.password.isNotEmpty()) b.setPositiveButton(R.string.qr_copy_password) { _, _ -> copy(c.password) }
                b.setNeutralButton(R.string.qr_wifi_settings) { _, _ ->
                    try { startActivity(Intent(android.provider.Settings.ACTION_WIFI_SETTINGS)) } catch (e: Exception) { }
                }
            }
            is QrContent.Text -> {
                b.setTitle(R.string.qr_text)
                b.setMessage(c.text)
                b.setPositiveButton(R.string.qr_copy) { _, _ -> copy(c.text) }
                b.setNeutralButton(R.string.qr_share) { _, _ ->
                    startActivity(Intent.createChooser(Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, c.text), null))
                }
            }
        }
        b.setNegativeButton(R.string.qr_close, null)
        b.setOnDismissListener {
            // 同じコードをすぐ読み直さないよう、少し待ってから再開
            preview.postDelayed({ qrAnalyzer.paused = false }, 1200)
        }
        b.show()
    }

    private fun openLink(url: String) {
        try {
            startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)))
        } catch (e: Exception) {
            toast(getString(R.string.no_viewer))
        }
    }

    private fun copy(text: String) {
        val cm = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        cm.setPrimaryClip(ClipData.newPlainText("QR", text))
        if (Build.VERSION.SDK_INT < 33) toast(getString(R.string.copied)) // 13以降はAndroidが自動で知らせる
    }

    // ---- 直前に撮ったもの ----

    private fun setLast(uri: Uri, video: Boolean) {
        getSharedPreferences("camera", Context.MODE_PRIVATE).edit()
            .putString("last", uri.toString())
            .putBoolean("last_video", video)
            .apply()
        loadLastThumb()
    }

    private fun loadLastThumb() {
        val s = getSharedPreferences("camera", Context.MODE_PRIVATE).getString("last", null)
        if (s == null || isCaptureRequest) {
            thumb.visibility = View.INVISIBLE
            return
        }
        val uri = Uri.parse(s)
        io.execute {
            val bmp = try {
                contentResolver.loadThumbnail(uri, Size(200, 200), null)
            } catch (e: Exception) {
                null // 削除された場合など
            }
            runOnUiThread {
                if (isDestroyed) return@runOnUiThread
                thumb.setImageBitmap(bmp)
                thumb.visibility = if (bmp != null) View.VISIBLE else View.INVISIBLE
            }
        }
    }

    /** 直前に撮った写真・動画を、ギャラリーなどで開く */
    private fun openLast() {
        val prefs = getSharedPreferences("camera", Context.MODE_PRIVATE)
        val s = prefs.getString("last", null) ?: return
        val type = if (prefs.getBoolean("last_video", false)) "video/*" else "image/*"
        try {
            startActivity(Intent(Intent.ACTION_VIEW).setDataAndType(Uri.parse(s), type).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION))
        } catch (e: Exception) {
            toast(getString(R.string.no_viewer))
        }
    }

    private fun stamp(): String = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())

    private fun timeText(ms: Long): String {
        val s = ms / 1000
        return if (s >= 3600) "%d:%02d:%02d".format(s / 3600, (s / 60) % 60, s % 60) else "%02d:%02d".format(s / 60, s % 60)
    }

    companion object {
        private const val REQ_CAMERA = 1
        private const val REQ_AUDIO = 2
    }
}
