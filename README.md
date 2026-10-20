# 轻影 · 本地视频播放器

原生 Android 播放器，Kotlin + Jetpack Compose，**零第三方 UI 库、零 DI 框架**。
只做一件事：把你手机里本地的视频，按文件夹列出来、舒服地播掉。

- 版本 **v1.5.0**（`versionCode = 6`，`versionName = "1.5.0"`，tag `v1.5.0`）
- 包名 `com.azhan.qingying` ｜ minSdk 26（Android 8.0）｜ targetSdk 37
- 23 个 Kotlin 文件、约 4900 行
- release APK 约 **2.2 MB**

---

## 一、功能清单

### 1. 添加目录 → 按文件夹名建列表

- 通过系统文件选择器（SAF）选择目录，**不需要申请任何存储权限**
- 列表分组名 = 文件夹名；一个目录 = 一个分组（点分组头展开 / 收起，默认折叠）
- 递归扫描子目录（最深 8 层，最多 3000 个文件），跳过隐藏目录与隐藏文件
- 支持格式（21 种，见 `VideoScanner.VIDEO_EXTENSIONS`）：**mp4 / m4v / mkv / webm / avi /
  mov / flv / ts / m2ts / mts / 3gp / 3g2 / wmv / asf / rmvb / rm / mpg / mpeg / vob /
  ogv / f4v**（能否真播以设备解码器为准）
- 组内按**文件名自然序**排列：`第2集` 排在 `第10集` 前面，不是字典序
- 重复添加同一目录不会建第二条记录，直接重扫
- 重扫会保留已探测的时长与分辨率，不会每次都重来一遍
- 展开分组时后台补时长 / 分辨率（分批落盘），**不放在扫描阶段** ——
  上千个视频逐个开 `MediaMetadataRetriever` 会让「添加目录」卡住
- 顶部「继续观看」按最近播放时间倒序，只列看到一半的（超过 5 秒且没到末尾 15 秒）
- **文件搜索**：顶栏放大镜进入，跨全部已添加文件夹按「文件名 / 子目录」匹配
  （忽略大小写），命中的那段在标题里高亮；每条结果标出所属文件夹，
  点开仍以该文件夹为播放队列，所以照样自动连播下一集
  - 不匹配文件夹名 —— 搜的是**列表里的文件**，不是分组
  - 纯内存过滤（上限 3000 个文件），不加防抖，敲一个字就出结果
  - 搜索时按系统返回键先退出搜索，不会直接退出应用
- 缩略图取视频第 1 秒的帧（第 0 帧常是黑场），内存 LRU 缓存，不落盘

### 2. 后台播放音频

播放器（ExoPlayer）跑在 `MediaSessionService` 里，**不是**跑在 Activity 里。
所以切后台、息屏、甚至 Activity 被销毁，解码都不会断。

更进一步：离开播放页或切后台时，会**摘掉 Surface 并禁用视频轨道**
（`TrackSelectionParameters.Builder.setTrackTypeDisabled(C.TRACK_TYPE_VIDEO, true)`），
ExoPlayer 只解音频 —— 既省电，也不会出现「Surface 被销毁了播放器还往上画」的崩溃。
回到前台 / 回到播放页时自动恢复画面，播放位置不会断。

- media3 自动挂前台通知（播放 / 暂停 / 上一集 / 下一集），不需要手写 Notification。
  **刻意不申请** Android 13+ 的通知权限 —— 该权限只决定通知「是否可见」，
  不决定前台服务能否运行，所以不影响后台播放，只是通知栏里没有控件。
  既然不影响功能，就不弹那个权限框；想要通知控件的用户在系统设置里手动打开即可
- 音频焦点、拔耳机自动暂停（`ExoPlayer.Builder.setHandleAudioBecomingNoisy(true)`）、
  `setWakeMode(C.WAKE_MODE_LOCAL)` 都已开
- 从最近任务划掉 App 时：**正在播就继续播**，没在播才收工
- 首页底部常驻一条迷你播放条，标注当前是「正在播放」还是「后台播放音频中」

### 3. 倒计时退出

播放页右上角随时可开，三种起算方式：

| 方式 | 行为 |
|---|---|
| **预设档位** | 5 / 10 / 15 / 30 / 45 / 60 / 90 / 120 分钟，一格一档 |
| **自定义** | 输入任意分钟数（1–600），回车或点「开始」即生效 |
| **播完当前视频后** | 跟着当前视频走，播完即触发（区分「自然播完」与「手动选集」） |

到点后的动作可选：

- **退出应用**（默认）—— 先暂停播放，再 `finishAndRemoveTask()` 连最近任务一起关掉，
  不会留下「退出了但还在后台响」的进程
- **仅暂停** —— 只停声音，界面留在原地

计时器挂在 Application 级作用域上，切后台、息屏、离开播放页都照常走。
顶栏胶囊实时显示剩余 `mm:ss`，一眼可见。

### 4. 片段循环（A-B 重复播放）

想反复看同一段（练听力、对动作、听副歌）时，不用手动拖进度条。

- 播放页控件里三个小键：**设 A** / **设 B** / **清除**。播到起点点 A、到终点点 B，
  两点齐了立刻开始循环
- 只点「设 B」也行 —— A 默认取 0，也就是「从开头循环到这里」
- 进度条下面会多出一条 **A-B 区间条**，标出正在循环的那一段；顶栏胶囊显示「循环中」
- 顶栏的 **A-B 胶囊**打开面板：两个时间点（点一下跳过去）、循环开关、清除。
  竖屏横屏都能用 —— 横屏没地方塞标记键，就从面板里设
- 循环只对**当前视频**有效，切到下一集自动清除；A/B 不写入播放记录
- 实现是 50ms 轮询位置、越过 B 点就 `seekTo(A)`：ExoPlayer 没有「到达某位置」的回调，
  而循环区间是临时状态，不值得为它重建 `ClippingConfiguration`。代价是每圈可能
  过冲几十毫秒
- B 点距结尾不足 300ms 时自动往前让一点：否则播放器先走到 `STATE_ENDED` 自动切下一集，
  循环会断在半路上

### 5. 播放

- 整张列表当播放队列，自动连播；上一集 / 下一集 / 快进快退 15 秒
- 进度条可拖动，倍速 0.5x–3x（7 档），默认倍速可在设置里改
- **播放进度记忆**：按视频单独记，退出、换设备都能续播（设置里可关自动续播）
- 进度回写有防抖（停顿 800ms 才落盘），播放中每 5 秒回写一次
- 画面按视频真实宽高比做 letterbox，处理了非方形像素（老 DVD 转码）与旋转元数据
- 解不了的文件给出明确错误（含错误码名），不会静默黑屏
- **横竖屏都支持**：竖屏竖排控件；横屏自动切**沉浸式**（隐藏状态栏/导航栏）+ 单行紧凑控件。
  横屏纵向只有 300–400dp，照竖屏堆四行控件会把画面挤掉一大半，所以横屏是独立布局
- **手动横屏切换**：进度条右侧（横屏时在右下角）一个全屏键，直接给 Activity 设
  `requestedOrientation` —— 系统「自动旋转」关着也能一键进/出横屏。
  离开播放页会把方向交还系统，不会把列表页也锁在横屏
- **音轨解不了时自动静音续播**：AVI 里封 MP2 音轨这类「画面正常、音频解不了」的文件，
  不再整片报错 —— 自动关掉音频轨、回到原位置继续放画面，并提示一次「已静音播放」。
  只对当前视频生效，切到别的视频会把音频轨重新打开
- **错误提示分类**：区分「音频解码失败 / 视频解码失败 / 容器不支持 / 文件读不到」，
  每种给出对应建议（转码 / 重新扫描 / 重启），技术细节折叠在下面，不占主视线

### 6. 防熄屏

需求本身是矛盾的：看视频时不能灭屏，只听声音时又必须允许灭屏（否则亮一整夜）。

分界线放在「**播放页是否在前台**」：

| 状态 | 行为 |
|---|---|
| 播放页在前台 | 加 `FLAG_KEEP_SCREEN_ON`，屏幕常亮 |
| 切后台 / 离开播放页 / 息屏 | 清掉该 flag，交还系统正常熄屏 |

后台时画面已经摘掉、视频轨道也关了（见上一节），剩下的就是纯音频，
本来就不需要屏幕陪着亮。用 Window flag 实现，**不需要 WAKE_LOCK 权限**。

### 7. 外观

- 拟态（Neumorphism）风格，与同系列项目一致
- 外壳（列表 / 设置）**默认浅色**，可在设置里切深色，选择会被记住
- **播放页恒为近黑**，不受上面那项影响 ——
  内容页不该被导航页的配色污染，画面边缘也不该出现一圈亮框
- 启动窗口背景跟随浅色外壳（`windowBackground = #EDEFF3`），避免启动瞬间闪一下深色
- 自适应图标同样按浅色配色重绘（圆盘 `#EDEFF3` / 月牙 `#B9C0CE` / 三角 `#4E62E0`）

---

## 二、工程结构

```
video/
├── settings.gradle.kts / build.gradle.kts / gradle.properties
├── gradle/libs.versions.toml           # 版本集中管理
├── gradlew / gradlew.bat / gradle/wrapper/
├── .github/workflows/build-apk.yml     # tag 触发构建 + 挂 Release
├── fdroid/                             # F-Droid 收录材料（需提交到 fdroiddata）
└── app/src/main/
    ├── AndroidManifest.xml
    ├── res/                            # 主题 + 自适应图标（纯矢量，无二进制图）
    └── java/com/azhan/qingying/
        ├── QingYingApp.kt              # 手写依赖容器（唯一的"注入"处）
        ├── MainActivity.kt             # 收口倒计时退出信号
        ├── data/
        │   ├── Models.kt               # VideoItem / VideoFolder / PlayProgress / AppPrefs
        │   ├── VideoStore.kt           # DataStore + JSON（folders / progress / prefs 三个 key）
        │   ├── VideoScanner.kt         # SAF 递归扫描 + 时长分辨率探测
        │   ├── LibraryRepository.kt    # 分组管理 / 扫描编排 / 播放进度
        │   └── ThumbnailLoader.kt      # 缩略图取帧 + 内存 LRU
        ├── player/
        │   ├── PlaybackService.kt      # MediaSessionService（后台播放的载体）
        │   ├── PlaybackController.kt   # MediaController 封装 → Compose state
        │   └── SleepTimer.kt           # 倒计时退出
        ├── ui/
        │   ├── AppRoot.kt              # 导航（3 个页面）+ 按页换主题
        │   ├── theme/                  # 三套配色（深色外壳 / 浅色外壳 / 播放页）
        │   ├── neu/Neumorphic.kt       # 拟态设计系统
        │   ├── common/Thumbnails.kt    # 异步缩略图
        │   ├── home/HomeScreen.kt      # 分组列表 + 继续观看 + 迷你播放条
        │   ├── player/
        │   │   ├── PlayerScreen.kt     # 画面 / 横竖屏控件 / 生命周期 / 防熄屏
        │   │   ├── SegmentLoopPanel.kt # 片段循环面板（A/B 点、循环开关）
        │   │   └── SleepTimerPanel.kt  # 倒计时面板（预设 + 自定义）
        │   └── settings/SettingsScreen.kt
        └── util/
            ├── NaturalOrder.kt         # 文件名自然序
            ├── ContextExt.kt           # Context → Activity
            └── Formatters.kt           # 时长 / 体积 / 日期格式化
```

---

## 三、构建

### 环境要求

- JDK 17+（AGP 9.4.1 要求）
- Android SDK：`platforms;android-37.0`（**注意**：Android 17 起平台包名带 minor 版本，
  写成 `android-37` 会找不到包）、`build-tools;36.0.0`
  （**不是 37.0.0** —— AGP 9.4.1 在 `compileSdk = 37` 下默认取 build-tools **36.0.0**；
  实测本机只装了 36.0.0 即可构建。老版本这里写的是 `build-tools;37.0.0`，是错的）
- media3 1.11 要求 `minCompileSdk >= 36`，所以 `compileSdk` 不能低于 36

### 命令行

```bash
cd video   # 仓库根目录

export JAVA_HOME="/path/to/jdk-17"          # JDK 17+（AGP 9.4.1 要求）
export ANDROID_HOME="/path/to/android-sdk"
export PATH="$JAVA_HOME/bin:$PATH"

./gradlew :app:assembleRelease   # 2.3 MB，装机用
./gradlew :app:assembleDebug     # 23 MB，含完整符号，便于看崩溃堆栈
```

> 上面两个路径换成你自己的 JDK / SDK 安装位置，或在 `local.properties` 里写
> `sdk.dir=/path/to/Android/sdk`。`local.properties` 不入库。

产物：

| 包 | 路径 | 体积 |
|---|---|---|
| release（推荐） | `app/build/outputs/apk/release/QingYing-1.5.0-release.apk` | **2.2 MB** |
| debug（便于看崩溃堆栈） | `app/build/outputs/apk/debug/QingYing-1.5.0-debug.apk` | 23 MB |

装到手机：

```bash
adb install -r app/build/outputs/apk/release/QingYing-1.5.0-release.apk
```

### 签名

release 包用 `keystore/qingying-release.jks` 签名：

- alias：`qingying`
- store / key 密码：`qingying2026`

**这是自签名测试证书，个人自用够用；要上架应用商店请换成你自己的 keystore 并妥善保管**
（丢了就没法覆盖升级）。

> 签名材料**不入库**：`.gitignore` 已忽略 `keystore/`、`keystore.properties`、
> `local.properties`、`build/`、`app/build/`、`*.apk`。

### GitHub Actions 自动编译

仓库自带 `.github/workflows/build-apk.yml`：**打 tag 才触发**（也可在 Actions 页手动触发）。

```bash
git tag v1.5.0          # 需以 v 开头，建议与 versionName 一致
git push origin v1.5.0  # 推上去即触发编译
```

产物有两个去处：

- **GitHub Release**（仅 tag 构建）：自动创建/更新对应 tag 的 Release，并把 APK
  作为附件挂上去，文件名形如 `QingYing-v1.2.0.apk`
  （**附件名必须纯 ASCII**：GitHub 会删掉 Release 资源名里的非 ASCII 字符）
- **Artifact**（tag 与手动触发都有）：名为 `qingying-release-apk`，保留 90 天

#### 签名：必须配 `KEYSTORE_BASE64`，否则 CI 直接失败

**这是唯一一条硬性前置条件。** 仓库 `Settings → Secrets and variables → Actions`
必须配一个 Secret：

```bash
# 名称：KEYSTORE_BASE64
# 值：下面这条命令的输出（一整行 base64）
base64 -i keystore/qingying-release.jks
```

口令的默认值与本地 `keystore.properties` 一致，所以**只需要配这一个 Secret**。
想更严的话把 `KEYSTORE_PASSWORD` / `KEY_ALIAS` / `KEY_PASSWORD` 也配成 Secret。

没配会怎样：**`Prepare signing key` 这一步直接 `exit 1`，CI 变红。**

为什么不「零配置兜底」用临时 debug keystore 签名？因为那种失败是**静默**的：

| 场景 | 结果 |
|---|---|
| 本地构建包 → 覆盖装本地构建包 | ✅ 同一把钥匙 |
| **GitHub 包 → 覆盖装本地构建包** | ❌ `INSTALL_FAILED_UPDATE_INCOMPATIBLE` |
| **GitHub 包 → 覆盖装上一次 GitHub 包** | ❌ 同样失败（每次 CI 都新生成一把 debug key） |
| F-Droid 包 → 覆盖装以上任一个 | ❌ 又是另一把钥匙 |

用 debug 兜底时，CI 是绿的、Release 有附件、包也能装到**空设备**上 ——
只有「升级」这个场景才暴露。宁可让 CI 红。

工作流里还加了一步 `Verify APK signature`，把 `Signer #1` 的证书指纹打进日志。
本地用同一条命令对照：

```bash
apksigner verify --print-certs app/build/outputs/apk/release/QingYing-1.2.0-release.apk
```

两边的 SHA-256 必须一致（本项目本地是 `3fa551ff…17ec`），否则那个包无法覆盖安装。

> ⚠️ **`.jks` 必须离线备份。** 丢了就永远无法再发能覆盖升级的新版本 ——
> 只能改包名重来。这是唯一一个「丢了就不可逆」的文件。

**已修好并实测确认。** 修复前 CI 出的包是 `CN=Android Debug`（每次还不一样），
配好 Secret 后重新发布的包已变成 `CN=QingYing`，SHA-256 `3fa551ff…17ec`，
与本地构建**完全一致**。下载回来验过：v2 签名通过、包名版本号正确、
不带 `application-debuggable`，是正经 release 包。

> 只装过 `CN=Android Debug` 那个版本的话，换包前需要先卸载一次。
> 此后的 GitHub 包与本地包同签名，可以直接覆盖升级。

---

## 四、几个关键设计

### 1. 播放器为什么必须放在 Service 里

「后台播放音频」这件事，放在 Activity 里做不到 —— 用户一切后台，Activity 随时可能被
系统回收，解码线程跟着没了。所以 ExoPlayer 建在 `MediaSessionService` 里，
UI 通过 `MediaController` 遥控（`SessionToken` + 同进程绑定）。

`MediaController.setVideoSurfaceView()` 会把 Surface 一路转发到 Service 里的播放器
（media3 1.11 的 `MediaSessionStub.setVideoSurfaceWithSize` / `onSurfaceSizeChanged`
就是干这个的），所以画面归 UI 管、解码归 Service 管，两边不打架。

### 2. 后台时为什么要关掉视频轨道

只摘 Surface 不够。ExoPlayer 在 Surface 为空时仍会走视频解码链路，
屏幕关了、人在听声音，这些解码帧全白解。

所以切后台时额外做一件事：
`player.trackSelectionParameters.buildUpon().setTrackTypeDisabled(C.TRACK_TYPE_VIDEO, true).build()`
（注意 `setTrackTypeDisabled` 在 **Builder** 上，`TrackSelectionParameters` 自身只有
`disabledTrackTypes` 字段 —— 写成 `TrackSelectionParameters.setTrackTypeDisabled(...)` 是编不过的）。
之后 ExoPlayer 只保留音频轨，功耗立刻降下来，播放位置不受影响。
回前台再打开 —— 视频轨道会重新被选中，画面接着播。

> 副作用：极少数设备上切换轨道会有一瞬间的画面重建（黑一帧），属正常现象。

### 3. 「本片结束后」怎么区分自然播完和手动选集

`Player.Listener.onMediaItemTransition(mediaItem, reason)` 的 `reason` 会告诉你原因：

- `MEDIA_ITEM_TRANSITION_REASON_AUTO` = 上一个播完了自动切下一个 → 该触发倒计时
- `MEDIA_ITEM_TRANSITION_REASON_SEEK` = 用户手动点了别的 → 不该触发

只看「有没有切歌」是不够的，手动选集也会切。所以 `SleepTimer` 只认 AUTO。

### 4. 退出信号用 Channel 而不是布尔状态

倒计时到点 → 暂停播放 → 通知 Activity `finishAndRemoveTask()`。

如果用一个 `exitRequested: Boolean` 状态来传，Activity 重建后会**再次**读到 true，
于是「重开 App 又立刻退出」。改成 `Channel(CONFLATED)` 后事件只被消费一次，不会重复触发。

### 5. 进度为什么按视频单独存，而不是一个「上次看到哪」

一个分组里几十集，进度混在一起就没用了。`PlayProgress` 以视频 id（document uri）为键，
和 `folders` 分开存 —— 播放进度是高频写入（每 5 秒一次），跟视频清单挤在同一个 key 里，
每次回写都要把整个清单重新序列化一遍。

### 6. 数据只回流一次

`LibraryRepository` 启动时从 DataStore `first()` 装载一次，之后本进程就是唯一写入方。

不用 `collect` 持续回流：DataStore 的写是异步的，乐观更新之后回流可能先送来旧值，
把刚写进去的清单盖回去 —— 扫描、补时长这类「连续多次写」的场景下尤其明显。

### 7. 拟态 UI 的物理约束

元素与背景同色，只靠「左上受光 + 右下背光」两个方向的阴影塑造体积。
所以每套主题必须**同时**给出 highlight / shadow 两色 —— 只给一个 elevation 做不出来。

- 外阴影画在背景色**之下**，内阴影画在背景色**之上**，顺序写反就看不见
- 拟态元素自带 `padding(elevation)` 预留阴影空间，占位比视觉面积大一圈是这套风格的固有代价
- 硬件加速下 `setShadowLayer` 对非文本绘制从 **API 28** 起才生效，
  更低版本会退化成扁平卡片（功能不受影响）

---

## 五、已知限制

- **不支持画中画（PiP）**。需求里没提，加进来会让 Activity 的生命周期与
  Surface 归属复杂一大截，不值得。
- **不联网、不做在线播放**。这是本地播放器，输入只有 SAF 授权目录。
- **零运行时权限**：目录走 SAF，防熄屏走 Window flag，都不需要权限。
  Android 13+ 不申请通知权限，代价是通知栏里没有播放控件（后台播放本身不受影响）。
- **格式支持取决于设备解码器**。ExoPlayer 自带 mp4 / mkv / webm 的软解与封装支持，
  但 rmvb、部分 HEVC 10bit、带 DRM 的文件能否播取决于厂商解码器。
  播不了会给分类后的错误提示，不是静默黑屏。
  - **AVI 的音轨是常见坑**：里面常封 MPEG-1 Layer II（MP2），MIME 同样是 `audio/mpeg`，
    系统能力检查报「支持」但真解码时失败（`ERROR_CODE_DECODING_FAILED`）。
    v1.5.0 起这种情况自动降级为静音播放，画面照常
  - **RMVB 是真的不支持**：ExoPlayer 没有 RealMedia 提取器，手机也基本没有 RealVideo 解码器，
    静音降级也救不回来，只能转码（`ffmpeg -i in.rmvb -c:v libx264 -c:a aac out.mp4`）
- **时长 / 分辨率探测是逐个文件做的**，超大目录首次展开会看到「正在读取时长…」停留一会儿。
- **缩略图不落盘**，只做内存缓存（16 MB LRU）；每次冷启动都要重新取帧。
- **扫描上限 3000 个文件 / 8 层深度**，超出部分不收录（`VideoScanner` 里的常量）。
- 应用**不跟随系统深色模式**，外壳配色由 App 内设置控制。
- **片段循环不是逐帧精确的**：靠 50ms 轮询位置再 `seekTo`，每圈可能过冲几十毫秒；
  片段本身也不落盘，切到别的视频就清除（同一次观看里退出播放页再回来仍然保留）。
- **循环期间「播完当前视频后」的倒计时不会触发** —— 视频永远播不完，这是语义上的必然。
- **手动横屏依赖 `requestedOrientation`**：平板、折叠屏、分屏模式下 Android 12+
  可能忽略这个请求（系统优先大屏/多窗口布局），此时按键不会生效。

---

## 六、验证状态

**已验证：**

- `assembleDebug` / `assembleRelease` 均构建通过，Kotlin 编译**零警告**，
  `lintVitalRelease` 通过
- release APK 经 `apksigner` 校验签名通过（CN=QingYing）
- 依赖链路核实：`MediaController.setVideoSurfaceView` 与
  `MediaSessionStub.setVideoSurfaceWithSize` 在 media3 1.11.1 中确实存在（javap 核过），
  即「UI 挂 Surface → Service 里的播放器渲染」这条链路成立
- 进度条拖动链路：`NeuSlider` 已接上 `onValueChangeFinished`，
  「拖动中」的临时值会在抬手时提交给 `seekTo`（此前这个回调漏接，导致拖了没反应）
- CI 签名两条路径都验证过：本地模拟（抽出 `Prepare signing key` 脚本执行）
  未配 `KEYSTORE_BASE64` → 退出码 1、不写任何 env；
  **以及真实 CI 产物**：配好 Secret 后重打 tag，从 GitHub Release 下载附件验签，
  签名 `CN=QingYing`、指纹 `3fa551ff…17ec`，与本地构建一致
- **v1.3.0（片段循环）**：`./gradlew :app:assembleRelease` 通过，
  含 `lintVitalRelease` 与 R8 压缩；产物 `QingYing-1.3.0-release.apk` 2.2 MB。
  该次构建在**没有签名密钥**的机器上完成，`apksigner verify` 报
  `Missing META-INF/MANIFEST.MF`（未签名），与 F-Droid 路径的预期一致
- **v1.3.0 的 CI 产物**：推 `v1.3.0` 后 `Build Release APK` 跑通，Release 附件
  `QingYing-v1.3.0.apk`（2.2 MB）验签为 `CN=QingYing`、SHA-256 `3fa551ff…17ec` ——
  与本地构建同一把钥匙，可以直接覆盖升级
- **v1.4.0（手动横屏）**：本地 `assembleRelease` 通过（含 `lintVitalRelease` / R8），
  CI Run 7 成功；Release 附件 `QingYing-v1.4.0.apk`（2.2 MB）验签同样是
  `CN=QingYing` / `3fa551ff…17ec`
- **v1.5.0（音轨降级）**：本地 `assembleRelease` 通过（含 `lintVitalRelease` / R8），
  CI Run 8 成功；Release 附件 `QingYing-v1.5.0.apk`（2.2 MB）验签为
  `CN=QingYing` / `3fa551ff…17ec`。降级判定依赖 media3 报错文本里的渲染器类名
  （`MediaCodecAudioRenderer`），属于实现细节耦合 —— 见下方「未验证」，
  没有真实 MP2 样本时不能算验证完成

**未验证：**

- **整机运行** —— 尚未在真机或模拟器上做过功能与视觉复核
- 各种封装格式的实际解码表现（尤其是 mkv 内封字幕、HEVC、RMVB）
- 后台播放在不同厂商 ROM 上的保活表现（国产 ROM 对前台服务有额外限制）
- 横屏沉浸式在挖孔屏 / 手势导航设备上的实际表现
- **片段循环的听感**：每圈回跳时是否有一帧黑场、长片段连续循环几十圈后是否累积漂移
- 片段循环的 50ms 轮询在低端机上的额外功耗（相对正常 200ms 轮询）
- **手动横屏在平板 / 折叠屏 / 分屏模式下的表现**（这些场景系统可能忽略方向请求）
- **音轨降级在真实文件上的表现**：本地没有 MP2/AC3 音轨的 AVI 样本，
  判定逻辑（渲染器类名匹配）与「降级后能否继续播」都还没在真机复核
- 错误分类对 `ERROR_CODE_*` 的映射是否覆盖了实际遇到的所有失败

建议装机后重点确认这几处：

- **音轨降级**：找一个音轨是 MP2 的 AVI → 应自动静音播放并提示一次，画面正常；
  切到下一集后声音应恢复（不能一直是静音）
- **错误分类**：放一个 RMVB → 应提示「容器不支持」而不是笼统的「编码不支持」
- **手动横屏**：竖屏点全屏键 → 应立刻转横屏、播放位置不断；横屏点退出 → 回竖屏；
  返回列表页不应还卡在横屏
- **片段循环**：播到中间点「设 A」，再播几秒点「设 B」→ 应立刻跳回 A 继续播；
  进度条下方应出现区间条；点「循环关」应停止回跳但保留 A/B；切下一集后 A/B 应清空
- **进度条**：按住拖动、松手 → 应跳到目标位置；单击轨道 → 也应跳过去
- **横竖屏**：播放中旋转 → 画面与播放位置都不能断；横屏应自动隐藏系统栏
- **防熄屏**：播放页停住不动，超过系统熄屏时间 → 屏幕不应灭；
  按 Home 键切后台 → 屏幕应能正常熄灭
- **倒计时**：预设档与自定义（如 3 分钟）都要能起算；
  到点应停声并退出到桌面，「仅暂停」档应只停声不退
- **后台播放**：播放中按 Home 键 → 声音应继续；息屏后声音应继续
- **画面比例**：竖屏视频（手机拍的）不应被拉扁
- **拟态阴影观感**：深色外壳下对比度天然偏弱，看是否够用

---

## 七、许可

本项目采用 **[Apache License 2.0](LICENSE)** 授权。

可自由使用、修改、分发（包括商用），需保留版权声明与许可副本。选择 Apache-2.0 而不是
GPL 系，是因为它与本项目依赖（AndroidX / Media3 / kotlinx.serialization）的许可一致，
且不要求衍生作品开源。

### 关于 F-Droid

收录材料在 [`fdroid/`](fdroid/README.md)，完整步骤见
[`fdroid/README.md`](fdroid/README.md)。要点：

1. **构建在无签名密钥时也能通过** —— 已**实测**。
   `app/build.gradle.kts` 在既没有 `KEYSTORE_PATH` 环境变量、也没有仓库内
   `keystore.properties` 时，会产出**未签名**的 release 包而不是构建失败，
   F-Droid 拿去用自己的密钥重新签名。
   实测方式：从 `v1.2.0` 这个 tag 克隆一份干净检出（没有密钥、没有 `local.properties`），
   跑 `:app:assembleRelease` —— 构建通过，产物 `QingYing-1.2.0-release.apk`，
   `apksigner verify` 报 `Missing META-INF/MANIFEST.MF`，即未签名。
2. **提交构建配方**：见
   [`fdroid/metadata/com.azhan.qingying.yml`](fdroid/metadata/com.azhan.qingying.yml)。
   把它复制到 `gitlab.com/fdroid/fdroiddata` 的 `metadata/` 目录
   （文件名必须是 `com.azhan.qingying.yml`），然后提 MR。
   配方里 **必须写 `output`** —— 本项目把产物名改成了 `QingYing-<versionName>-release.apk`，
   不是默认的 `app-release-unsigned.apk`。
3. **每次发版必须递增 `versionCode`**：F-Droid 靠 `versionCode` 判断新旧，
   一直停在 `1` 会导致新版本无法被识别。

注意：F-Droid 版与本地 / CI 版**签名不同**，用户切换时需要先卸载旧版。
另外本项目用 AGP 9.4.1 + `compileSdk = 37`，F-Droid 构建服务器能否装到对应 SDK
需要在 MR 评审时确认 —— **元数据里没有任何字段能指定 JDK 或 SDK 版本**（查过文档，确实没有），
这一点我无法在本地验证。

---

## 八、文档与实现一致性

README 里凡**能被代码证伪**的说法，都要能核对 —— 因为这类错**没人会替你发现**：
编译、lint、单测都不管「文档里写的函数名到底存不存在」。
照着错的写，第一行就编不过，或者更糟：**看着没问题，实际没生效**。

v1.0.0 发布前做了一次核对，下面记录**改了什么、为什么错、以及哪些查不到**。
（这一节是 v1.0.0 那次的历史记录，保留原文不随后续版本改动。）

### 本次修正的 4 处不一致

| 位置 | 原来写的（错） | 实际实现 | 为什么错 |
|---|---|---|---|
| 环境要求 | 旧写法：`build-tools;37.0.0` | AGP 9.4.1 在 `compileSdk = 37` 下默认取 **36.0.0** | 凭「compileSdk 37 ⇒ build-tools 37」想当然。实测本机只装 36.0.0 即可构建，AGP 自己的 lint 模型里也写着 `build-tools/36.0.0`。CI 里跟着装 37.0.0 是多余的 |
| 关键设计 2 | 旧写法：`TrackSelectionParameters.setTrackTypeDisabled(...)` | 该方法在 **`TrackSelectionParameters.Builder`** 上；`TrackSelectionParameters` 自身只有 `disabledTrackTypes` 字段 | **不存在的 API** —— 照抄的人第一行就编不过 |
| 功能清单 2 | 旧写法：`handleAudioBecomingNoisy` | `ExoPlayer.Builder.setHandleAudioBecomingNoisy(true)` | 少了 `set` 前缀，源码里 grep 不到 |
| 功能清单 1 | 旧写法：只列了 17 种扩展名 + 「等」 | `VideoScanner.VIDEO_EXTENSIONS` 是 **21 种** | 一个「等」字掩盖了 4 种漏列（3g2 / asf / rm / mpeg） |

### 核对方式

把 README 里**反引号包住的标识符**与**结构树里的路径**全抄成清单，逐个去源码里 grep：
「找不到」的再人工判断是**真错**、还是**库符号**（第三方类名在项目源码里当然找不到）。

本次清单共 80 余个标识符，命中 4 处真错（上表），其余为库符号、Gradle 任务名、
GitHub Secrets 名或「讨论旧写法」的语境。

### 这次查不到的（写在这里，免得下一个人重复尝试）

- **库符号**：`MediaSessionStub.setVideoSurfaceWithSize`、`onSurfaceSizeChanged`
  在项目源码里 grep 不到，但它们**真实存在**于 media3 1.11.1 —— 这类只能靠 `javap` 核
  （本次核过，结论：存在）。
- **抽象原则与权衡**：无法被证伪，不纳入核对。
- **「讨论旧写法」的标识符**：如 `exitRequested: Boolean` 出现在「为什么**不**用布尔状态」一节。

> **判据：说「某个东西不存在 / 不要用某个写法」时，必须把否定词写出来。**
> 否则核对脚本会（正确地）把它当成正向陈述，报出误报 ——
> 光挂一个 `⚠️` 符号不算数，豁免要基于**行里说了什么**，不是**行上挂了什么**。

### 一句话总结

**文档里的每一个具体名字，都要能被指到代码里的同一处。**
指不到就删掉或标注「尚未落地」—— 宁可少写，不要写错。
