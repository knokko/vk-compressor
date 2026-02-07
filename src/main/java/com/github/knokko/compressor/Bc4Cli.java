package com.github.knokko.compressor;

import com.github.knokko.boiler.builders.BoilerBuilder;

import java.io.File;

import static org.lwjgl.vulkan.VK10.VK_API_VERSION_1_0;

class Bc4Cli {

	static void run(
			int numThreads, int batchSize, boolean benchmark, int rawWidth, boolean signed,
			boolean validation, File[] inputFiles
	) {
		var boilerBuilder = new BoilerBuilder(
				VK_API_VERSION_1_0, "Bc4CompressorCli", 1
		).doNotUseVma();
		if (validation) {
			boilerBuilder.validation();
			boilerBuilder.forbidValidationErrors();
		} else {
			boilerBuilder.hideDeviceSelectionInfo();
		}

		var boiler = boilerBuilder.build();
		FileBatch.processBatches(numThreads, batchSize, benchmark, rawWidth, inputFiles, "bc4", batch ->
				Bc4Compressor.compressGreyscaleImageData(
						batch.imageData, batch.widths, batch.heights, signed, boiler, batch::saveResults
				)
		);
		boiler.destroyInitialObjects();
	}
}
