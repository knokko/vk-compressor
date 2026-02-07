# About the kim3 format
The kim3 format is another image format invented by me, 
and a simpler variant of the [kim1 image format](
../kim1/docs.md
). 
- It is slightly less compact.
- It is not completely lossless, but the quality loss is hard to spot with the naked eye.
- It is more efficient to sample on old iGpu's.

but more efficient to sample on old iGpu's.
Let's take a look at this example again:

![An example image with few distinct colors](
../test-helper/src/main/resources/com/github/knokko/compressor/mardek/Flametongue.png
)

- If you store this image uncompressed, it would take
`4 * 16 * 16 = 1024` bytes.
- When I save it as PNG file, it takes 455 bytes.
- With BC1 compression, it would take 128 bytes,
but it would cost a lot of detail.
- In the kim1 format, it takes 132 bytes instead.
- In the **kim3** format, it takes 164 bytes.

If you want better performance on old integrated GPUs,
this extra size may or may not be worth the faster
sampling (in the fragment shader).

## Compressing using the CLI
To compressor `some-image.png`, and store the compressed data in `some-image.kim3`, use the following command:
```shell
./vk-compressor some-image.png --encoding kim3
```
To 'preview' it, you can run:
```shell
./vkc-preview some-image.kim3 --gui
```
Note that the size is always included in `kim3` files,
so the `--width` and `--height` arguments of `vkc-preview` are not needed.

## Compressing using the API
The `Kim3Compressor` can be used to convert uncompressed
RGBA images to kim3. You need to use its public
constructor, which takes a `ByteBuffer` (for the data),
the width, and the height. You need to create 1
`Kim3Compressor` instance per image you want to compress.

The `intSize` field of a `Kim3Compressor` instance tells
you how many `int`s it needs to store all its data. To
compress the data, you need to call its `compress` method,
which requires a `ByteBuffer` that has (at least)
`4 * intSize` remaining bytes.

## Decompressing using the API
The `Kim3Decompressor` can be used to decode compressed
kim3 data, and restore the original image data (although
this will cost some precision due to the SRGB-to-linear
and linear-to-SRGB conversion).
You need to use its public constructor, whose only
parameter is a `ByteBuffer`: the compressed data.

The `width` and `height` field of a `Kim3Decompressor`
instance tell you the size of the original image. To
actually decompress it, you need to call the
`getColor(x, y)` for each pixel that you want to recover
(all pixels if you want the original image back).

## Sampling from a (fragment) shader
If you store compressed kim3 data in a uniform buffer or
storage buffer, you can sample the data from shaders
(just like you can sample from regular `VkImage`s).
- You need to declare a `uint[]` (inside a uniform block)
in your shader that needs to sample.
- You need to know the offset, `textureIndex`,
into this array at which your image is stored
(`0` if you only have 1 image).
- You need to include [kim3.glsl](
./src/test/resources/com/github/knokko/compressor/kim3.glsl)
, which you can just copy-paste since I have no clue how
to add it as a proper dependency.
- You need to call `defineReadInt(kimBufferName)`, where
`kimBufferName` is the name of your `uint[]`. Note that
you should **not** use a semicolon here.
- You need to call `defineSampleKim3(kimBufferName)`,
without semicolon.
- Finally, you can sample it like
```glsl
void main() {
	outColor = sampleKim3(header, textureIndex, firstColor, textureCoordinates);
}
```
where `header = kimBufferName[textureIndex]` and
`firstColor = kimBufferName[textureIndex + 1]`.
For performance reasons, you should sample `header` and
`firstColor` in the vertex shader, and propagate them to
the fragment shader.

You can take a look at an example [here](
./src/test/resources/com/github/knokko/compressor/kim3-test.frag)
