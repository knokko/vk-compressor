package com.github.knokko.compressor;

import java.io.File;
import java.nio.ByteBuffer;
import java.nio.IntBuffer;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import static com.github.knokko.boiler.utilities.ColorPacker.rgba;

class Kim2Cli {

	static void run(
			int numThreads, int batchSize, boolean benchmark,
			int rawWidth, int bitsPerPixel, File[] inputFiles
	) {
		FileBatch.processBatches(numThreads, batchSize, benchmark, rawWidth, inputFiles, "kim2", batch -> {
			var destinationBuffers = new ByteBuffer[batch.imageData.length];
			var completions = new Future[batch.imageData.length];

			try (var threadPool = Executors.newFixedThreadPool(numThreads)) {
				for (int index = 0; index < batch.imageData.length; index++) {
					final int finalIndex = index;
					int intSize = Kim2Compressor.predictIntSize(batch.widths[index], batch.heights[index], bitsPerPixel);
					completions[index] = threadPool.submit(() -> {
						destinationBuffers[finalIndex] = ByteBuffer.allocate(4 * intSize);

						var imageData = batch.imageData[finalIndex];
						var inputIntBuffer = IntBuffer.allocate(imageData.capacity() / 4);
						while (imageData.hasRemaining()) {
							inputIntBuffer.put(rgba(imageData.get(), imageData.get(), imageData.get(), imageData.get()));
						}
						inputIntBuffer.flip();
						imageData.flip();
						Kim2Compressor.compress(
								inputIntBuffer,
								batch.widths[finalIndex], batch.heights[finalIndex],
								destinationBuffers[finalIndex].asIntBuffer(),
								bitsPerPixel
						);
					});
				}
			}

			for (var shouldBeComplete : completions) {
				try {
					shouldBeComplete.get();
				} catch (Exception e) {
					throw new RuntimeException(e);
				}
			}

			batch.saveResults(destinationBuffers);
		});
	}
}
