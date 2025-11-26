package com.github.knokko.compressor;

import com.github.knokko.boiler.buffers.MappedVkbBuffer;
import com.github.knokko.boiler.buffers.VkbBuffer;
import com.github.knokko.boiler.builders.BoilerBuilder;
import com.github.knokko.boiler.commands.SingleTimeCommands;
import com.github.knokko.boiler.descriptors.DescriptorCombiner;
import com.github.knokko.boiler.memory.MemoryCombiner;
import com.github.knokko.boiler.synchronization.ResourceUsage;

import java.awt.*;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

import static com.github.knokko.boiler.utilities.BoilerMath.leastCommonMultiple;
import static org.lwjgl.vulkan.VK10.*;
import static org.lwjgl.vulkan.VK12.VK_API_VERSION_1_2;

public class Bc4Benchmark {

	public static void main(String[] args) throws IOException {
		var inputImages = BenchmarkHelper.getLargerBenchmarkImages();
		int numRepetitions = 250;

		var boiler = new BoilerBuilder(
				VK_API_VERSION_1_2, "Bc4Benchmark", 1
		).build();
		var compressor = new Bc4Compressor(boiler);

		var mainCombiner = new MemoryCombiner(boiler, "MainCombiner");
		var stagingCombiner = new MemoryCombiner(boiler, "StagingCombiner");

		var worker = new Bc4Worker(compressor, 0, mainCombiner);

		List<MappedVkbBuffer> hostInputs = new ArrayList<>(inputImages.size());
		List<VkbBuffer> deviceInputs = new ArrayList<>(inputImages.size());
		List<VkbBuffer> outputs = new ArrayList<>(numRepetitions * inputImages.size());

		long alignment = leastCommonMultiple(4L, boiler.deviceProperties.limits().minStorageBufferOffsetAlignment());
		long numPixels = 0;
		for (BufferedImage image : inputImages) {
			int size = image.getWidth() * image.getHeight();
			hostInputs.add(stagingCombiner.addMappedBuffer(size, alignment, VK_BUFFER_USAGE_TRANSFER_SRC_BIT));
			deviceInputs.add(mainCombiner.addBuffer(
					size, alignment,
					VK_BUFFER_USAGE_STORAGE_BUFFER_BIT | VK_BUFFER_USAGE_TRANSFER_DST_BIT, 1f
			));
			for (int repetition = 0; repetition < numRepetitions; repetition++) {
				outputs.add(mainCombiner.addBuffer(size / 2L, alignment, VK_BUFFER_USAGE_STORAGE_BUFFER_BIT, 1f));
				numPixels += size;
			}
		}

		var mainMemory = mainCombiner.build(false);
		var stagingMemory = stagingCombiner.build(false);

		var commands = new SingleTimeCommands(boiler);
		commands.submit("StagingTransfer", recorder -> {
			for (int index = 0; index < inputImages.size(); index++) {
				var inputImage = inputImages.get(index);
				var hostBuffer = hostInputs.get(index).byteBuffer();
				for (int y = 0; y < inputImage.getHeight(); y++) {
					for (int x = 0; x < inputImage.getWidth(); x++) {
						Color color = new Color(inputImage.getRGB(x, y), true);
						int value = color.getAlpha() * (color.getRed() + color.getGreen() + color.getBlue()) / 3 * 255;
						hostBuffer.put((byte) Math.min(255, Math.max(0, value)));
					}
				}
				recorder.copyBuffer(hostInputs.get(index), deviceInputs.get(index));
				recorder.bufferBarrier(
						deviceInputs.get(index), ResourceUsage.TRANSFER_DEST,
						ResourceUsage.computeBuffer(VK_ACCESS_SHADER_READ_BIT)
				);
			}
		}).awaitCompletion();

		stagingMemory.destroy(boiler);

		var descriptorCombiner = new DescriptorCombiner(boiler);
		long[] descriptorSets = descriptorCombiner.addMultiple(
				compressor.descriptorSetLayout, numRepetitions * inputImages.size()
		);
		var descriptorPool = descriptorCombiner.build("Bc4Descriptors");

		var submission = commands.submit("Bc4Compression", recorder -> {
			for (int index = 0; index < inputImages.size(); index++) {
				var image = inputImages.get(index);
				for (int repetition = 0; repetition < numRepetitions; repetition++) {
					int globalIndex = numRepetitions * index + repetition;
					worker.compress(
							recorder, descriptorSets[globalIndex], deviceInputs.get(index),
							outputs.get(globalIndex), image.getWidth(), image.getHeight()
					);
				}
			}
			recorder.bulkBufferBarrier(
					ResourceUsage.computeBuffer(VK_ACCESS_SHADER_WRITE_BIT),
					ResourceUsage.TRANSFER_SOURCE,
					outputs.toArray(new VkbBuffer[0])
			);
		});
		long startTime = System.nanoTime();
		submission.awaitCompletion();
		long endTime = System.nanoTime();
		commands.destroy();

		vkDestroyDescriptorPool(boiler.vkDevice(), descriptorPool, null);
		compressor.destroy();
		mainMemory.destroy(boiler);
		boiler.destroyInitialObjects();

		double milliseconds = (endTime - startTime) / 1000_000.0;
		System.out.println("Compressed " + (numPixels / 1000_000.0) + "M pixels in " + milliseconds + "ms");
		System.out.println("Which is " + (numPixels / milliseconds / 1000_000) + " mega-pixels/ms");
	}
}
