# Muse Launcher v1.2.1

本版本修复账号切换、代理应用时序、数据恢复及页面资源回收问题。

- 修复 WebView 淘汰后资源未释放、初始化失败和渲染进程退出时的生命周期处理。
- 校验账号 ID，保留损坏数据并恢复备份；延迟删除的 Profile 在下次冷启动继续清理。
- 代理变更按顺序等待生效，测活使用独立进程；只有测试通过的同一份代理配置可以保存。
- 下载使用所属账号的会话与代理，正确处理重定向；文件选择回调随账号切换和页面销毁取消。
- 改进 Emoji、会话 Cookie 识别与账号列表刷新；已加载页面切换更直接。
- GitHub Actions 自动运行单测、Lint 和构建，并发布经过签名验证的安装包及 SHA-256 校验文件。

安装 `MuseLauncher-v1.2.1.apk`，要求 Android 10+，且系统 WebView 支持 Multi-Profile。手机 Clash 使用 VPN/TUN 时，各账号代理留空即可。

本 fork 使用固定的独立签名。如果已安装其他签名的上游版或 debug 版，需要先卸载旧包；卸载会清除账号配置和登录数据，请先记录必要配置。后续本 fork 的同签名版本支持覆盖升级。

账号隔离针对 Cookie、登录状态和站点存储；不同账号仍共用真实手机，代理仍是 WebView 进程级设置。尚未完成 Android 真机验证。

详细修复和验证范围见 [docs/FIXES.md](https://github.com/544r/muse-launcher/blob/v1.2.1/docs/FIXES.md)。
