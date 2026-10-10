# 全新 X3 Pro 桌面启动器：可行性与实施范围

结论：能实现一个独立开发、双目同显、镜腿和手柄可用的应用启动器。成为系统默认 HOME 和整合蓝牙需在当前固件验证；不保证普通 APK 可以替换厂商后台服务或修复其他应用渲染。

## 首版建议

黑底、640×480 单眼视口、左右同步。主页显示时间、电量、网络、最近应用与收藏；应用页包含已安装可启动应用，单击/ENTER/A 确认，双击/ESC/B 返回桌面内部上一层，DPAD 与镜腿滑动选择。提供 BBLL、Lemuroid、浏览器快捷入口、蓝牙设置入口和返回官方桌面的按钮。

采用 Java/Kotlin 原生 Android 页面与 RayNeo 合目/触控能力；时间、电量等按事件或低频更新，避免高帧率常驻截图。首版不要求联网 AI 或大型网页桌面。

## 分阶段实施

1. 并排安装：声明正常 LAUNCHER 入口与 `com.rayneo.mercury.app=true`，从官方桌面启动；只测试自身应用列表、双目布局和真实镜腿/手柄。
2. 默认 HOME：为 Activity 增加 MAIN/HOME/DEFAULT 入口，检测 [ROLE_HOME](https://developer.android.com/reference/android/app/role/RoleManager#ROLE_HOME) 可用性；使用系统选择或 ADB 设置。必须验证 HOME、重启、应用返回和官方服务，并保存可恢复的原默认桌面信息。
3. 蓝牙入口：普通设置入口先做，授权辅助层另测。换了默认桌面不一定让 Mercury 动态配对 receiver 停止；短时停止/禁用旧包仍需恢复和回归，不能把新桌面等同于蓝牙解锁。
4. 产品化：验证用户的手柄与现有稳定应用，然后增加排序、文件夹、快捷操作；普通手机 APK 双目化与任意多窗口不属于已解决能力。

## 参考与代码使用边界

[TapGem](https://github.com/tropicalstream/tapgem) 已提供 X3 专用 HUD/桌面、窗口操作和语音的作者实现，可参考交互与黑底显示思路。核对时没有明确许可证，故未复制它的代码、图标或 APK，也不能将它称为自由可改编的桌面底座。

[TapLink](https://github.com/informalTechCode/TAPLINKX3) 的 dashboard、应用启动和双目交互是另一参考，其仓库标示 Apache-2.0；若将来实际复用，应保留许可证、原作者/NOTICE 并注明修改。本文目前仅链接。

[RayNeo Android 开发文档](https://rayneo.gitbook.io/rayneo-devdoc/x-xi-lie/android-kai-fa) 提供合目、SDK 初始化及镜腿触控能力。是否设为默认桌面与系统服务权限不是由合目 SDK 保证的。

本次完成研究和方案，没有制作或在设备上安装新启动器，没有改变默认 HOME 或官方桌面状态。
