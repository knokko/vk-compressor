# vk-compressor
## Vulkan image compressor, written in Java
This library provides methods and classes to deal with some
compressed (Vulkan) image formats. Currently, it provides:
- a [BC1 image compressor](docs/bc1.md) using a compute shader
- a [wrapper of a BC7 image compressor](docs/bc7.md)
- a ['kim1' image compressor](docs/kim1.md), decompressor, and sampler.
The 'kim1' format is a format that I invented for small images
where many pixels have the same color as another pixel.
- a ['kim2' image compressor](docs/kim2.md), decompressor, and sampler.
The 'kim2' format is a format that I invented for images where
each pixel has approximately the same color,
but with a possibly different intensity.

This library requires
[vk-boiler 4.2](https://github.com/knokko/vk-boiler).

### Performance
From my rough measurements (on my PC):
- The bc1 encoder can compress ~12M pixels in ~22ms,
  which is ~500k pixels per millisecond
- The bc7 encoder can compress ~48M pixels in ~36 seconds,
  which is ~1.33k pixels per millisecond 
  (but note that encoding bc7 images is inherently difficult)
- The kim1 encoder can compress ~13M pixels in ~300ms,
  which is ~40k pixels per millisecond
- The kim2 encoder can compress ~58M pixels in ~400ms,
  which is ~140k pixels per millisecond

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
  implementation 'com.github.knokko:vk-compressor:v0.4.0'
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
  <groupId>com.github.knokko</groupId>
  <artifactId>vk-compressor</artifactId>
  <version>v0.4.0</version>
</dependency>
```
