package com.github.knokko.compressor;

import com.github.knokko.boiler.BoilerInstance;
import com.github.knokko.boiler.descriptors.GrowingDescriptorBank;
import com.github.knokko.boiler.descriptors.VkbDescriptorSetLayout;
import org.lwjgl.vulkan.VkDescriptorSetLayoutBinding;
import org.lwjgl.vulkan.VkPushConstantRange;

import static org.lwjgl.system.MemoryStack.stackPush;
import static org.lwjgl.vulkan.VK10.*;

public class Bc1Compressor {

	final BoilerInstance boiler;
	public final VkbDescriptorSetLayout descriptorSetLayout;
	public final GrowingDescriptorBank descriptorBank;
	public final long pipelineLayout;
	public final long pipeline;

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
		}
	}

	public void destroy(boolean checkDescriptorBorrows) {
		vkDestroyPipeline(boiler.vkDevice(), pipeline, null);
		descriptorBank.destroy(checkDescriptorBorrows);
		descriptorSetLayout.destroy();
		vkDestroyPipelineLayout(boiler.vkDevice(), pipelineLayout, null);
	}
}
