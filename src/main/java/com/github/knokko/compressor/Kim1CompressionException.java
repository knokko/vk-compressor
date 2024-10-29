package com.github.knokko.compressor;

/**
 * This exception will be thrown by the constructor of <i>Kim1Compressor</i> when the given image can <b>not</b> be
 * compressed to the kim1 format. This happens when the width, height, or number of distinct pixels, is at least
 * 1024.
 */
public class Kim1CompressionException extends RuntimeException {

	public Kim1CompressionException(String message) {
		super(message);
	}
}
