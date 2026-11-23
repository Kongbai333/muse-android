# #16 Android 官方稳定 Release 更新

基线44abbb49，保留#15、0.1.27/code29与加密迁移v1；#14发布hold不变。scope-design.md已授权常规owner选择及原worker顺序实施，不再询问产品方向，不使用真实账号/profile做验证。

## 原生流程及owner选择

使用独立应用级 HTTPS 客户端访问固定官方 Kongbai333/muse-android（ID1467211251）及 latest stable Release；无 Muse CookieManager、代理认证、GitHub token 或新依赖。不使用 WebView承载下载/安装页，也不新增服务器。启动/前台恢复不阻塞地检查，并在现有侧栏增加“检查更新”。自动检查成功间隔6h、普通失败30min，手动1min节流，限流尊重 Retry-After；无提示后台大包下载不做。可缓存上次合法的公开 Release 元数据，标注来源时间，手动可再次检查。

只接受非draft/非prerelease的vX.Y.Z稳定版本，按数值比较，选择同一 Release 精确的 Muse-Android-X.Y.Z-preview.apk 和对应.sha256；preview是现有正式签名variant，不改签名。API/校验文本/APK分别限1MiB/4KiB/128MiB；APK声明大小及完整字节、sha256文件和可用API digest须一致。初始资产URL必须精确位于官方repo/tag/filename，所有跳转仅HTTPS、默认端口、无userinfo/fragment及明确GitHub/CDN主机，最多5跳。正文按纯文本显示，不执行脚本，不解压APK。

下载只写应用私有app-updates目录的固定download.part，完成校验后同目录rename为verified.apk；不按资产名拼任意路径。取消/失败删除半包。强制终止可能留下私有暂存，重启不会自动安装它，下一次准备先清理并重新下载校验；不碰账号/Keystore/工作室数据。

安装前用PackageManager读取已装与APK的包名、versionName、更高versionCode、签名证书集合，要求同应用、目标版本一致、严格同签名；失败不建议卸载或清数据。API26/27读取旧签名API，28+读取SigningInfo。下载校验后若未知来源未授权，仅交系统该应用授权页，用户可取消；已允许后用PackageInstaller.MODE_FULL_INSTALL，31+显式USER_ACTION_REQUIRED，fsync并关闭写流再commit。target35的statusReceiver使用显式、可变PendingIntent（官方Session.commit说明不能immutable），私有receiver只处理本应用记录的sessionId；PENDING_USER_ACTION交当前前台Activity启动系统确认，后台不强行拉起。失败/取消放弃本次会话，保留旧安装和所有用户数据；完成由系统处理，不承诺静默安装。

会话状态最小持久化；Activity销毁取消网络/派生回调，已commit的系统安装会话不随界面销毁误删。进程死亡丢失确认Intent时只提示可取消/重新准备，重试前放弃本应用旧会话，不自动提交新安装。用户可稍后/取消/重试，不强杀进程。

## 验证边界

纯Java真实策略与字节下载逻辑用合成Release/流/临时目录先RED后GREEN，覆盖版本、错包/缺包/源URL、尺寸/sha、取消/中断/半包、包名/code/同签名。native source边界+真实Android API编译覆盖系统权限、可变私有status receiver和待确认路径。最终必要Gradle/lint/既有合约只在两项追加结束后运行一次；没有真机安装/未知来源/provider实测，不以host检查冒充。老安装必须先手动升到包含本功能的版本，单发GitHub Release不会为没有更新代码的旧程序凭空增加提醒。

官方核对来源：Android PackageInstaller.Session（commit/status/fsync/close）、SessionParams（USER_ACTION_REQUIRED）；GitHub REST releases/latest与assets。只参考文档接口，不复制外部实现/新增许可证依赖。
