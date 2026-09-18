package com.tdiqsy.app;

import android.app.Activity;
import android.app.Application;
import android.os.Bundle;

/** 应用入口：通过生命周期回调给所有页面统一加上交互特效 */
public class App extends Application {

    @Override
    public void onCreate() {
        super.onCreate();
        registerActivityLifecycleCallbacks(new ActivityLifecycleCallbacks() {
            @Override
            public void onActivityCreated(Activity activity, Bundle savedInstanceState) {
                TouchEffects.apply(activity.findViewById(android.R.id.content));
            }

            @Override public void onActivityStarted(Activity activity) {}
            @Override public void onActivityResumed(Activity activity) {}
            @Override public void onActivityPaused(Activity activity) {}
            @Override public void onActivityStopped(Activity activity) {}
            @Override public void onActivitySaveInstanceState(Activity activity, Bundle outState) {}
            @Override public void onActivityDestroyed(Activity activity) {}
        });
    }
}