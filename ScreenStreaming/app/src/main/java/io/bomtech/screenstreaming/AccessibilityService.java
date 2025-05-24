package io.bomtech.screenstreaming;

import android.accessibilityservice.GestureDescription;
import android.content.Intent;
import android.graphics.Path;
import android.view.accessibility.AccessibilityEvent;

import java.util.Random;

public class AccessibilityService extends android.accessibilityservice.AccessibilityService {
    @Override
    public void onAccessibilityEvent(AccessibilityEvent accessibilityEvent) {

    }

    @Override
    public void onInterrupt() { }

    @Override
    public void onCreate() {
        super.onCreate();
    }

    @Override
    public void onDestroy() {
        super.onDestroy();
        JniBridge.accessibilityService = null;
    }

    @Override
    protected void onServiceConnected() {
        super.onServiceConnected();
        JniBridge.accessibilityService = this;
        Intent intent = this.getPackageManager().getLaunchIntentForPackage(this.getPackageName());
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TASK | Intent.FLAG_FROM_BACKGROUND);
        startActivity(intent);
    }

    private boolean swipe(int x1, int y1, int x2, int y2, int delay) {
        try {
            Path clickPath = new Path();
            clickPath.moveTo(x1, y1);
            for (int i = 1; i <= 20; i++) {
                int offset = new Random().nextInt(10);
                int x = x1 + (x2 - x1) * i * i / 400 + offset;
                int y = y1 + (y2 - y1)/20 * i + offset/2;
                clickPath.lineTo(x, y);
            }
            clickPath.lineTo(x2, y2);
            GestureDescription.Builder gestureBuilder = new GestureDescription.Builder();
            gestureBuilder.addStroke(new GestureDescription.StrokeDescription(clickPath, 0, new Random().nextInt(100) + delay));
            boolean res = dispatchGesture(gestureBuilder.build(), null, null);
            return true;
        } catch (Exception e) {
            LOG.printStackTrace(TAG, e);
            return false;
        }
    }
}
