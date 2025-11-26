# vk-compressor
## Vulkan image compressor, written in Java
This library provides methods and classes to deal with some
compressed (Vulkan) image formats. Currently, it provides:
- a [BC1 image compressor](bc1/docs.md) using a compute shader
- a [BC4 image compressor](bc4/docs.md) using a compute shader
- a [wrapper of a BC7 image compressor](bc7/docs.md)
- a ['kim1' image compressor](kim1/docs.md), decompressor, and sampler.
The 'kim1' format is a format that I invented for small images
where many pixels have the same color as another pixel.
- a ['kim2' image compressor](kim2/docs.md), decompressor, and sampler.
The 'kim2' format is a format that I invented for images where
each pixel has approximately the same color,
but with a possibly different intensity.
- a ['kim3' image compressor](kim3/docs.md), decompressor, and sampler.
The 'kim3' format is an alternative to the kim1 format that
is cheaper to sample on old integrated GPUs, but takes a bit
more space.

This library requires
[vk-boiler 5.0](https://github.com/knokko/vk-boiler).

### Performance
From my rough measurements (on my PC):
- The bc1 encoder can compress ~40M pixels in ~1.2ms,
  which is ~33M pixels per millisecond*
- The bc4 encoder can compress ~80M pixels in ~0.5ms,
  which is ~160M pixels per millisecond*
- The bc7 encoder can compress ~48M pixels in ~36 seconds,
  which is ~1.33k pixels per millisecond 
  (but note that encoding bc7 images is inherently difficult)
- The kim1 encoder can compress ~13M pixels in ~300ms,
  which is ~40k pixels per millisecond
- The kim2 encoder can compress ~58M pixels in ~400ms,
  which is ~140k pixels per millisecond
- TODO kim3

\* I only count the time needed for *compressing* the image,
*not* the time to load the source image from disk,
or to save the compressed image to disk.
For bc1/bc4 encoding, saving/loading the image(s) from disk
will normally take much more time than the compression itself,
so this throughput can probably not be reached in practice.

### Adding vk-compressor as dependency
#### Java version
This project requires Java 17 or later (Java 17 and 21
are tested in CI).

#### LWJGL and vk-boiler
While this project is compiled against LWJGL and `vk-boiler`,
it does **not** bundle them, so you still need to declare
the LWJGL and `vk-boiler` dependencies yourself (hint: use
[LWJGL customizer](https://www.lwjgl.org/customize)
). This approach allows you to control which version of
LWJGL and `vk-boiler` you want to use (as long as they
are compatible).

#### Modules
This project is split into 6 gradle modules: 1 for each
compression format: `kim1`, `kim2`, `kim3`, `bc1`, `bc4`, and `bc7`.
You should add the ones you need. The `Gradle` and `Maven`
examples below add just 1 of the modules. You need 1 line
for each module you want.

#### Gradle
```
...
repositories {
  ...
  maven { url 'https://jitpack.io' }
}
...
dependencies {
  ...
  implementation 'com.github.knokko.vk-compressor:bc1:v0.5.0'
}
```

#### Maven
```
...
<repositories>
  ...
  <repository>
    <id>jitpack.io</id>
    <url>https://jitpack.io</url>
  </repository>
</repositories>
...
<dependency>
  <groupId>com.github.knokko.vk-compressor</groupId>
  <artifactId>kim2</artifactId>
  <version>v0.5.0</version>
</dependency>
```
