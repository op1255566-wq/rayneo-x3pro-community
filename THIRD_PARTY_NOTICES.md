# 作者、转载与第三方声明

核对日期：2026-10-10。

| 内容 | 上游/作者 | 使用方式 | 本仓库的处理 |
| --- | --- | --- | --- |
| BBLL | [xiaye13579/BBLL](https://github.com/xiaye13579/BBLL) | 原应用及社区历史来源 | 只链接，不包含原 APK 或反编译工程 |
| BBLLV5 | [swyefun/BBLLV5](https://github.com/swyefun/BBLLV5) | `com.V2.blb5` 适配基线的社区维护来源 | 不将其代码、图标或 APK 声称为原创；没有把公开下载等同于改版分发许可 |
| RayneoMirrorHelper.java | 本项目适配贡献者 | 新增独立 Android 适配层 | GPL-3.0；未包含 BBLL 业务源码 |
| BridgeCommandServer.java / LightningX3Helper.java | 本项目适配贡献者 | 配套新增 helper；返回适配涉及 [AnthonyCR 的 Lightning Browser](https://github.com/anthonycr/Lightning-Browser) | GPL-3.0；不包含 Lightning 原应用源码或 APK；原应用由 AnthonyCR 及贡献者维护 |
| Lemuroid | [Swordfish90/Lemuroid](https://github.com/Swordfish90/Lemuroid) 及原贡献者 | 改编 | 保留 GPL-3.0，补丁目标为提交 `4266d3736b833f2fc75e0be9235a042bec53ae0a` |
| Retrograde | [retrograde/retrograde-android](https://github.com/retrograde/retrograde-android) | Lemuroid 的历史来源 | 保留上游版权及许可证链；本仓库仅含 Lemuroid 改动补丁 |
| LibretroDroid | [Swordfish90/LibretroDroid](https://github.com/Swordfish90/LibretroDroid) | 改编 | GPL-3.0；对 `0.13.2` 的双目视频和音频补丁 |
| LemuroidCores / Libretro / FBNeo | [LemuroidCores](https://github.com/Swordfish90/LemuroidCores)、[FBNeo](https://github.com/libretro/FBNeo) 及各核心作者 | 本机历史 APK 包含核心二进制 | 各核心许可证需分别遵守；修改过的 FBNeo 二进制尚未补齐对应源代码，因此不分发该 APK/核心 |
| Mercury Android SDK | [RayNeo 开发文档](https://rayneo.gitbook.io/rayneo-devdoc/x-xi-lie/android-kai-fa) | Lemuroid 本机依赖；文档参考 | 不转载厂商 AAR 或系统 APK；开发者自行从官方获得 SDK 并遵守条件 |
| 轻量抖音浏览器的应用源码 | 本项目适配贡献者 | 独立 Android 应用 | GPL-3.0；不是抖音官方 APK 的反编译/重打包版本 |
| 抖音网站和视频 | 抖音平台及内容作者 | 浏览器访问网页和播放用户可访问流 | 本仓库不复制网站、视频、账户会话或破解权限；服务条款与内容权利继续适用 |
| Gradle wrapper | [Gradle](https://github.com/gradle/gradle) 贡献者 | 保留生成的 wrapper 脚本和 JAR | Apache-2.0；见 `licenses/Apache-2.0.txt`，保留脚本中的版权说明 |

以下只作为研究链接，没有复制实现：TapLink（TheophileGaudin；X3 fork 为 informalTechCode）、Moonlight X3 fork（informalTechCode）、TapGem（tropicalstream）。引用和致谢不意味着获得许可证或作者背书。

本次新增贡献：双目视口、视频同步、镜腿输入适配、ROM 目录引导、音频参数调节、移植和构建说明。第三方原应用、品牌、核心、ROM 与 BIOS 均不属于本次原创成果。
