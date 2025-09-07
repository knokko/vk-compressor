package com.github.knokko.compressor;

import com.github.knokko.boiler.BoilerInstance;
import com.github.knokko.boiler.descriptors.DescriptorSetLayoutBuilder;
import com.github.knokko.boiler.descriptors.VkbDescriptorSetLayout;
import com.github.knokko.boiler.memory.callbacks.CallbackUserData;
import org.lwjgl.vulkan.VkPushConstantRange;

import static org.lwjgl.system.MemoryStack.stackPush;
import static org.lwjgl.vulkan.VK10.*;

/**
 * This is a modified version of the BC4 compressor from
 * <a href="https://github.com/darksylinc/betsy/blob/master/bin/Data/bc4.glsl">the Betsy GPU compressor</a>.
 * I (knokko) modified it to make it compatible with Vulkan. Furthermore, I altered the algorithm to
 * work on buffers rather than images, and changed the code style to my liking.
 */
public class Bc4Compressor {

	final BoilerInstance boiler;
	/**
	 * All <i>descriptorSet</i>s passed to the <i>compress</i> methods of <i>Bc4Worker</i> should have this layout.
	 */
	public final VkbDescriptorSetLayout descriptorSetLayout;

	final long pipelineLayout;
	final long pipeline;

	/**
	 * Constructs a new <i>Bc4Compressor</i> using the given <i>BoilerInstance</i>. You should normally only need 1
	 * <i>Bc4Compressor</i> instance.
	 */
	public Bc4Compressor(BoilerInstance boiler) {
		this.boiler = boiler;
		try (var stack = stackPush()) {
			var builder = new DescriptorSetLayoutBuilder(stack, 2);
			for (int index = 0; index < 2; index++) {
				builder.set(index, index, VK_DESCRIPTOR_TYPE_STORAGE_BUFFER, VK_SHADER_STAGE_COMPUTE_BIT);
			}
			this.descriptorSetLayout = builder.build(boiler, "Bc4CompressorDescriptorSetLayout");

			var pushConstants = VkPushConstantRange.calloc(1, stack);
			//noinspection resource
			pushConstants.get(0).set(VK_SHADER_STAGE_COMPUTE_BIT, 0, 12);
			this.pipelineLayout = boiler.pipelines.createLayout(
					pushConstants, "Bc4CompressorPipelineLayout",
					descriptorSetLayout.vkDescriptorSetLayout
			);

			this.pipeline = boiler.pipelines.createComputePipeline(
					pipelineLayout, "com/github/knokko/compressor/betsy-bc4.spv", "Bc4Compressor"
			);
		}
	}

	/**
	 * Destroys this <i>Bc4Compressor</i>
	 */
	public void destroy() {
		try (var stack = stackPush()) {
			vkDestroyPipeline(boiler.vkDevice(), pipeline, CallbackUserData.PIPELINE.put(stack, boiler));
			vkDestroyDescriptorSetLayout(
					boiler.vkDevice(), descriptorSetLayout.vkDescriptorSetLayout,
					CallbackUserData.DESCRIPTOR_SET_LAYOUT.put(stack, boiler)
			);
			vkDestroyPipelineLayout(
					boiler.vkDevice(), pipelineLayout,
					CallbackUserData.PIPELINE_LAYOUT.put(stack, boiler)
			);
		}
	}
}
