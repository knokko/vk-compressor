package com.github.knokko.compressor;

import com.github.knokko.boiler.builders.BoilerBuilder;
import com.github.knokko.boiler.commands.SingleTimeCommands;
import com.github.knokko.boiler.descriptors.VkbDescriptorSetLayout;
import com.github.knokko.boiler.pipelines.GraphicsPipelineBuilder;
import com.github.knokko.boiler.synchronization.ResourceUsage;
import org.junit.jupiter.api.Test;
import org.lwjgl.vulkan.*;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.nio.IntBuffer;

import static com.github.knokko.boiler.utilities.ColorPacker.*;
import static com.github.knokko.compressor.Kim2Compressor.*;
import static com.github.knokko.compressor.Kim2Decompressor.*;
import static com.github.knokko.compressor.TestHelper.assertImageEquals;
import static java.util.Objects.requireNonNull;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.lwjgl.system.MemoryStack.stackPush;
import static org.lwjgl.vulkan.VK10.*;

public class TestKim2Compression {

	@Test
	public void testPredictSize() {
		assertEquals(8, predictByteSize(0, 0, 8));
		assertEquals(2, predictIntSize(0, 0, 8));
		assertEquals(12, predictByteSize(1, 1, 8));
		assertEquals(3, predictIntSize(1, 1, 8));
		assertEquals(3, predictIntSize(1, 1, 1));

		assertEquals(127, predictIntSize(100, 10, 4));
	}

	@Test
	public void testExact8BitReconstruction() {
		IntBuffer input = IntBuffer.allocate(256);
		for (int alpha = 0; alpha < 256; alpha++) input.put(rgba(12, 34, 56, alpha));
		input.flip();

		IntBuffer compressed = IntBuffer.allocate(predictIntSize(4, 64, 8));
		compress(input, 4, 64, compressed, 8);
		compressed.flip();

		IntBuffer recovered = IntBuffer.allocate(256);
		decompress(compressed, recovered);
		recovered.flip();
		for (int alpha = 0; alpha < 256; alpha++) assertEquals(rgba(12, 34, 56, alpha), recovered.get());
	}

	@Test
	public void testBinaryPurpleMix() {
		IntBuffer input = IntBuffer.allocate(4);
		input.put(rgba(0, 255, 0, 0));
		input.put(rgb(200, 0, 100));
		input.put(rgb(100, 0, 200));
		input.put(rgba(0, 255, 0, 0));
		input.flip();

		IntBuffer compressed = IntBuffer.allocate(predictIntSize(2, 2, 1));
		compress(input, 2, 2, compressed, 1);
		compressed.flip();

		IntBuffer recovered = IntBuffer.allocate(4);
		decompress(compressed, recovered);
		recovered.flip();
		assertEquals(0, alpha(recovered.get()));
		assertEquals(rgb(150, 0, 150), recovered.get());
		assertEquals(rgb(150, 0, 150), recovered.get());
		assertEquals(0, alpha(recovered.get()));
	}

	@Test
	public void test2BitGradient() {
		// 2 bits per pixel -> 4 alpha values -> [0, 85, 170, 255]

		IntBuffer input = IntBuffer.allocate(8);
		input.put(rgba(255, 0, 0, 0));
		input.put(rgba(255, 0, 0, 42));
		input.put(rgba(255, 0, 0, 43));
		input.put(rgba(255, 0, 0, 127));
		input.put(rgba(255, 0, 0, 128));
		input.put(rgba(255, 0, 0, 212));
		input.put(rgba(255, 0, 0, 213));
		input.put(rgba(255, 0, 0, 255));
		input.flip();

		IntBuffer compressed = IntBuffer.allocate(predictIntSize(4, 2, 2));
		compress(input, 4, 2, compressed, 2);
		compressed.flip();

		IntBuffer recovered = IntBuffer.allocate(8);
		decompress(compressed, recovered);
		recovered.flip();

		assertEquals(rgba(255, 0, 0, 0), recovered.get());
		assertEquals(rgba(255, 0, 0, 0), recovered.get());
		assertEquals(rgba(255, 0, 0, 85), recovered.get());
		assertEquals(rgba(255, 0, 0, 85), recovered.get());
		assertEquals(rgba(255, 0, 0, 170), recovered.get());
		assertEquals(rgba(255, 0, 0, 170), recovered.get());
		assertEquals(rgba(255, 0, 0, 255), recovered.get());
		assertEquals(rgba(255, 0, 0, 255), recovered.get());
	}

	@Test
	public void testWidthOf0() {
		IntBuffer input = IntBuffer.allocate(1);
		input.put(1234);

		IntBuffer compressed = IntBuffer.allocate(predictIntSize(0, 10, 4));
		assertEquals(2, compressed.capacity());
		compress(input, 0, 10, compressed, 4);

		compressed.flip();
		decompress(compressed, input);
		assertEquals(1234, input.get(0));
	}

	@Test
	public void testInvalidSize() {
		assertThrows(IllegalArgumentException.class, () -> predictByteSize(MAX_SIZE + 1, 10, 2));
		assertThrows(IllegalArgumentException.class, () -> predictByteSize(100, MAX_SIZE + 1, 4));
		assertThrows(IllegalArgumentException.class, () -> predictIntSize(12, 34, 5));
		assertThrows(IllegalArgumentException.class, () -> predictIntSize(-1, 50, 8));

		IntBuffer dummy = IntBuffer.allocate(1);
		assertThrows(IllegalArgumentException.class, () -> compress(dummy, MAX_SIZE + 1, 10, dummy, 2));
		assertThrows(IllegalArgumentException.class, () -> compress(dummy, 1000, MAX_SIZE + 1, dummy, 2));
		assertThrows(IllegalArgumentException.class, () -> compress(dummy, 12, 34, dummy, 5));
	}

	@Test
	public void testLargeWidth() {
		IntBuffer input = IntBuffer.allocate(10 * MAX_SIZE);
		for (int counter = 0; counter < input.capacity(); counter++) input.put(rgba(100, 200, 250, counter % 256));
		input.flip();

		IntBuffer compressed = IntBuffer.allocate(predictIntSize(MAX_SIZE, 10, 8));
		compress(input, MAX_SIZE, 10, compressed, 8);
		compressed.flip();

		IntBuffer recovered = IntBuffer.allocate(10 * MAX_SIZE);
		decompress(compressed, recovered);
		recovered.flip();
		for (int counter = 0; counter < input.capacity(); counter++) {
			assertEquals(rgba(100, 200, 250, counter % 256), recovered.get());
		}
	}

	@Test
	public void testTransparentImage() {
		IntBuffer compressed = IntBuffer.allocate(3);
		compress(new BufferedImage(1, 1, BufferedImage.TYPE_INT_ARGB), compressed, 4);
		compressed.flip();

		BufferedImage recovered = decompress(compressed);
		assertEquals(0, recovered.getRGB(0, 0));
	}

	private BufferedImage recoveredEarth(int bitsPerPixel) throws IOException {
		return ImageIO.read(requireNonNull(TestKim2Compression.class.getResource("EarthRecovered" + bitsPerPixel + ".png")));
	}

	@Test
	public void testCpuCompressionAndDecompression() throws IOException {
		BufferedImage input = ImageIO.read(requireNonNull(TestKim2Compression.class.getResource("EarthThick.png")));
		assertEquals(240, input.getWidth());
		assertEquals(240, input.getHeight());

		for (int bitsPerPixel : new int[] { 1, 2, 4, 8 }) {
			IntBuffer compressed = IntBuffer.allocate(predictIntSize(input.getWidth(), input.getHeight(), bitsPerPixel));

			compress(input, compressed, bitsPerPixel);
			compressed.flip();

			assertEquals(input.getWidth(), getWidth(compressed.get(0)));
			assertEquals(input.getWidth(), getHeight(compressed.get(0)));
			assertEquals(bitsPerPixel, getBitsPerPixel(compressed.get(0)));

			BufferedImage recovered = decompress(compressed);
			BufferedImage expected = recoveredEarth(bitsPerPixel);
			assertImageEquals(expected, recovered);
		}
	}

	@SuppressWarnings("resource")
	@Test
	public void testGpuCompressionAndDecompression() throws IOException {
		var boiler = new BoilerBuilder(
				VK_API_VERSION_1_0, "TestKim2Compression", 1
		).enableDynamicRendering().validation().forbidValidationErrors().build();

		BufferedImage input = ImageIO.read(requireNonNull(TestKim2Compression.class.getResource("EarthThick.png")));
		assertEquals(240, input.getWidth());
		assertEquals(240, input.getHeight());

		var commands = new SingleTimeCommands(boiler);

		long compressedSize = 0;
		for (int bitsPerPixel : new int[] { 1, 2, 4, 8 }) {
			compressedSize += predictByteSize(input.getWidth(), input.getHeight(), bitsPerPixel);
		}

		var compressed = boiler.buffers.createMapped(
				compressedSize, VK_BUFFER_USAGE_STORAGE_BUFFER_BIT, "CompressedKim2Buffer"
		);
		var recovered = boiler.buffers.createMapped(
				16L * input.getWidth() * input.getHeight(),
				VK_BUFFER_USAGE_TRANSFER_DST_BIT, "RecoveredKim2Buffer"
		);
		var vertices = boiler.buffers.createMapped(48L, VK_BUFFER_USAGE_VERTEX_BUFFER_BIT, "Kim2Vertices");
		var targetImage = boiler.images.createSimple(
				2 * input.getWidth(), 2 * input.getHeight(), VK_FORMAT_R8G8B8A8_SRGB,
				VK_IMAGE_USAGE_COLOR_ATTACHMENT_BIT | VK_IMAGE_USAGE_TRANSFER_SRC_BIT,
				VK_IMAGE_ASPECT_COLOR_BIT, "Kim2TargetImage"
		);

		var compressedHost = compressed.fullMappedRange().intBuffer();
		var verticesHost = vertices.fullMappedRange().byteBuffer();
		for (int bitsPerPixel : new int[] { 1, 2, 4, 8 }) {
			float x = -1f;
			float y = -1f;
			if (bitsPerPixel == 2 || bitsPerPixel == 8) x = 0f;
			if (bitsPerPixel > 2) y = 0f;
			verticesHost.putFloat(x).putFloat(y).putInt(compressedHost.position());
			compress(input, compressedHost, bitsPerPixel);
		}

		VkbDescriptorSetLayout descriptorLayout;
		try (var stack = stackPush()) {
			var descriptorBindings = VkDescriptorSetLayoutBinding.calloc(1, stack);
			boiler.descriptors.binding(
					descriptorBindings, 0, VK_DESCRIPTOR_TYPE_STORAGE_BUFFER,
					VK_SHADER_STAGE_VERTEX_BIT | VK_SHADER_STAGE_FRAGMENT_BIT
			);
			descriptorLayout = boiler.descriptors.createLayout(stack, descriptorBindings, "Kim2DescriptorLayout");
		}

		var descriptorPool = descriptorLayout.createPool(1, 0, "Kim2DescriptorPool");
		var descriptorSet = descriptorPool.allocate(1)[0];

		try (var stack = stackPush()) {
			var writes = VkWriteDescriptorSet.calloc(1, stack);
			boiler.descriptors.writeBuffer(
					stack, writes, descriptorSet, 0,
					VK_DESCRIPTOR_TYPE_STORAGE_BUFFER, compressed.fullRange()
			);
			vkUpdateDescriptorSets(boiler.vkDevice(), writes, null);
		}

		var pipelineLayout = boiler.pipelines.createLayout(
				null, "Kim2PipelineLayout", descriptorLayout.vkDescriptorSetLayout
		);
		long graphicsPipeline;
		try (var stack = stackPush()) {
			var vertexBindings = VkVertexInputBindingDescription.calloc(1, stack);
			vertexBindings.get(0).set(0, 12, VK_VERTEX_INPUT_RATE_INSTANCE);

			var vertexAttributes = VkVertexInputAttributeDescription.calloc(2, stack);
			vertexAttributes.get(0).set(0, 0, VK_FORMAT_R32G32_SFLOAT, 0);
			vertexAttributes.get(1).set(1, 0, VK_FORMAT_R32_UINT, 8);

			var vertexInput = VkPipelineVertexInputStateCreateInfo.calloc(stack);
			vertexInput.sType$Default();
			vertexInput.pVertexBindingDescriptions(vertexBindings);
			vertexInput.pVertexAttributeDescriptions(vertexAttributes);

			var pipelineBuilder = new GraphicsPipelineBuilder(boiler, stack);
			String path = "com/github/knokko/compressor/";
			pipelineBuilder.simpleShaderStages("Kim2", path + "kim2.vert.spv", path + "kim2.frag.spv");
			pipelineBuilder.ciPipeline.pVertexInputState(vertexInput);
			pipelineBuilder.simpleInputAssembly();
			pipelineBuilder.fixedViewport(targetImage.width(), targetImage.height());
			pipelineBuilder.simpleRasterization(VK_CULL_MODE_NONE);
			pipelineBuilder.noMultisampling();
			pipelineBuilder.noDepthStencil();
			pipelineBuilder.noColorBlending(1);
			pipelineBuilder.ciPipeline.layout(pipelineLayout);
			pipelineBuilder.dynamicRendering(0, VK_FORMAT_UNDEFINED, VK_FORMAT_UNDEFINED, VK_FORMAT_R8G8B8A8_SRGB);

			graphicsPipeline = pipelineBuilder.build("Kim2Pipeline");
		}

		commands.submit("RecoverKim2", recorder -> {

			recorder.transitionLayout(targetImage, null, ResourceUsage.COLOR_ATTACHMENT_WRITE);

			var colorAttachments = VkRenderingAttachmentInfo.calloc(1, recorder.stack);
			recorder.simpleColorRenderingAttachment(
					colorAttachments.get(0), targetImage.vkImageView(), VK_ATTACHMENT_LOAD_OP_CLEAR,
					VK_ATTACHMENT_STORE_OP_STORE, 0f, 0f, 0f, 0f
			);
			recorder.beginSimpleDynamicRendering(
					targetImage.width(), targetImage.height(), colorAttachments,
					null, null
			);

			vkCmdBindPipeline(recorder.commandBuffer, VK_PIPELINE_BIND_POINT_GRAPHICS, graphicsPipeline);
			recorder.bindGraphicsDescriptors(pipelineLayout, descriptorSet);
			vkCmdBindVertexBuffers(
					recorder.commandBuffer, 0,
					recorder.stack.longs(vertices.vkBuffer()),
					recorder.stack.longs(0)
			);
			vkCmdDraw(recorder.commandBuffer, 6, 4, 0, 0);
			recorder.endDynamicRendering();

			recorder.transitionLayout(targetImage, ResourceUsage.COLOR_ATTACHMENT_WRITE, ResourceUsage.TRANSFER_SOURCE);
			recorder.copyImageToBuffer(targetImage, recovered.fullRange());
		});
		commands.destroy();

		var recoveredImage = boiler.buffers.decodeBufferedImageRGBA(recovered, 0L, targetImage.width(), targetImage.height());
		assertImageEquals(recoveredEarth(1), recoveredImage.getSubimage(0, 0, input.getWidth(), input.getHeight()));
		assertImageEquals(recoveredEarth(2), recoveredImage.getSubimage(
				input.getWidth(), 0, input.getWidth(), input.getHeight()
		));
		assertImageEquals(recoveredEarth(4), recoveredImage.getSubimage(
				0, input.getHeight(), input.getWidth(), input.getHeight()
		));
		assertImageEquals(recoveredEarth(8), recoveredImage.getSubimage(
				input.getWidth(), input.getHeight(), input.getWidth(), input.getHeight()
		));

		descriptorPool.destroy();
		descriptorLayout.destroy();
		recovered.destroy(boiler);
		compressed.destroy(boiler);
		vertices.destroy(boiler);
		targetImage.destroy(boiler);
		vkDestroyPipeline(boiler.vkDevice(), graphicsPipeline, null);
		vkDestroyPipelineLayout(boiler.vkDevice(), pipelineLayout, null);
		boiler.destroyInitialObjects();
	}
}
