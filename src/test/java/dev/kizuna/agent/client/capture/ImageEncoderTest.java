package dev.kizuna.agent.client.capture;

import org.junit.jupiter.api.Test;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ImageEncoderTest {
    private static final int RED = 0xFFFF0000;
    private static final int BLUE = 0xFF0000FF;

    @Test
    void shrinkingAveragesTheSourcePixelsUnderEachTargetPixel() {
        // 4×2 的图缩到 2×1：左半全红，右半一半红一半蓝，所以右边那个像素是红蓝各半。
        int[] source = {RED, RED, RED, BLUE, RED, RED, BLUE, RED};
        assertArrayEquals(new int[] {RED, 0xFF7F007F}, ImageEncoder.shrink(source, 4, 2, 2, 1));
    }

    @Test
    void wideImagesAreShrunkToTheLimitAndStayReadableAsJpeg() throws Exception {
        // 左红右蓝的 800×450：压成不超过 320 宽的 JPEG，比例不变，读回来左边还是红、右边还是蓝。
        int[] pixels = new int[800 * 450];
        for (int index = 0; index < pixels.length; index++) {
            pixels[index] = index % 800 < 400 ? RED : BLUE;
        }
        ImageEncoder.Encoded encoded = ImageEncoder.encode(pixels, 800, 450, 320);
        assertEquals(320, encoded.width());
        assertEquals(180, encoded.height());
        assertEquals("image/jpeg", encoded.mime());
        assertEquals("jpg", encoded.extension());
        BufferedImage image = ImageIO.read(new ByteArrayInputStream(encoded.bytes()));
        assertEquals(320, image.getWidth());
        assertEquals(180, image.getHeight());
        assertReddish(image.getRGB(40, 90));
        assertBluish(image.getRGB(280, 90));
    }

    @Test
    void narrowImagesAreNotEnlarged() {
        ImageEncoder.Encoded encoded = ImageEncoder.encode(new int[] {RED, BLUE, BLUE, RED}, 2, 2, 960);
        assertEquals(2, encoded.width());
        assertEquals(2, encoded.height());
    }

    @Test
    void pixelCountMustMatchTheSize() {
        assertThrows(IllegalArgumentException.class, () -> ImageEncoder.encode(new int[5], 2, 2, 960));
    }

    private static void assertReddish(int pixel) {
        assertTrue((pixel >> 16 & 0xFF) > 200 && (pixel & 0xFF) < 60, Integer.toHexString(pixel));
    }

    private static void assertBluish(int pixel) {
        assertTrue((pixel & 0xFF) > 200 && (pixel >> 16 & 0xFF) < 60, Integer.toHexString(pixel));
    }
}
