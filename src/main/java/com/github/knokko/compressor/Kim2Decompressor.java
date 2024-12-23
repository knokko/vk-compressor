package com.github.knokko.compressor;

import java.awt.*;
import java.awt.image.BufferedImage;
import java.nio.IntBuffer;

import static com.github.knokko.boiler.utilities.ColorPacker.*;

/**
 * This class has a CPU implementation to decode compressed 'kim2' images. You should normally decompress or sample
 * such images on the GPU, but this implementation can be useful for testing (and can be used as reference for the
 * GPU implementation).
 */
public class Kim2Decompressor {

	/**
	 * Extracts the width from the header (first int) of a compressed image
	 */
	public static int getWidth(int header) {
		return header & Short.MAX_VALUE;
	}

	/**
	 * Extracts the height from the header (first int) of a compressed image
	 */
	public static int getHeight(int header) {
		return (header >> 15) & Short.MAX_VALUE;
	}

	/**
	 * Extracts the number of bits per pixel from the header (first int) of a compressed image
	 */
	public static int getBitsPerPixel(int header) {
		int raw = (header >> 30) & 3;
		return switch (raw) {
			case 0 -> 1;
			case 1 -> 2;
			case 2 -> 4;
			case 3 -> 8;
			default -> throw new Error("This should be unreachable: raw is " + raw);
		};
	}

	/**
	 * Decompresses a kim2 image, and returns the recovered image as {@link BufferedImage}
	 * @param compressed The compressed kim2 image data
	 */
	public static BufferedImage decompress(IntBuffer compressed) {
		int width = getWidth(compressed.get(0));
		int height = getHeight(compressed.get(0));
		IntBuffer rawOutput = IntBuffer.allocate(width * height);
		decompress(compressed, rawOutput);
		rawOutput.flip();

		BufferedImage output = new BufferedImage(width, height, BufferedImage.TYPE_INT_ARGB);
		for (int y = 0; y < height; y++) {
			for (int x = 0; x < width;x ++) {
				int rawColor = rawOutput.get();
				Color color = new Color(
						unsigned(red(rawColor)), unsigned(green(rawColor)),
						unsigned(blue(rawColor)), unsigned(alpha(rawColor))
				);
				output.setRGB(x, y, color.getRGB());
			}
		}

		return output;
	}

	/**
	 * Decompresses a kim2 image, and stores the recovered image in {@code output}: it will write {@code width * height}
	 * ints to {@code output}, where the {@code i}'th int stores the color of the pixel at
	 * {@code (x = i % width, y = i / width)}, packed using {@link com.github.knokko.boiler.utilities.ColorPacker}
	 * @param compressed The compressed kim2 image data
	 * @param output The buffer to which the recovered image data should be written
	 */
	public static void decompress(IntBuffer compressed, IntBuffer output) {
		int header = compressed.get();
		int width = getWidth(header);
		int height = getHeight(header);
		int bitsPerPixel = getBitsPerPixel(header);

		int color = compressed.get() & rgba(255, 255, 255, 0);

		int bitOffset = 32;
		int nextValue = 0;
		int bitMask = (1 << bitsPerPixel) - 1;
		int stepSize = 255 / bitMask;
		for (int counter = 0; counter < width * height; counter++) {
			if (bitOffset == 32) {
				bitOffset = 0;
				nextValue = compressed.get();
			}

			int storedAlpha = (nextValue >> bitOffset) & bitMask;
			int actualAlpha = stepSize * storedAlpha;
			output.put(color | (actualAlpha << 24));

			bitOffset += bitsPerPixel;
		}
	}
}
