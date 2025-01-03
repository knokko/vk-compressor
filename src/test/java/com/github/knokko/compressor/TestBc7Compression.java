package com.github.knokko.compressor;

import org.junit.jupiter.api.Test;

import javax.imageio.ImageIO;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.Objects;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import static org.junit.jupiter.api.Assertions.assertTrue;

public class TestBc7Compression {

	@Test
	public void testCompressDreamShrine() throws IOException  {
		var resource = TestBc7Compression.class.getResource("dreamshrine.png");
		var sourceImage = ImageIO.read(Objects.requireNonNull(resource));
		byte[] actualResult = Bc7Compressor.compressBc7(sourceImage);

		// Since bc7enc is not deterministic, I can't just use assertArrayEquals.
		// Instead, I will test that it has the expected entropy:
		// it should have about 77 KB after being zipped (compressed)

		var byteOutput = new ByteArrayOutputStream();
		var zipOutput = new ZipOutputStream(byteOutput);
		zipOutput.putNextEntry(new ZipEntry("test.bc7"));
		zipOutput.write(actualResult);
		zipOutput.closeEntry();
		zipOutput.flush();
		zipOutput.close();

		int size = byteOutput.toByteArray().length;
		assertTrue(size > 70_000, "Expected size to be at least 70KB, but got " + size);
		assertTrue(size < 85_000, "Expected size to be at most 85 KB, but got " + size);
	}
}
