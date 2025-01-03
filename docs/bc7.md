# Using the BC7 compressor
The BC7 image format is a standardized GPU-compressed image
format supported by almost any *desktop* GPU. It requires 1
byte per pixel (twice as much as BC1), but looks much better on some
images, especially translucent images.

I did **not** write my own BC7 compressor: this library is just a
wrapper around [bc7enc_rdo](https://github.com/richgel999/bc7enc_rdo).
This library includes precompiled binaries for `bc7enc_rdo`, which I
ripped from [gpu-tex-enc](https://www.npmjs.com/package/@gpu-tex-enc/bc).
Furthermore, this library includes
[ispc](https://ispc.github.io/downloads.html), which is required
on Linux.

## Supported OS's
Windows and macOS are supported, and no additional installation is
needed.

Some Linux distributions are supported, but not all.
For instance, Ubuntu is supported, but Alpine is not. It looks like
the precompiled binary only supports distributions with `glibc`
support.

The Linux distributions that are supported do however need to
install OpenMP, for instance by running `sudo apt install libomp-dev`
on Ubuntu.

## Usage
To use this BC7 wrapper, you need to call
`Bc7Compressor.compressBc7(bufferedImage)`. This method will
return a `byte[]`, which will be the BC7 *payload*: this is the
stuff that you should put in a buffer, and send to a BC7
`VkImage` using `vkCmdCopyBufferToImage`. This byte array does
**not** contain any header data (like the width or height), because
you can just grab the width and height from the `bufferedImage`
that you passed as parameter.
