package com.github.knokko.compressor;

import java.nio.ByteBuffer;
import java.util.*;

import static com.github.knokko.boiler.utilities.BoilerMath.nextMultipleOf;
import static com.github.knokko.boiler.utilities.ColorPacker.*;
import static com.github.knokko.compressor.BitWriter.computeBitsPerPixel;
import static com.github.knokko.compressor.BitWriter.pack;
import static java.lang.Math.max;

public class Kim1Compressor {

	public final int width, height;
	private final int numChannels;
	private final Map<Integer, Integer> colorTable = new HashMap<>();
	private final int[] pixelBuffer;

	public Kim1Compressor(ByteBuffer pixelBuffer, int width, int height, int numDataChannels) {
		if (width >= 1 << 13 || height >= 1 << 13) throw new IllegalArgumentException("Too large");
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
			byte green = numDataChannels >= 2 ? pixelBuffer.get() : -1;
			byte blue = numDataChannels >= 3 ? pixelBuffer.get() : -1;
			byte alpha = numDataChannels >= 4 ? pixelBuffer.get() : -1;
			int color = rgba(red, green, blue, alpha);
			this.pixelBuffer[bufferIndex] = color;
			bufferIndex += 1;
			if (!colorTable.containsKey(color)) colorTable.put(color, colorTable.size());
			if (numChannels < 4 && alpha != -1) numChannels = 4;
			if (numChannels < 3 && blue != -1) numChannels = 3;
			if (numChannels < 2 && green != -1) numChannels = 2;
		}

		this.numChannels = numChannels;
		if (colorTable.size() >= 1 << 14) {
			throw new IllegalArgumentException("Too many distinct colors: " + colorTable.size());
		}
	}

	private int getHeaderByteSize() {
		return 8;
	}

	private int getColorTableByteSize() {
		// Since GLSL doesn't really have byte support, I will store it in an int array, so 4-byte alignment is nice
		return nextMultipleOf(colorTable.size() * numChannels, 4);
	}

	private void checkBlockSizes(int blockWidth, int blockHeight) {
		if (blockWidth < 1 || blockHeight < 1) throw new IllegalArgumentException("Block sizes must be positive");
		if (blockWidth > 255 || blockHeight > 255) throw new IllegalArgumentException("Block sizes must be < 256");
		if (width % blockWidth != 0) throw new IllegalArgumentException("Width must be an integer multiple of blockWidth");
		if (height % blockHeight != 0) throw new IllegalArgumentException("Height must be an integer multiple of blockHeight");
	}

	private int maxUniqueColorsPerBlock(int blockWidth, int blockHeight) {
		int maxUniqueColorsPerBlock = 0;
		int numBlocksX = width / blockWidth;
		int numBlocksY = height / blockHeight;

		Set<Integer> distinctColors = new HashSet<>();
		for (int blockY = 0; blockY < numBlocksY; blockY++) {
			for (int blockX = 0; blockX < numBlocksX; blockX++) {
				for (int localY = 0; localY < blockHeight; localY++) {
					int realY = blockY * blockHeight + localY;
					for (int localX = 0; localX < blockWidth; localX++) {
						int realX = blockX * blockWidth + localX;
						distinctColors.add(pixelBuffer[realX + width * realY]);
					}
				}
				maxUniqueColorsPerBlock = max(maxUniqueColorsPerBlock, distinctColors.size());
				distinctColors.clear();
			}
		}

		if (maxUniqueColorsPerBlock >= 64) {
			throw new IllegalArgumentException("Too many unique colors per block: " + maxUniqueColorsPerBlock);
		}
		return maxUniqueColorsPerBlock;
	}

	public int predictByteSize(int blockWidth, int blockHeight) {
		checkBlockSizes(blockWidth, blockHeight);

		int numBlocksX = width / blockWidth;
		int numBlocksY = height / blockHeight;

		int maxUniqueColorsPerBlock = maxUniqueColorsPerBlock(blockWidth, blockHeight);
		int numBlocks = numBlocksX * numBlocksY;
		int blockHeaderSize = maxUniqueColorsPerBlock * computeBitsPerPixel(colorTable.size());
		int blockDataSize = blockWidth * blockHeight * computeBitsPerPixel(maxUniqueColorsPerBlock);

		return getHeaderByteSize() + getColorTableByteSize() + nextMultipleOf(numBlocks * (blockHeaderSize + blockDataSize), 32) / 4;
	}

	public void compress(ByteBuffer destination, int blockWidth, int blockHeight) {
		checkBlockSizes(blockWidth, blockHeight);

		int numBlocksX = width / blockWidth;
		int numBlocksY = height / blockHeight;
		int maxUniqueColorsPerBlock = maxUniqueColorsPerBlock(blockWidth, blockHeight);
		int bitsPerPixel = computeBitsPerPixel(colorTable.size());
		int localBitsPerPixel = computeBitsPerPixel(maxUniqueColorsPerBlock);

		// Header: 2 ints = 8 bytes
		destination.putInt(pack(width, 0) | pack(height, 13) | pack(maxUniqueColorsPerBlock, 26));
		destination.putInt(pack(blockWidth, 0) | pack(blockHeight, 8) | pack(colorTable.size(), 16) | pack(numChannels - 1, 30));

		// Color table: numChannels * numColors, rounded up to a multiple of 4
		var colorEntries = new ArrayList<>(colorTable.entrySet());
		colorEntries.sort(Comparator.comparingInt(Map.Entry::getValue));
		int oldPosition = destination.position();
		for (var entry : colorEntries) {
			int color = entry.getKey();
			destination.put(red(color));
			if (numChannels >= 2) destination.put(green(color));
			if (numChannels >= 3) destination.put(blue(color));
			if (numChannels >= 4) destination.put(alpha(color));
		}
		while ((destination.position() - oldPosition) % 4 != 0) destination.put((byte) 0);

		// Color blocks
		var bitWriter = new BitWriter(destination);
		Map<Integer, Integer> localColorMap = new HashMap<>();
		for (int blockY = 0; blockY < numBlocksY; blockY++) {
			for (int blockX = 0; blockX < numBlocksX; blockX++) {
				for (int localY = 0; localY < blockHeight; localY++) {
					int realY = blockY * blockHeight + localY;
					for (int localX = 0; localX < blockWidth; localX++) {
						int realX = blockX * blockWidth + localX;
						int color = pixelBuffer[realX + width * realY];
						int globalIndex = colorTable.get(color);
						if (!localColorMap.containsKey(globalIndex)) localColorMap.put(globalIndex, localColorMap.size());
					}
				}

				// Block header
				var localColorEntries = new ArrayList<>(localColorMap.entrySet());
				localColorEntries.sort(Comparator.comparingInt(Map.Entry::getValue));
				for (var entry : localColorEntries) {
					int globalIndex = entry.getKey();
					bitWriter.write(globalIndex, bitsPerPixel);
				}
				for (int counter = localColorMap.size(); counter < maxUniqueColorsPerBlock; counter++) {
					bitWriter.write(0, bitsPerPixel);
				}

				for (int localY = 0; localY < blockHeight; localY++) {
					int realY = blockY * blockHeight + localY;
					for (int localX = 0; localX < blockWidth; localX++) {
						int realX = blockX * blockWidth + localX;
						int color = pixelBuffer[realX + width * realY];
						int globalIndex = colorTable.get(color);
						int localIndex = localColorMap.get(globalIndex);
						bitWriter.write(localIndex, localBitsPerPixel);
					}
				}
				localColorMap.clear();
			}
		}

		bitWriter.flush();
	}
}
