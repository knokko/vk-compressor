package com.github.knokko.compressor;

import com.github.knokko.boiler.buffers.MappedVkbBuffer;
import com.github.knokko.boiler.buffers.VkbBuffer;
import com.github.knokko.boiler.builders.BoilerBuilder;
import com.github.knokko.boiler.commands.SingleTimeCommands;
import com.github.knokko.boiler.descriptors.DescriptorCombiner;
import com.github.knokko.boiler.memory.MemoryCombiner;
import com.github.knokko.boiler.synchronization.ResourceUsage;
import com.github.knokko.boiler.utilities.ImageCoding;

import java.awt.image.BufferedImage;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

import static com.github.knokko.boiler.utilities.BoilerMath.leastCommonMultiple;
import static org.lwjgl.vulkan.VK10.*;
import static org.lwjgl.vulkan.VK12.VK_API_VERSION_1_2;

public class Bc1Benchmark {

	public static void main(String[] args) throws IOException {
		var inputImages = BenchmarkHelper.getLargerBenchmarkImages();
		int numRepetitions = 250;

		var boiler = new BoilerBuilder(
				VK_API_VERSION_1_2, "Bc1Benchmark", 1
		).build();

		var mainCombiner = new MemoryCombiner(boiler, "MainCombiner");
		var stagingCombiner = new MemoryCombiner(boiler, "StagingCombiner");

		var compressor = new Bc1Compressor(boiler, mainCombiner, stagingCombiner);
		var worker = new Bc1Worker(compressor, 0, mainCombiner);

		List<MappedVkbBuffer> hostInputs = new ArrayList<>(inputImages.size());
		List<VkbBuffer> deviceInputs = new ArrayList<>(inputImages.size());
		List<VkbBuffer> outputs = new ArrayList<>(numRepetitions * inputImages.size());

		long alignment = leastCommonMultiple(4L, boiler.deviceProperties.limits().minStorageBufferOffsetAlignment());
		long numPixels = 0;
		for (BufferedImage image : inputImages) {
			int size = image.getWidth() * image.getHeight();
			hostInputs.add(stagingCombiner.addMappedBuffer(4L * size, alignment, VK_BUFFER_USAGE_TRANSFER_SRC_BIT));
			deviceInputs.add(mainCombiner.addBuffer(
					4L * size, alignment,
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
			compressor.performStagingTransfer(recorder);
			for (int index = 0; index < inputImages.size(); index++) {
				ImageCoding.encodeBufferedImage(hostInputs.get(index).byteBuffer(), inputImages.get(index));
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
		var descriptorPool = descriptorCombiner.build("Bc1Descriptors");

		var submission = commands.submit("Bc1Compression", recorder -> {
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
