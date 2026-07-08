# Using the BC1 compressor
The BC1 image format is a standardized GPU-compressed image format supported by almost any *desktop* GPU.
`vk-compressor` uses a compute shader to compress images to BC1. and supports the implicit 1-bit alpha channel.

## CLI usage
To compressor `some-image.png`, and store the compressed data in `some-image.bc1`, use the following command:
```shell
./vk-compressor some-image.png --encoding bc1
```
To 'preview' it, you can run:
```shell
./vkc-preview some-image.bc1 --width 123 --height 123 --gui
```
(Assuming that the size of the original image was 123x123 pixels.)

## Simple API usage
The static methods of `Bc1Compressor` are the easy way to use the API. For instance:
```java
import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.IOException;

class SimpleApiUsage {
	static void main(String[] args) throws IOException {
		BufferedImage[] images = { ImageIO.read(new File("some-image.png")) };
		Bc1Compressor.compressBufferedImagesSimple(images, compressedData -> {
			// Do something with `compressedData[0]`, e.g. copy it to some staging buffer, or write it to a file.
		});
	}
}
```

## Complex API usage
The complex API usage is much more complicated to use than the simple API usage, but it can be orders of magnitude
faster, if you are working with data that is already in video memory.

To use the BC1 compressor, create the `BoilerInstance`.
Then, create an instance of `Bc1Compressor`:
```java
var compressor = new Bc1Compressor(boilerInstance);
```
You only need 1 instance of `Bc1Compressor` (per `BoilerInstance`).

### Descriptor sets
Before you start, you need to allocate 1
or more descriptor sets of the Bc1 layout. You can access
the layout using `compressor.descriptorSetLayout`.
```java
var descriptorCombiner = new DescriptorCombiner(boiler);
long[] descriptorSets = descriptorCombiner.addMultiple(compressor.descriptorSetLayout, 1);
var descriptorPool = descriptorCombiner.build("CompressionDescriptors");

long descriptorSet = descriptorSets[0];
```
Finally, you need to call one of the `compress` methods of
your `Bc1Compressor` to record commands that will actually
compress an image.

### The actual compression
1. You need to create some command pool + command buffer yourself (e.g. using `SingleTimeCommands.submit`),
   and let a `CommandRecorder` start recording.
2. Call `compressor.bindPipeline(recorder)` to bind the bc1 compression compute pipeline.
3. Call `compressor.compress(...)` for each bc1 buffer that you want to compress.

#### Compression parameters
##### recorder
This is the same `CommandRecorder` that you passed to `bindPipeline`.
You can use e.g. `SingleTimeCommands.submit` to obtain a `CommandRecorder` instance.

##### descriptorSet
This must be a `VkDescriptorSet` whose layout is `compressor.descriptorSetLayout`.
Since the `compress(...)` method will call `vkUpdateDescriptorSets`,
**you can't reuse the descriptor set until the command buffer has completed execution**.
If you want to compress N buffers/images during the same submission, you need N descriptor sets.

##### source
This buffer must contain the data of the image to be compressed, in an RGBA format with 1 byte per component,
which is 4 bytes per pixel.
Thus, the byte size of the buffer should be `4 * width * height`.

##### destination
Once the command buffer has completed execution, the encoded image data will be stored in this buffer.
The byte size should be `width * height / 2`.

##### width
The width of the image, in pixels, which must be a multiple of 4.

##### height
The height of the image, in pixels, which must be a multiple of 4.

### Synchronization
The `compress` method won't perform any synchronization on the source buffer and destination buffer.
It's your own responsibility to handle potential memory barriers and layout transitions.
Furthermore, the `compress` method won't submit or *end* the command buffer/recorder, so that's also up to you.

### Cleaning up
If you are done with all compression, call the `destroy()` method of the `Bc1Compressor`.
