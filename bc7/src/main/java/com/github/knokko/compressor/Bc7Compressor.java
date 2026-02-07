package com.github.knokko.compressor;

import com.github.knokko.boiler.utilities.ImageCoding;
import org.lwjgl.system.Platform;

import java.awt.image.BufferedImage;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.file.Files;

import static com.github.knokko.boiler.utilities.BoilerMath.nextMultipleOf;
import static org.lwjgl.system.MemoryUtil.*;

/**
 * This class provides bindings to the BC7 encoder of
 * <a href="https://github.com/knokko/basis_universal">my fork</a> of
 * <a href="https://github.com/BinomialLLC/basis_universal">basis_universal</a>.
 * You should use the static methods of this class to compress images to BC7.
 */
public class Bc7Compressor {

	static {
		String architecture = switch (Platform.getArchitecture()) {
			case X64 -> "-x64";
			case ARM64 -> "-arm64";
			default -> throw new UnsupportedOperationException(
					"Unsupported architecture " + Platform.getArchitecture()
			);
		};
		String nativeFileName = switch (Platform.get()) {
			case WINDOWS -> "bc7-compressor" + architecture + ".dll";
			case LINUX -> "libbc7-compressor" + architecture + ".so";
			case MACOSX -> "bc7-compressor" + architecture + ".dylib";
			default -> throw new UnsupportedOperationException("Unsupported OS " + Platform.get());
		};

		try (var nativeInput = Bc7Compressor.class.getResourceAsStream(nativeFileName)) {
			assert nativeInput != null;
			var nativeFile = Files.createTempFile("", nativeFileName);
			byte[] bytes = nativeInput.readAllBytes();
			Files.write(nativeFile, bytes);
			System.load(nativeFile.toFile().getAbsolutePath());
			nativeFile.toFile().deleteOnExit();
		} catch (IOException cannotLoadNative) {
			throw new Error("Cannot load native " + nativeFileName, cannotLoadNative);
		}
		initNative();
	}

	/**
	 * These flags can be used as the {@code bc7fFlags} parameter of {@link #compressNative},
	 * {@link #compressRgbaImageData}, and {@link #compressBufferedImage}.
	 * Alternatively, you can make your own configuration of flags.
	 */
	public static final int FLAGS_DEFAULT_FASTEST = 128,
							FLAGS_DEFAULT_FASTER = 176,
							FLAGS_DEFAULT_FAST = 179,
							FLAGS_DEFAULT = 255,
							FLAGS_DEFAULT_SLOWER = 1023,
							FLAGS_DEFAULT_SLOWEST = 3967;

	private static native void initNative();

	/**
	 * Compresses a {@code width} x {@code height} RGBA image whose first pixel is stored at memory address
	 * {@code sourceAddress}, and writes the first compressed byte to {@code destinationAddress}.
	 * @param bc7fFlags The bc7f flags, e.g. {@link #FLAGS_DEFAULT}
	 * @param sourceAddress The memory address where the Red component of the top-left pixel is stored
	 * @param width The width (in pixels) of the image that is stored at {@code sourceAddress}
	 * @param height The height (in pixels) of the image that is stored at {@code sourceAddress}
	 * @param destinationAddress The memory address where this method will write the first compressed byte
	 */
	public static native void compressNative(
			int bc7fFlags, long sourceAddress,
			int width, int height, long destinationAddress
	);

	/**
	 * Compresses a {@code width} x {@code height} RGBA image whose pixel data is stored in {@code sourceImageData}.
	 * @param bc7fFlags The bc7f compression flags, e.g. {@link #FLAGS_DEFAULT}
	 * @param sourceImageData The pixel data of the image to be compressed.
	 *                        Its size should be {@code 4 * width * height} bytes.
	 * @param width The width (in pixels) of the image to be compressed
	 * @param height The height (in pixels) of the image to be compressed
	 * @param destinationCompressedData The buffer where this method will store the compressed BC7 data
	 */
	public static void compressRgbaImageData(
			int bc7fFlags, ByteBuffer sourceImageData, int width, int height, ByteBuffer destinationCompressedData
	) {
		long sourceAddress = memAddress(sourceImageData);
		if (sourceAddress % 4L != 0L) throw new IllegalArgumentException("sourceImageData must be aligned to 4 bytes");
		compressNative(
				bc7fFlags, sourceAddress,
				width, height, memAddress(destinationCompressedData)
		);
	}

	/**
	 * Compresses {@code sourceImage} using the given {@code bc7fFlags}, and returns the compressed data as
	 * {@code byte[]}.
	 * @param bc7fFlags The bc7f compression flags, e.g. {@link #FLAGS_DEFAULT}
	 * @param sourceImage The image to be compressed
	 * @return The compressed BC7 data
	 */
	public static byte[] compressBufferedImage(int bc7fFlags, BufferedImage sourceImage) {
		ByteBuffer rgbaData = memCalloc(4 * sourceImage.getWidth() * sourceImage.getHeight());
		ImageCoding.encodeBufferedImage(rgbaData, sourceImage);
		rgbaData.flip();

		int paddedWidth = nextMultipleOf(sourceImage.getWidth(), 4);
		int paddedHeight = nextMultipleOf(sourceImage.getHeight(), 4);
		ByteBuffer compressedData = memCalloc(paddedWidth * paddedHeight);
		compressRgbaImageData(bc7fFlags, rgbaData, sourceImage.getWidth(), sourceImage.getHeight(), compressedData);

		memFree(rgbaData);

		byte[] compressedArray = new byte[compressedData.capacity()];
		compressedData.get(compressedArray);
		memFree(compressedData);
		return compressedArray;
	}
}
