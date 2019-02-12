package de.kutschertec.cameratest;

import android.graphics.ImageFormat;
import android.graphics.Rect;
import android.graphics.YuvImage;
import android.media.Image;

import java.io.ByteArrayOutputStream;
import java.nio.ByteBuffer;

/**
 * Class containing static methods for image modification and conversion.
 */
public abstract class ImageUtils {
    private ImageUtils() {
        // hidden constructor
    }

    /**
     * Convert an {@link Image} into a byte array containing a JPEG image.
     * @param image the {@link Image} that should be converted
     * @param quality the quality of the JPEG when converting a YUV image
     * @param mirror mirror the image when converting a YUV image
     * @return a byte array containing the JPEG image
     */
    public static byte[] imageToByteArray(Image image, int quality, boolean mirror) {
        byte[] data = null;
        if (image.getFormat() == ImageFormat.JPEG) {
            Image.Plane[] planes = image.getPlanes();
            ByteBuffer buffer = planes[0].getBuffer();
            data = new byte[buffer.capacity()];
            buffer.get(data);
            return data;
        } else if (image.getFormat() == ImageFormat.YUV_420_888) {
            data = convertNv21ToJpeg(convertYuv420888ToNv21(image, mirror), image.getWidth(), image.getHeight(), quality);
        }
        return data;
    }

    private static byte[] convertYuv420888ToNv21(Image image, boolean mirror) {
        byte[] nv21;
        ByteBuffer yBuffer = image.getPlanes()[0].getBuffer();
        ByteBuffer uBuffer = image.getPlanes()[1].getBuffer();
        ByteBuffer vBuffer = image.getPlanes()[2].getBuffer();

        int ySize = yBuffer.remaining();
        int uSize = uBuffer.remaining();
        int vSize = vBuffer.remaining();

        nv21 = new byte[ySize + uSize + vSize];

        //U and V are swapped
        yBuffer.get(nv21, 0, ySize);
        vBuffer.get(nv21, ySize, vSize);
        uBuffer.get(nv21, ySize + vSize, uSize);

        if (mirror) {
            reverseArray(nv21, 0, ySize);
            reverseArray(nv21, ySize, vSize);
            reverseArray(nv21, ySize+vSize, uSize);
        }

        return nv21;
    }

    private static void reverseArray(byte[] data, int start, int length) {
        byte swap;
        for (int i = 0; i < length / 2; i++) {
            swap = data[i + start];
            data[i + start] = data[length - i + start - 1];
            data[length - i + start - 1] = swap;
        }
    }

    private static byte[] convertNv21ToJpeg(byte[] nv21, int width, int height, int quality) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        YuvImage yuv = new YuvImage(nv21, ImageFormat.NV21, width, height, null);
        yuv.compressToJpeg(new Rect(0, 0, width, height), quality, out);
        return out.toByteArray();
    }
}
