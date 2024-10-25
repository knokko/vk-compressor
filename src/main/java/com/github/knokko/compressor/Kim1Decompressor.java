package com.github.knokko.compressor;

import java.nio.ByteBuffer;

import static com.github.knokko.boiler.utilities.BoilerMath.nextMultipleOf;
import static com.github.knokko.compressor.BitWriter.*;

public class Kim1Decompressor {

	public final int width, height;
	private final int numChannels, blockWidth, blockHeight, numColors, numColorsPerBlock;
	private final ByteBuffer compressedData;

	public Kim1Decompressor(ByteBuffer compressedData) {
		this.compressedData = compressedData;
		int header1 = compressedData.getInt(0);
		int header2 = compressedData.getInt(4);

		this.width = unpack(header1, 0, 13);
		this.height = unpack(header1, 13, 13);
		this.numColorsPerBlock = unpack(header1, 26, 6);
		this.blockWidth = unpack(header2, 0, 8);
		this.blockHeight = unpack(header2, 8, 8);
		this.numColors = unpack(header2, 16, 14);
		this.numChannels = 1 + unpack(header2, 30, 2);
		System.out.println("num colors is " + numColors + " and per block is " + numColorsPerBlock);
	}

	public int getColor(int x, int y) {
		int numTableBytes = nextMultipleOf(numColors * numChannels, 4);
		int headerAndTableBytes = 8 + numTableBytes;

		int blockX = x / blockWidth;
		int localX = x % blockWidth;
		int blockY = y / blockHeight;
		int localY = y % blockHeight;
		int blockIndex = blockX + (width / blockWidth) * blockY;

		int globalBitsPerPixel = computeBitsPerPixel(numColors);
		int blockHeaderBits = numColorsPerBlock * globalBitsPerPixel;
		int localBitsPerPixel = computeBitsPerPixel(numColorsPerBlock);
		int blockSizeBits = blockHeaderBits + blockWidth * blockHeight * localBitsPerPixel;

		int blockOffsetBits = headerAndTableBytes * 8 + blockIndex * blockSizeBits;
		int localIndexOffsetBits = blockOffsetBits + blockHeaderBits + localBitsPerPixel * (localX + blockWidth * localY);

		int localColorIndex = readInt(localIndexOffsetBits, localBitsPerPixel);
		int globalColorIndex = readInt(blockOffsetBits + localColorIndex * globalBitsPerPixel, globalBitsPerPixel);

		int colorByteIndex = 8 + globalColorIndex * numChannels;
		int color = readInt(8 * colorByteIndex, 8 * numChannels);
		if (numChannels < 4) color |= 255 << 24;
		if (numChannels < 3) color |= 255 << 16;
		if (numChannels < 2) color |= 255 << 8;
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
