package com.xx.blbl.x3;

import android.app.Activity;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.SharedPreferences;
import android.graphics.Color;
import android.database.sqlite.SQLiteDatabase;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.text.TextUtils;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.KeyCharacterMap;
import android.view.KeyEvent;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.ViewTreeObserver;
import android.webkit.CookieManager;
import android.webkit.WebSettings;
import android.webkit.ValueCallback;
import android.webkit.WebView;
import android.view.inputmethod.EditorInfo;
import android.view.inputmethod.InputMethodManager;
import android.widget.AutoCompleteTextView;
import android.widget.Button;
import android.widget.FrameLayout;
import android.widget.ImageButton;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import java.lang.reflect.Method;
import java.util.WeakHashMap;

public final class LightningX3Helper {
    private static final String BOOKMARK_DB_NAME = "bookmarkManager";
    private static final String BOOKMARK_TABLE = "bookmark";
    private static final String PREFS_NAME = "settings";
    private static final String PREF_HOME = "home";
    private static final String PREF_SEARCH = "search";
    private static final String PREF_SEARCH_SUGGESTIONS = "searchSuggestionsChoice";
    private static final String PREF_MIGRATION = "x3_browser_defaults_v1";
    private static final String PREF_BOOKMARK_MIGRATION = "x3_browser_bookmarks_v2";
    private static final String PREF_CLEAR_COOKIES_EXIT = "clearCookiesExit";
    private static final String PREF_DESKTOP_MODE = "x3_browser_desktop_mode";
    private static final String PREF_MODE_MIGRATION = "x3_browser_mode_default_v1";
    private static final String BING_HOME = "https://www.bing.com/";
    private static final String DESKTOP_USER_AGENT =
            "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 "
                    + "(KHTML, like Gecko) Chrome/131.0.0.0 Safari/537.36";
    private static final int SEARCH_CHOICE_BING = 3;
    private static final int SEARCH_SUGGESTIONS_OFF = 0;
    private static final int KEYBOARD_MODE_ADDRESS = 1;
    private static final int KEYBOARD_MODE_FOCUSED_VIEW = 2;
    private static final long WEB_INPUT_PROBE_MS = 350L;
    private static final long EXIT_CONFIRM_WINDOW_MS = 1200L;

    private static final WeakHashMap<Activity, BrowserState> STATES = new WeakHashMap<>();

    private LightningX3Helper() {}

    public static void install(Activity activity) {
        if (activity == null) {
            return;
        }
        BrowserState state = STATES.get(activity);
        if (state == null) {
            state = new BrowserState(activity);
            STATES.put(activity, state);
        }
        state.install();
        RayneoMirrorHelper.install(activity);
    }

    public static void uninstall(Activity activity) {
        BrowserState state = STATES.remove(activity);
        if (state != null) {
            state.destroy();
        }
        RayneoMirrorHelper.uninstall(activity);
    }

    public static boolean handleGlobalBack(Activity activity) {
        if (activity == null) {
            return false;
        }
        BrowserState state = STATES.get(activity);
        return state != null && state.handleGlobalBack();
    }

    public static boolean deleteBookmarkEntry(View sourceView, Object bookmarkEntry) {
        if (sourceView == null || bookmarkEntry == null) {
            return false;
        }
        Context context = sourceView.getContext();
        if (context == null) {
            return false;
        }
        String className = bookmarkEntry.getClass().getName();
        try {
            if (className.endsWith(".w.a")) {
                Method urlMethod = bookmarkEntry.getClass().getMethod("b");
                Object urlValue = urlMethod.invoke(bookmarkEntry);
                if (!(urlValue instanceof String) || TextUtils.isEmpty((String) urlValue)) {
                    return false;
                }
                return deleteBookmarkUrl(context, (String) urlValue);
            }
            if (className.endsWith(".w.d")) {
                Method nameMethod = bookmarkEntry.getClass().getMethod("a");
                Object folderValue = nameMethod.invoke(bookmarkEntry);
                if (!(folderValue instanceof String) || TextUtils.isEmpty((String) folderValue)) {
                    return false;
                }
                return deleteBookmarkFolder(context, (String) folderValue);
            }
        } catch (Throwable ignored) {
        }
        return false;
    }

    private static boolean deleteBookmarkUrl(Context context, String url) {
        SQLiteDatabase database = null;
        try {
            database = context.openOrCreateDatabase(BOOKMARK_DB_NAME, Context.MODE_PRIVATE, null);
            String alternate = url.endsWith("/") ? url.substring(0, url.length() - 1) : url + "/";
            int deleted =
                    database.delete(
                            BOOKMARK_TABLE, "url=? OR url=?", new String[] {url, alternate});
            return deleted > 0;
        } catch (Throwable ignored) {
            return false;
        } finally {
            if (database != null) {
                try {
                    database.close();
                } catch (Throwable ignored) {
                }
            }
        }
    }

    private static boolean deleteBookmarkFolder(Context context, String folderName) {
        SQLiteDatabase database = null;
        try {
            database = context.openOrCreateDatabase(BOOKMARK_DB_NAME, Context.MODE_PRIVATE, null);
            android.content.ContentValues values = new android.content.ContentValues(1);
            values.put("folder", "");
            int updated = database.update(BOOKMARK_TABLE, values, "folder=?", new String[] {folderName});
            return updated > 0;
        } catch (Throwable ignored) {
            return false;
        } finally {
            if (database != null) {
                try {
                    database.close();
                } catch (Throwable ignored) {
                }
            }
        }
    }

    private static final class BrowserState {
        private final Activity activity;
        private final Handler handler = new Handler(Looper.getMainLooper());
        private final Runnable bindRunnable =
                new Runnable() {
                    @Override
                    public void run() {
                        bindBrowserViews();
                    }
                };
        private final Runnable webInputProbe =
                new Runnable() {
                    @Override
                    public void run() {
                        if (!installed) {
                            return;
                        }
                        probeWebInput();
                        handler.postDelayed(this, WEB_INPUT_PROBE_MS);
                    }
                };
        private final Runnable customViewAdjuster =
                new Runnable() {
                    @Override
                    public void run() {
                        if (!installed) {
                            return;
                        }
                        adjustFullscreenCustomView();
                        updateNavigationButtonsState();
                        handler.postDelayed(this, 180L);
                    }
                };
        private final Runnable imeSuppressor =
                new Runnable() {
                    @Override
                    public void run() {
                        if (!installed) {
                            return;
                        }
                        if (!keyboardVisible && !webKeyboardDismissed) {
                            return;
                        }
                        if (keyboardVisible && keyboardMode != KEYBOARD_MODE_FOCUSED_VIEW) {
                            return;
                        }
                        View currentFocus = activity.getCurrentFocus();
                        hideSystemIme(currentFocus);
                        hideSystemIme(keyboardTargetView);
                        hideSystemIme(activeWebView);
                        handler.postDelayed(this, 120L);
                    }
                };
        private final ViewTreeObserver.OnGlobalFocusChangeListener focusChangeListener =
                new ViewTreeObserver.OnGlobalFocusChangeListener() {
                    @Override
                    public void onGlobalFocusChanged(View oldFocus, View newFocus) {
                        if (newFocus == searchView) {
                            disableSystemIme(searchView);
                            return;
                        }
                        if (keyboardVisible
                                && newFocus != null
                                && keyboardRoot != null
                                && isDescendantOf(newFocus, keyboardRoot)) {
                            return;
                        }
                        if (shouldUseInjectedKeyboard(newFocus)) {
                            if (suppressAutoWebKeyboard) {
                                suppressInitialHomepageFocus();
                                return;
                            }
                            hideSystemIme(newFocus);
                            webKeyboardDismissed = false;
                            showFocusedKeyboard(newFocus);
                            return;
                        }
                        if (keyboardVisible && newFocus != searchView) {
                            hideKeyboard();
                        }
                    }
                };
        private final View.OnTouchListener searchTouchListener =
                new View.OnTouchListener() {
                    @Override
                    public boolean onTouch(View v, MotionEvent event) {
                        if (event != null && event.getActionMasked() == MotionEvent.ACTION_UP) {
                            suppressAutoWebKeyboard = false;
                            v.post(
                                    new Runnable() {
                                        @Override
                                        public void run() {
                                            showAddressKeyboard();
                                        }
                                    });
                        }
                        return false;
                    }
                };
        private final View.OnTouchListener webTouchListener =
                new View.OnTouchListener() {
                    @Override
                    public boolean onTouch(View v, MotionEvent event) {
                        if (event == null) {
                            return false;
                        }
                        int action = event.getActionMasked();
                        if (action == MotionEvent.ACTION_UP || action == MotionEvent.ACTION_DOWN) {
                            suppressAutoWebKeyboard = false;
                            webKeyboardDismissed = false;
                            handler.removeCallbacks(webInputProbe);
                            handler.postDelayed(webInputProbe, 120L);
                            hideSystemIme(v);
                        }
                        return false;
                    }
                };

        private ViewGroup sourceRoot;
        private FrameLayout overlayHost;
        private Button desktopModeButton;
        private ImageButton bookmarkButton;
        private ImageButton backButton;
        private ImageButton forwardButton;
        private FrameLayout keyboardRoot;
        private TextView keyboardPreview;
        private AutoCompleteTextView searchView;
        private StringBuilder inputBuffer;
        private boolean installed;
        private boolean keyboardVisible;
        private int keyboardMode = KEYBOARD_MODE_ADDRESS;
        private View keyboardTargetView;
        private WebView observedWebView;
        private WebView activeWebView;
        private boolean webKeyboardDismissed;
        private boolean customViewPixelCopyForced;
        private boolean desktopModeEnabled;
        private String defaultUserAgent;
        private boolean toolbarSpaceAdjusted;
        private long lastExitBackTime;
        private boolean suppressAutoWebKeyboard = true;

        BrowserState(Activity activity) {
            this.activity = activity;
            SharedPreferences preferences =
                    activity.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
            desktopModeEnabled = preferences.getBoolean(PREF_DESKTOP_MODE, false);
        }

        void install() {
            applyDefaultPreferences();
            ensureDefaultMode();
            ensureDefaultBookmarks();
            if (!installed) {
                sourceRoot = findSourceRoot();
                if (sourceRoot == null) {
                    activity.getWindow().getDecorView().post(bindRunnable);
                    return;
                }
                ensureOverlayHost();
                ViewTreeObserver observer = sourceRoot.getViewTreeObserver();
                if (observer != null && observer.isAlive()) {
                    observer.addOnGlobalFocusChangeListener(focusChangeListener);
                }
                handler.removeCallbacks(webInputProbe);
                handler.postDelayed(webInputProbe, WEB_INPUT_PROBE_MS);
                handler.removeCallbacks(customViewAdjuster);
                handler.post(customViewAdjuster);
                installed = true;
            }
            activity.getWindow().getDecorView().post(bindRunnable);
        }

        private void ensureDefaultMode() {
            SharedPreferences preferences =
                    activity.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
            if (preferences.getBoolean(PREF_MODE_MIGRATION, false)) {
                desktopModeEnabled = preferences.getBoolean(PREF_DESKTOP_MODE, false);
                return;
            }
            desktopModeEnabled = false;
            preferences.edit()
                    .putBoolean(PREF_DESKTOP_MODE, false)
                    .putInt("userAgentChoice", 3)
                    .putBoolean(PREF_MODE_MIGRATION, true)
                    .apply();
        }

        void destroy() {
            hideKeyboard();
            if (sourceRoot != null) {
                ViewTreeObserver observer = sourceRoot.getViewTreeObserver();
                if (observer != null && observer.isAlive()) {
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.JELLY_BEAN) {
                        observer.removeOnGlobalFocusChangeListener(focusChangeListener);
                    }
                }
            }
            if (overlayHost != null && sourceRoot != null) {
                sourceRoot.removeView(overlayHost);
            }
            handler.removeCallbacks(webInputProbe);
            handler.removeCallbacks(imeSuppressor);
            handler.removeCallbacks(customViewAdjuster);
            if (customViewPixelCopyForced) {
                customViewPixelCopyForced = false;
                RayneoMirrorHelper.setVideoMirrorOverride(activity, false);
            }
            overlayHost = null;
            desktopModeButton = null;
            bookmarkButton = null;
            backButton = null;
            forwardButton = null;
            keyboardRoot = null;
            keyboardPreview = null;
            searchView = null;
            inputBuffer = null;
            observedWebView = null;
            activeWebView = null;
            webKeyboardDismissed = false;
            customViewPixelCopyForced = false;
            toolbarSpaceAdjusted = false;
            lastExitBackTime = 0L;
            installed = false;
        }

        boolean handleGlobalBack() {
            if (keyboardVisible) {
                if (keyboardMode == KEYBOARD_MODE_FOCUSED_VIEW) {
                    webKeyboardDismissed = false;
                    blurActiveWebInput();
                }
                hideKeyboard();
                lastExitBackTime = 0L;
                return true;
            }

            WebView webView = getActiveWebView();
            if (webView != null) {
                try {
                    if (webView.canGoBack()) {
                        lastExitBackTime = 0L;
                        return false;
                    }
                } catch (Throwable ignored) {
                }
            }

            long now = SystemClock.uptimeMillis();
            if (now - lastExitBackTime <= EXIT_CONFIRM_WINDOW_MS) {
                lastExitBackTime = 0L;
                return false;
            }
            lastExitBackTime = now;
            try {
                Toast.makeText(activity, "再按一次退出浏览器", Toast.LENGTH_SHORT).show();
            } catch (Throwable ignored) {
            }
            return true;
        }

        private void applyDefaultPreferences() {
            SharedPreferences preferences = activity.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
            if (preferences.getBoolean(PREF_MIGRATION, false)) {
                return;
            }
            preferences.edit()
                    .putString(PREF_HOME, BING_HOME)
                    .putInt(PREF_SEARCH, SEARCH_CHOICE_BING)
                    .putInt(PREF_SEARCH_SUGGESTIONS, SEARCH_SUGGESTIONS_OFF)
                    .putBoolean(PREF_CLEAR_COOKIES_EXIT, false)
                    .putBoolean(PREF_MIGRATION, true)
                    .apply();
        }

        private void ensureDefaultBookmarks() {
            SharedPreferences preferences = activity.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
            if (preferences.getBoolean(PREF_BOOKMARK_MIGRATION, false)) {
                return;
            }

            SQLiteDatabase database = null;
            boolean inTransaction = false;
            try {
                database = activity.openOrCreateDatabase(BOOKMARK_DB_NAME, Context.MODE_PRIVATE, null);
                database.beginTransaction();
                inTransaction = true;
                database.delete(BOOKMARK_TABLE, null, null);
                seedBookmark(database, 0, "B\u7ad9", "https://m.bilibili.com/");
                seedBookmark(database, 1, "\u817e\u8baf\u65b0\u95fb", "https://xw.qq.com/");
                seedBookmark(database, 2, "DeepSeek", "https://chat.deepseek.com/");
                seedBookmark(database, 3, "\u8c46\u5305", "https://www.doubao.com/");
                database.setTransactionSuccessful();
                preferences.edit().putBoolean(PREF_BOOKMARK_MIGRATION, true).apply();
            } catch (Throwable ignored) {
            } finally {
                if (database != null) {
                    if (inTransaction) {
                        try {
                            database.endTransaction();
                        } catch (Throwable ignored) {
                        }
                    }
                    try {
                        database.close();
                    } catch (Throwable ignored) {
                    }
                }
            }
        }

        private void seedBookmark(SQLiteDatabase database, int position, String title, String url) {
            if (database == null || TextUtils.isEmpty(title) || TextUtils.isEmpty(url)) {
                return;
            }

            android.database.Cursor cursor = null;
            String alternate = normalizeBookmarkUrl(url);
            try {
                cursor =
                        database.query(
                                BOOKMARK_TABLE,
                                new String[] {"id"},
                                "url=? OR url=?",
                                new String[] {url, alternate},
                                null,
                                null,
                                null);
                if (cursor != null && cursor.moveToFirst()) {
                    android.content.ContentValues update = new android.content.ContentValues(3);
                    update.put("title", title);
                    update.put("folder", "");
                    update.put("position", position);
                    database.update(
                            BOOKMARK_TABLE,
                            update,
                            "url=? OR url=?",
                            new String[] {url, alternate});
                    return;
                }
            } catch (Throwable ignored) {
            } finally {
                if (cursor != null) {
                    try {
                        cursor.close();
                    } catch (Throwable ignored) {
                    }
                }
            }

            android.content.ContentValues values = new android.content.ContentValues(4);
            values.put("url", url);
            values.put("title", title);
            values.put("folder", "");
            values.put("position", position);
            try {
                database.insert(BOOKMARK_TABLE, null, values);
            } catch (Throwable ignored) {
            }
        }

        private String normalizeBookmarkUrl(String url) {
            if (TextUtils.isEmpty(url)) {
                return url;
            }
            return url.endsWith("/") ? url.substring(0, url.length() - 1) : url + "/";
        }

        private void bindBrowserViews() {
            sourceRoot = findSourceRoot();
            if (sourceRoot == null) {
                return;
            }
            ensureOverlayHost();
            ensureToolbarSpace();
            ensureNavigationButtons();
            ensureBookmarkButton();
            ensureDesktopModeButton();
            adjustFullscreenCustomView();

            int searchId = resolveId("search");
            View searchCandidate = searchId != 0 ? activity.findViewById(searchId) : null;
            if (searchCandidate instanceof AutoCompleteTextView) {
                searchView = (AutoCompleteTextView) searchCandidate;
                disableSystemIme(searchView);
                searchView.setOnTouchListener(searchTouchListener);
            }

            WebView webView = findWebView(sourceRoot);
            if (webView != null) {
                observedWebView = webView;
                applyDesktopMode(webView);
                try {
                    CookieManager manager = CookieManager.getInstance();
                    manager.setAcceptCookie(true);
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
                        manager.setAcceptThirdPartyCookies(webView, true);
                    }
                } catch (Throwable ignored) {
                }
                webView.setOnTouchListener(webTouchListener);
                handler.postDelayed(
                        new Runnable() {
                            @Override
                            public void run() {
                                suppressInitialHomepageFocus();
                            }
                        },
                        100L);
            }
        }

        private ViewGroup findSourceRoot() {
            View content = activity.findViewById(android.R.id.content);
            if (!(content instanceof ViewGroup)) {
                return null;
            }
            ViewGroup contentGroup = (ViewGroup) content;
            if (contentGroup.getChildCount() == 0) {
                return null;
            }
            View root = contentGroup.getChildAt(0);
            if (root instanceof ViewGroup) {
                return (ViewGroup) root;
            }
            return null;
        }

        private void ensureOverlayHost() {
            if (overlayHost != null) {
                return;
            }
            overlayHost = new FrameLayout(activity);
            overlayHost.setClipChildren(false);
            overlayHost.setClipToPadding(false);
            overlayHost.setClickable(false);
            overlayHost.setFocusable(false);
            overlayHost.setLayoutParams(
                    new ViewGroup.LayoutParams(
                            ViewGroup.LayoutParams.MATCH_PARENT,
                            ViewGroup.LayoutParams.MATCH_PARENT));
            sourceRoot.addView(overlayHost);
        }

        private void ensureToolbarSpace() {
            if (toolbarSpaceAdjusted) {
                return;
            }
            int searchContainerId = resolveId("search_container");
            View searchContainer =
                    searchContainerId != 0 ? activity.findViewById(searchContainerId) : null;
            if (searchContainer == null) {
                return;
            }
            ViewGroup.LayoutParams baseParams = searchContainer.getLayoutParams();
            if (!(baseParams instanceof ViewGroup.MarginLayoutParams)) {
                return;
            }
            ViewGroup.MarginLayoutParams params = (ViewGroup.MarginLayoutParams) baseParams;
            params.rightMargin = params.rightMargin + 176;
            searchContainer.setLayoutParams(params);
            toolbarSpaceAdjusted = true;
        }

        private void ensureNavigationButtons() {
            if (overlayHost == null) {
                return;
            }
            if (backButton == null) {
                ImageButton button = createToolbarButton("ic_action_back", "Back");
                button.setOnClickListener(
                        new View.OnClickListener() {
                            @Override
                            public void onClick(View v) {
                                WebView webView = getActiveWebView();
                                if (webView != null) {
                                    try {
                                        if (webView.canGoBack()) {
                                            webView.goBack();
                                        }
                                    } catch (Throwable ignored) {
                                    }
                                }
                                updateNavigationButtonsState();
                            }
                        });
                FrameLayout.LayoutParams params =
                        new FrameLayout.LayoutParams(dp(34), dp(34), Gravity.END | Gravity.TOP);
                params.topMargin = dp(9);
                params.rightMargin = 136;
                overlayHost.addView(button, params);
                backButton = button;
            }
            if (forwardButton == null) {
                ImageButton button = createToolbarButton("ic_action_forward", "Forward");
                button.setOnClickListener(
                        new View.OnClickListener() {
                            @Override
                            public void onClick(View v) {
                                WebView webView = getActiveWebView();
                                if (webView != null) {
                                    try {
                                        if (webView.canGoForward()) {
                                            webView.goForward();
                                        }
                                    } catch (Throwable ignored) {
                                    }
                                }
                                updateNavigationButtonsState();
                            }
                        });
                FrameLayout.LayoutParams params =
                        new FrameLayout.LayoutParams(dp(34), dp(34), Gravity.END | Gravity.TOP);
                params.topMargin = dp(9);
                params.rightMargin = 96;
                overlayHost.addView(button, params);
                forwardButton = button;
            }
            updateNavigationButtonsState();
        }

        private ImageButton createToolbarButton(String drawableName, String contentDescription) {
            ImageButton button = new ImageButton(activity);
            int drawable =
                    activity.getResources()
                            .getIdentifier(drawableName, "drawable", activity.getPackageName());
            button.setImageResource(
                    drawable != 0 ? drawable : android.R.drawable.ic_media_previous);
            button.setBackgroundColor(Color.TRANSPARENT);
            button.setColorFilter(Color.WHITE);
            button.setScaleType(ImageButton.ScaleType.FIT_CENTER);
            button.setPadding(dp(4), dp(4), dp(4), dp(4));
            button.setClickable(true);
            button.setFocusable(false);
            button.setContentDescription(contentDescription);
            return button;
        }

        private void ensureBookmarkButton() {
            if (overlayHost == null || bookmarkButton != null) {
                return;
            }
            ImageButton button = new ImageButton(activity);
            int starDrawable =
                    activity.getResources()
                            .getIdentifier("ic_action_star", "drawable", activity.getPackageName());
            button.setImageResource(starDrawable != 0 ? starDrawable : android.R.drawable.btn_star_big_off);
            button.setBackgroundColor(Color.TRANSPARENT);
            button.setColorFilter(Color.WHITE);
            button.setScaleType(ImageButton.ScaleType.FIT_CENTER);
            button.setPadding(dp(1), dp(1), dp(1), dp(1));
            button.setClickable(true);
            button.setFocusable(false);
            button.setContentDescription("Bookmarks");
            button.setOnClickListener(
                    new View.OnClickListener() {
                        @Override
                        public void onClick(View v) {
                            toggleBookmarksDrawer();
                        }
                    });

            FrameLayout.LayoutParams params =
                    new FrameLayout.LayoutParams(dp(38), dp(38), Gravity.END | Gravity.TOP);
            params.topMargin = dp(9);
            params.rightMargin = 50;
            overlayHost.addView(button, params);
            bookmarkButton = button;
            updateBookmarkButtonState();
        }

        private void ensureDesktopModeButton() {
            if (overlayHost == null || desktopModeButton != null) {
                return;
            }
            Button button = new Button(activity);
            button.setAllCaps(false);
            button.setTextSize(TypedValue.COMPLEX_UNIT_SP, 9f);
            button.setTextColor(Color.WHITE);
            button.setBackgroundColor(Color.argb(180, 30, 30, 30));
            button.setPadding(0, 0, 0, 0);
            button.setMinWidth(0);
            button.setMinimumWidth(0);
            button.setMinHeight(0);
            button.setMinimumHeight(0);
            button.setFocusable(false);
            button.setOnClickListener(
                    new View.OnClickListener() {
                        @Override
                        public void onClick(View v) {
                            toggleDesktopMode();
                        }
                    });

            FrameLayout.LayoutParams params =
                    new FrameLayout.LayoutParams(dp(34), dp(34), Gravity.END | Gravity.TOP);
            params.topMargin = dp(9);
            params.rightMargin = dp(4);
            overlayHost.addView(button, params);
            desktopModeButton = button;
            updateDesktopModeButtonState();
        }

        private void toggleBookmarksDrawer() {
            hideKeyboard();
            int drawerId = resolveId("drawer_layout");
            View drawerLayout = drawerId != 0 ? activity.findViewById(drawerId) : null;
            if (drawerLayout == null) {
                return;
            }
            View bookmarksDrawer = findBookmarksDrawerContainer();
            if (bookmarksDrawer == null) {
                return;
            }

            boolean bookmarksOpen = invokeDrawerBoolean(drawerLayout, "h", bookmarksDrawer);
            invokeDrawerVoid(drawerLayout, "b");
            if (!bookmarksOpen) {
                invokeDrawerVoid(drawerLayout, "k", bookmarksDrawer);
            }
        }

        private void toggleDesktopMode() {
            desktopModeEnabled = !desktopModeEnabled;
            activity.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                    .edit()
                    .putBoolean(PREF_DESKTOP_MODE, desktopModeEnabled)
                    .putInt("userAgentChoice", desktopModeEnabled ? 2 : 3)
                    .apply();
            applyDesktopMode(observedWebView);
            updateDesktopModeButtonState();
            try {
                Toast.makeText(activity, desktopModeEnabled ? "PC mode" : "Mobile mode", Toast.LENGTH_SHORT)
                        .show();
            } catch (Throwable ignored) {
            }
            if (observedWebView != null) {
                try {
                    observedWebView.reload();
                } catch (Throwable ignored) {
                }
            }
        }

        private void updateBookmarkButtonState() {
            if (bookmarkButton == null) {
                return;
            }
            bookmarkButton.setColorFilter(Color.WHITE);
        }

        private void updateNavigationButtonsState() {
            WebView webView = getActiveWebView();
            boolean canGoBack = false;
            boolean canGoForward = false;
            if (webView != null) {
                try {
                    canGoBack = webView.canGoBack();
                    canGoForward = webView.canGoForward();
                } catch (Throwable ignored) {
                }
            }
            if (backButton != null) {
                backButton.setAlpha(canGoBack ? 1.0f : 0.45f);
            }
            if (forwardButton != null) {
                forwardButton.setAlpha(canGoForward ? 1.0f : 0.45f);
            }
        }

        private void updateDesktopModeButtonState() {
            if (desktopModeButton == null) {
                return;
            }
            desktopModeButton.setText(desktopModeEnabled ? "PC" : "M");
            desktopModeButton.setBackgroundColor(
                    desktopModeEnabled
                            ? Color.argb(220, 15, 90, 160)
                            : Color.argb(180, 30, 30, 30));
        }

        private void applyDesktopMode(WebView webView) {
            if (webView == null) {
                return;
            }
            try {
                WebSettings settings = webView.getSettings();
                if (settings == null) {
                    return;
                }
                if (TextUtils.isEmpty(defaultUserAgent)) {
                    defaultUserAgent = settings.getUserAgentString();
                }
                settings.setUseWideViewPort(desktopModeEnabled);
                settings.setLoadWithOverviewMode(desktopModeEnabled);
                settings.setUserAgentString(
                        desktopModeEnabled ? DESKTOP_USER_AGENT : defaultUserAgent);
            } catch (Throwable ignored) {
            }
        }

        private View findBookmarksDrawerContainer() {
            int leftId = resolveId("left_drawer");
            int rightId = resolveId("right_drawer");
            int bookmarkListId = resolveId("bookmark_list_view");
            View left = leftId != 0 ? activity.findViewById(leftId) : null;
            View right = rightId != 0 ? activity.findViewById(rightId) : null;
            if (containsViewWithId(left, bookmarkListId)) {
                return left;
            }
            if (containsViewWithId(right, bookmarkListId)) {
                return right;
            }
            return left != null ? left : right;
        }

        private boolean containsViewWithId(View root, int id) {
            if (root == null || id == 0) {
                return false;
            }
            if (root.getId() == id) {
                return true;
            }
            if (!(root instanceof ViewGroup)) {
                return false;
            }
            ViewGroup group = (ViewGroup) root;
            for (int i = 0; i < group.getChildCount(); i++) {
                if (containsViewWithId(group.getChildAt(i), id)) {
                    return true;
                }
            }
            return false;
        }

        private void showAddressKeyboard() {
            webKeyboardDismissed = false;
            keyboardMode = KEYBOARD_MODE_ADDRESS;
            keyboardTargetView = searchView;
            showKeyboardInternal();
        }

        private void showFocusedKeyboard(View target) {
            webKeyboardDismissed = false;
            keyboardMode = KEYBOARD_MODE_FOCUSED_VIEW;
            keyboardTargetView = target;
            if (target instanceof WebView) {
                activeWebView = (WebView) target;
            }
            showKeyboardInternal();
            hideSystemIme(target);
        }

        private void showKeyboardInternal() {
            if (searchView == null || overlayHost == null) {
                return;
            }
            disableSystemIme(searchView);
            if (inputBuffer == null) {
                inputBuffer = new StringBuilder();
            } else {
                inputBuffer.setLength(0);
            }
            CharSequence text = searchView.getText();
            if (keyboardMode == KEYBOARD_MODE_ADDRESS && !TextUtils.isEmpty(text)) {
                inputBuffer.append(text);
            }
            ensureKeyboardRoot();
            updateKeyboardPreview();
            keyboardRoot.setVisibility(View.VISIBLE);
            keyboardVisible = true;
            keyboardRoot.bringToFront();
            handler.removeCallbacks(imeSuppressor);
            handler.post(imeSuppressor);
            if (bookmarkButton != null) {
                bookmarkButton.setVisibility(View.GONE);
            }
            if (desktopModeButton != null) {
                desktopModeButton.setVisibility(View.GONE);
            }
        }

        private void hideKeyboard() {
            if (keyboardRoot != null) {
                keyboardRoot.setVisibility(View.GONE);
            }
            keyboardVisible = false;
            handler.removeCallbacks(imeSuppressor);
            if (bookmarkButton != null) {
                bookmarkButton.setVisibility(View.VISIBLE);
            }
            if (desktopModeButton != null) {
                desktopModeButton.setVisibility(View.VISIBLE);
            }
        }

        private void adjustFullscreenCustomView() {
            FrameLayout customContainer = getCustomContainer();
            boolean active =
                    customContainer != null
                            && customContainer.getParent() instanceof ViewGroup
                            && customContainer.getVisibility() == View.VISIBLE;

            if (!active) {
                if (customViewPixelCopyForced) {
                    customViewPixelCopyForced = false;
                    RayneoMirrorHelper.setVideoMirrorOverride(activity, false);
                }
                return;
            }

            if (!customViewPixelCopyForced) {
                customViewPixelCopyForced = true;
                RayneoMirrorHelper.setVideoMirrorOverride(activity, true);
            }
        }

        private FrameLayout getCustomContainer() {
            Object value = readField(activity, "C");
            return value instanceof FrameLayout ? (FrameLayout) value : null;
        }

        private Object readField(Object target, String fieldName) {
            if (target == null || TextUtils.isEmpty(fieldName)) {
                return null;
            }
            Class<?> type = target.getClass();
            while (type != null) {
                try {
                    java.lang.reflect.Field field = type.getDeclaredField(fieldName);
                    field.setAccessible(true);
                    return field.get(target);
                } catch (Throwable ignored) {
                    type = type.getSuperclass();
                }
            }
            return null;
        }

        private void ensureKeyboardRoot() {
            if (keyboardRoot != null) {
                return;
            }

            FrameLayout scrim = new FrameLayout(activity);
            scrim.setBackgroundColor(Color.argb(150, 0, 0, 0));
            scrim.setClickable(true);
            scrim.setFocusable(false);

            LinearLayout panel = new LinearLayout(activity);
            panel.setOrientation(LinearLayout.VERTICAL);
            panel.setBackgroundColor(Color.argb(230, 20, 20, 20));
            panel.setPadding(dp(8), dp(8), dp(8), dp(8));

            TextView preview = new TextView(activity);
            preview.setTextColor(Color.WHITE);
            preview.setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f);
            preview.setSingleLine(true);
            preview.setEllipsize(TextUtils.TruncateAt.START);
            preview.setBackgroundColor(Color.argb(255, 32, 32, 32));
            preview.setPadding(dp(8), dp(8), dp(8), dp(8));
            panel.addView(
                    preview,
                    new LinearLayout.LayoutParams(
                            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
            keyboardPreview = preview;

            panel.addView(buildKeyRow(new String[] {"1", "2", "3", "4", "5", "6", "7", "8", "9", "0"}));
            panel.addView(buildKeyRow(new String[] {"q", "w", "e", "r", "t", "y", "u", "i", "o", "p"}));
            panel.addView(buildKeyRow(new String[] {"a", "s", "d", "f", "g", "h", "j", "k", "l"}));
            panel.addView(buildKeyRow(new String[] {"z", "x", "c", "v", "b", "n", "m", ".", "/"}));

            LinearLayout shortcuts = new LinearLayout(activity);
            shortcuts.setOrientation(LinearLayout.HORIZONTAL);
            shortcuts.addView(createWideButton("www.", 1.0f, new KeyAction("www.")));
            shortcuts.addView(createWideButton(".com", 1.0f, new KeyAction(".com")));
            shortcuts.addView(createWideButton("https://", 1.2f, new KeyAction("https://")));
            shortcuts.addView(createWideButton("Paste", 0.9f, new PasteAction()));
            panel.addView(shortcuts);

            LinearLayout actions = new LinearLayout(activity);
            actions.setOrientation(LinearLayout.HORIZONTAL);
            actions.addView(createWideButton("Space", 2.0f, new KeyAction(" ")));
            actions.addView(createWideButton("Bksp", 1.0f, new BackspaceAction()));
            actions.addView(createWideButton("Clear", 1.0f, new ClearAction()));
            actions.addView(createWideButton("Close", 1.0f, new CloseAction()));
            actions.addView(createWideButton("Go", 1.0f, new SubmitAction()));
            panel.addView(actions);

            FrameLayout.LayoutParams panelParams =
                    new FrameLayout.LayoutParams(
                            ViewGroup.LayoutParams.MATCH_PARENT,
                            ViewGroup.LayoutParams.WRAP_CONTENT,
                            Gravity.BOTTOM);
            panelParams.leftMargin = dp(6);
            panelParams.rightMargin = dp(6);
            panelParams.bottomMargin = dp(6);
            scrim.addView(panel, panelParams);

            overlayHost.addView(
                    scrim,
                    new ViewGroup.LayoutParams(
                            ViewGroup.LayoutParams.MATCH_PARENT,
                            ViewGroup.LayoutParams.MATCH_PARENT));
            scrim.setVisibility(View.GONE);
            keyboardRoot = scrim;
        }

        private LinearLayout buildKeyRow(String[] labels) {
            LinearLayout row = new LinearLayout(activity);
            row.setOrientation(LinearLayout.HORIZONTAL);
            for (String label : labels) {
                row.addView(createWideButton(label, 1.0f, new KeyAction(label)));
            }
            return row;
        }

        private View createWideButton(String label, float weight, Runnable action) {
            Button button = new Button(activity);
            button.setText(label);
            button.setAllCaps(false);
            button.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f);
            button.setTextColor(Color.WHITE);
            button.setBackgroundColor(Color.argb(255, 56, 56, 56));
            button.setFocusable(false);
            button.setPadding(0, 0, 0, 0);
            button.setOnClickListener(
                    new View.OnClickListener() {
                        @Override
                        public void onClick(View v) {
                            action.run();
                        }
                    });

            LinearLayout.LayoutParams params =
                    new LinearLayout.LayoutParams(0, dp(34), weight);
            params.leftMargin = dp(2);
            params.rightMargin = dp(2);
            params.topMargin = dp(3);
            button.setLayoutParams(params);
            return button;
        }

        private void updateKeyboardPreview() {
            if (keyboardPreview == null) {
                return;
            }
            String text = inputBuffer != null ? inputBuffer.toString() : "";
            if (TextUtils.isEmpty(text)) {
                keyboardPreview.setText(
                        keyboardMode == KEYBOARD_MODE_FOCUSED_VIEW ? "Web Input" : "Address / Search");
            } else {
                keyboardPreview.setText(text);
            }
        }

        private void appendText(String text) {
            if (inputBuffer == null) {
                inputBuffer = new StringBuilder();
            }
            inputBuffer.append(text);
            updateKeyboardPreview();
        }

        private void backspace() {
            if (inputBuffer == null || inputBuffer.length() == 0) {
                return;
            }
            inputBuffer.deleteCharAt(inputBuffer.length() - 1);
            updateKeyboardPreview();
        }

        private void clearInput() {
            if (inputBuffer == null) {
                inputBuffer = new StringBuilder();
            } else {
                inputBuffer.setLength(0);
            }
            updateKeyboardPreview();
        }

        private void pasteClipboard() {
            Object service = activity.getSystemService(Context.CLIPBOARD_SERVICE);
            if (!(service instanceof ClipboardManager)) {
                return;
            }
            ClipboardManager manager = (ClipboardManager) service;
            if (!manager.hasPrimaryClip()) {
                return;
            }
            ClipData clip = manager.getPrimaryClip();
            if (clip == null || clip.getItemCount() == 0) {
                return;
            }
            CharSequence paste = clip.getItemAt(0).coerceToText(activity);
            if (TextUtils.isEmpty(paste)) {
                return;
            }
            if (inputBuffer == null) {
                inputBuffer = new StringBuilder();
            }
            inputBuffer.append(paste);
            if (keyboardMode == KEYBOARD_MODE_FOCUSED_VIEW) {
                sendTextToFocusedView(paste.toString());
            }
            updateKeyboardPreview();
        }

        private void submitAddress() {
            if (keyboardMode == KEYBOARD_MODE_FOCUSED_VIEW) {
                sendKeyToFocusedView(KeyEvent.KEYCODE_ENTER);
                hideKeyboard();
                return;
            }
            if (searchView == null) {
                hideKeyboard();
                return;
            }
            String text = inputBuffer != null ? inputBuffer.toString().trim() : "";
            if (TextUtils.isEmpty(text)) {
                hideKeyboard();
                return;
            }

            searchView.setText(text);
            searchView.setSelection(text.length());
            disableSystemIme(searchView);
            try {
                searchView.onEditorAction(EditorInfo.IME_ACTION_GO);
            } catch (Throwable ignored) {
            }
            try {
                searchView.dispatchKeyEvent(
                        new android.view.KeyEvent(
                                android.os.SystemClock.uptimeMillis(),
                                android.os.SystemClock.uptimeMillis(),
                                android.view.KeyEvent.ACTION_DOWN,
                                android.view.KeyEvent.KEYCODE_ENTER,
                                0));
                searchView.dispatchKeyEvent(
                        new android.view.KeyEvent(
                                android.os.SystemClock.uptimeMillis(),
                                android.os.SystemClock.uptimeMillis(),
                                android.view.KeyEvent.ACTION_UP,
                                android.view.KeyEvent.KEYCODE_ENTER,
                                0));
            } catch (Throwable ignored) {
            }
            searchView.clearFocus();
            hideKeyboard();
        }

        private void disableSystemIme(AutoCompleteTextView search) {
            try {
                search.setShowSoftInputOnFocus(false);
            } catch (Throwable ignored) {
            }
            hideSystemIme(search);
        }

        private void hideSystemIme(View target) {
            try {
                InputMethodManager imm =
                        (InputMethodManager) activity.getSystemService(Context.INPUT_METHOD_SERVICE);
                if (imm != null && target != null) {
                    imm.hideSoftInputFromWindow(target.getWindowToken(), 0);
                }
            } catch (Throwable ignored) {
            }
        }

        private boolean shouldUseInjectedKeyboard(View view) {
            if (view == null || view == searchView) {
                return false;
            }
            String className = view.getClass().getName();
            return className != null && className.contains("WebView");
        }

        private void sendTextToFocusedView(String text) {
            if (TextUtils.isEmpty(text)) {
                return;
            }
            if (injectTextIntoWebInput(text)) {
                return;
            }
            KeyCharacterMap map = KeyCharacterMap.load(KeyCharacterMap.VIRTUAL_KEYBOARD);
            KeyEvent[] events = map.getEvents(text.toCharArray());
            if (events == null) {
                return;
            }
            View target = keyboardTargetView != null ? keyboardTargetView : activity.getCurrentFocus();
            for (KeyEvent event : events) {
                dispatchToFocusedTarget(target, event);
            }
        }

        private void sendKeyToFocusedView(int keyCode) {
            if (keyCode == KeyEvent.KEYCODE_DEL && backspaceWebInput()) {
                return;
            }
            if (keyCode == KeyEvent.KEYCODE_ENTER && submitWebInput()) {
                return;
            }
            long now = android.os.SystemClock.uptimeMillis();
            View target = keyboardTargetView != null ? keyboardTargetView : activity.getCurrentFocus();
            dispatchToFocusedTarget(target, new KeyEvent(now, now, KeyEvent.ACTION_DOWN, keyCode, 0));
            dispatchToFocusedTarget(target, new KeyEvent(now, now, KeyEvent.ACTION_UP, keyCode, 0));
        }

        private void dispatchToFocusedTarget(View target, KeyEvent event) {
            if (target != null && target.dispatchKeyEvent(event)) {
                return;
            }
            activity.dispatchKeyEvent(event);
        }

        private void probeWebInput() {
            WebView webView = findWebView(sourceRoot);
            if (webView == null) {
                return;
            }
            activeWebView = webView;
            try {
                webView.evaluateJavascript(
                        "(function(){var e=document.activeElement;return !!e&&(e.tagName==='INPUT'||e.tagName==='TEXTAREA'||e.isContentEditable);})();",
                        new ValueCallback<String>() {
                            @Override
                            public void onReceiveValue(String value) {
                                boolean active = value != null && value.contains("true");
                                if (active) {
                                    if (suppressAutoWebKeyboard) {
                                        suppressInitialHomepageFocus();
                                        return;
                                    }
                                    hideSystemIme(webView);
                                    if (webKeyboardDismissed) {
                                        handler.removeCallbacks(imeSuppressor);
                                        handler.post(imeSuppressor);
                                        return;
                                    }
                                    handler.removeCallbacks(imeSuppressor);
                                    handler.post(imeSuppressor);
                                    if (!keyboardVisible || keyboardMode != KEYBOARD_MODE_FOCUSED_VIEW) {
                                        showFocusedKeyboard(webView);
                                    }
                                } else if (keyboardVisible && keyboardMode == KEYBOARD_MODE_FOCUSED_VIEW) {
                                    webKeyboardDismissed = false;
                                    hideKeyboard();
                                } else {
                                    webKeyboardDismissed = false;
                                }
                            }
                        });
            } catch (Throwable ignored) {
            }
        }

        private void suppressInitialHomepageFocus() {
            if (!suppressAutoWebKeyboard) {
                return;
            }
            if (searchView != null) {
                hideSystemIme(searchView);
                searchView.clearFocus();
            }
            webKeyboardDismissed = false;
            blurActiveWebInput();
            hideKeyboard();
        }

        private WebView findWebView(View root) {
            if (root == null) {
                return null;
            }
            if (root instanceof WebView) {
                return (WebView) root;
            }
            if (root instanceof ViewGroup) {
                ViewGroup group = (ViewGroup) root;
                for (int i = 0; i < group.getChildCount(); i++) {
                    WebView child = findWebView(group.getChildAt(i));
                    if (child != null) {
                        return child;
                    }
                }
            }
            return null;
        }

        private int resolveId(String name) {
            return activity.getResources().getIdentifier(name, "id", activity.getPackageName());
        }

        private int dp(int value) {
            return Math.round(
                    TypedValue.applyDimension(
                            TypedValue.COMPLEX_UNIT_DIP,
                            value,
                            activity.getResources().getDisplayMetrics()));
        }

        private boolean isDescendantOf(View child, View parent) {
            View current = child;
            while (current != null) {
                if (current == parent) {
                    return true;
                }
                Object viewParent = current.getParent();
                current = viewParent instanceof View ? (View) viewParent : null;
            }
            return false;
        }

        private boolean invokeDrawerBoolean(View drawerLayout, String methodName, View arg) {
            try {
                Method method = drawerLayout.getClass().getDeclaredMethod(methodName, View.class);
                method.setAccessible(true);
                Object value = method.invoke(drawerLayout, arg);
                return value instanceof Boolean && ((Boolean) value);
            } catch (Throwable ignored) {
            }
            return false;
        }

        private void invokeDrawerVoid(View drawerLayout, String methodName) {
            try {
                Method method = drawerLayout.getClass().getDeclaredMethod(methodName);
                method.setAccessible(true);
                method.invoke(drawerLayout);
            } catch (Throwable ignored) {
            }
        }

        private WebView getActiveWebView() {
            if (keyboardTargetView instanceof WebView) {
                return (WebView) keyboardTargetView;
            }
            if (activeWebView != null) {
                return activeWebView;
            }
            return observedWebView;
        }

        private boolean injectTextIntoWebInput(String text) {
            WebView webView = getActiveWebView();
            if (webView == null || TextUtils.isEmpty(text)) {
                return false;
            }
            String quoted = quoteJs(text);
            String script =
                    "(function(t){"
                            + "var e=document.activeElement;"
                            + "if(!e){return false;}"
                            + "if(!(e.tagName==='INPUT'||e.tagName==='TEXTAREA'||e.isContentEditable)){return false;}"
                            + "if(e.isContentEditable){document.execCommand('insertText',false,t);return true;}"
                            + "var start=typeof e.selectionStart==='number'?e.selectionStart:(e.value||'').length;"
                            + "var end=typeof e.selectionEnd==='number'?e.selectionEnd:start;"
                            + "var val=e.value||'';"
                            + "e.value=val.slice(0,start)+t+val.slice(end);"
                            + "var pos=start+t.length;"
                            + "if(e.setSelectionRange){e.setSelectionRange(pos,pos);}"
                            + "e.dispatchEvent(new Event('input',{bubbles:true}));"
                            + "e.dispatchEvent(new Event('change',{bubbles:true}));"
                            + "return true;"
                            + "})("
                            + quoted
                            + ");";
            return runWebScript(webView, script);
        }

        private boolean backspaceWebInput() {
            WebView webView = getActiveWebView();
            if (webView == null) {
                return false;
            }
            String script =
                    "(function(){"
                            + "var e=document.activeElement;"
                            + "if(!e){return false;}"
                            + "if(!(e.tagName==='INPUT'||e.tagName==='TEXTAREA'||e.isContentEditable)){return false;}"
                            + "if(e.isContentEditable){document.execCommand('delete');return true;}"
                            + "var start=typeof e.selectionStart==='number'?e.selectionStart:0;"
                            + "var end=typeof e.selectionEnd==='number'?e.selectionEnd:start;"
                            + "var val=e.value||'';"
                            + "if(start===end&&start>0){start--;}"
                            + "e.value=val.slice(0,start)+val.slice(end);"
                            + "if(e.setSelectionRange){e.setSelectionRange(start,start);}"
                            + "e.dispatchEvent(new Event('input',{bubbles:true}));"
                            + "e.dispatchEvent(new Event('change',{bubbles:true}));"
                            + "return true;"
                            + "})();";
            return runWebScript(webView, script);
        }

        private boolean clearWebInput() {
            WebView webView = getActiveWebView();
            if (webView == null) {
                return false;
            }
            String script =
                    "(function(){"
                            + "var e=document.activeElement;"
                            + "if(!e){return false;}"
                            + "if(!(e.tagName==='INPUT'||e.tagName==='TEXTAREA'||e.isContentEditable)){return false;}"
                            + "if(e.isContentEditable){e.innerText='';return true;}"
                            + "e.value='';"
                            + "if(e.setSelectionRange){e.setSelectionRange(0,0);}"
                            + "e.dispatchEvent(new Event('input',{bubbles:true}));"
                            + "e.dispatchEvent(new Event('change',{bubbles:true}));"
                            + "return true;"
                            + "})();";
            return runWebScript(webView, script);
        }

        private boolean submitWebInput() {
            WebView webView = getActiveWebView();
            if (webView == null) {
                return false;
            }
            String script =
                    "(function(){"
                            + "var e=document.activeElement;"
                            + "if(!e){return false;}"
                            + "if(!(e.tagName==='INPUT'||e.tagName==='TEXTAREA'||e.isContentEditable)){return false;}"
                            + "try{"
                            + "var kd=new KeyboardEvent('keydown',{key:'Enter',code:'Enter',keyCode:13,which:13,bubbles:true});"
                            + "var ku=new KeyboardEvent('keyup',{key:'Enter',code:'Enter',keyCode:13,which:13,bubbles:true});"
                            + "e.dispatchEvent(kd);e.dispatchEvent(ku);"
                            + "}catch(err){}"
                            + "if(e.form){"
                            + "if(e.form.requestSubmit){e.form.requestSubmit();}"
                            + "else{e.form.submit();}"
                            + "return true;"
                            + "}"
                            + "e.dispatchEvent(new Event('change',{bubbles:true}));"
                            + "return true;"
                            + "})();";
            return runWebScript(webView, script);
        }

        private boolean runWebScript(WebView webView, String script) {
            try {
                hideSystemIme(webView);
                webView.evaluateJavascript(script, null);
                return true;
            } catch (Throwable ignored) {
            }
            return false;
        }

        private void blurActiveWebInput() {
            WebView webView = getActiveWebView();
            if (webView == null) {
                return;
            }
            String script =
                    "(function(){"
                            + "var e=document.activeElement;"
                            + "if(!e){return false;}"
                            + "if(e.blur){e.blur();}"
                            + "return true;"
                            + "})();";
            runWebScript(webView, script);
        }

        private String quoteJs(String value) {
            if (value == null) {
                return "\"\"";
            }
            StringBuilder escaped = new StringBuilder(value.length() + 16);
            escaped.append('"');
            for (int i = 0; i < value.length(); i++) {
                char c = value.charAt(i);
                switch (c) {
                    case '\\':
                        escaped.append("\\\\");
                        break;
                    case '"':
                        escaped.append("\\\"");
                        break;
                    case '\n':
                        escaped.append("\\n");
                        break;
                    case '\r':
                        escaped.append("\\r");
                        break;
                    case '\t':
                        escaped.append("\\t");
                        break;
                    default:
                        if (c < 0x20) {
                            escaped.append(String.format("\\u%04x", (int) c));
                        } else {
                            escaped.append(c);
                        }
                        break;
                }
            }
            escaped.append('"');
            return escaped.toString();
        }

        private void invokeDrawerVoid(View drawerLayout, String methodName, View arg) {
            try {
                Method method = drawerLayout.getClass().getDeclaredMethod(methodName, View.class);
                method.setAccessible(true);
                method.invoke(drawerLayout, arg);
            } catch (Throwable ignored) {
            }
        }

        private final class KeyAction implements Runnable {
            private final String value;

            KeyAction(String value) {
                this.value = value;
            }

            @Override
            public void run() {
                if (keyboardMode == KEYBOARD_MODE_FOCUSED_VIEW) {
                    appendText(value);
                    sendTextToFocusedView(value);
                } else {
                    appendText(value);
                }
            }
        }

        private final class PasteAction implements Runnable {
            @Override
            public void run() {
                pasteClipboard();
            }
        }

        private final class BackspaceAction implements Runnable {
            @Override
            public void run() {
                backspace();
                if (keyboardMode == KEYBOARD_MODE_FOCUSED_VIEW) {
                    sendKeyToFocusedView(KeyEvent.KEYCODE_DEL);
                }
            }
        }

        private final class ClearAction implements Runnable {
            @Override
            public void run() {
                clearInput();
                if (keyboardMode == KEYBOARD_MODE_FOCUSED_VIEW) {
                    clearWebInput();
                }
            }
        }

        private final class CloseAction implements Runnable {
            @Override
            public void run() {
                if (keyboardMode == KEYBOARD_MODE_FOCUSED_VIEW) {
                    webKeyboardDismissed = false;
                    blurActiveWebInput();
                }
                hideKeyboard();
            }
        }

        private final class SubmitAction implements Runnable {
            @Override
            public void run() {
                submitAddress();
            }
        }
    }
}
