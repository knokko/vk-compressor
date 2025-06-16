package com.github.knokko.compressor;

import com.github.knokko.boiler.buffers.MappedVkbBuffer;
import com.github.knokko.boiler.builders.BoilerBuilder;
import com.github.knokko.boiler.commands.SingleTimeCommands;
import com.github.knokko.boiler.descriptors.DescriptorCombiner;
import com.github.knokko.boiler.memory.MemoryCombiner;
import com.github.knokko.boiler.synchronization.ResourceUsage;
import org.junit.jupiter.api.Test;

import javax.imageio.ImageIO;
import java.awt.*;
import java.awt.image.BufferedImage;
import java.io.File;
import java.io.IOException;
import java.nio.file.Files;

import static com.github.knokko.compressor.TestHelper.assertImageEquals;
import static org.junit.jupiter.api.Assertions.*;
import static org.lwjgl.vulkan.VK10.*;
import static org.lwjgl.vulkan.VK12.VK_API_VERSION_1_2;

public class TestBc1Compression {

	private void checkResults(File actualFolder) throws IOException {
		assertTrue(actualFolder.isDirectory());
		File[] actualFiles = actualFolder.listFiles();
		assertNotNull(actualFiles);

		File expectedFolder = new File("expected mardek output");
		assertTrue(expectedFolder.isDirectory());

		File[] expectedFiles = expectedFolder.listFiles();
		assertNotNull(expectedFiles);

		assertEquals(expectedFiles.length, actualFiles.length);
		for (File expected : expectedFiles) {
			File actual = new File(actualFolder + "/" + expected.getName());
			assertEquals(expected.length(), actual.length(), expected.getName());
			assertImageEquals(ImageIO.read(expected), ImageIO.read(actual));
		}
	}

	private int parseRaw(byte[] bytes, int offset) {
		return (bytes[offset] & 0xFF) + 256 * (bytes[offset + 1] & 0xFF);
	}

	private float[] parseColor(byte[] bytes, int offset) {
		int raw = parseRaw(bytes, offset);
		float[] result = new float[3];

		result[0] = (raw & 31) / 31f;
		result[1] = ((raw >> 5) & 63) / 63f;
		result[2] = ((raw >> 11) & 31) / 31f;
		return result;
	}

	private BufferedImage crappyDecodeBc1(byte[] bytes, int width, int height) {
		BufferedImage image = new BufferedImage(width, height, BufferedImage.TYPE_INT_ARGB);
		for (int blockX = 0; blockX < width / 4; blockX++) {
			for (int blockY = 0; blockY < height / 4; blockY++) {
				int blockIndex = 8 * (blockX + (width / 4) * blockY);
				float[] endpoint1 = parseColor(bytes, blockIndex);
				float[] endpoint2 = parseColor(bytes, blockIndex + 2);

				boolean swap = parseRaw(bytes, blockIndex) <= parseRaw(bytes, blockIndex + 2);
				if (swap) {
					var temp = endpoint2;
					endpoint2 = endpoint1;
					endpoint1 = temp;
				}

				for (int offsetX = 0; offsetX < 4; offsetX++) {
					for (int offsetY = 0; offsetY < 4; offsetY++) {
						int imageX = 4 * blockX + offsetX;
						int imageY = 4 * blockY + offsetY;
						int innerBitOffset = 2 * (offsetX + 4 * offsetY);
						int byteValue = bytes[blockIndex + 4 + innerBitOffset / 8] & 0xFF;
						int bits = (byteValue >> (innerBitOffset % 8)) & 3;
						if (swap && bits == 3) {
							image.setRGB(imageX, imageY, 0);
							continue;
						}
						if (swap) bits = 2 - bits;
						float progress = swap ? bits / 2f : bits / 3f;
						float[] mixedEndpoint = {
								progress * endpoint2[0] + (1f - progress) * endpoint1[0],
								progress * endpoint2[1] + (1f - progress) * endpoint1[1],
								progress * endpoint2[2] + (1f - progress) * endpoint1[2],
						};

						image.setRGB(imageX, imageY, new Color(mixedEndpoint[2], mixedEndpoint[1], mixedEndpoint[0]).getRGB());
					}
				}
			}
		}

		return image;
	}

	@Test
	public void testWith1SubmissionAnd1Worker() throws IOException {
		var boiler = new BoilerBuilder(
				VK_API_VERSION_1_2, "Bc1With1WorkerAnd1Submission", 1
		)
				.validation()
				.forbidValidationErrors()
				// This ridiculously long timeout is needed on GitHub Actions for some reason
				.defaultTimeout(10_000_000_000L)
				.build();

		File[] files = new File("../test-helper/src/main/resources/com/github/knokko/compressor/mardek").listFiles();
		assertNotNull(files);
		BufferedImage[] sourceImages = new BufferedImage[files.length];
		for (int index = 0; index < files.length; index++) {
			sourceImages[index] = ImageIO.read(files[index]);
		}

		File destinationFolder = Files.createTempDirectory("").toFile();
		destinationFolder.deleteOnExit();
		assertTrue(destinationFolder.isDirectory() || destinationFolder.mkdirs());

		var combiner = new MemoryCombiner(boiler, "CompressorMemory");
		var stagingCombiner = new MemoryCombiner(boiler, "Staging");
		var compressor = new Bc1Compressor(boiler, combiner, stagingCombiner);
		var worker = new Bc1Worker(compressor, 0, combiner);

		var descriptorCombiner = new DescriptorCombiner(boiler);
		var descriptorSets = descriptorCombiner.addMultiple(compressor.descriptorSetLayout, files.length);
		var descriptorPool = descriptorCombiner.build("CompressorDescriptors");

		MappedVkbBuffer[] sourceBuffers = new MappedVkbBuffer[files.length];
		MappedVkbBuffer[] destinationBuffers = new MappedVkbBuffer[files.length];
		long storageAlignment = boiler.deviceProperties.limits().minStorageBufferOffsetAlignment();
		for (int index = 0; index < files.length; index++) {
			var image = sourceImages[index];
			sourceBuffers[index] = combiner.addMappedDeviceLocalBuffer(
					4L * image.getWidth() * image.getHeight(),
					storageAlignment, VK_BUFFER_USAGE_STORAGE_BUFFER_BIT
			);
			destinationBuffers[index] = combiner.addMappedDeviceLocalBuffer(
					(long) image.getWidth() * image.getHeight() / 2,
					storageAlignment, VK_BUFFER_USAGE_STORAGE_BUFFER_BIT
			);
		}

		var memory = combiner.build(false);
		var stagingMemory = stagingCombiner.build(false);

		for (int index = 0; index < files.length; index++) {
			sourceBuffers[index].encodeBufferedImage(sourceImages[index]);
		}

		var commands = new SingleTimeCommands(boiler);
		commands.submit("StagingTransfer", compressor::performStagingTransfer).awaitCompletion();
		stagingMemory.destroy(boiler);

		long startRecordTime = System.nanoTime();
		commands.submit("Bc1Compression", recorder -> {
			for (int index = 0; index < files.length; index++) {
				var image = sourceImages[index];
				worker.compress(
						recorder, descriptorSets[index], sourceBuffers[index],
						destinationBuffers[index], image.getWidth(), image.getHeight()
				);
			}

		});
		long submissionTime = System.nanoTime();
		commands.destroy();
		System.out.println("Recording compression took " + (submissionTime - startRecordTime) / 1_000_000 + " ms");
		System.out.println("Compression took " + (System.nanoTime() - submissionTime) / 1_000 + " us");

		for (int index = 0; index < files.length; index++) {
			File destinationFile = new File(destinationFolder + "/" + files[index].getName());
			var image = sourceImages[index];
			var outputBuffer = destinationBuffers[index].byteBuffer();
			var outputArray = new byte[outputBuffer.capacity()];
			outputBuffer.get(outputArray);
			ImageIO.write(crappyDecodeBc1(outputArray, image.getWidth(), image.getHeight()), "PNG", destinationFile);
			destinationFile.deleteOnExit();
		}

		vkDestroyDescriptorPool(boiler.vkDevice(), descriptorPool, null);
		compressor.destroy();
		memory.destroy(boiler);
		boiler.destroyInitialObjects();

		checkResults(destinationFolder);
	}

	@Test
	public void testWithManySubmissionsAnd1Worker() throws IOException {
		var boiler = new BoilerBuilder(
				VK_API_VERSION_1_2, "Bc1SequentialTest", 1
		)
				.validation()
				.forbidValidationErrors()
				.build();

		File[] files = new File("../test-helper/src/main/resources/com/github/knokko/compressor/mardek").listFiles();
		assertNotNull(files);
		BufferedImage[] sourceImages = new BufferedImage[files.length];
		for (int index = 0; index < files.length; index++) {
			sourceImages[index] = ImageIO.read(files[index]);
		}

		var combiner = new MemoryCombiner(boiler, "CompressionMemory");
		var stagingCombiner = new MemoryCombiner(boiler, "StagingMemory");
		var compressor = new Bc1Compressor(boiler, combiner, stagingCombiner);
		var worker = new Bc1Worker(compressor, 0, combiner);

		MappedVkbBuffer[] sourceBuffers = new MappedVkbBuffer[files.length];
		MappedVkbBuffer[] destinationBuffers = new MappedVkbBuffer[files.length];
		long storageAlignment = boiler.deviceProperties.limits().minStorageBufferOffsetAlignment();
		for (int index = 0; index < files.length; index++) {
			var image = sourceImages[index];
			sourceBuffers[index] = combiner.addMappedDeviceLocalBuffer(
					4L * image.getWidth() * image.getHeight(),
					storageAlignment, VK_BUFFER_USAGE_STORAGE_BUFFER_BIT
			);
			destinationBuffers[index] = combiner.addMappedDeviceLocalBuffer(
					(long) image.getWidth() * image.getHeight() / 2,
					storageAlignment, VK_BUFFER_USAGE_STORAGE_BUFFER_BIT
			);
		}

		var memory = combiner.build(false);
		var stagingMemory = stagingCombiner.build(false);

		for (int index = 0; index < files.length; index++) {
			sourceBuffers[index].encodeBufferedImage(sourceImages[index]);
		}
		var descriptorCombiner = new DescriptorCombiner(boiler);
		var descriptorSet = descriptorCombiner.addMultiple(compressor.descriptorSetLayout, 1);
		var descriptorPool = descriptorCombiner.build("CompressionDescriptors");

		File destinationFolder = Files.createTempDirectory("").toFile();
		destinationFolder.deleteOnExit();
		assertTrue(destinationFolder.isDirectory() || destinationFolder.mkdirs());

		var commands = new SingleTimeCommands(boiler);
		commands.submit("StagingTransfer", compressor::performStagingTransfer).awaitCompletion();
		stagingMemory.destroy(boiler);

		for (int index = 0; index < files.length; index++) {
			var image = sourceImages[index];
			var sourceBuffer = sourceBuffers[index];
			var destinationBuffer = destinationBuffers[index];
			sourceBuffers[index].encodeBufferedImage(image);
			commands.submit("Bc1Compression", recorder -> {
				worker.compress(
						recorder, descriptorSet[0], sourceBuffer, destinationBuffer,
						image.getWidth(), image.getHeight()
				);
				var computeUsage = ResourceUsage.computeBuffer(VK_ACCESS_SHADER_WRITE_BIT);
				recorder.bufferBarrier(destinationBuffer, computeUsage, computeUsage);
			}).awaitCompletion();
			File destinationFile = new File(destinationFolder + "/" + files[index].getName());
			var outputBuffer = destinationBuffer.byteBuffer();
			var outputArray = new byte[outputBuffer.capacity()];
			outputBuffer.get(outputArray);
			ImageIO.write(crappyDecodeBc1(outputArray, image.getWidth(), image.getHeight()), "PNG", destinationFile);
			destinationFile.deleteOnExit();
		}

		commands.destroy();
		compressor.destroy();
		memory.destroy(boiler);
		vkDestroyDescriptorPool(boiler.vkDevice(), descriptorPool, null);
		boiler.destroyInitialObjects();

		checkResults(destinationFolder);
	}
}
