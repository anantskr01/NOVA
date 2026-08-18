package com.aircontrol;

import android.Manifest;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.Service;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.content.pm.ServiceInfo;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.hardware.camera2.CameraAccessException;
import android.hardware.camera2.CameraCaptureSession;
import android.hardware.camera2.CameraCharacteristics;
import android.hardware.camera2.CameraDevice;
import android.hardware.camera2.CameraManager;
import android.hardware.camera2.CaptureRequest;
import android.media.Image;
import android.media.ImageReader;
import android.os.Build;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.IBinder;
import android.os.SystemClock;
import android.util.Log;
import android.util.Range;

import com.google.mediapipe.framework.image.BitmapImageBuilder;
import com.google.mediapipe.framework.image.MPImage;
import com.google.mediapipe.tasks.components.containers.NormalizedLandmark;
import com.google.mediapipe.tasks.core.BaseOptions;
import com.google.mediapipe.tasks.vision.core.RunningMode;
import com.google.mediapipe.tasks.vision.handlandmarker.HandLandmarker;
import com.google.mediapipe.tasks.vision.handlandmarker.HandLandmarkerResult;

import java.nio.ByteBuffer;
import java.util.Arrays;
import java.util.List;

public class CameraGestureService extends Service {

    private static final String TAG = "NovaCamera";

    // =========================================================
    // NOTIFICATION
    // =========================================================

    private static final String CHANNEL_ID =
            "nova_camera_channel";

    private static final int NOTIFICATION_ID = 1001;

    // =========================================================
    // CAMERA
    // =========================================================

    private CameraManager cameraManager;
    private CameraDevice cameraDevice;
    private CameraCaptureSession cameraSession;
    private CaptureRequest.Builder captureRequestBuilder;
    private ImageReader imageReader;
    private String cameraId;

    private HandlerThread cameraThread;
    private Handler cameraHandler;

    private boolean processingFrame = false;

    // =========================================================
    // MEDIAPIPE
    // =========================================================

    private HandLandmarker handLandmarker;

    // =========================================================
    // SMOOTH FINGER CONTROL
    // =========================================================

    /*
     * Small dead-zone.
     *
     * Movements smaller than this are ignored.
     * This prevents camera noise from moving the screen.
     */
    private static final float DEAD_ZONE = 0.0035f;

    /*
     * Position smoothing.
     *
     * Higher = faster response.
     * Lower = smoother but slower.
     */
    private static final float POSITION_SMOOTHING = 0.48f;

    /*
     * Converts normalized finger movement into
     * screen-pixel movement.
     *
     * Increase if scrolling feels too slow.
     */
    private static final float MOVEMENT_SCALE = 1.20f;

    /*
     * Maximum movement sent in one update.
     */
    private static final float MAX_STEP = 0.040f;

    /*
     * Minimum movement required before sending
     * another accessibility gesture.
     */
    private static final float SEND_THRESHOLD = 0.006f;

    /*
     * Minimum time between small gestures.
     *
     * Lower = more responsive.
     */
    private static final long MOVE_INTERVAL = 65;

    /*
     * After a movement begins, the same direction is kept "locked".
     * Returning the fingertip to its starting position is ignored instead
     * of producing an accidental opposite swipe. The lock is released only
     * after the finger has been still for this long.
     */
    private static final long DIRECTION_RESET_MS = 300;

    /*
     * Minimum per-frame movement that counts as intentional movement
     * for direction locking.
     */
    private static final float DIRECTION_EPSILON = 0.0045f;

    /* Target camera inference rate. Lower values increase responsiveness but use more CPU. */
    private static final long FRAME_INTERVAL = 66; // ~15 FPS; much lower CPU/battery use

    /*
     * Finger must move enough before control begins.
     */
    private static final float START_DEAD_ZONE = 0.008f;

    // =========================================================
    // HAND STATE
    // =========================================================

    private boolean handTracking = false;

    private float previousX = -1f;
    private float previousY = -1f;

    private float smoothX = -1f;
    private float smoothY = -1f;

    private long lastMoveTime = 0;
    private long lastMeaningfulMoveTime = 0;

    private int lockedXDirection = 0;
    private int lockedYDirection = 0;

    private long lastFrameTimestamp = 0;

    private int preferredFps = 15;

    // =========================================================
    // SERVICE CREATE
    // =========================================================

    @Override
    public void onCreate() {

        super.onCreate();

        Log.d(
                TAG,
                "NOVA CAMERA SERVICE CREATED"
        );

        cameraManager =
                (CameraManager)
                        getSystemService(
                                CAMERA_SERVICE
                        );

        createNotificationChannel();

        setupHandLandmarker();
    }

    // =========================================================
    // SERVICE START
    // =========================================================

    @Override
    public int onStartCommand(
            Intent intent,
            int flags,
            int startId
    ) {

        Log.d(
                TAG,
                "NOVA CAMERA SERVICE START COMMAND"
        );

        if (checkSelfPermission(
                Manifest.permission.CAMERA
        ) != PackageManager.PERMISSION_GRANTED) {

            Log.e(
                    TAG,
                    "CAMERA PERMISSION NOT GRANTED"
            );

            stopSelf();

            return START_NOT_STICKY;
        }

        startAsForegroundService();

        startCamera();

        return START_STICKY;
    }

    // =========================================================
    // FOREGROUND SERVICE
    // =========================================================

    private void startAsForegroundService() {

        Notification notification =
                createNotification();

        try {

            if (Build.VERSION.SDK_INT >=
                    Build.VERSION_CODES.Q) {

                startForeground(
                        NOTIFICATION_ID,
                        notification,
                        ServiceInfo
                                .FOREGROUND_SERVICE_TYPE_CAMERA
                );

            } else {

                startForeground(
                        NOTIFICATION_ID,
                        notification
                );
            }

            Log.d(
                    TAG,
                    "NOVA FOREGROUND SERVICE STARTED"
            );

        } catch (Exception e) {

            Log.e(
                    TAG,
                    "FOREGROUND SERVICE ERROR",
                    e
            );

            stopSelf();
        }
    }

    // =========================================================
    // NOTIFICATION CHANNEL
    // =========================================================

    private void createNotificationChannel() {

        if (Build.VERSION.SDK_INT >=
                Build.VERSION_CODES.O) {

            NotificationChannel channel =
                    new NotificationChannel(
                            CHANNEL_ID,
                            "NOVA Gesture Control",
                            NotificationManager
                                    .IMPORTANCE_LOW
                    );

            channel.setDescription(
                    "NOVA uses the camera for hand control."
            );

            NotificationManager manager =
                    (NotificationManager)
                            getSystemService(
                                    NOTIFICATION_SERVICE
                            );

            if (manager != null) {

                manager.createNotificationChannel(
                        channel
                );
            }
        }
    }

    // =========================================================
    // NOTIFICATION
    // =========================================================

    private Notification createNotification() {

        Notification.Builder builder;

        if (Build.VERSION.SDK_INT >=
                Build.VERSION_CODES.O) {

            builder =
                    new Notification.Builder(
                            this,
                            CHANNEL_ID
                    );

        } else {

            builder =
                    new Notification.Builder(
                            this
                    );
        }

        return builder
                .setContentTitle("NOVA")
                .setContentText(
                        "Smooth finger control is active"
                )
                .setSmallIcon(
                        android.R.drawable.ic_menu_view
                )
                .setOngoing(true)
                .setCategory(
                        Notification.CATEGORY_SERVICE
                )
                .build();
    }

    // =========================================================
    // CAMERA THREAD
    // =========================================================

    private void startCameraThread() {

        if (cameraThread != null) {
            return;
        }

        cameraThread =
                new HandlerThread(
                        "NovaCameraThread"
                );

        cameraThread.start();

        cameraHandler =
                new Handler(
                        cameraThread.getLooper()
                );
    }

    // =========================================================
    // START CAMERA
    // =========================================================

    private void startCamera() {

        if (cameraDevice != null) {

            Log.d(
                    TAG,
                    "CAMERA ALREADY OPEN"
            );

            return;
        }

        if (cameraManager == null) {

            Log.e(
                    TAG,
                    "CAMERA MANAGER NULL"
            );

            return;
        }

        if (checkSelfPermission(
                Manifest.permission.CAMERA
        ) != PackageManager.PERMISSION_GRANTED) {

            Log.e(
                    TAG,
                    "CAMERA PERMISSION DENIED"
            );

            return;
        }

        startCameraThread();

        findFrontCamera();

        if (cameraId == null) {

            Log.e(
                    TAG,
                    "NO CAMERA FOUND"
            );

            return;
        }

        try {

            imageReader =
                    ImageReader.newInstance(
                            320,
                            240,
                            android.graphics.ImageFormat.JPEG,
                            2
                    );

            imageReader.setOnImageAvailableListener(
                    reader -> {

                        Image image =
                                reader.acquireLatestImage();

                        if (image == null) {
                            return;
                        }

                        if (processingFrame) {

                            image.close();

                            return;
                        }

                        processingFrame = true;

                        processCameraImage(
                                image
                        );
                    },
                    cameraHandler
            );

            cameraManager.openCamera(
                    cameraId,

                    new CameraDevice.StateCallback() {

                        @Override
                        public void onOpened(
                                CameraDevice camera
                        ) {

                            Log.d(
                                    TAG,
                                    "CAMERA OPENED"
                            );

                            cameraDevice = camera;

                            createCameraSession();
                        }

                        @Override
                        public void onDisconnected(
                                CameraDevice camera
                        ) {

                            Log.e(
                                    TAG,
                                    "CAMERA DISCONNECTED"
                            );

                            camera.close();

                            cameraDevice = null;
                        }

                        @Override
                        public void onError(
                                CameraDevice camera,
                                int error
                        ) {

                            Log.e(
                                    TAG,
                                    "CAMERA ERROR = "
                                            + error
                            );

                            camera.close();

                            cameraDevice = null;
                        }
                    },

                    cameraHandler
            );

        } catch (Exception e) {

            Log.e(
                    TAG,
                    "OPEN CAMERA FAILED",
                    e
            );
        }
    }

    // =========================================================
    // FIND FRONT CAMERA
    // =========================================================

    private void findFrontCamera() {

        try {

            String[] cameraIds =
                    cameraManager.getCameraIdList();

            cameraId = null;

            for (String id : cameraIds) {

                CameraCharacteristics characteristics =
                        cameraManager
                                .getCameraCharacteristics(id);

                Integer facing =
                        characteristics.get(
                                CameraCharacteristics
                                        .LENS_FACING
                        );

                if (facing != null &&
                        facing ==
                                CameraCharacteristics
                                        .LENS_FACING_FRONT) {

                    cameraId = id;

                    Log.d(
                            TAG,
                            "FRONT CAMERA = "
                                    + cameraId
                    );

                    return;
                }
            }

            if (cameraIds.length > 0) {

                cameraId = cameraIds[0];

                Log.d(
                        TAG,
                        "FALLBACK CAMERA = "
                                + cameraId
                );
            }

        } catch (CameraAccessException e) {

            Log.e(
                    TAG,
                    "FIND CAMERA FAILED",
                    e
            );
        }
    }

    // =========================================================
    // CAMERA SESSION
    // =========================================================

    private void createCameraSession() {

        if (cameraDevice == null ||
                imageReader == null) {

            return;
        }

        try {

            captureRequestBuilder =
                    cameraDevice.createCaptureRequest(
                            CameraDevice.TEMPLATE_PREVIEW
                    );

            captureRequestBuilder.addTarget(
                    imageReader.getSurface()
            );

            cameraDevice.createCaptureSession(
                    Arrays.asList(
                            imageReader.getSurface()
                    ),

                    new CameraCaptureSession
                            .StateCallback() {

                        @Override
                        public void onConfigured(
                                CameraCaptureSession session
                        ) {

                            Log.d(
                                    TAG,
                                    "CAMERA SESSION READY"
                            );

                            cameraSession = session;

                            try {

                                captureRequestBuilder.set(
                                        CaptureRequest
                                                .CONTROL_MODE,
                                        CaptureRequest
                                                .CONTROL_MODE_AUTO
                                );

                                // Ask the camera for a low-power 15 FPS stream when supported.
                                try {
                                    CameraCharacteristics characteristics =
                                            cameraManager.getCameraCharacteristics(cameraId);
                                    Range<Integer>[] ranges = characteristics.get(
                                            CameraCharacteristics.CONTROL_AE_AVAILABLE_TARGET_FPS_RANGES);
                                    Range<Integer> best = null;
                                    if (ranges != null) {
                                        for (Range<Integer> range : ranges) {
                                            if (range.getLower() <= 15 && range.getUpper() >= 15) {
                                                if (best == null || range.getUpper() < best.getUpper()) {
                                                    best = range;
                                                }
                                            }
                                        }
                                    }
                                    if (best != null) {
                                        captureRequestBuilder.set(
                                                CaptureRequest.CONTROL_AE_TARGET_FPS_RANGE,
                                                best
                                        );
                                    }
                                } catch (Exception fpsError) {
                                    Log.d(TAG, "LOW POWER FPS RANGE UNAVAILABLE", fpsError);
                                }

                                cameraSession
                                        .setRepeatingRequest(
                                                captureRequestBuilder
                                                        .build(),
                                                null,
                                                cameraHandler
                                        );

                                Log.d(
                                        TAG,
                                        "CAMERA STREAM STARTED"
                                );

                            } catch (
                                    CameraAccessException e
                            ) {

                                Log.e(
                                        TAG,
                                        "STREAM ERROR",
                                        e
                                );
                            }
                        }

                        @Override
                        public void onConfigureFailed(
                                CameraCaptureSession session
                        ) {

                            Log.e(
                                    TAG,
                                    "CAMERA SESSION FAILED"
                            );
                        }
                    },

                    cameraHandler
            );

        } catch (Exception e) {

            Log.e(
                    TAG,
                    "CREATE SESSION FAILED",
                    e
            );
        }
    }

    // =========================================================
    // MEDIAPIPE
    // =========================================================

    private void setupHandLandmarker() {

        try {

            BaseOptions baseOptions =
                    BaseOptions.builder()
                            .setModelAssetPath(
                                    "hand_landmarker.task"
                            )
                            .build();

            HandLandmarker.HandLandmarkerOptions options =
                    HandLandmarker
                            .HandLandmarkerOptions
                            .builder()
                            .setBaseOptions(
                                    baseOptions
                            )
                            .setRunningMode(
                                    RunningMode.VIDEO
                            )
                            .setNumHands(1)
                            .setMinHandDetectionConfidence(
                                    0.30f
                            )
                            .setMinHandPresenceConfidence(
                                    0.30f
                            )
                            .setMinTrackingConfidence(
                                    0.30f
                            )
                            .build();

            handLandmarker =
                    HandLandmarker
                            .createFromOptions(
                                    this,
                                    options
                            );

            Log.d(
                    TAG,
                    "MEDIAPIPE READY"
            );

        } catch (Exception e) {

            Log.e(
                    TAG,
                    "MEDIAPIPE ERROR",
                    e
            );
        }
    }

    // =========================================================
    // PROCESS CAMERA IMAGE
    // =========================================================

    private void processCameraImage(
            Image image
    ) {

        Bitmap bitmap = null;
        Bitmap argbBitmap = null;

        try {
            long frameNow = SystemClock.uptimeMillis();
            if (frameNow - lastFrameTimestamp < FRAME_INTERVAL) {
                return;
            }

            ByteBuffer buffer =
                    image.getPlanes()[0]
                            .getBuffer();

            byte[] bytes =
                    new byte[
                            buffer.remaining()
                    ];

            buffer.get(bytes);

            bitmap =
                    BitmapFactory.decodeByteArray(
                            bytes,
                            0,
                            bytes.length
                    );

            if (bitmap == null) {
                return;
            }

            if (bitmap.getConfig() ==
                    Bitmap.Config.ARGB_8888) {

                argbBitmap = bitmap;

            } else {

                argbBitmap =
                        bitmap.copy(
                                Bitmap.Config.ARGB_8888,
                                false
                        );
            }

            if (handLandmarker == null) {
                return;
            }

            MPImage mpImage =
                    new BitmapImageBuilder(
                            argbBitmap
                    ).build();

            long timestamp =
                    SystemClock.uptimeMillis();

            if (timestamp <= lastFrameTimestamp) {
                timestamp = lastFrameTimestamp + 1;
            }

            lastFrameTimestamp = timestamp;

            HandLandmarkerResult result =
                    handLandmarker.detectForVideo(
                            mpImage,
                            timestamp
                    );

            onHandResult(result);

        } catch (Exception e) {

            Log.e(
                    TAG,
                    "FRAME PROCESS ERROR",
                    e
            );

        } finally {

            image.close();

            if (argbBitmap != null &&
                    argbBitmap != bitmap) {

                argbBitmap.recycle();
            }

            if (bitmap != null &&
                    bitmap != argbBitmap) {

                bitmap.recycle();
            }

            processingFrame = false;
        }
    }

    // =========================================================
    // HAND RESULT
    // =========================================================

    private void onHandResult(
            HandLandmarkerResult result
    ) {

        if (result == null ||
                result.landmarks() == null ||
                result.landmarks().isEmpty()) {

            resetFingerTracking();

            return;
        }

        List<NormalizedLandmark> landmarks =
                result.landmarks().get(0);

        if (landmarks == null ||
                landmarks.size() <= 8) {

            resetFingerTracking();

            return;
        }

        /*
         * MediaPipe landmark 8:
         *
         * INDEX FINGERTIP
         */

        float rawX =
                landmarks.get(8).x();

        float rawY =
                landmarks.get(8).y();

        // =====================================================
        // FIRST DETECTION
        // =====================================================

        if (!handTracking) {

            handTracking = true;

            smoothX = rawX;
            smoothY = rawY;

            previousX = rawX;
            previousY = rawY;

            lastMoveTime =
                    SystemClock.uptimeMillis();

            Log.d(
                    TAG,
                    "INDEX FINGER TRACKING STARTED"
            );

            return;
        }

        // =====================================================
        // SMOOTH POSITION
        // =====================================================

        smoothX =
                smoothX
                        + (rawX - smoothX)
                        * POSITION_SMOOTHING;

        smoothY =
                smoothY
                        + (rawY - smoothY)
                        * POSITION_SMOOTHING;

        // =====================================================
        // FRAME MOVEMENT
        // =====================================================

        float deltaX =
                smoothX - previousX;

        float deltaY =
                smoothY - previousY;

        previousX = smoothX;
        previousY = smoothY;

        float absX =
                Math.abs(deltaX);

        float absY =
                Math.abs(deltaY);

        long now =
                SystemClock.uptimeMillis();

        /*
         * Direction lock:
         * once the finger starts moving in one direction, opposite
         * movement is treated as the user's return-to-center motion.
         * It is ignored until the finger becomes still for a short time.
         */
        if (absX >= DIRECTION_EPSILON || absY >= DIRECTION_EPSILON) {
            lastMeaningfulMoveTime = now;
        } else if ((lockedXDirection != 0 || lockedYDirection != 0) &&
                now - lastMeaningfulMoveTime >= DIRECTION_RESET_MS) {
            lockedXDirection = 0;
            lockedYDirection = 0;
        }

        if (lockedXDirection == 0 && absX >= DIRECTION_EPSILON) {
            lockedXDirection = deltaX > 0f ? 1 : -1;
        }

        if (lockedYDirection == 0 && absY >= DIRECTION_EPSILON) {
            lockedYDirection = deltaY > 0f ? 1 : -1;
        }

        if (lockedXDirection != 0 &&
                deltaX != 0f &&
                Math.signum(deltaX) != lockedXDirection) {
            deltaX = 0f;
        }

        if (lockedYDirection != 0 &&
                deltaY != 0f &&
                Math.signum(deltaY) != lockedYDirection) {
            deltaY = 0f;
        }

        absX = Math.abs(deltaX);
        absY = Math.abs(deltaY);

        // =====================================================
        // DEAD ZONE
        // =====================================================

        if (absX < DEAD_ZONE) {
            deltaX = 0f;
        }

        if (absY < DEAD_ZONE) {
            deltaY = 0f;
        }

        if (deltaX == 0f &&
                deltaY == 0f) {

            return;
        }

        // =====================================================
        // START DEAD ZONE
        // =====================================================

        if (absX < START_DEAD_ZONE &&
                absY < START_DEAD_ZONE) {

            return;
        }

        // =====================================================
        // MOVEMENT RATE LIMIT
        // =====================================================

        if (now - lastMoveTime <
                MOVE_INTERVAL) {

            return;
        }

        lastMoveTime = now;

        // =====================================================
        // SCALE MOVEMENT
        // =====================================================

        float moveX =
                deltaX * MOVEMENT_SCALE;

        float moveY =
                deltaY * MOVEMENT_SCALE;

        // =====================================================
        // LIMIT LARGE MOVEMENTS
        // =====================================================

        moveX =
                clamp(
                        moveX,
                        -MAX_STEP,
                        MAX_STEP
                );

        moveY =
                clamp(
                        moveY,
                        -MAX_STEP,
                        MAX_STEP
                );

        // =====================================================
        // SEND MOVEMENT
        // =====================================================

        GestureAccessibilityService service =
                GestureAccessibilityService
                        .getInstance();

        if (service == null) {

            Log.e(
                    TAG,
                    "ACCESSIBILITY SERVICE NOT CONNECTED"
            );

            return;
        }

        /*
         * Front-camera coordinates are mirrored.
         *
         * Therefore X is inverted.
         *
         * Y remains natural:
         *
         * Finger UP   -> screen UP
         * Finger DOWN -> screen DOWN
         */

        float screenMoveX =
                -moveX;

        float screenMoveY =
                moveY;

        if (Math.abs(screenMoveX) >=
                SEND_THRESHOLD ||
                Math.abs(screenMoveY) >=
                        SEND_THRESHOLD) {

            service.moveFinger(
                    screenMoveX,
                    screenMoveY
            );
        }
    }

    // =========================================================
    // CLAMP
    // =========================================================

    private float clamp(
            float value,
            float min,
            float max
    ) {

        return Math.max(
                min,
                Math.min(
                        max,
                        value
                )
        );
    }

    // =========================================================
    // RESET
    // =========================================================

    private void resetFingerTracking() {

        if (handTracking) {

            Log.d(
                    TAG,
                    "INDEX FINGER LOST"
            );
        }

        handTracking = false;

        previousX = -1f;
        previousY = -1f;

        smoothX = -1f;
        smoothY = -1f;

        lastMoveTime = 0;
        lastMeaningfulMoveTime = 0;
        lockedXDirection = 0;
        lockedYDirection = 0;
        lastFrameTimestamp = 0;
    }

    // =========================================================
    // CLOSE CAMERA
    // =========================================================

    private void closeCamera() {

        if (cameraSession != null) {

            try {
                cameraSession.stopRepeating();
            } catch (Exception ignored) {
            }

            try {
                cameraSession.close();
            } catch (Exception ignored) {
            }

            cameraSession = null;
        }

        if (cameraDevice != null) {

            try {
                cameraDevice.close();
            } catch (Exception ignored) {
            }

            cameraDevice = null;
        }

        if (imageReader != null) {

            try {
                imageReader.close();
            } catch (Exception ignored) {
            }

            imageReader = null;
        }

        processingFrame = false;

        if (cameraThread != null) {

            cameraThread.quitSafely();

            cameraThread = null;
            cameraHandler = null;
        }

        resetFingerTracking();
    }

    // =========================================================
    // SERVICE DESTROY
    // =========================================================

    @Override
    public void onDestroy() {

        Log.d(
                TAG,
                "NOVA CAMERA SERVICE DESTROYED"
        );

        closeCamera();

        if (handLandmarker != null) {

            try {
                handLandmarker.close();
            } catch (Exception ignored) {
            }

            handLandmarker = null;
        }

        super.onDestroy();
    }

    // =========================================================
    // BIND
    // =========================================================

    @Override
    public IBinder onBind(
            Intent intent
    ) {

        return null;
    }
}