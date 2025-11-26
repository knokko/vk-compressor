package com.github.knokko.compressor;

import com.github.knokko.boiler.utilities.ImageCoding;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

public class BenchmarkHelper {

	public static List<ByteBuffer> getPixelatedBenchmarkImages() throws IOException {
		String[] relativePaths = {
				"larger/itemsheet_armour.png", "larger/itemsheet_misc.png", "larger/itemsheet_weapons.png"
		};
		List<ByteBuffer> images = new ArrayList<>();

		for (String relativePath : relativePaths) {
			BufferedImage sheet = ImageIO.read(Objects.requireNonNull(BenchmarkHelper.class.getResource(relativePath)));
			for (int y = 0; y < sheet.getHeight(); y += 16) {
				for (int x = 0; x < sheet.getWidth(); x += 16) {
					BufferedImage slice = sheet.getSubimage(x, y, 16, 16);
					ByteBuffer imageBuffer = ByteBuffer.allocate(4 * 16 * 16);
					ImageCoding.encodeBufferedImage(imageBuffer, slice);
					imageBuffer.flip();

					boolean isEmpty = true;
					for (int index = imageBuffer.position(); index < imageBuffer.limit(); index++) {
						if (imageBuffer.get(index) != 0) isEmpty = false;
					}
					if (!isEmpty) images.add(imageBuffer);
				}
			}
		}

		return images;
	}

	public static List<BufferedImage> getLargerBenchmarkImages() throws IOException {
		String[] relativePaths = {
				"larger/itemsheet_armour.png", "larger/itemsheet_misc.png", "larger/itemsheet_weapons.png",
				"EarthThick.png", "dreamshrine.png"
		};
		List<BufferedImage> images = new ArrayList<>();

		for (String relativePath : relativePaths) {
			BufferedImage fullImage = ImageIO.read(Objects.requireNonNull(BenchmarkHelper.class.getResource(relativePath)));
			int width = fullImage.getWidth();
			width -= width % 4;
			int height = fullImage.getHeight();
			height -= height % 4;
			images.add(fullImage.getSubimage(0, 0, width, height));
		}

		return images;
	}
}
