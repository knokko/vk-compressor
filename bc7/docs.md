# Using the BC7 compressor
The BC7 image format is a standardized GPU-compressed image format supported by almost any *desktop* GPU.
`vk-compressor` provides Java bindings for the BC7 compressor of
[Binomials basis_universal library](https://github.com/BinomialLLC/basis_universal).
The bindings are created using [my fork of basis_universal](https://github.com/knokko/basis_universal).
`vk-compressor` supports the following platforms:
- Windows x64
- Windows arm64
- Linux x64
- Linux arm64
- MacOS x64
- MacOS arm64

## CLI usage
To compressor `some-image.png`, and store the compressed data in `some-image.bc7`, use the following command:
```shell
./vk-compressor some-image.png --encoding bc7
```
or e.g.
```shell
./vk-compressor some-image.png --encoding bc7 --bc7f-flags slowest
```
You can use the `bc7f-flags` argument to alter the quality and performance of the compressor. You can either use 
[an integer flag](https://github.com/BinomialLLC/basis_universal/blob/20ed781c4b8d98b36074019a3389d4f71527a4d9/transcoder/basisu_transcoder_internal.h#L3417),
or one of the following strings: `fastest`, `faster`, `fast`, `default`, `slower`, `slowest`.

To 'preview' it, you can run:
```shell
./vkc-preview some-image.bc7 --width 123 --height 123 --gui
```
(Assuming that the size of the original image was 123x123 pixels.)

## API usage
You can use one of the static methods of `Bc7Compressor` to compress an image to the BC7 format. For instance:
```java
import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.IOException;

class SimpleApiUsage {
	static void main(String[] args) throws IOException {
		int flags = Bc7Compressor.FLAGS_DEFAULT;
		byte[] bc7Data = Bc7Compressor.compressBufferedImage(flags, ImageIO.read(new File("some-image.png")));
		// Do something with bc7Data
	}
}
```
