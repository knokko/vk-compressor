# Using the BC4 compressor
The BC4 image format is a standardized GPU-compressed image format supported by almost any *desktop* GPU.
`vk-compressor` uses a modified version of
[the Betsy bc4 compressor](https://github.com/darksylinc/betsy/blob/master/bin/Data/bc4.glsl)
that uses Vulkan rather than OpenGL.

## CLI usage
To compressor `some-image.png`, and store the compressed data in `some-image.bc4`, use the following command:
```shell
./vk-compressor some-image.png --encoding bc4
```
or
```shell
./vk-compressor some-image.png --encoding bc4 --signed
```
If you use the `--signed` flag, you will get data for `VK_FORMAT_BC4_SNORM_BLOCK`,
otherwise you will get data for `VK_FORMAT_BC4_UNORM_BLOCK`.

To 'preview' it, you can run:
```shell
./vkc-preview some-image.bc4 --width 123 --height 123 --gui
```
or
```shell
./vkc-preview some-image.bc4 --width 123 --height 123 --gui --signed
```
(Assuming that the size of the original image was 123x123 pixels.)

## Simple API usage
The static methods of `Bc1Compressor` are the easy way to use the API. For instance:

```java
import java.awt.*;
import java.awt.image.BufferedImage;
import java.nio.ByteBuffer;

class SimpleApiUsage {
	static void main(String[] args) throws IOException {
		BufferedImage image = ImageIO.read(new File("some-grey-image.png"));
		BufferedImage[] images = {image};
		int[] widths = {image.getWidth()};
		int[] heights = {image.getHeight()};
		boolean signed = false;

		ByteBuffer imageData = ByteBuffer.allocate(image.getWidth() * image.getHeight());
		for (int y = 0; y < image.getHeight(); y++) {
			for (int x = 0; x < image.getWidth(); x++) {
				Color color = new Color(image.getRGB(x, y), true);
				
				// In this example, I use red as the 'greyscale color', but you could choose another component.
				imageData.put((byte) color.getRed());
			}
		}
		imageData.flip();
	
		ByteBuffer[] allImageData = {imageData};
		Bc4Compressor.compressGreyscaleImageDataSimple(allImageData, widths, heights, signed, compressedData -> {
			// Do something with `compressedData[0]`, e.g. copy it to some staging buffer, or write it to a file.
		});
	}
}
```

## Complex API usage
The complex API usage is much more complicated to use than the simple API usage, but it can be orders of magnitude
faster, if you are working with data that is already in video memory.

To use the BC4 compressor, create the `BoilerInstance`.
Then, create an instance of `Bc4Compressor`:
```java
var compressor = new Bc4Compressor(boiler);
```
You only need 1 instance of `Bc4Compressor`
(per `BoilerInstance`).

### Descriptor sets
Before you start, you need to allocate 1
or more descriptor sets of the Bc4 layout. You can access
the layout using `compressor.descriptorSetLayout`.
```java
var descriptorCombiner = new DescriptorCombiner(boiler);
long[] descriptorSets = descriptorCombiner.addMultiple(compressor.descriptorSetLayout, 1);
var descriptorPool = descriptorCombiner.build("CompressionDescriptors");

long descriptorSet = descriptorSets[0];
```

### The actual compression
1. You need to create some command pool + command buffer yourself
   (e.g. using `SingleTimeCommands.submit`), and let a `CommandRecorder`
   start recording.
2. Call `compressor.bindPipeline(recorder)` to bind the bc4
   compression compute pipeline.
3. Call `compressor.compress(...)` for each bc4 buffer that you want to compress.

#### Compression parameters
##### recorder
This is the same `CommandRecorder` that you passed to `bindPipeline`.
You can use e.g. `SingleTimeCommands.submit` to obtain a
`CommandRecorder` instance.

##### descriptorSet
This must be a `VkDescriptorSet` whose layout is
`compressor.descriptorSetLayout`.
Since the `compress(...)` method will call
`vkUpdateDescriptorSets`, **you can't reuse the descriptor
set until the command buffer has completed execution**.
If you want to compress N buffers/images during the
same submission, you need N descriptor sets.

##### source
This buffer must contain the data of the image to be compressed,
in a grayscale format with 1 byte per component. Thus, the
byte size of the buffer should be `width * height`.

##### destination
Once the command buffer has completed execution,
the encoded image data will be stored in this buffer.
The byte size should be `width * height / 2`.

##### width
The width of the image, in pixels

##### height
The height of the image, in pixels

##### signed
Whether the grayscale image data is *signed*:
- This should be `true` when the image format is `VK_FORMAT_BC4_SNORM_BLOCK`
- This should be `false` when the image format is `VK_FORMAT_BC4_UNORM_BLOCK`

### Synchronization
The `compress` method won't perform any synchronization on
the source buffer and destination buffer. It's your
own responsibility to handle potential memory barriers and
layout transitions. Furthermore, the `compress` method
won't submit or *end* the command buffer/recorder, so
that's also up to you.

### Cleaning up
If you are done with all compression, call the
`destroy()` method of the `Bc4Compressor`.
