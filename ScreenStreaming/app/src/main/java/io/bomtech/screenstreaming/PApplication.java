package io.bomtech.screenstreaming;

import android.app.Application;

public class PApplication extends Application {
    @Override
    public void onCreate() {
        JniBridge.nativeInit(new JniBridge());
        super.onCreate();
    }

    @Override
    public void onTerminate() {
        super.onTerminate();
        JniBridge.nativeDestroy();
    }
}
