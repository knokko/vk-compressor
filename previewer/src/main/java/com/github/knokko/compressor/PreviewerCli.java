package com.github.knokko.compressor;

import com.github.knokko.boiler.builders.BoilerBuilder;
import com.github.knokko.boiler.builders.WindowBuilder;
import com.github.knokko.boiler.commands.SingleTimeCommands;
import com.github.knokko.boiler.descriptors.DescriptorCombiner;
import com.github.knokko.boiler.descriptors.DescriptorSetLayoutBuilder;
import com.github.knokko.boiler.descriptors.DescriptorUpdater;
import com.github.knokko.boiler.descriptors.VkbDescriptorSetLayout;
import com.github.knokko.boiler.images.ImageBuilder;
import com.github.knokko.boiler.images.VkbImage;
import com.github.knokko.boiler.memory.MemoryBlock;
import com.github.knokko.boiler.memory.MemoryCombiner;
import com.github.knokko.boiler.pipelines.GraphicsPipelineBuilder;
import com.github.knokko.boiler.pipelines.SimpleRenderPass;
import com.github.knokko.boiler.synchronization.ResourceUsage;
import com.github.knokko.boiler.window.WindowEventLoop;
import org.apache.commons.cli.DefaultParser;
import org.apache.commons.cli.Option;
import org.apache.commons.cli.Options;
import org.apache.commons.cli.ParseException;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.vulkan.*;

import java.io.File;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.IntBuffer;
import java.nio.file.Files;

import static com.github.knokko.boiler.utilities.BoilerMath.nextMultipleOf;
import static com.github.knokko.boiler.utilities.ColorPacker.*;
import static org.lwjgl.stb.STBImageWrite.stbi_write_png;
import static org.lwjgl.system.MemoryStack.stackPush;
import static org.lwjgl.vulkan.VK10.*;

public class PreviewerCli {

	private static String runPreviewer(String[] args) throws ParseException, IOException {
		var options = new Options();
		var helpOption = Option.builder("H")
				.longOpt("help")
				.desc("Prints the help page")
				.build();
		var widthOption = Option.builder("w")
				.longOpt("width")
				.desc("The width of the (original) image. " +
						"This is needed because the width is not always stored in the compressed file.")
				.hasArg()
				.type(Integer.class)
				.build();
		var heightOption = Option.builder("h")
				.longOpt("height")
				.desc("The height of the (original) image. " +
						"This is needed because the height is not always stored in the compressed file.")
				.hasArg()
				.type(Integer.class)
				.build();
		var signedOption = Option.builder("s")
				.longOpt("signed")
				.desc("For bc4 encoding only, whether the data is signed (SNORM) rather than unsigned (UNORM)")
				.build();
		var guiOption = Option.builder("g")
				.longOpt("gui")
				.desc("Preview the image in a window/gui")
				.build();
		var validationOption = Option.builder("v")
				.longOpt("validation")
				.desc("Enable Vulkan Validation Layer (VVL)")
				.build();
		var exactKimOption = Option.builder("e")
				.longOpt("exact-kim-decompression")
				.desc("Use the exact CPU kim1/3 decompressor rather than the kim1/3 fragment shaders")
				.build();
		options.addOption(helpOption);
		options.addOption(widthOption);
		options.addOption(heightOption);
		options.addOption(guiOption);
		options.addOption(signedOption);
		options.addOption(validationOption);
		options.addOption(exactKimOption);

		var parser = new DefaultParser();
		var cmd = parser.parse(options, args);

		if (cmd.hasOption("help")) {
			System.out.println("Use e.g.: vkc-preview compressed-image.bc1 --width 128 --height 128 --gui");
			System.out.println("Options:");
			for (var option : options.getOptions()) {
				System.out.println("  " + option);
			}
			return null;
		}

		boolean signed = cmd.hasOption(signedOption);
		boolean renderSigned = false;

		File[] files = cmd.getArgList().stream().map(File::new).toArray(File[]::new);
		if (files.length != 1) return "You must specify exactly 1 file to preview";
		var inputFile = files[0];
		if (!inputFile.isFile()) return "Can't find file " + inputFile.getAbsolutePath();
		var fileContent = Files.readAllBytes(inputFile.toPath());
		var inputName = inputFile.getName();

		var boilerBuilder = new BoilerBuilder(
				VK_API_VERSION_1_0, "vk-compressor previewer", 1
		).doNotUseVma();
		if (cmd.hasOption(guiOption)) {
			boilerBuilder.addWindow(new WindowBuilder(800, 500, 1));
		}
		if (cmd.hasOption(validationOption)) {
			boilerBuilder.validation();
			boilerBuilder.forbidValidationErrors();
		} else {
			boilerBuilder.hideDeviceSelectionInfo();
		}

		var boiler = boilerBuilder.build();

		int swapchainFormat = VK_FORMAT_R8G8B8A8_SRGB;
		if (cmd.hasOption(guiOption)) swapchainFormat = boiler.window().properties.surfaceFormat();

		var sampler = boiler.images.createSimpleSampler(
				VK_FILTER_NEAREST, VK_SAMPLER_MIPMAP_MODE_NEAREST,
				VK_SAMPLER_ADDRESS_MODE_CLAMP_TO_BORDER, "SimpleSampler"
		);
		VkbDescriptorSetLayout descriptorLayout;
		long renderPass, graphicsPipeline, pipelineLayout;
		long descriptorPool;
		long[] descriptorSet;
		try (var stack = stackPush()) {
			renderPass = SimpleRenderPass.create(
					boiler, "SimpleRenderPass", null, new SimpleRenderPass.ColorAttachment(
							swapchainFormat, VK_ATTACHMENT_LOAD_OP_CLEAR,
							VK_ATTACHMENT_STORE_OP_STORE, VK_SAMPLE_COUNT_1_BIT
					)
			);
			var descriptorBuilder = new DescriptorSetLayoutBuilder(stack, 1);
			descriptorBuilder.set(
					0, 0,
					VK_DESCRIPTOR_TYPE_COMBINED_IMAGE_SAMPLER,
					VK_SHADER_STAGE_FRAGMENT_BIT
			);
			descriptorLayout = descriptorBuilder.build(boiler, "SingleImageDescriptorLayout");

			var combiner = new DescriptorCombiner(boiler);
			descriptorSet = combiner.addMultiple(descriptorLayout, 1);
			descriptorPool = combiner.build("SingleImageDescriptorPool");

			var pushConstants = VkPushConstantRange.calloc(1, stack);
			//noinspection resource
			pushConstants.get(0).set(VK_SHADER_STAGE_FRAGMENT_BIT, 0, 4);
			pipelineLayout = boiler.pipelines.createLayout(
					pushConstants, "Preview", descriptorLayout.vkDescriptorSetLayout
			);

			graphicsPipeline = PreviewerGui.createGraphicsPipeline(
					boiler, stack, pipelineLayout, renderPass, cmd.hasOption(guiOption)
			);
		}

		VkbImage image;
		MemoryBlock imageMemory;
		if (inputName.endsWith(".bc1") || inputName.endsWith(".bc4") || inputName.endsWith(".bc7")) {
			if (cmd.hasOption(exactKimOption)) {
				return "The --exact-kim-decompression option is only allowed for KIM previewing";
			}

			if (!cmd.hasOption(widthOption)) return "The --width option is required for previewing BC files";
			int width = cmd.getParsedOptionValue(widthOption);
			if (width <= 0) return "width must be positive";
			int paddedWidth = nextMultipleOf(width, 4);

			if (!cmd.hasOption(heightOption)) return "The --height option is required for previewing BC files";
			int height = cmd.getParsedOptionValue(heightOption);
			if (height <= 0) return "height must be positive";
			int paddedHeight = nextMultipleOf(height, 4);

			int numBlocks = paddedWidth * paddedHeight / 16;
			int bytesPerBlock = inputName.endsWith(".bc1") || inputName.endsWith(".bc4") ? 8 : 16;
			int expectedSize = numBlocks * bytesPerBlock;
			if (fileContent.length != expectedSize) {
				return "Unexpected file size " + fileContent.length + ": expected " + expectedSize;
			}

			int format = VK_FORMAT_UNDEFINED;
			if (inputName.endsWith(".bc1")) format = VK_FORMAT_BC1_RGBA_SRGB_BLOCK;
			if (inputName.endsWith(".bc4")) {
				if (signed) format = VK_FORMAT_BC4_SNORM_BLOCK;
				else format = VK_FORMAT_BC4_UNORM_BLOCK;
			}
			if (inputName.endsWith(".bc7")) format = VK_FORMAT_BC7_SRGB_BLOCK;
			if (format == VK_FORMAT_UNDEFINED) throw new IllegalStateException("Can't handle " + inputName);

			var combiner = new MemoryCombiner(boiler, "PreviewerMemory");
			var stagingBuffer = combiner.addMappedBuffer(expectedSize, bytesPerBlock, VK_BUFFER_USAGE_TRANSFER_SRC_BIT);
			var bcImage = combiner.addImage(new ImageBuilder(
					"BcImage", width, height
			).texture().format(format), 0.5f);
			image = combiner.addImage(new ImageBuilder(
					"RgbImage", width, height
			).colorAttachment().format(swapchainFormat).addUsage(
					VK_IMAGE_USAGE_SAMPLED_BIT | VK_IMAGE_USAGE_TRANSFER_SRC_BIT
			), 0.75f);
			imageMemory = combiner.build(false);

			long previewFramebuffer = boiler.images.createFramebuffer(
					renderPass, width, height, "PreviewFramebuffer", image.vkImageView
			);
			var rgbImage = image;

			SingleTimeCommands.submit(boiler, "PreviewTransfer", recorder -> {
				var updater = new DescriptorUpdater(recorder.stack, 1);
				updater.writeImage(0, descriptorSet[0], 0, bcImage.vkImageView, sampler);
				updater.update(boiler);

				stagingBuffer.byteBuffer().put(fileContent);
				recorder.transitionLayout(bcImage, null, ResourceUsage.TRANSFER_DEST);
				recorder.copyBufferToImage(bcImage, stagingBuffer);
				recorder.transitionLayout(bcImage, ResourceUsage.TRANSFER_DEST, ResourceUsage.shaderRead(
						VK_PIPELINE_STAGE_FRAGMENT_SHADER_BIT
				));
				recorder.transitionLayout(rgbImage, null, ResourceUsage.COLOR_ATTACHMENT_WRITE);

				var biRenderPass = VkRenderPassBeginInfo.calloc(recorder.stack);
				biRenderPass.sType$Default();
				biRenderPass.renderPass(renderPass);
				biRenderPass.framebuffer(previewFramebuffer);
				biRenderPass.renderArea().extent().set(width, height);
				biRenderPass.clearValueCount(1);
				biRenderPass.pClearValues(VkClearValue.calloc(1, recorder.stack));

				vkCmdBeginRenderPass(recorder.commandBuffer, biRenderPass, VK_SUBPASS_CONTENTS_INLINE);
				vkCmdBindPipeline(recorder.commandBuffer, VK_PIPELINE_BIND_POINT_GRAPHICS, graphicsPipeline);
				recorder.bindGraphicsDescriptors(pipelineLayout, descriptorSet);
				recorder.dynamicViewportAndScissor(width, height);
				vkCmdPushConstants(
						recorder.commandBuffer, pipelineLayout, VK_SHADER_STAGE_FRAGMENT_BIT, 0,
						recorder.stack.ints(signed ? 1 : 0)
				);
				vkCmdDraw(recorder.commandBuffer, 6, 1, 0, 0);
				vkCmdEndRenderPass(recorder.commandBuffer);

				if (cmd.hasOption(guiOption)) {
					recorder.transitionLayout(rgbImage, ResourceUsage.COLOR_ATTACHMENT_WRITE, ResourceUsage.shaderRead(
							VK_PIPELINE_STAGE_FRAGMENT_SHADER_BIT
					));
				} else {
					recorder.transitionLayout(
							rgbImage, ResourceUsage.COLOR_ATTACHMENT_WRITE, ResourceUsage.TRANSFER_SOURCE
					);
				}
			}).destroy();

			vkDestroyFramebuffer(boiler.vkDevice(), previewFramebuffer, null);
		} else if (inputName.endsWith(".kim1") || inputName.endsWith(".kim2") || inputName.endsWith(".kim3")) {
			if (cmd.hasOption(exactKimOption)) {
				int width, height;
				ByteBuffer imageData;
				if (inputName.endsWith(".kim1")) {
					var decompressor = new Kim1Decompressor(ByteBuffer.wrap(fileContent));
					width = decompressor.width;
					height = decompressor.height;
					imageData = ByteBuffer.allocate(4 * width * height);
					for (int y = 0; y < height; y++) {
						for (int x = 0; x < width; x++) {
							int color = decompressor.getColor(x, y);
							imageData.put(red(color));
							imageData.put(green(color));
							imageData.put(blue(color));
							imageData.put(alpha(color));
						}
					}
				} else if (inputName.endsWith(".kim2")) {
					var inputIntBuffer = ByteBuffer.wrap(fileContent).asIntBuffer();
					int header = inputIntBuffer.get(0);
					width = Kim2Decompressor.getWidth(header);
					height = Kim2Decompressor.getHeight(header);

					imageData = ByteBuffer.allocate(4 * width * height);
					var decompressedColors = IntBuffer.allocate(width * height);
					Kim2Decompressor.decompress(inputIntBuffer, decompressedColors);
					decompressedColors.flip();
					for (int y = 0; y < height; y++) {
						for (int x = 0; x < width; x++) {
							int color = decompressedColors.get();
							imageData.put(red(color));
							imageData.put(green(color));
							imageData.put(blue(color));
							imageData.put(alpha(color));
						}
					}
				} else if (inputName.endsWith(".kim3")) {
					var decompressor = new Kim3Decompressor(ByteBuffer.wrap(fileContent));
					width = decompressor.width;
					height = decompressor.height;
					imageData = ByteBuffer.allocate(4 * width * height);
					for (int y = 0; y < height; y++) {
						for (int x = 0; x < width; x++) {
							int color = decompressor.getColor(x, y);
							imageData.put(red(color));
							imageData.put(green(color));
							imageData.put(blue(color));
							imageData.put(alpha(color));
						}
					}
				} else {
					throw new Error("Unexpected file name " + inputName);
				}

				var combiner = new MemoryCombiner(boiler, "PreviewerMemory");
				var stagingBuffer = combiner.addMappedBuffer(
						4L * width * height, 4, VK_BUFFER_USAGE_TRANSFER_SRC_BIT
				);
				image = combiner.addImage(new ImageBuilder(
						"Image", width, height
				).format(VK_FORMAT_R8G8B8A8_SRGB).setUsage(
						VK_IMAGE_USAGE_SAMPLED_BIT | VK_IMAGE_USAGE_TRANSFER_SRC_BIT | VK_IMAGE_USAGE_TRANSFER_DST_BIT
				), 0.5f);
				imageMemory = combiner.build(false);

				var finalImage = image;
				SingleTimeCommands.submit(boiler, "PreviewTransfer", recorder -> {
					imageData.flip();
					stagingBuffer.byteBuffer().put(imageData);
					recorder.transitionLayout(finalImage, null, ResourceUsage.TRANSFER_DEST);
					recorder.copyBufferToImage(finalImage, stagingBuffer);

					if (cmd.hasOption(guiOption)) {
						recorder.transitionLayout(finalImage, ResourceUsage.TRANSFER_DEST, ResourceUsage.shaderRead(
								VK_PIPELINE_STAGE_FRAGMENT_SHADER_BIT
						));
					} else {
						recorder.transitionLayout(
								finalImage, ResourceUsage.TRANSFER_DEST, ResourceUsage.TRANSFER_SOURCE
						);
					}
				}).destroy();
			} else {
				int width, height;
				int header = ByteBuffer.wrap(fileContent).getInt(0);
				if (inputName.endsWith(".kim1")) {
					width = Kim1Decompressor.width(header);
					height = Kim1Decompressor.height(header);
				} else if (inputName.endsWith(".kim2")) {
					width = Kim2Decompressor.getWidth(header);
					height = Kim2Decompressor.getHeight(header);
				} else if (inputName.endsWith(".kim3")) {
					width = Kim3Decompressor.width(header);
					height = Kim3Decompressor.height(header);
				} else {
					throw new Error("Unexpected file name " + inputName);
				}

				var combiner = new MemoryCombiner(boiler, "PreviewerMemory");
				var kimBuffer = combiner.addMappedBuffer(
						fileContent.length, boiler.deviceProperties.limits().minStorageBufferOffsetAlignment(),
						VK_BUFFER_USAGE_STORAGE_BUFFER_BIT
				);
				image = combiner.addImage(new ImageBuilder(
						"Image", width, height
				).format(swapchainFormat).setUsage(
						VK_IMAGE_USAGE_SAMPLED_BIT | VK_IMAGE_USAGE_TRANSFER_SRC_BIT | VK_IMAGE_USAGE_COLOR_ATTACHMENT_BIT
				), 0.5f);
				var vertexBuffer = combiner.addMappedBuffer(8, 4, VK_BUFFER_USAGE_VERTEX_BUFFER_BIT);
				imageMemory = combiner.build(false);

				kimBuffer.intBuffer().put(ByteBuffer.wrap(fileContent).asIntBuffer());
				var vertexByteBuffer = vertexBuffer.byteBuffer();

				long previewFramebuffer = boiler.images.createFramebuffer(
						renderPass, width, height, "PreviewFramebuffer", image.vkImageView
				);

				VkbDescriptorSetLayout kimDescriptorSetLayout;
				long kimPipelineLayout, kimGraphicsPipeline, kimDescriptorPool;
				long[] kimDescriptorSet;
				try (MemoryStack stack = stackPush()) {
					var layoutBuilder = new DescriptorSetLayoutBuilder(stack, 1);
					layoutBuilder.set(0, 0, VK_DESCRIPTOR_TYPE_STORAGE_BUFFER, VK_SHADER_STAGE_FRAGMENT_BIT);
					kimDescriptorSetLayout = layoutBuilder.build(boiler, "KimDescriptorLayout");

					kimPipelineLayout = boiler.pipelines.createLayout(
							null, "KimPipelineLayout", kimDescriptorSetLayout.vkDescriptorSetLayout
					);

					var descriptors = new DescriptorCombiner(boiler);
					kimDescriptorSet = descriptors.addMultiple(kimDescriptorSetLayout, 1);
					kimDescriptorPool = descriptors.build("KimDescriptorPool");

					var builder = new GraphicsPipelineBuilder(boiler, stack);

					var vertexAttributes = VkVertexInputAttributeDescription.calloc(1, stack);
					//noinspection resource
					vertexAttributes.get(0).set(0, 0, VK_FORMAT_R32G32_UINT, 0);

					var vertexBindings = VkVertexInputBindingDescription.calloc(1, stack);
					//noinspection resource
					vertexBindings.get(0).set(0, 8, VK_VERTEX_INPUT_RATE_INSTANCE);

					vertexByteBuffer.putInt(width).putInt(height);

					var vertexInput = VkPipelineVertexInputStateCreateInfo.calloc(stack);
					vertexInput.sType$Default();
					vertexInput.pVertexAttributeDescriptions(vertexAttributes);
					vertexInput.pVertexBindingDescriptions(vertexBindings);

					if (inputName.endsWith(".kim1")) {
						builder.simpleShaderStages(
								"Kim1", "com/github/knokko/compressor/",
								"preview-kim.vert.spv", "preview-kim1.frag.spv"
						);
					} else if (inputName.endsWith(".kim2")) {
						builder.simpleShaderStages(
								"Kim2", "com/github/knokko/compressor/",
								"preview-kim.vert.spv", "preview-kim2.frag.spv"
						);
					} else if (inputName.endsWith(".kim3")) {
						builder.simpleShaderStages(
								"Kim3", "com/github/knokko/compressor/",
								"preview-kim.vert.spv", "preview-kim3.frag.spv"
						);
					} else {
						throw new Error("Weird file name " + inputName);
					}

					vertexInput.pVertexBindingDescriptions(vertexBindings);
					builder.ciPipeline.pVertexInputState(vertexInput);
					builder.simpleInputAssembly();
					builder.fixedViewport(width, height);
					builder.simpleRasterization(VK_CULL_MODE_NONE);
					builder.noMultisampling();
					builder.noDepthStencil();

					if (cmd.hasOption(guiOption)) builder.simpleColorBlending(1);
					else builder.noColorBlending(1);

					builder.ciPipeline.layout(kimPipelineLayout);
					builder.ciPipeline.renderPass(renderPass);
					builder.ciPipeline.subpass(0);

					kimGraphicsPipeline = builder.build("KimPipeline");
				}

				var finalImage = image;
				SingleTimeCommands.submit(boiler, "PreviewRendering", recorder -> {
					recorder.transitionLayout(finalImage, null, ResourceUsage.COLOR_ATTACHMENT_WRITE);

					var descriptorUpdater = new DescriptorUpdater(recorder.stack, 1);
					descriptorUpdater.writeStorageBuffer(0, kimDescriptorSet[0], 0, kimBuffer);
					descriptorUpdater.update(boiler);

					var biRenderPass = VkRenderPassBeginInfo.calloc(recorder.stack);
					biRenderPass.sType$Default();
					biRenderPass.renderPass(renderPass);
					biRenderPass.framebuffer(previewFramebuffer);
					biRenderPass.renderArea().extent().set(width, height);
					biRenderPass.clearValueCount(1);
					biRenderPass.pClearValues(VkClearValue.calloc(1, recorder.stack));

					vkCmdBeginRenderPass(recorder.commandBuffer, biRenderPass, VK_SUBPASS_CONTENTS_INLINE);
					vkCmdBindPipeline(recorder.commandBuffer, VK_PIPELINE_BIND_POINT_GRAPHICS, kimGraphicsPipeline);
					recorder.bindGraphicsDescriptors(kimPipelineLayout, kimDescriptorSet[0]);
					recorder.bindVertexBuffers(0, vertexBuffer);
					vkCmdDraw(recorder.commandBuffer, 6, 1, 0, 0);
					vkCmdEndRenderPass(recorder.commandBuffer);

					if (cmd.hasOption(guiOption)) {
						recorder.transitionLayout(finalImage, ResourceUsage.COLOR_ATTACHMENT_WRITE, ResourceUsage.shaderRead(
								VK_PIPELINE_STAGE_FRAGMENT_SHADER_BIT
						));
					} else {
						recorder.transitionLayout(
								finalImage, ResourceUsage.COLOR_ATTACHMENT_WRITE, ResourceUsage.TRANSFER_SOURCE
						);
					}
				}).destroy();

				vkDestroyFramebuffer(boiler.vkDevice(), previewFramebuffer, null);
				vkDestroyDescriptorPool(boiler.vkDevice(), kimDescriptorPool, null);
				vkDestroyPipeline(boiler.vkDevice(), kimGraphicsPipeline, null);
				vkDestroyPipelineLayout(boiler.vkDevice(), kimPipelineLayout, null);
				vkDestroyDescriptorSetLayout(boiler.vkDevice(), kimDescriptorSetLayout.vkDescriptorSetLayout, null);
			}
		} else if (inputName.endsWith(".rgba8") || inputName.endsWith(".r8")) {
			if (cmd.hasOption(exactKimOption)) {
				return "The --exact-kim-decompression option is only allowed for KIM previewing";
			}
			if (!cmd.hasOption(widthOption)) {
				return "The --width option is required for previewing R8 or RGBA8 files";
			}
			int width = cmd.getParsedOptionValue(widthOption);
			if (width <= 0) return "width must be positive";

			if (!cmd.hasOption(heightOption)) return "The --height option is required for previewing R8 or RGBA8 files";
			int height = cmd.getParsedOptionValue(heightOption);
			if (height <= 0) return "height must be positive";

			int bytesPerPixel = 0;
			if (inputName.endsWith(".r8")) bytesPerPixel = 1;
			if (inputName.endsWith(".rgba8")) bytesPerPixel = 4;
			int expectedSize = width * height * bytesPerPixel;
			if (fileContent.length != expectedSize) {
				return "Unexpected file size " + fileContent.length + ": expected " + expectedSize;
			}

			int format = VK_FORMAT_UNDEFINED;
			if (inputName.endsWith(".r8")) {
				format = signed ? VK_FORMAT_R8_SNORM : VK_FORMAT_R8_UNORM;
				renderSigned = signed;
			}
			if (inputName.endsWith(".rgba8")) format = VK_FORMAT_R8G8B8A8_SRGB;
			if (format == VK_FORMAT_UNDEFINED) throw new IllegalStateException("Can't handle " + inputName);

			var combiner = new MemoryCombiner(boiler, "PreviewerMemory");
			var stagingBuffer = combiner.addMappedBuffer(expectedSize, 4, VK_BUFFER_USAGE_TRANSFER_SRC_BIT);
			image = combiner.addImage(new ImageBuilder(
					"Image", width, height
			).format(format).setUsage(
					VK_IMAGE_USAGE_SAMPLED_BIT | VK_IMAGE_USAGE_TRANSFER_SRC_BIT | VK_IMAGE_USAGE_TRANSFER_DST_BIT
			), 0.5f);
			imageMemory = combiner.build(false);

			var finalImage = image;
			SingleTimeCommands.submit(boiler, "PreviewTransfer", recorder -> {
				stagingBuffer.byteBuffer().put(fileContent);
				recorder.transitionLayout(finalImage, null, ResourceUsage.TRANSFER_DEST);
				recorder.copyBufferToImage(finalImage, stagingBuffer);

				if (cmd.hasOption(guiOption)) {
					recorder.transitionLayout(finalImage, ResourceUsage.TRANSFER_DEST, ResourceUsage.shaderRead(
							VK_PIPELINE_STAGE_FRAGMENT_SHADER_BIT
					));
				} else {
					recorder.transitionLayout(
							finalImage, ResourceUsage.TRANSFER_DEST, ResourceUsage.TRANSFER_SOURCE
					);
				}
			}).destroy();
		} else {
			return "Can't guess format from file name " + inputName + ": the file name should end with e.g. \".bc1\"";
		}

		assert imageMemory != null;

		if (cmd.hasOption(guiOption)) {
			var eventLoop = new WindowEventLoop();
			eventLoop.addWindow(new PreviewerGui(
					boiler, boiler.window(), image, sampler, renderPass,
					pipelineLayout, descriptorSet[0], renderSigned
			));
			eventLoop.runMain();
		} else {
			var outputFile = new File(inputFile + ".png");
			var combiner = new MemoryCombiner(boiler, "ReadbackMemory");
			var readbackBuffer = combiner.addMappedBuffer(
					4L * image.width * image.height,
					4L, VK_BUFFER_USAGE_TRANSFER_DST_BIT
			);
			var readbackMemory = combiner.build(false);

			SingleTimeCommands.submit(boiler, "ReadbackTransfer", recorder -> {
				recorder.copyImageToBuffer(image, readbackBuffer);
				recorder.bufferBarrier(readbackBuffer, ResourceUsage.TRANSFER_DEST, ResourceUsage.HOST_READ);
			}).destroy();

			if (!stbi_write_png(outputFile.getAbsolutePath(), image.width, image.height, 4, readbackBuffer.byteBuffer(), 0)) {
				return "STBImage failed to write the output image";
			}
			readbackMemory.destroy(boiler);
		}

		vkDestroyPipeline(boiler.vkDevice(), graphicsPipeline, null);
		vkDestroyPipelineLayout(boiler.vkDevice(), pipelineLayout, null);
		vkDestroyRenderPass(boiler.vkDevice(), renderPass, null);
		vkDestroyDescriptorPool(boiler.vkDevice(), descriptorPool, null);
		vkDestroyDescriptorSetLayout(boiler.vkDevice(), descriptorLayout.vkDescriptorSetLayout, null);
		imageMemory.destroy(boiler);
		vkDestroySampler(boiler.vkDevice(), sampler, null);
		boiler.destroyInitialObjects();

		return null;
	}

	public static void main(String[] args) throws ParseException, IOException {
		String errorMessage = runPreviewer(args);
		if (errorMessage != null) {
			System.err.println(errorMessage);
			System.exit(1);
		}
	}
}
