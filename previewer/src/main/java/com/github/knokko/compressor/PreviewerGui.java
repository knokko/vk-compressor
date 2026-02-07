package com.github.knokko.compressor;

import com.github.knokko.boiler.BoilerInstance;
import com.github.knokko.boiler.commands.CommandRecorder;
import com.github.knokko.boiler.descriptors.DescriptorUpdater;
import com.github.knokko.boiler.images.VkbImage;
import com.github.knokko.boiler.memory.callbacks.CallbackUserData;
import com.github.knokko.boiler.pipelines.GraphicsPipelineBuilder;
import com.github.knokko.boiler.synchronization.ResourceUsage;
import com.github.knokko.boiler.window.AcquiredImage;
import com.github.knokko.boiler.window.SimpleWindowRenderLoop;
import com.github.knokko.boiler.window.SwapchainResourceManager;
import com.github.knokko.boiler.window.VkbWindow;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.vulkan.VkClearValue;
import org.lwjgl.vulkan.VkRenderPassBeginInfo;

import static org.lwjgl.system.MemoryStack.stackPush;
import static org.lwjgl.vulkan.KHRSurface.VK_PRESENT_MODE_FIFO_KHR;
import static org.lwjgl.vulkan.VK10.*;
import static org.lwjgl.vulkan.VK10.vkCmdEndRenderPass;

public class PreviewerGui extends SimpleWindowRenderLoop {

	public static long createGraphicsPipeline(
			BoilerInstance boiler, MemoryStack stack,
			long pipelineLayout, long renderPass, boolean blend
	) {
		var builder = new GraphicsPipelineBuilder(boiler, stack);
		builder.simpleShaderStages(
				"preview", "com/github/knokko/compressor/",
				"preview.vert.spv", "preview.frag.spv"
		);
		builder.noVertexInput();
		builder.simpleInputAssembly();
		builder.dynamicViewports(1);
		builder.simpleRasterization(VK_CULL_MODE_NONE);
		builder.noMultisampling();
		builder.noDepthStencil();
		if (blend) builder.simpleColorBlending(1);
		else builder.noColorBlending(1);
		builder.dynamicStates(VK_DYNAMIC_STATE_VIEWPORT, VK_DYNAMIC_STATE_SCISSOR);
		builder.ciPipeline.layout(pipelineLayout);
		builder.ciPipeline.renderPass(renderPass);
		builder.ciPipeline.subpass(0);
		return builder.build("PreviewBlend=" + blend);
	}

	private final VkbImage previewImage;
	private final long sampler, renderPass, pipelineLayout, graphicsPipeline, descriptorSet;
	private SwapchainResourceManager<Object, Long> swapchainResources;
	private final boolean renderSigned;

	public PreviewerGui(
			BoilerInstance boiler, VkbWindow window, VkbImage previewImage, long sampler, long renderPass,
			long pipelineLayout, long descriptorSet, boolean renderSigned
	) {
		super(
				window, false, VK_PRESENT_MODE_FIFO_KHR,
				ResourceUsage.COLOR_ATTACHMENT_WRITE, ResourceUsage.COLOR_ATTACHMENT_WRITE
		);
		this.previewImage = previewImage;
		this.sampler = sampler;
		this.renderPass = renderPass;
		this.pipelineLayout = pipelineLayout;
		try (MemoryStack stack = stackPush()) {
			this.graphicsPipeline = createGraphicsPipeline(boiler, stack, pipelineLayout, renderPass, true);
		}
		this.descriptorSet = descriptorSet;
		this.renderSigned = renderSigned;
	}

	@Override
	protected void setup(BoilerInstance boiler, MemoryStack stack) {
		super.setup(boiler, stack);
		var updater = new DescriptorUpdater(stack, 1);
		updater.writeImage(0, descriptorSet, 0, previewImage.vkImageView, sampler);
		updater.update(boiler);
		this.swapchainResources = new SwapchainResourceManager<>() {

			@Override
			protected Long createImage(Object swapchain, AcquiredImage swapchainImage) {
				return boiler.images.createFramebuffer(
						renderPass, swapchainImage.getWidth(), swapchainImage.getHeight(),
						"SwapchainFramebuffer", swapchainImage.getImage().vkImageView
				);
			}

			@Override
			protected void destroyImage(Long framebuffer) {
				try (var stack = stackPush()) {
					vkDestroyFramebuffer(
							boiler.vkDevice(), framebuffer,
							CallbackUserData.FRAME_BUFFER.put(stack, boiler)
					);
				}
			}
		};
	}

	@Override
	protected void recordFrame(
			MemoryStack stack, int frameIndex, CommandRecorder recorder,
			AcquiredImage acquiredImage, BoilerInstance boiler
	) {
		var biRenderPass = VkRenderPassBeginInfo.calloc(recorder.stack);
		biRenderPass.sType$Default();
		biRenderPass.renderPass(renderPass);
		biRenderPass.framebuffer(swapchainResources.getImageAssociation(acquiredImage));
		biRenderPass.renderArea().extent().set(acquiredImage.getWidth(), acquiredImage.getHeight());
		biRenderPass.clearValueCount(1);
		biRenderPass.pClearValues(VkClearValue.calloc(1, recorder.stack));

		vkCmdBeginRenderPass(recorder.commandBuffer, biRenderPass, VK_SUBPASS_CONTENTS_INLINE);
		vkCmdBindPipeline(recorder.commandBuffer, VK_PIPELINE_BIND_POINT_GRAPHICS, graphicsPipeline);
		recorder.bindGraphicsDescriptors(pipelineLayout, descriptorSet);
		recorder.dynamicViewportAndScissor(acquiredImage.getWidth(), acquiredImage.getHeight());
		vkCmdPushConstants(
				recorder.commandBuffer, pipelineLayout, VK_SHADER_STAGE_FRAGMENT_BIT, 0,
				recorder.stack.ints(renderSigned ? 1 : 0)
		);
		vkCmdDraw(recorder.commandBuffer, 6, 1, 0, 0);
		vkCmdEndRenderPass(recorder.commandBuffer);
	}

	@Override
	protected void cleanUp(BoilerInstance boiler) {
		super.cleanUp(boiler);
		vkDestroyPipeline(boiler.vkDevice(), graphicsPipeline, null);
	}
}
