package com.github.knokko.compressor;

import com.github.knokko.boiler.BoilerInstance;
import com.github.knokko.boiler.builders.BoilerBuilder;
import org.junit.jupiter.api.Test;

import javax.imageio.ImageIO;

import java.awt.*;
import java.io.File;
import java.io.IOException;

import static com.github.knokko.boiler.utilities.ColorPacker.*;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
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

		var compressedImage = memAlloc(4 * compressor.intSize);
		compressedImage.position(0);
		compressor.compress(compressedImage);

		var decompressor = new Kim1Decompressor(compressedImage);
		assertEquals(width, decompressor.width);
		assertEquals(height, decompressor.height);

		assertEquals(rgb(1, 2, 3), decompressor.getColor(0, 0));
		assertEquals(rgba(100, 101, 102, 103), decompressor.getColor(1, 0));
		assertEquals(rgb(200, 201, 202), decompressor.getColor(0, 1));
		assertEquals(rgb(200, 201, 202), decompressor.getColor(1, 1));

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
	public void testMardekImages() throws IOException {
		var boiler = new BoilerBuilder(
				VK_API_VERSION_1_0, "TestKim1Compression", 1
		).build();

		File[] files = new File("src/test/resources/com/github/knokko/compressor/mardek").listFiles();
		assertNotNull(files);

		assertEquals(99, files.length);
		for (File file : files) testCompressAndDecompress(boiler, file);

		boiler.destroyInitialObjects();
	}
}
