package com.github.knokko.compressor;

import java.io.File;
import java.nio.ByteBuffer;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import static com.github.knokko.boiler.utilities.BoilerMath.nextMultipleOf;
import static org.lwjgl.system.MemoryUtil.memCalloc;

public class Bc7Cli {

	static void run(int numThreads, int batchSize, boolean benchmark, int rawWidth, int flags, File[] inputFiles) {
		FileBatch.processBatches(numThreads, batchSize, benchmark, rawWidth, inputFiles, "bc7", batch -> {
			var destinationBuffers = new ByteBuffer[batch.imageData.length];
			var completions = new Future[batch.imageData.length];

			try (var threadPool = Executors.newFixedThreadPool(numThreads)) {

				for (int index = 0; index < batch.imageData.length; index++) {
					int paddedWidth = nextMultipleOf(batch.widths[index], 4);
					int paddedHeight = nextMultipleOf(batch.heights[index], 4);
					destinationBuffers[index] = memCalloc(paddedWidth * paddedHeight);

					final int finalIndex = index;
					completions[index] = threadPool.submit(() ->
						Bc7Compressor.compressRgbaImageData(
								flags, batch.imageData[finalIndex],
								batch.widths[finalIndex], batch.heights[finalIndex],
								destinationBuffers[finalIndex]
						)
					);
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
