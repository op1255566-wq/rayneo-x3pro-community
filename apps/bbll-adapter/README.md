# BBLL X3 Pro 适配层

上游应用来源见 [第三方声明](../../THIRD_PARTY_NOTICES.md)。这里分享独立编写的 `RayneoMirrorHelper.java`，以及编译所需的配套 `BridgeCommandServer.java`、`LightningX3Helper.java`，不是完整 BBLL 源码或可独立安装应用。完整改版 APK 尚未取得明确再分发许可，因此暂不上传。

本机稳定基线：2026-05-30；包名 `com.V2.blb5`。

SHA-256：`702C5D116BE4E049E4FEE801AF75CE4F2AC41363F444EC0530C1B323CC28101E`。

新增适配：每眼 640×480；普通 UI 镜像；视频采用 Window PixelCopy 与独立 PopupWindow，左右眼显示同一帧并避免递归抓屏；默认进入推荐页、AVC、720p；镜腿相对光标、抓手滚动、无输入自动隐藏光标。

2026-05-30 历史验收：冷启动与二次启动进入推荐页，连续滚动及视频播放正常；采样视频左右眼平均像素差为 0。2026-10-10 只复核基线哈希，没有重测 Bilibili 当前接口可用性。

该 helper 需要与合法取得的特定应用版本集成、接入 Activity 生命周期及输入回调。源码包含用于历史研究的安全 RFCOMM 输入桥和 Lightning 浏览器返回适配分支；正常镜腿输入不要求手机桥接，它们也不是蓝牙解锁方案。这里不提供反编译的上游业务类或图标。新页面、播放器 Surface 或版本升级后必须重测双眼同步；原有 APK 保留作为本地回滚成果。
