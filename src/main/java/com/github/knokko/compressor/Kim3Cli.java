package com.github.knokko.compressor;

import java.io.File;
import java.nio.ByteBuffer;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

class Kim3Cli {

	static void run(int numThreads, int batchSize, boolean benchmark, int rawWidth, File[] inputFiles) {
		FileBatch.processBatches(numThreads, batchSize, benchmark, rawWidth, inputFiles, "kim3", batch -> {
			var destinationBuffers = new ByteBuffer[batch.imageData.length];
			var completions = new Future[batch.imageData.length];

			try (var threadPool = Executors.newFixedThreadPool(numThreads)) {
				for (int index = 0; index < batch.imageData.length; index++) {
					final int finalIndex = index;
					completions[index] = threadPool.submit(() -> {
						var compressor = new Kim3Compressor(
								batch.imageData[finalIndex],
								batch.widths[finalIndex], batch.heights[finalIndex]
						);
						destinationBuffers[finalIndex] = ByteBuffer.allocate(4 * compressor.intSize);
						compressor.compress(destinationBuffers[finalIndex]);
						destinationBuffers[finalIndex].flip();
						batch.imageData[finalIndex].position(0);
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
