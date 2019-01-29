package de.kutschertec.cameratest;

import android.Manifest;
import android.app.Activity;
import android.arch.lifecycle.LifecycleOwner;
import android.content.Context;
import android.content.pm.PackageManager;
import android.graphics.ImageFormat;
import android.graphics.Matrix;
import android.graphics.Point;
import android.graphics.Rect;
import android.graphics.RectF;
import android.graphics.SurfaceTexture;
import android.hardware.camera2.CameraAccessException;
import android.hardware.camera2.CameraCaptureSession;
import android.hardware.camera2.CameraCharacteristics;
import android.hardware.camera2.CameraDevice;
import android.hardware.camera2.CameraManager;
import android.hardware.camera2.CaptureRequest;
import android.hardware.camera2.params.StreamConfigurationMap;
import android.media.Image;
import android.media.ImageReader;
import android.os.Handler;
import android.os.HandlerThread;
import android.support.annotation.NonNull;
import android.support.annotation.Nullable;
import android.support.v4.app.ActivityCompat;
import android.util.Log;
import android.util.Range;
import android.util.Size;
import android.util.SparseIntArray;
import android.view.OrientationEventListener;
import android.view.Surface;
import android.view.TextureView;

import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

public class Camera2Component implements CameraController {
    private final Logger logger = new Logger(this);

    private final Activity context;

//    private AtomicInteger countDown = new AtomicInteger(500);

    private int deviceRotation;
    private int sensorOrientation;

    private HandlerThread backgroundThread;
    private Handler backgroundHandler;
    private HandlerThread watchDogThread;
    private Handler watchDogHandler;

    private TextureView textureView;
    private SurfaceTexture previewTexture;
    private ImageReader jpegImageReader;

    private int cameraWidth;
    private int cameraHeight;
    private Size previewSize;

    private Size cameraResolution = new Size(320, 240);
    private byte jpegQuality = 50;
    private boolean flash = false;
    private float zoomLevel = 0.0f;

    private List<Size> cameraResolutions = new ArrayList<>();

    private String cameraId;
    private CameraDevice cameraDevice;
    private Range<Integer> maxFpsRange;

    private boolean frozen = false;

    private ByteBuffer imageBuffer = ByteBuffer.allocate(65535);
    private ByteBuffer exchangeBuffer = ByteBuffer.allocate(65535);
    private Semaphore imageBufferSemaphore = new Semaphore(1);

    private AtomicLong watchDogTimer = new AtomicLong(0);

    /**
     * A {@link Semaphore} to prevent the app from exiting before closing the camera.
     */
    private Semaphore cameraOpenCloseSemaphore = new Semaphore(1);
    private CameraCaptureSession captureSession;

    private Runnable onCameraInitializedHandler = null;
    private Runnable onWatchDogTriggeredHandler = null;

    private OrientationEventListener orientationEventListener = null;

    private Runnable watchdog = new Runnable() {
        public void run() {
            long t = watchDogTimer.get();
            if ((t > 0) && (System.currentTimeMillis() > t + 1000)) {
                restartCamera();

                if (onWatchDogTriggeredHandler != null) {
                    onWatchDogTriggeredHandler.run();
                }
            }

            watchDogHandler.postDelayed(watchdog, 1000);
        }
    };

    /**
     * Create a new instance
     *
     * @param context        the application {@link Context}
     * @param textureView    the {@link TextureView that displays the camera preview}
     * @param deviceRotation the device rotation in degrees at the start of the application
     */
    public Camera2Component(@NonNull Activity context, @NonNull TextureView textureView, int deviceRotation) {
        logger.verbose("Camera2Component()");
        this.context = context;
        this.textureView = textureView;
        this.deviceRotation = deviceRotation;
        logger.verbose("Camera2Component() ... done.");
    }

    @Override
    public void setOnCameraInitializedHandler(Runnable onCameraInitializedHandler) {
        logger.verbose("Camera2Component.setOnCameraInitializedHandler()");

        this.onCameraInitializedHandler = onCameraInitializedHandler;

        logger.verbose("Camera2Component.setOnCameraInitializedHandler() ... done.");
    }

    @Override
    public void setOnWatchDogHandler(Runnable handler) {
        this.onWatchDogTriggeredHandler = handler;
    }

    @Override
    public void onResume(@NonNull LifecycleOwner owner) {
        logger.verbose("Camera2Component.onResume()");

        // start the background thread
        startBackgroundThread();

        // start the watchdog thread
        startWatchDogThread();

        // start the camera
        startCamera();

        logger.debug("Initializing orientation listener.");
        orientationEventListener = new OrientationEventListener(context) {
            @Override
            public void onOrientationChanged(int orientation) {
                int rotation = context.getWindowManager().getDefaultDisplay().getRotation();
                if (rotation != deviceRotation) {
                    logger.debug("Device rotated. Restarting camera.");
                    deviceRotation = rotation;
                    restartCamera();
                    logger.debug("Device rotated. Restarting camera ... done.");
                }
            }
        };
//        orientationEventListener.enable();
        logger.debug("Initializing orientation listener ... done.");

        watchDogHandler.post(watchdog);
        logger.verbose("Camera2Component.onResume() ... done.");
    }

    @Override
    public void onPause(@NonNull LifecycleOwner owner) {
        logger.verbose("Camera2Component.onPause()");

        logger.debug("Disabling orientation listener.");
        orientationEventListener.disable();
        orientationEventListener = null;
        logger.debug("Disabling orientation listener ... done.");

        logger.debug("Closing camera.");
        closeCamera();
        logger.debug("Closing camera ... done.");

        logger.debug("Stopping watchdog thread.");
        stopWatchDogThread();
        logger.debug("Stopping watchdog thread ... done.");

        logger.debug("Stopping background thread.");
        stopBackgroundThread();
        logger.debug("Stopping background thread ... done.");

        logger.verbose("Camera2Component.onPause() ... done.");
    }

    @Override
    public ByteBuffer getImageBuffer() {
        logger.verbose("Camera2Component.getImageBuffer()");
        try {
            // lock the semaphore and wait until we can aquire it
            imageBufferSemaphore.acquire();

            try {
                if (imageBuffer.remaining() > 0) {
                    // resize the exchange buffer, if the current image is larger
                    if (imageBuffer.remaining() > exchangeBuffer.capacity()) {
                        logger.debug("Resizing exchange buffer: " + imageBuffer.remaining() + "bytes");
                        exchangeBuffer = ByteBuffer.allocate(imageBuffer.remaining());
                        logger.debug("Resizing exchange buffer ... done.");
                    }


                    exchangeBuffer.clear();
                    exchangeBuffer.put(imageBuffer);
                    exchangeBuffer.flip();
                } else {
                    exchangeBuffer.rewind();
                }
                logger.verbose("Camera2Component.getImageBuffer() ... done.");
                return exchangeBuffer;
            } finally {
                // safely release the semaphore
                imageBufferSemaphore.release();
            }
        } catch (InterruptedException e) {
            logger.error("Error while waiting for the image buffer semaphore.", e);
            Thread.currentThread().interrupt();
            return ByteBuffer.allocate(0);
        }
    }

//    private void configureTransform(int width, int height) {
//        if (configureTransformConsumer != null) {
//            configureTransformConsumer.accept(width, height);
//        }
//    }

    /**
     * Configures the necessary {@link Matrix} transformation to `mTextureView`.
     * This method should be called after the camera preview size is determined in
     * setUpCameraOutputs and also the size of `mTextureView` is fixed.
     *
     * @param viewWidth  The width of `mTextureView`
     * @param viewHeight The height of `mTextureView`
     */
    private void configureTransform(int viewWidth, int viewHeight) {
        Activity activity = context;
        if ((textureView == null) || (previewSize == null) || (activity == null)) {
            return;
        }

        int rotation = activity.getWindowManager().getDefaultDisplay().getRotation();
        Matrix matrix = new Matrix();
        RectF viewRect = new RectF(0, 0, viewWidth, viewHeight);
        RectF bufferRect = new RectF(0, 0, previewSize.getHeight(), previewSize.getWidth());
        float centerX = viewRect.centerX();
        float centerY = viewRect.centerY();
        if (Surface.ROTATION_90 == rotation || Surface.ROTATION_270 == rotation) {
            bufferRect.offset(centerX - bufferRect.centerX(), centerY - bufferRect.centerY());
            matrix.setRectToRect(viewRect, bufferRect, Matrix.ScaleToFit.FILL);
            float scale = Math.max(
                    (float) viewHeight / previewSize.getHeight(),
                    (float) viewWidth / previewSize.getWidth());
            matrix.postScale(scale, scale, centerX, centerY);
            matrix.postRotate(90 * (rotation - 2), centerX, centerY);
        } else if (Surface.ROTATION_180 == rotation) {
            matrix.postRotate(180, centerX, centerY);
        }

        textureView.setTransform(matrix);
    }

    /**
     * Given {@code choices} of {@code Size}s supported by a camera, choose the smallest one that is
     * at least as large as the respective texture view size, and that is at most as large as the
     * respective max size, and whose aspect ratio matches with the specified value. If such size
     * doesn't exist, choose the largest one that is at most as large as the respective max size,
     * and whose aspect ratio matches with the specified value.
     *
     * @param choices           The list of sizes that the camera supports for the intended output
     *                          class
     * @param textureViewWidth  The width of the texture view relative to sensor coordinate
     * @param textureViewHeight The height of the texture view relative to sensor coordinate
     * @param maxWidth          The maximum width that can be chosen
     * @param maxHeight         The maximum height that can be chosen
     * @param aspectRatio       The aspect ratio
     * @return The optimal {@code Size}, or an arbitrary one if none were big enough
     */
    private Size chooseOptimalSize(Size[] choices, int textureViewWidth,
                                          int textureViewHeight, int maxWidth, int maxHeight, Size aspectRatio) {

        // Collect the supported resolutions that are at least as big as the preview Surface
        List<Size> bigEnough = new ArrayList<>();
        // Collect the supported resolutions that are smaller than the preview Surface
        List<Size> notBigEnough = new ArrayList<>();
        int w = aspectRatio.getWidth();
        int h = aspectRatio.getHeight();
        for (Size option : choices) {
            if (option.getWidth() <= maxWidth && option.getHeight() <= maxHeight &&
                    option.getHeight() == option.getWidth() * h / w) {
                if (option.getWidth() >= textureViewWidth &&
                        option.getHeight() >= textureViewHeight) {
                    bigEnough.add(option);
                } else {
                    notBigEnough.add(option);
                }
            }
        }

        // Pick the smallest of those big enough. If there is no one big enough, pick the
        // largest of those not big enough.
        if (!bigEnough.isEmpty()) {
            return Collections.min(bigEnough, new CompareSizesByArea());
        } else if (!notBigEnough.isEmpty()) {
            return Collections.max(notBigEnough, new CompareSizesByArea());
        } else {
            logger.error("Couldn't find any suitable preview size");
            return choices[0];
        }
    }

    /**
     * Opens the camera.
     *
     * @param width  the width of the camera
     * @param height the height of the camera
     */
    private void openCamera(int width, int height) {
        logger.verbose("Camera2Component.openCamera(width=" + width + ";height=" + height + ")");
        cameraWidth = width;
        cameraHeight = height;

        setupCameraOutputs(width, height);
        configureTransform(width, height);

        CameraManager manager = (CameraManager) context.getSystemService(Context.CAMERA_SERVICE);
        try {
            if (!cameraOpenCloseSemaphore.tryAcquire(2500, TimeUnit.MILLISECONDS)) {
                throw new RuntimeException("Time out waiting to lock camera opening.");
            }

            // check that we have camera permission
            if (ActivityCompat.checkSelfPermission(context, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED) {
                logger.verbose("Using CameraManager to open the camera.");
                manager.openCamera(cameraId, stateCallback, backgroundHandler);

                logger.verbose("Using CameraManager to open the camera ... done.");
            } else {
                logger.warn("We don't have the permission to open the camera.");
            }
        } catch (CameraAccessException e) {
            logger.error("Error accessing camera.", e);
        } catch (InterruptedException e) {
            throw new RuntimeException("Interrupted while trying to lock camera opening.", e);
        }
        logger.verbose("Camera2Component.openCamera() ... done.");
    }

    /**
     * Closes the current {@link CameraDevice}.
     */
    private void closeCamera() {
        logger.verbose("Camera2Component.closeCamera()");
        try {
            cameraOpenCloseSemaphore.acquire();

            // check if we have a capture session active
            if (null != captureSession) {
                logger.debug("Closing capture session.");
                captureSession.close();
                captureSession = null;
                logger.debug("Closing capture session ... done.");
            }

            // check if the camera device is opened
            if (null != cameraDevice) {
                logger.debug("Closing camera device.");
                cameraDevice.close();
                cameraDevice = null;
                logger.debug("Closing camera device ... done.");
            }

            // check if the JPEG reader is opened
            if (null != jpegImageReader) {
                logger.debug("Closing JPEG reader.");
                jpegImageReader.close();
                jpegImageReader = null;
                logger.debug("Closing JPEG reader ... done.");
            }
        } catch (InterruptedException e) {
            throw new RuntimeException("Interrupted while trying to lock camera closing.", e);
        } finally {
            cameraOpenCloseSemaphore.release();
        }
        logger.verbose("Camera2Component.closeCamera() ... done.");
    }

    /**
     * Starts a background thread and its {@link Handler}.
     */
    private void startBackgroundThread() {
        logger.verbose("Camera2Component.startBackgroundThread()");

        logger.debug("Starting background thread.");
        backgroundThread = new HandlerThread("CameraBackground");
        backgroundThread.start();
        backgroundHandler = new Handler(backgroundThread.getLooper());
        logger.debug("Starting background thread ... done");

        logger.verbose("Camera2Component.startBackgroundThread() ... done.");
    }

    /**
     * Stops the background thread and its {@link Handler}.
     */
    private void stopBackgroundThread() {
        logger.verbose("Camera2Component.stopBackgroundThread()");
        if (backgroundThread != null) {
            logger.debug("Stopping background thread.");
            backgroundThread.quitSafely();
            try {
                logger.debug("Waiting for background thread to finish.");
                backgroundThread.join();
                logger.debug("Waiting for background thread to finish ... done.");
                backgroundThread = null;
                backgroundHandler = null;
            } catch (InterruptedException e) {
                logger.error("Error while waiting for background thread to finish.", e);
            }
        }
        logger.verbose("Camera2Component.stopBackgroundThread() ... done");
    }

    /**
     * Starts a watchdog thread and its {@link Handler}.
     */
    private void startWatchDogThread() {
        logger.verbose("Camera2Component.startWatchDogThread()");

        logger.debug("Starting watchdog thread.");
        watchDogThread = new HandlerThread("Camera Watchdog");
        watchDogThread.start();
        watchDogHandler = new Handler(watchDogThread.getLooper());
        logger.debug("Starting watchdog thread ... done");

        logger.verbose("Camera2Component.startWatchDogThread() ... done.");
    }

    /**
     * Stops the watchdog thread and its {@link Handler}.
     */
    private void stopWatchDogThread() {
        logger.verbose("Camera2Component.stopWatchDogThread()");
        if (watchDogThread != null) {
            logger.debug("Stopping watchdog thread.");
            watchDogThread.quitSafely();
            try {
                logger.debug("Waiting for watchdog thread to finish.");
                watchDogThread.join();
                logger.debug("Waiting for watchdog thread to finish ... done.");
                watchDogThread = null;
                watchDogHandler = null;
            } catch (InterruptedException e) {
                logger.error("Error while waiting for watchdog thread to finish.", e);
            }
        }
        logger.verbose("Camera2Component.stopWatchDogThread() ... done");
    }

    private void setupCameraOutputs(int width, int height) {
        logger.verbose("Camera2Component.setupCameraOutputs()");

        CameraManager cameraManager = (CameraManager) context.getSystemService(Context.CAMERA_SERVICE);
        try {
            logger.debug("Number of available cameras: " + cameraManager.getCameraIdList().length);
            cameraId = cameraManager.getCameraIdList()[0];
            logger.debug("Selected camera: " + cameraId);

            Integer hardwareLevel = cameraManager.getCameraCharacteristics(cameraId).get(CameraCharacteristics.INFO_SUPPORTED_HARDWARE_LEVEL);
            logger.info("Supported hardware level: " + hardwareLevel);

            StreamConfigurationMap streamConfigurationMap = cameraManager.getCameraCharacteristics(cameraId).get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP);
            Size[] jpegOutputSizes = streamConfigurationMap.getOutputSizes(ImageFormat.JPEG);

            StringBuffer sizes = new StringBuffer();
            for (Size size : jpegOutputSizes) {
                sizes.append('[');
                sizes.append(size);
                sizes.append(']');

                cameraResolutions.add(size);
            }
            logger.debug("Available camera sizes: " + sizes.toString());

            Size jpegOutputSize = jpegOutputSizes[jpegOutputSizes.length - 1];
            for (Size size : jpegOutputSizes) {
                if ((size.getWidth() == cameraResolution.getWidth()) && (size.getHeight() == cameraResolution.getHeight())) {
                    jpegOutputSize = size;
                    break;
                }
            }
            logger.debug("Selected camera size: " + jpegOutputSize);

            Point displaySize = new Point();
            context.getWindowManager().getDefaultDisplay().getSize(displaySize);
            Size largest = Collections.max(
                    Arrays.asList(streamConfigurationMap.getOutputSizes(ImageFormat.JPEG)),
                    new CompareSizesByArea());

            previewSize = chooseOptimalSize(streamConfigurationMap.getOutputSizes(SurfaceTexture.class), width, height, displaySize.x, displaySize.y, largest);

            StringBuffer ranges = new StringBuffer();
            maxFpsRange = null;
            Range<Integer> fpsRanges[] = cameraManager.getCameraCharacteristics(cameraId).get(CameraCharacteristics.CONTROL_AE_AVAILABLE_TARGET_FPS_RANGES);
            for (Range<Integer> fpsRange : fpsRanges) {
                if ((maxFpsRange == null) || (fpsRange.getUpper() > maxFpsRange.getUpper()) || (fpsRange.getLower() > maxFpsRange.getLower())) {
                    maxFpsRange = fpsRange;
                }
                ranges.append('[');
                ranges.append(fpsRange.getLower());
                ranges.append(':');
                ranges.append(fpsRange.getUpper());
                ranges.append(']');
            }
            logger.debug("Available camera FPS ranges: " + ranges.toString());

            logger.debug("Creating JPEG image reader.");
            jpegImageReader = ImageReader.newInstance(jpegOutputSize.getWidth(), jpegOutputSize.getHeight(), ImageFormat.JPEG, 2);
            jpegImageReader.setOnImageAvailableListener(this::onImageAvailable, backgroundHandler);
            logger.debug("Creating JPEG image reader ... done.");

            sensorOrientation = cameraManager.getCameraCharacteristics(cameraId).get(CameraCharacteristics.SENSOR_ORIENTATION);
            logger.debug("Camera sensor orientation: " + sensorOrientation);
        } catch (CameraAccessException e) {
            logger.error("Error setting up camera.", e);
        }

        logger.verbose("Camera2Component.setupCameraOutputs()");
    }

    private void onImageAvailable(ImageReader imageReader) {
        logger.verbose("Camera2Component.onImageAvailable()");

        watchDogTimer.set(System.currentTimeMillis());

//        int count = countDown.addAndGet(-1);
//        if (count ==0) {
//            try {
//                Thread.sleep(5000);
//            } catch (InterruptedException e) {
//                e.printStackTrace();
//            }
//
//            countDown.set(500);
//        } else {
//            logger.info("Count: " + count);
//        }

        try {
            Image image = imageReader.acquireLatestImage();
            if (image != null) {
                ByteBuffer originalBuffer = image.getPlanes()[0].getBuffer();

                boolean permit = false;
                try {
                    permit = imageBufferSemaphore.tryAcquire();
                    if ((permit) && (!frozen)) {
                        if (originalBuffer.remaining() > imageBuffer.capacity()) {
                            imageBuffer = ByteBuffer.allocate(originalBuffer.remaining());
                        }

                        imageBuffer.clear();
                        imageBuffer.put(originalBuffer);
                        imageBuffer.flip();
                    }
                } finally {
                    if (permit) {
                        imageBufferSemaphore.release();
                    }
                }

                image.close();
            }
        } catch (Exception e) {
            logger.error("Error in image loop.", e);
        }

        logger.verbose("Camera2Component.onImageAvailable() ... done.");
    }

    private void createCameraPreviewSession() {
        logger.verbose("Camera2Component.createCameraPreviewSession()");

        previewTexture.setDefaultBufferSize(textureView.getWidth(), textureView.getHeight());

        watchDogTimer.set(0);

        logger.debug("Create output surface.");
        Surface surface = new Surface(previewTexture);

        try {
            logger.debug("Create new capture session.");
            cameraDevice.createCaptureSession(Arrays.asList(surface, jpegImageReader.getSurface()), new CameraCaptureSession.StateCallback() {
                @Override
                public void onConfigured(@NonNull CameraCaptureSession session) {
                    logger.verbose("Camera2Component.StateCallback.onConfigured()");

                    if (cameraDevice == null) {
                        logger.debug("CameraDevice not configured.");
                        return;
                    }

                    try {
                        logger.debug("Storing capture session.");
                        Camera2Component.this.captureSession = session;

                        logger.debug("Creating new capture request.");
                        CaptureRequest.Builder captureRequestBuilder = cameraDevice
                                .createCaptureRequest(CameraDevice.TEMPLATE_PREVIEW);

                        logger.debug("Adding preview surface as target.");
                        captureRequestBuilder.addTarget(surface);

                        logger.debug("Adding JPEG reader as target.");
                        captureRequestBuilder.addTarget(jpegImageReader.getSurface());

                        logger.debug("Setting target FPS range to " + maxFpsRange);
                        captureRequestBuilder.set(CaptureRequest.CONTROL_AE_TARGET_FPS_RANGE, maxFpsRange);

                        int rotation = getJpegOrientation();
                        logger.debug("Setting JPEG orientation to " + rotation);
                        captureRequestBuilder.set(CaptureRequest.JPEG_ORIENTATION, rotation);

                        logger.debug("Setting JPEG quality to " + jpegQuality);
                        captureRequestBuilder.set(CaptureRequest.JPEG_QUALITY, jpegQuality);

                        logger.debug("Setting torch mode to " + flash);
                        captureRequestBuilder.set(CaptureRequest.CONTROL_AE_MODE, CaptureRequest.CONTROL_AE_MODE_ON);
                        captureRequestBuilder.set(CaptureRequest.FLASH_MODE, flash ? CaptureRequest.FLASH_MODE_TORCH : CaptureRequest.FLASH_MODE_OFF);

                        logger.debug("setting zoom level to " + zoomLevel);
                        Rect zoomRect = getZoomRect(zoomLevel);
                        if (zoomRect != null) {
                            logger.debug("Setting crop region to " + zoomRect);
                            captureRequestBuilder.set(CaptureRequest.SCALER_CROP_REGION, zoomRect);
                        }

                        logger.debug("Enabling video stabilization.");
                        captureRequestBuilder.set(CaptureRequest.CONTROL_VIDEO_STABILIZATION_MODE, CaptureRequest.CONTROL_VIDEO_STABILIZATION_MODE_ON);

                        CaptureRequest request = captureRequestBuilder.build();
                        logger.debug("Request created.");

                        logger.debug("Start repeating request.");
                        session.setRepeatingRequest(request, null, backgroundHandler);
                    } catch (CameraAccessException e) {
                        logger.error("Error configuring capture request.");
                    }
                    logger.verbose("Camera2Component.StateCallback.onConfigured() ... done.");
                }

                @Override
                public void onConfigureFailed(@NonNull CameraCaptureSession session) {
                    logger.verbose("Camera2Component.StateCallback.onConfigureFailed()");
                    logger.error("Failed to configure capture session.");
                    logger.verbose("Camera2Component.StateCallback.onConfigureFailed() ... done.");
                }
            }, backgroundHandler);
            logger.debug("Create new capture session ... done.");

        } catch (CameraAccessException e) {
            logger.error("Error creating preview session.", e);
        }

        logger.verbose("Camera2Component.createCameraPreviewSession() ... done.");
    }

    private void closeCameraPreviewSession() {
        logger.verbose("Camera2Component.closeCameraPreviewSession()");

        // check if we have a capture session active
        if (null != captureSession) {
            logger.debug("Closing capture session.");
            captureSession.close();
            captureSession = null;
            logger.debug("Closing capture session ... done.");
        }

        // check if the JPEG reader is opened
        if (null != jpegImageReader) {
            logger.debug("Closing JPEG reader.");
            jpegImageReader.close();
            jpegImageReader = null;
            logger.debug("Closing JPEG reader ... done.");
        }

        logger.verbose("Camera2Component.closeCameraPreviewSession() ... done.");
    }

    /**
     * {@link TextureView.SurfaceTextureListener} handles several lifecycle events on a {@link
     * TextureView}.
     */
    private final TextureView.SurfaceTextureListener surfaceTextureListener = new TextureView.SurfaceTextureListener() {

        @Override
        public void onSurfaceTextureAvailable(SurfaceTexture texture, int width, int height) {
            logger.verbose("Camera2Component.SurfaceTextureListener.onSurfaceTextureAvailable()");

            Camera2Component.this.previewTexture = texture;

            logger.debug("Opening camera.");
            openCamera(width, height);
            logger.debug("Opening camera ... done.");

            logger.verbose("Camera2Component.SurfaceTextureListener.onSurfaceTextureAvailable() ... done.");
        }

        @Override
        public void onSurfaceTextureSizeChanged(SurfaceTexture texture, int width, int height) {
            logger.verbose("Camera2Component.SurfaceTextureListener.onSurfaceTextureSizeChange()");
//            configureTransform(width, height);
        }

        @Override
        public boolean onSurfaceTextureDestroyed(SurfaceTexture texture) {
            logger.verbose("Camera2Component.SurfaceTextureListener.onSurfaceTextureDestroyed()");
            return true;
        }

        @Override
        public void onSurfaceTextureUpdated(SurfaceTexture texture) {
//            logger.verbose(this, "Camera2Component.SurfaceTextureListener.onSurfaceTextureUpdated()");
            // do nothing here
        }
    };

    /**
     * {@link CameraDevice.StateCallback} is called when {@link CameraDevice} changes its state.
     */
    private final CameraDevice.StateCallback stateCallback = new CameraDevice.StateCallback() {

        @Override
        public void onOpened(@NonNull CameraDevice cameraDevice) {
            // This method is called when the camera is opened.  We start camera preview here.
            logger.verbose("Camera2Component.SurfaceTextureListener.onOpened(CameraDevice=" + cameraDevice + ")");

            logger.debug("Releasing camera semaphore.");
            cameraOpenCloseSemaphore.release();

            Camera2Component.this.cameraDevice = cameraDevice;

            logger.debug("Create preview session.");
            createCameraPreviewSession();
            logger.debug("Create preview session ... done.");

            if (onCameraInitializedHandler != null) {
                onCameraInitializedHandler.run();
            }
            logger.verbose("Camera2Component.SurfaceTextureListener.onOpened() ... done.");
        }

        @Override
        public void onDisconnected(@NonNull CameraDevice cameraDevice) {
            logger.verbose("Camera2Component.SurfaceTextureListener.onDisconnected(CameraDevice=\" + cameraDevice +\")");

            logger.debug("Releasing camera semaphore.");
            cameraOpenCloseSemaphore.release();

            logger.debug("Closing camera device.");
            cameraDevice.close();
            Camera2Component.this.cameraDevice = null;
            logger.debug("Closing camera device ... done.");

            logger.verbose("Camera2Component.SurfaceTextureListener.onDisconnected() ... done.");
        }

        @Override
        public void onError(@NonNull CameraDevice cameraDevice, int error) {
            logger.verbose("Camera2Component.SurfaceTextureListener.onDisconnected(CameraDevice=\" + cameraDevice +\";error=" + error + ")");

            logger.error("Camera Error: " + error);

            logger.debug("Releasing camera semaphore.");
            cameraOpenCloseSemaphore.release();

            logger.debug("Closing camera device.");
            cameraDevice.close();
            Camera2Component.this.cameraDevice = null;
            logger.debug("Closing camera device ... done.");

            logger.verbose("Camera2Component.SurfaceTextureListener.onDisconnected() ... done.");
        }
    };

    private int getJpegOrientation() {
        SparseIntArray orientations = new SparseIntArray(4);
        orientations.append(Surface.ROTATION_0, 90);
        orientations.append(Surface.ROTATION_90, 0);
        orientations.append(Surface.ROTATION_180, 270);
        orientations.append(Surface.ROTATION_270, 180);

        int surfaceRotation = orientations.get(deviceRotation);
        return (surfaceRotation + sensorOrientation + 270) % 360;
    }

    public List<Size> getCameraResolutions() {
        logger.verbose("Camera2Component.getCameraResolutions()");
        return cameraResolutions;
    }

    public Size getCameraResolution() {
        return cameraResolution;
    }

    private void startCamera() {
        // When the screen is turned off and turned back on, the SurfaceTexture is already
        // available, and "onSurfaceTextureAvailable" will not be called. In that case, we can open
        // a camera and start preview from here (otherwise, we wait until the surface is ready in
        // the SurfaceTextureListener).
        if (textureView.isAvailable()) {
            logger.verbose("TextureView is available.");

            logger.debug("Opening camera.");
            openCamera(textureView.getWidth(), textureView.getHeight());
            logger.debug("Opening camera ... done.");
        } else {
            logger.debug("TextureView is not available.");
            textureView.setSurfaceTextureListener(surfaceTextureListener);
        }
    }

    private void restartCamera() {
        logger.verbose("Camera2Component.restartCamera()");

        logger.debug("Closing preview session.");
        closeCameraPreviewSession();
        logger.debug("Closing preview session ... done.");

        logger.debug("Create new camera outputs.");
        setupCameraOutputs(cameraWidth, cameraHeight);
//        configureTransform(cameraWidth, cameraHeight);
        logger.debug("Create new camera outputs ... done.");

        logger.debug("Create new preview session.");
        createCameraPreviewSession();
        logger.debug("Create new preview session ... done.");

        logger.verbose("Camera2Component.restartCamera() ... done.");
    }

    public void setCameraResolution(Size cameraResolution) {
        logger.verbose("Camera2Component.setCameraResolution(cameraResolution=" + cameraResolution + ")");

        logger.debug("Changing camera resolution.");
        this.cameraResolution = cameraResolution;
        restartCamera();
        logger.debug("Changing camera resolution ... done.");

        logger.verbose("Camera2Component.setCameraResolution() ... done.");
    }

    public void setJpegQuality(byte jpegQuality) {
        logger.verbose("Camera2Component.setJpegQuality(jpegQuality=" + jpegQuality + ")");

        logger.debug("Changing JPEG quality.");
        this.jpegQuality = jpegQuality;
        restartCamera();
        logger.debug("Changing JPEG quality ... done.");

        logger.verbose("Camera2Component.setJpegQuality() ... done.");
    }

    public void setDeviceRotation(int deviceRotation) {
        logger.verbose("Camera2Component.setDeviceRotation(deviceRoation=" + deviceRotation + ")");

        logger.debug("Changing device rotation.");
        this.deviceRotation = deviceRotation;
        restartCamera();
        logger.debug("Changing device rotation ... done.");

        logger.verbose("Camera2Component.setDeviceRotation() ... done.");
    }

    public byte getJpegQuality() {
        return jpegQuality;
    }

    /**
     * Compares two {@code Size}s based on their areas.
     */
    static class CompareSizesByArea implements Comparator<Size> {

        @Override
        public int compare(Size lhs, Size rhs) {
            // We cast here to ensure the multiplications won't overflow
            return Long.signum((long) lhs.getWidth() * lhs.getHeight() -
                    (long) rhs.getWidth() * rhs.getHeight());
        }

    }

    @Override
    public void setTorchMode(boolean on) {
        this.flash = on;

        restartCamera();
    }

    @Override
    public void setZoomLevel(float zoomLevel) {
        this.zoomLevel = zoomLevel;

        restartCamera();
    }

    @Nullable
    private Rect getZoomRect(float zoomLevel) {
        try {
            CameraManager cameraManager = (CameraManager) context.getSystemService(Context.CAMERA_SERVICE);
            CameraCharacteristics characteristics = cameraManager.getCameraCharacteristics(cameraId);

            float maxZoom = (characteristics.get(CameraCharacteristics.SCALER_AVAILABLE_MAX_DIGITAL_ZOOM)) * 10;
            Rect activeRect = characteristics.get(CameraCharacteristics.SENSOR_INFO_ACTIVE_ARRAY_SIZE);

            if ((zoomLevel <= maxZoom) && (zoomLevel > 1)) {
                int minW = (int) (activeRect.width() / maxZoom);
                int minH = (int) (activeRect.height() / maxZoom);
                int difW = activeRect.width() - minW;
                int difH = activeRect.height() - minH;
                int cropW = difW / 100 * (int) zoomLevel;
                int cropH = difH / 100 * (int) zoomLevel;
                cropW -= cropW & 3;
                cropH -= cropH & 3;
                return new Rect(cropW, cropH, activeRect.width() - cropW, activeRect.height() - cropH);
            } else if (zoomLevel == 0) {
                return new Rect(0, 0, activeRect.width(), activeRect.height());
            }

            return null;
        } catch (Exception e) {
            logger.error("Error calculating zoom rectangle.", e);
            return null;
        }
    }

    @Override
    public float getMaxZoom() {
        try {
            CameraManager cameraManager = (CameraManager) context.getSystemService(Context.CAMERA_SERVICE);
            return (cameraManager.getCameraCharacteristics(cameraId).get(CameraCharacteristics.SCALER_AVAILABLE_MAX_DIGITAL_ZOOM)) * 10;
        } catch (Exception e) {
            logger.error("Error accessing camera.", e);
            return -1;
        }
    }

    @Override
    public void setFreeze(boolean freeze) {
        this.frozen = freeze;
    }

    @Override
    public boolean getFreeze() {
        return frozen;
    }
}
