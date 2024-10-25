package com.github.knokko.compressor;

import com.github.knokko.boiler.builders.BoilerBuilder;
import org.junit.jupiter.api.Test;

import javax.imageio.ImageIO;

import java.awt.*;
import java.awt.image.BufferedImage;
import java.io.File;
import java.io.IOException;

import static com.github.knokko.boiler.utilities.ColorPacker.*;
import static org.junit.jupiter.api.Assertions.assertEquals;
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

		for (int blockHeight = 1; blockHeight <= 2; blockHeight++) {
			for (int blockWidth = 1; blockWidth <= 2; blockWidth++) {
				assertEquals(28, compressor.predictByteSize(blockWidth, 1));

				var compressedImage = memAlloc(28);
				compressor.compress(compressedImage, blockWidth, 1);
				compressedImage.position(0);

				var decompressor = new Kim1Decompressor(compressedImage);
				assertEquals(2, decompressor.width);
				assertEquals(2, decompressor.height);

				assertEquals(rgb(1, 2, 3), decompressor.getColor(0, 0));
				assertEquals(rgba(100, 101, 102, 103), decompressor.getColor(1, 0));
				assertEquals(rgb(200, 201, 202), decompressor.getColor(0, 1));
				assertEquals(rgb(200, 201, 202), decompressor.getColor(1, 1));

				memFree(compressedImage);
			}
		}

		memFree(simpleImage);
	}

	@Test
	public void testFlameTongue() throws IOException {
		var imageInput = TestKim1Compression.class.getResourceAsStream("mardek/Flametongue.png");
		var sourceImage = ImageIO.read(imageInput);
		imageInput.close();

		var boiler = new BoilerBuilder(
				VK_API_VERSION_1_0, "TestKim1Compression", 1
		).build();

		var rawBuffer = boiler.buffers.createMapped(
				sourceImage.getWidth() * sourceImage.getHeight() * 4L,
				VK_BUFFER_USAGE_STORAGE_BUFFER_BIT, "RawBuffer"
		);
		boiler.buffers.encodeBufferedImageRGBA(rawBuffer, sourceImage, 0L);

		int[] blockSizes = { 1, 2, 4, 8, 16 };

		var compressor = new Kim1Compressor(
				rawBuffer.fullMappedRange().byteBuffer(),
				sourceImage.getWidth(), sourceImage.getHeight(), 4
		);

		for (int blockWidth : blockSizes) {
			if (sourceImage.getWidth() % blockWidth != 0) continue;
			for (int blockHeight : blockSizes) {
				if (sourceImage.getHeight() % blockHeight != 0) continue;
				int compressedSize = compressor.predictByteSize(blockWidth, blockHeight);
				System.out.println("compressed size is " + compressedSize);

				var compressedData = memCalloc(compressedSize);
				compressor.compress(compressedData, blockWidth, blockHeight);
				compressedData.position(0);

				var decompressor = new Kim1Decompressor(compressedData);
				assertEquals(sourceImage.getWidth(), decompressor.width);
				assertEquals(sourceImage.getHeight(), decompressor.height);

				BufferedImage decompressed = new BufferedImage(sourceImage.getWidth(), sourceImage.getHeight(), BufferedImage.TYPE_INT_ARGB);
				for (int y = 0; y < sourceImage.getHeight(); y++) {
					for (int x = 0; x < sourceImage.getWidth(); x++) {
						var expectedColor = new Color(sourceImage.getRGB(x, y), true);
						int actualColor = decompressor.getColor(x, y);
						decompressed.setRGB(x, y, new Color(unsigned(red(actualColor)), unsigned(green(actualColor)), unsigned(blue(actualColor)), unsigned(alpha(actualColor))).getRGB());
						assertEquals(expectedColor.getRed(), unsigned(red(actualColor)));
						assertEquals(expectedColor.getGreen(), unsigned(green(actualColor)));
						assertEquals(expectedColor.getBlue(), unsigned(blue(actualColor)));
						assertEquals(expectedColor.getAlpha(), unsigned(alpha(actualColor)));
					}
				}

				if (blockWidth == 1 && blockHeight == 1) {
					ImageIO.write(decompressed, "PNG", new File("compressed.png"));
				}

				memFree(compressedData);
			}
		}

		rawBuffer.destroy(boiler);
		boiler.destroyInitialObjects();
	}
}
