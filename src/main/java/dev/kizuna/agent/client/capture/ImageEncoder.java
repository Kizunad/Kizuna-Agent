package dev.kizuna.agent.client.capture;

import javax.imageio.IIOImage;
import javax.imageio.ImageIO;
import javax.imageio.ImageWriteParam;
import javax.imageio.ImageWriter;
import javax.imageio.stream.MemoryCacheImageOutputStream;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.util.Iterator;

/** 把截下来的像素缩小、压成 JPEG：发给模型的图小一些，请求快，也省 token。 */
public final class ImageEncoder {
    /**
     * 压好的图。
     *
     * @param mime image/jpeg；这台 Java 没有 JPEG 编码器时是 image/png
     */
    public record Encoded(byte[] bytes, int width, int height, String mime) {
        /** 文件扩展名，不带点。 */
        public String extension() {
            return mime.equals("image/jpeg") ? "jpg" : "png";
        }
    }

    private static final float QUALITY = 0.85F;
    private static final int OPAQUE = 0xFF000000;

    private ImageEncoder() {
        // 只有静态方法。
    }

    /**
     * @param argb 一行行从上到下的像素，每个是 0xAARRGGBB（透明度不看）
     * @param maxWidth 最宽多少像素；原图更窄就不放大
     */
    public static Encoded encode(int[] argb, int width, int height, int maxWidth) {
        if (width <= 0 || height <= 0 || argb.length != width * height) {
            throw new IllegalArgumentException("像素个数 " + argb.length + " 和尺寸 " + width + "×" + height + " 对不上");
        }
        int outWidth = Math.min(width, maxWidth);
        int outHeight = Math.max(1, Math.round((float) height * outWidth / width));
        int[] pixels = outWidth == width ? argb : shrink(argb, width, height, outWidth, outHeight);
        var image = new BufferedImage(outWidth, outHeight, BufferedImage.TYPE_INT_RGB);
        image.setRGB(0, 0, outWidth, outHeight, pixels, 0, outWidth);
        var bytes = new ByteArrayOutputStream();
        Iterator<ImageWriter> writers = ImageIO.getImageWritersByFormatName("jpeg");
        try {
            if (!writers.hasNext()) {
                ImageIO.write(image, "png", bytes);
                return new Encoded(bytes.toByteArray(), outWidth, outHeight, "image/png");
            }
            ImageWriter writer = writers.next();
            try (var output = new MemoryCacheImageOutputStream(bytes)) {
                ImageWriteParam settings = writer.getDefaultWriteParam();
                settings.setCompressionMode(ImageWriteParam.MODE_EXPLICIT);
                settings.setCompressionQuality(QUALITY);
                writer.setOutput(output);
                writer.write(null, new IIOImage(image, null, null), settings);
            } finally {
                writer.dispose();
            }
        } catch (IOException failed) {
            throw new UncheckedIOException(failed);
        }
        return new Encoded(bytes.toByteArray(), outWidth, outHeight, "image/jpeg");
    }

    /** 缩小：目标的每个像素取它盖住的那一块原像素的平均色。 */
    static int[] shrink(int[] source, int width, int height, int outWidth, int outHeight) {
        int[] result = new int[outWidth * outHeight];
        for (int y = 0; y < outHeight; y++) {
            int top = (int) ((long) y * height / outHeight);
            int bottom = Math.max(top + 1, (int) ((long) (y + 1) * height / outHeight));
            for (int x = 0; x < outWidth; x++) {
                int left = (int) ((long) x * width / outWidth);
                int right = Math.max(left + 1, (int) ((long) (x + 1) * width / outWidth));
                long red = 0;
                long green = 0;
                long blue = 0;
                for (int row = top; row < bottom; row++) {
                    for (int column = left; column < right; column++) {
                        int pixel = source[row * width + column];
                        red += pixel >> 16 & 0xFF;
                        green += pixel >> 8 & 0xFF;
                        blue += pixel & 0xFF;
                    }
                }
                int count = (bottom - top) * (right - left);
                result[y * outWidth + x] = OPAQUE | (int) (red / count) << 16 | (int) (green / count) << 8 | (int) (blue / count);
            }
        }
        return result;
    }
}
