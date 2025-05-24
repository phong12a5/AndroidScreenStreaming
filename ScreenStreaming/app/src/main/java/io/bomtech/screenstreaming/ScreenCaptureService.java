package io.bomtech.screenstreaming;

import android.app.Activity;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
// import android.graphics.PixelFormat; // No longer needed for raw frames
import android.hardware.display.DisplayManager;
import android.hardware.display.VirtualDisplay;
import android.media.MediaCodec;
import android.media.MediaCodecInfo;
import android.media.MediaFormat;
// import android.media.ImageReader; // No longer needed
import android.media.projection.MediaProjection;
import android.media.projection.MediaProjectionManager;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.IBinder;
import android.util.DisplayMetrics;
import android.util.Log;
import android.view.Surface;
import android.view.WindowManager;
import androidx.core.app.NotificationCompat;

import java.io.IOException;
import java.nio.ByteBuffer;

public class ScreenCaptureService extends Service {

    private static final String TAG = "ScreenCaptureService";
    public static final String EXTRA_RESULT_CODE = "resultCode";
    public static final String EXTRA_DATA = "data";
    private static final String CHANNEL_ID = "ScreenCaptureServiceChannel";
    private static final int NOTIFICATION_ID = 123;

    private static final String VIDEO_MIME_TYPE = MediaFormat.MIMETYPE_VIDEO_AVC; // H.264
    private int videoWidth;
    private int videoHeight;
    private static final int VIDEO_MAX_WIDTH = 320;
    private static final int VIDEO_MAX_HEIGHT = 640;
    private static final int VIDEO_BITRATE = 512 * 1024;
    private static final int VIDEO_FRAME_RATE = 15;
    private static final int VIDEO_I_FRAME_INTERVAL = 10;
    private MediaProjectionManager mediaProjectionManager;
    private MediaProjection mediaProjection;
    private VirtualDisplay virtualDisplay;

    private MediaCodec videoEncoder;
    private Surface inputSurface;
    private HandlerThread encoderThread;
    private Handler encoderHandler;
    private MediaCodec.BufferInfo bufferInfo;


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

        float screenRatio = (float) screenHeight / screenWidth;
        // Adjust video width and height based on screen ratio, make sure fit in 320x640
        if (screenRatio > 2) { // if screenRate > 640/320
            videoHeight = VIDEO_MAX_HEIGHT;
            videoWidth = (int) (videoHeight / screenRatio);
        } else {
            videoWidth = VIDEO_MAX_WIDTH;
            videoHeight = (int) (videoWidth * screenRatio);
        }


        /*
        // Set video dimensions (e.g., half of screen resolution)
        videoWidth = screenWidth / 2;
        videoHeight = screenHeight / 2;
         */

        // Ensure width and height are multiples of 2, common requirement for encoders
        if (videoWidth % 2 != 0) videoWidth--;
        if (videoHeight % 2 != 0) videoHeight--;

        JniBridge.nativeConfigScreen(screenWidth, screenHeight, screenDensity, videoWidth, videoHeight);

        Log.d(TAG, "Screen dimensions: " + screenWidth + "x" + screenHeight + " Density: " + screenDensity);
        Log.d(TAG, "Video encoding dimensions: " + videoWidth + "x" + videoHeight);
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
                Log.d(TAG, "MediaProjection obtained. Starting capture and encoding.");
                try {
                    startScreenCaptureAndEncode();
                } catch (IOException e) {
                    Log.e(TAG, "Failed to start screen capture/encoding", e);
                    stopSelf();
                    return START_NOT_STICKY;
                }
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

    private void startScreenCaptureAndEncode() throws IOException {
        if (mediaProjection == null) {
            Log.e(TAG, "MediaProjection is not available.");
            return;
        }

        bufferInfo = new MediaCodec.BufferInfo();

        MediaFormat format = MediaFormat.createVideoFormat(VIDEO_MIME_TYPE, videoWidth, videoHeight);
        format.setInteger(MediaFormat.KEY_COLOR_FORMAT, MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface);
        format.setInteger(MediaFormat.KEY_BIT_RATE, VIDEO_BITRATE);
        format.setInteger(MediaFormat.KEY_FRAME_RATE, VIDEO_FRAME_RATE);
        format.setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, VIDEO_I_FRAME_INTERVAL);
        // format.setInteger(MediaFormat.KEY_BITRATE_MODE, MediaCodecInfo.EncoderCapabilities.BITRATE_MODE_CBR); // Optional: Constant Bitrate

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            format.setInteger(MediaFormat.KEY_LATENCY, 1); // Yêu cầu encoder hoạt động ở chế độ low latency
            format.setInteger(MediaFormat.KEY_PRIORITY, 0); // 0 for realtime
        }

        Log.d(TAG, "Creating video encoder with format: " + format);
        videoEncoder = MediaCodec.createEncoderByType(VIDEO_MIME_TYPE);
        videoEncoder.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE);
        inputSurface = videoEncoder.createInputSurface(); // Get the input surface from the encoder
        videoEncoder.start();
        Log.d(TAG, "Video encoder started.");

        // Create VirtualDisplay with the encoder's input surface
        virtualDisplay = mediaProjection.createVirtualDisplay("ScreenCapture",
                videoWidth, videoHeight, screenDensity,
                DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
                inputSurface, // Pass the encoder's surface here
                null, null);
        Log.d(TAG, "VirtualDisplay created and directed to encoder surface.");

        // Start a thread to handle encoder output
        encoderThread = new HandlerThread("EncoderOutputThread", Thread.MAX_PRIORITY);
        encoderThread.start();
        encoderHandler = new Handler(encoderThread.getLooper());
        encoderHandler.post(this::drainEncoder);
    }

    private void drainEncoder() {
        if (videoEncoder == null) return;

        while (true) {
            try {
                int outputBufferIndex = videoEncoder.dequeueOutputBuffer(bufferInfo, 10000); // 10ms timeout

                if (outputBufferIndex == MediaCodec.INFO_TRY_AGAIN_LATER) {
                    // No output available yet
                } else if (outputBufferIndex == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                    MediaFormat newFormat = videoEncoder.getOutputFormat();
                    Log.d(TAG, "Encoder output format changed: " + newFormat);
                    ByteBuffer sps = newFormat.getByteBuffer("csd-0");
                    ByteBuffer pps = newFormat.getByteBuffer("csd-1");

                    if (sps != null && pps != null) {
                        // Make sure to rewind buffers before reading, in case they were read before
                        sps.rewind();
                        pps.rewind();

                        int spsSize = sps.remaining();
                        int ppsSize = pps.remaining();
                        byte[] configData = new byte[spsSize + ppsSize];
                        
                        sps.get(configData, 0, spsSize);
                        pps.get(configData, spsSize, ppsSize); // Corrected offset

                        Thread.sleep(2000);
                        Log.d(TAG, "Sending Codec Config Data (SPS/PPS), size: " + configData.length);
//                        if (JniBridge.isDataChannelReady()) {
                            JniBridge.nativeSendCodecConfigData(configData, configData.length);
//                        } else {
//                            Log.w(TAG, "DataChannel not ready, codec config not sent yet.");
//                            // TODO: Consider queueing this config data if DC is expected to open soon
//                        }
                    } else {
                        Log.w(TAG, "SPS or PPS was null after format changed.");
                    }
                } else if (outputBufferIndex < 0) {
                    // Ignore
                } else {
                    ByteBuffer outputBuffer = videoEncoder.getOutputBuffer(outputBufferIndex);
                    if (outputBuffer == null) {
                        Log.e(TAG, "encoderOutputBuffer " + outputBufferIndex + " was null");
                        continue;
                    }

                    if ((bufferInfo.flags & MediaCodec.BUFFER_FLAG_CODEC_CONFIG) != 0) {
                        // This buffer contains codec-specific data (e.g., SPS/PPS)
                        // This might be redundant if already handled by INFO_OUTPUT_FORMAT_CHANGED
                        Log.d(TAG, "Received BUFFER_FLAG_CODEC_CONFIG, size: " + bufferInfo.size);
                        byte[] configData = new byte[bufferInfo.size];
                        outputBuffer.get(configData);
                        // if (JniBridge.isDataChannelReady()) { // Check before sending this too
                            JniBridge.nativeSendCodecConfigData(configData, configData.length);
                        // } else {
                        //    Log.w(TAG, "DataChannel not ready, BUFFER_FLAG_CODEC_CONFIG not sent yet.");
                        // }
                        videoEncoder.releaseOutputBuffer(outputBufferIndex, false);
                        continue; // Don't send this as a frame
                    }

                    if (bufferInfo.size != 0) {
//                        if (JniBridge.isDataChannelReady()) {
                        byte[] encodedData = new byte[bufferInfo.size];
                            outputBuffer.get(encodedData);

                        boolean isKeyFrame = (bufferInfo.flags & MediaCodec.BUFFER_FLAG_KEY_FRAME) != 0;
//                             Log.d(TAG, "Sending encoded frame. Size: " + encodedData.length + ", KeyFrame: " + isKeyFrame + ", PTS: " + bufferInfo.presentationTimeUs);
                        JniBridge.nativeSendEncodedFrame(encodedData, encodedData.length, isKeyFrame, bufferInfo.presentationTimeUs);
//                        } else {
////                             Log.w(TAG, "DataChannel not ready, encoded frame dropped."); // Can be very verbose
//                        }
                    }

                    videoEncoder.releaseOutputBuffer(outputBufferIndex, false);

                    if ((bufferInfo.flags & MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0) {
                        Log.d(TAG, "End of stream reached");
                        break; // Exit loop
                    }
                }
            } catch (IllegalStateException e) {
                Log.e(TAG, "drainEncoder IllegalStateException: " + e.getMessage(), e);
                break; // Exit loop on error
            } catch (Exception e) { // Catch any other exception to prevent thread crash
                 Log.e(TAG, "drainEncoder Exception: " + e.getMessage(), e);
                 // Potentially break or continue based on error type
            }
        }
        Log.d(TAG, "Encoder drain loop finished.");
    }


    private void stopScreenCapture() {
        Log.d(TAG, "Stopping screen capture and encoding.");
        if (encoderThread != null) {
            encoderThread.quitSafely();
            encoderThread = null;
            encoderHandler = null;
        }
        if (videoEncoder != null) {
            try {
                videoEncoder.stop();
                videoEncoder.release();
            } catch (Exception e) {
                Log.e(TAG, "Error stopping/releasing video encoder", e);
            }
            videoEncoder = null;
        }
        if (inputSurface != null) {
            inputSurface.release();
            inputSurface = null;
        }
        if (virtualDisplay != null) {
            virtualDisplay.release();
            virtualDisplay = null;
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
