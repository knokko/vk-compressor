package com.github.knokko.compressor;

import com.github.knokko.boiler.BoilerInstance;
import com.github.knokko.boiler.buffers.MappedVkbBufferRange;
import com.github.knokko.boiler.buffers.SharedMappedBufferBuilder;
import com.github.knokko.boiler.builders.BoilerBuilder;
import com.github.knokko.boiler.commands.SingleTimeCommands;
import com.github.knokko.boiler.descriptors.HomogeneousDescriptorPool;
import com.github.knokko.boiler.descriptors.VkbDescriptorSetLayout;
import com.github.knokko.boiler.pipelines.GraphicsPipelineBuilder;
import com.github.knokko.boiler.pipelines.ShaderInfo;
import com.github.knokko.boiler.synchronization.ResourceUsage;
import org.junit.jupiter.api.Test;
import org.lwjgl.BufferUtils;
import org.lwjgl.vulkan.*;

import javax.imageio.ImageIO;

import java.awt.*;
import java.awt.image.BufferedImage;
import java.io.File;
import java.io.IOException;
import java.util.Arrays;
import java.util.Objects;
import java.util.function.Supplier;

import static com.github.knokko.boiler.utilities.ColorPacker.*;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.lwjgl.system.MemoryStack.stackPush;
import static org.lwjgl.system.MemoryUtil.*;
import static org.lwjgl.vulkan.VK10.*;

public class TestKim1Compression {

	@Test
	public void testVerySimpleImage() {
		int width = 2;
		int height = 2;
		var simpleImage = memAlloc(4 * width * height);
		simpleImage.putInt(0, rgb(1, 2, 3));
		simpleImage.putInt(4, rgba(100, 101, 102, 103));
		simpleImage.putInt(8, rgb(200, 201, 202));
		simpleImage.putInt(12, rgb(200, 201, 202));

		var compressor = new Kim1Compressor(simpleImage, width, height, 4);
		assertEquals(width, compressor.width);
		assertEquals(height, compressor.height);
		assertEquals(5, compressor.intSize);

		var compressedImage = memCalloc(2 * 4 * compressor.intSize);
		compressor.compress(compressedImage);
		compressor.compress(compressedImage);

		compressedImage.position(0);
		for (int position : new int[] { 0, 4 * compressor.intSize }) {
			while (compressedImage.position() < position) compressedImage.putInt(0);

			var decompressor = new Kim1Decompressor(compressedImage);
			assertEquals(width, decompressor.width);
			assertEquals(height, decompressor.height);

			assertEquals(rgb(1, 2, 3), decompressor.getColor(0, 0));
			assertEquals(rgba(100, 101, 102, 103), decompressor.getColor(1, 0));
			assertEquals(rgb(200, 201, 202), decompressor.getColor(0, 1));
			assertEquals(rgb(200, 201, 202), decompressor.getColor(1, 1));
		}

		memFree(compressedImage);

		memFree(simpleImage);
	}

	private void testCompressAndDecompress(BoilerInstance boiler, File file) throws IOException {
		var sourceImage = ImageIO.read(file);

		var rawBuffer = boiler.buffers.createMapped(
				sourceImage.getWidth() * sourceImage.getHeight() * 4L,
				VK_BUFFER_USAGE_STORAGE_BUFFER_BIT, "RawBuffer"
		);
		boiler.buffers.encodeBufferedImageRGBA(rawBuffer, sourceImage, 0L);

		var compressor = new Kim1Compressor(
				rawBuffer.fullMappedRange().byteBuffer(),
				sourceImage.getWidth(), sourceImage.getHeight(), 4
		);

		System.out.println("compressed size is " + 4 * compressor.intSize + " and file is " + file);

		var compressedData = memCalloc(4 * compressor.intSize);
		compressor.compress(compressedData);
		compressedData.position(0);

		var decompressor = new Kim1Decompressor(compressedData);
		assertEquals(sourceImage.getWidth(), decompressor.width);
		assertEquals(sourceImage.getHeight(), decompressor.height);

		for (int y = 0; y < sourceImage.getHeight(); y++) {
			for (int x = 0; x < sourceImage.getWidth(); x++) {
				var expectedColor = new Color(sourceImage.getRGB(x, y), true);
				int actualColor = decompressor.getColor(x, y);
				assertEquals(expectedColor.getRed(), unsigned(red(actualColor)));
				assertEquals(expectedColor.getGreen(), unsigned(green(actualColor)));
				assertEquals(expectedColor.getBlue(), unsigned(blue(actualColor)));
				assertEquals(expectedColor.getAlpha(), unsigned(alpha(actualColor)));
			}
		}

		memFree(compressedData);

		rawBuffer.destroy(boiler);
	}

	@Test
	public void testMardekImagesJava() throws IOException {
		var boiler = new BoilerBuilder(
				VK_API_VERSION_1_0, "TestKim1Compression", 1
		).build();

		File[] files = new File("src/test/resources/com/github/knokko/compressor/mardek").listFiles();
		assertNotNull(files);

		assertEquals(99, files.length);
		for (File file : files) testCompressAndDecompress(boiler, file);

		boiler.destroyInitialObjects();
	}

	@Test
	@SuppressWarnings({"resource", "unchecked"})
	public void testMardekImagesShader() throws IOException {
		var boiler = new BoilerBuilder(
				VK_API_VERSION_1_0, "TestKim1Compression", 1
		).validation().forbidValidationErrors().enableDynamicRendering().build();

		File[] files = new File("src/test/resources/com/github/knokko/compressor/mardek").listFiles();
		assertNotNull(files);
		assertEquals(99, files.length);
		Arrays.sort(files, (a, b) -> a.getName().compareToIgnoreCase(b.getName()));

		BufferedImage[] images = new BufferedImage[files.length];

		var uncompressedBuilder = new SharedMappedBufferBuilder(boiler);
		Supplier<MappedVkbBufferRange>[] uncompressedImages = new Supplier[files.length];
		for (int index = 0; index < files.length; index++) {
			images[index] = ImageIO.read(files[index]);
			uncompressedImages[index] = uncompressedBuilder.add(
					4L * images[index].getWidth() * images[index].getHeight(), 4
			);
		}
		// Space for the copy result
		uncompressedBuilder.add(4L * 200 * 200, 4L);

		var uncompressedBuffer = uncompressedBuilder.build(VK_BUFFER_USAGE_TRANSFER_DST_BIT, "Uncompressed");

		var compressedBuilder = new SharedMappedBufferBuilder(boiler);
		Supplier<MappedVkbBufferRange>[] compressedImages = new Supplier[files.length];

		for (int index = 0; index < files.length; index++) {
			var uncompressedImage = uncompressedImages[index].get();
			boiler.buffers.encodeBufferedImageIntoRangeRGBA(uncompressedImage, images[index]);
			compressedImages[index] = compressedBuilder.add(new Kim1Compressor(
					uncompressedImage.byteBuffer(), images[index].getWidth(), images[index].getHeight(), 4
			).intSize * 4L, 4);
		}

		var compressedBuffer = compressedBuilder.build(VK_BUFFER_USAGE_STORAGE_BUFFER_BIT, "Compressed");
		int[] spriteOffsets = new int[files.length];

		for (int index = 0; index < files.length; index++) {
			var uncompressedImage = uncompressedImages[index].get();
			var compressor = new Kim1Compressor(
					uncompressedImage.byteBuffer(), images[index].getWidth(), images[index].getHeight(), 4
			);
			compressor.compress(compressedImages[index].get().byteBuffer());
			spriteOffsets[index] = (int) compressedImages[index].get().offset() / 4;
		}

		var vertexBuffer = boiler.buffers.createMapped(
				20L * files.length, VK_BUFFER_USAGE_VERTEX_BUFFER_BIT, "VertexBuffer"
		);

		var targetImage = boiler.images.createSimple(
				200, 200, VK_FORMAT_R8G8B8A8_SRGB,
				VK_IMAGE_USAGE_COLOR_ATTACHMENT_BIT | VK_IMAGE_USAGE_TRANSFER_SRC_BIT,
				VK_IMAGE_ASPECT_COLOR_BIT, "TargetImage"
		);

		VkbDescriptorSetLayout descriptorSetLayout;
		HomogeneousDescriptorPool descriptorPool;
		long descriptorSet, pipelineLayout, graphicsPipeline;

		try (var stack = stackPush()) {
			var descriptorBindings = VkDescriptorSetLayoutBinding.calloc(1, stack);
			boiler.descriptors.binding(descriptorBindings, 0, VK_DESCRIPTOR_TYPE_STORAGE_BUFFER, VK_SHADER_STAGE_FRAGMENT_BIT);

			descriptorSetLayout = boiler.descriptors.createLayout(stack, descriptorBindings, "KimDescriptorLayout");
			descriptorPool = descriptorSetLayout.createPool(1, 0, "KimDescriptorPool");
			descriptorSet = descriptorPool.allocate(1)[0];

			pipelineLayout = boiler.pipelines.createLayout(null, "KimPipelineLayout", descriptorSetLayout.vkDescriptorSetLayout);

			var vertexAttributes = VkVertexInputAttributeDescription.calloc(3, stack);
			vertexAttributes.get(0).set(0, 0, VK_FORMAT_R32G32_SFLOAT, 0);
			vertexAttributes.get(1).set(1, 0, VK_FORMAT_R32G32_UINT, 8);
			vertexAttributes.get(2).set(2, 0, VK_FORMAT_R32_UINT, 16);

			var vertexBindings = VkVertexInputBindingDescription.calloc(1, stack);
			vertexBindings.get(0).set(0, 20, VK_VERTEX_INPUT_RATE_INSTANCE);

			var vertexInput = VkPipelineVertexInputStateCreateInfo.calloc(stack);
			vertexInput.sType$Default();
			vertexInput.pVertexBindingDescriptions(vertexBindings);
			vertexInput.pVertexAttributeDescriptions(vertexAttributes);

			var vertexShader = boiler.pipelines.createShaderModule(
					"com/github/knokko/compressor/kim1-test.vert.spv", "KimVertex"
			);
			var fragmentShader = boiler.pipelines.createShaderModule(
					"com/github/knokko/compressor/kim1-test.frag.spv", "KimFragment"
			);

			var specializationEntries = VkSpecializationMapEntry.calloc(1, stack);
			specializationEntries.get(0).set(0, 0, 4);

			var specializationInfo = VkSpecializationInfo.calloc(stack);
			specializationInfo.pMapEntries(specializationEntries);
			specializationInfo.pData(stack.calloc(4).putInt(0, (int) (compressedBuffer.size() / 4L)));

			var pipelineBuilder = new GraphicsPipelineBuilder(boiler, stack);
			pipelineBuilder.shaderStages(
					new ShaderInfo(VK_SHADER_STAGE_VERTEX_BIT, vertexShader, null),
					new ShaderInfo(VK_SHADER_STAGE_FRAGMENT_BIT, fragmentShader, specializationInfo)
			);
			pipelineBuilder.simpleShaderStages(
					"kim1", "com/github/knokko/compressor/kim1-test.vert.spv",
					"com/github/knokko/compressor/kim1-test.frag.spv"
			);
			pipelineBuilder.ciPipeline.pVertexInputState(vertexInput);
			pipelineBuilder.simpleInputAssembly();
			pipelineBuilder.fixedViewport(targetImage.width(), targetImage.height());
			pipelineBuilder.simpleRasterization(VK_CULL_MODE_NONE);
			pipelineBuilder.noMultisampling();
			pipelineBuilder.noDepthStencil();
			pipelineBuilder.simpleColorBlending(1);
			pipelineBuilder.ciPipeline.layout(pipelineLayout);
			pipelineBuilder.dynamicRendering(0, VK_FORMAT_UNDEFINED, VK_FORMAT_UNDEFINED, VK_FORMAT_R8G8B8A8_SRGB);
			graphicsPipeline = pipelineBuilder.build("KimPipeline");

			var descriptorWrites = VkWriteDescriptorSet.calloc(1, stack);
			boiler.descriptors.writeBuffer(
					stack, descriptorWrites, descriptorSet, 0,
					VK_DESCRIPTOR_TYPE_STORAGE_BUFFER, compressedBuffer.fullRange()
			);
			vkUpdateDescriptorSets(boiler.vkDevice(), descriptorWrites, null);

			vkDestroyShaderModule(boiler.vkDevice(), vertexShader, null);
			vkDestroyShaderModule(boiler.vkDevice(), fragmentShader, null);
		}
		var commands = new SingleTimeCommands(boiler);
		commands.submit("Draw compressed images", recorder -> {
			recorder.transitionLayout(targetImage, null, ResourceUsage.COLOR_ATTACHMENT_WRITE);

			var colorAttachment = VkRenderingAttachmentInfo.calloc(1, recorder.stack);
			recorder.simpleColorRenderingAttachment(
					colorAttachment.get(0), targetImage.vkImageView(), VK_ATTACHMENT_LOAD_OP_CLEAR,
					VK_ATTACHMENT_STORE_OP_STORE, 0.2f, 0.2f, 0.2f, 1f
			);
			recorder.beginSimpleDynamicRendering(targetImage.width(), targetImage.height(), colorAttachment, null, null);
			vkCmdBindPipeline(recorder.commandBuffer, VK_PIPELINE_BIND_POINT_GRAPHICS, graphicsPipeline);
			recorder.bindGraphicsDescriptors(pipelineLayout, descriptorSet);

			var hostVertexBuffer = vertexBuffer.fullMappedRange().byteBuffer();
			int offsetX = 0;
			int offsetY = 0;
			for (int index = 0; index < files.length; index++) {
				if (offsetX + images[index].getWidth() > targetImage.width()) {
					offsetX = 0;
					offsetY += 16;
				}
				hostVertexBuffer.putFloat(2f * offsetX / targetImage.width() - 1f);
				hostVertexBuffer.putFloat(2f * offsetY / targetImage.height() - 1f);
				hostVertexBuffer.putInt(images[index].getWidth()).putInt(images[index].getHeight());
				hostVertexBuffer.putInt(spriteOffsets[index]);

				offsetX += images[index].getWidth();
			}
			vkCmdBindVertexBuffers(
					recorder.commandBuffer, 0, recorder.stack.longs(vertexBuffer.vkBuffer()),
					recorder.stack.longs(0)
			);
			vkCmdDraw(recorder.commandBuffer, 6, files.length, 0, 0);
			recorder.endDynamicRendering();

			recorder.transitionLayout(targetImage, ResourceUsage.COLOR_ATTACHMENT_WRITE, ResourceUsage.TRANSFER_SOURCE);
			recorder.copyImageToBuffer(targetImage, uncompressedBuffer.fullRange());
		}).awaitCompletion();
		commands.destroy();

		var actualImage = boiler.buffers.decodeBufferedImageFromRangeRGBA(
				uncompressedBuffer.mappedRange(0, 4L * targetImage.width() * targetImage.height()),
				targetImage.width(), targetImage.height()
		);
		var expectedInput = TestKim1Compression.class.getResourceAsStream("expected-kim1-result.png");
		var expectedImage = ImageIO.read(Objects.requireNonNull(expectedInput));
		expectedInput.close();

		assertEquals(expectedImage.getWidth(), actualImage.getWidth());
		assertEquals(expectedImage.getHeight(), actualImage.getHeight());
		for (int x = 0; x < expectedImage.getWidth(); x++) {
			for (int y = 0; y < expectedImage.getHeight(); y++) {
				var expectedColor = new Color(expectedImage.getRGB(x, y));
				var actualColor = new Color(actualImage.getRGB(x, y));
				assertEquals(expectedColor.getRed(), actualColor.getRed(), 1.0);
				assertEquals(expectedColor.getGreen(), actualColor.getGreen(), 1.0);
				assertEquals(expectedColor.getBlue(), actualColor.getBlue(), 1.0);
				assertEquals(expectedColor.getAlpha(), actualColor.getAlpha(), 1.0);
			}
		}

		descriptorPool.destroy();
		descriptorSetLayout.destroy();
		vkDestroyPipeline(boiler.vkDevice(), graphicsPipeline, null);
		vkDestroyPipelineLayout(boiler.vkDevice(), pipelineLayout, null);
		targetImage.destroy(boiler);
		vertexBuffer.destroy(boiler);
		compressedBuffer.destroy(boiler);
		uncompressedBuffer.destroy(boiler);
		boiler.destroyInitialObjects();
	}

	@Test
	public void orangeRegressionTest() {
		var orangeBuffer = BufferUtils.createByteBuffer(3 * 2 * 2);
		for (int counter = 0; counter < 3; counter++) {
			orangeBuffer.put((byte) 255).put((byte) 255).put((byte) 0);
		}
		orangeBuffer.put((byte) 11).put((byte) 22).put((byte) 223);
		orangeBuffer.flip();

		var compressor = new Kim1Compressor(orangeBuffer, 2, 2, 3);
		assertEquals(3, compressor.intSize);

		var compressedBuffer = BufferUtils.createByteBuffer(4 * compressor.intSize);
		compressor.compress(compressedBuffer);
		compressedBuffer.flip();

		var decompressor = new Kim1Decompressor(compressedBuffer);
		assertEquals(rgb(255, 255, 0), decompressor.getColor(0, 0));
		assertEquals(rgb(255, 255, 0), decompressor.getColor(1, 0));
		assertEquals(rgb(255, 255, 0), decompressor.getColor(0, 1));
		assertEquals(rgb(11, 22, 223), decompressor.getColor(1, 1));
	}
}
