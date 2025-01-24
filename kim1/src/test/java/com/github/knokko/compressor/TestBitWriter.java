package com.github.knokko.compressor;

import org.junit.jupiter.api.Test;

import static com.github.knokko.compressor.BitWriter.*;
import static org.junit.jupiter.api.Assertions.assertEquals;

public class TestBitWriter {

	@Test
	public void testComputeBitsPerPixel() {
		assertEquals(0, computeBitsPerPixel(1));
		assertEquals(1, computeBitsPerPixel(2));
		assertEquals(2, computeBitsPerPixel(3));
		assertEquals(2, computeBitsPerPixel(4));
		assertEquals(3, computeBitsPerPixel(5));
		assertEquals(3, computeBitsPerPixel(6));
		assertEquals(3, computeBitsPerPixel(7));
		assertEquals(3, computeBitsPerPixel(8));
		assertEquals(4, computeBitsPerPixel(9));
	}

	@Test
	public void testPacking() {
		int packed = pack(123, 0) | pack(456, 7) | pack(5, 26) | pack(7, 29);
		assertEquals(123, unpack(packed, 0, 7));
		assertEquals(456, unpack(packed, 7, 9));
		assertEquals(456, unpack(packed, 7, 15));
		assertEquals(5, unpack(packed, 26, 3));
		assertEquals(7, unpack(packed, 29, 3));
	}

	@Test
	public void testUnpack24() {
		int[] subjects = { 0, 1, 2, 3, 100, 1000, (1 << 24) - 1 };
		for (int subject : subjects) {
			assertEquals(subject, unpack(pack(subject, 0), 0, 24));
			assertEquals(subject, unpack(pack(subject, 7), 7, 24));
			assertEquals(subject, unpack(pack(subject, 8), 8, 24));
		}
	}

	@Test
	public void testFullUnpack() {
		int[] subjects = {-1, 0, 1, 123, Integer.MIN_VALUE, Integer.MAX_VALUE};
		for (int subject : subjects) {
			assertEquals(subject, unpack(pack(subject, 0), 0, 32));
		}
	}
}
