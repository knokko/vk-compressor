# vk-compressor
## Vulkan image compressor, written in Java
This library provides methods and classes to deal with some
compressed (Vulkan) image formats. Currently, it provides:
- a [BC1 image compressor](docs/bc1.md) using a compute shader
- a ['kim1' image compressor](docs/kim1.md), decompressor, and sampler.
The 'kim1' format is a format that I invented for small images
where many pixels have the same color as another pixel.

This library requires
[vk-boiler 4.2](https://github.com/knokko/vk-boiler).

### Performance
From my rough measurements (on my PC):
- The bc1 encoder can compress ~12M pixels in ~22ms,
  which is ~500k pixels per millisecond
- The kim1 encoder can compress ~13M pixels in ~300ms,
  which is ~40k pixels per millisecond

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
  implementation 'com.github.knokko:vk-compressor:v0.2.3'
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
  <version>v0.2.3</version>
</dependency>
```