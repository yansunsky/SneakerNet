# SneakerNet

我的世界 1.21.1 NeoForge 实验性跨服务器传递物品模组

## 简介

SneakerNet 是一个基于物理介质（"SneakerNet"）实现 Minecraft 服务器之间物品流转的安全模组。  
使用 **ECC P-256 非对称加密**（ECDH 密钥协商 + ECDSA 数字签名 + AES-256-GCM 加密），确保物品传输的鉴权、防篡改与防重放。

物品以加密凭据（JSON 格式）导出，玩家手动跨服务器传递文件后导入，无需服务器间实时网络连接。

## 版本

- **v0.5.0** — ECC P-256 非对称加密升级
- **Mod ID**: `sneakernet`
- **运行环境**: NeoForge 21.1.219+ / Minecraft 1.21.1 / Java 21

## 工作流程

```
服务器 A                               服务器 B
  │                                       │
  ├─ /sneakernet keygen                  ├─ /sneakernet keygen
  ├─ /sneakernet showpub                 ├─ /sneakernet showpub
  │       ↓                              │       ↓
  │   导出 local_pub.json                │   导出 local_pub.json
  │       ↓                              │       ↓
  │  (交换公钥文件) ────────────────→     │
  │       ↓                              │
  │  /sneakernet trust A_pub.json        ├─ /sneakernet trust B_pub.json
  │                                       │
  │  /sneakernet bind B                  ├─ /sneakernet bind A
  │       ↓                              │
  │  使用 Ticket 右键容器                 │
  │  → 加密导出为 voucher.json           │
  │  → 同步到客户端 .minecraft/...        │
  │       ↓                              │
  │  (复制 json 文件) ────────────────→   │
  │                                       │
  │                                      ├─ /sneakernet import
  │                                      │  → 客户端上传凭证到服务端
  │                                      │  → 解密验证
  │                                      │  → 发放 Package 物品
  │                                      │  → 客户端自动核销文件
```

## 命令

### 玩家命令

| 命令 | 说明 | 权限 |
|------|------|------|
| `/sneakernet bind <name>` | 绑定目标服务器（使用 Ticket 前必须绑定） | 所有玩家 |
| `/sneakernet bind clear` | 清除目标服务器绑定 | 所有玩家 |
| `/sneakernet import` | 扫描并上传客户端本地所有凭证 | 所有玩家 |
| `/sneakernet import <filename>` | 上传指定凭证文件 | 所有玩家 |
| `/sneakernet showpub` | 导出本服公钥到 JSON 文件 | 所有玩家 |

### 管理员命令

| 命令 | 说明 | 权限 |
|------|------|------|
| `/sneakernet keygen` | 生成本服 ECC 密钥对 | OP (level 2) |
| `/sneakernet trust <name> <file>` | 导入可信服务器公钥 | OP (level 2) |
| `/sneakernet untrust <name>` | 移除可信服务器 | OP (level 2) |
| `/sneakernet list` | 列出所有可信服务器 | OP (level 2) |

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
