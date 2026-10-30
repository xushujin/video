# F-Droid 收录材料

这个目录是提交到 [fdroiddata](https://gitlab.com/fdroid/fdroiddata) 的元数据，
**不是**本项目运行所需的东西。不想上架 F-Droid 的话可以整个删掉。

## 目录结构

```
fdroid/metadata/
├── com.azhan.qingying.yml                     # 构建配置
└── com.azhan.qingying/en-US/
    ├── title.txt                              # 应用名
    ├── short_description.txt                  # 摘要（≤ 80 字符）
    ├── full_description.txt                   # 完整描述（≤ 4000 字符）
    └── changelogs/1.txt … 13.txt              # 每个 versionCode 一份更新日志
```

与 fdroiddata 仓库的结构一致，整个 `metadata/` 目录可以直接复制过去。

## 提交步骤

**1. Fork 并克隆 fdroiddata**

```bash
# 先在 GitLab 上 fork https://gitlab.com/fdroid/fdroiddata
git clone https://gitlab.com/<你的用户名>/fdroiddata.git
cd fdroiddata
git checkout -b com.azhan.qingying
```

**2. 复制元数据**

```bash
cp -r /path/to/video/fdroid/metadata/* metadata/
```

**3. 本地校验**（强烈建议，能提前发现大部分问题）

```bash
pip install fdroidserver
fdroid lint com.azhan.qingying
fdroid readmeta
```

**4. 提交并推送**

```bash
git add metadata/com.azhan.qingying.yml metadata/com.azhan.qingying
git commit -m "New app: com.azhan.qingying (轻影)"
git push origin com.azhan.qingying
```

**5. 在 GitLab 上开 Merge Request**

目标仓库选 `fdroid/fdroiddata`，分支 `master`。
F-Droid 的 CI 会自动跑构建检查，维护者会 review。

## 配方里几个「为什么这么写」

| 字段 | 值 | 原因 |
|---|---|---|
| `Categories` | `Multimedia` | F-Droid 的分类是固定列表，**没有 "Video" 这一项**；播放器归 Multimedia |
| `gradle: yes` + 无 `subdir` | — | Gradle 工程根就在仓库根目录（`settings.gradle.kts` 在根），不需要 `subdir` |
| 无 `scandelete` | — | 项目没有测试源集（`app/src` 下只有 `main`），没有需要剔除的二进制 |
| **`output`** | `app/build/outputs/apk/release/QingYing-*-release.apk` | **这条是本项目的特殊之处**：`app/build.gradle.kts` 用 `androidComponents` 把产物名改成了 `QingYing-<versionName>-release.apk`，不是默认的 `app-release-unsigned.apk`。不写 `output`，F-Droid 可能找不到产物。用 glob 是为了升版本号时不用改配方 |
| `UpdateCheckMode` | `Tags ^v[\d.]+$` | 文档允许在 `Tags` 后加一个空格分隔的正则；限定 `v` 开头，以后打 `hotfix` / `beta` 这类 tag 不会被误判成新版本 |
| `UpdateCheckData` | `app/build.gradle.kts\|versionCode\s*=\s*(\d+)\|.\|versionName\s*=\s*"(.*)"` | 让 fdroidserver 直接从构建脚本读版本号，不依赖它对 Gradle DSL 的自动解析。其中 `.` 表示「用同一份文件」，是文档的约定写法 |

> 上面这些字段在
> [Build Metadata Reference](https://f-droid.org/docs/Build_Metadata_Reference/)
> 里都有明文定义，没有凭印象填的东西。

## 还缺什么

| 项 | 说明 |
|---|---|
| **应用图标** | 从 `app/src/main/res/` 导出 512×512 PNG，放到 `en-US/images/icon.png` |
| **截图** | 建议 3–5 张（分组列表、播放页竖屏、播放页横屏、倒计时面板、设置页），放 `en-US/images/phoneScreenshots/1.png` 等 |
| **作者名** | `.yml` 里现在填的是 GitHub 用户名 `xushujin`，想用真名自行替换 |
| **每个新 versionCode 一份 changelog** | 否则 F-Droid 会用空白的更新说明（目前 `1.txt`–`13.txt` 已就位，对应 versionCode 1–13） |

截图需要装机后自己截 —— 这是唯一我没法代劳的部分。

## 几个要注意的点

**签名是分开的。** F-Droid 用它自己的密钥签名，与 GitHub Release 里的 APK 是两套签名，
**不能互相升级**。用户从 GitHub Release 换到 F-Droid 版本必须先卸载。

**每次发版必须递增 `versionCode`。** F-Droid 靠 `versionCode` 判断新旧，
一直停在 `1` 会导致新版本无法被识别。

**自动更新已配好。** `.yml` 里设了 `AutoUpdateMode: Version` 与 `UpdateCheckMode: Tags`，
以后打 tag 推送后，F-Droid 会自动检测到新版本并构建，不需要再提 MR。

**应用自带的更新入口在 F-Droid 版上是关闭的。** 代码按安装来源识别 `org.fdroid.fdroid`，
命中就提示改用 F-Droid 更新 —— 两边签名不同，装上去必然 `INSTALL_FAILED_UPDATE_INCOMPATIBLE`。
如果评审时 F-Droid 要求彻底移除自带更新（部分维护者持这个立场），把「关于」卡片里那块
UI 去掉即可，`UpdateController.kt` 可以整文件删除，不影响其他功能。

**构建环境是个未知数。** 本项目用 AGP 9.4.1 + `compileSdk = 37`，要求 JDK 17 与
`platforms;android-37.0`。F-Droid 的构建服务器是否装了对应版本，**我无法在本地确认** ——
元数据里也没有任何字段可以指定 JDK 或 SDK 版本（查过文档，确实没有）。
若构建服务器不支持，只能在 MR 评审时与 F-Droid 维护者沟通。

**首次审核有周期。** 提 MR 后要等维护者 review 并跑通构建，通常几天到几周；
收录之后的版本更新会快很多。

## 当前状态

| 前置条件 | 状态 |
|---|---|
| 许可证 Apache-2.0 | ✅ `LICENSE` 已在仓库根目录 |
| 零运行时权限 | ✅ 不申请任何权限；通知权限只声明不申请 |
| 无专有依赖 | ✅ 全是 AndroidX / Media3 / Kotlin |
| 无签名材料时可从源码构建 | ✅ **已实测**：`v1.2.0` 从 tag 克隆干净检出（无密钥、无 `local.properties`）构建通过；`v1.3.0`、`v1.5.0` 也在无密钥环境跑通 `:app:assembleRelease`。产物均未签名，`apksigner verify` 报 `Missing META-INF/MANIFEST.MF`（F-Droid 可直接重签） |
| git tag | ✅ `v1.5.0` |
| 元数据字段合法性 | ⚪ 未跑 `fdroid lint`（本机没装 fdroidserver）；已逐字段对照 [Build Metadata Reference](https://f-droid.org/docs/Build_Metadata_Reference/)，无自造字段 |
| 应用图标 / 截图 | ❌ 缺 —— 见上文「还缺什么」，需要装机后自己截 |
| 构建服务器支持 AGP 9.4.1 / compileSdk 37 | ⚪ 未验证，见上文 |

**代码侧的前置条件都已满足，只差提交 MR 与补截图。**
