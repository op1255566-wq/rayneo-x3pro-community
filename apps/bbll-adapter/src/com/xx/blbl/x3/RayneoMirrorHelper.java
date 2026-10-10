package com.xx.blbl.x3;

import android.app.Activity;
import android.content.SharedPreferences;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Rect;
import android.graphics.RectF;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.view.Choreographer;
import android.view.Gravity;
import android.view.InputDevice;
import android.view.KeyEvent;
import android.view.MotionEvent;
import android.view.PixelCopy;
import android.view.SurfaceView;
import android.view.TextureView;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.PopupWindow;
import android.widget.TextView;
import android.widget.Toast;

import java.util.ArrayList;
import java.util.Locale;
import java.util.WeakHashMap;

public final class RayneoMirrorHelper {
    private static final int EYE_WIDTH = 640;
    private static final int EYE_HEIGHT = 480;
    private static final int CURSOR_SIZE = 24;
    private static final long CLICK_UP_DELAY_MS = 16L;
    private static final long DOUBLE_TAP_TIMEOUT_MS = 320L;
    private static final long SINGLE_TAP_CONFIRM_DELAY_MS = 220L;
    private static final long CURSOR_HIDE_DELAY_MS = 3000L;
    private static final long APP_SWIPE_DURATION_MS = 140L;
    private static final int APP_SWIPE_STEP_COUNT = 4;
    private static final int DEFAULT_RESOLUTION_720P = 0x40;
    private static final int DEFAULT_CODEC_AVC = 0x0;
    private static final int DEFAULTS_MIGRATION_VERSION = 3;
    private static final int DEFAULT_START_PAGE_RECOMMEND = 0;
    private static final String DEVICE_TRACKPAD = "cyttsp5_mt";
    private static final String BROWSER_PACKAGE = "acr.browser.lightning";
    private static final String BBLL_PACKAGE = "com.V2.blb5";
    private static final float BROWSER_SCROLL_TRIGGER_PX = 28.0f;
    private static final String BROWSER_SCROLL_TOGGLE_DEVICE = "cyttsp6_mt";
    private static final float BROWSER_TOGGLE_TRIGGER_PX = 24.0f;
    private static final float CURSOR_GAIN_X = 0.7f;
    private static final float CURSOR_GAIN_Y = 0.7f;
    private static final WeakHashMap<Activity, MirrorController> CONTROLLERS = new WeakHashMap<>();
    private static final Object BRIDGE_LOCK = new Object();
    private static BridgeCommandServer BRIDGE_SERVER;
    private static MirrorController ACTIVE_CONTROLLER;

    private RayneoMirrorHelper() {}

    public static void install(Activity activity) {
        if (activity == null) {
            return;
        }
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) {
            return;
        }
        if (CONTROLLERS.containsKey(activity)) {
            return;
        }

        View contentView = activity.findViewById(android.R.id.content);
        if (!(contentView instanceof FrameLayout)) {
            return;
        }

        FrameLayout content = (FrameLayout) contentView;
        if (content.getChildCount() == 0) {
            return;
        }

        View sourceRoot = content.getChildAt(0);
        MirrorController controller = new MirrorController(activity, content, sourceRoot);
        CONTROLLERS.put(activity, controller);
        ACTIVE_CONTROLLER = controller;
        controller.install();
        ensureBridgeServerStarted();
    }

    public static boolean handleMotionEvent(Activity activity, MotionEvent event) {
        MirrorController controller = CONTROLLERS.get(activity);
        return controller != null && controller.handleMotionEvent(event);
    }

    public static boolean handleGenericMotionEvent(Activity activity, MotionEvent event) {
        MirrorController controller = CONTROLLERS.get(activity);
        return controller != null && controller.handleGenericMotionEvent(event);
    }

    public static boolean handleKeyEvent(Activity activity, KeyEvent event) {
        MirrorController controller = CONTROLLERS.get(activity);
        return controller != null && controller.handleKeyEvent(event);
    }

    public static void setVideoMirrorOverride(Activity activity, boolean enabled) {
        MirrorController controller = CONTROLLERS.get(activity);
        if (controller != null) {
            controller.setVideoMirrorOverride(enabled);
        }
    }

    public static void uninstall(Activity activity) {
        MirrorController controller = CONTROLLERS.remove(activity);
        if (controller != null) {
            controller.destroy();
        }
        if (ACTIVE_CONTROLLER == controller) {
            ACTIVE_CONTROLLER = pickAnyController();
        }
        if (ACTIVE_CONTROLLER == null) {
            stopBridgeServer();
        }
    }

    static void applyBridgeMove(float dx, float dy) {
        MirrorController controller = ACTIVE_CONTROLLER;
        if (controller != null) {
            controller.applyBridgeMove(dx, dy);
        }
    }

    static void applyBridgeClick() {
        MirrorController controller = ACTIVE_CONTROLLER;
        if (controller != null) {
            controller.applyBridgeClick();
        }
    }

    static void applyBridgeBack() {
        MirrorController controller = ACTIVE_CONTROLLER;
        if (controller != null) {
            controller.applyBridgeBack();
        }
    }

    static void applyBridgeShowCursor() {
        MirrorController controller = ACTIVE_CONTROLLER;
        if (controller != null) {
            controller.applyBridgeShowCursor();
        }
    }

    private static MirrorController pickAnyController() {
        for (MirrorController controller : CONTROLLERS.values()) {
            if (controller != null) {
                return controller;
            }
        }
        return null;
    }

    private static void ensureBridgeServerStarted() {
        synchronized (BRIDGE_LOCK) {
            if (BRIDGE_SERVER != null) {
                return;
            }
            BRIDGE_SERVER = new BridgeCommandServer();
            BRIDGE_SERVER.start();
        }
    }

    private static void stopBridgeServer() {
        synchronized (BRIDGE_LOCK) {
            if (BRIDGE_SERVER == null) {
                return;
            }
            BRIDGE_SERVER.stop();
            BRIDGE_SERVER = null;
        }
    }

    private static final class MirrorController {
        private final Activity activity;
        private final FrameLayout content;
        private final View sourceRoot;
        private final MirrorBitmapView leftMirrorView;
        private PopupWindow leftMirrorPopup;
        private final MirrorBitmapView mirrorView;
        private final CursorView leftCursorView;
        private final CursorView rightCursorView;
        private final TextView statusView;
        private final Handler handler = new Handler(Looper.getMainLooper());
        private final int[] rootLocation = new int[2];
        private final int[] textureLocation = new int[2];
        private final int[] surfaceLocation = new int[2];

        private Bitmap bitmap;
        private Bitmap textureBitmap;
        private Bitmap surfaceBitmap;
        private boolean running;
        private boolean mouseModeEnabled;
        private boolean dragActive;
        private float cursorX = EYE_WIDTH / 2.0f;
        private float cursorY = EYE_HEIGHT / 2.0f;
        private long dragDownTime;
        private long lastTempleUpTime;
        private long touchDownTime;
        private float touchDownCursorX;
        private float touchDownCursorY;
        private boolean cursorMovedSinceDown;
        private boolean sawTempleDown;
        private float templeDownRawX;
        private float templeDownRawY;
        private float templeCursorOriginX;
        private float templeCursorOriginY;
        private Runnable pendingClickRunnable;
        private boolean cursorTemporarilyHidden;
        private boolean syntheticSwipeActive;
        private int syntheticSwipeToken;
        private long syntheticSwipeDownTime;
        private float syntheticSwipeLastX;
        private float syntheticSwipeLastY;
        private boolean browserScrollModeEnabled;
        private boolean browserScrollGestureActive;
        private float browserScrollRawX;
        private float browserScrollRawY;
        private boolean browserToggleGestureActive;
        private float browserToggleRawX;
        private float browserToggleRawY;
        private boolean videoSurfaceVisible;
        private boolean pixelCopyInFlight;
        private boolean pixelCopyModeActive;
        private boolean videoMirrorOverride;
        private boolean surfaceCopyInFlight;

        private final Runnable cursorHideRunnable =
                new Runnable() {
                    @Override
                    public void run() {
                        if (!mouseModeEnabled || !videoSurfaceVisible) {
                            return;
                        }
                        cursorTemporarilyHidden = true;
                        updateCursorViews(false);
                    }
                };

        private final Choreographer.FrameCallback frameCallback =
                new Choreographer.FrameCallback() {
                    @Override
                    public void doFrame(long frameTimeNanos) {
                        if (!running) {
                            return;
                        }
                        if (activity.isFinishing()) {
                            stop();
                            return;
                        }
                        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.JELLY_BEAN_MR1
                                && activity.isDestroyed()) {
                            stop();
                            return;
                        }

                        try {
                            enforceRootBounds();
                            updateVideoCursorState();
                            copyLeftEye();
                        } catch (Throwable ignored) {
                        }
                        Choreographer.getInstance().postFrameCallback(this);
                    }
                };

        private final View.OnLayoutChangeListener layoutListener =
                new View.OnLayoutChangeListener() {
                    @Override
                    public void onLayoutChange(
                            View v,
                            int left,
                            int top,
                            int right,
                            int bottom,
                            int oldLeft,
                            int oldTop,
                            int oldRight,
                            int oldBottom) {
                        enforceRootBounds();
                            }
                };

        MirrorController(Activity activity, FrameLayout content, View sourceRoot) {
            this.activity = activity;
            this.content = content;
            this.sourceRoot = sourceRoot;
            this.leftMirrorView = new MirrorBitmapView(activity);
            this.mirrorView = new MirrorBitmapView(activity);
            this.leftCursorView = new CursorView(activity);
            this.rightCursorView = new CursorView(activity);
            this.statusView = new TextView(activity);
        }

        void install() {
            ensureAppDefaults();
            content.setBackgroundColor(Color.BLACK);
            content.setClipChildren(false);
            content.setClipToPadding(false);

            FrameLayout.LayoutParams sourceParams =
                    new FrameLayout.LayoutParams(EYE_WIDTH, EYE_HEIGHT);
            sourceParams.gravity = Gravity.START | Gravity.TOP;
            sourceRoot.setLayoutParams(sourceParams);
            sourceRoot.setPivotX(0f);
            sourceRoot.setPivotY(0f);
            sourceRoot.setX(0f);
            sourceRoot.setY(0f);

            FrameLayout.LayoutParams mirrorParams =
                    new FrameLayout.LayoutParams(EYE_WIDTH, EYE_HEIGHT);
            mirrorParams.gravity = Gravity.START | Gravity.TOP;
            mirrorParams.leftMargin = EYE_WIDTH;
            leftMirrorView.setLayoutParams(sourceParams);
            leftMirrorView.setClickable(false);
            leftMirrorView.setFocusable(false);
            leftMirrorView.setVisibility(View.GONE);
            mirrorView.setLayoutParams(mirrorParams);
            mirrorView.setClickable(false);
            mirrorView.setFocusable(false);

            statusView.setTextColor(Color.YELLOW);
            statusView.setTextSize(10.0f);
            statusView.setBackgroundColor(Color.argb(160, 0, 0, 0));
            statusView.setPadding(6, 4, 6, 4);
            statusView.setText("X3 cursor on");
            statusView.setVisibility(View.GONE);

            FrameLayout.LayoutParams cursorParams =
                    new FrameLayout.LayoutParams(CURSOR_SIZE, CURSOR_SIZE);
            cursorParams.gravity = Gravity.START | Gravity.TOP;
            leftCursorView.setLayoutParams(cursorParams);

            FrameLayout.LayoutParams rightCursorParams =
                    new FrameLayout.LayoutParams(CURSOR_SIZE, CURSOR_SIZE);
            rightCursorParams.gravity = Gravity.START | Gravity.TOP;
            rightCursorView.setLayoutParams(rightCursorParams);

            FrameLayout.LayoutParams statusParams =
                    new FrameLayout.LayoutParams(
                            ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            statusParams.gravity = Gravity.TOP | Gravity.START;
            statusParams.leftMargin = 8;
            statusParams.topMargin = 8;
            statusView.setLayoutParams(statusParams);

            content.addView(mirrorView);
            content.addView(leftCursorView);
            content.addView(rightCursorView);
            content.addView(statusView);
            mirrorView.bringToFront();
            leftCursorView.bringToFront();
            rightCursorView.bringToFront();
            statusView.bringToFront();
            content.addOnLayoutChangeListener(layoutListener);
            sourceRoot.addOnLayoutChangeListener(layoutListener);

            content.post(
                    new Runnable() {
                        @Override
                        public void run() {
                            showLeftMirrorPopup();
                            enforceRootBounds();
                            ensureBitmap(EYE_WIDTH, EYE_HEIGHT);
                            drawDebugFrame();
                            mouseModeEnabled = true;
                            updateCursorViews(true);
                            start();
                        }
                    });
        }

        void showLeftMirrorPopup() {
            if (leftMirrorPopup != null && leftMirrorPopup.isShowing()) {
                return;
            }

            try {
                leftMirrorPopup = new PopupWindow(leftMirrorView, EYE_WIDTH, EYE_HEIGHT, false);
                leftMirrorPopup.setTouchable(false);
                leftMirrorPopup.setFocusable(false);
                leftMirrorPopup.setOutsideTouchable(false);
                leftMirrorPopup.setClippingEnabled(false);
                leftMirrorPopup.showAtLocation(content, Gravity.TOP | Gravity.START, 0, 0);
            } catch (Throwable ignored) {
                leftMirrorPopup = null;
            }
        }

        void start() {
            if (running) {
                return;
            }
            running = true;
            Choreographer.getInstance().removeFrameCallback(frameCallback);
            Choreographer.getInstance().postFrameCallback(frameCallback);
        }

        void stop() {
            running = false;
            handler.removeCallbacks(cursorHideRunnable);
            Choreographer.getInstance().removeFrameCallback(frameCallback);
            pixelCopyInFlight = false;
            pixelCopyModeActive = false;
        }

        void destroy() {
            stop();
            cancelPendingClick();
            cancelSyntheticSwipe();
            finishDrag(MotionEvent.ACTION_CANCEL, SystemClock.uptimeMillis());
            content.removeOnLayoutChangeListener(layoutListener);
            sourceRoot.removeOnLayoutChangeListener(layoutListener);
            if (leftMirrorPopup != null) {
                try {
                    leftMirrorPopup.dismiss();
                } catch (Throwable ignored) {
                }
                leftMirrorPopup = null;
            }
            content.removeView(leftCursorView);
            content.removeView(rightCursorView);
            content.removeView(mirrorView);
            content.removeView(statusView);
            if (bitmap != null && !bitmap.isRecycled()) {
                bitmap.recycle();
            }
            if (textureBitmap != null && !textureBitmap.isRecycled()) {
                textureBitmap.recycle();
            }
            if (surfaceBitmap != null && !surfaceBitmap.isRecycled()) {
                surfaceBitmap.recycle();
            }
        }

        void setVideoMirrorOverride(boolean enabled) {
            if (videoMirrorOverride == enabled) {
                return;
            }
            videoMirrorOverride = enabled;
            updateVideoCursorState();
        }

        boolean handleMotionEvent(MotionEvent event) {
            if (event == null) {
                return false;
            }

            String deviceName = getDeviceName(event);
            if (deviceName == null) {
                return false;
            }

            int action = event.getActionMasked();
            float rawX = getGestureX(event);
            float rawY = getGestureY(event);
            if (isBrowserScrollModeSupported()) {
                updateStatus(
                        "motion "
                                + shortDeviceName(deviceName)
                                + " "
                                + actionName(action)
                                + " "
                                + (int) rawX
                                + ","
                                + (int) rawY);
            }

            if (isBrowserScrollModeSupported() && isBrowserScrollToggleDevice(deviceName)) {
                onTempleInteraction();
                return handleBrowserToggleMotion(action, rawX, rawY);
            }

            if (!isTempleTouchDevice(deviceName)) {
                return false;
            }
            updateStatus(
                    "touch "
                            + shortDeviceName(deviceName)
                            + " "
                            + actionName(action)
                            + " "
                            + (int) rawX
                            + ","
                            + (int) rawY);

            boolean browserScrollMode = isBrowserScrollModeActive();
            if (!mouseModeEnabled && !browserScrollMode) {
                return false;
            }

            onTempleInteraction();
            if (browserScrollMode) {
                updateCursorViews(true);
                return handleBrowserScrollMotion(action, rawX, rawY, event.getEventTime());
            }

            switch (action) {
                case MotionEvent.ACTION_DOWN:
                    sawTempleDown = true;
                    touchDownTime = event.getEventTime();
                    touchDownCursorX = cursorX;
                    touchDownCursorY = cursorY;
                    templeDownRawX = rawX;
                    templeDownRawY = rawY;
                    templeCursorOriginX = cursorX;
                    templeCursorOriginY = cursorY;
                    cursorMovedSinceDown = false;
                    return true;
                case MotionEvent.ACTION_MOVE:
                case MotionEvent.ACTION_HOVER_MOVE:
                    if (!sawTempleDown) {
                        sawTempleDown = true;
                        touchDownTime = event.getEventTime();
                        touchDownCursorX = cursorX;
                        touchDownCursorY = cursorY;
                        templeDownRawX = rawX;
                        templeDownRawY = rawY;
                        templeCursorOriginX = cursorX;
                        templeCursorOriginY = cursorY;
                        cursorMovedSinceDown = false;
                        return true;
                    }
                    float deltaX = (templeDownRawX - rawX) * CURSOR_GAIN_X;
                    float deltaY = (rawY - templeDownRawY) * CURSOR_GAIN_Y;
                    moveCursorTo(templeCursorOriginX + deltaX, templeCursorOriginY + deltaY);
                    if (Math.abs(deltaX) > 8.0f || Math.abs(deltaY) > 8.0f) {
                        cursorMovedSinceDown = true;
                    }
                    return true;
                case MotionEvent.ACTION_UP:
                    if (!sawTempleDown) {
                        touchDownTime = event.getEventTime();
                        touchDownCursorX = cursorX;
                        touchDownCursorY = cursorY;
                        cursorMovedSinceDown = false;
                    }
                    sawTempleDown = false;
                    long eventTime = event.getEventTime();
                    if (!cursorMovedSinceDown && eventTime - touchDownTime <= 300L) {
                        if (lastTempleUpTime > 0 && eventTime - lastTempleUpTime <= DOUBLE_TAP_TIMEOUT_MS) {
                            lastTempleUpTime = 0L;
                            cancelPendingClick();
                            performGlobalBack();
                            return true;
                        }
                        lastTempleUpTime = eventTime;
                        scheduleClickAtCursor();
                    } else {
                        lastTempleUpTime = 0L;
                    }
                    cursorMovedSinceDown = false;
                    return true;
                case MotionEvent.ACTION_CANCEL:
                    sawTempleDown = false;
                    cursorMovedSinceDown = false;
                    finishDrag(action, event.getEventTime());
                    return true;
                default:
                    return true;
            }
        }

        boolean handleGenericMotionEvent(MotionEvent event) {
            return handleMotionEvent(event);
        }

        boolean handleKeyEvent(KeyEvent event) {
            if (event == null) {
                return false;
            }

            String deviceName = getDeviceName(event);
            if (deviceName == null) {
                return false;
            }

            if (isBrowserScrollModeSupported()) {
                updateStatus(
                        "key "
                                + shortDeviceName(deviceName)
                                + " "
                                + event.getKeyCode()
                                + " "
                                + event.getAction());
            }

            String lower = deviceName.toLowerCase(Locale.US);
            boolean templeDevice = lower.contains("cyttsp") || lower.contains("capsense");
            if (templeDevice) {
                updateStatus(
                        "key "
                                + shortDeviceName(deviceName)
                                + " "
                                + event.getKeyCode()
                                + " "
                                + event.getAction());
            }
            if (!templeDevice) {
                return false;
            }
            int keyCode = event.getKeyCode();
            if (isBrowserScrollModeSupported()) {
                if ((keyCode == KeyEvent.KEYCODE_MEDIA_PREVIOUS
                                || keyCode == KeyEvent.KEYCODE_MEDIA_NEXT)
                        && event.getAction() == KeyEvent.ACTION_DOWN
                        && event.getRepeatCount() == 0) {
                    toggleBrowserScrollMode();
                    return true;
                }
                return false;
            }
            if (keyCode == KeyEvent.KEYCODE_MEDIA_PREVIOUS
                    || keyCode == KeyEvent.KEYCODE_MEDIA_NEXT) {
                if (event.getAction() == KeyEvent.ACTION_DOWN && event.getRepeatCount() == 0) {
                    performLeftTempleVerticalSwipe(keyCode == KeyEvent.KEYCODE_MEDIA_PREVIOUS);
                }
                return true;
            }
            return false;
        }

        void enforceRootBounds() {
            ViewGroup.LayoutParams rawParams = sourceRoot.getLayoutParams();
            FrameLayout.LayoutParams params;
            if (rawParams instanceof FrameLayout.LayoutParams) {
                params = (FrameLayout.LayoutParams) rawParams;
            } else {
                params = new FrameLayout.LayoutParams(EYE_WIDTH, EYE_HEIGHT);
            }

            boolean changed = false;
            if (params.width != EYE_WIDTH) {
                params.width = EYE_WIDTH;
                changed = true;
            }
            if (params.height != EYE_HEIGHT) {
                params.height = EYE_HEIGHT;
                changed = true;
            }
            if (params.gravity != (Gravity.START | Gravity.TOP)) {
                params.gravity = Gravity.START | Gravity.TOP;
                changed = true;
            }
            if (params.leftMargin != 0) {
                params.leftMargin = 0;
                changed = true;
            }
            if (params.topMargin != 0) {
                params.topMargin = 0;
                changed = true;
            }
            if (changed) {
                sourceRoot.setLayoutParams(params);
            }

            sourceRoot.setX(0f);
            sourceRoot.setY(0f);
            sourceRoot.setTranslationX(0f);
            sourceRoot.setTranslationY(0f);
            updateCursorViews(mouseModeEnabled);
        }

        void ensureAppDefaults() {
            try {
                SharedPreferences prefs = activity.getSharedPreferences("BLBL", 0);
                int migrationVersion = prefs.getInt("x3DefaultsVersion", 0);
                if (migrationVersion < DEFAULTS_MIGRATION_VERSION) {
                    SharedPreferences.Editor editor = prefs.edit();
                    editor.putInt("videoCodec", DEFAULT_CODEC_AVC);
                    editor.putInt("defaultResolution", DEFAULT_RESOLUTION_720P);
                    editor.putInt("defaultStartPage", DEFAULT_START_PAGE_RECOMMEND);
                    editor.putInt("x3DefaultsVersion", DEFAULTS_MIGRATION_VERSION);
                    editor.apply();
                }
            } catch (Throwable ignored) {
            }
        }

        void ensureBitmap(int width, int height) {
            int safeWidth = width > 0 ? width : EYE_WIDTH;
            int safeHeight = height > 0 ? height : EYE_HEIGHT;

            if (bitmap != null
                    && bitmap.getWidth() == safeWidth
                    && bitmap.getHeight() == safeHeight
                    && !bitmap.isRecycled()) {
                return;
            }

            if (bitmap != null && !bitmap.isRecycled()) {
                bitmap.recycle();
            }
            bitmap = Bitmap.createBitmap(safeWidth, safeHeight, Bitmap.Config.ARGB_8888);
            leftMirrorView.setBitmap(bitmap);
            mirrorView.setBitmap(bitmap);
        }

        void ensureTextureBitmap(int width, int height) {
            int safeWidth = width > 0 ? width : 1;
            int safeHeight = height > 0 ? height : 1;

            if (textureBitmap != null
                    && textureBitmap.getWidth() == safeWidth
                    && textureBitmap.getHeight() == safeHeight
                    && !textureBitmap.isRecycled()) {
                return;
            }

            if (textureBitmap != null && !textureBitmap.isRecycled()) {
                textureBitmap.recycle();
            }
            textureBitmap = Bitmap.createBitmap(safeWidth, safeHeight, Bitmap.Config.ARGB_8888);
        }

        void ensureSurfaceBitmap(int width, int height) {
            int safeWidth = width > 0 ? width : 1;
            int safeHeight = height > 0 ? height : 1;

            if (surfaceBitmap != null
                    && surfaceBitmap.getWidth() == safeWidth
                    && surfaceBitmap.getHeight() == safeHeight
                    && !surfaceBitmap.isRecycled()) {
                return;
            }

            if (surfaceBitmap != null && !surfaceBitmap.isRecycled()) {
                surfaceBitmap.recycle();
            }
            surfaceBitmap = Bitmap.createBitmap(safeWidth, safeHeight, Bitmap.Config.ARGB_8888);
        }

        void copyLeftEye() {
            ensureBitmap(EYE_WIDTH, EYE_HEIGHT);
            if (bitmap == null || bitmap.isRecycled()) {
                return;
            }

            SurfaceView visibleSurfaceView = findVisibleSurfaceView(activity.getWindow().getDecorView());
            if (visibleSurfaceView != null) {
                if (pixelCopyModeActive) {
                    pixelCopyModeActive = false;
                    updateCursorViews(mouseModeEnabled);
                }
                drawFromSourceRoot();
                return;
            }

            if (videoSurfaceVisible) {
                requestPixelCopy();
                return;
            }

            if (pixelCopyModeActive) {
                pixelCopyModeActive = false;
                updateCursorViews(mouseModeEnabled);
            }
            drawFromSourceRoot();
        }

        void requestPixelCopy() {
            if (pixelCopyInFlight) {
                return;
            }

            if (!pixelCopyModeActive) {
                pixelCopyModeActive = true;
                updateCursorViews(mouseModeEnabled);
            }

            try {
                pixelCopyInFlight = true;
                final Rect srcRect = new Rect(0, 0, EYE_WIDTH, EYE_HEIGHT);
                PixelCopy.request(
                        activity.getWindow(),
                        srcRect,
                        bitmap,
                        new PixelCopy.OnPixelCopyFinishedListener() {
                            @Override
                            public void onPixelCopyFinished(int copyResult) {
                                pixelCopyInFlight = false;
                                if (copyResult == PixelCopy.SUCCESS) {
                                    drawBitmapToSurface();
                                    return;
                                }
                                if (pixelCopyModeActive) {
                                    pixelCopyModeActive = false;
                                    updateCursorViews(mouseModeEnabled);
                                }
                                drawFromSourceRoot();
                            }
                        },
                        handler);
            } catch (Throwable ignored) {
                pixelCopyInFlight = false;
                if (pixelCopyModeActive) {
                    pixelCopyModeActive = false;
                    updateCursorViews(mouseModeEnabled);
                }
                drawFromSourceRoot();
            }
        }

        void drawFromSourceRoot() {
            if (bitmap == null || bitmap.isRecycled()) {
                return;
            }

            try {
                Canvas bitmapCanvas = new Canvas(bitmap);
                bitmapCanvas.drawColor(Color.BLACK);
                int leftMirrorVisibility = leftMirrorView.getVisibility();
                int leftCursorVisibility = leftCursorView.getVisibility();
                int rightCursorVisibility = rightCursorView.getVisibility();
                int statusVisibility = statusView.getVisibility();
                ArrayList<HiddenViewState> hiddenViewStates = new ArrayList<HiddenViewState>();
                try {
                    leftMirrorView.setVisibility(View.GONE);
                    leftCursorView.setVisibility(View.GONE);
                    rightCursorView.setVisibility(View.GONE);
                    statusView.setVisibility(View.GONE);
                    drawSurfaceViews(bitmapCanvas, activity.getWindow().getDecorView());
                    drawTextureViews(bitmapCanvas, content);
                    hideMirrorOccluders(activity.getWindow().getDecorView(), hiddenViewStates);
                    content.draw(bitmapCanvas);
                } finally {
                    restoreHiddenViews(hiddenViewStates);
                    leftMirrorView.setVisibility(leftMirrorVisibility);
                    leftCursorView.setVisibility(leftCursorVisibility);
                    rightCursorView.setVisibility(rightCursorVisibility);
                    statusView.setVisibility(statusVisibility);
                }
                drawBitmapToSurface();
            } catch (Throwable ignored) {
                drawDebugFrame();
            }
        }

        void drawTextureViews(Canvas canvas, View view) {
            if (view == null || view.getVisibility() != View.VISIBLE) {
                return;
            }

            if (view == leftMirrorView
                    || view == mirrorView
                    || view == leftCursorView
                    || view == rightCursorView
                    || view == statusView) {
                return;
            }

            if (view instanceof TextureView) {
                drawTextureView(canvas, (TextureView) view);
            }

            if (!(view instanceof ViewGroup)) {
                return;
            }

            ViewGroup group = (ViewGroup) view;
            int childCount = group.getChildCount();
            for (int i = 0; i < childCount; i++) {
                drawTextureViews(canvas, group.getChildAt(i));
            }
        }

        void drawSurfaceViews(Canvas canvas, View view) {
            if (view == null || view.getVisibility() != View.VISIBLE) {
                return;
            }

            if (view == leftMirrorView
                    || view == mirrorView
                    || view == leftCursorView
                    || view == rightCursorView
                    || view == statusView) {
                return;
            }

            if (view instanceof SurfaceView) {
                drawSurfaceView(canvas, (SurfaceView) view);
            }

            if (!(view instanceof ViewGroup)) {
                return;
            }

            ViewGroup group = (ViewGroup) view;
            int childCount = group.getChildCount();
            for (int i = 0; i < childCount; i++) {
                drawSurfaceViews(canvas, group.getChildAt(i));
            }
        }

        void drawTextureView(Canvas canvas, TextureView textureView) {
            if (textureView == null || !textureView.isAvailable()) {
                return;
            }

            int width = textureView.getWidth();
            int height = textureView.getHeight();
            if (width <= 0 || height <= 0) {
                return;
            }

            ensureTextureBitmap(width, height);
            if (textureBitmap == null || textureBitmap.isRecycled()) {
                return;
            }

            textureView.getBitmap(textureBitmap);
            content.getLocationOnScreen(rootLocation);
            textureView.getLocationOnScreen(textureLocation);

            int left = textureLocation[0] - rootLocation[0];
            int top = textureLocation[1] - rootLocation[1];
            Rect src = new Rect(0, 0, width, height);
            Rect dst = new Rect(left, top, left + width, top + height);
            canvas.drawBitmap(textureBitmap, src, dst, null);
        }

        void drawSurfaceView(Canvas canvas, SurfaceView surfaceView) {
            if (surfaceView == null || surfaceView.getWidth() <= 0 || surfaceView.getHeight() <= 0) {
                return;
            }

            int width = surfaceView.getWidth();
            int height = surfaceView.getHeight();
            ensureSurfaceBitmap(width, height);
            if (surfaceBitmap == null || surfaceBitmap.isRecycled()) {
                return;
            }

            content.getLocationOnScreen(rootLocation);
            surfaceView.getLocationOnScreen(surfaceLocation);
            int left = surfaceLocation[0] - rootLocation[0];
            int top = surfaceLocation[1] - rootLocation[1];
            Rect src = new Rect(0, 0, width, height);
            Rect dst = new Rect(left, top, left + width, top + height);
            canvas.drawBitmap(surfaceBitmap, src, dst, null);
            requestSurfaceCopy(surfaceView);
        }

        void requestSurfaceCopy(SurfaceView surfaceView) {
            if (surfaceCopyInFlight
                    || surfaceView == null
                    || surfaceBitmap == null
                    || surfaceBitmap.isRecycled()) {
                return;
            }

            try {
                surfaceCopyInFlight = true;
                PixelCopy.request(
                        surfaceView,
                        surfaceBitmap,
                        new PixelCopy.OnPixelCopyFinishedListener() {
                            @Override
                            public void onPixelCopyFinished(int copyResult) {
                                surfaceCopyInFlight = false;
                                if (copyResult == PixelCopy.SUCCESS) {
                                    drawBitmapToSurface();
                                }
                            }
                        },
                        handler);
            } catch (Throwable ignored) {
                surfaceCopyInFlight = false;
            }
        }

        void hideMirrorOccluders(View view, ArrayList<HiddenViewState> hiddenViewStates) {
            if (!videoSurfaceVisible || view == null || view.getVisibility() != View.VISIBLE) {
                return;
            }

            if (shouldHideForMirror(view)) {
                hiddenViewStates.add(new HiddenViewState(view, view.getVisibility()));
                view.setVisibility(View.INVISIBLE);
                return;
            }

            if (!(view instanceof ViewGroup)) {
                return;
            }

            ViewGroup group = (ViewGroup) view;
            int childCount = group.getChildCount();
            for (int i = 0; i < childCount; i++) {
                hideMirrorOccluders(group.getChildAt(i), hiddenViewStates);
            }
        }

        boolean shouldHideForMirror(View view) {
            if (view instanceof TextureView || view instanceof SurfaceView) {
                return true;
            }

            int id = view.getId();
            if (id == View.NO_ID) {
                return false;
            }

            try {
                return "exo_shutter".equals(activity.getResources().getResourceEntryName(id));
            } catch (Throwable ignored) {
                return false;
            }
        }

        void restoreHiddenViews(ArrayList<HiddenViewState> hiddenViewStates) {
            int count = hiddenViewStates.size();
            for (int i = 0; i < count; i++) {
                HiddenViewState state = hiddenViewStates.get(i);
                state.view.setVisibility(state.visibility);
            }
        }

        void drawDebugFrame() {
            try {
                Canvas canvas = new Canvas(bitmap);
                canvas.drawColor(Color.BLACK);
                canvas.drawRect(0, 0, 24, EYE_HEIGHT, makePaint(Color.rgb(0, 180, 0)));
                canvas.drawRect(24, 0, 48, EYE_HEIGHT, makePaint(Color.rgb(180, 0, 0)));
                canvas.drawRect(48, 0, 72, EYE_HEIGHT, makePaint(Color.rgb(0, 0, 180)));
                updateSynchronizedMirrorState();
                leftMirrorView.postInvalidateOnAnimation();
                mirrorView.postInvalidateOnAnimation();
            } catch (Throwable ignored) {
            }
        }

        void drawBitmapToSurface() {
            if (bitmap == null || bitmap.isRecycled()) {
                return;
            }

            try {
                updateSynchronizedMirrorState();
                leftMirrorView.postInvalidateOnAnimation();
                mirrorView.postInvalidateOnAnimation();
            } catch (Throwable ignored) {
            }
        }

        void moveCursor(float dx, float dy) {
            cursorX = clamp(cursorX + dx, 0.0f, EYE_WIDTH);
            cursorY = clamp(cursorY + dy, 0.0f, EYE_HEIGHT);
            updateStatus("cursor " + (int) cursorX + "," + (int) cursorY);
            updateCursorViews(mouseModeEnabled);
        }

        void applyBridgeMove(float dx, float dy) {
            mouseModeEnabled = true;
            onTempleInteraction();
            moveCursor(dx, dy);
        }

        void applyBridgeClick() {
            mouseModeEnabled = true;
            onTempleInteraction();
            cancelPendingClick();
            dispatchClickAtCursor();
        }

        void applyBridgeBack() {
            mouseModeEnabled = true;
            onTempleInteraction();
            cancelPendingClick();
            performGlobalBack();
        }

        void applyBridgeShowCursor() {
            mouseModeEnabled = true;
            cursorTemporarilyHidden = false;
            updateCursorViews(true);
        }

        void moveCursorTo(float x, float y) {
            cursorX = clamp(x, 0.0f, EYE_WIDTH);
            cursorY = clamp(y, 0.0f, EYE_HEIGHT);
            updateStatus("cursor " + (int) cursorX + "," + (int) cursorY);
            updateCursorViews(mouseModeEnabled);
        }

        void updateCursorViews(boolean visible) {
            float half = CURSOR_SIZE / 2.0f;
            leftCursorView.setX(cursorX - half);
            leftCursorView.setY(cursorY - half);
            rightCursorView.setX(cursorX + EYE_WIDTH - half);
            rightCursorView.setY(cursorY - half);
            boolean grabMode = isBrowserScrollModeActive();
            leftCursorView.setGrabMode(grabMode);
            rightCursorView.setGrabMode(grabMode);

            boolean show = visible && mouseModeEnabled && !cursorTemporarilyHidden;
            leftCursorView.setVisibility(show ? View.VISIBLE : View.GONE);
            rightCursorView.setVisibility(show && !pixelCopyModeActive ? View.VISIBLE : View.GONE);
            leftCursorView.invalidate();
            rightCursorView.invalidate();
        }

        void dispatchClickAtCursor() {
            dispatchClickAt(cursorX, cursorY);
        }

        void dispatchClickAt(final float x, final float y) {
            final long downTime = SystemClock.uptimeMillis();
            dispatchSyntheticTouch(MotionEvent.ACTION_DOWN, x, y, downTime, downTime);
            handler.postDelayed(
                    new Runnable() {
                        @Override
                        public void run() {
                            dispatchSyntheticTouch(
                                    MotionEvent.ACTION_UP,
                                    x,
                                    y,
                                    SystemClock.uptimeMillis(),
                                    downTime);
                        }
                    },
                    CLICK_UP_DELAY_MS);
        }

        void scheduleClickAtCursor() {
            final float x = cursorX;
            final float y = cursorY;
            cancelPendingClick();
            pendingClickRunnable =
                    new Runnable() {
                        @Override
                        public void run() {
                            updateStatus("tap click " + (int) x + "," + (int) y);
                            dispatchClickAt(x, y);
                            pendingClickRunnable = null;
                        }
                    };
            handler.postDelayed(pendingClickRunnable, SINGLE_TAP_CONFIRM_DELAY_MS);
        }

        void cancelPendingClick() {
            if (pendingClickRunnable != null) {
                handler.removeCallbacks(pendingClickRunnable);
                pendingClickRunnable = null;
            }
        }

        void performGlobalBack() {
            updateStatus("global back");
            if (LightningX3Helper.handleGlobalBack(activity)) {
                return;
            }
            try {
                activity.onBackPressed();
            } catch (Throwable ignored) {
            }
        }

        void performLeftTempleVerticalSwipe(boolean upward) {
            performLeftTempleVerticalSwipe(upward, 1.0f);
        }

        void performLeftTempleVerticalSwipe(boolean upward, float strength) {
            onTempleInteraction();
            cancelPendingClick();
            finishDrag(MotionEvent.ACTION_CANCEL, SystemClock.uptimeMillis());
            float centerX = EYE_WIDTH / 2.0f;
            float clampedStrength = clamp(strength, 0.2f, 1.0f);
            float halfSpan = (EYE_HEIGHT * 0.44f * clampedStrength) / 2.0f;
            float centerY = EYE_HEIGHT / 2.0f;
            float startY = upward ? centerY + halfSpan : centerY - halfSpan;
            float endY = upward ? centerY - halfSpan : centerY + halfSpan;
            dispatchSyntheticSwipe(centerX, startY, centerX, endY, APP_SWIPE_DURATION_MS);
            updateStatus(upward ? "left swipe up" : "left swipe down");
        }

        boolean isBrowserScrollModeSupported() {
            String packageName = activity.getPackageName();
            return BROWSER_PACKAGE.equals(packageName) || BBLL_PACKAGE.equals(packageName);
        }

        boolean isBrowserScrollModeActive() {
            return isBrowserScrollModeSupported() && browserScrollModeEnabled;
        }

        void toggleBrowserScrollMode() {
            if (!isBrowserScrollModeSupported()) {
                return;
            }
            browserScrollModeEnabled = !browserScrollModeEnabled;
            browserScrollGestureActive = false;
            browserToggleGestureActive = false;
            cancelPendingClick();
            finishDrag(MotionEvent.ACTION_CANCEL, SystemClock.uptimeMillis());
            lastTempleUpTime = 0L;
            updateCursorViews(mouseModeEnabled);
            updateStatus(browserScrollModeEnabled ? "scroll mode on" : "cursor mode on");
            try {
                Toast.makeText(
                                activity,
                                browserScrollModeEnabled ? "滑动模式" : "光标模式",
                                Toast.LENGTH_SHORT)
                        .show();
            } catch (Throwable ignored) {
            }
        }

        boolean handleBrowserToggleMotion(int action, float rawX, float rawY) {
            switch (action) {
                case MotionEvent.ACTION_DOWN:
                    browserToggleGestureActive = true;
                    browserToggleRawX = rawX;
                    browserToggleRawY = rawY;
                    return true;
                case MotionEvent.ACTION_MOVE:
                case MotionEvent.ACTION_HOVER_MOVE:
                    if (!browserToggleGestureActive) {
                        browserToggleGestureActive = true;
                        browserToggleRawX = rawX;
                        browserToggleRawY = rawY;
                    }
                    return true;
                case MotionEvent.ACTION_UP:
                    float deltaX = rawX - browserToggleRawX;
                    float deltaY = rawY - browserToggleRawY;
                    browserToggleGestureActive = false;
                    if (Math.abs(deltaY) >= BROWSER_TOGGLE_TRIGGER_PX
                            && Math.abs(deltaY) >= Math.abs(deltaX)) {
                        toggleBrowserScrollMode();
                    } else {
                        updateStatus("toggle ignored");
                    }
                    return true;
                case MotionEvent.ACTION_CANCEL:
                    browserToggleGestureActive = false;
                    return true;
                default:
                    return true;
            }
        }

        boolean handleBrowserScrollMotion(int action, float rawX, float rawY, long eventTime) {
            cancelPendingClick();
            switch (action) {
                case MotionEvent.ACTION_DOWN:
                    browserScrollGestureActive = true;
                    browserScrollRawX = rawX;
                    browserScrollRawY = rawY;
                    updateStatus("scroll ready");
                    return true;
                case MotionEvent.ACTION_MOVE:
                case MotionEvent.ACTION_HOVER_MOVE:
                    if (!browserScrollGestureActive) {
                        browserScrollGestureActive = true;
                        browserScrollRawX = rawX;
                        browserScrollRawY = rawY;
                        return true;
                    }
                    float deltaX = (rawX - browserScrollRawX) * getScrollModeSensitivityScaleX();
                    float deltaY = (rawY - browserScrollRawY) * getScrollModeSensitivityScaleY();
                    if (Math.abs(deltaX) < BROWSER_SCROLL_TRIGGER_PX
                            && Math.abs(deltaY) < BROWSER_SCROLL_TRIGGER_PX) {
                        return true;
                    }
                    finishDrag(MotionEvent.ACTION_CANCEL, eventTime);
                    dispatchBrowserScrollStep(deltaX, deltaY);
                    browserScrollRawX = rawX;
                    browserScrollRawY = rawY;
                    return true;
                case MotionEvent.ACTION_UP:
                case MotionEvent.ACTION_CANCEL:
                    browserScrollGestureActive = false;
                    updateCursorViews(mouseModeEnabled);
                    return true;
                default:
                    return true;
            }
        }

        void dispatchBrowserScrollStep(float deltaX, float deltaY) {
            float centerX = EYE_WIDTH / 2.0f;
            float centerY = EYE_HEIGHT / 2.0f;
            if (Math.abs(deltaY) >= Math.abs(deltaX)) {
                boolean upward = deltaY < 0.0f;
                float startY = upward ? EYE_HEIGHT * 0.72f : EYE_HEIGHT * 0.28f;
                float endY = upward ? EYE_HEIGHT * 0.28f : EYE_HEIGHT * 0.72f;
                dispatchSyntheticSwipe(centerX, startY, centerX, endY, APP_SWIPE_DURATION_MS);
                updateStatus(upward ? "scroll up" : "scroll down");
                return;
            }

            boolean leftward = deltaX < 0.0f;
            float startX = leftward ? EYE_WIDTH * 0.72f : EYE_WIDTH * 0.28f;
            float endX = leftward ? EYE_WIDTH * 0.28f : EYE_WIDTH * 0.72f;
            dispatchSyntheticSwipe(startX, centerY, endX, centerY, APP_SWIPE_DURATION_MS);
            updateStatus(leftward ? "scroll left" : "scroll right");
        }

        float getScrollModeSensitivityScaleX() {
            return 1.0f;
        }

        float getScrollModeSensitivityScaleY() {
            return BBLL_PACKAGE.equals(activity.getPackageName()) ? 0.5f : 1.0f;
        }

        void updateSynchronizedMirrorState() {
            leftMirrorView.setVisibility(shouldUseSynchronizedMirror() ? View.VISIBLE : View.GONE);
        }

        boolean shouldUseSynchronizedMirror() {
            return BBLL_PACKAGE.equals(activity.getPackageName())
                    && (isBrowserScrollModeActive() || videoSurfaceVisible);
        }

        void onTempleInteraction() {
            if (cursorTemporarilyHidden) {
                cursorTemporarilyHidden = false;
                updateCursorViews(true);
            }
            if (videoSurfaceVisible) {
                handler.removeCallbacks(cursorHideRunnable);
                handler.postDelayed(cursorHideRunnable, CURSOR_HIDE_DELAY_MS);
            }
        }

        SurfaceView findVisibleSurfaceView(View view) {
            if (view == null || view.getVisibility() != View.VISIBLE) {
                return null;
            }
            if (view == leftMirrorView
                    || view == mirrorView
                    || view == leftCursorView
                    || view == rightCursorView
                    || view == statusView) {
                return null;
            }
            if (view instanceof SurfaceView && view.getWidth() > 0 && view.getHeight() > 0) {
                return (SurfaceView) view;
            }
            if (!(view instanceof ViewGroup)) {
                return null;
            }
            ViewGroup group = (ViewGroup) view;
            int childCount = group.getChildCount();
            for (int i = 0; i < childCount; i++) {
                SurfaceView surface = findVisibleSurfaceView(group.getChildAt(i));
                if (surface != null) {
                    return surface;
                }
            }
            return null;
        }

        void updateVideoCursorState() {
            boolean hasVisibleVideo = videoMirrorOverride || hasVisibleTextureView(content);
            if (hasVisibleVideo == videoSurfaceVisible) {
                return;
            }
            videoSurfaceVisible = hasVisibleVideo;
            handler.removeCallbacks(cursorHideRunnable);
            if (!videoSurfaceVisible) {
                if (cursorTemporarilyHidden) {
                    cursorTemporarilyHidden = false;
                    updateCursorViews(true);
                }
                return;
            }
            handler.postDelayed(cursorHideRunnable, CURSOR_HIDE_DELAY_MS);
        }

        boolean hasVisibleTextureView(View view) {
            if (view == null || view.getVisibility() != View.VISIBLE) {
                return false;
            }
            if (view == leftMirrorView
                    || view == mirrorView
                    || view == leftCursorView
                    || view == rightCursorView
                    || view == statusView) {
                return false;
            }
            if (view instanceof TextureView || view instanceof SurfaceView) {
                return view.getWidth() > 0 && view.getHeight() > 0;
            }
            if (!(view instanceof ViewGroup)) {
                return false;
            }
            ViewGroup group = (ViewGroup) view;
            int childCount = group.getChildCount();
            for (int i = 0; i < childCount; i++) {
                if (hasVisibleTextureView(group.getChildAt(i))) {
                    return true;
                }
            }
            return false;
        }

        void finishDrag(int action, long eventTime) {
            if (!dragActive) {
                return;
            }

            int finalAction =
                    action == MotionEvent.ACTION_CANCEL
                            ? MotionEvent.ACTION_CANCEL
                            : MotionEvent.ACTION_UP;
            dispatchSyntheticTouch(finalAction, cursorX, cursorY, eventTime, dragDownTime);
            dragActive = false;
            updateStatus(finalAction == MotionEvent.ACTION_CANCEL ? "drag cancel" : "drag end");
        }

        void dispatchSyntheticSwipe(
                final float startX,
                final float startY,
                final float endX,
                final float endY,
                final long durationMs) {
            cancelSyntheticSwipe();
            final int token = ++syntheticSwipeToken;
            final long downTime = SystemClock.uptimeMillis();
            syntheticSwipeActive = true;
            syntheticSwipeDownTime = downTime;
            syntheticSwipeLastX = startX;
            syntheticSwipeLastY = startY;
            dispatchSyntheticTouch(MotionEvent.ACTION_DOWN, startX, startY, downTime, downTime);
            for (int i = 1; i < APP_SWIPE_STEP_COUNT; i++) {
                final int step = i;
                handler.postDelayed(
                        new Runnable() {
                            @Override
                            public void run() {
                                if (!isSyntheticSwipeTokenActive(token)) {
                                    return;
                                }
                                float progress = (float) step / (float) APP_SWIPE_STEP_COUNT;
                                float moveX = lerp(startX, endX, progress);
                                float moveY = lerp(startY, endY, progress);
                                syntheticSwipeLastX = moveX;
                                syntheticSwipeLastY = moveY;
                                dispatchSyntheticTouch(
                                        MotionEvent.ACTION_MOVE,
                                        moveX,
                                        moveY,
                                        SystemClock.uptimeMillis(),
                                        downTime);
                            }
                        },
                        (durationMs * step) / APP_SWIPE_STEP_COUNT);
            }
            handler.postDelayed(
                    new Runnable() {
                        @Override
                        public void run() {
                            if (!isSyntheticSwipeTokenActive(token)) {
                                return;
                            }
                            syntheticSwipeLastX = endX;
                            syntheticSwipeLastY = endY;
                            dispatchSyntheticTouch(
                                    MotionEvent.ACTION_UP,
                                    endX,
                                    endY,
                                    SystemClock.uptimeMillis(),
                                    downTime);
                            syntheticSwipeActive = false;
                        }
                    },
                    durationMs);
        }

        void cancelSyntheticSwipe() {
            if (!syntheticSwipeActive) {
                return;
            }
            dispatchSyntheticTouch(
                    MotionEvent.ACTION_CANCEL,
                    syntheticSwipeLastX,
                    syntheticSwipeLastY,
                    SystemClock.uptimeMillis(),
                    syntheticSwipeDownTime);
            syntheticSwipeActive = false;
            syntheticSwipeToken++;
        }

        boolean isSyntheticSwipeTokenActive(int token) {
            return syntheticSwipeActive && syntheticSwipeToken == token;
        }

        void dispatchSyntheticTouch(
                int action, float x, float y, long eventTime, long downTime) {
            MotionEvent synthetic = MotionEvent.obtain(downTime, eventTime, action, x, y, 0);
            synthetic.setSource(InputDevice.SOURCE_TOUCHSCREEN);
            try {
                sourceRoot.dispatchTouchEvent(synthetic);
            } catch (Throwable ignored) {
            } finally {
                synthetic.recycle();
            }
        }

        float lerp(float start, float end, float progress) {
            return start + ((end - start) * progress);
        }

        void showToast(String message) {
            try {
                Toast.makeText(activity, message, Toast.LENGTH_SHORT).show();
            } catch (Throwable ignored) {
            }
        }

        String getDeviceName(MotionEvent event) {
            if (event.getDevice() != null && event.getDevice().getName() != null) {
                return event.getDevice().getName();
            }

            InputDevice inputDevice = InputDevice.getDevice(event.getDeviceId());
            if (inputDevice != null) {
                return inputDevice.getName();
            }

            return null;
        }

        String getDeviceName(KeyEvent event) {
            if (event.getDevice() != null && event.getDevice().getName() != null) {
                return event.getDevice().getName();
            }

            InputDevice inputDevice = InputDevice.getDevice(event.getDeviceId());
            if (inputDevice != null) {
                return inputDevice.getName();
            }

            return null;
        }

        boolean isNamedDevice(String deviceName, String expected) {
            return deviceName.toLowerCase(Locale.US).contains(expected);
        }

        boolean isBrowserScrollToggleDevice(String deviceName) {
            return isNamedDevice(deviceName, BROWSER_SCROLL_TOGGLE_DEVICE);
        }

        boolean isTempleTouchDevice(String deviceName) {
            return isNamedDevice(deviceName, DEVICE_TRACKPAD);
        }

        String shortDeviceName(String deviceName) {
            if (deviceName == null) {
                return "?";
            }
            if (deviceName.length() <= 14) {
                return deviceName;
            }
            return deviceName.substring(0, 14);
        }

        String actionName(int action) {
            switch (action) {
                case MotionEvent.ACTION_DOWN:
                    return "DOWN";
                case MotionEvent.ACTION_UP:
                    return "UP";
                case MotionEvent.ACTION_POINTER_DOWN:
                    return "POINTER_DOWN";
                case MotionEvent.ACTION_POINTER_UP:
                    return "POINTER_UP";
                case MotionEvent.ACTION_MOVE:
                    return "MOVE";
                case MotionEvent.ACTION_CANCEL:
                    return "CANCEL";
                case MotionEvent.ACTION_HOVER_MOVE:
                    return "HOVER";
                default:
                    return String.valueOf(action);
            }
        }

        void updateStatus(final String text) {
            if (statusView.getVisibility() != View.VISIBLE) {
                return;
            }
            handler.post(
                    new Runnable() {
                        @Override
                        public void run() {
                            statusView.setText(text);
                        }
                    });
        }

        float clamp(float value, float min, float max) {
            if (value < min) {
                return min;
            }
            if (value > max) {
                return max;
            }
            return value;
        }

        float getGestureX(MotionEvent event) {
            if (event == null || event.getPointerCount() <= 0) {
                return 0.0f;
            }
            return event.getX(0);
        }

        float getGestureY(MotionEvent event) {
            if (event == null || event.getPointerCount() <= 0) {
                return 0.0f;
            }
            return event.getY(0);
        }

        private Paint makePaint(int color) {
            Paint paint = new Paint();
            paint.setColor(color);
            paint.setStyle(Paint.Style.FILL);
            return paint;
        }
    }

    private static final class CursorView extends View {
        private final Paint fillPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint strokePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private boolean grabMode;

        CursorView(Activity activity) {
            super(activity);
            fillPaint.setColor(Color.argb(200, 255, 255, 255));
            fillPaint.setStyle(Paint.Style.FILL);
            strokePaint.setColor(Color.BLACK);
            strokePaint.setStrokeWidth(2.0f);
            strokePaint.setStyle(Paint.Style.STROKE);
            setClickable(false);
            setFocusable(false);
            setVisibility(View.GONE);
        }

        void setGrabMode(boolean enabled) {
            if (grabMode == enabled) {
                return;
            }
            grabMode = enabled;
            invalidate();
        }

        @Override
        protected void onDraw(Canvas canvas) {
            super.onDraw(canvas);
            if (grabMode) {
                drawGrabCursor(canvas);
                return;
            }
            float cx = getWidth() / 2.0f;
            float cy = getHeight() / 2.0f;
            float radius = Math.min(cx, cy) - 2.0f;
            canvas.drawCircle(cx, cy, radius, fillPaint);
            canvas.drawCircle(cx, cy, radius, strokePaint);
            canvas.drawLine(cx, 2.0f, cx, getHeight() - 2.0f, strokePaint);
            canvas.drawLine(2.0f, cy, getWidth() - 2.0f, cy, strokePaint);
        }

        private void drawGrabCursor(Canvas canvas) {
            float w = getWidth();
            float h = getHeight();
            drawRoundRect(canvas, new RectF(w * 0.32f, h * 0.44f, w * 0.78f, h * 0.88f), 3.0f);
            drawRoundRect(canvas, new RectF(w * 0.14f, h * 0.52f, w * 0.38f, h * 0.70f), 3.0f);
            drawRoundRect(canvas, new RectF(w * 0.28f, h * 0.12f, w * 0.38f, h * 0.50f), 2.0f);
            drawRoundRect(canvas, new RectF(w * 0.40f, h * 0.08f, w * 0.50f, h * 0.50f), 2.0f);
            drawRoundRect(canvas, new RectF(w * 0.52f, h * 0.10f, w * 0.62f, h * 0.50f), 2.0f);
            drawRoundRect(canvas, new RectF(w * 0.64f, h * 0.16f, w * 0.74f, h * 0.52f), 2.0f);
        }

        private void drawRoundRect(Canvas canvas, RectF rect, float radius) {
            canvas.drawRoundRect(rect, radius, radius, fillPaint);
            canvas.drawRoundRect(rect, radius, radius, strokePaint);
        }
    }

    private static final class HiddenViewState {
        private final View view;
        private final int visibility;

        HiddenViewState(View view, int visibility) {
            this.view = view;
            this.visibility = visibility;
        }
    }

    private static final class MirrorBitmapView extends View {
        private Bitmap bitmap;

        MirrorBitmapView(Activity activity) {
            super(activity);
            setClickable(false);
            setFocusable(false);
            setWillNotDraw(false);
        }

        void setBitmap(Bitmap bitmap) {
            this.bitmap = bitmap;
        }

        @Override
        protected void onDraw(Canvas canvas) {
            super.onDraw(canvas);
            if (bitmap == null || bitmap.isRecycled()) {
                return;
            }
            canvas.drawBitmap(bitmap, 0.0f, 0.0f, null);
        }
    }
}
