package com.github.knokko.compressor;

import javax.imageio.ImageIO;
import java.io.IOException;
import java.nio.IntBuffer;
import java.util.Objects;

import static com.github.knokko.boiler.utilities.BoilerMath.nextMultipleOf;

public class Kim2Benchmark {

	public static void main(String[] args) throws IOException {
		var inputImage = ImageIO.read(Objects.requireNonNull(Kim2Benchmark.class.getResource("EarthThick.png")));
		int width = inputImage.getWidth();
		int height = inputImage.getHeight();
		var inputBuffer = IntBuffer.allocate(width * height);
		int bitsPerPixel = 4;
		int numRepetitions = 10_000;

		int byteSize = Kim2Compressor.predictByteSize(width, height, bitsPerPixel);
		int intSize = nextMultipleOf(byteSize, 4);
		IntBuffer destination = IntBuffer.allocate(intSize);

		long startTime = System.nanoTime();
		for (int repetition = 0; repetition < numRepetitions; repetition++) {
			inputBuffer.position(0);
			destination.position(0);
			Kim2Compressor.compress(inputBuffer, width, height, destination, bitsPerPixel);
		}
		long endTime = System.nanoTime();

		long milliseconds = (endTime - startTime) / 1000_000L;
		long numPixels = width * height * (long) numRepetitions;
		System.out.println("Compressed " + (numPixels / 1000.0) + "k pixels in " + milliseconds + "ms");
		System.out.println("Which is " + (numPixels / milliseconds) + " pixels/ms");
	}
}
