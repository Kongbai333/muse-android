# 密码保护的账号与静态代理双向迁移

已获实现授权：PC/Android 双向本地迁移账号 Cookie、真实上游代理认证和绑定关系，使用密码保护文件。所有代理按静态配置处理，不增加 IP 检测、轮换或供应商会话逻辑。保留 Cookie 文本兼容入口，不把它称作完整迁移包。

## 架构与默认行为

1. `AccountTransferCrypto` 使用标准 JCA PBKDF2-HMAC-SHA256/AES-256-GCM；精确 envelope/AAD/限制及数据 schema 由 `docs/protocols/account-transfer-v1.md` 定义，Java/Rust 互解后共用，不能各自发布另一种 v1。CPU 派生在单线程后台执行，密码的可变字符数组/派生密钥/明文字节使用后清零，不落配置、日志或明文临时文件。
2. `AccountTransferBundle` 负责已认证 UTF-8 JSON 的严格字段/引用/大小/网站范围验证和 Cookie 文本兼容。`AccountTransferCookies` 负责目标 profile 的本地读取、GET_COOKIE_INFO 属性转换和逐条 setCookie/flush，不为导出导航网站；缺失属性标 unknown，不伪造 expiry。
3. `AccountTransferFlow` 是可取消、带代次的异步准备/写入/提交状态机。候选账号不进入 AccountStore 的可调度列表；代理、profile 与所有 Cookie 全部准备成功后，才一次性提交新账号并允许导航。崩溃前未提交的孤立 profile/代理不得被调度。失败不修改包外账号，取消后回调不能提交。
4. `MuseWeb` 在迁移期间关闭已有 worker/parked/前台页并阻止 open/openWorker/pump 导航；路由配置成功、Cookie 回调及 flush 后才能激活。`AccountStore.activateImported` 单次 commit 保存全部新候选，不复用源 ID/名称、不覆盖已有账号。代理每个包内引用新建一次，保留共享关系，失败仅回滚本次新节点。
5. `AccountTransferUi` 提供选择账号、密码/确认、系统文件保存/选择、预览和取消；MainActivity/AccountSidebar 仅接入动作和文件结果。默认全部新建，系统网络、设备相关地址、属性降级和来源旁路差异在预览说明并确认。Android 实际仍使用现有严格旁路规则，再导出描述实际策略，保留来源 force 标记只作来源说明。

## 兼容与交互

- 现有 minSdk 26、Webkit 1.9.0 不变。多账号导入必须有 MULTI_PROFILE；不支持时拒绝创建导入候选，不读错 Default。导出可处理当前真正使用 Default 的 fallback 账号；GET_COOKIE_INFO 单独检测，缺失时明确降级。
- 账号管理增加“导出账号与代理…”、“导入账号包…”和“导入 Cookie 文本…”。导入预览列账号/上游配置（认证遮罩），不显示 Cookie 或密码。旧文本需另外明确选定代理/系统网络。
- 不导出 native vault 密文、profile 路径、本机路由 listener/随机认证或本地 revision。不访问真实账号、真实代理或真实认证端点做开发验证，不改版本/签名/Android main/PC 文件。

## 验证

密码固定向量覆盖 ASCII 与中文/emoji，另有 Java 新随机包交 Rust 消费、Rust 新随机包交 Java 消费；固定向量不能替代真实实现互解。协议负例、属性/引用/认证保真、新 ID/共享节点、失败/取消/原子激活、源码集成约束、构建/lint/既有合约均需证据。没有真实 Muse 登录、实际手机/provider 或未跑平台的项目需单独列出，不以 mock 宣称真实可用。

## 实施选择

直接复用本地 vault 密文无法跨设备；明文包与用户已选“加密”冲突；因此采用标准密码加密 envelope 加逻辑负载。保持原界面和存储模型，仅增加聚焦的迁移组件，不新建管理模块。外层采用唯一紧凑 JSON 语法并固定参数/顺序，便于在昂贵派生前拒绝未知/重复字段和参数，避免两端 JSON 规范化/AAD 分歧。
