package com.github.knokko.compressor;

import org.apache.commons.cli.DefaultParser;
import org.apache.commons.cli.Option;
import org.apache.commons.cli.Options;
import org.apache.commons.cli.ParseException;

import java.io.File;

import static java.lang.Math.min;

public class CompressorCli {

	public static void main(String[] args) throws ParseException {
		String errorMessage = runCli(args);
		if (errorMessage != null) {
			System.err.println(errorMessage);
			System.exit(1);
		}
	}

	private static String runCli(String[] args) throws ParseException {
		var options = new Options();
		var helpOption = Option.builder("h")
				.longOpt("help")
				.desc("Prints the help page")
				.build();
		var encodingOption = Option.builder("e")
				.longOpt("encoding")
				.desc("The encoding: kim1, kim2, bc1, bc4, etc...")
				.hasArg()
				.type(String.class)
				.build();
		var batchSizeOption = Option.builder("b")
				.longOpt("batch-size")
				.desc("The batch size (number of files)")
				.hasArg()
				.type(Integer.class)
				.build();
		var threadsOption = Option.builder("t")
				.longOpt("num-threads")
				.desc("The number of threads to use")
				.hasArg()
				.type(Integer.class)
				.build();
		var benchmarkOption = Option.builder("B")
				.longOpt("benchmark")
				.desc("Measure execution time, and delete all files on exit")
				.build();
		var signedOption = Option.builder("s")
				.longOpt("signed")
				.desc("For bc4 encoding only, whether the data is signed (SNORM) rather than unsigned (UNORM)")
				.build();
		var rawWidthOption = Option.builder("w")
				.longOpt("raw-width")
				.desc("Use this option when the images are encoded using raw RGBA rather than PNG. " +
						"Then this option is the width of all images.")
				.hasArg()
				.type(Integer.class)
				.build();
		var validationOption = Option.builder("v")
				.longOpt("validation")
				.desc("Enable Vulkan Validation Layer (VVL)")
				.build();
		var bc7fFlagsOption = Option.builder("7")
				.longOpt("bc7f-flags")
				.desc("The flags passed to fast_pack_bc7_auto_rgba. " +
						"You can choose 'fastest', 'faster', 'fast', 'default', 'slower', or 'slowest', " +
						"or any integer (see https://github.com/BinomialLLC/basis_universal/blob/20ed781c4b8d98b36074019a3389d4f71527a4d9/transcoder/basisu_transcoder_internal.h#L3417 for their documentation)")
				.hasArg()
				.build();
		var bitsPerPixelOption = Option.builder("2")
				.longOpt("kim2-bits-per-pixel")
				.desc("The bits per pixel for kim2 encoding")
				.hasArg()
				.type(Integer.class)
				.build();
		options.addOption(helpOption);
		options.addOption(encodingOption);
		options.addOption(batchSizeOption);
		options.addOption(threadsOption);
		options.addOption(benchmarkOption);
		options.addOption(signedOption);
		options.addOption(rawWidthOption);
		options.addOption(validationOption);
		options.addOption(bc7fFlagsOption);
		options.addOption(bitsPerPixelOption);

		var parser = new DefaultParser();
		var cmd = parser.parse(options, args);

		if (cmd.hasOption(helpOption)) {
			System.out.println("Use e.g.: vk-compressor -b 50 -e bc1 file1.png file2.png");
			System.out.println("Options:");
			for (var option : options.getOptions()) {
				System.out.println("  " + option);
			}
			return null;
		}

		File[] files = cmd.getArgList().stream().map(File::new).toArray(File[]::new);
		if (files.length == 0) return "You must specify at least 1 file";
		for (var file : files) {
			if (!file.exists()) return "Can't find file " + file.getAbsolutePath();
		}

		int numThreads = 1;
		if (cmd.hasOption(threadsOption)) numThreads = cmd.getParsedOptionValue(threadsOption);
		if (numThreads <= 0) return "num-threads must be positive";

		int batchSize = files.length;
		if (cmd.hasOption(batchSizeOption)) batchSize = min(batchSize, cmd.getParsedOptionValue(batchSizeOption));
		if (batchSize <= 0) return "batch-size must be positive";

		boolean benchmark = cmd.hasOption(benchmarkOption);
		boolean signed = cmd.hasOption(signedOption);
		boolean validation = cmd.hasOption(validationOption);

		String bc7FlagsString = cmd.getOptionValue(bc7fFlagsOption, "default");
		int bc7fFlags = switch (bc7FlagsString) {
			case "fastest" -> Bc7Compressor.FLAGS_DEFAULT_FASTEST;
			case "faster" -> Bc7Compressor.FLAGS_DEFAULT_FASTER;
			case "fast" -> Bc7Compressor.FLAGS_DEFAULT_FAST;
			case "default" -> Bc7Compressor.FLAGS_DEFAULT;
			case "slower" -> Bc7Compressor.FLAGS_DEFAULT_SLOWER;
			case "slowest" -> Bc7Compressor.FLAGS_DEFAULT_SLOWEST;
			default -> {
				try {
					yield Integer.parseInt(bc7FlagsString);
				} catch (NumberFormatException invalid) {
					yield -1;
				}
			}
		};

		int rawWidth = 0;
		if (cmd.hasOption(rawWidthOption)) rawWidth = cmd.getParsedOptionValue(rawWidthOption);

		String encoding = cmd.getOptionValue(encodingOption);
		if (encoding == null) return "Missing required encoding option: use e.g. --encoding bc1";

		if (signed && !encoding.equals("bc4")) {
			return "The --signed option can only be used when the --encoding is 'bc4'";
		}

		if (signed && rawWidth == 0) {
			return "The --signed option can only be used when the --raw-width option is also used.";
		}

		if (validation && !encoding.equals("bc1") && !encoding.equals("bc4")) {
			return "The --validation option can only be used when the --encoding is 'bc1' or 'bc4'";
		}

		if (bc7fFlags < 0 && encoding.equals("bc7")) {
			return "Invalid bc7f flag " + bc7FlagsString;
		}

		if (bc7fFlags != Bc7Compressor.FLAGS_DEFAULT && !encoding.equals("bc7")) {
			return "The bc7f-flags option can only be used when the encoding is bc7";
		}

		int kim2BitsPerPixel = cmd.getParsedOptionValue(bitsPerPixelOption, -1);
		if (!encoding.equals("kim2") && kim2BitsPerPixel != -1) {
			return "The kim2-bits-per-pixel option can only be used when the encoding is kim2";
		}

		if (encoding.equals("kim2")) {
			if (kim2BitsPerPixel == -1) return "The kim2-bits-per-pixel option is required when the encoding is kim2";
			if (kim2BitsPerPixel != 1 && kim2BitsPerPixel != 2 && kim2BitsPerPixel != 4 && kim2BitsPerPixel != 8) {
				return "kim2-bits-per-pixel must be 1, 2, 4, or 8";
			}
		}

		return switch (encoding) {
			case "bc1" -> {
				Bc1Cli.run(numThreads, batchSize, benchmark, rawWidth, validation, files);
				yield null;
			}
			case "bc4" -> {
				Bc4Cli.run(numThreads, batchSize, benchmark, rawWidth, signed, validation, files);
				yield null;
			}
			case "bc7" -> {
				Bc7Cli.run(numThreads, batchSize, benchmark, rawWidth, bc7fFlags, files);
				yield null;
			}
			case "kim1" -> {
				Kim1Cli.run(numThreads, batchSize, benchmark, rawWidth, files);
				yield null;
			}
			case "kim2" -> {
				Kim2Cli.run(numThreads, batchSize, benchmark, rawWidth, kim2BitsPerPixel, files);
				yield null;
			}
			case "kim3" -> {
				Kim3Cli.run(numThreads, batchSize, benchmark, rawWidth, files);
				yield null;
			}
			default -> "Unexpected encoding option: use bc1, bc4, bc7, kim1, kim2, or kim3";
		};
	}
}
