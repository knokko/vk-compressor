package com.github.knokko.compressor;

import com.github.knokko.boiler.builders.BoilerBuilder;
import com.github.knokko.boiler.commands.SingleTimeCommands;
import com.github.knokko.boiler.descriptors.DescriptorCombiner;
import com.github.knokko.boiler.descriptors.DescriptorSetLayoutBuilder;
import com.github.knokko.boiler.descriptors.DescriptorUpdater;
import com.github.knokko.boiler.descriptors.VkbDescriptorSetLayout;
import com.github.knokko.boiler.images.ImageBuilder;
import com.github.knokko.boiler.memory.MemoryCombiner;
import com.github.knokko.boiler.pipelines.GraphicsPipelineBuilder;
import com.github.knokko.boiler.synchronization.ResourceUsage;
import com.github.knokko.boiler.utilities.ImageCoding;
import org.junit.jupiter.api.Test;
import org.lwjgl.vulkan.*;

import javax.imageio.ImageIO;

import java.awt.*;
import java.awt.image.BufferedImage;
import java.io.File;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.util.Arrays;
import java.util.Objects;

import static com.github.knokko.boiler.utilities.BoilerMath.leastCommonMultiple;
import static com.github.knokko.boiler.utilities.ColorPacker.*;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.lwjgl.system.MemoryStack.stackPush;
import static org.lwjgl.system.MemoryUtil.*;
import static org.lwjgl.vulkan.VK10.*;

public class TestKim3Compression {

	@Test
	public void testVerySimpleImage() {
		int width = 2;
		int height = 2;
		var simpleImage = memAlloc(4 * width * height);
		simpleImage.putInt(0, rgb(1, 2, 3));
		simpleImage.putInt(4, rgba(100, 101, 102, 103));
		simpleImage.putInt(8, rgb(200, 201, 202));
		simpleImage.putInt(12, rgb(200, 201, 202));

		var compressor = new Kim3Compressor(simpleImage, width, height);
		assertEquals(width, compressor.width);
		assertEquals(height, compressor.height);
		assertEquals(5, compressor.intSize);

		var compressedImage = memCalloc(2 * 4 * compressor.intSize);
		compressor.compress(compressedImage);
		compressor.compress(compressedImage);

		compressedImage.position(0);
		for (int position : new int[] { 0, 4 * compressor.intSize }) {
			while (compressedImage.position() < position) compressedImage.putInt(0);

			var decompressor = new Kim3Decompressor(compressedImage);
			assertEquals(width, decompressor.width);
			assertEquals(height, decompressor.height);

			assertEquals(1, red(decompressor.getColor(0, 0)), 1.0);
			assertEquals(2, green(decompressor.getColor(0, 0)), 2.0);
			assertEquals(3, blue(decompressor.getColor(0, 0)), 3.0);

			assertEquals(100, red(decompressor.getColor(1, 0)), 1.0);
			assertEquals(101, green(decompressor.getColor(1, 0)), 1.0);
			assertEquals(102, blue(decompressor.getColor(1, 0)), 1.0);
			assertEquals(rgb(200, 201, 202), decompressor.getColor(0, 1));
			assertEquals(rgb(200, 201, 202), decompressor.getColor(1, 1));
		}

		memFree(compressedImage);

		memFree(simpleImage);
	}

	private void testCompressAndDecompress(File file) throws IOException {
		var sourceImage = ImageIO.read(file);

		var rawBuffer = ByteBuffer.allocate(4 * sourceImage.getWidth() * sourceImage.getHeight());
		ImageCoding.encodeBufferedImage(rawBuffer, sourceImage);
		rawBuffer.flip();

		var compressor = new Kim3Compressor(rawBuffer, sourceImage.getWidth(), sourceImage.getHeight());

		System.out.println("compressed size is " + 4 * compressor.intSize + " and file is " + file);

		var compressedData = memCalloc(4 * compressor.intSize);
		compressor.compress(compressedData);
		compressedData.position(0);

		var decompressor = new Kim3Decompressor(compressedData);
		assertEquals(sourceImage.getWidth(), decompressor.width);
		assertEquals(sourceImage.getHeight(), decompressor.height);

		for (int y = 0; y < sourceImage.getHeight(); y++) {
			for (int x = 0; x < sourceImage.getWidth(); x++) {
				var expectedColor = new Color(sourceImage.getRGB(x, y), true);
				int actualColor = decompressor.getColor(x, y);
				assertEquals(expectedColor.getRed(), unsigned(red(actualColor)), 6.0);
				assertEquals(expectedColor.getGreen(), unsigned(green(actualColor)), 6.0);
				assertEquals(expectedColor.getBlue(), unsigned(blue(actualColor)), 6.0);
				assertEquals(expectedColor.getAlpha(), unsigned(alpha(actualColor)), 6.0);
			}
		}

		memFree(compressedData);
	}

	@Test
	public void testMardekImagesJava() throws IOException {
		var boiler = new BoilerBuilder(
				VK_API_VERSION_1_0, "TestKim3Compression", 1
		).build();

		File[] files = new File("../test-helper/src/main/resources/com/github/knokko/compressor/mardek").listFiles();
		assertNotNull(files);

		assertEquals(99, files.length);
		for (File file : files) testCompressAndDecompress(file);

		boiler.destroyInitialObjects();
	}

	@Test
	@SuppressWarnings("resource")
	public void testMardekImagesShader() throws IOException {
		var boiler = new BoilerBuilder(
				VK_API_VERSION_1_0, "TestKim1Compression", 1
		).validation().forbidValidationErrors().enableDynamicRendering().build();

		File[] files = new File("../test-helper/src/main/resources/com/github/knokko/compressor/mardek").listFiles();
		assertNotNull(files);
		assertEquals(99, files.length);
		Arrays.sort(files, (a, b) -> a.getName().compareToIgnoreCase(b.getName()));

		BufferedImage[] images = new BufferedImage[files.length];

		var uncompressedImages = new ByteBuffer[files.length];
		for (int index = 0; index < files.length; index++) {
			images[index] = ImageIO.read(files[index]);
			uncompressedImages[index] = ByteBuffer.allocate(
					4 * images[index].getWidth() * images[index].getHeight()
			);
		}

		var combiner = new MemoryCombiner(boiler, "CompressionMemory");
		var targetImage = combiner.addImage(new ImageBuilder(
				"TargetImage", 200, 200
		).colorAttachment().addUsage(VK_IMAGE_USAGE_TRANSFER_SRC_BIT), 1f);
		var resultBuffer = combiner.addMappedBuffer(
				4L * targetImage.width * targetImage.height,
				4, VK_BUFFER_USAGE_TRANSFER_DST_BIT
		);

		int[] spriteOffsets = new int[files.length];
		int nextSpriteOffset = 0;
		for (int index = 0; index < files.length; index++) {
			var uncompressedImage = uncompressedImages[index];
			ImageCoding.encodeBufferedImage(uncompressedImage, images[index]);
			uncompressedImage.flip();

			spriteOffsets[index] = nextSpriteOffset;
			nextSpriteOffset += new Kim3Compressor(
					uncompressedImage, images[index].getWidth(), images[index].getHeight()
			).intSize;
		}
		var compressedImages = combiner.addMappedDeviceLocalBuffer(
				4L * nextSpriteOffset, boiler.deviceProperties.limits().minStorageBufferOffsetAlignment(),
				VK_BUFFER_USAGE_STORAGE_BUFFER_BIT, 0.5f
		);

		var vertexBuffer = combiner.addMappedDeviceLocalBuffer(
				20L * 6L * files.length, 8, VK_BUFFER_USAGE_VERTEX_BUFFER_BIT, 0.5f
		);
		var infoBuffer = combiner.addMappedDeviceLocalBuffer(
				12L * files.length, leastCommonMultiple(
						12L, boiler.deviceProperties.limits().minStorageBufferOffsetAlignment()
				), VK_BUFFER_USAGE_STORAGE_BUFFER_BIT, 0.5f
		);

		var memory = combiner.build(false);

		for (int index = 0; index < files.length; index++) {
			var uncompressedImage = uncompressedImages[index];
			uncompressedImage.position(0);

			var compressor = new Kim3Compressor(
					uncompressedImage, images[index].getWidth(), images[index].getHeight()
			);
			compressor.compress(compressedImages.byteBuffer().position(4 * spriteOffsets[index]));
		}

		VkbDescriptorSetLayout descriptorSetLayout;
		long descriptorPool, pipelineLayout, graphicsPipeline;
		final long[] descriptorSet = new long[1];

		try (var stack = stackPush()) {
			var builder = new DescriptorSetLayoutBuilder(stack, 2);
			builder.set(
					0, 0, VK_DESCRIPTOR_TYPE_STORAGE_BUFFER,
					VK_SHADER_STAGE_VERTEX_BIT | VK_SHADER_STAGE_FRAGMENT_BIT
			);
			builder.set(1, 1, VK_DESCRIPTOR_TYPE_STORAGE_BUFFER, VK_SHADER_STAGE_VERTEX_BIT);
			descriptorSetLayout = builder.build(boiler, "Kim3DescriptorSetLayout");

			var descriptorCombiner = new DescriptorCombiner(boiler);
			descriptorCombiner.addSingle(descriptorSetLayout, set -> descriptorSet[0] = set);
			descriptorPool = descriptorCombiner.build("Kim3DescriptorPool");

			pipelineLayout = boiler.pipelines.createLayout(
					null, "Kim3PipelineLayout", descriptorSetLayout.vkDescriptorSetLayout
			);

			var vertexAttributes = VkVertexInputAttributeDescription.calloc(3, stack);
			vertexAttributes.get(0).set(0, 0, VK_FORMAT_R32G32_SFLOAT, 0);
			vertexAttributes.get(1).set(1, 0, VK_FORMAT_R32G32_SFLOAT, 8);
			vertexAttributes.get(2).set(2, 0, VK_FORMAT_R32_UINT, 16);

			var vertexBindings = VkVertexInputBindingDescription.calloc(1, stack);
			vertexBindings.get(0).set(0, 20, VK_VERTEX_INPUT_RATE_VERTEX);

			var vertexInput = VkPipelineVertexInputStateCreateInfo.calloc(stack);
			vertexInput.sType$Default();
			vertexInput.pVertexBindingDescriptions(vertexBindings);
			vertexInput.pVertexAttributeDescriptions(vertexAttributes);

			var pipelineBuilder = new GraphicsPipelineBuilder(boiler, stack);
			pipelineBuilder.simpleShaderStages(
					"kim3", "com/github/knokko/compressor/",
					"kim3-test.vert.spv", "kim3-test.frag.spv"
			);
			pipelineBuilder.ciPipeline.pVertexInputState(vertexInput);
			pipelineBuilder.simpleInputAssembly();
			pipelineBuilder.fixedViewport(targetImage.width, targetImage.height);
			pipelineBuilder.simpleRasterization(VK_CULL_MODE_NONE);
			pipelineBuilder.noMultisampling();
			pipelineBuilder.noDepthStencil();
			pipelineBuilder.simpleColorBlending(1);
			pipelineBuilder.ciPipeline.layout(pipelineLayout);
			pipelineBuilder.dynamicRendering(0, VK_FORMAT_UNDEFINED, VK_FORMAT_UNDEFINED, VK_FORMAT_R8G8B8A8_SRGB);
			graphicsPipeline = pipelineBuilder.build("Kim3Pipeline");

			var updater = new DescriptorUpdater(stack, 2);
			updater.writeStorageBuffer(0, descriptorSet[0], 0, compressedImages);
			updater.writeStorageBuffer(1, descriptorSet[0], 1, infoBuffer);
			updater.update(boiler);
		}
		var commands = new SingleTimeCommands(boiler);
		commands.submit("Draw compressed images", recorder -> {
			recorder.transitionLayout(targetImage, null, ResourceUsage.COLOR_ATTACHMENT_WRITE);

			var colorAttachment = VkRenderingAttachmentInfo.calloc(1, recorder.stack);
			recorder.simpleColorRenderingAttachment(
					colorAttachment.get(0), targetImage.vkImageView, VK_ATTACHMENT_LOAD_OP_CLEAR,
					VK_ATTACHMENT_STORE_OP_STORE, 0.2f, 0.2f, 0.2f, 1f
			);
			recorder.beginSimpleDynamicRendering(
					targetImage.width, targetImage.height,
					colorAttachment, null, null
			);
			vkCmdBindPipeline(recorder.commandBuffer, VK_PIPELINE_BIND_POINT_GRAPHICS, graphicsPipeline);
			recorder.bindGraphicsDescriptors(pipelineLayout, descriptorSet);

			var hostVertexBuffer = vertexBuffer.byteBuffer();
			var hostInfoBuffer = infoBuffer.byteBuffer();

			int offsetX = 0;
			int offsetY = 0;
			for (int index = 0; index < files.length; index++) {
				if (offsetX + images[index].getWidth() > targetImage.width) {
					offsetX = 0;
					offsetY += 16;
				}

				hostInfoBuffer.putInt(spriteOffsets[index]);
				hostInfoBuffer.putInt(compressedImages.intBuffer().get(spriteOffsets[index]));
				hostInfoBuffer.putInt(compressedImages.intBuffer().get(spriteOffsets[index] + 1));

				for (int corner = 0; corner < 6; corner++) {
					float x1 = 2f * offsetX / targetImage.width - 1f;
					float x2 = 2f * (offsetX + 16) / targetImage.width - 1f;
					float y1 = 2f * offsetY / targetImage.height - 1f;
					float y2 = 2f * (offsetY + 16) / targetImage.height - 1f;
					if (corner == 0 || corner == 5) {
						hostVertexBuffer.putFloat(x1).putFloat(y1);
						hostVertexBuffer.putFloat(0f).putFloat(0f);
					}
					if (corner == 1) {
						hostVertexBuffer.putFloat(x2).putFloat(y1);
						hostVertexBuffer.putFloat(1f).putFloat(0f);
					}
					if (corner == 2 || corner == 3) {
						hostVertexBuffer.putFloat(x2).putFloat(y2);
						hostVertexBuffer.putFloat(1f).putFloat(1f);
					}
					if (corner == 4) {
						hostVertexBuffer.putFloat(x1).putFloat(y2);
						hostVertexBuffer.putFloat(0f).putFloat(1f);
					}
					hostVertexBuffer.putInt(index);
				}

				offsetX += images[index].getWidth();
			}
			vkCmdBindVertexBuffers(
					recorder.commandBuffer, 0, recorder.stack.longs(vertexBuffer.vkBuffer),
					recorder.stack.longs(0)
			);
			vkCmdDraw(recorder.commandBuffer, 6 * files.length, 1, 0, 0);
			recorder.endDynamicRendering();

			recorder.transitionLayout(targetImage, ResourceUsage.COLOR_ATTACHMENT_WRITE, ResourceUsage.TRANSFER_SOURCE);
			recorder.copyImageToBuffer(targetImage, resultBuffer);
		}).awaitCompletion();
		commands.destroy();

		var actualImage = ImageCoding.decodeBufferedImage(resultBuffer.byteBuffer(), targetImage.width, targetImage.height);
		var expectedInput = TestKim3Compression.class.getResourceAsStream("expected-kim3-result.png");
		var expectedImage = ImageIO.read(Objects.requireNonNull(expectedInput));
		expectedInput.close();

		assertEquals(expectedImage.getWidth(), actualImage.getWidth());
		assertEquals(expectedImage.getHeight(), actualImage.getHeight());
		for (int x = 0; x < expectedImage.getWidth(); x++) {
			for (int y = 0; y < expectedImage.getHeight(); y++) {
				var expectedColor = new Color(expectedImage.getRGB(x, y));
				var actualColor = new Color(actualImage.getRGB(x, y));
				assertEquals(expectedColor.getRed(), actualColor.getRed(), 2.0);
				assertEquals(expectedColor.getGreen(), actualColor.getGreen(), 2.0);
				assertEquals(expectedColor.getBlue(), actualColor.getBlue(), 6.0);
				assertEquals(expectedColor.getAlpha(), actualColor.getAlpha(), 2.0);
			}
		}

		vkDestroyDescriptorPool(boiler.vkDevice(), descriptorPool, null);
		vkDestroyDescriptorSetLayout(boiler.vkDevice(), descriptorSetLayout.vkDescriptorSetLayout, null);
		vkDestroyPipeline(boiler.vkDevice(), graphicsPipeline, null);
		vkDestroyPipelineLayout(boiler.vkDevice(), pipelineLayout, null);
		memory.destroy(boiler);
		boiler.destroyInitialObjects();
	}
}
