package com.github.knokko.compressor;

import org.lwjgl.system.Platform;
import java.awt.image.BufferedImage;
import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.util.*;
import java.util.stream.Collectors;
import java.util.zip.ZipInputStream;
import javax.imageio.ImageIO;

public class Bc7Compressor {

	private static final File COMPRESSION_DIRECTORY;

	static {
		try {
			COMPRESSION_DIRECTORY = Files.createTempDirectory("").toFile();
		} catch (IOException failed) {
			throw new Error("Failed to create temp directory", failed);
		}
		COMPRESSION_DIRECTORY.deleteOnExit();

		Platform os = Platform.get();
		String fileName;

		if (os == Platform.WINDOWS) fileName = "bc7enc.exe";
		else if (os == Platform.LINUX) fileName = "bc7enc-linux";
		else if (os == Platform.MACOSX) {
			var arch = Platform.getArchitecture();
			if (arch == Platform.Architecture.X64) fileName = "bc7enc-macos-x64";
			else if (arch == Platform.Architecture.ARM64) fileName = "bc7enc-macos-arm64";
			else throw new UnsupportedOperationException("Unsupported MacOS arch: " + arch);
		} else throw new UnsupportedOperationException("Unsupported OS: " + os);

		try (var input = Bc7Compressor.class.getResourceAsStream(fileName)) {
			if (input == null) throw new Error("Can't find resource " + fileName);
			File destination = new File(COMPRESSION_DIRECTORY + "/" + fileName);
			Files.copy(input, destination.toPath());
			if (os == Platform.LINUX || os == Platform.MACOSX) {
				if (!destination.setExecutable(true)) throw new Error("Failed to make " + destination + " executable");
			}
			destination.deleteOnExit();
		} catch (IOException failed) {
			throw new Error("Failed to copy " + fileName + " to temp directory");
		}

		if (os == Platform.LINUX) {
			File destination = new File(COMPRESSION_DIRECTORY + "/ispc");
			try (
					var input = Bc7Compressor.class.getResourceAsStream("ispc-linux.zip");
					var output = Files.newOutputStream(destination.toPath())
			) {
				if (input == null) throw new Error("Can't find ispc-linux.zip");
				var zipInput = new ZipInputStream(input);

				var entry = zipInput.getNextEntry();
				if (entry == null || !entry.getName().equals("ispc")) throw new Error("Unexpected entry " + entry);
				output.write(input.readAllBytes());
				output.flush();
			} catch (IOException failed) {
				throw new Error("Failed to extract ispc-linux.zip", failed);
			}
		}
	}

	public static byte[] compressBc7(BufferedImage image) throws IOException {
		String name = UUID.randomUUID().toString();
		File source = new File(COMPRESSION_DIRECTORY + "/" + name + ".png");
		source.deleteOnExit();

		ImageIO.write(image, "PNG", source);
		var compressionProcess = startCompressionProcess(name);
		try {
			if (compressionProcess.waitFor() != 0) {
				try (var errorScanner = compressionProcess.errorReader()) {
					throw new IOException("Bc7 compression failed: " + errorScanner.lines().collect(Collectors.joining()));
				}
			}
		} catch (InterruptedException e) {
			throw new IOException(e);
		}

		File destination = new File(COMPRESSION_DIRECTORY + "/" + name + ".dds");

		try (var input = Files.newInputStream(destination.toPath())) {
			input.skipNBytes(148);
			return input.readAllBytes();
		} finally {
			if (!source.delete()) System.out.println("Warning: failed to delete " + source);
			if (!destination.delete()) System.out.println("Warning: failed to delete " + destination);
		}
	}

	private static Process startCompressionProcess(String name) throws IOException {
		Platform os = Platform.get();

		String baseCommand;
		if (os == Platform.WINDOWS) baseCommand = COMPRESSION_DIRECTORY + "/bc7enc.exe";
		else if (os == Platform.LINUX) baseCommand = "./bc7enc-linux";
		else if (Platform.getArchitecture() == Platform.Architecture.ARM64) baseCommand = "./bc7enc-macos-arm64";
		else baseCommand = "./bc7enc-macos-x64";

		var processBuilder = new ProcessBuilder(baseCommand, "./" + name + ".png", "-g", "-q");
		processBuilder.directory(COMPRESSION_DIRECTORY);
		return processBuilder.start();
	}
}
