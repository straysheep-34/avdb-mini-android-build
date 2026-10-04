package com.avdbmini.android;

import android.app.Application;
import org.mozilla.geckoview.GeckoRuntime;
import org.mozilla.geckoview.GeckoRuntimeSettings;

public final class AVDBApplication extends Application {
    private GeckoRuntime runtime;

    @Override
    public void onCreate() {
        super.onCreate();
        GeckoRuntimeSettings settings = new GeckoRuntimeSettings.Builder()
                .javaScriptEnabled(true)
                .remoteDebuggingEnabled(false)
                .build();
        runtime = GeckoRuntime.create(this, settings);
        runtime.warmUp();
    }

    public GeckoRuntime getRuntime() {
        return runtime;
    }
}
