# Muse Android

面向 Muse.ai 的非官方 Android 客户端。支持 Android 8.0 及以上，与 Windows 客户端独立运行。

## 功能

- 多账号、独立网页登录、头像与套餐额度同步。
- 可拖动、贴边收起的悬浮账号头像及快速切号菜单。
- HTTP / HTTPS / SOCKS5 代理池与本机账号路由分流。
- 图片 / 视频创作工作室、参考图片、独立会话、进度和本机历史。
- 深色 / 浅色外观、可调整的提示词输入框和媒体预览保存。
- 凭据使用 Android Keystore 保护，账号、历史和生成文件保存在设备本机。
- 密码保护的本地账号包：双向迁移 Cookie、静态代理与绑定关系，导入默认新建。

## 账号与代理迁移

在侧边栏切到“账号”，选择“导出账号与代理…”并勾选账号，设置并确认迁移密码后保存 `.muse-account` 文件。
目标端在账号页点“导入…”，选择“加密账号包”，输入密码、检查账号及代理预览，再确认新建；另一选项“Cookie文本”进入原文本导入流程。密码只用于本次操作，不写入配置或日志；不要在聊天中提供密码、Cookie 或代理认证。

账号与绑定的真实上游代理一起加密；共享代理在包内保留引用关系，不按名称或仅地址覆盖本机节点。
系统网络、本机/局域地址、Cookie 属性降级和旁路策略差异会在预览说明，地址不会自动改写。
指定代理、独立 profile 或 Cookie 写入失败时不激活账号，不改为直连。所有代理按静态配置处理。

Android WebView 需要支持独立 profile 才能导入。带属性导出另外检测 Cookie 信息 API；不支持时明确标为名值降级，未知有效期不会被估算或延长。仅按已声明的 Muse URL 读取，不承诺完整浏览器存储迁移。
旧“导入 Cookie 文本…”每次处理一个账号的请求头，需要另选代理；它不包含原代理或完整属性，批量迁移请使用加密账号包。
本地导入完成后仍需在网页核对真实账号与工作区，不能把写入完成当作服务端已认可登录。

## 卡片刷新与应用更新

额度、套餐、额外额度和已采集的邀请信息按字段保留上次成功值与时间。暂态失败不会把未知当作 0；跨周期/变旧的数值会标为“上次”或“旧周期”，不冒充实时数据。前台会节流刷新当前账号，鉴权失败、限流或连续失败会暂停自动尝试，可按提示登录或手动重试。其他账号在选中时刷新，不为所有卡片同时发请求。

邀请码/次数只被动读取页面已经提供的字段；`uses_remaining` 与额外额度不是同一数值。当前尚无经证实的隐藏邀请信息首次只读 reader，不会模拟点击揭示、刮卡、领取或兑换，因此未首次提供的信息仍会显示待页面提供。

应用启动后会在后台检查本仓库公开的稳定 Release，也可在侧边栏点“检查更新”。发现新版后点“更新”才下载，显示进度并支持取消；包名、版本、SHA-256 和签名校验通过后，按系统未知来源授权及安装确认流程继续。签名不一致或包损坏会停止，不建议卸载重装或清除数据来绕过。

首次必须手动升级到包含更新功能的版本；不含这段代码的旧应用不会仅因 GitHub 发布新版就自行弹出提醒。更新检查不使用 Muse Cookie、账号代理认证或 GitHub token。真实设备上的 provider、系统安装与真实账号身份仍需单独验收，主机合约不等于真机成功。

## 下载与安装

在本仓库的 **Releases** 页面下载 `Muse-Android-<版本>-preview.apk`。
同一签名的后续版本可覆盖安装；请勿先卸载旧版或清除数据。首次使用请打开账号，在 Muse 网页登录。
需要语音输入时允许 Android 麦克风权限。

## 独立构建

本仓库已经包含网页适配脚本，不需要下载 PC 端或创建任何相邻目录。
安装 JDK 21、Android SDK platform 35 / build-tools 35.0.0，并设置 `JAVA_HOME`、`ANDROID_HOME`。
Gradle Wrapper 固定为 8.9，并验证分发包 SHA-256；首次构建会下载 Gradle 和依赖。

```bash
./gradlew :app:assembleDebug :app:lintDebug :app:writeContractCheckClasspath
pwsh ./scripts/check-contracts.ps1
```

Windows 使用 `gradlew.bat`，或双击 `一键打包APK.cmd`。已有本机工具链会自动复用。
本机预览签名构建：

```powershell
.\build-preview.ps1
.\scripts\package-android.ps1 -Variant preview
```

本机构建结果在 `app/build/outputs/apk/`；整理后的 APK、许可说明和 SHA-256 文件在 `dist/`。
本地签名保存在 `.build/preview.keystore`，请备份并保留。不要把它提交到 Git。

## 自动构建和签名

`main` 提交、Pull Request 和手动运行 **Android client** 都会自动构建、运行 Java / 浏览器适配回归检查，并上传 APK。

- 配置签名 Secrets 的可信构建生成 `preview` 包，后续版本保持相同签名。
- Pull Request 及未配置签名的 Fork 生成 `debug` 包，不能直接覆盖不同签名的正式包。
- `v*` 标签必须与 `app/build.gradle` 的 `versionName` 一致；标签构建成功后自动发布 GitHub Release。
- 发布前同时递增 `versionCode`，Android 用它判断是否为更新版本。

在 GitHub 仓库 **Settings → Secrets and variables → Actions** 配置：

| Secret | 内容 |
| --- | --- |
| `ANDROID_KEYSTORE_BASE64` | 签名文件的 Base64 内容 |
| `ANDROID_KEYSTORE_PASSWORD` | 签名文件密码 |
| `ANDROID_KEY_ALIAS` | 签名条目的 alias |
| `ANDROID_KEY_PASSWORD` | 私钥密码 |

CI 只把签名文件临时还原到 runner 的临时目录，完成后删除；APK、源码和缓存中不会附带签名文件。
本地也可通过 `MUSE_ANDROID_KEYSTORE`、`MUSE_ANDROID_STORE_PASSWORD`、`MUSE_ANDROID_KEY_ALIAS`、
`MUSE_ANDROID_KEY_PASSWORD` 环境变量选择自己的签名，不必修改源码。

```bash
git tag v0.1.27
git push origin v0.1.27
```

## 网页适配与测试

网页适配器位于 `app/src/main/assets/`，由 PC 端适配器同步而来，在本仓库内独立维护。
对应回归检查位于 `checks/`，可用 Node.js 22 及以上运行：

```bash
node --test checks/check-observer.cjs
node checks/check-studio.cjs
node checks/check-original-media.cjs
node checks/check-reference-upload.cjs
```

## 社区致谢

感谢 [LINUX DO](https://linux.do/) 社区的交流与分享。本项目认可并支持 LINUX DO 社区的开源交流与互助。

## 许可证

原创代码采用 MIT。AndroidX、Material Components 和 Gradle Wrapper 保留 Apache-2.0 许可；
图标和 MUSE2API 相关适配保留相应 MIT 说明。详见 `NOTICE`、`THIRD_PARTY_NOTICES.md`、`LICENSES/`。
