# Using the BC1 compressor
The BC1 image format is a standardized GPU-compressed image
format supported by almost any *desktop* GPU.

To use the BC1 compressor, create the `BoilerInstance`.
Then, create an instance of `Bc1Compressor`:
```java
var compressor = new Bc1Compressor(boilerInstance);
```
You only need 1 instance of `Bc1Compressor`
(per `BoilerInstance`). Next, create 1 or more workers:
```java
var worker = new Bc1Worker(compressor);
```
You usually need just 1 instance of `Bc1Worker`, but
having more of them allows you to do some more
parallelism.

## Descriptor sets
Before you start, you need to allocate 1
or more descriptor sets of the Bc1 layout. You can borrow
them from the `descriptorBank` of your `Bc1Compressor`:
```java
var descriptorSet = compressor.descriptorBank.borrowDescriptorSet("Example");
```
Alternatively, you can use the `descriptorSetLayout` of
your `Bc1Compressor` directly, for instance:
```java
var descriptorPool = compressor.descriptorSetLayout.createPool(1, 0, "Bc1Descriptors");
var descriptorSet = descriptorPool.allocate(1)[0];
```
Finally, you need to call one of the `compress` methods of
your `Bc1Worker` to record commands that will actually
compress an image.

## The actual compression
Depending on the overload you choose,
the result will either be stored in a buffer, or in an
image.
- To store the result in a buffer, call the
  `compress(recorder, descriptorSet, sourceBufferRange, destinationBufferRange, width, height)`
  overload.
- To store the result in an image, call the
  `compress(recorder, descriptorSet, sourceBufferRange, destinationImage)`
  overload.

In either case, you need to create some command pool +
command buffer yourself, and let a `CommandRecorder`
start recording, which is the first parameter you need to
pass.

Furthermore, you need your descriptor set as the second
parameter. Since the `compress(...)` method will call
`vkUpdateDescriptorSets`, you can't reuse the descriptor
set until the command buffer has completed execution.

The `sourceBufferRange` is the third parameter. This buffer
range must contain the data of the image to be compressed,
in an RGBA format with 1 byte per component. Thus, the
byte size of the range should be `4 * width * height`.

In the first overload, the `destinationBufferRange` is
the fourth parameter. Once the command buffer has completed
execution, the encoded image data will be stored in this
buffer range. The byte size should be `width * height / 2`.
The `width` and `height` parameters are simply the width
and height of the image to be compressed, in pixels.

In the second overload, the `destinationImage` is the
fourth and last parameter. Once the command buffer has
completed execution, the compressed data will have been
copied to the image. The image must have the layout
`VK_IMAGE_LAYOUT_TRANSFER_DST_OPTIMAL`. Assuming that
the source image data uses SRGB, the image format should
be `VK_FORMAT_BC1_RGBA_SRGB_BLOCK`.

## Synchronization
The `compress` method won't perform any synchronization on
the source buffer and destination image/buffer. It's your
own responsibility to handle potential memory barriers and
layout transitions. Furthermore, the `compress` method
won't submit or *end* the command buffer/recorder, so
that's also up to you.

## Cleaning up
Once you are done with a worker, call its `destroy()`
method. If you are done with all compression, call the
`destroy()` method on all workers, after which you should
call the `destroy(true)` method of the `Bc1Compressor`.
