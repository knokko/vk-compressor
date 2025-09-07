# Using the BC4 compressor
The BC4 image format is a standardized GPU-compressed image
format supported by almost any *desktop* GPU.

To use the BC4 compressor, create the `BoilerInstance`.
Then, create an instance of `Bc4Compressor`:
```java
var compressor = new Bc4Compressor(boiler);
```

You only need 1 instance of `Bc4Compressor`
(per `BoilerInstance`). Next, create 1 or more workers:
```java
var worker = new Bc4Worker(compressor, maxDestinationImagePixels, combiner);
```
If you want to put the compressed image data in a `VkImage`, you need to pick
`maxDestinationImagePixels >= image.width * image.height` for any of your destination images.
If you want to put the compressed image data in a `VkBuffer`, you can use `0`.
The `combiner` parameter is needed because the worker may need to allocate some memory.
Example code:
```java
var combiner = new MemoryCombiner(boiler, "CompressorMemory");
var compressor = new Bc4Compressor(boiler, combiner, stagingCombiner);
var worker = new Bc4Worker(compressor, 0, combiner);
var memory = combiner.build(true);

// Now you can use `worker`
```
You usually need just 1 instance of `Bc4Worker`, but
having more of them allows you to do parallel recording.

## Descriptor sets
Before you start, you need to allocate 1
or more descriptor sets of the Bc4 layout. You can access
the layout using `compressor.descriptorSetLayout`.
```java
var descriptorCombiner = new DescriptorCombiner(boiler);
long[] descriptorSets = descriptorCombiner.addMultiple(compressor.descriptorSetLayout, 1);
var descriptorPool = descriptorCombiner.build("CompressionDescriptors");

long descriptorSet = descriptorSets[0];
```
Finally, you need to call one of the `compress` methods of
your `Bc4Worker` to record commands that will actually
compress an image.

## The actual compression
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
in a grayscale format with 1 byte per component. Thus, the
byte size of the buffer should be `width * height`.

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
be `VK_FORMAT_BC4_UNORM_BLOCK`.

## Synchronization
The `compress` method won't perform any synchronization on
the source buffer and destination image/buffer. It's your
own responsibility to handle potential memory barriers and
layout transitions. Furthermore, the `compress` method
won't submit or *end* the command buffer/recorder, so
that's also up to you.

## Cleaning up
If you are done with all compression, call the
`destroy()` method of the `Bc4Compressor`.

## Credits
The compressor uses a compute shader to compress images.
This shader is a modified version of the BC4 compression shader of
[Betsy](https://github.com/darksylinc/betsy/blob/master/bin/Data/bc4.glsl)
