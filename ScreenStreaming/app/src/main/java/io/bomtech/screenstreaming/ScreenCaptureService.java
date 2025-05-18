package io.bomtech.screenstreaming;

import android.app.Activity;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.graphics.PixelFormat;
import android.hardware.display.DisplayManager;
import android.hardware.display.VirtualDisplay;
import android.media.ImageReader;
import android.media.projection.MediaProjection;
import android.media.projection.MediaProjectionManager;
import android.os.Build;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.IBinder;
import android.util.DisplayMetrics;
import android.util.Log;
import android.view.WindowManager;
import androidx.core.app.NotificationCompat;

public class ScreenCaptureService extends Service {

    private static final String TAG = "ScreenCaptureService";
    public static final String EXTRA_RESULT_CODE = "resultCode";
    public static final String EXTRA_DATA = "data";
    private static final String CHANNEL_ID = "ScreenCaptureServiceChannel";
    private static final int NOTIFICATION_ID = 123;

    private MediaProjectionManager mediaProjectionManager;
    private MediaProjection mediaProjection;
    private VirtualDisplay virtualDisplay;
    private ImageReader imageReader;
    private HandlerThread imageReaderThread;
    private Handler imageReaderHandler;

    private int screenWidth;
    private int screenHeight;
    private int screenDensity;

    @Override
    public void onCreate() {
        super.onCreate();
        mediaProjectionManager = (MediaProjectionManager) getSystemService(Context.MEDIA_PROJECTION_SERVICE);
        createNotificationChannel();

        DisplayMetrics metrics = new DisplayMetrics();
        WindowManager windowManager = (WindowManager) getSystemService(Context.WINDOW_SERVICE);
        windowManager.getDefaultDisplay().getRealMetrics(metrics);
        screenDensity = metrics.densityDpi;
        screenWidth = metrics.widthPixels;
        screenHeight = metrics.heightPixels;

        // Adjust width and height if needed, e.g., for performance or specific aspect ratio
        // For simplicity, using full resolution here.
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        Log.d(TAG, "onStartCommand received");
        if (intent == null) {
            Log.e(TAG, "Intent is null, stopping service.");
            stopSelf();
            return START_NOT_STICKY;
        }

        int resultCode = intent.getIntExtra(EXTRA_RESULT_CODE, Activity.RESULT_CANCELED);
        Intent data = intent.getParcelableExtra(EXTRA_DATA);

        if (resultCode == Activity.RESULT_OK && data != null) {
            Log.d(TAG, "Starting screen capture permission obtained.");
            startForeground(NOTIFICATION_ID, createNotification());
            mediaProjection = mediaProjectionManager.getMediaProjection(resultCode, data);
            if (mediaProjection != null) {
                Log.d(TAG, "MediaProjection obtained. Starting capture.");
                startScreenCapture();
            } else {
                Log.e(TAG, "MediaProjection is null. Cannot start capture.");
                stopSelf();
            }
        } else {
            Log.e(TAG, "Result code or data invalid. ResultCode: " + resultCode);
            stopSelf(); // Stop if we didn't get permission
        }
        return START_STICKY; // Or START_NOT_STICKY depending on desired behavior
    }

    private void startScreenCapture() {
        if (mediaProjection == null) {
            Log.e(TAG, "MediaProjection is not available.");
            return;
        }

        // Setup ImageReader
        // Using PixelFormat.RGBA_8888 for broad compatibility, adjust as needed.
        imageReader = ImageReader.newInstance(screenWidth, screenHeight, PixelFormat.RGBA_8888, 2);

        imageReaderThread = new HandlerThread("ImageReaderThread");
        imageReaderThread.start();
        imageReaderHandler = new Handler(imageReaderThread.getLooper());

        imageReader.setOnImageAvailableListener(reader -> {
            // This is where you get screen frames
            // For WebRTC, you would convert this Image to a suitable format (e.g., I420) 
            // and send it via the DataChannel or a VideoTrack.
            // For sending raw frames via DataChannel (simplest for libdatachannel without full WebRTC stack):
            // 1. Get Image from reader.acquireLatestImage()
            // 2. Get ByteBuffer from image.getPlanes()[0].getBuffer()
            // 3. Create a byte array from ByteBuffer
            // 4. Send this byte array (possibly with metadata like width, height, format) via JniBridge.nativeSendData()
            // This part needs careful implementation for performance and data handling.
            // Example: 
            /*
            try (Image image = reader.acquireLatestImage()) {
                if (image != null) {
                    Image.Plane[] planes = image.getPlanes();
                    ByteBuffer buffer = planes[0].getBuffer();
                    byte[] data = new byte[buffer.remaining()];
                    buffer.get(data);
                    // TODO: Send 'data' (and width/height/format) to native layer via JniBridge
                    // JniBridge.nativeSendFrameData(data, image.getWidth(), image.getHeight(), image.getFormat());
                    // This nativeSendFrameData would then use dc->send(rtc::binary(data.begin(), data.end()));
                    Log.d(TAG, "Frame captured, size: " + data.length);
                }
            } catch (Exception e) {
                Log.e(TAG, "Error processing image: " + e.getMessage());
            }
            */
        }, imageReaderHandler);

        // Create VirtualDisplay
        virtualDisplay = mediaProjection.createVirtualDisplay("ScreenCapture",
                screenWidth, screenHeight, screenDensity,
                DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
                imageReader.getSurface(), null, null);
        Log.d(TAG, "VirtualDisplay created.");
        // At this point, JNI nativeStartStreaming can be called if it's not already called from MainActivity
        // JniBridge.nativeStartStreaming(); // If offer generation should happen after capture starts
    }

    private void stopScreenCapture() {
        Log.d(TAG, "Stopping screen capture.");
        if (virtualDisplay != null) {
            virtualDisplay.release();
            virtualDisplay = null;
        }
        if (imageReader != null) {
            imageReader.close();
            imageReader = null;
        }
        if (imageReaderThread != null) {
            imageReaderThread.quitSafely();
            imageReaderThread = null;
        }
        if (mediaProjection != null) {
            mediaProjection.stop();
            mediaProjection = null;
        }
    }

    private Notification createNotification() {
        Intent notificationIntent = new Intent(this, MainActivity.class);
        PendingIntent pendingIntent = PendingIntent.getActivity(this, 0, notificationIntent, PendingIntent.FLAG_IMMUTABLE);

        return new NotificationCompat.Builder(this, CHANNEL_ID)
                .setContentTitle("Screen Streaming Active")
                .setContentText("Your screen is being streamed.")
                .setSmallIcon(R.mipmap.ic_launcher) // Replace with your app's icon
                .setContentIntent(pendingIntent)
                .build();
    }

    private void createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            NotificationChannel serviceChannel = new NotificationChannel(
                    CHANNEL_ID,
                    "Screen Capture Service Channel",
                    NotificationManager.IMPORTANCE_DEFAULT
            );
            NotificationManager manager = getSystemService(NotificationManager.class);
            if (manager != null) {
                manager.createNotificationChannel(serviceChannel);
            }
        }
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null; // We don't provide binding, so return null
    }

    @Override
    public void onDestroy() {
        Log.d(TAG, "onDestroy called");
        stopScreenCapture();
        // JniBridge.nativeStopStreaming(); // Ensure WebRTC connection is also closed
        super.onDestroy();
    }
}
