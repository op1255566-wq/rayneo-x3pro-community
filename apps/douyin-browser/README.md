# 轻量抖音浏览器：RayNeo X3 Pro

一个独立编写的 Android 浏览器/播放器外壳，直接访问抖音公开网页。它不是官方抖音 APK 改包，不包含转载的视频、站点素材或账号数据。

版本 0.4.0，包名 `com.codex.x3douyin`，minSdk 26、compile/target SDK 34。应用只使用 Android 平台 API；Gradle wrapper 使用 Apache-2.0。

一个 640×480 WebView 作为网页控制和视频队列入口，原生 MediaPlayer 解码一份视频，OpenGL external texture 在同一绘制过程输出到两眼。原生播放开始后隐藏 WebView；镜腿上下滑动切换队列，点击确认，双击返回。

## 构建

设置 JDK 17 与 Android SDK。需要 Android SDK 34 和 Gradle 8.13；Gradle 根据 wrapper 下载发行版。

```powershell
$env:JAVA_HOME = 'C:/path/to/jdk17'
$env:ANDROID_HOME = 'C:/path/to/Android/Sdk'
.\gradlew.bat assembleDebug
```

输出：`app/build/outputs/apk/debug/app-debug.apk`。

```powershell
adb devices
adb -s YOUR_DEVICE_SERIAL install -r app/build/outputs/apk/debug/app-debug.apk
adb -s YOUR_DEVICE_SERIAL shell am start -n com.codex.x3douyin/.MainActivity
```

## 已验证和限制

2026-05-30 历史实机记录：连续队列 0→1→2，左右眼截图平均像素差 0，30 FPS 来源约 30 FPS，10 秒样本未见音频重启，约 89.5MB PSS。

0.4.0 应作为实验预发布。尚待长期佩戴检查、真实镜腿方向/阈值与当前网站复测；网站接口、WebView 版本与视频签名地址可能变化。浏览器不能替代平台登录、会员、地区或版权授权。短时截图同显不等于已经完成长期视觉和播放稳定性验收。

历史 APK：`x3_douyin_browser_native_queue_v040_debug_2026-05-30.apk`。

SHA-256：`D080B38A8AEE5363E1093C7BA1505FC1689B951473AA42AE2199665A8DE56EC3`。当前源码工程的历史 build 输出与该存档 APK 哈希相同；本次公开构建将在验证记录中单独注明。
