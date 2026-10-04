package com.avdbmini.android;

import android.app.Activity;
import android.graphics.Color;
import android.os.Bundle;
import android.util.Log;
import android.view.View;
import android.view.Window;
import android.view.WindowInsets;
import android.view.WindowInsetsController;
import android.view.WindowManager;
import android.widget.ScrollView;
import android.widget.TextView;

import org.mozilla.geckoview.AllowOrDeny;
import org.mozilla.geckoview.GeckoResult;
import org.mozilla.geckoview.GeckoRuntime;
import org.mozilla.geckoview.GeckoSession;
import org.mozilla.geckoview.GeckoSession.NavigationDelegate;
import org.mozilla.geckoview.GeckoView;

public final class MainActivity extends Activity {
    private static final String TAG = "AVDBMini";
    private static GeckoRuntime sRuntime;

    private GeckoView geckoView;
    private GeckoSession session;
    private MiniServer server;
    private String localOrigin;
    private boolean htmlFullscreen;
    private boolean recoveryAttempted;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        Window window = getWindow();
        window.setStatusBarColor(Color.rgb(9, 11, 16));
        window.setNavigationBarColor(Color.rgb(9, 11, 16));
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);

        geckoView = new GeckoView(this);
        geckoView.setBackgroundColor(Color.rgb(9, 11, 16));
        setContentView(geckoView);

        try {
            server = new MiniServer(this);
            localOrigin = server.start();

            if (sRuntime == null) {
                sRuntime = GeckoRuntime.create(this);
            }

            createSessionAndLoad();
        } catch (Throwable t) {
            showFatalError("AVDB Mini startup failed", t);
        }
    }

    private void createSessionAndLoad() {
        GeckoSession newSession = new GeckoSession();
        installDelegates(newSession);
        newSession.open(sRuntime);
        geckoView.setSession(newSession);
        session = newSession;
        newSession.loadUri(localOrigin + "/");
    }

    private void installDelegates(GeckoSession targetSession) {
        targetSession.setNavigationDelegate(new GeckoSession.NavigationDelegate() {
            @Override
            public GeckoResult<AllowOrDeny> onLoadRequest(
                    GeckoSession s, NavigationDelegate.LoadRequest request) {
                String uri = request.uri == null ? "" : request.uri;
                String lower = uri.toLowerCase();

                if (request.target == NavigationDelegate.TARGET_WINDOW_NEW) {
                    return GeckoResult.deny();
                }

                if (lower.startsWith("intent:") || lower.startsWith("market:") ||
                        lower.startsWith("mailto:") || lower.startsWith("tel:")) {
                    return GeckoResult.deny();
                }

                if (uri.isEmpty() || uri.startsWith("about:") ||
                        (localOrigin != null && uri.startsWith(localOrigin))) {
                    return GeckoResult.allow();
                }

                return GeckoResult.deny();
            }

            @Override
            public GeckoResult<AllowOrDeny> onSubframeLoadRequest(
                    GeckoSession s, NavigationDelegate.LoadRequest request) {
                String uri = request.uri == null ? "" : request.uri.toLowerCase();
                if (uri.startsWith("intent:") || uri.startsWith("market:") ||
                        uri.startsWith("mailto:") || uri.startsWith("tel:")) {
                    return GeckoResult.deny();
                }
                return GeckoResult.allow();
            }

            @Override
            public GeckoResult<GeckoSession> onNewSession(GeckoSession s, String uri) {
                return null;
            }
        });

        targetSession.setContentDelegate(new GeckoSession.ContentDelegate() {
            @Override
            public void onFullScreen(GeckoSession s, boolean fullScreen) {
                htmlFullscreen = fullScreen;
                setImmersiveMode(fullScreen);
            }

            @Override
            public void onCrash(GeckoSession s) {
                Log.e(TAG, "Gecko content process crashed");
                runOnUiThread(MainActivity.this::recoverFromContentCrash);
            }
        });
    }

    private void recoverFromContentCrash() {
        if (isFinishing() || isDestroyed()) return;

        if (recoveryAttempted) {
            showFatalError("Gecko content process crashed twice",
                    new IllegalStateException("Repeated Gecko content-process crash"));
            return;
        }
        recoveryAttempted = true;

        try {
            if (geckoView != null) {
                try { geckoView.releaseSession(); } catch (Throwable ignored) {}
            }
            if (session != null) {
                try { session.close(); } catch (Throwable ignored) {}
                session = null;
            }
            createSessionAndLoad();
        } catch (Throwable t) {
            showFatalError("Unable to recover Gecko session", t);
        }
    }

    private void showFatalError(String title, Throwable error) {
        Log.e(TAG, title, error);

        TextView text = new TextView(this);
        text.setTextColor(Color.WHITE);
        text.setBackgroundColor(Color.rgb(9, 11, 16));
        text.setTextSize(14f);
        text.setPadding(32, 32, 32, 32);
        text.setTextIsSelectable(true);
        text.setText(title + "\n\n" + Log.getStackTraceString(error));

        ScrollView scroll = new ScrollView(this);
        scroll.setBackgroundColor(Color.rgb(9, 11, 16));
        scroll.addView(text);
        setContentView(scroll);
    }

    private void setImmersiveMode(boolean enabled) {
        Window window = getWindow();
        if (android.os.Build.VERSION.SDK_INT >= 30) {
            WindowInsetsController controller = window.getInsetsController();
            if (controller != null) {
                if (enabled) {
                    controller.hide(WindowInsets.Type.statusBars() | WindowInsets.Type.navigationBars());
                    controller.setSystemBarsBehavior(
                            WindowInsetsController.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE);
                } else {
                    controller.show(WindowInsets.Type.statusBars() | WindowInsets.Type.navigationBars());
                }
            }
        } else {
            View decor = window.getDecorView();
            if (enabled) {
                decor.setSystemUiVisibility(
                        View.SYSTEM_UI_FLAG_FULLSCREEN |
                        View.SYSTEM_UI_FLAG_HIDE_NAVIGATION |
                        View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY |
                        View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN |
                        View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION |
                        View.SYSTEM_UI_FLAG_LAYOUT_STABLE);
            } else {
                decor.setSystemUiVisibility(View.SYSTEM_UI_FLAG_LAYOUT_STABLE);
            }
        }
    }

    @Override
    public void onBackPressed() {
        if (htmlFullscreen && session != null) {
            session.exitFullScreen();
            return;
        }
        super.onBackPressed();
    }

    @Override
    protected void onDestroy() {
        if (geckoView != null) {
            try { geckoView.releaseSession(); } catch (Throwable ignored) {}
        }
        if (session != null) {
            try { session.close(); } catch (Throwable ignored) {}
            session = null;
        }
        if (server != null) {
            try { server.close(); } catch (Throwable ignored) {}
            server = null;
        }
        super.onDestroy();
    }
}
