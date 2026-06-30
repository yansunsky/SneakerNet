# SneakerNet

我的世界 1.21.1 NeoForge 实验性跨服务器传递物品模组

> English documentation: [README_EN.md](README_EN.md)

## 简介

SneakerNet 是一个基于物理介质（"SneakerNet"）实现 Minecraft 服务器之间物品流转的安全模组。  
使用 **ECC P-256 非对称加密**（ECDH 密钥协商 + ECDSA 数字签名 + AES-256-GCM 加密），确保物品传输的鉴权、防篡改与防重放。

物品以加密凭据（JSON 格式）导出，玩家手动跨服务器传递文件后导入，无需服务器间实时网络连接。

## 版本

- **v0.5.0** — ECC P-256 非对称加密升级
- **Mod ID**: `sneakernet`
- **运行环境**: NeoForge 21.1.219+ / Minecraft 1.21.1 / Java 21

## 工作流程

SneakerNet 的核心是"互相信任对方的公钥"。管理员先在各自服务器生成密钥并交换公钥，玩家再绑定目标服务器并用凭证票传递物品。

### 简化的公钥交换（推荐：直接粘贴公钥）

```
服务器 A                                服务器 B
  │                                        │
  ├─ /sneakernet keygen                    ├─ /sneakernet keygen
  │   → 生成密钥、自动信任本服              │   → 生成密钥、自动信任本服
  │   → 聊天栏输出可点击复制的公钥          │   → 聊天栏输出可点击复制的公钥
  │                                        │
  │   (把 A 的公钥发给 B 的管理员) ───────→ │
  │ ←─────── (把 B 的公钥发给 A 的管理员)   │
  │                                        │
  ├─ /sneakernet trustkey B <B的公钥>      ├─ /sneakernet trustkey A <A的公钥>
  │                                        │
  ▼                                        ▼
玩家在 A 服：                            玩家在 B 服：
  ├─ /sneakernet serverlist  查看可信服务器
  ├─ /sneakernet bind B      绑定目标服务器
  ├─ 用凭证票右键容器 → 加密导出为 voucher.json（自动同步到客户端）
  │   (把 json 文件发给在 B 服的自己) ────→
  │                                        ├─ /sneakernet import  → 换取包裹物品
```

> `keygen` 生成的公钥可在聊天栏中**点击直接复制**，发给对方管理员后用 `trustkey` 一行即可完成信任，无需传文件。
> 若偏好文件方式，仍可使用 `showpub` 导出公钥 JSON，对方用 `trust <name> <file>` 导入。

## 命令

> 不确定某条命令的用法时，随时输入 `/sneakernet help` 查看说明（命令可点击填入聊天栏）。

### 玩家命令

| 命令 | 说明 | 权限 |
|------|------|------|
| `/sneakernet help` | 显示所有命令的说明 | 所有玩家 |
| `/sneakernet serverlist` | 查看可信服务器名称列表 | 所有玩家 |
| `/sneakernet bind <name>` | 绑定目标服务器（使用凭证票前必须绑定） | 所有玩家 |
| `/sneakernet bind clear` | 清除目标服务器绑定 | 所有玩家 |
| `/sneakernet import` | 扫描并上传客户端本地所有凭证 | 所有玩家 |
| `/sneakernet import <filename>` | 上传指定凭证文件 | 所有玩家 |

### 管理员命令

| 命令 | 说明 | 权限 |
|------|------|------|
| `/sneakernet keygen` | 生成/更新本服密钥对，自动信任本服并输出可复制公钥 | OP (level 2) |
| `/sneakernet pubkey` | 在聊天栏显示当前公钥，可点击复制（不重新生成密钥） | OP (level 2) |
| `/sneakernet showpub` | 导出本服公钥到 JSON 文件（用于文件交换） | OP (level 2) |
| `/sneakernet trust <name> <file>` | 从公钥 JSON 文件导入信任 | OP (level 2) |
| `/sneakernet trustkey <name> <pubkey>` | 直接粘贴对方公钥（Base64）完成信任 | OP (level 2) |
| `/sneakernet rename <old> <new>` | 重命名一个可信服务器 | OP (level 2) |
| `/sneakernet untrust <name>` | 移除可信服务器 | OP (level 2) |
| `/sneakernet list` | 列出所有可信服务器及详细信息（KeyID、指纹） | OP (level 2) |

### 管理员快速上手

1. `/sneakernet keygen` — 生成本服密钥，会自动信任本服（默认名"当前服务器"），并在聊天栏给出可复制的公钥。
2. 把你的公钥发给对方服务器的管理员，同时索取对方的公钥。
3. 信任对方公钥（任选其一）：
   - **方式 A · 命令直接信任**：`/sneakernet trustkey <对方服务器名> <对方公钥>` —— 一行完成。
   - **方式 B · JSON 文件导入**：见下文「手动 JSON 导入」。
4. 用 `/sneakernet list` 核对，必要时用 `/sneakernet rename` 改名。

#### 手动 JSON 导入（公钥太长发不出去、或服务器禁用命令方块时）

ECC 公钥的 Base64 串较长，**普通聊天栏单条消息有 256 字符上限，无法直接粘贴整段公钥**。
如果你又没有命令方块可用（部分服务器关闭了命令方块，见下方说明），就改用文件交换：

1. **导出方**执行 `/sneakernet showpub`，会在本服 `config/sneakernet/local_pub.json` 生成公钥文件，内容形如：
   ```json
   {
     "serverName": "...",
     "keyId": "...",
     "publicKeyBase64": "<完整公钥，不受聊天 256 字符限制>",
     "fingerprint": "..."
   }
   ```
2. 通过任意带外渠道（QQ/邮件/网盘/U 盘等）把这个 `local_pub.json` 文件发给对方管理员。
3. **接收方**把收到的文件放进自己服务器的 `config/sneakernet/` 目录，建议重命名以区分来源，例如 `serverA_pub.json`。
4. **接收方**执行 `/sneakernet trust <对方服务器名> <文件名>`，例如：
   ```
   /sneakernet trust ServerA serverA_pub.json
   ```
   > `<文件名>` 是相对 `config/sneakernet/` 目录的路径，只需填文件名即可。
5. 双方各自完成上述步骤后，用 `/sneakernet list` 确认对方已出现在可信列表中。

> 关于命令方块：`trustkey`/`trust` 等 OP 命令**可以由命令方块执行**——命令方块默认以权限等级 2（等同 OP）运行，且命令方块输入框没有聊天栏的 256 字符限制，能容纳整段长公钥。
> 但前提是服务器**启用了命令方块**（`server.properties` 中 `enable-command-block=true`）。若服务器关闭了命令方块，就只能用上面的「手动 JSON 导入」方式。

### 玩家快速上手

1. `/sneakernet serverlist` — 查看本服已信任哪些目标服务器。
2. `/sneakernet bind <目标服务器名>` — 绑定你要寄往的服务器。
3. 用**凭证票**右键一个装好物品的容器，导出凭证（文件会同步到你的客户端 `.minecraft/sneakernet/vouchers/`）。
4. 把凭证文件带到目标服务器（同一客户端登录即可），执行 `/sneakernet import` 换取**包裹**，右键放置即可还原容器与物品。

## 物品

| 物品 | 获取方式 | 用途 |
|------|----------|------|
| **SneakerNet Ticket**（凭证票） | 合成：8 纸 + 1 墨囊 → 8 个 | 右键容器，加密导出内容为 JSON 凭证 |
| **SneakerNet Package**（包裹） | 通过 `/sneakernet import` 获得 | 右键放置，还原容器内容 |

## 安全机制

| 机制 | 实现 | 说明 |
|------|------|------|
| 非对称加密 | ECC P-256 (secp256r1) | 每个服务器独立密钥对，无共享主密钥 |
| 前向安全 | 每次导出生成临时 ECC 密钥对 | 私钥泄露不影响历史凭证 |
| 数字签名 | ECDSA (SHA256withECDSA) | 防篡改、防伪造 |
| 数据加密 | AES-256-GCM + HKDF 密钥派生 | 容器 NBT 加密传输 |
| 防双花 | JSON 文件黑名单 (`blacklist.json`) | 已核销凭证无法再次使用 |
| 玩家绑定 | UUID 匹配（可选） | 导出者与导入者可要求一致 |
| TTL 过期 | Unix 时间戳比较 | 凭据超时自动失效 |

## 构建

```bash
./gradlew build
```

构建产物：`build/libs/sneakernet-0.5.0.jar`

## 配置

服务端配置文件：`sneakernet-server.toml`（生成于 `world/serverconfig/`）

| 配置项 | 默认值 | 说明 |
|--------|--------|------|
| `voucherTtlHours` | 24 | 凭据有效期（小时） |
| `maxItemsPerVoucher` | 1728 | 单个凭据最大物品数 |
| `requirePlayerMatch` | true | 是否要求使用者和导出者一致 |
| `debugMode` | false | 调试模式 |

## 目录结构

```
config/sneakernet/                    # 服务端密钥 & 配置目录
├── local_key.der                     # 本地 ECC 私钥 (PKCS#8 DER)
├── local_pub.der                     # 本地 ECC 公钥 (X.509 DER)
├── local_pub.json                    # 公钥导出文件（用于交换）
├── trusted_keys.json                 # 可信服务器公钥列表
└── blacklist.json                    # 已核销凭证黑名单

.minecraft/sneakernet/                # 客户端目录
├── vouchers/                         # 待导入的凭证文件
└── redeemed/                         # 已核销的凭证文件
```

## 网络协议

| 包名 | 方向 | 说明 |
|------|------|------|
| `VoucherSyncPayload` | S2C | 导出成功后同步凭证 JSON 到客户端 |
| `ImportVoucherPayload` | C2S | 客户端上传凭证 JSON 到服务端 |
| `ImportResultPayload` | S2C | 服务端回执导入结果，触发客户端核销 |

## 依赖

- **纯 Java 标准库 + Gson** — 零外部运行时依赖
- NeoForge 内置 API — 所有 Mod 注册、事件、网络均使用 NeoForge 原生 API

## 开源协议

本项目采用 GPLv3 开源协议。
