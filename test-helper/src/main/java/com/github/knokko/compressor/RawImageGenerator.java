package com.github.knokko.compressor;

import com.github.knokko.boiler.utilities.ImageCoding;

import javax.imageio.ImageIO;
import java.io.File;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.file.Files;
import java.util.Objects;

public class RawImageGenerator {

	public static void main(String[] args) throws IOException {
		var image = ImageIO.read(Objects.requireNonNull(
				RawImageGenerator.class.getResourceAsStream("mardek/AirStaff.png")
		));

		var colorBuffer = ByteBuffer.allocate(4 * image.getWidth() * image.getHeight());
		var greyscaleBuffer = ByteBuffer.allocate(image.getWidth() * image.getHeight());
		var signedBuffer = ByteBuffer.allocate(image.getWidth() * image.getHeight());
		ImageCoding.encodeBufferedImage(colorBuffer, image);
		for (int i = 0; i < greyscaleBuffer.capacity(); i++) {
			byte redByte = colorBuffer.get(4 * i);
			greyscaleBuffer.put(i, redByte);
			int redUnsigned = redByte & 0xFF;
			int redSigned = redUnsigned - 128;
			byte redSignedByte = (byte) redSigned;
			signedBuffer.put(i, redSignedByte);
		}

		try (var colorOutput = Files.newOutputStream(new File("color").toPath())) {
			colorOutput.write(colorBuffer.array());
			colorOutput.flush();
		}

		try (var greyscaleOutput = Files.newOutputStream(new File("grey").toPath())) {
			greyscaleOutput.write(greyscaleBuffer.array());
			greyscaleOutput.flush();
		}

		try (var signedOutput = Files.newOutputStream(new File("signed").toPath())) {
			signedOutput.write(signedBuffer.array());
			signedOutput.flush();
		}
	}
}
