Muse Launcher 1.2.1 修复记录

本次依据 PROJECT_REVIEW.md 的审查修复核心正确性问题。版本号为 1.2.1，versionCode 为 4。

| 范围 | 已实现 |
| --- | --- |
| WebView 资源 | LRU 返回被移出的实际资源，统一从容器移除并销毁；释放加载/错误状态；内存压力下回收后台页面；渲染进程退出后销毁并重建当前页面 |
| 生命周期 | 初始化失败时不进入账号生命周期；页面退出取消回调；文件选择结果在所属页面恢复网络后交付；切换账号或销毁页面会取消旧回调 |
| 代理设置 | 串行应用并等待完成通知；快速切换忽略过期回调；取消 DIRECT 回退；校验协议、地址、端口，凭据与代理规则分离 |
| 页面网络 | 非活动 WebView 及已加载 Profile 的 Service Worker 阻止新网络加载；不同代理切换销毁旧页面；相同代理下最多缓存四个页面 |
| 代理测活 | 独立进程及 WebView 数据目录，访问 muse.ai；处理主框架 HTTP/网络错误；测活结束释放专用进程和连接缓存；普通保存及调试保存共用流程 |
| 配置保存 | 新增/修改非空代理测活成功才写入；测试锁定表单；输入变化或离开页面取消自动保存；先刷新数据再更新当前账号，返回主界面立即刷新 |
| Profile 删除 | 删除任务和账号记录原子写入；立即取消下载和页面访问；不能即时清理的 Profile 在冷启动加载前重试；区分完成与待清理提示 |
| 数据恢复 | 校验正整数及唯一 ID，修正 nextId 并保留孤立 Profile 编号；局部坏条目也留底并提示；保存失败显式报错；删除时清理旧备份避免复活账号 |
| 下载 | 公共资源保留系统下载；带会话/代理的下载使用 OkHttp 和固定账号配置；重定向按目标 URL 读取 Cookie，禁止 HTTPS 降级；取消与失败清理未完成文件 |
| 文本与界面 | 按字素簇截断头像/名称/标签，精确识别会话 Cookie；搜索合并刷新；修正 Lint tint 错误；已加载页面取消固定时长过渡遮罩 |
| 发布与隐私 | release 不再使用默认 debug 签名，支持环境变量正式签名；添加 CI、Gradle SHA-256 校验和 Android 12+ 备份/迁移排除规则；调试日志只输出 Cookie 名 |

执行验证：

```text
gradlew testDebugUnitTest lintDebug assembleDebug assembleRelease
58 tests, 0 failures
Lint: 0 errors, 59 warnings
Debug APK 与未签名 Release APK 构建成功
```

测试包括 LRU 资源释放、数据恢复和删除队列、代理队列时序及格式校验、Emoji 和 Cookie 识别；Robolectric 验证不支持多 Profile 时的完整 Activity 生命周期、代理保存成功/失败/编辑/离开页面、文件选择回调；本地 TLS MockWebServer 验证下载的跨主机 Cookie 处理、HTTP 失败、HTTPS 降级拒绝和取消请求。

Lint 剩余警告主要涉及 SDK/依赖版本、特性检查的跨方法分析、布局/无障碍、旧 API 和 JavaScript 设置，没有通过基线屏蔽错误。后续的 RecyclerView 迁移、进一步拆分 MainActivity、正式版本的 SDK 升级和可访问性改版属于优化工作，本次没有进行框架级改造。

当前边界：

- 尚无连接的 Android 真机，实际 WebView 多 Profile、代理认证、Service Worker/既有连接、测活进程启停及 Profile 冷启动清理仍需设备验证。单元测试不代表 Chromium 网络隔离测试。
- WebView 代理仍为进程级能力。本次收紧了后台请求及切换行为，但未实现每个账号独立网络进程，不能保证并发的严格独立出口。
- HTTP(S) 代理认证仅响应匹配主机的挑战，不支持的认证方式会失败；SOCKS5 账密需本地转接。账号下载支持 HTTP 代理和无认证 SOCKS5；HTTPS 上游代理、blob 下载目前明确拒绝，不会静默直连。
- 账号下载需保持应用运行，不具备后台进程被杀后的断点恢复；已完成的下载文件不会因账号删除自动移除。
- 部分 Profile 删除受平台限制，需应用进程重新启动后完成。删除时会清空旧滚动备份和损坏留底，防止敏感配置恢复；这也意味着删除操作后不再保留此前损坏数据的人工恢复副本。
- 代理凭据仍在应用私有存储中，尚未迁移到 Keystore 加密。本 fork 使用独立且固定的发布签名，与上游版或 debug 版签名不同，首次安装可能需要先卸载旧包；卸载会清除本机账号数据。

本地未配置签名时产物为 `app/build/outputs/apk/debug/app-debug.apk` 和 `app/build/outputs/apk/release/app-release-unsigned.apk`。GitHub Actions 配置了本 fork 的持久发布签名，发布产物为 `MuseLauncher-v1.2.1.apk`，同时提供 SHA-256 校验文件；只有测试、Lint、构建及签名验证通过才发布。签名配置和使用行为见 README。
