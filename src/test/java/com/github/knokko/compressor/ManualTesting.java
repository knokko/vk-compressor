package com.github.knokko.compressor;

import com.github.knokko.boiler.BoilerInstance;
import com.github.knokko.boiler.builders.BoilerBuilder;
import com.github.knokko.boiler.builders.WindowBuilder;
import com.github.knokko.boiler.commands.CommandRecorder;
import com.github.knokko.boiler.descriptors.HomogeneousDescriptorPool;
import com.github.knokko.boiler.descriptors.VkbDescriptorSetLayout;
import com.github.knokko.boiler.images.VkbImage;
import com.github.knokko.boiler.pipelines.GraphicsPipelineBuilder;
import com.github.knokko.boiler.synchronization.ResourceUsage;
import com.github.knokko.boiler.window.AcquiredImage;
import com.github.knokko.boiler.window.SimpleWindowRenderLoop;
import com.github.knokko.boiler.window.VkbWindow;
import com.github.knokko.boiler.window.WindowEventLoop;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.vulkan.*;

import javax.imageio.ImageIO;

import java.io.IOException;
import java.nio.FloatBuffer;
import java.nio.IntBuffer;

import static org.lwjgl.system.MemoryStack.stackPush;
import static org.lwjgl.vulkan.KHRSurface.VK_PRESENT_MODE_FIFO_KHR;
import static org.lwjgl.vulkan.VK10.*;
import static org.lwjgl.vulkan.VK12.VK_API_VERSION_1_2;

public class ManualTesting extends SimpleWindowRenderLoop {

	public static void main(String[] args) throws IOException {
		var boiler = new BoilerBuilder(
				VK_API_VERSION_1_2, "HelloCompressor", 1
		)
				.validation()
				.enableDynamicRendering()
				.addWindow(new WindowBuilder(800, 500, VK_IMAGE_USAGE_COLOR_ATTACHMENT_BIT))
				.requiredFeatures10(VkPhysicalDeviceFeatures::textureCompressionBC)
				.featurePicker10(((stack, supportedFeatures, toEnable) -> toEnable.textureCompressionBC(true)))
				.build();

		var compressor = new Bc1Compressor(boiler);
		var worker = new Bc1Worker(compressor);
		var compressorDescriptorSet = compressor.descriptorBank.borrowDescriptorSet("Bc1Image");

		var sourceImage = ImageIO.read(ManualTesting.class.getResourceAsStream("mardek/Flametongue.png"));
		var bc1Image = boiler.images.createSimple(
				sourceImage.getWidth(), sourceImage.getHeight(), VK_FORMAT_BC1_RGBA_SRGB_BLOCK,
				VK_IMAGE_USAGE_TRANSFER_DST_BIT | VK_IMAGE_USAGE_SAMPLED_BIT,
				VK_IMAGE_ASPECT_COLOR_BIT, "Bc1Image"
		);
		var originalImage = boiler.images.createSimple(
				sourceImage.getWidth(), sourceImage.getHeight(), VK_FORMAT_R8G8B8A8_SRGB,
				VK_IMAGE_USAGE_TRANSFER_DST_BIT | VK_IMAGE_USAGE_SAMPLED_BIT,
				VK_IMAGE_ASPECT_COLOR_BIT, "OriginalImage"
		);
		try (var stack = stackPush()) {
			var sourceBuffer = boiler.buffers.createMapped(
					4L * sourceImage.getWidth() * sourceImage.getHeight(),
					VK_BUFFER_USAGE_STORAGE_BUFFER_BIT | VK_BUFFER_USAGE_TRANSFER_SRC_BIT,
					"Bc1Source"
			);
			boiler.buffers.encodeBufferedImageRGBA(sourceBuffer, sourceImage, 0);

			var commandPool = boiler.commands.createPool(0, boiler.queueFamilies().graphics().index(), "Bc1Pool");
			var commandBuffer = boiler.commands.createPrimaryBuffers(commandPool, 1, "Bc1Commands")[0];
			var recorder = CommandRecorder.begin(commandBuffer, boiler, stack, "Bc1Upload");
			recorder.transitionLayout(originalImage, null, ResourceUsage.TRANSFER_DEST);
			recorder.transitionLayout(bc1Image, null, ResourceUsage.TRANSFER_DEST);

			recorder.copyBufferToImage(originalImage, sourceBuffer.fullRange());
			worker.compress(recorder, compressorDescriptorSet, sourceBuffer.fullRange(), bc1Image);

			recorder.transitionLayout(
					originalImage, ResourceUsage.TRANSFER_DEST,
					ResourceUsage.shaderRead(VK_PIPELINE_STAGE_FRAGMENT_SHADER_BIT)
			);
			recorder.transitionLayout(
					bc1Image, ResourceUsage.TRANSFER_DEST,
					ResourceUsage.shaderRead(VK_PIPELINE_STAGE_FRAGMENT_SHADER_BIT)
			);
			recorder.end();

			var fence = boiler.sync.fenceBank.borrowFence(false, "Bc1CompressFence");

			boiler.queueFamilies().graphics().first().submit(commandBuffer, "Bc1Submit", null, fence);
			fence.awaitSignal();
			boiler.sync.fenceBank.returnFence(fence);

			vkDestroyCommandPool(boiler.vkDevice(), commandPool, null);
			sourceBuffer.destroy(boiler);
			worker.destroy();
			compressor.descriptorBank.returnDescriptorSet(compressorDescriptorSet);
			compressor.destroy(true);
		}

		var eventLoop = new WindowEventLoop();
		eventLoop.addWindow(new ManualTesting(boiler.window(), originalImage, bc1Image));
		eventLoop.runMain();

		boiler.destroyInitialObjects();
	}
	ManualTesting(VkbWindow window, VkbImage originalImage, VkbImage bc1Image) {
		super(
				window, 1, true, VK_PRESENT_MODE_FIFO_KHR,
				ResourceUsage.COLOR_ATTACHMENT_WRITE, ResourceUsage.COLOR_ATTACHMENT_WRITE
		);
		this.originalImage = originalImage;
		this.bc1Image = bc1Image;
	}

	private final VkbImage originalImage, bc1Image;
	private long sampler;
	private VkbDescriptorSetLayout descriptorSetLayout;
	private HomogeneousDescriptorPool descriptorPool;
	private long descriptorSet;
	private long pipelineLayout, graphicsPipeline;

	@SuppressWarnings("resource")
	@Override
	protected void setup(BoilerInstance boiler, MemoryStack stack) {
		super.setup(boiler, stack);

		this.sampler = boiler.images.createSimpleSampler(
				VK_FILTER_NEAREST, VK_SAMPLER_MIPMAP_MODE_NEAREST,
				VK_SAMPLER_ADDRESS_MODE_CLAMP_TO_BORDER, "Sampler"
		);

		var bindings = VkDescriptorSetLayoutBinding.calloc(2, stack);
		boiler.descriptors.binding(bindings, 0, VK_DESCRIPTOR_TYPE_SAMPLED_IMAGE, VK_SHADER_STAGE_FRAGMENT_BIT);
		bindings.get(0).descriptorCount(2);
		boiler.descriptors.binding(bindings, 1, VK_DESCRIPTOR_TYPE_SAMPLER, VK_SHADER_STAGE_FRAGMENT_BIT);

		this.descriptorSetLayout = boiler.descriptors.createLayout(stack, bindings, "DrawingDescriptorSetLayout");
		this.descriptorPool = descriptorSetLayout.createPool(1, 0, "ImagesPool");
		this.descriptorSet = descriptorPool.allocate(1)[0];

		var writeImages = VkDescriptorImageInfo.calloc(2, stack);
		for (int index = 0; index < 2; index++) {
			writeImages.get(index).imageLayout(VK_IMAGE_LAYOUT_SHADER_READ_ONLY_OPTIMAL);
		}
		writeImages.get(0).imageView(originalImage.vkImageView());
		writeImages.get(1).imageView(bc1Image.vkImageView());

		var writeSamplers = VkDescriptorImageInfo.calloc(1, stack);
		writeSamplers.sampler(sampler);

		var descriptorWrites = VkWriteDescriptorSet.calloc(2, stack);
		boiler.descriptors.writeImage(descriptorWrites, descriptorSet, 0, VK_DESCRIPTOR_TYPE_SAMPLED_IMAGE, writeImages);
		boiler.descriptors.writeImage(descriptorWrites, descriptorSet, 1, VK_DESCRIPTOR_TYPE_SAMPLER, writeSamplers);

		vkUpdateDescriptorSets(boiler.vkDevice(), descriptorWrites, null);

		var pushConstants = VkPushConstantRange.calloc(2, stack);
		var vertexPushConstants = pushConstants.get(0);
		vertexPushConstants.offset(0);
		vertexPushConstants.size(8);
		vertexPushConstants.stageFlags(VK_SHADER_STAGE_VERTEX_BIT);
		var fragmentPushConstants = pushConstants.get(1);
		fragmentPushConstants.offset(vertexPushConstants.size());
		fragmentPushConstants.size(4);
		fragmentPushConstants.stageFlags(VK_SHADER_STAGE_FRAGMENT_BIT);
		this.pipelineLayout = boiler.pipelines.createLayout(
				pushConstants, "ShowcaseLayout", descriptorSetLayout.vkDescriptorSetLayout
		);

		var builder = new GraphicsPipelineBuilder(boiler, stack);
		builder.simpleShaderStages(
				"ShowcasePipeline",
				"com/github/knokko/compressor/showcase.vert.spv",
				"com/github/knokko/compressor/showcase.frag.spv"
		);
		builder.noVertexInput();
		builder.simpleInputAssembly();
		builder.dynamicViewports(1);
		builder.simpleRasterization(VK_CULL_MODE_NONE);
		builder.noMultisampling();
		builder.noDepthStencil();
		builder.simpleColorBlending(1);
		builder.dynamicStates(VK_DYNAMIC_STATE_SCISSOR, VK_DYNAMIC_STATE_VIEWPORT);
		builder.ciPipeline.layout(pipelineLayout);
		builder.dynamicRendering(0, VK_FORMAT_UNDEFINED, VK_FORMAT_UNDEFINED, window.surfaceFormat);
		this.graphicsPipeline = builder.build("ShowcasePipeline");
	}

	@Override
	protected void recordFrame(MemoryStack stack, int frameIndex, CommandRecorder recorder, AcquiredImage acquiredImage, BoilerInstance instance) {
		var colorAttachments = VkRenderingAttachmentInfo.calloc(1, stack);
		recorder.simpleColorRenderingAttachment(
				colorAttachments.get(0), acquiredImage.image().vkImageView(),
				VK_ATTACHMENT_LOAD_OP_CLEAR, VK_ATTACHMENT_STORE_OP_STORE,
				0.2f, 0.5f, 0.7f, 1f
		);
		recorder.beginSimpleDynamicRendering(
				acquiredImage.width(), acquiredImage.height(),
				colorAttachments, null, null
		);
		vkCmdBindPipeline(recorder.commandBuffer, VK_PIPELINE_BIND_POINT_GRAPHICS, graphicsPipeline);
		recorder.dynamicViewportAndScissor(acquiredImage.width(), acquiredImage.height());
		vkCmdBindDescriptorSets(
				recorder.commandBuffer, VK_PIPELINE_BIND_POINT_GRAPHICS, pipelineLayout,
				0, stack.longs(descriptorSet), null
		);

		var vertexPushConstants = stack.callocFloat(2);
		var fragmentPushConstants = stack.callocInt(1);
		drawQuad(recorder.commandBuffer, vertexPushConstants, fragmentPushConstants, -0.9f, -0.4f, 0);
		drawQuad(recorder.commandBuffer, vertexPushConstants, fragmentPushConstants, 0.1f, -0.4f, 1);
		recorder.endDynamicRendering();
	}

	private void drawQuad(
			VkCommandBuffer commandBuffer, FloatBuffer vertexPushConstants, IntBuffer fragmentPushConstants,
			float offsetX, float offsetY, int imageIndex
	) {
		vertexPushConstants.put(0, offsetX).put(1, offsetY);
		vkCmdPushConstants(commandBuffer, pipelineLayout, VK_SHADER_STAGE_VERTEX_BIT, 0, vertexPushConstants);

		fragmentPushConstants.put(0, imageIndex);
		vkCmdPushConstants(commandBuffer, pipelineLayout, VK_SHADER_STAGE_FRAGMENT_BIT, 8, fragmentPushConstants);

		vkCmdDraw(commandBuffer, 6, 1, 0, 0);
	}

	@Override
	protected void cleanUp(BoilerInstance boiler) {
		super.cleanUp(boiler);
		descriptorPool.destroy();
		descriptorSetLayout.destroy();
		vkDestroySampler(boiler.vkDevice(), sampler, null);
		vkDestroyPipeline(boiler.vkDevice(), graphicsPipeline, null);
		vkDestroyPipelineLayout(boiler.vkDevice(), pipelineLayout, null);
		originalImage.destroy(boiler);
		bc1Image.destroy(boiler);
	}
}
