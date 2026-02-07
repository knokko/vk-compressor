package com.github.knokko.compressor;

import com.github.knokko.boiler.builders.BoilerBuilder;

import java.io.File;

import static org.lwjgl.vulkan.VK10.VK_API_VERSION_1_0;

class Bc1Cli {

	static void run(
			int numThreads, int batchSize, boolean benchmark, int rawWidth,
			boolean validation, File[] inputFiles
	) {
		var boilerBuilder = new BoilerBuilder(
				VK_API_VERSION_1_0, "Bc1CompressorCli", 1
		).doNotUseVma();
		if (validation) {
			boilerBuilder.validation();
			boilerBuilder.forbidValidationErrors();
		} else {
			boilerBuilder.hideDeviceSelectionInfo();
		}

		var boiler = boilerBuilder.build();
		FileBatch.processBatches(numThreads, batchSize, benchmark, rawWidth, inputFiles, "bc1", batch ->
				Bc1Compressor.compressRgbaImageData(
						batch.imageData, batch.widths, batch.heights, boiler, batch::saveResults
				)
		);
		boiler.destroyInitialObjects();
	}
}
