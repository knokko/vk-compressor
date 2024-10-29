package com.github.knokko.compressor;

import java.nio.ByteBuffer;

import static com.github.knokko.boiler.utilities.ColorPacker.green;
import static com.github.knokko.boiler.utilities.ColorPacker.red;
import static com.github.knokko.compressor.BitWriter.*;

/**
 * This class can be used to recover the data of images compressed by <i>Kim1Compressor</i>.
 */
public class Kim1Decompressor {

	/**
	 * The width and height of the original image, in pixels
	 */
	public final int width, height;
	private final int numChannels, numColors;
	private final ByteBuffer compressedData;

	/**
	 * Constructs a <i>Kim1Decompressor</i> that can recover the original image from the given <i>compressedData</i>
	 * @param compressedData The data that was the result of compressing an image using <i>Kim1Compressor</i>
	 */
	public Kim1Decompressor(ByteBuffer compressedData) {
		this.compressedData = compressedData;
		int header = compressedData.getInt(0);

		this.width = unpack(header, 0, 10);
		this.height = unpack(header, 10, 10);
		this.numColors = unpack(header, 20, 10);
		this.numChannels = 1 + unpack(header, 30, 2);
	}

	/**
	 * Gets the color of the pixel at coordinates <i>(x, y)</i>, packed using the <i>ColorPacker</i> of vk-boiler.
	 * @param x The x-coordinate of the pixel
	 * @param y The y-coordinate of the pixel
	 * @return The packed color of the pixel
	 */
	public int getColor(int x, int y) {
		int bitsPerPixel = computeBitsPerPixel(numColors);
		int colorIndex = readInt(32 + 8 * numColors * numChannels + bitsPerPixel * (x + y * width), bitsPerPixel);

		int color = readInt(32 + colorIndex * 8 * numChannels, 8 * numChannels);
		if (numChannels < 2) color |= red(color) << 8;
		if (numChannels < 3) color |= green(color) << 16;
		if (numChannels < 4) color |= 255 << 24;
		return color;
	}

	private int readInt(int bitIndex, int bitLength) {
		int intIndex1 = bitIndex / 32;
		int bitIndex1 = bitIndex % 32;
		int value1 = compressedData.getInt(4 * intIndex1);
		int bitLength2 = bitIndex1 + bitLength - 32;
		if (bitLength2 > 0) {
			int bitLength1 = bitLength - bitLength2;
			int value2 = compressedData.getInt(4 * intIndex1 + 4);
			return unpack(value1, 32 - bitLength1, bitLength1) | pack(unpack(value2, 0, bitLength2), bitLength1);
		} else {
			return unpack(value1, bitIndex1, bitLength);
		}
	}
}
