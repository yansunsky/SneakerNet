# SneakerNet

我的世界1.21.1 NeoForge 实验性跨服务器传递物品模组

## 简介

SneakerNet 是一个基于物理介质（"SneakerNet"）实现 Minecraft 服务器之间物品流转的安全模组。  
使用 ECC P-256 非对称加密，确保物品传输的鉴权、防篡改与防重放。

## 版本

- **v0.5.0** — ECC P-256 升级，使用 ECDH + ECDSA + AES-256-GCM
- **Mod ID**: `sneakernet`
- **运行环境**: NeoForge 21.1.219+ / Minecraft 1.21.1 / Java 21

## 核心功能

1. **`/sneakernet keygen`** — 生成服务器 ECC 密钥对
2. **`/sneakernet showpub`** — 显示 Base64 公钥，用于服务器间交换
3. **`/sneakernet trust <json文件>`** — 信任其他服务器（导入其公钥）
4. **SneakerNet Ticket（凭证票）** — 右键容器导出物品为加密凭证
5. **SneakerNet Package（包裹）** — 右键放置，还原容器内容

## 安全机制

- 非对称加密（ECC P-256）：每个服务器持有独立密钥对，无共享主密钥
- 前向安全性：每次导出使用临时 ECC 密钥对
- 数字签名（ECDSA）：防止凭据伪造
- SQLite 黑名单：防止凭据重复使用
- 凭据有效期：可配置（默认 24 小时）

## 构建

```bash
./gradlew build
```

构建产物：`build/libs/sneakernet-0.5.0.jar`

## 配置

服务端配置文件：`sneakernet-server.toml`（生成于 `world/serverconfig/`）

| 配置项 | 默认值 | 说明 |
|--------|--------|------|
| `voucherTtlHours` | 24 | 凭据有效期（小时）|
| `maxItemsPerVoucher` | 1728 | 单个凭据最大物品数 |
| `requirePlayerMatch` | true | 是否要求使用者和导出者一致 |
| `debugMode` | false | 调试模式 |

## 开源协议

本项目采用 GPLv3 开源协议。
