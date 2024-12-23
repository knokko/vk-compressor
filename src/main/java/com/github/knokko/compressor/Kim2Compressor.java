package com.github.knokko.compressor;

import java.awt.*;
import java.awt.image.BufferedImage;
import java.nio.IntBuffer;

import static com.github.knokko.boiler.utilities.ColorPacker.*;
import static java.lang.Math.*;

/**
 * This class has static methods to compress images to my 'kim2' image format, specialized for images where basically
 * every pixel has the same color, but with a different intensity.
 */
public class Kim2Compressor {

	/**
	 * The maximum supported width and height, in pixels
	 */
	public static final int MAX_SIZE = Short.MAX_VALUE;

	private static int validateSize(int width, int height, int bitsPerPixel) {
		if (width < 0 || width > MAX_SIZE || height < 0 || height > MAX_SIZE) {
			throw new IllegalArgumentException("Invalid size: (" + width + ", " + height + ")");
		}
		if (bitsPerPixel == 1) return 0;
		if (bitsPerPixel == 2) return 1;
		if (bitsPerPixel == 4) return 2;
		if (bitsPerPixel == 8) return 3;
		throw new IllegalArgumentException("bits per pixels must be 1, 2, 4, or 8");
	}

	/**
	 * Predicts the capacity of an {@link IntBuffer} needed to store a kim2 image with the given size
	 * @param width The width of the image, in pixels
	 * @param height The height of the image, in pixels
	 * @param bitsPerPixel The number of bits that should be used to store 1 pixel, must be either 1, 2, 4, or 8
	 * @throws IllegalArgumentException When {@code bitsPerPixel} is invalid,
	 * or when either width or height is negative, or larger than {@link #MAX_SIZE}
	 */
	public static int predictIntSize(int width, int height, int bitsPerPixel) throws IllegalArgumentException {
		validateSize(width, height, bitsPerPixel);
		int dataBits = width * height * bitsPerPixel;
		int dataInts = dataBits / 32;
		if (dataBits % 32 != 0) dataInts += 1;
		return 2 + dataInts;
	}

	/**
	 * Predicts the capacity of a {@link java.nio.ByteBuffer} needed to store a kim2 image with the given size
	 * @param width The width of the image, in pixels
	 * @param height The height of the image, in pixels
	 * @param bitsPerPixel The number of bits that should be used to store 1 pixel, must be either 1, 2, 4, or 8
	 * @throws IllegalArgumentException When {@code bitsPerPixel} is invalid,
	 * or when either width or height is negative, or larger than {@link #MAX_SIZE}
	 */
	public static int predictByteSize(int width, int height, int bitsPerPixel) throws IllegalArgumentException {
		return 4 * predictIntSize(width, height, bitsPerPixel);
	}

	/**
	 * Compresses the given image, and puts the compressed data in {@code output}. The {@link IntBuffer#remaining()} of
	 * {@code output} must be at least {@code predictIntSize(input.getWidth(), input.getHeight(), bitsPerPixel}
	 * @param input The image to be compressed
	 * @param output The buffer to which the compressed data should be written
	 * @param bitsPerPixel The number of bits that should be used to store 1 pixel, must be either 1, 2, 4, or 8
	 * @throws IllegalArgumentException When {@code bitsPerPixel} is invalid, or when either width or height is larger
	 * than {@link #MAX_SIZE}
	 */
	public static void compress(BufferedImage input, IntBuffer output, int bitsPerPixel) throws IllegalArgumentException {
		IntBuffer inputBuffer = IntBuffer.allocate(4 * input.getWidth() * input.getHeight());
		for (int y = 0; y < input.getHeight(); y++) {
			for (int x = 0; x < input.getWidth(); x++) {
				Color color = new Color(input.getRGB(x, y), true);
				inputBuffer.put(rgba(color.getRed(), color.getGreen(), color.getBlue(), color.getAlpha()));
			}
		}
		inputBuffer.flip();
		compress(inputBuffer, input.getWidth(), input.getHeight(), output, bitsPerPixel);
	}

	private static int clamp(long component) {
		return (int) max(0, min(255, component));
	}

	/**
	 * Compresses the image stored in {@code input}, and puts the compressed data in {@code output}. The color of the
	 * input pixel at {@code (x, y)} must be stored at {@code input[x + y * width]}, and packed using
	 * {@link com.github.knokko.boiler.utilities.ColorPacker}.
	 * The {@link IntBuffer#remaining()} of {@code output} must be at least
	 * {@code predictIntSize(width, height, bitsPerPixel}.
	 * @param input A buffer containing the image data to be compressed
	 * @param width The width of the image, in pixels
	 * @param height The height of the image, in pixels
	 * @param output The buffer to which the compressed data should be written
	 * @param bitsPerPixel The number of bits that should be used to store 1 pixel, must be either 1, 2, 4, or 8
	 * @throws IllegalArgumentException When {@code bitsPerPixel} is invalid, or when either width or height is larger
	 * than {@link #MAX_SIZE}
	 */
	public static void compress(
			IntBuffer input, int width, int height,
			IntBuffer output, int bitsPerPixel
	) {
		int storedBitsPerPixel = validateSize(width, height, bitsPerPixel);
		int numSteps = (1 << bitsPerPixel) - 1;
		output.put(width | (height << 15) | (storedBitsPerPixel << 30));

		long totalAlpha = 0;
		long totalRed = 0;
		long totalGreen = 0;
		long totalBlue = 0;

		int oldPosition = input.position();
		for (int counter = 0; counter < width * height; counter++) {
			int raw = input.get();
			long alpha = unsigned(alpha(raw));
			long weight = alpha * alpha;
			totalAlpha += weight;
			totalRed += weight * unsigned(red(raw));
			totalGreen += weight * unsigned(green(raw));
			totalBlue += weight * unsigned(blue(raw));
		}
		input.position(oldPosition);

		int color = 0;
		if (totalAlpha > 0) {
			int red = clamp(totalRed / totalAlpha);
			int green = clamp(totalGreen / totalAlpha);
			int blue = clamp(totalBlue / totalAlpha);
			color = rgb(red, green, blue);
		}
		output.put(color);

		int bitOffset = 0;
		int nextValue = 0;
		int stepSize = 255 / numSteps;
		for (int counter = 0; counter < width * height; counter++) {
			int rawColor = input.get();
			int storedAlpha = (unsigned(alpha(rawColor)) + stepSize / 2) / stepSize;
			nextValue |= storedAlpha << bitOffset;
			bitOffset += bitsPerPixel;

			if (bitOffset == 32) {
				output.put(nextValue);
				nextValue = 0;
				bitOffset = 0;
			}
		}

		if (bitOffset != 0) output.put(nextValue);
	}
}
