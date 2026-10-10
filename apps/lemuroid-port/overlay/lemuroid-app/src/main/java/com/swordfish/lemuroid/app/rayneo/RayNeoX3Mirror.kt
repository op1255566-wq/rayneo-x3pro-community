package com.swordfish.lemuroid.app.rayneo

import android.app.Activity
import android.app.Application
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Rect
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.Choreographer
import android.view.PixelCopy
import android.view.SurfaceView
import android.view.TextureView
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.LinearLayout
import com.swordfish.lemuroid.R
import timber.log.Timber
import java.util.WeakHashMap

object RayNeoX3Mirror {
    private const val EYE_WIDTH = 640
    private const val EYE_HEIGHT = 480
    private const val FRAME_DELAY_MS = 16L
    private const val INSTALL_TAG = "rayneo_x3_mirror_installed"
    private val controllers = WeakHashMap<Activity, Controller>()

    fun register(application: Application) {
        application.registerActivityLifecycleCallbacks(
            object : Application.ActivityLifecycleCallbacks {
                override fun onActivityCreated(activity: Activity, savedInstanceState: Bundle?) = Unit

                override fun onActivityStarted(activity: Activity) {
                    if (shouldMirror(activity)) {
                        activity.window.decorView.post { install(activity) }
                    }
                }

                override fun onActivityResumed(activity: Activity) {
                    controllers[activity]?.start()
                }

                override fun onActivityPaused(activity: Activity) {
                    controllers[activity]?.stop()
                }

                override fun onActivityStopped(activity: Activity) {
                    controllers[activity]?.stop()
                }

                override fun onActivitySaveInstanceState(activity: Activity, outState: Bundle) = Unit

                override fun onActivityDestroyed(activity: Activity) {
                    controllers.remove(activity)?.destroy()
                }
            },
        )
    }

    private fun shouldMirror(activity: Activity): Boolean {
        val className = activity.javaClass.name
        return className.endsWith(".mobile.feature.main.MainActivity") ||
            className.endsWith(".mobile.feature.gamemenu.GameMenuActivity") ||
            className.endsWith(".shared.gamecrash.GameCrashActivity")
    }

    private fun install(activity: Activity) {
        if (controllers.containsKey(activity) || Build.VERSION.SDK_INT < Build.VERSION_CODES.O) {
            return
        }
        val content = activity.findViewById<FrameLayout>(android.R.id.content) ?: return
        if (content.getTag(R.id.rayneo_x3_mirror_tag) == INSTALL_TAG) {
            return
        }
        if (content.childCount == 0) {
            content.post { install(activity) }
            return
        }

        val originalChildren = (0 until content.childCount).map { content.getChildAt(it) }
        content.removeAllViews()

        val leftHost = FrameLayout(activity).apply {
            layoutParams = LinearLayout.LayoutParams(EYE_WIDTH, ViewGroup.LayoutParams.MATCH_PARENT)
            setBackgroundColor(Color.BLACK)
        }
        val rightMirror = MirrorView(activity).apply {
            layoutParams = LinearLayout.LayoutParams(EYE_WIDTH, ViewGroup.LayoutParams.MATCH_PARENT)
        }
        val stereoRoot = LinearLayout(activity).apply {
            orientation = LinearLayout.HORIZONTAL
            setBackgroundColor(Color.BLACK)
            layoutParams = FrameLayout.LayoutParams(
                EYE_WIDTH * 2,
                ViewGroup.LayoutParams.MATCH_PARENT,
            )
            addView(leftHost)
            addView(rightMirror)
        }

        originalChildren.forEach { child ->
            val params = FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT,
            )
            leftHost.addView(child, params)
        }

        content.addView(stereoRoot)
        content.setTag(R.id.rayneo_x3_mirror_tag, INSTALL_TAG)

        Controller(activity, leftHost, rightMirror).also {
            controllers[activity] = it
            it.start()
        }
        Timber.i("RayNeo X3 mirror installed for ${activity.javaClass.name}")
    }

    private class Controller(
        private val activity: Activity,
        private val source: View,
        private val target: MirrorView,
    ) {
        private val handler = Handler(Looper.getMainLooper())
        private val sourceRect = Rect()
        private var bitmap: Bitmap? = null
        private var running = false
        private var pixelCopyInFlight = false

        private val frameCallback = object : Choreographer.FrameCallback {
            override fun doFrame(frameTimeNanos: Long) {
                if (!running) {
                    return
                }
                copyFrame()
                Choreographer.getInstance().postFrameCallbackDelayed(this, FRAME_DELAY_MS)
            }
        }

        fun start() {
            if (running) {
                return
            }
            running = true
            Choreographer.getInstance().postFrameCallback(frameCallback)
        }

        fun stop() {
            running = false
            Choreographer.getInstance().removeFrameCallback(frameCallback)
        }

        fun destroy() {
            stop()
            bitmap?.recycle()
            bitmap = null
        }

        private fun copyFrame() {
            val width = source.width.takeIf { it > 0 } ?: EYE_WIDTH
            val height = source.height.takeIf { it > 0 } ?: EYE_HEIGHT
            val nextBitmap = ensureBitmap(width, height) ?: return

            if (pixelCopyInFlight || !source.isAttachedToWindow) {
                return
            }

            val surfaceView = findSurfaceView(source)
            if (surfaceView != null && surfaceView.holder.surface.isValid) {
                copySurfaceView(surfaceView, nextBitmap)
                return
            } else if (surfaceView != null) {
                return
            }

            val location = IntArray(2)
            source.getLocationInWindow(location)
            sourceRect.set(location[0], location[1], location[0] + width, location[1] + height)
            pixelCopyInFlight = true
            runCatching {
                PixelCopy.request(
                    activity.window,
                    sourceRect,
                    nextBitmap,
                    { result ->
                        pixelCopyInFlight = false
                        if (result == PixelCopy.SUCCESS) {
                            target.setBitmap(nextBitmap)
                        }
                    },
                    handler,
                )
            }.onFailure {
                pixelCopyInFlight = false
            }
        }

        private fun copySurfaceView(
            surfaceView: SurfaceView,
            nextBitmap: Bitmap,
        ) {
            pixelCopyInFlight = true
            runCatching {
                PixelCopy.request(
                    surfaceView,
                    nextBitmap,
                    { result ->
                        pixelCopyInFlight = false
                        if (result != PixelCopy.SUCCESS) {
                            drawViewFallback(nextBitmap)
                        }
                        target.setBitmap(nextBitmap)
                    },
                    handler,
                )
            }.onFailure {
                pixelCopyInFlight = false
                drawViewFallback(nextBitmap)
                target.setBitmap(nextBitmap)
            }
        }

        private fun drawViewFallback(nextBitmap: Bitmap) {
            val canvas = Canvas(nextBitmap)
            canvas.drawColor(Color.BLACK)
            drawSourceViews(canvas)
            target.setBitmap(nextBitmap)
        }

        private fun drawSourceViews(canvas: Canvas) {
            hideMirrorUnsafe {
                runCatching { source.draw(canvas) }
            }
        }

        private fun hideMirrorUnsafe(block: () -> Unit) {
            val hidden = mutableListOf<Pair<View, Int>>()
            fun visit(view: View) {
                if (view === target || view is TextureView || view is SurfaceView) {
                    hidden += view to view.visibility
                    view.visibility = View.INVISIBLE
                    return
                }
                if (view is ViewGroup) {
                    repeat(view.childCount) { visit(view.getChildAt(it)) }
                }
            }
            visit(source)
            try {
                block()
            } finally {
                hidden.forEach { (view, visibility) -> view.visibility = visibility }
            }
        }

        private fun findSurfaceView(view: View): SurfaceView? {
            if (view is SurfaceView) {
                return view
            }
            if (view is ViewGroup) {
                repeat(view.childCount) { index ->
                    findSurfaceView(view.getChildAt(index))?.let { return it }
                }
            }
            return null
        }

        private fun ensureBitmap(width: Int, height: Int): Bitmap? {
            val current = bitmap
            if (current != null && !current.isRecycled && current.width == width && current.height == height) {
                return current
            }
            current?.recycle()
            bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
            return bitmap
        }
    }

    private class MirrorView(activity: Activity) : View(activity) {
        private var bitmap: Bitmap? = null

        init {
            setBackgroundColor(Color.BLACK)
        }

        fun setBitmap(nextBitmap: Bitmap) {
            bitmap = nextBitmap
            postInvalidateOnAnimation()
        }

        override fun onDraw(canvas: Canvas) {
            super.onDraw(canvas)
            val current = bitmap ?: return
            val src = Rect(0, 0, current.width, current.height)
            val dst = Rect(0, 0, width, height)
            canvas.drawBitmap(current, src, dst, null)
        }
    }
}
