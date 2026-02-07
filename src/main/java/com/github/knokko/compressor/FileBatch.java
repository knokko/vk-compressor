package com.github.knokko.compressor;

import java.io.File;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.file.StandardOpenOption;
import java.util.Arrays;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.function.Consumer;

import static java.lang.Math.min;
import static org.lwjgl.stb.STBImage.*;
import static org.lwjgl.system.MemoryStack.stackPush;
import static org.lwjgl.system.MemoryUtil.memCalloc;
import static org.lwjgl.system.MemoryUtil.memFree;

class FileBatch {

	public final ByteBuffer[] imageData;
	public final int[] widths, heights;
	private final File[] destinationFiles;
	private final int numThreads;
	private final long startProcessTime = System.nanoTime();
	private long startSaveTime, finishSaveTime;

	private FileBatch(ByteBuffer[] imageData, int[] widths, int[] heights, File[] destinationFiles, int numThreads) {
		this.imageData = imageData;
		this.widths = widths;
		this.heights = heights;
		this.destinationFiles = destinationFiles;
		this.numThreads = numThreads;
	}

	static void processBatches(
			int numThreads, int batchSize, boolean benchmark, int rawWidth, File[] inputFiles,
			String extension, Consumer<FileBatch> processBatch
	) {
		long totalReadTime = 0L;
		long totalProcessTime = 0L;
		long totalWriteTime = 0L;
		long totalPixels = 0L;

		for (int batchStartIndex = 0; batchStartIndex < inputFiles.length; batchStartIndex += batchSize) {
			long startReadTime = System.nanoTime();
			int batchBoundIndex = min(batchStartIndex + batchSize, inputFiles.length);
			var currentBatchFiles = Arrays.copyOfRange(inputFiles, batchStartIndex, batchBoundIndex);
			ByteBuffer[] imageData = new ByteBuffer[currentBatchFiles.length];
			int[] widths = new int[imageData.length];
			int[] heights = new int[imageData.length];
			var completions = new Future[imageData.length];

			try (var threadPool = Executors.newFixedThreadPool(numThreads)) {
				for (int fileIndex = 0; fileIndex < currentBatchFiles.length; fileIndex++) {
					var imageFile = currentBatchFiles[fileIndex];
					final int rememberFileIndex = fileIndex;
					completions[fileIndex] = threadPool.submit(() -> {
						try (var stack = stackPush()) {
							var pWidth = stack.callocInt(1);
							var pHeight = stack.callocInt(1);
							var pChannels = stack.callocInt(1);
							int desiredChannels = 4;
							if (extension.equals("bc4")) desiredChannels = 1;

							if (rawWidth == 0) {
								imageData[rememberFileIndex] = stbi_load(
										imageFile.getPath(), pWidth, pHeight, pChannels, desiredChannels
								);
								if (imageData[rememberFileIndex] == null) {
									throw new RuntimeException("Failed to load " + imageFile.getAbsolutePath() +
											": " + stbi_failure_reason());
								}
								widths[rememberFileIndex] = pWidth.get();
								heights[rememberFileIndex] = pHeight.get();
							} else {
								try (var channel = FileChannel.open(imageFile.toPath(), StandardOpenOption.READ)) {
									int fileSize = Math.toIntExact(channel.size());
									imageData[rememberFileIndex] = memCalloc(fileSize);
									int numPixels = fileSize / desiredChannels;
									int rawHeight = numPixels / rawWidth;
									if (numPixels % rawWidth != 0) {
										throw new RuntimeException(
												"All file sizes must be a multiple of raw-width = " + rawWidth +
														" times channels = " + desiredChannels + ", but got " +
														imageFile + " with length " + fileSize
										);
									}

									while (imageData[rememberFileIndex].position() < fileSize) {
										if (channel.read(imageData[rememberFileIndex]) == -1) {
											throw new RuntimeException("Failed to finish reading " + imageFile);
										}
									}
									imageData[rememberFileIndex].position(0);
									widths[rememberFileIndex] = rawWidth;
									heights[rememberFileIndex] = rawHeight;
								} catch (IOException failed) {
									throw new RuntimeException("Failed to read " + imageFile.getAbsolutePath(), failed);
								}
							}
						}
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

			var destinationFiles = new File[currentBatchFiles.length];
			for (int fileIndex = 0; fileIndex < destinationFiles.length; fileIndex++) {
				var inputFile = currentBatchFiles[fileIndex];
				var inputFileName = inputFile.getName();
				var indexDot = inputFileName.indexOf('.');

				String outputFileName;
				if (indexDot == -1) {
					outputFileName = inputFileName + "." + extension;
				} else {
					outputFileName = inputFileName.substring(0, indexDot) + "." + extension;
				}

				destinationFiles[fileIndex] = new File(inputFile.getAbsoluteFile().getParentFile() + "/" + outputFileName);
				if (benchmark) destinationFiles[fileIndex].deleteOnExit();
				totalPixels += (long) widths[fileIndex] * heights[fileIndex];
			}

			var batch = new FileBatch(imageData, widths, heights, destinationFiles, numThreads);
			processBatch.accept(batch);
			long finishProcessTime = System.nanoTime();

			if (rawWidth == 0) {
				for (var fileContent : imageData) stbi_image_free(fileContent);
			} else {
				for (var fileContent : imageData) memFree(fileContent);
			}
			long finishFreeTime = System.nanoTime();

			long currentWriteTime = batch.finishSaveTime - batch.startSaveTime;
			long currentProcessTime = finishProcessTime - batch.startProcessTime - currentWriteTime;
			totalReadTime += finishFreeTime - startReadTime - currentProcessTime - currentWriteTime;
			totalProcessTime += currentProcessTime;
			totalWriteTime += currentWriteTime;
		}

		if (benchmark) {
			System.out.println("Processed " + totalPixels + " pixels from " + inputFiles.length + " files");
			System.out.println("Reading took " + totalReadTime / 1000_000L + "ms");
			System.out.println("Processing took " + totalProcessTime / 1000_000L + "ms");
			System.out.println("Writing took " + totalWriteTime / 1000_000L + "ms");
		}
	}

	void saveResults(ByteBuffer[] results) {
		startSaveTime = System.nanoTime();
		try (var threadPool = Executors.newFixedThreadPool(numThreads)) {
			for (int fileIndex = 0; fileIndex < results.length; fileIndex++) {
				var file = destinationFiles[fileIndex];
				var resultBuffer = results[fileIndex];
				threadPool.submit(() -> {
					try {
						var channel = FileChannel.open(
								file.toPath(), StandardOpenOption.CREATE,
								StandardOpenOption.WRITE, StandardOpenOption.TRUNCATE_EXISTING
						);
						while (resultBuffer.remaining() > 0) {
							//noinspection ResultOfMethodCallIgnored
							channel.write(resultBuffer);
						}
						channel.close();
					} catch (IOException failed) {
						throw new RuntimeException("Failed to save " + file.getAbsolutePath(), failed);
					}
				});
			}
		}
		finishSaveTime = System.nanoTime();
	}
}
