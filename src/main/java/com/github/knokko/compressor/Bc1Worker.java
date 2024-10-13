package com.github.knokko.compressor;

import com.github.knokko.boiler.buffers.VkbBuffer;
import com.github.knokko.boiler.buffers.VkbBufferRange;
import com.github.knokko.boiler.commands.CommandRecorder;
import com.github.knokko.boiler.images.VkbImage;
import com.github.knokko.boiler.synchronization.ResourceUsage;
import org.lwjgl.vulkan.VkWriteDescriptorSet;

import static org.lwjgl.vulkan.VK10.*;

public class Bc1Worker {

	private final Bc1Compressor compressor;
	private VkbBuffer transferBuffer;

	Bc1Worker(Bc1Compressor compressor) {
		this.compressor = compressor;
	}

	public void compress(CommandRecorder recorder, long descriptorSet, VkbBufferRange source, VkbImage destination) {
		long transferBufferSize = (long) destination.width() * destination.height() / 2;
		if (transferBuffer == null || transferBuffer.size() < transferBufferSize) {
			if (transferBuffer != null) transferBuffer.destroy(compressor.boiler);
			transferBuffer = compressor.boiler.buffers.create(
					transferBufferSize,
					VK_BUFFER_USAGE_STORAGE_BUFFER_BIT | VK_BUFFER_USAGE_TRANSFER_SRC_BIT,
					"Bc1TransferBuffer"
			);
		} else {
			recorder.bufferBarrier(
					transferBuffer.fullRange(), ResourceUsage.TRANSFER_SOURCE,
					ResourceUsage.computeBuffer(VK_ACCESS_SHADER_WRITE_BIT)
			);
		}

		compress(recorder, descriptorSet, source, transferBuffer.fullRange(), destination.width(), destination.height());
		recorder.bufferBarrier(
				transferBuffer.fullRange(), ResourceUsage.computeBuffer(VK_ACCESS_SHADER_WRITE_BIT),
				ResourceUsage.TRANSFER_SOURCE
		);
		recorder.copyBufferToImage(destination, transferBuffer.fullRange());
	}

	public void compress(
			CommandRecorder recorder, long descriptorSet, VkbBufferRange source,
			VkbBufferRange destination, int width, int height
	) {
		if (width % 4 != 0 || height % 4 != 0) {
			throw new IllegalArgumentException("Width (" + width + ") and height (" + height + ") must be a multiple of 4");
		}
		if (4L * width * height > source.size()) throw new IllegalArgumentException("Source range is too small");
		if ((long) width * height / 2 > destination.size()) throw new IllegalArgumentException("Destination range is too small");

		var writes = VkWriteDescriptorSet.calloc(3, recorder.stack);
		compressor.boiler.descriptors.writeBuffer(
				recorder.stack, writes, descriptorSet, 0,
				VK_DESCRIPTOR_TYPE_STORAGE_BUFFER, compressor.matchBuffer.fullRange()
		);
		compressor.boiler.descriptors.writeBuffer(
				recorder.stack, writes, descriptorSet, 1,
				VK_DESCRIPTOR_TYPE_STORAGE_BUFFER, source
		);
		compressor.boiler.descriptors.writeBuffer(
				recorder.stack, writes, descriptorSet, 2,
				VK_DESCRIPTOR_TYPE_STORAGE_BUFFER, destination
		);
		vkUpdateDescriptorSets(compressor.boiler.vkDevice(), writes, null);

		vkCmdBindPipeline(recorder.commandBuffer, VK_PIPELINE_BIND_POINT_COMPUTE, compressor.pipeline);
		recorder.bindComputeDescriptors(compressor.pipelineLayout, descriptorSet);
		//noinspection SuspiciousNameCombination
		vkCmdPushConstants(
				recorder.commandBuffer, compressor.pipelineLayout,
				VK_SHADER_STAGE_COMPUTE_BIT, 0, recorder.stack.ints(2, width)
		);
		vkCmdDispatch(recorder.commandBuffer, width / 4, height / 4, 1);
	}

	public void destroy() {
		if (transferBuffer != null) transferBuffer.destroy(compressor.boiler);
	}
}
