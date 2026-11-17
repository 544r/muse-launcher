# v1.2.4 性能优化与对比

本轮先完成本地开发与验证，再统一纳入 v1.2.4 / versionCode 7，通过 GitHub Actions 构建并签名。对比基线为 v1.2.3，提交 `5ff328bf5bcdb6ee577cb20b2b514a6211b7184a`。历史版本结果见 [PERFORMANCE.md](PERFORMANCE.md)。

## 已确认的工作量变化

两侧账号列表改用 RecyclerView，复用屏幕附近的条目；账号名称、头像、标签使用不可变快照并异步计算差异。选中项和登录标记只更新对应账号，切换账号不再重建全部元数据。返回前台优先检查当前及屏幕中的账号，其他账号滚入屏幕后再检查。WebView 回调查询账号改用 ID 索引，验证页面身份时不再复制整个页面注册表。

同一 Robolectric 测试、300×600 像素窗口、实际账号条目布局的创建数量：

| 账号数 | v1.2.3 创建条目数 | 本轮首次创建条目数 |
| --- | ---: | ---: |
| 20 | 20 | 6 |
| 200 | 200 | 6 |
| 1000 | 1000 | 6 |

原始结果：`app/build/reports/performance/list-work.csv`。这只证明视图创建量下降，不是手机耗时、内存或帧率结果。少量账号与网页内部交互的收益需要另外测量。

已暂停的后台 WebView 不再重复暂停、停止加载和设置网络阻断；所有暂停操作成功后才记录状态，失败仍允许重试。恢复与销毁时移除记录。每账号 Profile、Cookie 持久化、代理切换的连接清理规则和最多保留 4 个页面的上限继续保留。

## 日常交互衔接

本轮按代码中的实际执行路径优化：

- 登录状态检查从动画帧回调移到主消息队列空闲时段。触摸按下、列表拖动/惯性滚动、抽屉动画期间不执行；交互结束或网页滚动停止后等待 120ms，再逐个检查，检查之间至少间隔 32ms。持续操作时登录小圆点可能稍晚更新，不影响账号实际登录状态。
- 首次创建账号页面、或需要更换代理时，等抽屉完全收起再开始创建/销毁页面及应用代理。已缓存且使用相同代理的页面直接切换；连续切换只保留最后一次待执行请求，退出前台时取消。
- 已缓存 WebView 用 `INVISIBLE` 隐藏，保留尺寸，降低热切换引起重新布局的机会；仍暂停并阻断后台网络。屏幕尺寸变化时隐藏页面仍可能参加测量，页面缓存上限继续为 4。
- 加载遮罩延迟 120ms 才出现，快速完成的加载不会闪现遮罩；首个页面内容提交可显示或加载完成时立即收起，取消原来的最短 150ms 显示时间。失败/退出前台/后续切换都会取消旧定时任务。
- 普通返回前台和文件选择返回时，复用已经读入的账号元数据；只有打开配置页后才重新读取、渲染。进程重建仍由 `onCreate` 完整读取，配置保存、取消和失败路径仍会触发重新读取。
- 移除根容器、抽屉外层、WebView 容器及空状态/错误页内部重复的纯色背景，保留负责可见区域的背景。

这些是执行时机、布局和绘制工作量的调整。120ms/32ms 为保守的调度取值，未通过手机调参得出；也不修改网页脚本、视频解码或系统刷新率。

## 构建和安装

在已配置 JDK 17、Android SDK 34 的 PowerShell 中运行：

```powershell
.\gradlew.bat --gradle-user-home .gradle --console=plain :app:testDebugUnitTest :app:lintDebug :app:assembleBenchmark :app:assembleRelease
python -m unittest discover -s scripts -p 'test_*.py'
```

`benchmark` 继承 release，关闭 debuggable，允许 shell 性能采样，使用本机调试签名；调试 Intent 自动化入口保持关闭。APK 位于 `app/build/outputs/apk/benchmark/app-benchmark.apk`，显示名为“Muse 性能测试”，包名 `com.seekstars.muselauncher.benchmark`。

正式版和测试版可以并存；测试版的数据独立，需要另行创建账号并登录。对比用旧版、优化版采用同一测试包名和同一签名，用 `install -r` 轮换覆盖测试版，保留测试账号、Cookie 和缓存；不会覆盖正式版。

开发期间生成的对比文件存放于忽略目录 `.gradle/performance/apks/`（不随源码或 Release 分发）：

- `Muse-perf-baseline-v1.2.3.apk`：从上述基线 `git archive` 导出，只添加相同的 benchmark 构建类型、profileable 清单与应用名称；版本后缀 `-perf-baseline`。
- `Muse-perf-optimized.apk`：开发时的本轮源码快照，版本后缀 `-perf-local`；精确来源以 `comparison.json` 为准。发布版本请从 Releases 获取，或重新构建当前源码。
- `comparison.json`：包名、版本、签名校验、APK SHA-256 和构建来源。

基线源码留在 `.gradle/performance/baseline-5ff328b/`，使用同一 JDK、SDK、Gradle 缓存构建。它不包含本轮自定义 Trace 标记，系统帧数据、调度和内存仍可对比。

```powershell
$adb = "$env:LOCALAPPDATA\Android\Sdk\platform-tools\adb.exe"
& $adb install -r .gradle/performance/apks/Muse-perf-baseline-v1.2.3.apk
# 完成旧版采样后，覆盖测试版再重复同一场景：
& $adb install -r .gradle/performance/apks/Muse-perf-optimized.apk
```

## 可选的真机采样

需要手机启用 USB 调试并授权电脑。先打开测试版，准备好同一网页、账号数量和内容，再开始采样：

```powershell
python scripts/perf_capture.py capture --label baseline-list-01 --seconds 30 --trace
python scripts/perf_capture.py capture --label optimized-list-01 --seconds 30 --trace
```

一次采样只重复一种操作，建议每个版本每种场景至少 3 次，条件允许时交替 A/B/A/B，降低缓存和温度随时间变化的影响：

| 场景 | 操作与判读 |
| --- | --- |
| 账号列表 | 固定账号数量，上下滑动、搜索、清空搜索；检查条目跳动和 p95/p99 |
| 热切换 | 同一代理下切换已经加载的 2–4 个账号；观察窗口帧和页面恢复 |
| 首次加载 / 超出缓存 | 与热切换分开记录；超过 4 个页面可能发生淘汰和重建 |
| 网页滚动 / 输入 | 固定同一页和内容，分别执行滚动、连续输入；重点看渲染线程与网页工作 |
| 视频 | 固定分辨率、播放位置和网络；窗口帧不能代表视频解码掉帧 |
| 冷启动 | 单独记录进程未运行时的启动；不要与普通回到前台混合 |

使用同一手机、选中的 WebView 提供程序、系统刷新率、亮度、电源模式、网络与页面内容；温度明显升高时停止对比并冷却。先预热页面再测热切换，冷启动则明确记录进程状态。不要为了跑分清除账号数据或强制提高刷新率。

脚本默认采样测试包，`--package com.seekstars.muselauncher` 可只读采样已安装正式版；多台设备需 `--serial`。采样只重置 gfxinfo 统计，不清除应用数据、不安装应用、不自动切换账号或停止进程。

每次输出到 `.gradle/performance/<时间>-<标签>/`，包含：

- 机型、Android / App 版本、实际 WebView 提供程序和显示配置；
- `frames.txt` 与 `summary.json`：有效窗口帧样本、p50/p95/p99、最长帧，以及有 deadline 字段时的超期帧数；
- 采样前后内存与热状态；
- 可选 Perfetto 跟踪与执行状态。设备不支持跟踪时，仍保留可用的窗口数据。

`gfxinfo framestats` 是有容量上限的窗口样本，不能代表整段 30 秒的全部帧，也不等于 WebView 合成器或视频 FPS。多窗口数据会合并，比较时保持同样的窗口操作。零有效样本表示无法判断，不能当作零掉帧。采样脚本及 Perfetto 命令还需要在实际手机上验证。

优化版的 Trace 标记：`Muse.createWebView`、`Muse.freezePages`、`Muse.switchAccount`、`Muse.activateAccount`、`Muse.checkSession`、`Muse.accountMetadata`、`Muse.filterAccounts`、`Muse.flushCookies`。这些标记记录对应方法中的同步工作，不覆盖抽屉等待、异步代理应用、页面加载或异步列表差异计算的总时长。结合系统时间线判读，不能直接用 `switchAccount` 区间当作完整切换延迟。

## 验收边界

本地 86 项 JVM / UI 测试通过，Debug、benchmark 与 release 构建通过。回归覆盖列表复用、回收后的账号点击、滚动位置、搜索、会话标记、存储失败回滚、交互结束后再执行辅助任务、连续切换/退后台取消旧请求、遮罩定时任务与现有账号隔离相关逻辑；另有 5 项采样脚本测试。这些不能代替真机 Profile / Cookie / 网络验证。测试与 Lint 输出以 `app/build/reports/` 为准。

本轮没有真机测量，因此没有 FPS 提升比例或“已经达到极限”的结论。改动通过代码分析和本地自动化检查验证，采样工具作为可选基础设施保留。正式 APK 通过本仓库的 GitHub Actions 发布。
