package com.github.knokko.compressor;

import com.github.knokko.boiler.BoilerInstance;
import com.github.knokko.boiler.buffers.MappedVkbBuffer;
import com.github.knokko.boiler.buffers.VkbBuffer;
import com.github.knokko.boiler.buffers.VkbBufferRange;
import com.github.knokko.boiler.commands.CommandRecorder;
import com.github.knokko.boiler.images.VkbImage;
import com.github.knokko.boiler.synchronization.ResourceUsage;
import org.lwjgl.vulkan.VkWriteDescriptorSet;

import static org.lwjgl.system.MemoryUtil.memByteBuffer;
import static org.lwjgl.vulkan.VK10.*;

public class Bc1Worker {

	static byte[][] stb__OMatch5 = { // TODO Move this to some file/resource
			{ 0, 0 },   { 0, 0 },   { 0, 1 },   { 0, 1 },   { 1, 0 },   { 1, 0 },   { 1, 0 },   { 1, 1 },
			{ 1, 1 },   { 2, 0 },   { 2, 0 },   { 0, 4 },   { 2, 1 },   { 2, 1 },   { 2, 1 },   { 3, 0 },
			{ 3, 0 },   { 3, 0 },   { 3, 1 },   { 1, 5 },   { 3, 2 },   { 3, 2 },   { 4, 0 },   { 4, 0 },
			{ 4, 1 },   { 4, 1 },   { 4, 2 },   { 4, 2 },   { 4, 2 },   { 3, 5 },   { 5, 1 },   { 5, 1 },
			{ 5, 2 },   { 4, 4 },   { 5, 3 },   { 5, 3 },   { 5, 3 },   { 6, 2 },   { 6, 2 },   { 6, 2 },
			{ 6, 3 },   { 5, 5 },   { 6, 4 },   { 6, 4 },   { 4, 8 },   { 7, 3 },   { 7, 3 },   { 7, 3 },
			{ 7, 4 },   { 7, 4 },   { 7, 4 },   { 7, 5 },   { 5, 9 },   { 7, 6 },   { 7, 6 },   { 8, 4 },
			{ 8, 4 },   { 8, 5 },   { 8, 5 },   { 8, 6 },   { 8, 6 },   { 8, 6 },   { 7, 9 },   { 9, 5 },
			{ 9, 5 },   { 9, 6 },   { 8, 8 },   { 9, 7 },   { 9, 7 },   { 9, 7 },   { 10, 6 },  { 10, 6 },
			{ 10, 6 },  { 10, 7 },  { 9, 9 },   { 10, 8 },  { 10, 8 },  { 8, 12 },  { 11, 7 },  { 11, 7 },
			{ 11, 7 },  { 11, 8 },  { 11, 8 },  { 11, 8 },  { 11, 9 },  { 9, 13 },  { 11, 10 }, { 11, 10 },
			{ 12, 8 },  { 12, 8 },  { 12, 9 },  { 12, 9 },  { 12, 10 }, { 12, 10 }, { 12, 10 }, { 11, 13 },
			{ 13, 9 },  { 13, 9 },  { 13, 10 }, { 12, 12 }, { 13, 11 }, { 13, 11 }, { 13, 11 }, { 14, 10 },
			{ 14, 10 }, { 14, 10 }, { 14, 11 }, { 13, 13 }, { 14, 12 }, { 14, 12 }, { 12, 16 }, { 15, 11 },
			{ 15, 11 }, { 15, 11 }, { 15, 12 }, { 15, 12 }, { 15, 12 }, { 15, 13 }, { 13, 17 }, { 15, 14 },
			{ 15, 14 }, { 16, 12 }, { 16, 12 }, { 16, 13 }, { 16, 13 }, { 16, 14 }, { 16, 14 }, { 16, 14 },
			{ 15, 17 }, { 17, 13 }, { 17, 13 }, { 17, 14 }, { 16, 16 }, { 17, 15 }, { 17, 15 }, { 17, 15 },
			{ 18, 14 }, { 18, 14 }, { 18, 14 }, { 18, 15 }, { 17, 17 }, { 18, 16 }, { 18, 16 }, { 16, 20 },
			{ 19, 15 }, { 19, 15 }, { 19, 15 }, { 19, 16 }, { 19, 16 }, { 19, 16 }, { 19, 17 }, { 17, 21 },
			{ 19, 18 }, { 19, 18 }, { 20, 16 }, { 20, 16 }, { 20, 17 }, { 20, 17 }, { 20, 18 }, { 20, 18 },
			{ 20, 18 }, { 19, 21 }, { 21, 17 }, { 21, 17 }, { 21, 18 }, { 20, 20 }, { 21, 19 }, { 21, 19 },
			{ 21, 19 }, { 22, 18 }, { 22, 18 }, { 22, 18 }, { 22, 19 }, { 21, 21 }, { 22, 20 }, { 22, 20 },
			{ 20, 24 }, { 23, 19 }, { 23, 19 }, { 23, 19 }, { 23, 20 }, { 23, 20 }, { 23, 20 }, { 23, 21 },
			{ 21, 25 }, { 23, 22 }, { 23, 22 }, { 24, 20 }, { 24, 20 }, { 24, 21 }, { 24, 21 }, { 24, 22 },
			{ 24, 22 }, { 24, 22 }, { 23, 25 }, { 25, 21 }, { 25, 21 }, { 25, 22 }, { 24, 24 }, { 25, 23 },
			{ 25, 23 }, { 25, 23 }, { 26, 22 }, { 26, 22 }, { 26, 22 }, { 26, 23 }, { 25, 25 }, { 26, 24 },
			{ 26, 24 }, { 24, 28 }, { 27, 23 }, { 27, 23 }, { 27, 23 }, { 27, 24 }, { 27, 24 }, { 27, 24 },
			{ 27, 25 }, { 25, 29 }, { 27, 26 }, { 27, 26 }, { 28, 24 }, { 28, 24 }, { 28, 25 }, { 28, 25 },
			{ 28, 26 }, { 28, 26 }, { 28, 26 }, { 27, 29 }, { 29, 25 }, { 29, 25 }, { 29, 26 }, { 28, 28 },
			{ 29, 27 }, { 29, 27 }, { 29, 27 }, { 30, 26 }, { 30, 26 }, { 30, 26 }, { 30, 27 }, { 29, 29 },
			{ 30, 28 }, { 30, 28 }, { 30, 28 }, { 31, 27 }, { 31, 27 }, { 31, 27 }, { 31, 28 }, { 31, 28 },
			{ 31, 28 }, { 31, 29 }, { 31, 29 }, { 31, 30 }, { 31, 30 }, { 31, 30 }, { 31, 31 }, { 31, 31 }
	};

	static byte[][] stb__OMatch6 = {
			{ 0, 0 },   { 0, 1 },   { 1, 0 },   { 1, 0 },   { 1, 1 },   { 2, 0 },   { 2, 1 },   { 3, 0 },
			{ 3, 0 },   { 3, 1 },   { 4, 0 },   { 4, 0 },   { 4, 1 },   { 5, 0 },   { 5, 1 },   { 6, 0 },
			{ 6, 0 },   { 6, 1 },   { 7, 0 },   { 7, 0 },   { 7, 1 },   { 8, 0 },   { 8, 1 },   { 8, 1 },
			{ 8, 2 },   { 9, 1 },   { 9, 2 },   { 9, 2 },   { 9, 3 },   { 10, 2 },  { 10, 3 },  { 10, 3 },
			{ 10, 4 },  { 11, 3 },  { 11, 4 },  { 11, 4 },  { 11, 5 },  { 12, 4 },  { 12, 5 },  { 12, 5 },
			{ 12, 6 },  { 13, 5 },  { 13, 6 },  { 8, 16 },  { 13, 7 },  { 14, 6 },  { 14, 7 },  { 9, 17 },
			{ 14, 8 },  { 15, 7 },  { 15, 8 },  { 11, 16 }, { 15, 9 },  { 15, 10 }, { 16, 8 },  { 16, 9 },
			{ 16, 10 }, { 15, 13 }, { 17, 9 },  { 17, 10 }, { 17, 11 }, { 15, 16 }, { 18, 10 }, { 18, 11 },
			{ 18, 12 }, { 16, 16 }, { 19, 11 }, { 19, 12 }, { 19, 13 }, { 17, 17 }, { 20, 12 }, { 20, 13 },
			{ 20, 14 }, { 19, 16 }, { 21, 13 }, { 21, 14 }, { 21, 15 }, { 20, 17 }, { 22, 14 }, { 22, 15 },
			{ 25, 10 }, { 22, 16 }, { 23, 15 }, { 23, 16 }, { 26, 11 }, { 23, 17 }, { 24, 16 }, { 24, 17 },
			{ 27, 12 }, { 24, 18 }, { 25, 17 }, { 25, 18 }, { 28, 13 }, { 25, 19 }, { 26, 18 }, { 26, 19 },
			{ 29, 14 }, { 26, 20 }, { 27, 19 }, { 27, 20 }, { 30, 15 }, { 27, 21 }, { 28, 20 }, { 28, 21 },
			{ 28, 21 }, { 28, 22 }, { 29, 21 }, { 29, 22 }, { 24, 32 }, { 29, 23 }, { 30, 22 }, { 30, 23 },
			{ 25, 33 }, { 30, 24 }, { 31, 23 }, { 31, 24 }, { 27, 32 }, { 31, 25 }, { 31, 26 }, { 32, 24 },
			{ 32, 25 }, { 32, 26 }, { 31, 29 }, { 33, 25 }, { 33, 26 }, { 33, 27 }, { 31, 32 }, { 34, 26 },
			{ 34, 27 }, { 34, 28 }, { 32, 32 }, { 35, 27 }, { 35, 28 }, { 35, 29 }, { 33, 33 }, { 36, 28 },
			{ 36, 29 }, { 36, 30 }, { 35, 32 }, { 37, 29 }, { 37, 30 }, { 37, 31 }, { 36, 33 }, { 38, 30 },
			{ 38, 31 }, { 41, 26 }, { 38, 32 }, { 39, 31 }, { 39, 32 }, { 42, 27 }, { 39, 33 }, { 40, 32 },
			{ 40, 33 }, { 43, 28 }, { 40, 34 }, { 41, 33 }, { 41, 34 }, { 44, 29 }, { 41, 35 }, { 42, 34 },
			{ 42, 35 }, { 45, 30 }, { 42, 36 }, { 43, 35 }, { 43, 36 }, { 46, 31 }, { 43, 37 }, { 44, 36 },
			{ 44, 37 }, { 44, 37 }, { 44, 38 }, { 45, 37 }, { 45, 38 }, { 40, 48 }, { 45, 39 }, { 46, 38 },
			{ 46, 39 }, { 41, 49 }, { 46, 40 }, { 47, 39 }, { 47, 40 }, { 43, 48 }, { 47, 41 }, { 47, 42 },
			{ 48, 40 }, { 48, 41 }, { 48, 42 }, { 47, 45 }, { 49, 41 }, { 49, 42 }, { 49, 43 }, { 47, 48 },
			{ 50, 42 }, { 50, 43 }, { 50, 44 }, { 48, 48 }, { 51, 43 }, { 51, 44 }, { 51, 45 }, { 49, 49 },
			{ 52, 44 }, { 52, 45 }, { 52, 46 }, { 51, 48 }, { 53, 45 }, { 53, 46 }, { 53, 47 }, { 52, 49 },
			{ 54, 46 }, { 54, 47 }, { 57, 42 }, { 54, 48 }, { 55, 47 }, { 55, 48 }, { 58, 43 }, { 55, 49 },
			{ 56, 48 }, { 56, 49 }, { 59, 44 }, { 56, 50 }, { 57, 49 }, { 57, 50 }, { 60, 45 }, { 57, 51 },
			{ 58, 50 }, { 58, 51 }, { 61, 46 }, { 58, 52 }, { 59, 51 }, { 59, 52 }, { 62, 47 }, { 59, 53 },
			{ 60, 52 }, { 60, 53 }, { 60, 53 }, { 60, 54 }, { 61, 53 }, { 61, 54 }, { 61, 54 }, { 61, 55 },
			{ 62, 54 }, { 62, 55 }, { 62, 55 }, { 62, 56 }, { 63, 55 }, { 63, 56 }, { 63, 56 }, { 63, 57 },
			{ 63, 58 }, { 63, 59 }, { 63, 59 }, { 63, 60 }, { 63, 61 }, { 63, 62 }, { 63, 62 }, { 63, 63 }
	};

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
