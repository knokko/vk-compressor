package com.github.knokko.compressor;

import java.awt.image.BufferedImage;
import java.io.IOException;

public class Bc7Benchmark {

	public static void main(String[] args) throws IOException {
		var inputImages = BenchmarkHelper.getLargerBenchmarkImages();
		int numRepetitions = 5;

		long startTime = System.nanoTime();
		long numPixels = 0;
		for (int repetition = 0; repetition < numRepetitions; repetition++) {
			for (BufferedImage image : inputImages) {
				Bc7Compressor.compressBc7(image);
				numPixels += (long) image.getWidth() * image.getHeight();
			}
		}
		long endTime = System.nanoTime();

		long milliseconds = (endTime - startTime) / 1000_000L;
		System.out.println("Compressed " + (numPixels / 1000.0) + "k pixels in " + milliseconds + "ms");
		System.out.println("Which is " + (numPixels / milliseconds) + " pixels/ms");
	}
}
