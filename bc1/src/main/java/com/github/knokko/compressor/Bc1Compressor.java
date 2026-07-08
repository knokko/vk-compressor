package com.github.knokko.compressor;

import com.github.knokko.boiler.BoilerInstance;
import com.github.knokko.boiler.buffers.MappedVkbBuffer;
import com.github.knokko.boiler.buffers.VkbBuffer;
import com.github.knokko.boiler.builders.BoilerBuilder;
import com.github.knokko.boiler.commands.CommandRecorder;
import com.github.knokko.boiler.commands.SingleTimeCommands;
import com.github.knokko.boiler.descriptors.DescriptorCombiner;
import com.github.knokko.boiler.descriptors.DescriptorSetLayoutBuilder;
import com.github.knokko.boiler.descriptors.DescriptorUpdater;
import com.github.knokko.boiler.descriptors.VkbDescriptorSetLayout;
import com.github.knokko.boiler.memory.MemoryCombiner;
import com.github.knokko.boiler.memory.callbacks.CallbackUserData;
import com.github.knokko.boiler.synchronization.ResourceUsage;
import com.github.knokko.boiler.utilities.ImageCoding;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.vulkan.VkPushConstantRange;

import java.awt.image.BufferedImage;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.function.Consumer;

import static com.github.knokko.boiler.utilities.BoilerMath.nextMultipleOf;
import static java.lang.Math.min;
import static org.lwjgl.system.MemoryStack.stackPush;
import static org.lwjgl.system.MemoryUtil.memCalloc;
import static org.lwjgl.system.MemoryUtil.memFree;
import static org.lwjgl.vulkan.VK10.*;

/**
 * <p>
 *     This class manages a BC1 compressor that uses a Vulkan compute shader.
 *     It supports the implicit 1-bit alpha channel.
 * </p>
 *
 * <ul>
 *     <li>The static methods are the easiest way to use this class.</li>
 *     <li>
 *         Alternatively, you can use the constructor and instance methods.
 *         These are more complicated, but can have massive performance benefits when the input images are already
 *         in video memory.
 *     </li>
 * </ul>
 */
public class Bc1Compressor {

	/**
	 * Compresses {@code imageData.length} images to BC1, and invokes {@code resultCallback} with the compressed data,
	 * for each of the images.
	 * <b>
	 *     The buffers passed to <i>resultCallback</i> must not be used anymore after the callback returns.
	 *     If you want to process it later, you must copy it to some other buffer before returning from the callback.
	 * </b>
	 * @param imageData The image data to be compressed, in RGBA format. The length of {@code imageData[i]} must be
	 *                  {@code 4 * widths[i] * heights[i]}. The Red component of the pixel at {@code (x, y)} must be
	 *                  stored at {@code 4 * (x + y * widths[i])}. The Green component of each pixel must be stored
	 *                  right after the Red component. The Blue component must be stored right after the Green component.
	 *                  The Alpha component must be stored right after the Blue component.
	 * @param widths The width of {@code imageData[i]} is {@code widths[i]}.
	 *               It must hold that {@code imageData.length == widths.length}.
	 * @param heights The height of {@code imageData[i]} is {@code heights[i]}.
	 *                It must hold that {@code imageData.length == heights.length}.
	 * @param boiler The {@link BoilerInstance} that will be used for all GPU-related operations.
	 * @param resultCallback This callback will be invoked once before this method returns.
	 *                       The compressed data of {@code imageData[i]} will be passed to the
	 *                       ByteBuffer at index {@code i}.
	 */
	public static void compressRgbaImageData(
			ByteBuffer[] imageData, int[] widths, int[] heights,
			BoilerInstance boiler, Consumer<ByteBuffer[]> resultCallback
	) {
		var combiner = new MemoryCombiner(boiler, "CompressorMemory");
		var stagingCombiner = new MemoryCombiner(boiler, "Staging");
		var compressor = new Bc1Compressor(boiler);

		var descriptorCombiner = new DescriptorCombiner(boiler);
		var descriptorSets = descriptorCombiner.addMultiple(compressor.descriptorSetLayout, imageData.length);
		var descriptorPool = descriptorCombiner.build("CompressorDescriptors");

		var sourceTransferBuffers = new MappedVkbBuffer[imageData.length];
		var sourceBuffers = new VkbBuffer[imageData.length];
		var destinationBuffers = new VkbBuffer[imageData.length];
		var destinationTransferBuffers = new MappedVkbBuffer[imageData.length];
		long storageAlignment = boiler.deviceProperties.limits().minStorageBufferOffsetAlignment();

		for (int index = 0; index < imageData.length; index++) {
			int width = nextMultipleOf(widths[index], 4);
			int height = nextMultipleOf(heights[index], 4);

			sourceTransferBuffers[index] = stagingCombiner.addMappedBuffer(
					4L * width * height, 4L, VK_BUFFER_USAGE_TRANSFER_SRC_BIT
			);
			sourceBuffers[index] = combiner.addBuffer(
					4L * width * height, storageAlignment,
					VK_BUFFER_USAGE_STORAGE_BUFFER_BIT | VK_BUFFER_USAGE_TRANSFER_DST_BIT, 1f
			);
			destinationBuffers[index] = combiner.addBuffer(
					(long) width * height / 2, storageAlignment,
					VK_BUFFER_USAGE_STORAGE_BUFFER_BIT | VK_BUFFER_USAGE_TRANSFER_SRC_BIT, 1f
			);
			destinationTransferBuffers[index] = combiner.addMappedBuffer(
					(long) width * height / 2, storageAlignment, VK_BUFFER_USAGE_TRANSFER_DST_BIT
			);
		}

		var memory = combiner.build(false);
		var stagingMemory = stagingCombiner.build(false);

		for (int index = 0; index < imageData.length; index++) {
			int width = widths[index];
			int paddedWidth = nextMultipleOf(width, 4);
			int height = heights[index];
			int paddedHeight = nextMultipleOf(height, 4);
			var image = imageData[index];
			var sourceBuffer = sourceTransferBuffers[index].byteBuffer();
			if (width == paddedWidth && height == paddedHeight) {
				sourceBuffer.put(0, image, 0, image.capacity());
			} else {
				for (int y = 0; y < paddedHeight; y++) {
					int imageY = min(y, height - 1);
					sourceBuffer.put(4 * y * paddedWidth, image, 4 * imageY * width, 4 * width);
					for (int x = width; x < paddedWidth; x++) {
						sourceBuffer.putInt(4 * (y * paddedWidth + x), image.getInt(4 * (imageY * width + width - 1)));
					}
				}
			}
		}

		var commands = new SingleTimeCommands(boiler);
		commands.submit("StagingTransfer", recorder -> {
			recorder.bulkCopyBuffers(sourceTransferBuffers, sourceBuffers);
			recorder.bulkBufferBarrier(ResourceUsage.TRANSFER_DEST, ResourceUsage.computeBuffer(VK_ACCESS_SHADER_READ_BIT), sourceBuffers);
		}).awaitCompletion();
		stagingMemory.destroy(boiler);

		commands.submit("Bc1Compression", recorder -> {
			compressor.bindPipeline(recorder);
			for (int index = 0; index < imageData.length; index++) {
				compressor.compress(
						recorder, descriptorSets[index], sourceBuffers[index], destinationBuffers[index],
						nextMultipleOf(widths[index], 4), nextMultipleOf(heights[index], 4)
				);
			}
			recorder.bulkBufferBarrier(
					ResourceUsage.computeBuffer(VK_ACCESS_SHADER_WRITE_BIT),
					ResourceUsage.TRANSFER_SOURCE, destinationBuffers
			);
			recorder.bulkCopyBuffers(destinationBuffers, destinationTransferBuffers);
			recorder.bulkBufferBarrier(ResourceUsage.TRANSFER_DEST, ResourceUsage.HOST_READ, destinationTransferBuffers);
		});
		commands.destroy();

		var resultByteBuffers = new ByteBuffer[imageData.length];
		for (int index = 0; index < imageData.length; index++) {
			resultByteBuffers[index] = destinationTransferBuffers[index].byteBuffer();
		}
		resultCallback.accept(resultByteBuffers);

		vkDestroyDescriptorPool(boiler.vkDevice(), descriptorPool, null);
		compressor.destroy();
		memory.destroy(boiler);
	}

	/**
	 * Compresses {@code imageData.length} images to BC1, and invokes {@code resultCallback} with the compressed data,
	 * for each of the images.
	 * <b>
	 *     The buffers passed to <i>resultCallback</i> must not be used anymore after the callback returns.
	 *     If you want to process it later, you must copy it to some other buffer before returning from the callback.
	 * </b>
	 * @param imageData The image data to be compressed, in RGBA format. The length of {@code imageData[i]} must be
	 *                  {@code 4 * widths[i] * heights[i]}. The Red component of the pixel at {@code (x, y)} must be
	 *                  stored at {@code 4 * (x + y * widths[i])}. The Green component of each pixel must be stored
	 *                  right after the Red component. The Blue component must be stored right after the Green component.
	 *                  The Alpha component must be stored right after the Blue component.
	 * @param widths The width of {@code imageData[i]} is {@code widths[i]}.
	 *               It must hold that {@code imageData.length == widths.length}.
	 * @param heights The height of {@code imageData[i]} is {@code heights[i]}.
	 *                It must hold that {@code imageData.length == heights.length}.
	 * @param resultCallback This callback will be invoked once before this method returns.
	 *                       The compressed data of {@code imageData[i]} will be passed to the
	 *                       ByteBuffer at index {@code i}.
	 */
	public static void compressRgbaImageDataSimple(
			ByteBuffer[] imageData, int[] widths, int[] heights, Consumer<ByteBuffer[]> resultCallback
	) {
		var boiler = new BoilerBuilder(
				VK_API_VERSION_1_0, "Bc1Compressor", 1
		).doNotUseVma().build();
		compressRgbaImageData(imageData, widths, heights, boiler, resultCallback);
		boiler.destroyInitialObjects();
	}

	/**
	 * Compresses all {@code inputImages} to BC1, and invokes {@code resultCallback} with the compressed data,
	 * for each of the images.
	 * <b>
	 *     The buffers passed to <i>resultCallback</i> must not be used anymore after the callback returns.
	 *     If you want to process it later, you must copy it to some other buffer before returning from the callback.
	 * </b>
	 * @param inputImages The images to be compressed
	 * @param boiler The {@link BoilerInstance} that will be used for all GPU-related operations.
	 * @param resultCallback This callback will be invoked once before this method returns.
	 *                       The compressed data of {@code inputImages[i]} will be passed to the
	 *                       ByteBuffer at index {@code i}.
	 */
	public static void compressBufferedImages(
			BufferedImage[] inputImages, BoilerInstance boiler, Consumer<ByteBuffer[]> resultCallback
	) {
		ByteBuffer[] imageData = new ByteBuffer[inputImages.length];
		int[] widths = new int[inputImages.length];
		int[] heights = new int[inputImages.length];
		for (int index = 0; index < inputImages.length; index++) {
			var image = inputImages[index];
			var buffer = memCalloc(4 * image.getWidth() * image.getHeight());
			ImageCoding.encodeBufferedImage(buffer, image);
			buffer.flip();

			imageData[index] = buffer;
			widths[index] = image.getWidth();
			heights[index] = image.getHeight();
		}

		compressRgbaImageData(imageData, widths, heights, boiler, resultCallback);
		for (var imageBuffer : imageData) memFree(imageBuffer);
	}

	/**
	 * Compresses all {@code inputImages} to BC1, and invokes {@code resultCallback} with the compressed data,
	 * for each of the images.
	 * <b>
	 *     The buffers passed to <i>resultCallback</i> must not be used anymore after the callback returns.
	 *     If you want to process it later, you must copy it to some other buffer before returning from the callback.
	 * </b>
	 * @param inputImages The images to be compressed
	 * @param resultCallback This callback will be invoked once before this method returns.
	 *                       The compressed data of {@code inputImages[i]} will be passed to the
	 *                       ByteBuffer at index {@code i}.
	 */
	public static void compressBufferedImagesSimple(
			BufferedImage[] inputImages, Consumer<ByteBuffer[]> resultCallback
	) {
		var boiler = new BoilerBuilder(
				VK_API_VERSION_1_0, "Bc1Compressor", 1
		).doNotUseVma().build();
		compressBufferedImages(inputImages, boiler, resultCallback);
		boiler.destroyInitialObjects();
	}

	final BoilerInstance boiler;
	/**
	 * All <i>descriptorSet</i>s passed to the <i>compress</i> methods of <i>Bc1Worker</i> should have this layout.
	 */
	public final VkbDescriptorSetLayout descriptorSetLayout;

	final long pipelineLayout;
	final long pipeline;

	private boolean calledBindPipeline = false;

	/**
	 * Constructs a new <i>Bc1Compressor</i> using the given <i>BoilerInstance</i>. You should normally only need 1
	 * <i>Bc1Compressor</i> instance.
	 */
	public Bc1Compressor(BoilerInstance boiler) {
		this.boiler = boiler;
		try (var stack = stackPush()) {
			var builder = new DescriptorSetLayoutBuilder(stack, 2);
			for (int index = 0; index < 2; index++) {
				builder.set(index, index, VK_DESCRIPTOR_TYPE_STORAGE_BUFFER, VK_SHADER_STAGE_COMPUTE_BIT);
			}
			this.descriptorSetLayout = builder.build(boiler, "Bc1CompressorDescriptorSetLayout");

			var pushConstants = VkPushConstantRange.calloc(1, stack);
			//noinspection resource
			pushConstants.get(0).set(VK_SHADER_STAGE_COMPUTE_BIT, 0, 12);
			this.pipelineLayout = boiler.pipelines.createLayout(
					pushConstants, "Bc1CompressorPipelineLayout",
					descriptorSetLayout.vkDescriptorSetLayout
			);

			this.pipeline = boiler.pipelines.createComputePipeline(
					pipelineLayout, "com/github/knokko/compressor/bc1.spv", "Bc1Compressor"
			);
		}
	}

	/**
	 * Calls <i>vkCmdBindPipeline</i> to bind the bc1 compute pipeline.
	 * You must call this before calling {@link #compress}.
	 */
	public void bindPipeline(CommandRecorder recorder) {
		vkCmdBindPipeline(recorder.commandBuffer, VK_PIPELINE_BIND_POINT_COMPUTE, pipeline);
		calledBindPipeline = true;
	}

	/**
	 * Records commands to compress the RGBA data (1 byte per component) from the <i>source</i> buffer, and store the
	 * compressed data in <i>destination</i>. The {@link VkbBuffer#offset} of all buffers must be a
	 * multiple of {@link org.lwjgl.vulkan.VkPhysicalDeviceLimits#minStorageBufferOffsetAlignment}
	 * @param recorder The command recorder onto which the compute command will be recorded
	 * @param descriptorSet The descriptor set. It must have the <i>descriptorSetLayout</i> of the <i>Bc1Compressor</i>.
	 *                      This method will call <i>vkUpdateDescriptorSets</i>, so you can't reuse it until the
	 *                      recorded commands have completed execution.
	 * @param source The source buffer containing the RGBA8 image data
	 * @param destination The destination buffer to which the resulting BC1 image data will be written.
	 * @param width The width of the image, in pixels
	 * @param height The height of the image, in pixels
	 */
	public void compress(
			CommandRecorder recorder, long descriptorSet, VkbBuffer source,
			VkbBuffer destination, int width, int height
	) {
		if (width % 4 != 0 || height % 4 != 0) {
			throw new IllegalArgumentException("Width (" + width + ") and height (" + height + ") must be a multiple of 4");
		}
		if (4L * width * height > source.size) throw new IllegalArgumentException("Source buffer is too small");
		if ((long) width * height / 2 > destination.size) throw new IllegalArgumentException("Destination buffer is too small");
		if (!calledBindPipeline) throw new IllegalStateException("You need to call this.bindPipeline() first");

		try (MemoryStack stack = stackPush()) {
			var updater = new DescriptorUpdater(stack, 2);
			updater.writeStorageBuffer(0, descriptorSet, 0, source);
			updater.writeStorageBuffer(1, descriptorSet, 1, destination);
			updater.update(boiler);

			recorder.bindComputeDescriptors(pipelineLayout, descriptorSet);
			int bigEndian = ByteOrder.nativeOrder() == ByteOrder.BIG_ENDIAN ? VK_TRUE : VK_FALSE;
			vkCmdPushConstants(
					recorder.commandBuffer, pipelineLayout,
					VK_SHADER_STAGE_COMPUTE_BIT, 0, stack.ints(bigEndian, width, height)
			);
		}

		int numBlocksX = width / 4;
		int numBlocksY = height / 4;
		int groupSize = 8;
		int numGroupsX = nextMultipleOf(numBlocksX, groupSize) / groupSize;
		int numGroupsY = nextMultipleOf(numBlocksY, groupSize) / groupSize;
		vkCmdDispatch(recorder.commandBuffer, numGroupsX, numGroupsY, 1);
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
