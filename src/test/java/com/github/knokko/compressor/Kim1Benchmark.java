package com.github.knokko.compressor;

import java.io.IOException;
import java.nio.ByteBuffer;

public class Kim1Benchmark {

	public static void main(String[] args) throws IOException {
		var inputs = BenchmarkHelper.getPixelatedBenchmarkImages();
		ByteBuffer destination = ByteBuffer.allocate(800);

		long startTime = System.nanoTime();
		long numPixels = 0L;
		long numBytes = 0L;
		for (int repetition = 0; repetition < 1000; repetition++) {
			for (ByteBuffer inputImage : inputs) {
				destination.position(0);
				inputImage.position(0);
				var compressor = new Kim1Compressor(inputImage, 16, 16, 4);
				compressor.compress(destination);
				numPixels += 16L * 16L;
				numBytes += destination.position();
			}
		}
		long endTime = System.nanoTime();

		long milliseconds = (endTime - startTime) / 1000_000L;
		System.out.println("Compressed " + (numPixels / 1000.0) + "k pixels in " + milliseconds + "ms");
		System.out.println("Which is " + (numPixels / milliseconds) + " pixels/ms");
		System.out.println("Using " + numBytes + " bytes, which is " + ((double) numBytes / numPixels) + " bytes/pixel");
	}
}
