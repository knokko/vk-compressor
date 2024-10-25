package com.github.knokko.compressor;

import java.nio.ByteBuffer;

class BitWriter {

	static int computeBitsPerPixel(int numColors) {
		int bitsPerPixel = 0;
		int numSupportedColors = 1;

		while (numSupportedColors < numColors) {
			bitsPerPixel += 1;
			numSupportedColors *= 2;
			if (numSupportedColors < 0) throw new IllegalArgumentException("Too many colors " + numColors);
		}

		return bitsPerPixel;
	}

	static int pack(int value, int bitOffset) {
		return value << bitOffset;
	}

	static int unpack(int packed, int bitOffset, int bitLength) {
		int unpacked = packed >> bitOffset;
		if (bitLength < 32) unpacked &= (1 << bitLength) - 1;
		return unpacked;
	}

	private final ByteBuffer byteBuffer;
	private int bitIndex;
	private long bits;

	public BitWriter(ByteBuffer byteBuffer) {
		this.byteBuffer = byteBuffer;
	}

	public void write(int value, int numBits) {
		bits |= (long) value << bitIndex;
		bitIndex += numBits;

		if (bitIndex >= 32) {
			byteBuffer.putInt((int) bits);
			bits >>= 32;
			bitIndex -= 32;
		}
	}

	public void flush() {
		if (bitIndex > 0) {
			byteBuffer.putInt((int) bits);
			bits = 0;
			bitIndex = 0;
		}
	}
}
