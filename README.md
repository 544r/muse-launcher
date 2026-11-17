# Muse Launcher · Muse 多账号启动器

<p align="center">
  <img src="docs/screenshots/main.png" width="270" alt="Muse Launcher 主界面">
</p>

**把 muse.ai 网页版变成一个真正的 Android 应用**：每个账号独立保存站点数据、按当前账号切换代理、支持全屏沉浸浏览。原生壳 + WebView，轻量无广告、无任何数据上报。

[![Release](https://img.shields.io/badge/下载-Releases-blue)](../../releases)
![Platform](https://img.shields.io/badge/Android-10%2B-green)
![WebView](https://img.shields.io/badge/System%20WebView-115%2B-orange)

---

## ✨ 为什么需要它

在桌面放一个 muse.ai 的「网页快捷方式」，点开是浏览器标签页：单账号、无隔离、切换登录要反复登出登入。

Muse Launcher 用 AndroidX WebView 的 **Multi-Profile** 能力，给每个账号一套**完全独立**的 Cookie / localStorage / 存储空间：

- 账号之间互不串数据，像一台手机开了 N 个互不相通的分身；
- 登录状态持久保留，切回来还是登录态；
- 每个账号可以保存不同代理配置；切换账号时等待代理生效后再加载页面。网络限制见下文。

## 📱 功能一览

| 功能 | 说明 |
|---|---|
| 🔐 多账号完全隔离 | 每账号独立 WebView Profile，Cookie / 存储 / 登录态互不相通 |
| 🌐 按账号切换代理 | 支持 `http` / `https` / 无认证 `socks5`；HTTP(S) 凭据按代理主机校验，设置失败不会回退直连 |
| 📡 代理测活 | 独立进程通过代理访问 muse.ai，10 秒请求超时；新增或修改的非空代理测活成功才保存 |
| 🔌 Clash 端口探测 | 自动探测本机 Clash 默认端口（7890 / 7891 / 7892），一键填入 |
| 🟢 登录状态识别 | 基于 muse.ai 登录会话 cookie 判断，匿名访问不会误判为已登录 |
| 🔍 搜索过滤 | 抽屉顶部搜索框，按**名称 + 标签**实时过滤列表和侧栏头像 |
| ⛶ 全屏沉浸 | 隐藏侧栏全屏浏览，左缘右滑或返回键唤回 |
| ⬇️ 下载 / 文件选择 | 公共资源走系统下载；账号下载使用其 Cookie 和 HTTP/SOCKS5 代理，文件上传接系统选择器 |
| 🕵️ UA 伪装 | 去除 WebView 标识，避免 muse.ai 误判为移动端而跳转应用商店 |
| 🧰 adb 自动化接口 | 提供一组 am start 参数，方便脚本批量管理账号 / 配置代理 |

## 🖼️ 界面预览

| 多账号抽屉（搜索过滤中） | 账号配置（头像 / 名称 / 代理 / 标签） | 全屏模式 |
|:---:|:---:|:---:|
| <img src="docs/screenshots/drawer.png" width="240"> | <img src="docs/screenshots/config.png" width="240"> | <img src="docs/screenshots/fullscreen.png" width="240"> |

- **左侧窄条**：账号头像一列，绿点表示该账号已登录，点击即切；
- **抽屉**：展开的账号列表，支持重命名、删除、进入配置；
- **配置页**：头像（1–2 字符或 Emoji，实时预览）、名称、代理、标签。

## 📦 安装

1. 前往 [**Releases**](../../releases) 页面下载最新 `MuseLauncher-vx.x.apk`；
2. 直接安装（需允许「安装未知来源应用」）；
3. 系统要求：
   - Android 10（API 29）及以上；
   - 系统 WebView ≥ 115（多账号隔离依赖；版本过旧时启动会提示先更新 WebView）。

## 🚀 快速上手

1. **添加账号**：点侧栏底部或抽屉里的 ➕，会新建一个独立存储环境并自动打开 muse.ai；
2. **登录**：在页面里正常登录即可，登录态会持久保留在该账号的隔离环境中；
3. **配置账号**：抽屉里点某账号的 ⚙️ 进入配置页，可设置头像、名称、代理、标签；
4. **切换账号**：点侧栏头像或抽屉行即切，切换时会先应用该账号的代理；
5. **删除账号**：抽屉里点 🗑️ 二次确认后删除，立即移除账号配置；若 Profile 当前已载入，登录数据会排队在应用下次冷启动时清理，界面会明确提示。

### 配置代理

在配置页的「代理」卡片中填入代理地址，支持以下格式：

```
127.0.0.1:7890
http://127.0.0.1:7890
http://用户名:密码@1.2.3.4:3129
socks5://127.0.0.1:1080
```

- 点 **探测**：检查本机常见端口（7890 / 7891 / 7892）是否监听并填入候选地址；端口开放并不能证明代理协议或服务类型，请继续测活；
- 点 **测活**：在独立服务进程和独立 WebView 数据目录中访问 muse.ai，不修改账号页面的代理；返回成功、HTTP/网络失败或超时。请求超时为 10 秒，含服务启动的总等待上限为 15 秒；
- **留空**表示跟随系统网络（系统开了 VPN/TUN 时即走 TUN）；
- 新增或修改非空代理时，保存会自动测活；测试期间锁定输入，成功后保存同一份配置。清空代理、仅修改名称/头像/标签不要求重新测活。离开配置页会取消尚未完成的测试和自动保存。
- HTTP(S) 代理凭据与地址分开传给 WebView，仅响应匹配代理主机的认证挑战；不支持的认证方式会失败，不会改走直连。SOCKS5 账密代理需要先经本机代理工具转接。

代理设置是 **WebView 进程级**能力。应用只在代理相同的账号之间缓存页面；切换不同代理会销毁原页面，并阻止非活动 Profile 的新网络加载。此措施不构成完整的多账号独立网络沙箱，不能承诺已有连接和所有后台行为都按 Profile 隔离。需要严格并发独立出口时，应使用独立应用/进程环境。

> 💡 提示：如果你手机上的 Clash 开着 VPN/TUN 模式，所有流量本来就会经过 Clash，各账号留空即可；独立代理适合「Clash 关闭/仅代理模式」或想让不同账号走不同出口 IP 的场景。

### 登录状态是怎么判断的？

绿点只认 muse.ai 的登录会话 cookie（`hatch_sess`）。匿名访问 muse.ai 也会落下一堆埋点与风控 cookie，但不会出现这个会话 cookie——所以新建的、只打开过页面的账号会正确显示「未登录」。判断会精确匹配非空 Cookie 名，在页面加载完成和回到前台时刷新。绿点只表示本地存在会话，不能保证服务端会话仍有效。

## 🤖 adb 自动化接口

所有能力都可通过 `am start` 参数驱动，方便配合脚本做批量运营（例如批量配置代理、批量建号删号）：

| 参数 | 类型 | 作用 |
|---|---|---|
| `--ez show_drawer true` | bool | 启动后展开账号抽屉 |
| `--el start_account <id>` | long | 启动后切换到指定账号 |
| `--ez fullscreen true/false` | bool | 进入 / 退出全屏模式 |
| `--el open_config <id>` | long | 打开指定账号的配置页 |
| `--es proxy_preset '<url>'` | string | 配置页预填代理地址 |
| `--ez proxy_autotest true` | bool | 预填后自动测活（结果只显示不保存） |
| `--ez proxy_save true` | bool | 测活通过后自动保存入库（失败不保存） |
| `--es search_query '<关键词>'` | string | 预填搜索过滤关键词 |
| `--ez add_account true` | bool | 新建一个账号 |
| `--el del_account <id>` | long | 移除账号并排队清理隔离数据 |
| `--ez dump_auth true` | bool | 诊断：logcat 输出各账号 cookie 名与 localStorage 键 |

示例：给账号 3 配置代理，测活通过才保存：

```bash
adb shell am start -n com.seekstars.muselauncher/.MainActivity \
  --el open_config 3 \
  --es proxy_preset 'http://user:pass@1.2.3.4:3129' \
  --ez proxy_save true
```

> ⚠️ 这组接口仅在 **debug 构建**中生效（release 构建出于安全考虑不响应任何 intent 参数），面向**个人设备自动化**设计，未做调用方校验。请勿在不可信的环境中使用。需要自动化能力时请自行用 `./gradlew assembleDebug` 构建安装。

## 🧩 实现原理

- **账号隔离**：每个账号使用 `acct_<id>` Profile。读取时校验 ID，保留已有 Profile 的编号；删除任务与账号元数据一起持久化，并在冷启动加载 Profile 之前重试清理；
- **页面按 LRU 驻留**：相同代理下最多缓存 4 个页面，淘汰时移除容器并销毁 WebView；不同代理间切换会重建页面。Profile 数据保留，页面内尚未保存的状态可能丢失。支持渲染进程异常处理和内存压力下回收后台页面；
- **代理时序**：进程级 ProxyController 通过串行队列应用设置，等待回调才加载目标页；快速切换时忽略过期操作的回调，代理不可用时不追加 DIRECT 回退；
- **UA 伪装**：移除 UA 中的 `; wv` 与 `Version/x.x` 标识，避免被 muse.ai 判定为 WebView 而重定向商店。

## ❓ 常见问题

**新建环境加载很慢或卡住？**
muse.ai 是 Meta 服务，国内网络直连不通。新建环境会**自动继承当前账号的代理**；如果继承不到（当前账号也没配代理），请先给某个账号配好可用代理再新建，或打开 Clash TUN 模式让全局流量可用。

**页面显示"加载失败"？**
说明该账号的网络路径当前不可用（断网 / 代理失效）。点 **重试** 重新加载；修复网络或代理后再点重试即可，无需删除重建。切换账号时，处于错误状态的页面也会自动重新加载。

**加载速度取决于什么？**
 muse.ai 是重前端单页应用，完整加载需要下载数 MB 资源。速度主要取决于网络路径：经代理（尤其海外代理）会比直连慢，属正常现象；应用侧按 LRU 缓存相同代理下最近 4 个账号的页面，已加载页面不再强制展示固定时长的遮罩。不同代理间切换需要重新加载。

## 🛠️ 本地构建

```bash
# 要求：JDK 17，Android SDK（compileSdk 34）
./gradlew testDebugUnitTest lintDebug
./gradlew assembleDebug     # 可安装测试包：app/build/outputs/apk/debug/
./gradlew assembleRelease   # 未配置正式签名时输出 unsigned APK
```

正式签名通过四个环境变量配置：`MUSE_KEYSTORE_FILE`、`MUSE_KEYSTORE_PASSWORD`、`MUSE_KEY_ALIAS`、`MUSE_KEY_PASSWORD`。缺少签名时 release 不再使用 debug 密钥。已有安装包升级需要兼容的签名；不要直接更换旧版发布密钥。

本 fork 使用独立且固定的发布签名。GitHub Actions 从仓库 Secrets（`MUSE_KEYSTORE_BASE64`、`MUSE_KEYSTORE_PASSWORD`、`MUSE_KEY_ALIAS`、`MUSE_KEY_PASSWORD`）读取密钥，运行单测和 Lint，构建并验证签名。`main` 分支构建的可安装包位于 `release-apk` artifact；推送与应用版本一致的 `v*` 标签会自动发布 APK 和 SHA-256 校验文件到 Releases。PR 只验证构建，不读取签名密钥。

如果手机已装其他签名的上游版或 debug 版，不能直接覆盖安装本 fork 的 release 包；卸载会清除本机账号和登录状态，请先记录必要配置。此后使用本 fork 同一签名的新版即可覆盖升级。

下载补充：需要 Cookie 或自定义代理的文件由应用使用固定账号配置下载，逐次重定向重新读取目标 URL 对应的 Cookie，并禁止降级到 HTTP。此类下载请保持应用运行，失败会清理未完成文件；删除账号会取消其下载。HTTPS 上游代理及 `blob:` 下载暂不支持，会明确提示，不会静默换成系统直连。下载文件保存在系统下载目录，已完成的文件不会因删除账号自动移除。

## 🔒 隐私

- 应用**不包含统计或上报 SDK**，使用 AndroidX 和 OkHttp 网络库；
- 网络请求包括 muse.ai 及网页引用资源、用户触发的下载链接、所配置的代理；测活也访问 muse.ai；
- 账号元数据（名称/头像/标签/代理）存于本机 SharedPreferences；登录数据存于各账号的本机 WebView Profile；
- **系统备份已禁用**，并为 Android 12+ 配置云备份和设备迁移排除规则；不同厂商的迁移行为仍需设备验证；
- 账号数据损坏时尝试从滚动备份恢复；局部坏条目、重复或非法 ID 也会留底并提示。删除账号会同时清除旧备份和损坏留底中的配置，避免恢复出已删除账号。代理凭据仍存于应用私有存储，请保护设备访问权限。

## ⚠️ 免责声明

本项目是面向 muse.ai 网页版的个人效率工具，与 Meta / muse.ai 官方无关。使用时请遵守目标站点的服务条款与当地法律法规。

## 📄 License

[MIT](LICENSE)

---

## 🔗 友情链接

- [LINUX DO](https://linux.do/) —— 真诚分享、友好讨论的技术社区，本项目的交流与反馈也发布于此

---

## 🔗 相关链接

- [Muse Launcher Windows 版](https://github.com/seekstars306/muse-launcher-windows) —— 对应的 Windows WPF + WebView2 多账号启动器

