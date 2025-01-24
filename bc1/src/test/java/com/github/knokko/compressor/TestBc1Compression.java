package com.github.knokko.compressor;

import com.github.knokko.boiler.builders.BoilerBuilder;
import com.github.knokko.boiler.commands.CommandRecorder;
import com.github.knokko.boiler.synchronization.ResourceUsage;
import org.junit.jupiter.api.Test;
import org.lwjgl.vulkan.VkPhysicalDeviceProperties;

import javax.imageio.ImageIO;
import java.awt.*;
import java.awt.image.BufferedImage;
import java.io.File;
import java.io.IOException;
import java.nio.file.Files;

import static com.github.knokko.boiler.exceptions.VulkanFailureException.assertVkSuccess;
import static com.github.knokko.boiler.utilities.BoilerMath.nextMultipleOf;
import static com.github.knokko.compressor.TestHelper.assertImageEquals;
import static org.junit.jupiter.api.Assertions.*;
import static org.lwjgl.system.MemoryStack.stackPush;
import static org.lwjgl.system.MemoryUtil.memByteBuffer;
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

		var fence = boiler.sync.fenceBank.borrowFence(false, "Bc1Fence");
		var commandPool = boiler.commands.createPool(0, boiler.queueFamilies().compute().index(), "CmdPool");
		var commandBuffer = boiler.commands.createPrimaryBuffers(commandPool, 1, "CmdBuffer")[0];

		var compressor = new Bc1Compressor(boiler);
		var worker = new Bc1Worker(compressor);

		var sourceBuffer = boiler.buffers.createMapped(200_000, VK_BUFFER_USAGE_STORAGE_BUFFER_BIT, "Source");
		var destinationBuffer = boiler.buffers.createMapped(40_000, VK_BUFFER_USAGE_STORAGE_BUFFER_BIT, "Destination");

		var descriptorPool = compressor.descriptorSetLayout.createPool(files.length, 0, "Bc1Descriptors");
		var descriptorSets = descriptorPool.allocate(files.length);

		long storageAlignment;
		try (var stack = stackPush()) {
			var properties = VkPhysicalDeviceProperties.calloc(stack);
			vkGetPhysicalDeviceProperties(boiler.vkPhysicalDevice(), properties);
			storageAlignment = properties.limits().minStorageBufferOffsetAlignment();
		}

		long sourceOffset = 0;
		for (int index = 0; index < files.length; index++) {
			sourceOffset = nextMultipleOf(sourceOffset, storageAlignment);
			var image = sourceImages[index];
			long sourceSize = 4L * image.getWidth() * image.getHeight();
			boiler.buffers.encodeBufferedImageRGBA(sourceBuffer, image, sourceOffset);
			sourceOffset += sourceSize;
		}

		try (var stack = stackPush()) {
			long startRecordTime = System.nanoTime();
			var recorder = CommandRecorder.begin(commandBuffer, boiler, stack, "Bc1Encode");

			long destOffset = 0;
			sourceOffset = 0;
			for (int index = 0; index < files.length; index++) {
				sourceOffset = nextMultipleOf(sourceOffset, storageAlignment);
				destOffset = nextMultipleOf(destOffset, storageAlignment);
				var image = sourceImages[index];

				long sourceSize = 4L * image.getWidth() * image.getHeight();
				long destSize = (long) image.getWidth() * image.getHeight() / 2;
				worker.compress(
						recorder, descriptorSets[index], sourceBuffer.range(sourceOffset, sourceSize),
						destinationBuffer.range(destOffset, destSize), image.getWidth(), image.getHeight()
				);

				sourceOffset += sourceSize;
				destOffset += destSize;
			}

			recorder.end();

			long submissionTime = System.nanoTime();
			boiler.queueFamilies().graphics().first().submit(commandBuffer, "Bc1", null, fence);

			// This ridiculously long timeout is needed on GitHub Actions for some reason
			fence.waitAndReset(10_000_000_000L);
			System.out.println("Recording compression took " + (submissionTime - startRecordTime) / 1_000_000 + " ms");
			System.out.println("Compression took " + (System.nanoTime() - submissionTime) / 1_000 + " us");
		}

		long destinationOffset = 0;
		for (int index = 0; index < files.length; index++) {
			destinationOffset = nextMultipleOf(destinationOffset, storageAlignment);
			File destinationFile = new File(destinationFolder + "/" + files[index].getName());
			var image = sourceImages[index];
			int destinationSize = image.getWidth() * image.getHeight() / 2;
			var outputBuffer = memByteBuffer(destinationBuffer.hostAddress() + destinationOffset, destinationSize);
			var outputArray = new byte[outputBuffer.capacity()];
			outputBuffer.get(outputArray);
			ImageIO.write(crappyDecodeBc1(outputArray, image.getWidth(), image.getHeight()), "PNG", destinationFile);
			destinationFile.deleteOnExit();

			destinationOffset += destinationSize;
		}

		vkDestroyCommandPool(boiler.vkDevice(), commandPool, null);
		boiler.sync.fenceBank.returnFence(fence);
		worker.destroy();
		descriptorPool.destroy();
		compressor.destroy(true);
		sourceBuffer.destroy(boiler);
		destinationBuffer.destroy(boiler);
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

		var compressor = new Bc1Compressor(boiler);
		var worker = new Bc1Worker(compressor);

		var sourceBuffer = boiler.buffers.createMapped(5000, VK_BUFFER_USAGE_STORAGE_BUFFER_BIT, "Source");
		var destinationBuffer = boiler.buffers.createMapped(5000, VK_BUFFER_USAGE_STORAGE_BUFFER_BIT, "Source");
		var descriptorSet = compressor.descriptorBank.borrowDescriptorSet("Single");

		File[] files = new File("../test-helper/src/main/resources/com/github/knokko/compressor/mardek").listFiles();
		assertNotNull(files);
		BufferedImage[] sourceImages = new BufferedImage[files.length];
		for (int index = 0; index < files.length; index++) {
			sourceImages[index] = ImageIO.read(files[index]);
		}

		File destinationFolder = Files.createTempDirectory("").toFile();
		destinationFolder.deleteOnExit();
		assertTrue(destinationFolder.isDirectory() || destinationFolder.mkdirs());

		var fence = boiler.sync.fenceBank.borrowFence(false, "Bc1Fence");
		var commandPool = boiler.commands.createPool(0, boiler.queueFamilies().compute().index(), "CmdPool");
		var commandBuffer = boiler.commands.createPrimaryBuffers(commandPool, 1, "CmdBuffer")[0];

		for (int index = 0; index < files.length; index++) {
			var image = sourceImages[index];
			boiler.buffers.encodeBufferedImageRGBA(sourceBuffer, image, 0);
			try (var stack = stackPush()) {
				assertVkSuccess(vkResetCommandPool(
						boiler.vkDevice(), commandPool, 0
				), "ResetCommandPool", "Bc1");
				var recorder = CommandRecorder.begin(commandBuffer, boiler, stack, "Bc1Encode");
				worker.compress(
						recorder, descriptorSet, sourceBuffer.fullRange(),
						destinationBuffer.fullRange(), image.getWidth(), image.getHeight()
				);
				var computeUsage = ResourceUsage.computeBuffer(VK_ACCESS_SHADER_WRITE_BIT);
				recorder.bufferBarrier(destinationBuffer.fullRange(), computeUsage, computeUsage);
				recorder.end();

				boiler.queueFamilies().graphics().first().submit(commandBuffer, "Bc1", null, fence);

				// This ridiculously long timeout is needed on GitHub Actions for some reason
				fence.waitAndReset(10_000_000_000L);

				File destinationFile = new File(destinationFolder + "/" + files[index].getName());
				var outputBuffer = memByteBuffer(destinationBuffer.hostAddress(), image.getWidth() * image.getHeight() / 2);
				var outputArray = new byte[outputBuffer.capacity()];
				outputBuffer.get(outputArray);
				ImageIO.write(crappyDecodeBc1(outputArray, image.getWidth(), image.getHeight()), "PNG", destinationFile);
				destinationFile.deleteOnExit();
			}
		}

		vkDestroyCommandPool(boiler.vkDevice(), commandPool, null);
		boiler.sync.fenceBank.returnFence(fence);
		worker.destroy();
		compressor.descriptorBank.returnDescriptorSet(descriptorSet);
		compressor.destroy(true);
		sourceBuffer.destroy(boiler);
		destinationBuffer.destroy(boiler);
		boiler.destroyInitialObjects();

		checkResults(destinationFolder);
	}
}
