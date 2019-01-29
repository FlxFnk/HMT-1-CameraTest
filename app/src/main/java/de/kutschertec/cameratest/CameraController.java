package de.kutschertec.cameratest;

import android.arch.lifecycle.DefaultLifecycleObserver;
import android.support.annotation.NonNull;
import android.util.Size;

import java.nio.ByteBuffer;
import java.util.List;

public interface CameraController extends DefaultLifecycleObserver {
    /**
     * Returns a list of resolution {@link Size}s the camera supports.
     *
     * @return a list of resolution {@link Size}s the camera supports
     */
    List<Size> getCameraResolutions();

    /**
     * Returns the current resolution of the camera.
     *
     * @return the current resolution of the camera
     */
    Size getCameraResolution();

    /**
     * Sets the current camera resolution.
     *
     * @param cameraResolution the current camera resolution
     */
    void setCameraResolution(Size cameraResolution);

    /**
     * Returns the JPEG quality.
     *
     * @return the JPEG quality
     */
    byte getJpegQuality();

    /**
     * Sets the current JPEG quality.
     *
     * @param jpegQuality the current JPEG quality
     */
    void setJpegQuality(byte jpegQuality);

    /**
     * Sets the rotation of the device.
     *
     * @param deviceRotation the rotation of the device in degrees
     */
    void setDeviceRotation(int deviceRotation);

    /**
     * Returns a {@link ByteBuffer} that contains a copy of the current image buffer. The {@link
     * ByteBuffer} is flipped and ready for reading.
     *
     * @return a ByteBuffer that contains the current camera image
     */
    @NonNull
    ByteBuffer getImageBuffer();

    /**
     * Sets the {@link Runnable} that will be called when the camera has been initialized.
     *
     * @param onCameraInitializedHandler {@link Runnable} that will be called, when the camera has
     *                                    been initialized
     */
    void setOnCameraInitializedHandler(Runnable onCameraInitializedHandler);

    /**
     * Sets the torch/flash on the active camera.
     *
     * @param on {@code true} if the flash should be turned on
     */
    public void setTorchMode(boolean on);

    /**
     * Sets the zoom level.
     *
     * @param zoomLevel the zoom level
     */
    public void setZoomLevel(float zoomLevel);

    /**
     * Returns the maximum zoom level of the active camera.
     *
     * @return the maximum zoom level of the active camera
     */
    public float getMaxZoom();

    /**
     * Sets whether the current camera picture should be updated or not.
     *
     * @param freeze {@code true} if the current picture should not be updated.
     */
    public void setFreeze(boolean freeze);

    /**
     * Returns whether the current camera picture should be updated or not.
     *
     * @return {@code true} if the current picture should not be updated
     */
    public boolean getFreeze();

    /**
     * Sets the handler that is invoked, when the watchdog is triggered.
     *
     * @param handler the handler that is invoked, when the watchdog is triggered
     */
    public void setOnWatchDogHandler(Runnable handler);
}