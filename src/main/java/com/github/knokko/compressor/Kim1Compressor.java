package com.github.knokko.compressor;

import java.nio.ByteBuffer;
import java.util.*;

import static com.github.knokko.boiler.utilities.BoilerMath.nextMultipleOf;
import static com.github.knokko.boiler.utilities.ColorPacker.*;
import static com.github.knokko.compressor.BitWriter.computeBitsPerPixel;
import static com.github.knokko.compressor.BitWriter.pack;

public class Kim1Compressor {

	/**
	 * The dimensions of the compressed image, in pixels
	 */
	public final int width, height;
	/**
	 * The size of the compressed image, in <b>int</b>s (4 <b>byte</b>s)
	 */
	public final int intSize;
	private final int numChannels;
	private final Map<Integer, Integer> colorTable = new HashMap<>();
	private final int[] pixelBuffer;

	public Kim1Compressor(ByteBuffer pixelBuffer, int width, int height, int numDataChannels) {
		if (width >= 1024 || height >= 1024) throw new IllegalArgumentException("Too large");
		if (numDataChannels > 4) throw new IllegalArgumentException("Too many data channels");
		if (numDataChannels < 1) throw new IllegalArgumentException("Number of data channels must be positive");
		int expectedSize = 4 * width * height;
		if (expectedSize != pixelBuffer.remaining()) {
			throw new IllegalArgumentException("Expected imageData to have a length of " + expectedSize +
					", but got " + pixelBuffer.remaining());
		}
		this.width = width;
		this.height = height;

		int numChannels = 1;
		this.pixelBuffer = new int[pixelBuffer.remaining() / numDataChannels];
		int bufferIndex = 0;

		while (pixelBuffer.hasRemaining()) {
			byte red = pixelBuffer.get();
			byte green = numDataChannels >= 2 ? pixelBuffer.get() : red;
			byte blue = numDataChannels >= 3 ? pixelBuffer.get() : green;
			byte alpha = numDataChannels == 4 ? pixelBuffer.get() : -1;
			int color = rgba(red, green, blue, alpha);
			this.pixelBuffer[bufferIndex] = color;
			bufferIndex += 1;
			if (!colorTable.containsKey(color)) colorTable.put(color, colorTable.size());
			if (numChannels < 4 && alpha != -1) numChannels = 4;
			if (numChannels < 3 && blue != green) numChannels = 3;
			if (numChannels < 2 && green != red) numChannels = 2;
		}

		this.numChannels = numChannels;
		if (colorTable.size() >= 1024) {
			throw new IllegalArgumentException("Too many distinct colors: " + colorTable.size());
		}

		int dataBitSize = 8 * colorTable.size() * numChannels + computeBitsPerPixel(colorTable.size()) * width * height;
		this.intSize = 1 + nextMultipleOf(dataBitSize, 32) / 32;
	}

	public void compress(ByteBuffer destination) {
		int bitsPerPixel = computeBitsPerPixel(colorTable.size());

		// Header
		destination.putInt(
				pack(width, 0) | pack(height, 10) |
						pack(colorTable.size(), 20) | pack(numChannels - 1, 30)
		);

		// Color table
		var colorEntries = new ArrayList<>(colorTable.entrySet());
		colorEntries.sort(Comparator.comparingInt(Map.Entry::getValue));
		for (var entry : colorEntries) {
			int color = entry.getKey();
			destination.put(red(color));
			if (numChannels >= 2) destination.put(green(color));
			if (numChannels >= 3) destination.put(blue(color));
			if (numChannels >= 4) destination.put(alpha(color));
		}

		// Color indices
		var bitWriter = new BitWriter(destination);
		for (int y = 0; y < height; y++) {
			for (int x = 0; x < width; x++) {
				int color = pixelBuffer[x + width * y];
				int colorIndex = colorTable.get(color);
				bitWriter.write(colorIndex, bitsPerPixel);
			}
		}

		bitWriter.flush();
	}
}
