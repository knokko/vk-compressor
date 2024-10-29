# About the kim1 format
The kim1 format is an image format invented by me, and
is simply an appreviation for Knokko IMage format. It was
made to compress small images where the number of distinct
colors is rather small, for instance

![An example image with few distinct colors](
../src/test/resources/com/github/knokko/compressor/mardek/Flametongue.png
)

- If you store this image uncompressed, it would take
`4 * 16 * 16 = 1024` bytes.
- When I save it as PNG file, it takes 455 bytes.
- With BC1 compression, it would take 128 bytes,
but it would cost a lot of detail.
- In the kim1 format, it takes 132 bytes instead.

Thus, the kim1 format is very effective for such images
because it is **lossless** and takes up little space.
Furthermore, it can be sampled by shaders, just like
BC1.

Please note that this format is only suitable when the
number of distinct colors is small, which only holds for
simple images like the one presented above. This format
is **inefficient** for any other type of image, and
therefor quite niche. Luckily, such other images can
usually be compressed well using BC1.

Since I invented this format myself, it is **not** a
standard (Vulkan) image format. Therefor, you need to
store it in a uniform/storage buffer, and use my
provided sampling function to 'sample' it. Instructions
for compression, decompression, and sampling are given
below.

## Compressing
The `Kim1Compressor` can be used to convert uncompressed
RGBA images to kim1. You need to use its public
constructor, which takes a `ByteBuffer` (for the data),
a width, a height, and the number of channels
(typically 4 for RGBA). You need to create 1
`Kim1Compressor` instance per image you want to compress.

The `intSize` field of a `Kim1Compressor` instance tells
you how many `int`s it needs to store all its data. To
compress the data, you need to call its `compress` method,
which requires a `ByteBuffer` that has (at least)
`4 * intSize` remaining bytes.

## Decompressing
The `Kim1Decompressor` can be used to decode compressed
kim1 data, and restore the original image data.
You need to use its public constructor, whose only
parameter is a `ByteBuffer`: the compressed data.

The `width` and `height` field of a `Kim1Decompressor`
instance tell you the size of the original image. To
actually decompress it, you need to call the
`getColor(x, y)` for each pixel that you want to recover
(all pixels if you want the original image back).

## Sampling
If you store compressed kim1 data in a uniform buffer or
storage buffer, you can sample the data from shaders
(just like you can sample from regular `VkImage`s).
- You need to declare a `uint[]` (inside a uniform block)
in your shader that needs to sample.
- You need to know the offset into this array at which
your image is stored (`0` if you only have 1 image).
- You need to include [kim1.glsl](
../src/test/resources/com/github/knokko/compressor/kim1.glsl)
, which you can just copy-paste since I have no clue how
to add it as a proper dependency.
- You need to call `defineReadInt(kimBufferName)`, where
`kimBufferName` is the name of your `uint[]`. Note that
you should **not** use a semicolon here.
- You need to call `defineSampleKimFloat(kimBufferName)` or
`defineSampleKimInt(kimBufferName)`, depending on whether
you want to sample with normalized coordinates, or
absolute coordinates.
- Finally, you can sample it like
```glsl
void main() {
	outColor = sampleKim(imageOffset, textureCoordinates);
}
```

You can take a look at examples [here](
../src/test/resources/com/github/knokko/compressor/kim1.frag)
and [here](
../src/test/resources/com/github/knokko/compressor/kim1-test.frag)
