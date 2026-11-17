Muse Launcher 项目分析与优化建议

审查日期：2026-11-17。以下为提交 `563cda4` 的修复前审查记录；后续修复范围与验证见 [FIXES.md](FIXES.md)。

项目的轻量化方向合理，优先投入应当是 WebView 资源释放、代理行为、账号删除和生命周期正确性。现在存在会直接影响核心承诺的缺陷，先修这些，比增加新功能或迁移框架更有价值。

本次检查了全部 Java 生产代码、4 个测试类、Manifest、Gradle 配置、布局和样式、README，并查看了仓库中的界面截图。业务代码未修改。下文的源文件行号均对应本次审查版本。

验证情况：使用 JDK 17、已安装 Android SDK 的 android.jar，以及项目声明版本的 JUnit 4.13.2 和 org.json 20231013，直接编译并运行现有 JVM 测试，30/30 通过。这不等同于 Android Gradle 构建通过；完整 Gradle 单测/Lint 因 Gradle 8.10.2 下载超时未完成，本机也缺少项目指定的 Android 34 平台。目前没有连接的 Android 设备，因此没有真机性能、代理出口或 UI 自动化测试结论。临时复现代码和下载的参考源码存放在被 Git 忽略的 `.gradle/review/`。

项目结构和已有优势

| 部分 | 现状 | 评价 |
| --- | --- | --- |
| 应用层 | Java 17、AppCompat、两个 Activity，最低 Android 10 | 规模小，容易构建和部署，适合继续保持简单 |
| 网页会话 | 每个账号绑定独立 WebView Profile | 账号站点数据隔离的方向正确；隔离创建失败时会销毁 WebView，没有静默退到共享 Profile |
| 网络 | ProxyHelper 统一调用全局 ProxyController | 接口集中，但需要补上异步结果和隔离边界 |
| 存储 | SharedPreferences + JSON + 上一版备份 | 对小规模账号列表足够；KeyValueStore 可注入，已有单测基础 |
| 资源管理 | 泛型 LruAccountRegistry，目标上限 4 | 思路合理，当前调用契约导致实际资源未释放 |
| 安全边界 | 配置页不导出、release 禁用调试 extras、禁用明文站点流量和常规备份 | 已有有效的基础保护 |
| 可测试性 | JSON、存储、LRU、启动参数都有 JVM 测试 | 可以低成本补回归，但当前没有覆盖 Activity 与资源的协作 |

优先修复的问题

1. **P1：LRU 驱逐没有释放 WebView，实际驻留数量可以持续增长。已通过最小 Java 复现验证。**

   位置：`LruAccountRegistry.java:53`、`MainActivity.java:608`。

   `touchAndEvict()` 在返回淘汰 ID 前已经 `map.remove(eldest)`；MainActivity 随后又 `webViews.remove(evictedId)`，得到 null，因而不会执行容器移除和 `destroy()`。旧 WebView 仍由 `webContainer` 持有，却已经不在登记表中。再次切回账号还会创建另一个 WebView。

   最小复现沿用当前调用顺序，以普通对象模拟容器持有关系，创建 20 个资源后得到：`registry=4, attached=20, destroyed=0`。这是调用契约复现，不是真机内存测量。除了内存和后台请求问题，失去登记的 WebView 也会让崩溃恢复、删除和状态回调更难正确处理。

   建议让驱逐返回实际资源，例如 `List<Evicted<T>>`，或由明确的回调接收并销毁被淘汰值。销毁逻辑统一处理容器、加载状态、错误状态、计时器和页面实例。增加“登记数量、容器子项数、销毁次数一致”的协作测试，不能只检查 Map 的容量。

2. **P1：不支持 MULTI_PROFILE 的设备会进入未初始化的生命周期路径。源码可直接确认。**

   位置：`MainActivity.java:123`、`:525`、`:550`。

   能力检查失败后，onCreate 弹出更新对话框并 return，store 和 drawerLayout 尚未初始化。Activity 仍会进入 onResume，而其中直接调用 `store.reload()`。返回键逻辑也直接访问 drawerLayout。因此“提示用户更新 WebView”的分支存在空指针崩溃。

   建议引入明确的初始化成功状态，所有依赖账号界面的生命周期入口先检查状态；也可把不支持提示放到单独的入口界面。验收应模拟特性不支持，确认提示能稳定显示、跳转更新和退出。

3. **P1：当前实现不能保证不同账号始终使用各自配置的出口。源码和平台契约确认，实际流量需真机验证。**

   位置：`ProxyHelper.java:26`、`MainActivity.java:589`、`AccountConfigActivity.java:188`。

   ProxyController 对应用内所有 WebView 生效。切换账号只隐藏旧页面并调用 onPause，Android 的 onPause 不会暂停 JavaScript，所以后台页面仍可能发起请求。代理变更会影响这些页面；测活创建的临时 WebView 也共享同一全局代理。

   此外，apply 忽略 setProxyOverride/clearProxyOverride 的完成回调，调用方马上 loadUrl、reload 或恢复页面。官方接口要求等待回调后再加载，因此首次请求或快速切换存在使用旧配置的窗口。

   建议先建立统一的代理协调器：串行应用配置、返回成功/失败、以切换序号忽略过期回调，并在配置生效后才允许目标页加载。需要严格不同出口时，应采用有明确网络边界的方案，例如不同代理上下文放到独立进程，或采用经验证支持按 Profile 配置代理的平台能力。销毁不同出口的非活动页面只能作为收紧行为的步骤，还需要验证 Service Worker 和已有连接，不能把隐藏/onPause 当成网络隔离。

   这一判断依据项目使用的 [AndroidX WebKit 1.12.1 官方源码包](https://dl.google.com/dl/android/maven2/androidx/webkit/webkit/1.12.1/webkit-1.12.1-sources.jar) 中 ProxyController 的作用域和回调约定，以及 [AOSP WebView 源码](https://raw.githubusercontent.com/aosp-mirror/platform_frameworks_base/master/core/java/android/webkit/WebView.java) 对 onPause 的说明。

4. **P1：代理失败可以回退直连，设置错误被静默吞掉。源码确认。**

   位置：`ProxyHelper.java:36`。

   每个非空代理配置都追加 `.addDirect()`，代理无法连接时允许尝试直连。用户即使填写了代理，也可能实际使用系统网络出口。异常被空 catch 吞掉后，调用方继续加载，无法区分新代理应用成功、仍是旧代理或没有覆盖。这也会造成测活假阳性。

   建议非空代理默认失败即报错，允许直连必须成为明确选择；apply 返回结果，不支持或格式错误不能当作成功。错误信息仅保留协议、主机和端口，避免包含代理密码。addDirect 的回退含义可在上述 1.12.1 源码包的 ProxyConfig 中核对。

5. **P1：保存和测活没有形成可靠的同一条校验流程。源码确认。**

   位置：`AccountConfigActivity.java:144`、`:176`、`:196`、`:276`。

   普通保存直接 `persist(); finish();`，没有测活或代理格式校验，README 中“测活通过才保存”只在特定调试自动保存路径部分成立。测活把任意 onPageFinished 视为成功，没有检查主框架 HTTP 错误、最终目标和预期响应。测活期间代理输入仍可编辑，成功后的 persist 读取的是当时输入框内容，可能保存一个没有测试过的地址。测活结束也未立即恢复先前代理，而是等配置页销毁。

   建议使用同一状态流程：规范化输入 → 检查格式 → 应用代理并等待回调 → 测试 → 保存该次测试的不可变配置。输入变化使之前的成功结果失效；失败和取消清空 pendingSave；成功、失败、超时和离开页面都走统一清理与恢复。要支持离线保存，则明确更改产品规则和文档。仅修改名称、头像和标签时不应无故要求重新验证未变化的代理。

   探测到本地端口只能说明存在监听进程，不能证明它是 Clash 或所猜测的代理协议。测活最好区分“代理可建立连接”和“目标站点可访问”；gstatic 的成功并不能证明 muse.ai 可用。出口 IP 检查可作为用户主动触发的诊断，不能把一个外部检查站点的结果当成全部访问都隔离的证明。

6. **P1：删除账号可能只删除列表记录，Profile 删除失败后仍提示成功。源码和所用库版本契约确认。**

   位置：`MainActivity.java:649`。

   当前先 getProfile、清 Cookie/Storage、deleteProfile，再销毁 WebView。AndroidX 1.12.1 的 ProfileStore.deleteProfile 文档明确限制存在活跃 WebView 或已经载入内存的 Profile；当前顺序触及这些限制。异常被忽略，最后仍移除账号并显示“删除成功”。清 Cookie/部分存储不等于确认整个 Profile 已删除。

   不能只把 destroy 移到前面就认定问题解决，因为本版本还限制已载入的 Profile。建议持久化待删除列表，先阻止新访问并清理页面；当前进程无法删除的 Profile，在下一次冷启动、任何账号 Profile 被读取之前执行删除并检查结果。删除中的账号必须排除在登录探测和页面创建之外。需要记录删除失败以便重试，避免留下无法访问和管理的数据。

   账号删除后，`AccountsStore.save()` 还会把删除前列表存进 list_prev，其中可能包括代理账密。若承诺删除敏感配置，备份和损坏留底也需要明确的删除策略。Profile 的限制已经对照 [1.12.1 源码包](https://dl.google.com/dl/android/maven2/androidx/webkit/webkit/1.12.1/webkit-1.12.1-sources.jar) 中 ProfileStore.java 核实。

7. **P1：账号 ID 恢复缺少不变量校验，可能把两个逻辑账号指向同一个 Profile。已最小复现。**

   位置：`AccountsStore.java:67`、`:117`，`AccountJsonCodec.java:54`。

   nextId 直接读取偏好，缺失时回到 1，没有与已有账号最大 ID 比较；解码也没有拒绝重复 ID 或非法 ID。让 list 含 id=1，同时缺少 nextId，再添加账号，得到两个 id=1。Profile 名称完全由 id 决定，所以这会影响隔离。

   建议加载时校验 ID 为正、唯一；nextId 至少大于所有已知有效账号 ID，并处理溢出和已有 Profile 的关联。恢复异常要隔离并提示，不能静默重新关联账号与旧 Profile。正常的原子保存已经降低了发生概率，此问题主要在损坏、恢复或未来导入路径触发。

8. **P2：局部损坏会静默丢条目，滚动备份不能长期保留原始内容。已最小复现。**

   位置：`AccountJsonCodec.java:65`、`AccountsStore.java:46`、`:82`。

   合法 JSON 数组中的坏条目会被直接跳过，整个解析仍算成功，因此没有 loadNotice，也不会写 list_corrupt。复现中，一条好记录和一条坏记录加载后连续保存两次，坏条目从主记录和上一版备份中均消失，未保留原始内容。这与 README 的“不静默丢失”不一致，现有 partialCorruption_noNotice 测试反而固定了这一行为。

   建议解码返回有效账号、坏条目及错误信息；局部损坏也留底并提示。备份要区分“语法可解析”和“语义完整”，避免把残缺列表滚成唯一备份。存储保存返回结果，避免异常后 UI 仍提示成功。

9. **P2：下载没有接入当前账号的会话与代理策略。源码确认，具体下载行为需真机验证。**

   位置：`MainActivity.java:459`。

   DownloadManager 请求只添加 User-Agent，没有读取当前 Profile 对该下载 URL 的 Cookie，也没有对账号代理做处理。系统下载不属于 ProxyController 管理的 WebView 请求，因此需要登录或只能通过指定代理访问的文件不能保证正常下载。文件名、Content-Disposition 和 MIME 信息也没有充分利用；blob URL 不能直接按普通网络链接下载。

   建议明确下载模式：公共 HTTP(S) 资源可以继续用系统管理器；账号资源通过受控会话传递，Cookie 只用于匹配的目标和安全的重定向策略；需要账号代理的下载由能使用该代理的网络客户端执行。对 blob 提供受约束的导出路径或明确提示，不能直接扩展一个对任意网页开放的文件写入桥。

10. **P2：带认证代理的支持没有贯穿真实页面。源码确认，协议兼容性待验证。**

    位置：`ProxyHelper.java:35`、`AccountConfigActivity.java:209`、`:225`，`MainActivity.java:351`。

    代理 URL 原样传给 addProxyRule，而 1.12.1 文档给出的格式为 scheme/host/port，没有给出 URL 内嵌凭据的保证。认证回调只在测活 WebView 中实现，账号 WebView 没有同样的处理。测试凭据解析用字符串切割，没有规范化 percent-encoding，也没有校验认证挑战的 host/realm。

    建议把代理地址与凭据分开建模，验证所承诺的 HTTP、HTTPS、SOCKS5 认证能力；不把普通站点认证挑战自动当作代理挑战。仅增加账号页面的同名回调不足以证明所有代理协议都可用。测试和实际页面必须使用一致的、经过端到端验证的认证机制。

11. **P2：页面销毁和异步任务清理不完整，返回配置页后 UI 也可能保留旧数据。源码确认。**

    位置：`MainActivity.java:506`、`:525`、`:679`；MainActivity 未实现 onDestroy；`AccountConfigActivity.java:244`。

    MainActivity 没有统一销毁 WebView、取消 mainHandler 探测和 splash 回调、结束文件选择回调。配置页的端口探测线程完成后也没有检查 Activity 是否已经销毁。旋转、系统重建和较慢异步结果会使这些缺陷更容易显现。

    配置页返回时，onActivityResult 先根据旧 store 渲染；随后 onResume reload，但只有登录状态变化才会再次 renderAccounts。若都是未登录账号，名称和头像修改可能不立即刷新。配置页保存代理后也没有一个明确的、确认应用完成后重新加载当前失败页面的流程。

    建议统一页面资源所有者及清理入口；给异步请求加生命周期和操作序号校验；先刷新存储再渲染。配置页通过 Activity Result API 返回变更字段或账号 ID，主页面按变更更新状态。

12. **P2：release 使用 debug 签名，发布身份不稳定。配置确认。**

    位置：`app/build.gradle:24`。

    正式包显式使用 signingConfigs.debug。不同机器的默认 debug keystore 通常不同，会影响后续覆盖安装。建议确定长期维护的正式签名和 CI 密钥来源，签名文件及密码不进仓库。已经分发的安装包若要切换签名，先明确现有用户升级和数据迁移路径，不能直接假定可覆盖更新。

性能和交互方面的后续优化

| 优化项 | 证据和影响 | 建议 |
| --- | --- | --- |
| 列表增量更新 | MainActivity:725 每次删除所有头像和列表行再创建；搜索每输入一个字符都会触发 | 改为 RecyclerView/ListAdapter + DiffUtil，选中状态和登录状态局部更新；大量账号时再根据测量增加搜索防抖 |
| 登录探测范围 | 每次页面结束/恢复都遍历所有账号；probeChanged 变 true 后后续分块持续全量渲染 | 优先刷新发生变化的账号；分块结束时清除变化标记，或合并一轮刷新；用耗时预算评估分块，不能假定 20 次 Cookie 读取必在一帧内 |
| 暂停时写盘 | MainActivity:540 在 UI 线程遍历全部 Profile 并 flush Cookie | 跟踪实际活跃且需要刷新状态的 Profile，减少无意义工作；遵守 API 线程要求并用实测评估阻塞 |
| 页面缓存策略 | 固定 4 个 WebView；没有主页面统一低内存回收策略 | 先修 LRU，再测低内存机型；允许较小容量，并在内存压力下释放后台页面。需要保留的 URL/草稿状态与 Profile 登录状态分别设计 |
| 人为切换延迟 | showSplashFor 对已加载页面仍覆盖约 450ms | 热切换可直接显示或短暂淡入；加载中的页面使用进度和可重试状态，减少固定等待 |
| HTTP 错误处理 | 主页面只处理 onReceivedError，没有主框架 onReceivedHttpError | 按产品需要区分断网、代理错误、HTTP 失败和站点返回内容，不把所有 onPageFinished 都等同成功 |
| 字符串截断 | 配置头像直接 substring(0,2)，名称/标签也按 UTF-16 截断 | 头像按用户感知字符边界截断。最小复现中 `A` 加一个 Emoji 被截成悬空代理字符；组合 Emoji 也应纳入用例 |
| 无障碍操作 | 动态编辑按钮 30dp，头像 40dp，头像没有账号名称/状态描述 | 扩大实际触摸范围，补充账号名、登录状态、选中状态语义，验证 TalkBack 和大字体 |
| 登录状态文案 | cookies.contains("hatch_sess=") 仅表示字符串存在；SPA 登录退出不一定触发页面完成 | 精确解析 Cookie 名和值；把本地会话存在与服务端仍认可区分开，采用未知/有会话/无会话状态，补充 SPA 更新时机 |
| 全屏体验 | 当前只隐藏侧栏，系统状态栏和导航栏保持显示 | 若产品希望真正沉浸，补充系统栏与手势 Insets 处理；否则在文案中明确是隐藏侧栏 |
| 表单和列表状态 | 动态创建的重命名输入、固定抽屉宽度、配置页未保存状态缺少完整恢复策略 | 先覆盖返回、进程重建、大字体和横屏场景，再决定是否调整布局；保持重命名草稿，避免刷新时丢失输入 |

可维护性、隐私与工程化

MainActivity 当前 1013 行，混合了 WebView、下载、文件选择、代理切换、账号操作、UI 组装、登录探测和调试逻辑。建议按资源所有权逐步拆分为会话管理、代理协调、下载/文件交互、列表展示四个部分。拆分时让页面状态和操作结果可测试，避免只是把原有方法搬进多个仍然相互访问内部字段的工具类。

AccountsStore.all() 暴露可变内部列表，Account 的字段也可直接修改，synchronized 只能保护方法执行期间，不能保护外部直接改字段。配置页和主页面还各持有自己的 store 快照。适合提供只读快照、按账号更新的明确接口及统一仓库实例；创建账号和继承代理合并成一次保存。当前规模下没有必要仅为“优化”迁移到大型数据库。

账号 ID 是 long，多个页面状态容器却把它强转为 int。短期通常不触发，但建议统一为 LongSparseArray 或按账号封装 SessionState，避免未来导入数据或 ID 增长造成状态碰撞。

代理密码目前包含在普通输入框和 JSON 中，list_prev/list_corrupt 也可能复制它。建议独立密码字段、显示/隐藏控制，按需求用 Android Keystore 保护本地凭据，并定义备份留底的保留与删除规则。调试 dumpAuthState 的 JS 分支把 document.cookie 原始值写入 logcat（MainActivity:283），与“只记录 cookie 名”不符；应仅保留名称和布尔状态。release 的调试入口限制已有，不应误报为 release 任意应用可改配置。

不应把 allowBackup=false 的声明直接等同于所有 Android 厂商和设备迁移路径均已验证。需要对应系统版本的数据提取规则和实际迁移测试，再给出文档承诺。文件上传、外部 URL 跳转和目标站点新增媒体能力也应有明确边界：外部协议按需求允许、提示无法处理的链接；若需要摄像头/麦克风，则设计运行时授权和按来源授权，不能直接放行任意 WebView 请求。

README 需要与实际行为一起维护：代理的全局作用域、是否允许失败直连、保存是否强制测活、删除何时完成、局部损坏处理及热切换延迟都不能超出实现。隐私声明中的“网络请求只发生于 muse.ai 与代理之间”遗漏了测活访问 gstatic；会话长期保存时，“无痕环境”也容易引起误解。当前没有发现需要凭空纠正 Muse 归属的依据，站点内容应随产品变化核查，不依赖旧知识判断。

仓库未包含 CI 配置，建议添加 debug/release 编译、JUnit 和 Android Lint 检查，并保留报告与构建产物。Gradle Wrapper 可固定分发文件校验和；依赖校验和升级可逐步纳入。release 的 R8 和资源压缩可以评估，但只有比较包体及验证 WebView/AndroidX 行为后才启用。SDK 和依赖升级也应伴随导航返回、边到边布局、文件选择及 Profile 能力回归，不以升级版本本身作为优化效果。

建议的实施顺序与验收

| 批次 | 范围 | 必须验证的结果 |
| --- | --- | --- |
| 第一批：稳定性和数据 | LRU、初始化失败路径、统一销毁、ID 校验、局部损坏留底、Profile 删除队列 | 超过容量后容器不继续增长；不支持能力时能正常提示；无重复 ID；坏数据留底；冷启动后确认待删除 Profile 不存在 |
| 第二批：代理链路 | 回调时序、严格失败策略、测活和保存、凭据、下载出口 | A→B→A 快速切换不会用过期结果；不可用代理不能误报成功；修改输入不能复用旧测试；后台请求及下载出口符合产品承诺 |
| 第三批：界面和性能 | 列表增量更新、登录探测、表单状态、Emoji、无障碍、热切换 | 10/50/200 账号下比较搜索和切换耗时；旧配置不残留；组合 Emoji 完整；大字体和 TalkBack 可操作 |
| 第四批：发布和维护 | 正式签名、CI、文档同步、依赖和体积评估 | 固定身份签名并明确旧版升级路径；构建/测试可复现；文档中的关键保证均有对应验证 |

优先补的回归用例包括：驱逐后资源恰好销毁一次；不支持 MULTI_PROFILE 的完整 Activity 生命周期；代理设置延迟和乱序回调；测活失败但直连可用；测活过程中编辑代理或离开页面；活跃及已加载 Profile 的延迟删除；nextId 缺失/过小及重复 ID；局部损坏连续保存；配置返回时无登录状态变化；切换账号期间文件选择结果返回；后台页面崩溃和内存压力。

已有 30 个 JVM 测试值得保留，但测试通过只能说明目前覆盖的 JSON、存储和 LRU 内部规则正常。此次最严重的 LRU 缺陷发生在两个组件的交界处，需要围绕实际结果增加协作测试；代理、Profile 和下载的承诺则需要 Android 设备或模拟器上的端到端验证。
