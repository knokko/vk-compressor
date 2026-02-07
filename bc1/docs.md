# Using the BC1 compressor
The BC1 image format is a standardized GPU-compressed image format supported by almost any *desktop* GPU.
`vk-compressor` uses a modified version of
[the Betsy bc1 compressor](https://github.com/darksylinc/betsy/blob/master/bin/Data/bc1.glsl)
that supports the 1-bit alpha channel.

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
var compressor = new Bc1Compressor(boilerInstance, memoryCombiner, stagingCombiner);
```

The `Bc1Compressor` needs to allocate some device memory, so it takes two `MemoryCombiner`s as parameters. If you don't
want it to share its memory with anything else, you could simply use:
```java
var combiner = new MemoryCombiner(boiler, "CompressorMemory");
var stagingCombiner = new MemoryCombiner(boiler, "CompressorStagingMemory");
var compressor = new Bc1Compressor(boiler, combiner, stagingCombiner);
var memory = combiner.build(true);
var stagingMemory = stagingCombiner.build(true);

var commands = new SingleTimeCommands(boiler);
commands.submit("StagingTransfer", compressor::performStagingTransfer).awaitCompletion();
stagingMemory.destroy(boiler);

// Now you can use `compressor`
```
You only need 1 instance of `Bc1Compressor`
(per `BoilerInstance`). Next, create 1 or more workers:
```java
var worker = new Bc1Worker(compressor, maxDestinationImagePixels, combiner);
```
If you want to put the compressed image data in a `VkImage`, you need to pick
`maxDestinationImagePixels >= image.width * image.height` for any of your destination images.
If you want to put the compressed image data in a `VkBuffer`, you can use `0`.
The `combiner` is probably the same one that you use for the `Bc1Compressor`, but that is not required.
Example code:
```java
var combiner = new MemoryCombiner(boiler, "CompressorMemory");
var stagingCombiner = new MemoryCombiner(boiler, "CompressorStagingMemory");
var compressor = new Bc1Compressor(boiler, combiner, stagingCombiner);
var worker = new Bc1Worker(compressor, 0, combiner);
var memory = combiner.build(true);
var stagingMemory = stagingCombiner.build(true);

var commands = new SingleTimeCommands(boiler);
commands.submit("StagingTransfer", compressor::performStagingTransfer).awaitCompletion();
stagingMemory.destroy(boiler);

// Now you can use `worker`
```
You usually need just 1 instance of `Bc1Worker`, but
having more of them allows you to do parallel recording.

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
your `Bc1Worker` to record commands that will actually
compress an image.

### The actual compression
Before you can call any of the `compress(...)` methods,
you need to call the `bindPipeline(recorder)` method of the worker.
(An exception will be thrown if you forget this.)

Depending on the overload you choose,
the result will either be stored in a buffer, or in an
image.
- To store the result in a buffer, call the
  `compress(recorder, descriptorSet, sourceBuffer, destinationBuffer, width, height)`
  overload.
- To store the result in an image, call the
  `compress(recorder, descriptorSet, sourceBuffer, destinationImage)`
  overload.

In either case, you need to create some command pool +
command buffer yourself
(e.g. using `SingleTimeCommands.submit`),
and let a `CommandRecorder`
start recording, which is the first parameter you need to
pass.

Furthermore, you need your descriptor set as the second
parameter. Since the `compress(...)` method will call
`vkUpdateDescriptorSets`, you can't reuse the descriptor
set until the command buffer has completed execution.

The `sourceBuffer` is the third parameter. This buffer
must contain the data of the image to be compressed,
in an RGBA format with 1 byte per component. Thus, the
byte size of the should be `4 * width * height`.

In the first overload, the `destinationBuffer` is
the fourth parameter. Once the command buffer has completed
execution, the encoded image data will be stored in this
buffer. The byte size should be `width * height / 2`.
The `width` and `height` parameters are simply the width
and height of the image to be compressed, in pixels.

In the second overload, the `destinationImage` is the
fourth and last parameter. Once the command buffer has
completed execution, the compressed data will have been
copied to the image. The image must have the layout
`VK_IMAGE_LAYOUT_TRANSFER_DST_OPTIMAL`. Assuming that
the source image data uses SRGB, the image format should
be `VK_FORMAT_BC1_RGBA_SRGB_BLOCK`.

### Synchronization
The `compress` method won't perform any synchronization on
the source buffer and destination image/buffer. It's your
own responsibility to handle potential memory barriers and
layout transitions. Furthermore, the `compress` method
won't submit or *end* the command buffer/recorder, so
that's also up to you.

### Cleaning up
If you are done with all compression, call the
`destroy()` method of the `Bc1Compressor`.
