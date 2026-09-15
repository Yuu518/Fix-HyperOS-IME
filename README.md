# HyperOS 全面屏键盘优化

使用 **libxposed API 102**，为 LSPosed 作用域中选中的第三方输入法接入 HyperOS 系统底部栏。

## 使用

1. 安装模块 APK，在支持 API 102 的 LSPosed 中启用模块。
2. 在模块作用域勾选需要适配的输入法。微信输入法和 Gboard 是推荐项，也可以手动选择其他输入法。
3. 同时勾选 **剪贴板和常用语（`com.miui.phrase`）**，用于系统剪贴板读取。
4. 重启所选输入法和“剪贴板和常用语”，或者重启手机。
5. 系统设置中启用全面屏手势和全面屏键盘优化，在任意应用的输入框中验证效果。

模块没有独立界面、桌面入口或 LSPosed 设置页，仅通过 LSPosed 启用并选择作用域。修改作用域或更新模块后，需要重启对应输入法进程。

无需勾选系统框架。若需要从小爱或搜狗小米版的左下角面板切换到第三方输入法，也勾选对应的原生输入法；对原生输入法仅补全切换列表，保留它原本的支持判断、布局和颜色行为。

## 实现范围

- 在被勾选的输入法进程内，放行 `InputMethodServiceInjector` 的输入法支持判断。
- 在 `InputMethodModuleManager.loadDex()` 后处理动态加载的 `InputMethodBottomManager`，放行第二层支持判断。
- 保留系统对优化开关、手势导航、横屏、悬浮和设备姿态的判断。
- 系统底部栏实际显示时，在键盘输入区域分发的 `WindowInsets` 和该输入法窗口的根 inset 读取中去掉导航栏底部 inset，保留顶部、侧边和其他 inset 类型。直接读取系统底部栏及其子视图的根 inset 保持原值；底部栏隐藏后恢复原始行为。
- 输入法切换面板使用系统返回的已启用输入法列表，不使用小米定制输入法白名单过滤。
- 第三方输入法显示底部栏时，从键盘与底部栏接缝上方 1 像素高的区域取样，在内存中缩至 24 个像素，用中位颜色调用系统着色接口，并按亮度调整按钮及手势条。绘制时至多每 750 ms 取样一次，颜色未变不重设背景；不保存取样图片。
- 保留切换输入法、剪贴板和无功能设置。厂商专用语音、语言与键盘类型操作回退到左侧“切换输入法”、右侧“剪贴板”，不改写全局设置。
- 所选输入法向系统剪贴板组件注册进程 Binder；组件只为已注册且 UID、包名、当前默认输入法服务都匹配的调用开放 `query` 和 `getType` 检查。进程死亡后撤销注册，每次显示键盘会重新注册。
- 不伪造包名，不全局 Hook PackageManager，不改写剪贴板历史，不放宽写入及签名检查。不会读取或记录输入文本与剪贴板内容。

## 兼容边界

- 最低 Android 14；需要含小米输入法底部栏实现的 HyperOS 国行系统，以及 `com.miui.phrase`。
- 开发依据：HyperOS `OS4.0.0.39.XPBCNXM` / Android 17，剪贴板和常用语 `5.7.2`（10272）。
- 第三方输入法若自行绘制底部留白、不通过 WindowInsets 计算布局，仍可能需要单独适配；不通过强制固定键盘高度处理。
- 不模拟搜狗、小爱专用广播协议。图片、渐变皮肤使用接缝的代表色，不能将完整图案延伸到底部栏；取色不可用时保留系统颜色。
- 其他版本的类或方法签名不匹配时记录错误并保留原行为。Provider 权限方法存在多个候选时拒绝放行。
- 不启用热重载。更新模块后需重启目标进程。

## 构建

### GitHub Actions

工作流位于 `.github/workflows/build.yml`，在 push、pull request 或手动运行时构建。

在 GitHub 的 **Actions → Build APK → Run workflow** 启动任务，完成后从 Artifacts 下载 `HyperOS-IME-release`，其中包含使用固定密钥签名并验证通过的 Release APK，可直接安装。仅上传这一份产物。

CI 使用 JDK 21、SDK 35 和项目 Gradle Wrapper，运行单元测试和 Android Lint，仅打包 Release APK，禁用 Gradle 缓存复用，构建结束后清理生成缓存。Artifact 保留 14 天；持续升级请使用相同密钥签名的 Release APK。

首次运行前，在仓库 **Settings → Secrets and variables → Actions** 配置：

| Secret | 内容 |
| --- | --- |
| `ANDROID_KEYSTORE_BASE64` | JKS 或 PKCS12 密钥库文件的 Base64 内容 |
| `ANDROID_KEYSTORE_PASSWORD` | 密钥库密码 |
| `ANDROID_KEY_ALIAS` | 签名密钥别名 |
| `ANDROID_KEY_PASSWORD` | 签名密钥密码 |

push 和手动运行会签名并上传 Release；缺少上述 Secrets 时明确失败。Pull request 运行检查和编译验证，不读取签名 Secrets，也不上传产物。临时密钥在任务结束时删除，不上传到 Artifact。

已有密钥应继续复用。若还没有密钥，可在本机用 JDK 创建并妥善备份，按提示输入密码：

```sh
keytool -genkeypair -keystore hyperos-ime-release.jks -alias hyperos-ime -keyalg RSA -keysize 3072 -validity 10000
```

Windows 可将密钥库的 Base64 内容复制到剪贴板，再粘贴进 `ANDROID_KEYSTORE_BASE64`：

```powershell
[Convert]::ToBase64String([IO.File]::ReadAllBytes((Resolve-Path ./hyperos-ime-release.jks))) | Set-Clipboard
```

密钥文件不要提交到仓库。使用不同于当前已安装 APK 的签名时，Android 不允许直接覆盖安装。签名命令使用 Android SDK 的 [apksigner](https://developer.android.com/tools/apksigner)。

### 本地构建

需要 JDK 17 或更高版本、Android SDK 35 和 Build Tools 35.0.0。

```powershell
./gradlew.bat :app:assembleDebug :app:testDebugUnitTest :app:lintDebug
```

Linux/macOS 使用 `./gradlew`。SDK 路径通过 `local.properties` 或 `ANDROID_HOME` 指定。

调试 APK 输出到 `app/build/outputs/apk/debug/`。发布构建 `:app:assembleRelease` 默认未签名，应使用自己的签名密钥。

## 诊断

LSPosed 日志标签为 `HyperOSIME`，记录 Hook 安装、注册结果和窗口尺寸，不记录输入内容。

测试应覆盖：微信输入法、Gboard、输入法切换面板、系统剪贴板、横屏、悬浮键盘、优化开关关闭、目标进程重启，以及取消作用域后的行为。

本仓库的 `analysis/` 是设备静态分析与验证材料，`.tools/` 是本地构建工具，均不纳入版本控制或 APK。

本次实机验证结果见 [VALIDATION.md](VALIDATION.md)。
