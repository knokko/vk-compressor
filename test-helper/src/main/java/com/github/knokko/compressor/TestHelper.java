package com.github.knokko.compressor;

import java.awt.*;
import java.awt.image.BufferedImage;

import static org.junit.jupiter.api.Assertions.assertEquals;

class TestHelper {

	static void assertImageEquals(BufferedImage expected, BufferedImage actual) {
		assertEquals(expected.getWidth(), actual.getWidth());
		assertEquals(expected.getHeight(), actual.getHeight());
		for (int x = 0; x < expected.getWidth(); x++) {
			for (int y = 0; y < expected.getHeight(); y++) {
				var expectedColor = new Color(expected.getRGB(x, y), true);
				var actualColor = new Color(actual.getRGB(x, y), true);
				assertEquals(expectedColor, actualColor);
			}
		}
	}
}
