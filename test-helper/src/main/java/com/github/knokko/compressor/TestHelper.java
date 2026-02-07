package com.github.knokko.compressor;

import javax.imageio.ImageIO;
import java.awt.*;
import java.awt.image.BufferedImage;
import java.io.File;
import java.io.IOException;

import static java.lang.Double.parseDouble;
import static org.junit.jupiter.api.Assertions.assertEquals;

public class TestHelper {

	static void assertImageEquals(BufferedImage expected, BufferedImage actual, double threshold) {
		assertEquals(expected.getWidth(), actual.getWidth());
		assertEquals(expected.getHeight(), actual.getHeight());
		for (int x = 0; x < expected.getWidth(); x++) {
			for (int y = 0; y < expected.getHeight(); y++) {
				var expectedColor = new Color(expected.getRGB(x, y), true);
				var actualColor = new Color(actual.getRGB(x, y), true);
				int finalX = x;
				int finalY = y;
				assertEquals(
						expectedColor.getRed(), actualColor.getRed(), threshold,
						() -> "red (" + finalX + ", " + finalY + ")"
				);
				assertEquals(
						expectedColor.getGreen(), actualColor.getGreen(), threshold,
						() -> "green (" + finalX + ", " + finalY + ")"
				);
				assertEquals(
						expectedColor.getBlue(), actualColor.getBlue(), threshold,
						() -> "blue (" + finalX + ", " + finalY + ")"
				);
				assertEquals(
						expectedColor.getAlpha(), actualColor.getAlpha(), threshold,
						() -> "alpha (" + finalX + ", " + finalY + ")"
				);
			}
		}
	}

	public static void main(String[] args) throws IOException {
		double threshold = 1.0;
		if (args.length == 3) threshold = parseDouble(args[2]);
		assertImageEquals(ImageIO.read(new File(args[0])), ImageIO.read(new File(args[1])), threshold);
	}
}
