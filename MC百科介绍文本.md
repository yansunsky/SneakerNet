[mark:title_menu]
基于离线凭证的跨服务器物品转运模组。

**不再需要服务器间开通实时网络连接了！**

[h1=🎒 什么是 SneakerNet？]

SneakerNet（网球鞋网络）是一个让物品在**不同 Minecraft 服务器之间安全流转**的模组。它的名字来源于计算机界经典的「SneakerNet」概念——靠人穿着球鞋跑来跑去传递数据，而不是靠网线。

在 SneakerNet 中，物品以**加密凭证（Voucher）**的形式导出为 JSON 文件，由玩家手动将文件带到目标服务器的客户端目录，再执行导入命令即可还原物品。整个过程无需服务器之间建立任何实时网络连接。

使用 **ECC P-256 非对称加密**（ECDH 密钥协商 + ECDSA 数字签名 + AES-256-GCM 加密 + HKDF 密钥派生），确保物品传输的鉴权、防篡改与防重放。

模组目前支持 NeoForge 1.21.1（v0.5.0），为 1.21.1 原生开发，无需前置模组。

[h2=🔐 安全概览]

| 机制 | 实现 |
|------|------|
| 非对称加密 | ECC P-256 (secp256r1)，每服务器独立密钥对 |
| 前向安全 | 每次导出生成临时 ECC 密钥对，私钥泄露不影响历史凭证 |
| 数字签名 | ECDSA (SHA256withECDSA)，防篡改、防伪造 |
| 数据加密 | AES-256-GCM + HKDF 密钥派生，容器 NBT 密文传输 |
| 防双花 | JSON 黑名单，已核销凭证无法再次使用 |
| 玩家绑定 | UUID 匹配（可选），要求导入者与导出者一致 |
| TTL 过期 | Unix 时间戳比较，凭证超时自动失效 |

[h2=📦 模组内容]

### 物品

| 物品 | 获取方式 | 用途 |
|------|----------|------|
| **SneakerNet Ticket（凭证票）** | 合成：8 纸 + 1 墨囊 → 8 个 | 右键容器，加密导出内容为 JSON 凭证 |
| **SneakerNet Package（包裹）** | 通过 `/sneakernet import` 获得 | 右键放置，还原容器内容与物品 |

### 系统架构

```
┌─────────────────────────────────┐
│         服务端 A                 │
│  ┌───────────┐                  │
│  │ 本地密钥对 ├─→ keygen        │
│  └─────┬─────┘                  │
│        │ 公钥交换                │
│  ┌─────▼─────┐                  │
│  │ 可信密钥列表├─→ trust / trustkey
│  └─────┬─────┘                  │
│        │                        │
│  ┌─────▼─────┐                  │
│  │ Ticket 使用├─→ 右键容器       │
│  │ + 异步加密 │  → voucher.json │
│  └───────────┘  → 同步到客户端   │
└─────────────────────────────────┘
         │ 玩家拷贝 JSON 文件
         ▼
┌─────────────────────────────────┐
│         服务端 B                 │
│  ┌───────────┐                  │
│  │ 本地密钥对 ├─→ keygen        │
│  └─────┬─────┘                  │
│        │                        │
│  ┌─────▼─────┐                  │
│  │ Package   ├─→ 解密验证       │
│  │ 物品还原  │  → 还原容器+物品  │
│  └───────────┘                  │
└─────────────────────────────────┘
```

[h1=⚡ 快速上手]

[h2=👑 管理员（服务器互通设置）]

1. 进入服务器，执行 `/sneakernet keygen` — 一键生成本服 ECC 密钥对，自动信任本服（默认名"当前服务器"），聊天栏输出可**点击复制**的公钥字符串。
2. 把复制的公钥发给对方管理员，同时索取对方的公钥。
3. 信任对方（二选一）：
   - **公钥直接粘贴**：`/sneakernet trustkey <服务器名> <公钥>` — 一行完成。
   - **JSON 文件导入**：`/sneakernet showpub` 导出公钥文件，发给对方，对方放入 `config/sneakernet/` 后用 `/sneakernet trust <服务器名> <文件名>` 导入。
4. `/sneakernet list` — 确认对方已出现在可信列表中。必要时用 `/sneakernet rename` 改名。
5. `trustkey`/`trust` 等 OP 命令**可由命令方块执行**（权限等级 2、无 256 字符限制），但需 `server.properties` 的 `enable-command-block=true`。

[h2=🎮 玩家（跨服转运物品）]

1. `/sneakernet serverlist` — 查看本服已信任了哪些目标服务器。
2. `/sneakernet bind <目标服务器名>` — 绑定你要寄往的服务器（可按 Tab 补全服务器名）。
3. 合成**凭证票**（8 纸 + 1 墨囊），右键一个装好物品的容器（支持任意模组容器）。
4. 凭证自动同步到客户端 `sneakernet/vouchers/` 目录。
5. 携带文件登录目标服务器，执行 `/sneakernet import` 换取**包裹**，右键放置即可还原容器与物品。

> 不确定某条命令的用法？随时输入 `/sneakernet help` 查看说明，命令可点击填入聊天栏。

[h1=📋 命令一览]

[h2=🟢 玩家命令（所有玩家可用）]

| 命令 | 说明 |
|------|------|
| `/sneakernet help` | 显示所有命令说明 |
| `/sneakernet serverlist` | 查看可信服务器名称列表 |
| `/sneakernet bind <名称>` | 绑定目标服务器（使用凭证票前必须绑定，支持 Tab 补全） |
| `/sneakernet bind clear` | 清除目标服务器绑定 |
| `/sneakernet import` | 扫描并上传客户端本地所有凭证 |
| `/sneakernet import <文件名>` | 上传指定凭证文件 |

[h2=🔴 管理员命令（OP level 2）]

| 命令 | 说明 |
|------|------|
| `/sneakernet keygen` | 生成/更新本服密钥对，自动信任本服并输出可复制公钥 |
| `/sneakernet pubkey` | 在聊天栏显示当前公钥，可点击复制（不重新生成密钥） |
| `/sneakernet showpub` | 导出本服公钥到 JSON 文件（用于文件交换） |
| `/sneakernet trust <名称> <文件>` | 从公钥 JSON 文件导入信任 |
| `/sneakernet trustkey <名称> <公钥>` | 直接粘贴对方公钥（Base64）完成信任 |
| `/sneakernet rename <原名> <新名>` | 重命名可信服务器（支持 Tab 补全原名） |
| `/sneakernet untrust <名称>` | 移除可信服务器（支持 Tab 补全名称） |
| `/sneakernet list` | 列出所有可信服务器的 KeyID、指纹等详细信息 |

[h1=📂 目录结构]

```
config/sneakernet/                    # 服务端密钥 & 配置目录
├── local_key.der                     # ECC 私钥 (PKCS#8 DER)
├── local_pub.der                     # ECC 公钥 (X.509 DER)
├── local_pub.json                    # 公钥导出文件（用于交换）
├── trusted_keys.json                 # 可信服务器公钥列表
└── blacklist.json                    # 已核销凭证黑名单

.minecraft/sneakernet/                # 客户端目录（自动创建）
├── vouchers/                         # 待导入的凭证文件
└── redeemed/                         # 已核销的凭证文件
```

[h1=🛠️ 网络包协议]

| 包名 | 方向 | 说明 |
|------|------|------|
| `VoucherSyncPayload` | S2C | 导出成功后同步凭证 JSON 到客户端 |
| `ImportVoucherPayload` | C2S | 客户端上传凭证 JSON 到服务端 |
| `ImportResultPayload` | S2C | 服务端回执导入结果，触发客户端核销 |

[h1=⚙️ 配置文件]

服务端配置文件：`sneakernet-server.toml`（生成于 `world/serverconfig/`）

| 配置项 | 默认值 | 说明 |
|--------|--------|------|
| `voucherTtlHours` | 24 | 凭证有效期（小时） |
| `maxItemsPerVoucher` | 1728 | 单个凭证最大物品数 |
| `requirePlayerMatch` | true | 是否要求使用者和导出者为同一位玩家 |
| `debugMode` | false | 调试模式 |

[h1=📦 构建]

```bash
./gradlew build
```

构建产物：`build/libs/sneakernet-0.5.0.jar`

[h1=📖 本地化（汉化）]

模组自带简体中文（zh_cn）与英文（en_us）语言文件，开箱即用，无需额外安装汉化包。

[h1=📎 依赖]

- **纯 Java 标准库 + Gson** — 零外部运行时依赖，无前置模组要求
- NeoForge 内置 API — 所有注册、事件、网络均使用原生 API
- 无 Flying Squid / Flywheel / 飞轮 等第三方库依赖

[h1=⚠️ 常见问题]

[h2=❓ 单人模式切换存档会报错吗？]

不会。SneakerNet 的资源（线程池、密钥管理器、黑名单）绑定到服务器生命周期，每次进入存档自动初始化，退出存档自动清理。**跨存档切换不影响使用**。

[h2=❓ 两个存档共享密钥吗？]

是的。单机模式下密钥存储在全局 `config/sneakernet/` 目录（按游戏安装而非按世界隔离），所以同一台电脑的所有存档使用同一套密钥。这是设计使然——你只需要在单机不同存档之间进行一次密钥交换。

如需每个存档独立密钥，可在进入新存档后手动执行 `/sneakernet keygen` 重新生成。

[h2=❓ 公钥太长、聊天栏放不下怎么办？]

ECC 公钥的 Base64 字符串确实较长。处理方式（按推荐顺序）：
1. **用命令方块**：命令方块输入框没有 256 字符限制，可以完整容纳公钥。前提是 `enable-command-block=true`。
2. **用 JSON 文件导入**：`showpub` 导出文件后通过 QQ / 邮件 / 网盘等带外渠道发送，对方用 `trust <名称> <文件>` 导入。

[h2=❓ 与跨服同步插件有冲突吗？**

SneakerNet 走的是纯"物理介质"路线——导出 JSON 文件 → 人工拷贝 → 导入还原。它**不涉及任何服务器间网络连接**，因此与任何跨服同步插件（如 Velocity / BungeeCord 的数据同步）没有直接冲突。

[h2=❓ 支持模组容器吗？**

支持！SneakerNet 通过 NeoForge 的方块能力（`Capabilities.ItemHandler.BLOCK`）检测容器，**兼容任何暴露了物品栏能力的模组容器**，包括但不限于：
- 模组箱子、模组桶
- 模组熔炉、模组机器输入/输出槽
- 各种模组背包的放置形态
- 科技模组的物流容器

不支持仅通过 GUI 操作、不暴露 IItemHandler 能力的自定义容器。

[h2=❓ 会出现物品复制漏洞吗？**

不会的过程在导出流程中有三重保障：
1. **主线程同步序列化**：容器物品在服务端主线程读取，不跨线程持有能力对象。
2. **清空容器**：导出成功立即在主线程清空容器所有槽位（`IItemHandlerModifiable` 逐槽置空）并移除容器方块。
3. **黑名单防双花**：已核销的凭证进入 `blacklist.json`，无法再次使用。

[h1=🔗 相关链接]

- GitHub（开源仓库）：https://github.com/yansunsky/SneakerNet
- NeoForge：https://neoforged.net/
- 协议：GPLv3

[h1=📺 展示]

> 以下为 SneakerNet 模组的使用流程示意图

```
【服务器 A】
┌─────────────────────────────────────┐
│  /sneakernet keygen                  │
│  → 生成密钥并自动信任本服             │
│  → 点击复制公钥发给对方               │
│  /sneakernet trustkey B <公钥>       │
│  → 一行信任对方                       │
│  /sneakernet bind B                  │
│  → 绑定目标服务器                     │
│  用凭证票右键容器                     │
│  → 自动导出并同步到客户端             │
└──────────────┬──────────────────────┘
               │ 复制 voucher.json
               ▼
【服务器 B】
┌─────────────────────────────────────┐
│  /sneakernet import                  │
│  → 导入凭证                           │
│  → 解密验证 → 发放 Package           │
│  右键 Package → 还原容器与物品        │
└─────────────────────────────────────┘
```
