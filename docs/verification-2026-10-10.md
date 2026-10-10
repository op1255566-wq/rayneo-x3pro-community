# 本次发布准备验证

核对日期：2026-10-10。

| 检查 | 结果 |
| --- | --- |
| BBLL 2026-05-30 原稳定 APK SHA-256 | 与既有封版记录一致，原文件未修改 |
| Lemuroid 2026-05-28 原交付 APK SHA-256 | 与既有交付记录一致，原文件未修改 |
| 轻量浏览器 0.4.0 存档与历史构建输出 SHA-256 | 完全一致 |
| 存档浏览器 APK 签名验证 | Android apksigner 验证通过 |
| Lemuroid 补丁对确定上游提交的 git apply --check | 通过 |
| LibretroDroid 补丁对官方 0.13.2 源码的 git apply --check | 通过 |
| BBLL helper 及两个配套 helper 的 Java 编译 | 对 Android SDK 34 stub 的 javac 编译通过；不等于完整 BBLL 已重建 |
| 浏览器 Java 编译 / Android 资源及 Manifest 链接 / DEX 生成 | javac、aapt2、D8 分别通过 |
| 本机 Gradle assembleDebug | 未通过：JDK 17 回环连接错误，尚非完整 Gradle 重建验收 |
| 发布目录的敏感数据和相对文档链接检查 | 未发现已定义的令牌、私钥、设备标识、私人目录或断链规则命中 |
| 当前眼镜和网站实测 | 本次未执行，历史短时结果的局限见各应用说明 |

源码清单和 SHA-256 在 source-manifest.json。设备日志、签名密钥、ROM、BIOS、上游原 APK、厂商系统 APK/AAR、修改过的核心 .so 和整个本机工作区均不进入公开源码包。

这不是对仓库所有历史来源的全面法律审计，也不是“完整 Lemuroid 历史 APK 对应源码已补齐”的声明。BBLL 与 Lemuroid 完整 APK 的发布条件在各自 README 中注明。
