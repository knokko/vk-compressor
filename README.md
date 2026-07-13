# vk-compressor
## CLI and API for compressing images to some GPU formats
This tool/library provides compressors for several standardized GPU image formats:
- a [BC1 image compressor](bc1/docs.md) using a compute shader
- a [BC4 image compressor](bc4/docs.md) using a compute shader
- [bindings to a BC7 image compressor](bc7/docs.md)

Furthermore, this tool provides compressors for some of my own 'image formats':
- a ['kim1' image compressor](kim1/docs.md), decompressor, and sampler.
- a ['kim2' image compressor](kim2/docs.md), decompressor, and sampler.
- a ['kim3' image compressor](kim3/docs.md), decompressor, and sampler.

### CLI
The CLI is the easiest way to use this tool.
You can download the CLI tool from [the releases](https://github.com/knokko/vk-compressor/releases).
- For Windows x64 and Linux x64, a native executable is provided.
- For all other platforms, you will have to use the JAR version instead.

For the native executable, the usage should be something like this:
```shell
./vk-compressor some-image.png --encoding bc7
```
which should compress `some-image.png`, and store the compressed version in `some-image.bc7`.
Note that `some-image.bc7` will only contain the *payload* of the image, but *not* the width or height.
It only contains the data that you want to copy with e.g. `vkCmdCopyBufferToImage`.
For the KIM image formats, the payload *does* include the size though.

The equivalent command for the JAR version would be:
```shell
java -jar vk-compressor.jar some-image.png --encoding bc7
```
Alternatively, the following command could be used in development:
```shell
./gradlew cli --args="some-image.png --encoding bc7"
```

To 'test' your compressed images, you can preview them using e.g. this command:
```shell
./vkc-preview some-image.bc7 --width 123 --height 123 --gui
```
The equivalent command for the JAR version would be:
```shell
java -jar previewer.jar some-image.bc7 --width 123 --height 123 --gui
```
Alternatively, the following command could be used in development:
```shell
./gradlew preview --args="some-image.bc7 --width 123 --height 123 --gui"
```

To learn about the remaining options, use `./vk-compressor --help` or `./vkc-preview --help`.
Note that `vk-compressor` supports multiple files per invocation, whereas `vkc-preview` does not.

#### CLI performance
The `--benchmark` option can be used to measure the performance of the compressor.

##### BC compression performance
The BC compression results are:
- BC1:
  ```
  ./vk-compressor /home/knokko/mardek/flash/all-shapes-x2/*.png --encoding bc1 --benchmark --num-threads 10 --batch-size 500`
  Processed 759112760 pixels from 15565 files
  Reading took 1059ms
  Processing took 2278ms
  Writing took 823ms
  ```
- BC4:
  ```
  ./vk-compressor /home/knokko/mardek/flash/all-shapes-x2/*.png --encoding bc4 --benchmark --num-threads 10 --batch-size 500
  Processed 759112760 pixels from 15565 files
  Reading took 806ms
  Processing took 886ms
  Writing took 819ms
  ```
- BC7 (default):
  ```
  ./vk-compressor /home/knokko/mardek/flash/all-shapes-x2/*.png --encoding bc7 --benchmark --num-threads 10 --batch-size 500
  Processed 759112760 pixels from 15565 files
  Reading took 1160ms
  Processing took 1217ms
  Writing took 699ms
  ```
- BC7 (high quality):
  ```
  ./vk-compressor /home/knokko/mardek/flash/all-shapes-x2/*.png --encoding bc7 --benchmark --num-threads 10 --batch-size 500 --bc7f-flags slowest
  Processed 759112760 pixels from 15565 files
  Reading took 1128ms
  Processing took 5915ms
  Writing took 656ms
  ```

Note that the BC1 and BC4 compressor run on the GPU. Most of the 'processing time' is spent on transferring the pixels
from the CPU to the GPU, and on transferring the compressed data from the GPU to the CPU.
The time spent during the compute shader itself is negligible.
From my rough measurements:
- The BC1 shader speed seems to be ~1 *million* pixels per *milli*second
- The BC4 shader speed seems to be ~50 *million* pixels per *milli*second

Unfortunately, this speed can only be achieved by using the API rather than the CLI,
and requires both the input and output to already be on the GPU.

##### KIM compression performance
Since the KIM image format only supports images with specific limits, I used a different (much smaller) test set.
- KIM1:
  ```
  ./vk-compressor /home/knokko/vk-compressor/test-helper/src/main/resources/com/github/knokko/compressor/mardek/*.png --encoding kim1 --benchmark --num-threads 10
  Processed 25344 pixels from 99 files
  Reading took 91ms
  Processing took 11ms
  Writing took 12ms
  ```
- KIM2:
  ```
  ./vk-compressor /home/knokko/vk-compressor/test-helper/src/main/resources/com/github/knokko/compressor/mardek/*.png --encoding kim2 --benchmark --num-threads 10 --kim2-bits-per-pixel 8
  Processed 25344 pixels from 99 files
  Reading took 91ms
  Processing took 12ms
  Writing took 6ms
  ```
- KIM3:
  ```
  ./vk-compressor /home/knokko/vk-compressor/test-helper/src/main/resources/com/github/knokko/compressor/mardek/*.png --encoding kim3 --benchmark --num-threads 10
  Processed 25344 pixels from 99 files
  Reading took 91ms
  Processing took 8ms
  Writing took 6ms
  ```
It looks like KIM compression is slightly faster than BC compression. For reference, using BC7 on this same (small) dataset:
- BC7 (default):
  ```
  ./vk-compressor /home/knokko/vk-compressor/test-helper/src/main/resources/com/github/knokko/compressor/mardek/*.png --encoding bc7 --benchmark --num-threads 10
  Processed 25344 pixels from 99 files
  Reading took 88ms
  Processing took 29ms
  Writing took 6ms
  ```

### API
The API is harder to use than the CLI, but is occasionally useful,
and required to get very high BC1/BC4 compression speed.
Since this library is written in Java, only Java applications can use the API.
Java 21 or later is required.
To start using the API, `vk-compressor` must be added as dependency, using either Gradle or Maven:

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
  maven {
    name = "knokko-reposilite"
    url = "https://49.12.188.159:8080/releases/"
    content {
        includeGroup "com.github.knokko"
        includeGroup "com.github.knokko.vk-compressor"
    }
  }
}
...
dependencies {
  ...
  // The next line adds the BC1 compressor. Change it to get another compressor.
  implementation "com.github.knokko.vk-compressor:bc1:1.0.0"
}
```

#### Maven
```
...
<repositories>
  ...
  <repository>
    <id>knokko-reposilite</id>
    <name>Knokko Reposilite</name>
    <url>https://49.12.188.159:8080/releases/</url>
  </repository>
</repositories>
...
<dependency>
  <groupId>com.github.knokko.vk-compressor</groupId>
  <!-- The next line adds the BC1 compressor. Change it to get another compressor. -->
  <artifactId>bc1</artifactId>
  <version>1.0.0</version>
</dependency>
```

#### Additional dependencies
This library requires [LWJGL](https://www.lwjgl.org) to be available at runtime,
but it does *not* bundle LWJGL (so that *you* can choose the LWJGL version).
All compressors require the 'core' of LWJGL, and the BC1 and BC4 compressors also require `lwjgl-vulkan`.
This library also bundles [vk-boiler 5.5](https://github.com/knokko/vk-boiler).

#### Using the API
Each of the compressor has its own docs, which tell you how to use it:
[bc1](./bc1/docs.md), [bc4](./bc4/docs.md), [bc7](./bc7/docs.md),
[kim1](./kim1/docs.md), [kim2](./kim2/docs.md), [kim3](./kim3/docs.md).
