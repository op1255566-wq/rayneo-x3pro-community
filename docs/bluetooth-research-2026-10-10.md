# X3 Pro 第三方蓝牙：2026-10-10 检索结果

检索覆盖 GitHub、作者 Reddit 发帖、官方开发文档与产品说明、中文侧载教程。仅把作者仓库/发布说明与官方 API 作为实现证据，论坛作为体验和故障线索。搜索无结果不能证明方案不存在。

| 路线 | 证据 | 对本机目标的意义 |
| --- | --- | --- |
| 键鼠、手柄、Mudra 直接连接 | [Informal-Tech 作者展示](https://www.reddit.com/r/RayNeo/comments/1r2t0kp/rayneo_x3_pro_i_unlocked_bluetooth_and_ported/)、[Moonlight X3 fork](https://github.com/informalTechCode/moonlight-android-RayNeoX3) | 作者报告多外设并连，但未找到可直接用于本机国行固件的独立通用配对 APK；其步骤与本机差异仍需验证 |
| Mudra 应用内输入适配 | [TapLink Releases](https://github.com/informalTechCode/TAPLINKX3/releases) | 1.5.1 提到识别 Mudra 自动开启鼠标模式；改善应用收到的输入，不等于解除系统配对限制 |
| 手机控制器 + 蓝牙 RFCOMM + UDP | [TapLink 用户指南](https://github.com/informalTechCode/TAPLINKX3/blob/main/docs/USER_GUIDE.md) | 1.6.0 新增控制器，当前公开最新 release 为 1.7.0；蓝牙用于发现/控制/回退，UDP 用于高频输入；需要手机，适合作为备选 |
| 原生蓝牙设置 | [RayNeo 官方连接说明](https://www.rayneo.com/blogs/news/smart-glasses-bluetooth-connectivity-guide-2026) | 官方网页描述添加键盘/戒指的设置路径；没有足够固件/地区信息证明你这台旧国行系统具备相同入口 |
| 停止/暂时禁用 Mercury 后配对再恢复 | 本机既有已成功配对记录 | 对本机证据最强；不是新发现。历史 Mercury 拦截 PAIRING_REQUEST；配对与按键映射必须分别验证 |
| Wi-Fi 手机投屏 | [X3Mira](https://github.com/tropicalstream/X3Mira) | 解决手机内容投到眼镜，不是手柄独立连接或系统全局控制 |

## 本次结论

有新的控制器与应用内适配，但截至核对时，未找到有明确代码、固件说明和成功证据、可以保证替代本机 Mercury 配对流程的通用免授权解锁方案。网上“支持蓝牙”可能分别指手机链路、音频、HID 外设或专用 GATT/RFCOMM；必须确认目标是手柄直接连接眼镜。

当前手柄键盘模式 A→ENTER 已在历史实测中全局确认；B→ESC 的稳定全局返回仍未解决。新桌面可以在自身范围处理 ENTER/ESC/DPAD；其他应用必须自己支持返回/焦点，或另做经过验证的无障碍/授权辅助层。

[Android createBond() 文档](https://developer.android.com/reference/android/bluetooth/BluetoothDevice#createBond())：调用是异步请求，系统处理必要确认，返回 true 仅表示启动配对，并非已经配对成功。普通 APK 不能凭此获得系统特权；Shizuku/ADB 权限路径仅属待验证工程选项，不是本次发现的 X3 专用成品。

## 推荐验证顺序

1. 确认眼镜固件/地区、外设 Classic HID 或 BLE HID 模式，排除手机抢连。
2. 检查设备是否已存在 bond、是否能扫描；沿用已成功流程完成一次配对，再恢复 Mercury。
3. 分别测重启回连、桌面 A/B/DPAD、BBLL、游戏内输入；不要用可移动焦点替代全部按键可用。
4. 新桌面安装和 HOME 替换先独立测试；默认桌面变化不保证 Mercury 配对 receiver 停止。

未执行本机新配对、禁用系统包或刷机。未将 iO、X2 或 Air 系列蓝牙方案混作 X3 Pro 成果。
