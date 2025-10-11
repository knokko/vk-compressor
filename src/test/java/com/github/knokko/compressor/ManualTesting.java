package com.github.knokko.compressor;

import com.github.knokko.boiler.BoilerInstance;
import com.github.knokko.boiler.buffers.VkbBuffer;
import com.github.knokko.boiler.builders.BoilerBuilder;
import com.github.knokko.boiler.builders.WindowBuilder;
import com.github.knokko.boiler.commands.CommandRecorder;
import com.github.knokko.boiler.commands.SingleTimeCommands;
import com.github.knokko.boiler.descriptors.DescriptorCombiner;
import com.github.knokko.boiler.descriptors.DescriptorSetLayoutBuilder;
import com.github.knokko.boiler.descriptors.DescriptorUpdater;
import com.github.knokko.boiler.descriptors.VkbDescriptorSetLayout;
import com.github.knokko.boiler.images.ImageBuilder;
import com.github.knokko.boiler.images.VkbImage;
import com.github.knokko.boiler.memory.MemoryCombiner;
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

import java.awt.*;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.FloatBuffer;
import java.nio.IntBuffer;
import java.util.Objects;

import static java.lang.Math.toIntExact;
import static org.lwjgl.vulkan.KHRSurface.VK_PRESENT_MODE_FIFO_KHR;
import static org.lwjgl.vulkan.VK10.*;
import static org.lwjgl.vulkan.VK12.VK_API_VERSION_1_2;

public class ManualTesting extends SimpleWindowRenderLoop {

	public static void main(String[] args) throws IOException {
		var boiler = new BoilerBuilder(
				VK_API_VERSION_1_2, "HelloCompressor", 1
		)
				.validation().forbidValidationErrors()
				.enableDynamicRendering()
				.addWindow(new WindowBuilder(800, 500, VK_IMAGE_USAGE_COLOR_ATTACHMENT_BIT))
				.requiredFeatures10("BC compression", VkPhysicalDeviceFeatures::textureCompressionBC)
				.featurePicker10(((stack, supportedFeatures, toEnable) -> toEnable.textureCompressionBC(true)))
				.build();

		var sourceImage = ImageIO.read(Objects.requireNonNull(ManualTesting.class.getResourceAsStream("mardek/Flametongue.png")));

		var combiner = new MemoryCombiner(boiler, "PersistentMemory");
		var stagingCombiner = new MemoryCombiner(boiler, "CompressionMemory");
		var bc1Compressor = new Bc1Compressor(boiler, stagingCombiner, stagingCombiner);
		var bc4Compressor = new Bc4Compressor(boiler);
		var bc1Worker = new Bc1Worker(
				bc1Compressor, sourceImage.getWidth() * sourceImage.getHeight(), stagingCombiner
		);
		var bc4Worker = new Bc4Worker(
				bc4Compressor, sourceImage.getWidth() * sourceImage.getHeight(), stagingCombiner
		);

		var bc1Image = combiner.addImage(new ImageBuilder(
				"Bc1Image", sourceImage.getWidth(), sourceImage.getHeight()
		).texture().format(VK_FORMAT_BC1_RGBA_SRGB_BLOCK), 0.5f);
		var bc4Image = combiner.addImage(new ImageBuilder(
				"Bc4Image", sourceImage.getWidth(), sourceImage.getHeight()
		).texture().format(VK_FORMAT_BC4_UNORM_BLOCK), 0.5f);
		var originalImage = combiner.addImage(new ImageBuilder(
				"OriginalImage", sourceImage.getWidth(), sourceImage.getHeight()
		).texture(), 0.5f);
		var sourceBufferBc1 = stagingCombiner.addMappedBuffer(
				4L * sourceImage.getWidth() * sourceImage.getHeight(),
				boiler.deviceProperties.limits().minStorageBufferOffsetAlignment(),
				VK_BUFFER_USAGE_STORAGE_BUFFER_BIT | VK_BUFFER_USAGE_TRANSFER_SRC_BIT
		);
		var sourceBufferBc4 = stagingCombiner.addMappedBuffer(
				(long) sourceImage.getWidth() * sourceImage.getHeight(),
				boiler.deviceProperties.limits().minStorageBufferOffsetAlignment(),
				VK_BUFFER_USAGE_STORAGE_BUFFER_BIT | VK_BUFFER_USAGE_TRANSFER_SRC_BIT
		);
		var stagingMemory = stagingCombiner.build(false);

		sourceBufferBc1.encodeBufferedImage(sourceImage);
		ByteBuffer byteBufferBc4 = sourceBufferBc4.byteBuffer();
		for (int y = 0; y < sourceImage.getHeight(); y++) {
			for (int x = 0; x < sourceImage.getWidth(); x++) {
				Color pixel = new Color(sourceImage.getRGB(x, y));
				byteBufferBc4.put((byte) ((pixel.getRed() + pixel.getGreen() + pixel.getBlue()) / 3));
			}
		}
		var kimCompressor = new Kim1Compressor(
				sourceBufferBc1.byteBuffer(), sourceImage.getWidth(), sourceImage.getHeight(), 4
		);
		var kimBuffer = combiner.addMappedDeviceLocalBuffer(
				4L * kimCompressor.intSize,
				boiler.deviceProperties.limits().minStorageBufferOffsetAlignment(),
				VK_BUFFER_USAGE_STORAGE_BUFFER_BIT, 0.5f
		);

		var memory = combiner.build(false);
		kimCompressor.compress(kimBuffer.byteBuffer());

		var descriptorCombiner = new DescriptorCombiner(boiler);
		var descriptorSetBc1 = descriptorCombiner.addMultiple(bc1Compressor.descriptorSetLayout, 1);
		var descriptorSetBc4 = descriptorCombiner.addMultiple(bc4Compressor.descriptorSetLayout, 1);
		var descriptorPool = descriptorCombiner.build("CompressionDescriptors");

		var commands = new SingleTimeCommands(boiler);
		commands.submit("Bc1/4Upload", recorder -> {
			bc1Compressor.performStagingTransfer(recorder);
			recorder.bulkTransitionLayout(null, ResourceUsage.TRANSFER_DEST, originalImage, bc1Image, bc4Image);

			recorder.copyBufferToImage(originalImage, sourceBufferBc1);
			bc1Worker.compress(recorder, descriptorSetBc1[0], sourceBufferBc1, bc1Image);
			bc4Worker.compress(recorder, descriptorSetBc4[0], sourceBufferBc4, bc4Image);

			recorder.bulkTransitionLayout(
					ResourceUsage.TRANSFER_DEST,
					ResourceUsage.shaderRead(VK_PIPELINE_STAGE_FRAGMENT_SHADER_BIT),
					originalImage, bc1Image, bc4Image
			);
		});
		commands.destroy();

		stagingMemory.destroy(boiler);
		bc1Compressor.destroy();
		bc4Compressor.destroy();
		vkDestroyDescriptorPool(boiler.vkDevice(), descriptorPool, null);

		var eventLoop = new WindowEventLoop();
		eventLoop.addWindow(new ManualTesting(boiler.window(), originalImage, bc1Image, bc4Image, kimBuffer));
		eventLoop.runMain();

		memory.destroy(boiler);
		boiler.destroyInitialObjects();
	}

	ManualTesting(VkbWindow window, VkbImage originalImage, VkbImage bc1Image, VkbImage bc4Image, VkbBuffer kimBuffer) {
		super(
				window, true, VK_PRESENT_MODE_FIFO_KHR,
				ResourceUsage.COLOR_ATTACHMENT_WRITE, ResourceUsage.COLOR_ATTACHMENT_WRITE
		);
		this.originalImage = originalImage;
		this.bc1Image = bc1Image;
		this.bc4Image = bc4Image;
		this.kimBuffer = kimBuffer;
	}

	private final VkbImage originalImage, bc1Image, bc4Image;
	private final VkbBuffer kimBuffer;
	private long sampler;
	private VkbDescriptorSetLayout descriptorSetLayout, kimDescriptorSetLayout;
	private long descriptorPool, descriptorSet, kimDescriptorSet;
	private long pipelineLayout, kimPipelineLayout, graphicsPipeline, kimPipeline;

	@SuppressWarnings("resource")
	@Override
	protected void setup(BoilerInstance boiler, MemoryStack stack) {
		super.setup(boiler, stack);

		this.sampler = boiler.images.createSimpleSampler(
				VK_FILTER_NEAREST, VK_SAMPLER_MIPMAP_MODE_NEAREST,
				VK_SAMPLER_ADDRESS_MODE_CLAMP_TO_BORDER, "Sampler"
		);

		var builder = new DescriptorSetLayoutBuilder(stack, 2);
		builder.set(0, 0, VK_DESCRIPTOR_TYPE_SAMPLED_IMAGE, VK_SHADER_STAGE_FRAGMENT_BIT);
		Objects.requireNonNull(builder.ciLayout.pBindings()).get(0).descriptorCount(3);
		builder.set(1, 1, VK_DESCRIPTOR_TYPE_SAMPLER, VK_SHADER_STAGE_FRAGMENT_BIT);
		this.descriptorSetLayout = builder.build(boiler, "DrawingDescriptorSetLayout");

		var combiner = new DescriptorCombiner(boiler);
		combiner.addSingle(this.descriptorSetLayout, set -> this.descriptorSet = set);

		builder = new DescriptorSetLayoutBuilder(stack, 1);
		builder.set(0, 0, VK_DESCRIPTOR_TYPE_STORAGE_BUFFER, VK_SHADER_STAGE_FRAGMENT_BIT);
		this.kimDescriptorSetLayout = builder.build(boiler, "KimDescriptorSetLayout");

		combiner.addSingle(this.kimDescriptorSetLayout, set -> this.kimDescriptorSet = set);
		this.descriptorPool = combiner.build("PersistentDescriptors");

		var imageInfo = VkDescriptorImageInfo.calloc(3, stack);
		imageInfo.get(0).set(VK_NULL_HANDLE, originalImage.vkImageView, VK_IMAGE_LAYOUT_SHADER_READ_ONLY_OPTIMAL);
		imageInfo.get(1).set(VK_NULL_HANDLE, bc1Image.vkImageView, VK_IMAGE_LAYOUT_SHADER_READ_ONLY_OPTIMAL);
		imageInfo.get(2).set(VK_NULL_HANDLE, bc4Image.vkImageView, VK_IMAGE_LAYOUT_SHADER_READ_ONLY_OPTIMAL);

		var updater = new DescriptorUpdater(stack, 3);
		updater.write(0, descriptorSet, 0, VK_DESCRIPTOR_TYPE_SAMPLED_IMAGE);
		updater.descriptorWrites.get(0).pImageInfo(imageInfo);
		updater.descriptorWrites.get(0).descriptorCount(3);
		updater.writeSampler(1, descriptorSet, 1, sampler);
		updater.writeStorageBuffer(2, kimDescriptorSet, 0, kimBuffer);
		updater.update(boiler);

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
				"ShowcasePipeline", "com/github/knokko/compressor/",
				"showcase.vert.spv", "showcase.frag.spv"
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
		builder.dynamicRendering(0, VK_FORMAT_UNDEFINED, VK_FORMAT_UNDEFINED, window.properties.surfaceFormat());
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
		//noinspection resource
		specializationMappings.get(0).set(0, 0, 4);

		var specializationInfo = VkSpecializationInfo.calloc(stack);
		specializationInfo.pMapEntries(specializationMappings);
		specializationInfo.pData(stack.calloc(4).putInt(0, toIntExact(kimBuffer.size / 4)));

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
		builder.dynamicRendering(0, VK_FORMAT_UNDEFINED, VK_FORMAT_UNDEFINED, window.properties.surfaceFormat());
		long result = builder.build("KimPipeline");

		vkDestroyShaderModule(boiler.vkDevice(), vertexModule, null);
		vkDestroyShaderModule(boiler.vkDevice(), fragmentModule, null);

		return result;
	}

	@Override
	protected void recordFrame(MemoryStack stack, int frameIndex, CommandRecorder recorder, AcquiredImage acquiredImage, BoilerInstance instance) {
		var colorAttachments = VkRenderingAttachmentInfo.calloc(1, stack);
		recorder.simpleColorRenderingAttachment(
				colorAttachments.get(0), acquiredImage.getImage().vkImageView,
				VK_ATTACHMENT_LOAD_OP_CLEAR, VK_ATTACHMENT_STORE_OP_STORE,
				0.2f, 0.5f, 0.7f, 1f
		);
		recorder.beginSimpleDynamicRendering(
				acquiredImage.getWidth(), acquiredImage.getHeight(),
				colorAttachments, null, null
		);
		recorder.dynamicViewportAndScissor(acquiredImage.getWidth(), acquiredImage.getHeight());

		vkCmdBindPipeline(recorder.commandBuffer, VK_PIPELINE_BIND_POINT_GRAPHICS, graphicsPipeline);
		recorder.bindGraphicsDescriptors(pipelineLayout, descriptorSet);

		var vertexPushConstants = stack.callocFloat(2);
		var fragmentPushConstants = stack.callocInt(1);
		drawQuad(recorder.commandBuffer, vertexPushConstants, fragmentPushConstants, -0.9f, -0.9f, 0, pipelineLayout);
		drawQuad(recorder.commandBuffer, vertexPushConstants, fragmentPushConstants, 0.1f, -0.9f, 1, pipelineLayout);
		drawQuad(recorder.commandBuffer, vertexPushConstants, fragmentPushConstants, -0.9f, 0.1f, 2, pipelineLayout);

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
		vkDestroyDescriptorPool(boiler.vkDevice(), descriptorPool, null);
		vkDestroyDescriptorSetLayout(boiler.vkDevice(), descriptorSetLayout.vkDescriptorSetLayout, null);
		vkDestroyDescriptorSetLayout(boiler.vkDevice(), kimDescriptorSetLayout.vkDescriptorSetLayout, null);
		vkDestroySampler(boiler.vkDevice(), sampler, null);
		vkDestroyPipeline(boiler.vkDevice(), graphicsPipeline, null);
		vkDestroyPipeline(boiler.vkDevice(), kimPipeline, null);
		vkDestroyPipelineLayout(boiler.vkDevice(), pipelineLayout, null);
		vkDestroyPipelineLayout(boiler.vkDevice(), kimPipelineLayout, null);
	}
}
