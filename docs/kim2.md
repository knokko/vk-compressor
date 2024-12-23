# About the kim2 format
The kim2 format is an image format invented by me, and
is simply an appreviation for Knokko IMage format. It was
made to compress images where each pixel has approximately
the same color, but with possibly different intensities.

![An example of such an image](
../src/test/resources/com/github/knokko/compressor/EarthThick.png
)

- If you store this image uncompressed, it would take
  `4 * 240 * 240 = 230k` bytes.
- When I save it as PNG file, it takes 63k bytes.
- With BC1 compression, it would take 29k bytes,
  but look like this:

  ![BC1 output](./kim2/output-bc1.png)
- In the kim2 format with 4 bits per pixel, it also takes 29k bytes,
  but looks much better:

  ![4-bit output](./kim2/output4.png)
- When using 8 bits per pixel instead, it looks even better,
  but does use 58k bytes instead:

  ![8-bit output](./kim2/output8.png)
- When using 2 bits per pixel, it becomes 14k bytes,
  but looks considerably worse:

  ![2-bit output](./kim2/output2.png)
- It is also possible to use only 1 bit per pixel, which requires
  only 7k bytes, and has an... interesting... style:

  ![1-bit output](./kim2/output1.png)


Thus, the kim2 format is very effective for images like the one
above, since it takes much less space and does not look much
worse (at least, when you use 4 or 8 bits per pixel).
Furthermore, it can be sampled by shaders, just like
BC1.

Please note that this format is only suitable for images like the
one above: it will look horrible on most other images.

Since I invented this format myself, it is **not** a
standard (Vulkan) image format. Therefor, you need to
store it in a uniform/storage buffer, and use my
provided sampling function to 'sample' it. Instructions
for compression, decompression, and sampling are given
below.

## Compressing
The `Kim2Compressor` can be used to convert uncompressed
images to kim2. You need to use one of its static `compress`
methods to put the compressed data in an `IntBuffer`.

You can use the `predictIntSize` method to predict the
minimum capacity that your `IntBuffer` must have.

## Decompressing
The `Kim2Decompressor` can be used to decode compressed
kim2 data, and restore the original image data. You need
to use one of its static `decompress` methods for this.

You can also use the static `getWidth()`, `getHeight()`,
or `getBitsPerPixel()` methods to query basic information
about compressed kim2 images.

## Sampling
If you store compressed kim2 data in a uniform buffer or
storage buffer, you can sample the data from shaders
(just like you can sample from regular `VkImage`s).
- You need to declare a `uint[]` (inside a uniform block)
  in your shader that needs to sample.
- You need to know the offset into this array at which
  your image is stored (`0` if you only have 1 image).
- You need to include [kim2.glsl](
  ../src/test/resources/com/github/knokko/compressor/kim2.glsl)
  , which you can just copy-paste since I have no clue how
  to add it as a proper dependency.
- You need to call `defineSampleKim2Float(kimBufferName)` or
  `defineSampleKim2Int(kimBufferName)`, depending on whether
  you want to sample with normalized coordinates, or
  absolute coordinates. You must **not** put a semicolon
  behind this macro call.
- Finally, you can sample it like
```glsl
void main() {
	outColor = sampleKim2(imageOffset, textureCoordinates);
}
```

You can take a look at an example [here](
../src/test/resources/com/github/knokko/compressor/kim2.frag).
