package com.codex.x3douyin;

import android.annotation.SuppressLint;
import android.app.Activity;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.SurfaceTexture;
import android.media.AudioAttributes;
import android.media.MediaPlayer;
import android.net.Uri;
import android.opengl.GLES11Ext;
import android.opengl.GLES20;
import android.opengl.GLSurfaceView;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.util.Log;
import android.view.InputDevice;
import android.view.KeyEvent;
import android.view.MotionEvent;
import android.view.Surface;
import android.view.View;
import android.view.ViewGroup;
import android.view.Window;
import android.view.WindowManager;
import android.webkit.CookieManager;
import android.webkit.JavascriptInterface;
import android.webkit.RenderProcessGoneDetail;
import android.webkit.WebChromeClient;
import android.webkit.WebResourceRequest;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.FrameLayout;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.FloatBuffer;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;

import javax.microedition.khronos.egl.EGLConfig;
import javax.microedition.khronos.opengles.GL10;

public final class MainActivity extends Activity {
    private static final String TAG = "X3Douyin";
    private static final int EYE_WIDTH = 640;
    private static final int EYE_HEIGHT = 480;
    private static final int OVERLAY_SWEEP_DELAY_MS = 1000;
    private static final float SWIPE_THRESHOLD = 30.0f;
    private static final float TAP_SLOP = 18.0f;
    private static final long TAP_TIMEOUT_MS = 260L;
    private static final long DOUBLE_TAP_TIMEOUT_MS = 320L;
    private static final long SINGLE_TAP_DELAY_MS = 220L;
    private static final String TEMPLE_DEVICE_NAME = "cyttsp5_mt";
    private static final String HOME_URL = "https://www.douyin.com/";
    private static final String DESKTOP_USER_AGENT =
            "Mozilla/5.0 (Windows NT 10.0; Win64; x64) "
                    + "AppleWebKit/537.36 (KHTML, like Gecko) "
                    + "Chrome/131.0.0.0 Safari/537.36";

    private final Handler handler = new Handler(Looper.getMainLooper());
    private final Runnable overlaySweepRunnable =
            new Runnable() {
                @Override
                public void run() {
                    dismissLoginOverlay();
                    handler.postDelayed(this, OVERLAY_SWEEP_DELAY_MS);
                }
            };

    private WebView webView;
    private StereoWebViewContainer stereoWebViewContainer;
    private StereoVideoView stereoVideoView;
    private MediaPlayer mediaPlayer;
    private Surface nativeVideoSurface;
    private String pendingVideoId;
    private String pendingVideoUrl;
    private String playingVideoId;
    private String playingVideoUrl;
    private boolean nativePlayerPrepared;
    private boolean templeGestureActive;
    private float templeDownX;
    private float templeDownY;
    private long templeDownTime;
    private long lastTempleTapUpTime;
    private Runnable pendingSingleTap;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        configureWindow();
        createContent();
        configureWebView();
        if (savedInstanceState == null) {
            loadHome();
        } else {
            webView.restoreState(savedInstanceState);
        }
    }

    @Override
    protected void onResume() {
        super.onResume();
        enterImmersiveMode();
        handler.removeCallbacks(overlaySweepRunnable);
        handler.post(overlaySweepRunnable);
        webView.onResume();
        stereoVideoView.onResume();
        if (nativePlayerPrepared && mediaPlayer != null) {
            mediaPlayer.start();
        }
    }

    @Override
    protected void onPause() {
        handler.removeCallbacks(overlaySweepRunnable);
        if (nativePlayerPrepared && mediaPlayer != null && mediaPlayer.isPlaying()) {
            mediaPlayer.pause();
        }
        stereoVideoView.onPause();
        webView.onPause();
        super.onPause();
    }

    @Override
    protected void onDestroy() {
        handler.removeCallbacksAndMessages(null);
        releaseNativePlayer();
        if (stereoVideoView != null) {
            stereoVideoView.release();
        }
        if (webView != null) {
            webView.stopLoading();
            webView.destroy();
        }
        super.onDestroy();
    }

    @Override
    protected void onSaveInstanceState(Bundle outState) {
        webView.saveState(outState);
        super.onSaveInstanceState(outState);
    }

    @Override
    public void onWindowFocusChanged(boolean hasFocus) {
        super.onWindowFocusChanged(hasFocus);
        if (hasFocus) {
            enterImmersiveMode();
        }
    }

    @Override
    public boolean dispatchTouchEvent(MotionEvent event) {
        if (handleTempleEvent(event)) {
            return true;
        }
        return super.dispatchTouchEvent(event);
    }

    @Override
    public boolean dispatchGenericMotionEvent(MotionEvent event) {
        if (handleTempleEvent(event)) {
            return true;
        }
        return super.dispatchGenericMotionEvent(event);
    }

    @Override
    public boolean dispatchKeyEvent(KeyEvent event) {
        if (event.getAction() != KeyEvent.ACTION_DOWN) {
            return super.dispatchKeyEvent(event);
        }
        switch (event.getKeyCode()) {
            case KeyEvent.KEYCODE_DPAD_DOWN:
            case KeyEvent.KEYCODE_PAGE_DOWN:
                browseVideo(1);
                return true;
            case KeyEvent.KEYCODE_DPAD_UP:
            case KeyEvent.KEYCODE_PAGE_UP:
                browseVideo(-1);
                return true;
            case KeyEvent.KEYCODE_DPAD_CENTER:
            case KeyEvent.KEYCODE_ENTER:
                dispatchCenterTap();
                return true;
            case KeyEvent.KEYCODE_R:
                webView.reload();
                return true;
            default:
                return super.dispatchKeyEvent(event);
        }
    }

    @Override
    public void onBackPressed() {
        if (webView.canGoBack()) {
            webView.goBack();
            return;
        }
        super.onBackPressed();
    }

    private void configureWindow() {
        Window window = getWindow();
        window.setStatusBarColor(Color.BLACK);
        window.setNavigationBarColor(Color.BLACK);
        window.addFlags(
                WindowManager.LayoutParams.FLAG_FULLSCREEN
                        | WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
    }

    private void createContent() {
        FrameLayout root = new FrameLayout(this);
        root.setBackgroundColor(Color.BLACK);
        root.setClipChildren(false);
        root.setClipToPadding(false);

        stereoWebViewContainer = new StereoWebViewContainer();
        webView = new WebView(this);
        webView.setBackgroundColor(Color.BLACK);
        stereoWebViewContainer.addView(
                webView,
                new ViewGroup.LayoutParams(EYE_WIDTH, EYE_HEIGHT));
        root.addView(
                stereoWebViewContainer,
                new FrameLayout.LayoutParams(EYE_WIDTH * 2, EYE_HEIGHT));

        stereoVideoView = new StereoVideoView();
        stereoVideoView.setVisibility(View.GONE);
        root.addView(
                stereoVideoView,
                new FrameLayout.LayoutParams(EYE_WIDTH * 2, EYE_HEIGHT));

        setContentView(
                root,
                new ViewGroup.LayoutParams(
                        EYE_WIDTH * 2,
                        EYE_HEIGHT));
    }

    @SuppressLint("SetJavaScriptEnabled")
    private void configureWebView() {
        WebView.setWebContentsDebuggingEnabled(true);
        CookieManager cookies = CookieManager.getInstance();
        cookies.setAcceptCookie(true);
        cookies.setAcceptThirdPartyCookies(webView, true);

        WebSettings settings = webView.getSettings();
        settings.setJavaScriptEnabled(true);
        settings.setDomStorageEnabled(true);
        settings.setDatabaseEnabled(true);
        settings.setMediaPlaybackRequiresUserGesture(false);
        settings.setLoadsImagesAutomatically(true);
        settings.setUseWideViewPort(true);
        settings.setLoadWithOverviewMode(false);
        settings.setSupportZoom(false);
        settings.setBuiltInZoomControls(false);
        settings.setDisplayZoomControls(false);
        settings.setUserAgentString(DESKTOP_USER_AGENT);

        webView.addJavascriptInterface(new NativeVideoBridge(), "X3Native");
        webView.setWebChromeClient(new WebChromeClient());
        webView.setWebViewClient(
                new WebViewClient() {
                    @Override
                    public boolean shouldOverrideUrlLoading(
                            WebView view,
                            WebResourceRequest request) {
                        String scheme = request.getUrl().getScheme();
                        return !("http".equalsIgnoreCase(scheme)
                                || "https".equalsIgnoreCase(scheme));
                    }

                    @Override
                    public void onPageFinished(WebView view, String url) {
                        super.onPageFinished(view, url);
                        Log.i(TAG, "Loaded " + url);
                        injectX3Controls();
                    }

                    @Override
                    public boolean onRenderProcessGone(
                            WebView view,
                            RenderProcessGoneDetail detail) {
                        Log.e(TAG, "WebView renderer gone; reloading activity");
                        recreate();
                        return true;
                    }
                });
    }

    private void loadHome() {
        Map<String, String> desktopHeaders = new HashMap<>();
        desktopHeaders.put("Sec-CH-UA-Mobile", "?0");
        desktopHeaders.put("Sec-CH-UA-Platform", "\"Windows\"");
        desktopHeaders.put("Upgrade-Insecure-Requests", "1");
        webView.loadUrl(HOME_URL, desktopHeaders);
    }

    private void injectX3Controls() {
        webView.evaluateJavascript(
                "(function(){"
                        + "if(window.__x3ControlsInstalled){return;}"
                        + "window.__x3ControlsInstalled=true;"
                        + "document.documentElement.style.background='#000';"
                        + "document.body&&document.body.style&&(document.body.style.background='#000');"
                        + "var style=document.createElement('style');"
                        + "style.textContent='::-webkit-scrollbar{display:none!important;}';"
                        + "(document.head||document.documentElement).appendChild(style);"
                        + "window.__x3QueueIds=[];"
                        + "window.__x3QueueCache={};"
                        + "window.__x3QueuePromises={};"
                        + "window.__x3QueueIndex=-1;"
                        + "window.__x3RefreshQueue=function(){"
                        + "var ids=[...document.querySelectorAll('.jingxuanVideoCard')].map(function(card){"
                        + "var href=card.getAttribute('href')||'';"
                        + "var match=href.match(/\\/video\\/(\\d+)/);"
                        + "return match&&match[1];"
                        + "}).filter(Boolean);"
                        + "if(ids.length){window.__x3QueueIds=ids;}"
                        + "return window.__x3QueueIds;"
                        + "};"
                        + "window.__x3ExtractVideoDetail=function(html){"
                        + "var doc=new DOMParser().parseFromString(html,'text/html');"
                        + "var script=doc.getElementById('RENDER_DATA');"
                        + "if(!script){throw new Error('missing RENDER_DATA');}"
                        + "var data=JSON.parse(decodeURIComponent(script.textContent)).app.videoDetail;"
                        + "var src=data&&data.video&&data.video.playAddr&&data.video.playAddr[0]&&data.video.playAddr[0].src;"
                        + "if(!data||!data.awemeId||!src){throw new Error('missing video detail');}"
                        + "return {id:String(data.awemeId),url:src};"
                        + "};"
                        + "window.__x3PreloadCard=function(id){"
                        + "if(window.__x3QueueCache[id]){return Promise.resolve(window.__x3QueueCache[id]);}"
                        + "if(window.__x3QueuePromises[id]){return window.__x3QueuePromises[id];}"
                        + "var promise=fetch('/jingxuan?modal_id='+id,{credentials:'include'}).then(function(response){"
                        + "return response.text();"
                        + "}).then(window.__x3ExtractVideoDetail).then(function(info){"
                        + "window.__x3QueueCache[id]=info;"
                        + "delete window.__x3QueuePromises[id];"
                        + "return info;"
                        + "}).catch(function(){"
                        + "delete window.__x3QueuePromises[id];"
                        + "return null;"
                        + "});"
                        + "window.__x3QueuePromises[id]=promise;"
                        + "return promise;"
                        + "};"
                        + "window.__x3WarmQueue=function(){"
                        + "var ids=window.__x3RefreshQueue();"
                        + "ids.slice(0,4).forEach(window.__x3PreloadCard);"
                        + "};"
                        + "window.__x3GrowQueue=function(){"
                        + "window.scrollTo(0,document.documentElement.scrollHeight);"
                        + "setTimeout(window.__x3WarmQueue,1200);"
                        + "};"
                        + "window.__x3PlayQueueIndex=function(index){"
                        + "var ids=window.__x3RefreshQueue();"
                        + "if(!ids.length){return 'missing';}"
                        + "var normalized=((index%ids.length)+ids.length)%ids.length;"
                        + "window.__x3QueueIndex=normalized;"
                        + "var id=ids[normalized];"
                        + "window.__x3PreloadCard(id).then(function(info){"
                        + "if(!info||window.__x3QueueIds[window.__x3QueueIndex]!==id){return;}"
                        + "window.__x3NativeVideoId=info.id;"
                        + "window.X3Native.playVideo(info.id,info.url);"
                        + "[1,2,3].forEach(function(offset){"
                        + "window.__x3PreloadCard(ids[(normalized+offset)%ids.length]);"
                        + "});"
                        + "if(normalized>=ids.length-3){window.__x3GrowQueue();}"
                        + "});"
                        + "return 'queue';"
                        + "};"
                        + "window.__x3StartQueue=function(){"
                        + "window.__x3WarmQueue();"
                        + "return window.__x3QueueIndex<0?window.__x3PlayQueueIndex(0):'queue';"
                        + "};"
                        + "window.__x3Browse=function(direction){"
                        + "if(window.__x3QueueIds.length||window.__x3RefreshQueue().length){"
                        + "return window.__x3PlayQueueIndex((window.__x3QueueIndex<0?0:window.__x3QueueIndex)+direction);"
                        + "}"
                        + "var selector=direction>0?'[data-e2e=\"video-switch-next-arrow\"]':'[data-e2e=\"video-switch-prev-arrow\"]';"
                        + "var button=document.querySelector(selector);"
                        + "if(button&&!button.classList.contains('disabled')){"
                        + "button.click();setTimeout(window.__x3EnsurePlayback,300);return 'button';"
                        + "}"
                        + "var delta=direction>0?760:-760;"
                        + "var target=document.elementFromPoint(innerWidth/2,innerHeight/2)||document.body||document.documentElement;"
                        + "var init={deltaY:delta,deltaMode:0,bubbles:true,cancelable:true,view:window};"
                        + "try{target.dispatchEvent(new WheelEvent('wheel',init));}catch(e){}"
                        + "return 'wheel';"
                        + "};"
                        + "window.__x3OpenFirstVideo=function(){"
                        + "if(window.__x3RefreshQueue().length){return window.__x3StartQueue();}"
                        + "if(location.pathname!='/jingxuan'||location.search.indexOf('modal_id=')>=0){return 'skip';}"
                        + "var card=document.querySelector('.jingxuanVideoCard');"
                        + "if(card){card.click();return 'card';}"
                        + "return 'missing';"
                        + "};"
                        + "window.__x3EnsurePlayback=function(){"
                        + "var active=document.querySelector('[data-e2e=\"feed-active-video\"] video');"
                        + "if(active&&window.X3Native){"
                        + "var src=active.currentSrc||active.src;"
                        + "var container=active.closest('[data-e2e=\"feed-active-video\"]');"
                        + "var videoId=container&&container.getAttribute('data-e2e-vid');"
                        + "document.querySelectorAll('video').forEach(function(video){"
                        + "video.muted=true;video.volume=0;"
                        + "});"
                        + "if(src&&videoId&&videoId!==window.__x3NativeVideoId){"
                        + "window.__x3NativeVideoId=videoId;"
                        + "try{window.X3Native.playVideo(videoId,src);}catch(e){}"
                        + "}"
                        + "}else if(active&&active.paused&&typeof active.play==='function'){"
                        + "try{active.play().catch(function(){});}catch(e){}"
                        + "}"
                        + "};"
                        + "window.__x3DismissOverlays=function(){"
                        + "var titles=[...document.querySelectorAll('body *')].filter(function(e){"
                        + "return (e.textContent||'').trim()==='登录后免费畅享高清视频';"
                        + "});"
                        + "titles.forEach(function(title){"
                        + "var close=title.parentElement&&title.parentElement.querySelector('svg');"
                        + "var button=close&&close.parentElement;"
                        + "if(button&&typeof button.click==='function'){button.click();}"
                        + "});"
                        + "var tutorial=[...document.querySelectorAll('body *')].find(function(e){"
                        + "return (e.textContent||'').trim()==='我知道了';"
                        + "});"
                        + "if(tutorial&&typeof tutorial.click==='function'){tutorial.click();}"
                        + "};"
                        + "window.__x3DismissOverlays();"
                        + "setTimeout(window.__x3StartQueue,500);"
                        + "setTimeout(window.__x3OpenFirstVideo,900);"
                        + "setTimeout(window.__x3EnsurePlayback,1200);"
                        + "setInterval(window.__x3EnsurePlayback,500);"
                        + "})();",
                null);
    }

    private void dismissLoginOverlay() {
        webView.evaluateJavascript(
                "(function(){"
                        + "if(window.__x3DismissOverlays){"
                        + "window.__x3DismissOverlays();"
                        + "if(window.__x3OpenFirstVideo){window.__x3OpenFirstVideo();}"
                        + "if(window.__x3EnsurePlayback){window.__x3EnsurePlayback();}"
                        + "return 'ok';"
                        + "}"
                        + "return 'missing';"
                        + "})();",
                null);
    }

    private void browseVideo(int direction) {
        injectX3Controls();
        String command =
                "(function(){if(window.__x3Browse){window.__x3Browse("
                        + (direction > 0 ? "1" : "-1")
                        + ");return 'ok';}return 'missing';})();";
        webView.evaluateJavascript(command, value -> Log.i(TAG, "browse=" + direction + " " + value));
    }

    private boolean handleTempleEvent(MotionEvent event) {
        if (!isTempleEvent(event)) {
            return false;
        }

        int action = event.getActionMasked();
        float x = event.getPointerCount() > 0 ? event.getX(0) : 0.0f;
        float y = event.getPointerCount() > 0 ? event.getY(0) : 0.0f;
        long eventTime = event.getEventTime();

        switch (action) {
            case MotionEvent.ACTION_DOWN:
                cancelPendingSingleTap();
                templeGestureActive = true;
                templeDownX = x;
                templeDownY = y;
                templeDownTime = eventTime;
                return true;
            case MotionEvent.ACTION_MOVE:
            case MotionEvent.ACTION_HOVER_MOVE:
                if (!templeGestureActive) {
                    templeGestureActive = true;
                    templeDownX = x;
                    templeDownY = y;
                    templeDownTime = eventTime;
                }
                return true;
            case MotionEvent.ACTION_UP:
                if (!templeGestureActive) {
                    return true;
                }
                float deltaX = x - templeDownX;
                float deltaY = y - templeDownY;
                if (Math.abs(deltaY) >= SWIPE_THRESHOLD
                        && Math.abs(deltaY) >= Math.abs(deltaX)) {
                    browseVideo(deltaY < 0.0f ? 1 : -1);
                } else if (eventTime - templeDownTime <= TAP_TIMEOUT_MS
                        && Math.abs(deltaX) <= TAP_SLOP
                        && Math.abs(deltaY) <= TAP_SLOP) {
                    handleTempleTap(eventTime);
                }
                templeGestureActive = false;
                return true;
            case MotionEvent.ACTION_CANCEL:
                templeGestureActive = false;
                cancelPendingSingleTap();
                return true;
            default:
                return true;
        }
    }

    private boolean isTempleEvent(MotionEvent event) {
        InputDevice device = event.getDevice();
        if (device == null || device.getName() == null) {
            return false;
        }
        return device.getName().toLowerCase(Locale.US).contains(TEMPLE_DEVICE_NAME);
    }

    private void handleTempleTap(long eventTime) {
        if (lastTempleTapUpTime > 0L
                && eventTime - lastTempleTapUpTime <= DOUBLE_TAP_TIMEOUT_MS) {
            lastTempleTapUpTime = 0L;
            cancelPendingSingleTap();
            onBackPressed();
            return;
        }
        lastTempleTapUpTime = eventTime;
        cancelPendingSingleTap();
        pendingSingleTap =
                new Runnable() {
                    @Override
                    public void run() {
                        dispatchCenterTap();
                        pendingSingleTap = null;
                    }
                };
        handler.postDelayed(pendingSingleTap, SINGLE_TAP_DELAY_MS);
    }

    private void cancelPendingSingleTap() {
        if (pendingSingleTap == null) {
            return;
        }
        handler.removeCallbacks(pendingSingleTap);
        pendingSingleTap = null;
    }

    private void dispatchCenterTap() {
        if (stereoVideoView.getVisibility() == View.VISIBLE
                && nativePlayerPrepared
                && mediaPlayer != null) {
            if (mediaPlayer.isPlaying()) {
                mediaPlayer.pause();
            } else {
                mediaPlayer.start();
            }
            return;
        }
        long downTime = SystemClock.uptimeMillis();
        dispatchWebTouch(MotionEvent.ACTION_DOWN, EYE_WIDTH / 2.0f, EYE_HEIGHT / 2.0f, downTime, downTime);
        handler.postDelayed(
                () -> {
                    long upTime = SystemClock.uptimeMillis();
                    dispatchWebTouch(
                            MotionEvent.ACTION_UP,
                            EYE_WIDTH / 2.0f,
                            EYE_HEIGHT / 2.0f,
                            upTime,
                            downTime);
                },
                16L);
    }

    private void dispatchWebTouch(
            int action,
            float x,
            float y,
            long eventTime,
            long downTime) {
        MotionEvent synthetic = MotionEvent.obtain(downTime, eventTime, action, x, y, 0);
        synthetic.setSource(InputDevice.SOURCE_TOUCHSCREEN);
        try {
            webView.dispatchTouchEvent(synthetic);
        } finally {
            synthetic.recycle();
        }
    }

    private void playNativeVideo(String videoId, String url) {
        if (videoId == null
                || videoId.isEmpty()
                || url == null
                || url.isEmpty()
                || videoId.equals(playingVideoId)
                || videoId.equals(pendingVideoId)) {
            return;
        }
        pendingVideoId = videoId;
        pendingVideoUrl = url;
        stereoVideoView.setVisibility(View.VISIBLE);
        if (nativeVideoSurface == null || !nativeVideoSurface.isValid()) {
            return;
        }
        startNativePlayer(videoId, url);
    }

    private void startNativePlayer(String videoId, String url) {
        releaseNativePlayer();
        playingVideoId = videoId;
        playingVideoUrl = url;
        nativePlayerPrepared = false;
        MediaPlayer player = new MediaPlayer();
        mediaPlayer = player;
        player.setAudioAttributes(
                new AudioAttributes.Builder()
                        .setContentType(AudioAttributes.CONTENT_TYPE_MOVIE)
                        .setUsage(AudioAttributes.USAGE_MEDIA)
                        .build());
        player.setSurface(nativeVideoSurface);
        player.setOnVideoSizeChangedListener(
                (ignored, width, height) -> stereoVideoView.setVideoSize(width, height));
        player.setOnPreparedListener(
                prepared -> {
                    if (prepared != mediaPlayer) {
                        return;
                    }
                    stereoVideoView.setVideoSize(
                            prepared.getVideoWidth(),
                            prepared.getVideoHeight());
                    nativePlayerPrepared = true;
                    prepared.start();
                    stereoWebViewContainer.setNativeVideoOverlayActive(true);
                    webView.setVisibility(View.INVISIBLE);
                    Log.i(TAG, "Native playback started");
                });
        player.setOnErrorListener(
                (failed, what, extra) -> {
                    Log.e(TAG, "Native playback failed what=" + what + " extra=" + extra);
                    if (failed == mediaPlayer) {
                        releaseNativePlayer();
                        pendingVideoId = null;
                        pendingVideoUrl = null;
                        stereoVideoView.setVisibility(View.GONE);
                        stereoWebViewContainer.setNativeVideoOverlayActive(false);
                        webView.setVisibility(View.VISIBLE);
                        webView.evaluateJavascript("window.__x3NativeVideoId='';", null);
                    }
                    return true;
                });

        Map<String, String> headers = new HashMap<>();
        headers.put("User-Agent", DESKTOP_USER_AGENT);
        headers.put("Referer", "https://www.douyin.com/");
        try {
            player.setDataSource(this, Uri.parse(url), headers);
            player.prepareAsync();
        } catch (IOException | RuntimeException error) {
            Log.e(TAG, "Unable to prepare native video", error);
            releaseNativePlayer();
            pendingVideoId = null;
            pendingVideoUrl = null;
            stereoVideoView.setVisibility(View.GONE);
            stereoWebViewContainer.setNativeVideoOverlayActive(false);
            webView.setVisibility(View.VISIBLE);
        }
    }

    private void releaseNativePlayer() {
        MediaPlayer player = mediaPlayer;
        mediaPlayer = null;
        playingVideoId = null;
        playingVideoUrl = null;
        nativePlayerPrepared = false;
        if (player == null) {
            return;
        }
        try {
            player.reset();
        } catch (RuntimeException ignored) {
            // Ignore teardown races while switching videos.
        }
        player.release();
    }

    private void onNativeVideoSurfaceReady(Surface surface) {
        nativeVideoSurface = surface;
        if (mediaPlayer != null) {
            mediaPlayer.setSurface(surface);
            return;
        }
        if (pendingVideoId != null
                && pendingVideoUrl != null
                && !pendingVideoId.equals(playingVideoId)) {
            startNativePlayer(pendingVideoId, pendingVideoUrl);
        }
    }

    private final class NativeVideoBridge {
        @JavascriptInterface
        public void playVideo(String videoId, String url) {
            runOnUiThread(() -> playNativeVideo(videoId, url));
        }
    }

    private void enterImmersiveMode() {
        getWindow()
                .getDecorView()
                .setSystemUiVisibility(
                        View.SYSTEM_UI_FLAG_FULLSCREEN
                                | View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
                                | View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
                                | View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
                                | View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
                                | View.SYSTEM_UI_FLAG_LAYOUT_STABLE);
    }

    private final class StereoWebViewContainer extends ViewGroup {
        private boolean nativeVideoOverlayActive;

        StereoWebViewContainer() {
            super(MainActivity.this);
        }

        void setNativeVideoOverlayActive(boolean active) {
            nativeVideoOverlayActive = active;
            invalidate();
        }

        @Override
        protected void onMeasure(int widthMeasureSpec, int heightMeasureSpec) {
            webView.measure(
                    MeasureSpec.makeMeasureSpec(EYE_WIDTH, MeasureSpec.EXACTLY),
                    MeasureSpec.makeMeasureSpec(EYE_HEIGHT, MeasureSpec.EXACTLY));
            setMeasuredDimension(EYE_WIDTH * 2, EYE_HEIGHT);
        }

        @Override
        protected void onLayout(boolean changed, int left, int top, int right, int bottom) {
            webView.layout(0, 0, EYE_WIDTH, EYE_HEIGHT);
        }

        @Override
        protected void dispatchDraw(Canvas canvas) {
            long drawingTime = getDrawingTime();
            drawChild(canvas, webView, drawingTime);
            if (nativeVideoOverlayActive) {
                return;
            }
            int checkpoint = canvas.save();
            canvas.translate(EYE_WIDTH, 0.0f);
            drawChild(canvas, webView, drawingTime);
            canvas.restoreToCount(checkpoint);
        }
    }

    private final class StereoVideoView extends GLSurfaceView
            implements GLSurfaceView.Renderer, SurfaceTexture.OnFrameAvailableListener {
        private static final String VERTEX_SHADER =
                "attribute vec4 aPosition;"
                        + "attribute vec4 aTextureCoord;"
                        + "uniform mat4 uTextureMatrix;"
                        + "varying vec2 vTextureCoord;"
                        + "void main(){"
                        + "gl_Position=aPosition;"
                        + "vTextureCoord=(uTextureMatrix*aTextureCoord).xy;"
                        + "}";
        private static final String FRAGMENT_SHADER =
                "#extension GL_OES_EGL_image_external : require\n"
                        + "precision mediump float;"
                        + "varying vec2 vTextureCoord;"
                        + "uniform samplerExternalOES uTexture;"
                        + "void main(){gl_FragColor=texture2D(uTexture,vTextureCoord);}";
        private final float[] textureMatrix = new float[16];
        private final FloatBuffer textureCoordinates =
                createFloatBuffer(
                        new float[] {
                            0.0f, 0.0f,
                            1.0f, 0.0f,
                            0.0f, 1.0f,
                            1.0f, 1.0f
                        });
        private FloatBuffer vertices = createFloatBuffer(fullScreenVertices());
        private SurfaceTexture surfaceTexture;
        private Surface surface;
        private int program;
        private int textureId;
        private int positionHandle;
        private int textureCoordinateHandle;
        private int textureMatrixHandle;
        private int viewWidth;
        private int viewHeight;
        private int videoWidth = EYE_WIDTH;
        private int videoHeight = EYE_HEIGHT;

        StereoVideoView() {
            super(MainActivity.this);
            setZOrderOnTop(true);
            setEGLContextClientVersion(2);
            setRenderer(this);
            setRenderMode(RENDERMODE_WHEN_DIRTY);
            setPreserveEGLContextOnPause(true);
        }

        @Override
        public void onSurfaceCreated(GL10 ignored, EGLConfig config) {
            program = createProgram(VERTEX_SHADER, FRAGMENT_SHADER);
            positionHandle = GLES20.glGetAttribLocation(program, "aPosition");
            textureCoordinateHandle = GLES20.glGetAttribLocation(program, "aTextureCoord");
            textureMatrixHandle = GLES20.glGetUniformLocation(program, "uTextureMatrix");

            int[] textures = new int[1];
            GLES20.glGenTextures(1, textures, 0);
            textureId = textures[0];
            GLES20.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, textureId);
            GLES20.glTexParameteri(
                    GLES11Ext.GL_TEXTURE_EXTERNAL_OES,
                    GLES20.GL_TEXTURE_MIN_FILTER,
                    GLES20.GL_LINEAR);
            GLES20.glTexParameteri(
                    GLES11Ext.GL_TEXTURE_EXTERNAL_OES,
                    GLES20.GL_TEXTURE_MAG_FILTER,
                    GLES20.GL_LINEAR);
            GLES20.glTexParameteri(
                    GLES11Ext.GL_TEXTURE_EXTERNAL_OES,
                    GLES20.GL_TEXTURE_WRAP_S,
                    GLES20.GL_CLAMP_TO_EDGE);
            GLES20.glTexParameteri(
                    GLES11Ext.GL_TEXTURE_EXTERNAL_OES,
                    GLES20.GL_TEXTURE_WRAP_T,
                    GLES20.GL_CLAMP_TO_EDGE);

            surfaceTexture = new SurfaceTexture(textureId);
            surfaceTexture.setOnFrameAvailableListener(this);
            surface = new Surface(surfaceTexture);
            runOnUiThread(() -> onNativeVideoSurfaceReady(surface));
        }

        @Override
        public void onSurfaceChanged(GL10 ignored, int width, int height) {
            viewWidth = width;
            viewHeight = height;
            updateVertices();
        }

        @Override
        public void onDrawFrame(GL10 ignored) {
            SurfaceTexture texture = surfaceTexture;
            if (texture == null) {
                return;
            }
            texture.updateTexImage();
            texture.getTransformMatrix(textureMatrix);
            GLES20.glClearColor(0.0f, 0.0f, 0.0f, 1.0f);
            GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT);
            GLES20.glUseProgram(program);
            GLES20.glActiveTexture(GLES20.GL_TEXTURE0);
            GLES20.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, textureId);
            GLES20.glEnableVertexAttribArray(positionHandle);
            GLES20.glEnableVertexAttribArray(textureCoordinateHandle);
            GLES20.glVertexAttribPointer(
                    positionHandle,
                    2,
                    GLES20.GL_FLOAT,
                    false,
                    0,
                    vertices);
            GLES20.glVertexAttribPointer(
                    textureCoordinateHandle,
                    2,
                    GLES20.GL_FLOAT,
                    false,
                    0,
                    textureCoordinates);
            GLES20.glUniformMatrix4fv(textureMatrixHandle, 1, false, textureMatrix, 0);

            int eyeWidth = viewWidth / 2;
            GLES20.glViewport(0, 0, eyeWidth, viewHeight);
            GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4);
            GLES20.glViewport(eyeWidth, 0, eyeWidth, viewHeight);
            GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4);
        }

        @Override
        public void onFrameAvailable(SurfaceTexture ignored) {
            requestRender();
        }

        void setVideoSize(int width, int height) {
            if (width <= 0 || height <= 0) {
                return;
            }
            videoWidth = width;
            videoHeight = height;
            queueEvent(this::updateVertices);
        }

        void release() {
            Surface releasedSurface = surface;
            SurfaceTexture releasedTexture = surfaceTexture;
            surface = null;
            surfaceTexture = null;
            nativeVideoSurface = null;
            if (releasedSurface != null) {
                releasedSurface.release();
            }
            if (releasedTexture != null) {
                releasedTexture.release();
            }
        }

        private void updateVertices() {
            float scaleX = 1.0f;
            float scaleY = 1.0f;
            if (viewWidth > 0 && viewHeight > 0 && videoWidth > 0 && videoHeight > 0) {
                float targetAspect = (viewWidth / 2.0f) / viewHeight;
                float videoAspect = videoWidth / (float) videoHeight;
                if (videoAspect > targetAspect) {
                    scaleY = targetAspect / videoAspect;
                } else {
                    scaleX = videoAspect / targetAspect;
                }
            }
            vertices =
                    createFloatBuffer(
                            new float[] {
                                -scaleX, -scaleY,
                                scaleX, -scaleY,
                                -scaleX, scaleY,
                                scaleX, scaleY
                            });
        }

        private int createProgram(String vertexSource, String fragmentSource) {
            int vertexShader = compileShader(GLES20.GL_VERTEX_SHADER, vertexSource);
            int fragmentShader = compileShader(GLES20.GL_FRAGMENT_SHADER, fragmentSource);
            int shaderProgram = GLES20.glCreateProgram();
            GLES20.glAttachShader(shaderProgram, vertexShader);
            GLES20.glAttachShader(shaderProgram, fragmentShader);
            GLES20.glLinkProgram(shaderProgram);
            return shaderProgram;
        }

        private int compileShader(int type, String source) {
            int shader = GLES20.glCreateShader(type);
            GLES20.glShaderSource(shader, source);
            GLES20.glCompileShader(shader);
            return shader;
        }

        private FloatBuffer createFloatBuffer(float[] values) {
            FloatBuffer buffer =
                    ByteBuffer.allocateDirect(values.length * Float.BYTES)
                            .order(ByteOrder.nativeOrder())
                            .asFloatBuffer();
            buffer.put(values);
            buffer.position(0);
            return buffer;
        }

        private float[] fullScreenVertices() {
            return new float[] {
                -1.0f, -1.0f,
                1.0f, -1.0f,
                -1.0f, 1.0f,
                1.0f, 1.0f
            };
        }
    }
}
