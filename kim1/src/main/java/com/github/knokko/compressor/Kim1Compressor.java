package com.github.knokko.compressor;

import java.nio.ByteBuffer;
import java.util.*;

import static com.github.knokko.boiler.utilities.BoilerMath.nextMultipleOf;
import static com.github.knokko.boiler.utilities.ColorPacker.*;
import static com.github.knokko.compressor.BitWriter.computeBitsPerPixel;
import static com.github.knokko.compressor.BitWriter.pack;

/**
 * This class can be used to compress images to my kim1 format.
 */
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

	/**
	 * Constructs a new <i>Kim1Compressor</i> capable of compressing the image stored in <i>pixelBuffer</i>.
	 * The <i>pixelBuffer</i> must have at least <b>width * height * numDataChannels</b> <i>remaining()</i>. When
	 * <ul>
	 *     <li><i>numDataChannels</i> is 4, all pixels are stored in RGBA format</li>
	 *     <li><i>numDataChannels</i> is 3, all pixels are stored in RGB format, and are assumed to be opaque</li>
	 *     <li>
	 *         <i>numDataChannels</i> is 2, all pixels are stored in RG format, and are assumed to be opaque. The
	 *         B component of each pixel is assumed to be identical to the G component.
	 *     </li>
	 *     <li>
	 *         <i>numDataChannels</i> is 1, the image is assumed to be greyscale,
	 *         and all pixels are assumed to be opaque
	 *     </li>
	 * </ul>
	 * @param pixelBuffer The buffer that contains all the image data
	 * @param width The width of the image, in pixels
	 * @param height The height of the image, in pixels
	 * @param numDataChannels The number of bytes per pixel
	 * @throws Kim1CompressionException When the width, height, or number of distinct colors, is greater than or equal
	 * to 1024.
	 */
	public Kim1Compressor(ByteBuffer pixelBuffer, int width, int height, int numDataChannels) throws Kim1CompressionException {
		if (width >= 1024) throw new Kim1CompressionException("The width " + width + " must be smaller than 1024");
		if (height >= 1024) throw new Kim1CompressionException("The height " + height + " must be smaller than 1024");
		if (width < 1 || height < 1) throw new IllegalArgumentException("Both width and height must be positive");
		if (numDataChannels > 4) throw new IllegalArgumentException("Too many data channels, at most 4 are supported");
		if (numDataChannels < 1) throw new IllegalArgumentException("Number of data channels must be positive");
		int expectedSize = numDataChannels * width * height;
		if (expectedSize > pixelBuffer.remaining()) {
			throw new IllegalArgumentException("Expected imageData to have a length of at least " + expectedSize +
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
			throw new Kim1CompressionException(
					"Too many distinct colors: " + colorTable.size() + "; at most 1023 are allowed"
			);
		}

		int dataBitSize = 8 * colorTable.size() * numChannels + computeBitsPerPixel(colorTable.size()) * width * height;
		this.intSize = 1 + nextMultipleOf(dataBitSize, 32) / 32;
	}

	/**
	 * Compresses the source image, and stores the result in <i>destination</i>. Note:
	 * <ul>
	 *     <li><i>destination</i> must have at least <b>this.intSize * 4</b> bytes <i>remaining()</i></li>
	 *     <li>this method will increase the <i>position()</i> of <i>destination</i></li>
	 * </ul>
	 */
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
		while (destination.position() % 4 != 0) destination.put((byte) 0);
	}
}
