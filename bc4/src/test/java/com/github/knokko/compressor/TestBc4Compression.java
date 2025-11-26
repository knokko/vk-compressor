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
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.file.Files;

import static com.github.knokko.compressor.TestHelper.assertImageEquals;
import static org.junit.jupiter.api.Assertions.*;
import static org.lwjgl.vulkan.VK10.*;
import static org.lwjgl.vulkan.VK12.VK_API_VERSION_1_2;

public class TestBc4Compression {

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

	private BufferedImage crappyDecodeBc4(byte[] bytes, int width, int height) {
		BufferedImage image = new BufferedImage(width, height, BufferedImage.TYPE_INT_ARGB);
		ByteBuffer byteBuffer = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN);
		for (int blockX = 0; blockX < width / 4; blockX++) {
			for (int blockY = 0; blockY < height / 4; blockY++) {
				int blockIndex = 8 * (blockX + (width / 4) * blockY);
				long longValue = byteBuffer.getLong(blockIndex);
				float maxValue = Byte.toUnsignedInt((byte) (longValue & 255)) / 255f;
				float minValue = Byte.toUnsignedInt((byte) ((longValue >> 8) & 255)) / 255f;

				for (int offsetX = 0; offsetX < 4; offsetX++) {
					for (int offsetY = 0; offsetY < 4; offsetY++) {
						int imageX = 4 * blockX + offsetX;
						int imageY = 4 * blockY + offsetY;
						int bitOffset = 16 + 3 * (offsetX + 4 * offsetY);
						long byteValue = (longValue >> bitOffset) & 7;
						float progress = (byteValue - 1) / 7f;
						if (byteValue == 0) progress = 1f;
						if (byteValue == 1) progress = 0f;
						float gray = minValue + progress * (maxValue - minValue);
						image.setRGB(imageX, imageY, new Color(gray, gray, gray).getRGB());
					}
				}
			}
		}

		return image;
	}

	@Test
	public void testWith1SubmissionAnd1Worker() throws IOException {
		var boiler = new BoilerBuilder(
				VK_API_VERSION_1_2, "Bc4With1WorkerAnd1Submission", 1
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
		var compressor = new Bc4Compressor(boiler);
		var worker = new Bc4Worker(compressor, 0, combiner);

		var descriptorCombiner = new DescriptorCombiner(boiler);
		var descriptorSets = descriptorCombiner.addMultiple(compressor.descriptorSetLayout, files.length);
		var descriptorPool = descriptorCombiner.build("CompressorDescriptors");

		MappedVkbBuffer[] sourceBuffers = new MappedVkbBuffer[files.length];
		MappedVkbBuffer[] destinationBuffers = new MappedVkbBuffer[files.length];
		long storageAlignment = boiler.deviceProperties.limits().minStorageBufferOffsetAlignment();
		for (int index = 0; index < files.length; index++) {
			var image = sourceImages[index];
			sourceBuffers[index] = combiner.addMappedDeviceLocalBuffer(
					(long) image.getWidth() * image.getHeight(),
					storageAlignment, VK_BUFFER_USAGE_STORAGE_BUFFER_BIT, 0.5f
			);
			destinationBuffers[index] = combiner.addMappedDeviceLocalBuffer(
					(long) image.getWidth() * image.getHeight() / 2,
					storageAlignment, VK_BUFFER_USAGE_STORAGE_BUFFER_BIT, 0.5f
			);
		}

		var memory = combiner.build(false);

		for (int index = 0; index < files.length; index++) {
			ByteBuffer byteBuffer = sourceBuffers[index].byteBuffer();
			for (int y = 0; y < sourceImages[index].getHeight(); y++) {
				for (int x = 0; x < sourceImages[index].getWidth(); x++) {
					Color color = new Color(sourceImages[index].getRGB(x, y));
					byteBuffer.put((byte) ((color.getRed() + color.getGreen() + color.getBlue()) / 3));
				}
			}
		}

		long startRecordTime = System.nanoTime();
		var submission = SingleTimeCommands.submit(boiler, "Bc4Compression", recorder -> {
			for (int index = 0; index < files.length; index++) {
				var image = sourceImages[index];
				worker.compress(
						recorder, descriptorSets[index], sourceBuffers[index],
						destinationBuffers[index], image.getWidth(), image.getHeight()
				);
			}
			recorder.bulkBufferBarrier(
					ResourceUsage.computeBuffer(VK_ACCESS_SHADER_WRITE_BIT),
					ResourceUsage.HOST_READ, destinationBuffers
			);
		});
		long submissionTime = System.nanoTime();
		submission.destroy();
		System.out.println("Recording compression took " + (submissionTime - startRecordTime) / 1_000_000 + " ms");
		System.out.println("Compression took " + (System.nanoTime() - submissionTime) / 1_000 + " us");

		for (int index = 0; index < files.length; index++) {
			File destinationFile = new File(destinationFolder + "/" + files[index].getName());
			var image = sourceImages[index];
			var outputBuffer = destinationBuffers[index].byteBuffer();
			var outputArray = new byte[outputBuffer.capacity()];
			outputBuffer.get(outputArray);
			ImageIO.write(crappyDecodeBc4(outputArray, image.getWidth(), image.getHeight()), "PNG", destinationFile);
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
				VK_API_VERSION_1_2, "Bc4SequentialTest", 1
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
		var compressor = new Bc4Compressor(boiler);
		var worker = new Bc4Worker(compressor, 0, combiner);

		MappedVkbBuffer[] sourceBuffers = new MappedVkbBuffer[files.length];
		MappedVkbBuffer[] destinationBuffers = new MappedVkbBuffer[files.length];
		long storageAlignment = boiler.deviceProperties.limits().minStorageBufferOffsetAlignment();
		for (int index = 0; index < files.length; index++) {
			var image = sourceImages[index];
			sourceBuffers[index] = combiner.addMappedDeviceLocalBuffer(
					(long) image.getWidth() * image.getHeight(),
					storageAlignment, VK_BUFFER_USAGE_STORAGE_BUFFER_BIT, 0.5f
			);
			destinationBuffers[index] = combiner.addMappedDeviceLocalBuffer(
					(long) image.getWidth() * image.getHeight() / 2,
					storageAlignment, VK_BUFFER_USAGE_STORAGE_BUFFER_BIT, 0.5f
			);
		}

		var memory = combiner.build(false);

		for (int index = 0; index < files.length; index++) {
			ByteBuffer byteBuffer = sourceBuffers[index].byteBuffer();
			for (int y = 0; y < sourceImages[index].getHeight(); y++) {
				for (int x = 0; x < sourceImages[index].getWidth(); x++) {
					Color color = new Color(sourceImages[index].getRGB(x, y));
					byteBuffer.put((byte) ((color.getRed() + color.getGreen() + color.getBlue()) / 3));
				}
			}
		}
		var descriptorCombiner = new DescriptorCombiner(boiler);
		var descriptorSet = descriptorCombiner.addMultiple(compressor.descriptorSetLayout, 1);
		var descriptorPool = descriptorCombiner.build("CompressionDescriptors");

		File destinationFolder = Files.createTempDirectory("").toFile();
		destinationFolder.deleteOnExit();
		assertTrue(destinationFolder.isDirectory() || destinationFolder.mkdirs());

		var commands = new SingleTimeCommands(boiler);
		for (int index = 0; index < files.length; index++) {
			var image = sourceImages[index];
			var sourceBuffer = sourceBuffers[index];
			var destinationBuffer = destinationBuffers[index];
			commands.submit("Bc4Compression", recorder -> {
				worker.compress(
						recorder, descriptorSet[0], sourceBuffer, destinationBuffer,
						image.getWidth(), image.getHeight()
				);
				var computeUsage = ResourceUsage.computeBuffer(VK_ACCESS_SHADER_WRITE_BIT);
				recorder.bufferBarrier(destinationBuffer, computeUsage, ResourceUsage.HOST_READ);
			}).awaitCompletion();
			File destinationFile = new File(destinationFolder + "/" + files[index].getName());
			var outputBuffer = destinationBuffer.byteBuffer();
			var outputArray = new byte[outputBuffer.capacity()];
			outputBuffer.get(outputArray);
			ImageIO.write(crappyDecodeBc4(outputArray, image.getWidth(), image.getHeight()), "PNG", destinationFile);
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
