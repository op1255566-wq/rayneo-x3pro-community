# Lemuroid RayNeo X3 Pro 移植补丁

原作者：Swordfish90 与 Lemuroid/Retrograde/LibretroDroid 贡献者。派生部分遵循 GPL-3.0，完整许可见仓库根目录 LICENSE。

本机 2026-05-28 已交付可用阶段版本：`com.swordfish.lemuroid.debug`，ARM64，47,622,074 bytes。

历史 APK SHA-256：`400BEF440DA550363CAD0DFD701C1ECB9002509EAA319571D206B619CA4BAE02`。

适配成果：固定 `/sdcard/Android/data/com.swordfish.lemuroid.debug/files/roms` 目录及启动扫描、去除触屏虚拟手柄、蓝牙手柄输入、native 同帧左右眼、镜腿双击返回、FBNeo 60Hz/48kHz 与稳定缓冲。历史已测 7 个用户自备游戏，未公开其 ROM；系统层音频 underrun 为 0，但主观长期音频体验仍需复测。

## 源码结构

`patches/lemuroid-x3pro.patch` 以 Lemuroid 提交 `4266d3736b833f2fc75e0be9235a042bec53ae0a` 为基础。

`overlay/` 是新增 Kotlin 与资源文件；`patches/libretrodroid-0.13.2-x3pro.patch` 是根据官方 `0.13.2` 和本机修改文件生成的 native/音频/构建差异。

## 应用补丁

```sh
git clone https://github.com/Swordfish90/Lemuroid.git
cd Lemuroid
git checkout 4266d3736b833f2fc75e0be9235a042bec53ae0a
git apply /path/to/rayneo-x3pro-community/apps/lemuroid-port/patches/lemuroid-x3pro.patch
```

再将 overlay 内文件按相同相对路径复制到 Lemuroid 根目录。保留上游 COPYING。

```sh
git clone --branch 0.13.2 https://github.com/Swordfish90/LibretroDroid.git
cd LibretroDroid
git submodule update --init --recursive
git apply /path/to/rayneo-x3pro-community/apps/lemuroid-port/patches/libretrodroid-0.13.2-x3pro.patch
```

在 JDK 17、所需 Android SDK/NDK/CMake 环境构建 LibretroDroid AAR；官方 0.13.2 构建环境还需兼容其上游 Gradle/AGP。复制产物到 `Lemuroid/lemuroid-app/libs/libretrodroid-opensles-arm64-release.aar`。自行从 RayNeo 官方取得 `MercuryAndroidSDK-v0.2.2-20250717110238_48b655b3.aar` 放在同一 libs 目录，然后按上游流程构建 `:lemuroid-app:assembleFreeBundleDebug`。

## 二进制发布尚待补齐

本机 LemuroidCores 基础提交为 `fee2e824525daa22bcf318f96127fe43fa8a15ad`，其中 FBNeo `.so` 被本地修改，尚未建立完整源代码对应关系。厂商 SDK 分发条件也须核实。因此目前分享源码补丁，没有分发完整 APK、AAR、核心 `.so`、ROM 或 BIOS。这些补丁不被宣称为历史 APK 的完整对应源代码；完成复现与依赖审计后再发二进制。
