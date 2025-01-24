package com.github.knokko.compressor;

import com.github.knokko.boiler.buffers.VkbBuffer;
import com.github.knokko.boiler.buffers.VkbBufferRange;
import com.github.knokko.boiler.commands.CommandRecorder;
import com.github.knokko.boiler.images.VkbImage;
import com.github.knokko.boiler.synchronization.ResourceUsage;
import org.lwjgl.vulkan.VkWriteDescriptorSet;

import static org.lwjgl.vulkan.VK10.*;

/**
 * A worker of the <i>Bc1Compressor</i>. This class contains some potentially mutable state that is needed for the
 * compression. Therefor, you need multiple <i>Bc1Worker</i>s for some parallelism.
 */
public class Bc1Worker {

	private final Bc1Compressor compressor;
	private VkbBuffer transferBuffer;

	/**
	 * Constructs a new worker for <i>compressor</i>
	 */
	public Bc1Worker(Bc1Compressor compressor) {
		this.compressor = compressor;
	}

	/**
	 * Records commands to compress the RGBA data (1 byte per component) from the <i>source</i> range, and store the
	 * compressed data in <i>destination</i>.
	 * @param recorder The command recorder onto which the compute commands and transfer commands will be recorded
	 * @param descriptorSet The descriptor set. It must have the <i>descriptorSetLayout</i> of the <i>Bc1Compressor</i>.
	 *                      This method will call <i>vkUpdateDescriptorSets</i>, so you can't reuse it until the
	 *                      recorded commands have completed execution.
	 * @param source The source buffer range containing the RGBA8 image data
	 * @param destination The destination image with BC1 format, must be in <i>VK_IMAGE_LAYOUT_TRANSFER_DST_OPTIMAL</i>
	 */
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

	/**
	 * Records commands to compress the RGBA data (1 byte per component) from the <i>source</i> range, and store the
	 * compressed data in <i>destination</i>. The {@link VkbBufferRange#offset()} of all buffer ranges must be a
	 * multiple of {@link org.lwjgl.vulkan.VkPhysicalDeviceLimits#minStorageBufferOffsetAlignment}
	 * @param recorder The command recorder onto which the compute command will be recorded
	 * @param descriptorSet The descriptor set. It must have the <i>descriptorSetLayout</i> of the <i>Bc1Compressor</i>.
	 *                      This method will call <i>vkUpdateDescriptorSets</i>, so you can't reuse it until the
	 *                      recorded commands have completed execution.
	 * @param source The source buffer range containing the RGBA8 image data
	 * @param destination The destination buffer range to which the resulting BC1 image data will be written.
	 * @param width The width of the image, in pixels
	 * @param height The height of the image, in pixels
	 */
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

	/**
	 * Destroys this worker. You should do this before destroying the <i>Bc1Compressor</i>.
	 */
	public void destroy() {
		if (transferBuffer != null) transferBuffer.destroy(compressor.boiler);
	}
}
