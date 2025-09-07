package com.github.knokko.compressor;

import com.github.knokko.boiler.BoilerInstance;
import com.github.knokko.boiler.buffers.MappedVkbBuffer;
import com.github.knokko.boiler.buffers.VkbBuffer;
import com.github.knokko.boiler.commands.CommandRecorder;
import com.github.knokko.boiler.descriptors.DescriptorSetLayoutBuilder;
import com.github.knokko.boiler.descriptors.VkbDescriptorSetLayout;
import com.github.knokko.boiler.memory.MemoryCombiner;
import com.github.knokko.boiler.memory.callbacks.CallbackUserData;
import com.github.knokko.boiler.synchronization.ResourceUsage;
import org.lwjgl.vulkan.VkPushConstantRange;

import java.io.IOException;
import java.util.Objects;

import static org.lwjgl.system.MemoryStack.stackPush;
import static org.lwjgl.vulkan.VK10.*;

/**
 * This is a modified version of the BC1 compressor from
 * <a href="https://github.com/darksylinc/betsy/blob/master/bin/Data/bc1.glsl">the Betsy GPU compressor</a>.
 * I (knokko) modified it to make it compatible with Vulkan. Furthermore, I altered the algorithm to
 * use the implicit 1-bit alpha channel.
 */
public class Bc1Compressor {

	final BoilerInstance boiler;
	/**
	 * All <i>descriptorSet</i>s passed to the <i>compress</i> methods of <i>Bc1Worker</i> should have this layout.
	 */
	public final VkbDescriptorSetLayout descriptorSetLayout;

	final long pipelineLayout;
	final long pipeline;
	final VkbBuffer matchBuffer;
	final MappedVkbBuffer stagingBuffer;
	boolean didStagingTransfer = false;

	/**
	 * Constructs a new <i>Bc1Compressor</i> using the given <i>BoilerInstance</i>. You should normally only need 1
	 * <i>Bc1Compressor</i> instance.
	 */
	public Bc1Compressor(BoilerInstance boiler, MemoryCombiner combiner, MemoryCombiner stagingCombiner) {
		this.boiler = boiler;
		try (var stack = stackPush()) {
			var builder = new DescriptorSetLayoutBuilder(stack, 3);
			for (int index = 0; index < 3; index++) {
				builder.set(index, index, VK_DESCRIPTOR_TYPE_STORAGE_BUFFER, VK_SHADER_STAGE_COMPUTE_BIT);
			}
			this.descriptorSetLayout = builder.build(boiler, "Bc1CompressorDescriptorSetLayout");

			var pushConstants = VkPushConstantRange.calloc(1, stack);
			//noinspection resource
			pushConstants.get(0).set(VK_SHADER_STAGE_COMPUTE_BIT, 0, 16);
			this.pipelineLayout = boiler.pipelines.createLayout(
					pushConstants, "Bc1CompressorPipelineLayout",
					descriptorSetLayout.vkDescriptorSetLayout
			);

			this.pipeline = boiler.pipelines.createComputePipeline(
					pipelineLayout, "com/github/knokko/compressor/betsy-bc1.spv", "Bc1Compressor"
			);

			this.stagingBuffer = stagingCombiner.addMappedBuffer(
					4096, 1, VK_BUFFER_USAGE_TRANSFER_SRC_BIT
			);

			long alignment = boiler.deviceProperties.limits().minStorageBufferOffsetAlignment();
			this.matchBuffer = combiner.addBuffer(
					4096, alignment,
					VK_BUFFER_USAGE_STORAGE_BUFFER_BIT | VK_BUFFER_USAGE_TRANSFER_DST_BIT, 1f
			);
		}
	}

	public void performStagingTransfer(CommandRecorder recorder) {
		didStagingTransfer = true;
		try {
			var matchInput = Bc1Compressor.class.getResourceAsStream("match.bin");
			stagingBuffer.byteBuffer().put(Objects.requireNonNull(matchInput).readAllBytes());
			matchInput.close();
		} catch (IOException shouldNotHappen) {
			throw new Error(shouldNotHappen);
		}

		recorder.copyBuffer(stagingBuffer, matchBuffer);
		recorder.bufferBarrier(
				matchBuffer, ResourceUsage.TRANSFER_DEST,
				ResourceUsage.computeBuffer(VK_ACCESS_SHADER_READ_BIT)
		);
	}

	/**
	 * Destroys this <i>Bc1Compressor</i>
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
