package com.avdbmini.android;

import android.app.Activity;
import android.os.Bundle;
import android.graphics.Color;
import android.view.View;
import android.view.Window;
import android.view.WindowInsets;
import android.view.WindowInsetsController;
import android.view.WindowManager;

import org.mozilla.geckoview.GeckoResult;
import org.mozilla.geckoview.AllowOrDeny;
import org.mozilla.geckoview.GeckoSession;
import org.mozilla.geckoview.GeckoSession.NavigationDelegate;
import org.mozilla.geckoview.GeckoView;

public final class MainActivity extends Activity {
    private GeckoView geckoView;
    private GeckoSession session;
    private MiniServer server;
    private boolean htmlFullscreen = false;
    private String localOrigin;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        Window window = getWindow();
        window.setStatusBarColor(Color.rgb(9, 11, 16));
        window.setNavigationBarColor(Color.rgb(9, 11, 16));
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);

        try {
            server = new MiniServer(this);
            localOrigin = server.start();
        } catch (Exception e) {
            throw new RuntimeException("Unable to start AVDB local server", e);
        }

        geckoView = new GeckoView(this);
        geckoView.setBackgroundColor(Color.rgb(9, 11, 16));
        setContentView(geckoView);

        session = new GeckoSession();
        installDelegates();
        session.open(((AVDBApplication) getApplication()).getRuntime());
        geckoView.setSession(session);
        session.loadUri(localOrigin + "/");
    }

    private void installDelegates() {
        session.setNavigationDelegate(new GeckoSession.NavigationDelegate() {
            @Override
            public GeckoResult<AllowOrDeny> onLoadRequest(
                    GeckoSession s, NavigationDelegate.LoadRequest request) {
                if (request.target == NavigationDelegate.TARGET_WINDOW_NEW) {
                    return GeckoResult.deny();
                }
                // The app's top-level document stays on localhost. External playback
                // pages live in subframes, so ad scripts cannot replace the whole app.
                if (!request.isDirectNavigation && request.uri != null && !request.uri.startsWith(localOrigin)) {
                    return GeckoResult.deny();
                }
                return GeckoResult.allow();
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
                // Returning null means window.open()/target=_blank fails before
                // another browser window or external app can be created.
                return null;
            }
        });

        session.setContentDelegate(new GeckoSession.ContentDelegate() {
            @Override
            public void onFullScreen(GeckoSession s, boolean fullScreen) {
                htmlFullscreen = fullScreen;
                applyImmersiveMode(fullScreen);
            }

            @Override
            public void onCrash(GeckoSession s) {
                // A content-process crash should not kill the Activity; recreate the page.
                runOnUiThread(() -> {
                    if (server != null && session != null) {
                        try { session.loadUri(localOrigin + "/"); } catch (Throwable ignored) {}
                    }
                });
            }
        });
    }

    private void setImmersiveMode(boolean enabled) {
        Window window = getWindow();
        if (android.os.Build.VERSION.SDK_INT >= 30) {
            WindowInsetsController c = window.getInsetsController();
            if (c != null) {
                if (enabled) {
                    c.hide(WindowInsets.Type.statusBars() | WindowInsets.Type.navigationBars());
                    c.setSystemBarsBehavior(WindowInsetsController.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE);
                } else {
                    c.show(WindowInsets.Type.statusBars() | WindowInsets.Type.navigationBars());
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
        if (session != null) {
            // The app itself is a single local document with JS-managed views.
            // Let Android back exit rather than navigating into ad/iframe history.
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
        }
        if (server != null) {
            try { server.close(); } catch (Throwable ignored) {}
        }
        super.onDestroy();
    }
}
