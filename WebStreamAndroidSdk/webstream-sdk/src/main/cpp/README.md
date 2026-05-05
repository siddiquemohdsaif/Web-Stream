# Native JPEG XL support

The SDK builds `webstream_jxl` through CMake and calls it from `NativeJxlCodec`.

To enable the real JPEG XL encoder/decoder, place a libjxl checkout at:

```text
webstream-sdk/src/main/cpp/third_party/libjxl
```

The referenced Android source tree can be used:

```text
https://github.com/crdroidandroid/android_external_libjxl
```

The CMake build automatically links libjxl when that directory contains a
`CMakeLists.txt`. If the directory is missing, the SDK still builds a small
stub library and logs that JXL is unavailable, then falls back to JPEG.

You can also point CMake at another checkout with:

```text
./gradlew assembleDebug -PwebstreamLibjxlRoot=/absolute/path/to/libjxl
```
