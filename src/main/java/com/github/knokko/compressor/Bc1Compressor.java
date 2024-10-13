package com.github.knokko.compressor;

import com.github.knokko.boiler.BoilerInstance;
import com.github.knokko.boiler.buffers.VkbBuffer;
import com.github.knokko.boiler.commands.CommandRecorder;
import com.github.knokko.boiler.descriptors.GrowingDescriptorBank;
import com.github.knokko.boiler.descriptors.VkbDescriptorSetLayout;
import com.github.knokko.boiler.synchronization.ResourceUsage;
import org.lwjgl.vulkan.VkDescriptorSetLayoutBinding;
import org.lwjgl.vulkan.VkPushConstantRange;

import java.io.IOException;

import static org.lwjgl.system.MemoryStack.stackPush;
import static org.lwjgl.system.MemoryUtil.memByteBuffer;
import static org.lwjgl.vulkan.VK10.*;

public class Bc1Compressor {

	final BoilerInstance boiler;
	public final VkbDescriptorSetLayout descriptorSetLayout;
	public final GrowingDescriptorBank descriptorBank;
	final long pipelineLayout;
	final long pipeline;
	final VkbBuffer matchBuffer;

	public Bc1Compressor(BoilerInstance boiler) {
		this.boiler = boiler;
		try (var stack = stackPush()) {
			var bindings = VkDescriptorSetLayoutBinding.calloc(3, stack);
			for (int index = 0; index < 3; index++) {
				boiler.descriptors.binding(bindings, index, VK_DESCRIPTOR_TYPE_STORAGE_BUFFER, VK_SHADER_STAGE_COMPUTE_BIT);
			}
			this.descriptorSetLayout = boiler.descriptors.createLayout(
					stack, bindings, "Bc1CompressorDescriptorSetLayout"
			);

			var pushConstants = VkPushConstantRange.calloc(1, stack);
			//noinspection resource
			pushConstants.get(0).set(VK_SHADER_STAGE_COMPUTE_BIT, 0, 8);
			this.pipelineLayout = boiler.pipelines.createLayout(
					pushConstants, "Bc1CompressorPipelineLayout",
					descriptorSetLayout.vkDescriptorSetLayout
			);

			this.pipeline = boiler.pipelines.createComputePipeline(
					pipelineLayout, "com/github/knokko/compressor/betsy-bc1.spv", "Bc1Compressor"
			);

			this.descriptorBank = new GrowingDescriptorBank(descriptorSetLayout, 0);

			var matchStagingBuffer = boiler.buffers.createMapped(
					4096, VK_BUFFER_USAGE_TRANSFER_SRC_BIT, "Bc1CompressorMatchStaging"
			);
			this.matchBuffer = boiler.buffers.create(
					4096, VK_BUFFER_USAGE_STORAGE_BUFFER_BIT | VK_BUFFER_USAGE_TRANSFER_DST_BIT,
					"Bc1CompressorMatch"
			);

			var hostBuffer = memByteBuffer(matchStagingBuffer.hostAddress(), (int) matchBuffer.size());
			var matchInput = Bc1Compressor.class.getResourceAsStream("match.bin");
			hostBuffer.put(matchInput.readAllBytes());
			matchInput.close();

			var stagingPool = boiler.commands.createPool(0, boiler.queueFamilies().graphics().index(), "Bc1MatchStaging");
			var stagingCommands = boiler.commands.createPrimaryBuffers(stagingPool, 1, "Bc1MatchStaging")[0];

			var recorder = CommandRecorder.begin(stagingCommands, boiler, stack, "Bc1MatchStaging");
			recorder.copyBuffer(matchStagingBuffer.fullRange(), matchBuffer.vkBuffer(), 0);
			recorder.bufferBarrier(
					matchBuffer.fullRange(), ResourceUsage.TRANSFER_DEST,
					ResourceUsage.computeBuffer(VK_ACCESS_SHADER_READ_BIT)
			);
			recorder.end();

			var fence = boiler.sync.fenceBank.borrowFence(false, "Bc1MatchFence");
			boiler.queueFamilies().graphics().first().submit(stagingCommands, "Bc1Match", null, fence);
			fence.awaitSignal();
			boiler.sync.fenceBank.returnFence(fence);

			vkDestroyCommandPool(boiler.vkDevice(), stagingPool, null);
			matchStagingBuffer.destroy(boiler);
		} catch (IOException e) {
			throw new RuntimeException("Failed to read match.bin", e);
		}
	}

	public void destroy(boolean checkDescriptorBorrows) {
		matchBuffer.destroy(boiler);
		vkDestroyPipeline(boiler.vkDevice(), pipeline, null);
		descriptorBank.destroy(checkDescriptorBorrows);
		descriptorSetLayout.destroy();
		vkDestroyPipelineLayout(boiler.vkDevice(), pipelineLayout, null);
	}
}
