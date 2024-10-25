package com.github.knokko.compressor;

import com.github.knokko.boiler.BoilerInstance;
import com.github.knokko.boiler.buffers.VkbBuffer;
import com.github.knokko.boiler.builders.BoilerBuilder;
import com.github.knokko.boiler.builders.WindowBuilder;
import com.github.knokko.boiler.commands.CommandRecorder;
import com.github.knokko.boiler.commands.SingleTimeCommands;
import com.github.knokko.boiler.descriptors.HomogeneousDescriptorPool;
import com.github.knokko.boiler.descriptors.VkbDescriptorSetLayout;
import com.github.knokko.boiler.images.VkbImage;
import com.github.knokko.boiler.pipelines.GraphicsPipelineBuilder;
import com.github.knokko.boiler.pipelines.ShaderInfo;
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

import static java.lang.Math.toIntExact;
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

		var bc1Compressor = new Bc1Compressor(boiler);
		var bc1Worker = new Bc1Worker(bc1Compressor);
		var bc1CompressorDescriptorSet = bc1Compressor.descriptorBank.borrowDescriptorSet("Bc1Image");

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
		var sourceBuffer = boiler.buffers.createMapped(
				4L * sourceImage.getWidth() * sourceImage.getHeight(),
				VK_BUFFER_USAGE_STORAGE_BUFFER_BIT | VK_BUFFER_USAGE_TRANSFER_SRC_BIT,
				"Bc1Source"
		);
		boiler.buffers.encodeBufferedImageRGBA(sourceBuffer, sourceImage, 0);
		var kimCompressor = new Kim1Compressor(
				sourceBuffer.fullMappedRange().byteBuffer(), sourceImage.getWidth(), sourceImage.getHeight(), 4
		);
		var kimBuffer = boiler.buffers.createMapped(
				4L * kimCompressor.intSize, VK_BUFFER_USAGE_STORAGE_BUFFER_BIT, "Kim1Buffer"
		);
		kimCompressor.compress(kimBuffer.fullMappedRange().byteBuffer());

		var commands = new SingleTimeCommands(boiler);
		commands.submit("Bc1Upload", recorder -> {
			recorder.transitionLayout(originalImage, null, ResourceUsage.TRANSFER_DEST);
			recorder.transitionLayout(bc1Image, null, ResourceUsage.TRANSFER_DEST);

			recorder.copyBufferToImage(originalImage, sourceBuffer.fullRange());
			bc1Worker.compress(recorder, bc1CompressorDescriptorSet, sourceBuffer.fullRange(), bc1Image);

			recorder.transitionLayout(
					originalImage, ResourceUsage.TRANSFER_DEST,
					ResourceUsage.shaderRead(VK_PIPELINE_STAGE_FRAGMENT_SHADER_BIT)
			);
			recorder.transitionLayout(
					bc1Image, ResourceUsage.TRANSFER_DEST,
					ResourceUsage.shaderRead(VK_PIPELINE_STAGE_FRAGMENT_SHADER_BIT)
			);
		});
		commands.destroy();

		sourceBuffer.destroy(boiler);
		bc1Worker.destroy();
		bc1Compressor.descriptorBank.returnDescriptorSet(bc1CompressorDescriptorSet);
		bc1Compressor.destroy(true);

		var eventLoop = new WindowEventLoop();
		eventLoop.addWindow(new ManualTesting(boiler.window(), originalImage, bc1Image, kimBuffer));
		eventLoop.runMain();

		boiler.destroyInitialObjects();
	}

	ManualTesting(VkbWindow window, VkbImage originalImage, VkbImage bc1Image, VkbBuffer kimBuffer) {
		super(
				window, 1, true, VK_PRESENT_MODE_FIFO_KHR,
				ResourceUsage.COLOR_ATTACHMENT_WRITE, ResourceUsage.COLOR_ATTACHMENT_WRITE
		);
		this.originalImage = originalImage;
		this.bc1Image = bc1Image;
		this.kimBuffer = kimBuffer;
	}

	private final VkbImage originalImage, bc1Image;
	private final VkbBuffer kimBuffer;
	private long sampler;
	private VkbDescriptorSetLayout descriptorSetLayout, kimDescriptorSetLayout;
	private HomogeneousDescriptorPool descriptorPool, kimDescriptorPool;
	private long descriptorSet, kimDescriptorSet;
	private long pipelineLayout, kimPipelineLayout, graphicsPipeline, kimPipeline;

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

		var kimBindings = VkDescriptorSetLayoutBinding.calloc(1, stack);
		boiler.descriptors.binding(kimBindings, 0, VK_DESCRIPTOR_TYPE_STORAGE_BUFFER, VK_SHADER_STAGE_FRAGMENT_BIT);

		this.kimDescriptorSetLayout = boiler.descriptors.createLayout(stack, kimBindings, "KimDescriptorSetLayout");
		this.kimDescriptorPool = kimDescriptorSetLayout.createPool(1, 0, "KimPool");
		this.kimDescriptorSet = kimDescriptorPool.allocate(1)[0];

		var writeImages = VkDescriptorImageInfo.calloc(2, stack);
		for (int index = 0; index < 2; index++) {
			writeImages.get(index).imageLayout(VK_IMAGE_LAYOUT_SHADER_READ_ONLY_OPTIMAL);
		}
		writeImages.get(0).imageView(originalImage.vkImageView());
		writeImages.get(1).imageView(bc1Image.vkImageView());

		var writeSamplers = VkDescriptorImageInfo.calloc(1, stack);
		writeSamplers.sampler(sampler);

		var descriptorWrites = VkWriteDescriptorSet.calloc(3, stack);
		boiler.descriptors.writeImage(descriptorWrites, descriptorSet, 0, VK_DESCRIPTOR_TYPE_SAMPLED_IMAGE, writeImages);
		boiler.descriptors.writeImage(descriptorWrites, descriptorSet, 1, VK_DESCRIPTOR_TYPE_SAMPLER, writeSamplers);
		boiler.descriptors.writeBuffer(stack, descriptorWrites, kimDescriptorSet, 2, VK_DESCRIPTOR_TYPE_STORAGE_BUFFER, kimBuffer.fullRange());
		descriptorWrites.get(2).dstBinding(0);

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
		this.kimPipelineLayout = boiler.pipelines.createLayout(
				pushConstants, "KimLayout", kimDescriptorSetLayout.vkDescriptorSetLayout
		);

		this.graphicsPipeline = buildCorePipeline(boiler, stack);
		this.kimPipeline = buildKimPipeline(boiler, stack);
	}

	private long buildCorePipeline(BoilerInstance boiler, MemoryStack stack) {
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
		return builder.build("ShowcasePipeline");
	}

	private long buildKimPipeline(BoilerInstance boiler, MemoryStack stack) {
		var vertexModule = boiler.pipelines.createShaderModule(
				"com/github/knokko/compressor/showcase.vert.spv", "KimVertexShader"
		);
		var fragmentModule = boiler.pipelines.createShaderModule(
				"com/github/knokko/compressor/kim1.frag.spv", "KimFragmentShader"
		);

		var specializationMappings = VkSpecializationMapEntry.calloc(1, stack);
		specializationMappings.get(0).set(0, 0, 4);

		var specializationInfo = VkSpecializationInfo.calloc(stack);
		specializationInfo.pMapEntries(specializationMappings);
		specializationInfo.pData(stack.calloc(4).putInt(0, toIntExact(kimBuffer.size() / 4)));

		var builder = new GraphicsPipelineBuilder(boiler, stack);
		builder.shaderStages(
				new ShaderInfo(VK_SHADER_STAGE_VERTEX_BIT, vertexModule, null),
				new ShaderInfo(VK_SHADER_STAGE_FRAGMENT_BIT, fragmentModule, specializationInfo)
		);
		builder.noVertexInput();
		builder.simpleInputAssembly();
		builder.dynamicViewports(1);
		builder.simpleRasterization(VK_CULL_MODE_NONE);
		builder.noMultisampling();
		builder.noDepthStencil();
		builder.simpleColorBlending(1);
		builder.dynamicStates(VK_DYNAMIC_STATE_SCISSOR, VK_DYNAMIC_STATE_VIEWPORT);
		builder.ciPipeline.layout(kimPipelineLayout);
		builder.dynamicRendering(0, VK_FORMAT_UNDEFINED, VK_FORMAT_UNDEFINED, window.surfaceFormat);
		long result = builder.build("KimPipeline");

		vkDestroyShaderModule(boiler.vkDevice(), vertexModule, null);
		vkDestroyShaderModule(boiler.vkDevice(), fragmentModule, null);

		return result;
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
		recorder.dynamicViewportAndScissor(acquiredImage.width(), acquiredImage.height());

		vkCmdBindPipeline(recorder.commandBuffer, VK_PIPELINE_BIND_POINT_GRAPHICS, graphicsPipeline);
		recorder.bindGraphicsDescriptors(pipelineLayout, descriptorSet);

		var vertexPushConstants = stack.callocFloat(2);
		var fragmentPushConstants = stack.callocInt(1);
		drawQuad(recorder.commandBuffer, vertexPushConstants, fragmentPushConstants, -0.9f, -0.9f, 0, pipelineLayout);
		drawQuad(recorder.commandBuffer, vertexPushConstants, fragmentPushConstants, 0.1f, -0.9f, 1, pipelineLayout);

		vkCmdBindPipeline(recorder.commandBuffer, VK_PIPELINE_BIND_POINT_GRAPHICS, kimPipeline);
		recorder.bindGraphicsDescriptors(kimPipelineLayout, kimDescriptorSet);
		drawQuad(recorder.commandBuffer, vertexPushConstants, fragmentPushConstants, 0.1f, 0.1f, 0, kimPipelineLayout);

		recorder.endDynamicRendering();
	}

	private void drawQuad(
			VkCommandBuffer commandBuffer, FloatBuffer vertexPushConstants, IntBuffer fragmentPushConstants,
			float offsetX, float offsetY, int imageIndex, long pipelineLayout
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
		kimDescriptorPool.destroy();
		descriptorSetLayout.destroy();
		kimDescriptorSetLayout.destroy();
		vkDestroySampler(boiler.vkDevice(), sampler, null);
		vkDestroyPipeline(boiler.vkDevice(), graphicsPipeline, null);
		vkDestroyPipeline(boiler.vkDevice(), kimPipeline, null);
		vkDestroyPipelineLayout(boiler.vkDevice(), pipelineLayout, null);
		vkDestroyPipelineLayout(boiler.vkDevice(), kimPipelineLayout, null);
		originalImage.destroy(boiler);
		bc1Image.destroy(boiler);
		kimBuffer.destroy(boiler);
	}
}
