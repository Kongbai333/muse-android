# Muse Android

面向 Muse.ai 的非官方 Android 客户端。支持 Android 8.0 及以上，与 Windows 客户端独立运行。

## 功能

- 多账号、独立网页登录、头像与套餐额度同步。
- 可拖动、贴边收起的悬浮账号头像及快速切号菜单。
- HTTP / HTTPS / SOCKS5 代理池与本机账号路由分流。
- 图片 / 视频创作工作室、参考图片、独立会话、进度和本机历史。
- 深色 / 浅色外观、可调整的提示词输入框和媒体预览保存。
- 凭据使用 Android Keystore 保护，账号、历史和生成文件保存在设备本机。

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
git tag v0.1.26
git push origin v0.1.26
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
