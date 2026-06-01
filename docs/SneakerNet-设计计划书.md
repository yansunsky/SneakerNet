# SneakerNet — 跨服物品离线转移模组 技术设计计划书

> **版本**：v0.5（伪代码审查修正 + 实现中）  
> **日期**：2026-06-02  
> **作者**：yansunsky  
> **运行环境**：NeoForge 1.21.1 / Minecraft 1.21.1 / Java 21  
> **状态**：实现中（Phase A 进行中）

---

## 一、项目背景与目标

### 1.1 问题陈述

多服务器架构下，玩家在不同服务器之间的物品转移通常需要：
- 实时网络通信（API 中间层、数据库共享）
- 可信第三方平台中转

当服务器之间**无法实时通信**（不同网络、无公网、防火墙限制）时，传统方案全部失效。

### 1.2 核心创意

**用玩家作为"人肉网桥"（Sneaker Network）**：

```
服务器A ──离线不可达──→ 服务器B
   │                        │
   │  [玩家携带加密凭据文件]  │
   └────── 玩家物理移动 ──────┘
```

玩家从一个服务器导出物品为**加密离线凭据（Voucher）**，保存到本地文件；  
在另一台服务器导入该文件，完成物品跨服转移。

### 1.3 设计目标

| 目标 | 说明 |
|------|------|
| **零实时通信** | 两台服务器永远不需要互相连接 |
| **高安全性** | 凭据无法伪造、篡改、重复使用、窃取 |
| **非对称密钥** | 服务器之间无需共享秘密，公钥可明文分发 |
| **易于部署** | 管理员只需交换公钥，无需安全信道 |
| **前向安全** | 每次导出使用临时密钥，历史凭据泄露不影响未来 |

---

## 二、系统架构

### 2.1 整体架构图

```
┌─────────────────────────────────────────────────────────────┐
│                       SneakerNet 模组                      │
├─────────────────────────────────────────────────────────────┤
│                    指令层（Commands）                       │
│   /sneakernet keygen             → 生成服务器密钥对       │
│   /sneakernet showpub            → 保存公钥到 JSON 文件   │
│   /sneakernet trust <name> <file> → 从 JSON 导入可信公钥│
│   /sneakernet untrust <name>     → 移除可信服务器        │
│   /sneakernet list               → 列出所有可信服务器    │
│   /sneakernet import [filename]  → 从文件导入物品        │
├─────────────────────────────────────────────────────────────┤
│                    物品层（Items）                          │
│   sneakernet_ticket   → 右键容器导出凭证（可合成）      │
│   sneakerNet_package   → 右键放置容器/获得物品（不可合成）│
├─────────────────────────────────────────────────────────────┤
│                    加密层（Crypto Layer）                   │
│   • ECC P-256 密钥对管理（每台服务器独立）               │
│   • ECDH 密钥协商（离线双方各自算出相同共享密钥）         │
│   • ECDSA 数字签名（防篡改 + 非否认）                   │
│   • AES-256-GCM 数据加密（混合加密，性能保证）           │
│   • HKDF 密钥派生（RFC 5869，基于 HmacSHA256）          │
├─────────────────────────────────────────────────────────────┤
│                    数据层（Data Layer）                    │
│   • Voucher 序列化 / 反序列化（FriendlyByteBuf 二进制）  │
│   • 物品 NBT 编解码（NeoForge 1.21.1 API）             │
│   • 一次性凭据黑名单（SQLite，防双花）                   │
│   • 凭据文件 I/O（JSON 格式，Base64 编码二进制数据）    │
├─────────────────────────────────────────────────────────────┤
│                    配置层（Config Layer）                  │
│   • 本服密钥对（DER 二进制格式，compact）               │
│   • 可信签发者公钥列表（Base64 DER 字符串）              │
│   • 凭据 TTL（默认 24h，可配置）                       │
│   • 凭据存储目录（客户端：.minecraft/sneakernet/）       │
└─────────────────────────────────────────────────────────────┘
```

### 2.2 模块划分

| 模块 | 文件名 | 职责 |
|------|--------|------|
| 主入口 | `SneakerNet.java` | Mod 生命周期、事件总线注册 |
| 客户端入口 | `SneakerNetClient.java` | 客户端侧初始化（Phase 1 留空，Phase 2 实现） |
| 配置管理 | `Config.java` | ForgeConfigSpec，管理密钥路径、TTL 等 |
| 密钥管理 | `KeyManager.java` | ECC 密钥对生成、DER 文件 I/O、Base64 编码 |
| 加密核心 | `VoucherCrypto.java` | ECDH + ECDSA + AES-GCM + HKDF |
| 凭据结构 | `Voucher.java` | 凭据数据结构（record），序列化/反序列化 |
| 黑名单 | `VoucherBlacklist.java` | SQLite 消费记录，防双花 |
| 命令注册 | `SneakerNetCommands.java` | Brigadier 命令树 |
| 物品注册 | `ModItems.java` | 注册 Ticket 和 Package 物品 |
| 物品行为 | `TicketItem.java` | Ticket 右键容器导出逻辑 |
| 物品行为 | `PackageItem.java` | Package 右键放置容器/获得物品逻辑 |
| 多线程管理 | `CryptoExecutor.java` | 加密/解密线程池（CompletableFuture） |
| 物品 NBT 工具 | `ItemNbtUtil.java` | FriendlyByteBuf NBT 编解码工具 |

---

## 三、ECC 非对称加密详细设计

### 3.1 为什么选 ECC（而非 RSA）

| 对比项 | RSA 2048 | ECC P-256 |
|--------|-----------|------------|
| 安全强度 | 112 bit | 128 bit |
| 私钥长度 | 2048 bit | 256 bit |
| 公钥长度 | 2048 bit | 256 bit（压缩）/ 512 bit（未压缩）|
| 签名速度 | 慢 | **快 10~100x** |
| Java 标准库支持 | ✅ | ✅ |
| 凭据文件膨胀 | 较大 | **小** |

**结论**：ECC P-256（= secp256r1 = prime256v1）是最优选择。

### 3.2 密钥体系

**每台服务器持有独立的密钥对**：

```
服务器A：
  local_key.der  →  绝对保密，DER 二进制格式（PKCS#8）
  local_pub.der  →  公钥 DER 二进制（X.509，本地备份用）
  showpub 输出   →  保存为 local_pub.json（Base64 DER 字符串，用于交换）

服务器B：
  local_key.der  →  绝对保密
  local_pub.der  →  本地备份
  showpub 输出   →  保存为 local_pub.json
```

**初始部署流程**（只需一次）：

```
Step 1: 每台服务器执行 /sneakernet keygen
        生成 KeyPair（P-256）
        私钥保存为 local_key.der（PKCS#8 DER，约 121 字节）
        公钥保存为 local_pub.der（X.509 DER，约 91 字节）

Step 2: 执行 /sneakernet showpub
        生成 config/sneakernet/local_pub.json
        内容包含 serverName、keyId、pubKeyBase64、fingerprint

Step 3: 管理员交换公钥文件
        将 local_pub.json 文件发送给其他服务器管理员
        其他服务器管理员执行 /sneakernet trust ServerA local_pub.json
        （自动解析 JSON 并写入 config/sneakernet/trusted_keys.json）

Step 4: 验证配置
        执行 /sneakernet list 确认所有可信服务器已添加
```

> ⚠️ **私钥不需要人类可读**，DER 二进制格式最紧凑，Java 原生支持直接加载。  
> ⚠️ **公钥交换用 JSON 文件**，避免聊天栏 256 字符限制。

### 3.3 导出凭据流程（服务器 A）

```
输入：玩家手持 SneakerNet Ticket 右键容器（箱子/潜影盒/桶等）
  │
  ▼
[1] 扫描容器内所有物品（递归扫描潜影盒内的物品）
  │
  ▼
[2] 显示确认 GUI（Phase 2，Phase 1 直接执行）
  │  "正在导出 N 个物品到服务器 B"
  │  [确认]  [取消]
  │
  ▼
[3] 扣除玩家 1 个 Ticket 物品（Server 端，先验）
  │
  ▼
[4] 异步执行加密（CompletableFuture，不阻塞主线程）
  │
  ▼
[5] 生成临时 ECC 密钥对（每次导出都不同 → 前向安全）
       ephemeralKeyPair = KeyPairGenerator("EC", "secp256r1").generateKeyPair()
  │
  ▼
[6] ECDH 密钥协商
       用 A 的临时私钥 + B 的公钥（从配置读取）
       → sharedSecret = KeyAgreement("ECDH").doPhase(publicKey_B)
  │
  ▼
[7] HKDF 密钥派生（RFC 5869）
       salt = ephemeralPublicKey_XY（截取部分作为 salt）
       info = "SneakerNet_v2_Encryption"
       aesKey = HKDF(sharedSecret, salt, info, length=32)
  │
  ▼
[8] 序列化物品 NBT（FriendlyByteBuf 二进制格式）
       nbtBytes = FriendlyByteBuf.writeNbt(containerNbt).toByteArray()
  │
  ▼
[9] AES-256-GCM 加密
       iv = SecureRandom(12 bytes)
       encryptedData = GCM.encrypt(aesKey, iv, nbtBytes)
  │
  ▼
[10] ECDSA 签名（用 A 的私钥）
       签名内容 = SHA256(ephemeralPubKey || encryptedData || iv || playerUuid || timestamp || itemHash)
       signature = Signature("SHA256withECDSA").sign(privateKey_A, signData)
  │
  ▼
[11] 组装 Voucher 对象
       Voucher {
         version: 2,
         issuerKeyId: SHA256(publicKey_A) 的前8字节（标识签发者）,
         ephemeralPublicKey: Base64(ephemeralPubKey),
         encryptedData: Base64(encryptedData),
         iv: Base64(iv),
         playerUuid: player.getUUID(),
         timestamp: Instant.now().getEpochSecond(),
         itemHash: SHA256(nbtBytes) 前8字节（防内容替换）,
         signature: Base64(signature),
         ttl: config.voucherTtlHours * 3600
       }
  │
  ▼
[12] 序列化为 JSON 文件，保存到客户端目录
       .minecraft/sneakernet/vouchers/export_<timestamp>_<random>.json
  │
  ▼
[13] 回调主线程通知玩家
       聊天栏提示："导出成功！文件已保存到 sneakernet/vouchers/"
       同时显示凭证指纹（前8字节）方便核对
```

> **Phase 1（当前版本）**：文件直接保存到客户端目录，玩家手动拷贝到另一台电脑。  
> **Phase 2（未来）**：自动通过网络传输（需要客户端模组配合）。

### 3.4 导入凭据流程（服务器 B）

```
输入：玩家执行 /sneakernet import [filename]
  │
  ▼
[1] 扫描客户端目录 .minecraft/sneakernet/vouchers/ 下所有 *.json 文件
      如果不指定 filename，则导入所有凭证
  │
  ▼
[2] 异步执行解密（CompletableFuture，不阻塞主线程）
  │
  ▼
[3] 逐个解析 JSON → Voucher 对象
      跳过无法解析的文件（记录日志）
  │
  ▼
[4] 验证签发者是否可信
       用 issuerKeyId 查找配置中的 trusted_issuers
       → 找到对应的 publicKey_A
       ✅ 找到 → 继续
       ❌ 未找到 → 记录"未知签发者"，跳过该凭证
  │
  ▼
[5] ECDSA 签名验证（用 A 的公钥）
       重新计算 signData = SHA256(ephemeralPubKey || encryptedData || iv || playerUuid || timestamp || itemHash)
       用 publicKey_A 验证 signature
       ✅ 通过 → 数据完整且确实由 A 签发
       ❌ 失败 → 记录"签名验证失败（可能被篡改）"，跳过该凭证
  │
  ▼
[6] ECDH 密钥协商（B 侧）
       用 B 的私钥 + Voucher 中的 ephemeralPublicKey
       → sharedSecret = KeyAgreement("ECDH").doPhase(ephemeralPubKey_A)
       （数学保证：与导出时算出的 sharedSecret 完全相同）
  │
  ▼
[7] HKDF 派生（与导出时完全相同的参数）
       aesKey = HKDF(sharedSecret, salt, info, length=32)
  │
  ▼
[8] AES-256-GCM 解密
       nbtBytes = GCM.decrypt(aesKey, iv, encryptedData)
       ✅ 成功 → 数据正确
       ❌ 失败（Authentication Tag 错误）→ 记录"解密失败（密钥错误或数据损坏）"，跳过该凭证
  │
  ▼
[9] 验证 itemHash（额外完整性校验）
       计算 SHA256(nbtBytes) 前8字节，与 Voucher.itemHash 对比
       ✅ 一致 → 数据未被替换
       ❌ 不一致 → 记录"数据完整性校验失败"，跳过该凭证
  │
  ▼
[10] 策略检查（Security Policies）
       ├─ [10a] 玩家 UUID 绑定检查
       │      Voucher.playerUuid == 当前玩家 UUID
       │      ✅ 一致 → 继续
       │      ❌ 不一致 → 记录"凭据与当前玩家不匹配"，跳过该凭证
       │
       ├─ [10b] TTL 过期检查
       │      currentTime - Voucher.timestamp > Voucher.ttl
       │      ✅ 未过期 → 继续
       │      ❌ 已过期 → 记录"凭据已过期"，跳过该凭证
       │
       └─ [10c] 一次性检查（黑名单）
              查询 SQLite：SELECT 1 FROM blacklist WHERE voucher_id = SHA256(ephemeralPubKey || timestamp)
              ✅ 未找到 → 继续（未使用过）
              ❌ 已找到 → 记录"凭据已使用（双花检测）"，跳过该凭证
  │
  ▼
[11] 反序列化容器 NBT
       containerNbt = CompoundTag.read(FriendlyByteBuf.fromByteArray(nbtBytes))
  │
  ▼
[12] 生成 SneakerNet Package 物品
       将 containerNbt 写入 Package 物品的 NBT
       放入玩家背包（或掉落到玩家位置）
  │
  ▼
[13] 记录到黑名单（防双花）
       INSERT INTO blacklist(voucher_id, issuer_id, player_uuid, used_timestamp)
       VALUES (SHA256(...), issuerKeyId, playerUuid, now())
       同时清理超过 TTL 的过期记录（定时任务）
  │
  ▼
[14] 移动凭证文件到已核销目录
       将 .minecraft/sneakernet/vouchers/xxx.json
       移动到 .minecraft/sneakernet/redeemed/xxx.json
       （删除失败也没关系，下次导入时会检测到已核销）
  │
  ▼
[15] 回调主线程通知玩家结果
       聊天栏提示："成功导入 N 个容器，失败 M 个（原因：xxx）"
```

---

## 四、道具物品设计

### 4.1 SneakerNet Ticket（票据物品）

**获取方式**：合成
```
合成配方：
  纸张  纸张  纸张
  纸张  墨囊  纸张
  纸张  纸张  纸张
  → 输出 8 个 SneakerNet Ticket
```

**功能**：右键容器（箱子/潜影盒/桶等）导出凭证

**使用流程**：
```
1. 玩家手持 Ticket 右键容器
2. 异步扫描容器 + 加密
3. 生成 voucher.json → 保存到 .minecraft/sneakernet/vouchers/
4. 消耗 1 个 Ticket
5. 提示玩家"导出成功！文件已保存"
```

**NBT 结构**：
```java
// Ticket 物品本身不需要存储数据
// 所有加密数据存储在 voucher.json 文件中
```

### 4.2 SneakerNet Package（包裹物品）

**获取方式**：**非合成**，使用 `/sneakernet import` 导入后获得

**功能**：右键放置容器（重新生成容器方块并填充物品）

**使用流程**：
```
1. 玩家手持 Package 物品右键方块表面
2. 读取物品 NBT 中的容器数据
3. 在瞄准位置放置容器方块
4. 将 NBT 中的物品列表填入容器
5. 消耗 1 个 Package 物品
```

**NBT 结构**：
```java
// SneakerNet Package 物品的 NBT
{
  "sneakernet:package": {
    "version": 2,
    "issuerKeyId": "a1b2c3d4e5f6a7b8",
    "playerUuid": "xxxx-xxxx-xxxx-xxxx",  // 原始导出玩家
    "exportedAt": 1717286400,
    "containerType": "minecraft:chest",  // 容器类型
    "containerNbt": {...}  // 容器的完整 BlockEntity NBT
  }
}
```

**重要特性**：
- ✅ **可以交易给其他玩家**（数据存储在 NBT 中，self-contained）
- ✅ **玩家可以选择放置位置**（右键时选择目标方块）
- ✅ **支持容器模式**（重新放置容器方块）

---

## 五、Voucher 文件格式

### 5.1 JSON Schema（人类可读，方便调试）

```json
{
  "version": 2,
  "issuerKeyId": "a1b2c3d4e5f6a7b8",
  "ephemeralPublicKey": "Base64(DER 或 X9.62 编码的 ECC 公钥)",
  "encryptedData": "Base64(AES-GCM 加密后的 NBT 数据)",
  "iv": "Base64(12字节随机 IV)",
  "playerUuid": "xxxxxxxx-xxxx-xxxx-xxxx-xxxxxxxxxxxx",
  "timestamp": 1717286400,
  "itemHash": "a1b2c3d4e5f6a7b8",
  "ttl": 86400,
  "signature": "Base64(ECDSA P-256 签名，约 64~72 字节)"
}
```

### 5.2 文件大小估算

| 字段 | 大小估算 |
|------|---------|
| version + metadata | ~50 bytes |
| ephemeralPublicKey（未压缩）| ~128 bytes → Base64 ~170 bytes |
| encryptedData | 与容器 NBT 大小相同（通常 200~5000 bytes）|
| iv | 12 bytes → Base64 ~16 bytes |
| signature | ~72 bytes → Base64 ~96 bytes |
| **总计** | **通常 0.5KB ~ 6KB**，Json 格式，可读性良好 |

### 5.3 为什么用 JSON 而不是纯二进制

- ✅ 方便管理员手动检查凭据内容
- ✅ 方便调试（直接 `cat voucher.json | jq`）
- ✅ 扩展性好（未来可加字段，不影响旧版解析）
- ✅ Base64 编码后可以直接粘贴到聊天栏/论坛/Discord
- ❌ 比纯二进制大约 30%，但凭据文件本身很小，可以接受

---

## 六、目录结构

### 6.1 服务端目录（config/sneakernet/）

```
config/sneakernet/
├── local_key.der      # 私钥（DER 二进制，PKCS#8 格式，建议权限 600）
├── local_pub.der      # 公钥（DER 二进制，X.509 格式，备用）
├── local_pub.json     # 公钥（JSON 格式，用于交换）
├── trusted_keys.json  # 可信服务器公钥列表（Base64 DER 字符串）
└── blacklist.db       # SQLite 黑名单数据库（防双花）
```

`trusted_keys.json` 格式：
```json
{
  "servers": [
    {"name": "ServerA", "keyId": "a1b2c3d4", "pubKeyBase64": "MFkwEwYHKoZIzj0CAQYIKoZIzj0...", "fingerprint": "D6:87:98:..."},
    {"name": "ServerB", "keyId": "e5f6a7b8", "pubKeyBase64": "MFkwEwYHKoZIzj0CAQYIKoZIzj0...", "fingerprint": "A1:B2:C3:..."}
  ]
}
```

`local_pub.json` 格式：
```json
{
  "serverName": "ServerA",
  "keyId": "a1b2c3d4e5f6a7b8",
  "pubKeyBase64": "MFkwEwYHKoZIzj0CAQYIKoZIzj0...",
  "generatedAt": "2026-06-02T02:30:00Z",
  "fingerprint": "SHA256:D6:87:98:..."
}
```

### 6.2 客户端目录（.minecraft/sneakernet/）

```
.minecraft/sneakernet/
├── vouchers/          # 待兑换凭证（导出时保存到这里）
│   ├── export_1717286400_ab12cd.json
│   └── export_1717286500_ef34gh.json
└── redeemed/         # 已核销凭证（备份，可手动清理）
    └── export_1717286400_ab12cd.json
```

**设计决策**：
- 核销后移动到 `redeemed/` 而不是直接删除，防止误删导致需要重新下载
- 删除失败也没关系，下次导入时会检测到已核销（通过 SQLite 黑名单）

---

## 七、多线程设计

### 7.1 线程池配置

```java
// CryptoExecutor.java
public class CryptoExecutor {
    private static final ExecutorService cryptoExecutor = 
        Executors.newFixedThreadPool(2);  // 加解密线程池
    
    public static <T> CompletableFuture<T> supplyAsync(Callable<T> task) {
        return CompletableFuture.supplyAsync(() -> {
            try {
                return task.call();
            } catch (Exception e) {
                throw new CompletionException(e);
            }
        }, cryptoExecutor);
    }
    
    public static void shutdown() {
        cryptoExecutor.shutdown();
    }
}
```

### 7.2 异步操作范围

| 操作 | 是否异步 | 原因 |
|------|----------|------|
| 导出（Ticket 右键） | ✅ 异步 | 加密耗时（容器可能很大） |
| 导入（/sneakernet import） | ✅ 异步 | 解密耗时（可能多个凭证） |
| 使用 Package（右键） | ❌ 同步 | 直接操作世界，必须在主线程 |
| 密钥生成（/sneakernet keygen） | ❌ 同步 | 耗时短（~100ms），无需异步 |

### 7.3 主线程回调

```java
// 加密完成后回调主线程
CryptoExecutor.supplyAsync(() -> {
    // 1. 扫描容器
    // 2. 序列化所有物品
    // 3. 加密
    // 4. 保存文件
    return voucherFile;
}).thenAccept(voucherFile -> {
    // 切回主线程通知玩家
    server.execute(() -> {
        player.sendSystemMessage(Component.literal("导出成功：" + voucherFile.getName()));
    });
});
```

---

## 八、安全分析

### 8.1 威胁模型

| 威胁 | 描述 | 防御措施 |
|------|------|---------|
| **窃听** | 攻击者截获 Voucher 文件 | AES-256-GCM 加密，无密钥无法解密 |
| **篡改** | 攻击者修改 Voucher 内容 | ECDSA 签名验证，任何修改都会导致验证失败 |
| **伪造** | 攻击者自己签发假 Voucher | 需要 A 的私钥，ECDSA 不可伪造 |
| **重放攻击** | 攻击者重复使用同一 Voucher | SQLite 黑名单，一次性使用 |
| **凭据窃取** | 攻击者偷走玩家本地文件 | 玩家 UUID 绑定，他人无法使用 |
| **中间人替换公钥** | 攻击者替换传输中的公钥 | 公钥通过可信渠道分发（管理员手动验证指纹）|
| **私钥泄露** | 服务器私钥被窃取 | 仅影响该服务器的签发能力，其他服务器不受影响（隔离性）|
| **过期的凭据被使用** | 时间窗口内重放 | TTL 检查，超时就拒绝 |

### 8.2 非对称方案 vs 对称方案 安全对比

```
【对称方案风险】
  两台服务器共享同一个 masterKey
  → 密钥分发需要安全信道（U盘/加密邮件）
  → 密钥泄露 = 所有服务器都受影响
  → 无法证明"这个凭据是 A 签发的"（共享密钥，A 可以否认）

【ECC方案优势】
  公钥可明文传输（不怕窃听）
  私钥泄露只影响单台服务器（隔离性）
  ECDSA 签名提供非否认性（A 无法否认自己签发的凭据）
  部署简单：管理员手动交换 JSON 公钥文件
```

### 8.3 密钥管理安全建议

```
私钥存储：
  ✅ 建议：存储在 config/sneakernet/local_key.der（DER 二进制）
  ✅ 建议：定期轮换密钥对（比如每 90 天），旧公钥在黑名单里保留 TTL 时长
  ❌ 禁止：将私钥存放在配置文件的明文里
  ❌ 禁止：将私钥提交到 Git 仓库

公钥分发：
  ✅ 建议：通过管理员手动交换 JSON 文件（避免聊天栏限制）
  ✅ 可选：公钥指纹（SHA256）在 Discord/论坛公示，供管理员交叉验证
```

---

## 九、实现计划（分阶段）

### Phase 1：核心功能（本次实现目标）

```
Sub-task 1.1：项目初始化
    ✅ 复制 MDK 1.21.1 模板
    ✅ 配置 modId=sneakernet, groupId=com.yansunsky.sneakernet
    ✅ 添加 SQLite JDBC 依赖（xerial:sqlite-jdbc:3.47.1.0）
    ⏳ 修复 build.gradle 编译错误

Sub-task 1.2：密钥管理模块（KeyManager.java）
    ⏳ 实现 ECC P-256 密钥对生成
    ⏳ 实现 DER 格式 I/O（PKCS#8 for Private Key, X.509 for Public Key）
    ⏳ 实现 Base64 编码/解码（用于公钥交换）
    ⏳ 实现密钥文件 I/O（保存到 config/sneakernet/）
    ⏳ 注册 /sneakernet keygen 和 /sneakernet showpub 命令
    ⏳ 实现 /sneakernet showpub 保存 local_pub.json

Sub-task 1.3：HKDF 实现（无外部依赖）
    ⏳ 按 RFC 5869 实现 HmacSHA256 版本的 HKDF-Expand
    ⏳ 编写单元测试验证与其他实现的一致性

Sub-task 1.4：加密核心重写（VoucherCrypto.java）
    ⏳ 实现 ECDH 密钥协商（用 Java 标准库 KeyAgreement）
    ⏳ 实现 ECDSA 签名/验证（Signature("SHA256withECDSA")）
    ⏳ 实现 AES-256-GCM 加密/解密
    ⏳ 对接 HKDF 派生密钥

Sub-task 1.5：NBT 序列化修复
    ⏳ 用 FriendlyByteBuf 重写容器 BlockEntity 的 NBT 序列化
    ⏳ 处理 NeoForge 1.21.1 中 Tag 与 CompoundTag 的类型差异

Sub-task 1.6：Voucher 结构升级（Voucher.java）
    ⏳ 升级到 version=2 格式
    ⏳ 实现 JSON 序列化/反序列化（Gson 或 Jackson）
    ⏳ 添加 issuerKeyId、itemHash 等新字段

Sub-task 1.7：物品注册与行为（ModItems.java, TicketItem.java, PackageItem.java）
    ⏳ 注册 SneakerNet Ticket 物品
    ⏳ 注册 SneakerNet Package 物品
    ⏳ 实现 Ticket 合成配方（8 纸 + 1 墨囊 → 8 个）
    ⏳ 实现 TicketItem 右键容器逻辑（异步加密）
    ⏳ 实现 PackageItem 右键放置容器逻辑（主线程同步）
    ⏳ 错误提示国际化（en_us.json + zh_cn.json）

Sub-task 1.8：多线程管理（CryptoExecutor.java）
    ⏳ 实现线程池（FixedThreadPool(2)）
    ⏳ 实现 CompletableFuture 异步包装
    ⏳ 实现主线程回调（server.execute()）

Sub-task 1.9：命令实现（SneakerNetCommands.java）
    ⏳ /sneakernet import → 完整导入流程（异步解密）
    ⏳ /sneakernet trust → 从 JSON 文件导入可信公钥
    ⏳ /sneakernet untrust → 移除可信服务器
    ⏳ /sneakernet list → 列出所有可信服务器
    ⏳ 错误提示国际化（en_us.json + zh_cn.json）

Sub-task 1.10：黑名单 SQLite 实现（VoucherBlacklist.java）
    ⏳ 创建 blacklist.db 数据库和表结构
    ⏳ 实现 voucher_id 字段（基于 ephemeralPubKey + timestamp 的 Hash）
    ⏳ 实现查询接口（检查是否已核销）
    ⏳ 实现插入接口（记录已核销凭证）
    ⏳ 添加过期记录自动清理任务

Sub-task 1.11：配置系统（Config.java）
    ⏳ 添加 trusted_issuers 配置项（List<String>，Base64 公钥）
    ⏳ 添加 key_pair_path 配置项（私钥文件路径）
    ⏳ 添加 voucher_ttl_hours 配置项（默认 24）
    ⏳ 实现 trusted_keys.json 的读写

Sub-task 1.12：客户端文件管理
    ⏳ 实现客户端目录扫描（.minecraft/sneakernet/vouchers/）
    ⏳ 实现凭证文件移动（vouchers/ → redeemed/）
    ⏳ 实现文件删除失败兜底（通过黑名单检测）

Sub-task 1.13：端到端测试
    ⏳ 同一服务器内导出→导入测试
    ⏳ 两台服务器之间导出→拷贝文件→导入测试
    ⏳ 篡改检测测试（手动修改 JSON 后导入）
    ⏳ UUID 绑定测试（A 的凭据 B 尝试导入）
    ⏳ 双花检测测试（同一凭据导入两次）
    ⏳ TTL 过期测试（修改系统时间）
    ⏳ 容器导出测试（箱子/潜影盒）
    ⏳ Package 物品交易测试（给其他玩家使用）
```

### Phase 2：用户体验优化（未来版本）

```
Sub-task 2.1：客户端模组支持
    → 导出时自动将 JSON 文件保存到玩家本地（.minecraft/sneakernet/）
    → 导入时弹出文件选择对话框
    → 自动检测 .minecraft/sneakernet/vouchers/ 目录

Sub-task 2.2：GUI 界面
    → 用 NeoForge 的 Screen 系统做导入/导出界面
    → 显示凭据详情（物品预览、签发者、过期时间）
    → 导出时显示确认 GUI（防止误操作）
    → 批量导入/导出支持

Sub-task 2.3：公钥自动拉取
    → 配置 URL，服务器启动时自动拉取最新公钥列表
    → 支持多个可信签发者（白名单模式）
```

### Phase 3：高级安全特性（未来版本）

```
Sub-task 3.1：密钥轮换支持
    → 旧公钥在配置中保留（标注有效期）
    → 导入时自动选择合适的公钥验证

Sub-task 3.2：审计日志
    → 所有导出/导入操作记录到 CSV 或数据库
    → 支持查询"某玩家转移了哪些物品"

Sub-task 3.3：速率限制
    → 每玩家每小时最多导出 N 个凭据（防滥用）
```

---

## 十、技术栈与依赖

### 10.1 强制依赖（已确认可用）

| 依赖 | 版本 | 用途 |
|------|------|------|
| NeoForge | 21.1.219+ | Mod 加载器 |
| Minecraft | 1.21.1 | 游戏本体 |
| Java | 21 | 运行环境 |
| SQLite JDBC | 3.47.1.0 | 黑名单数据库 |

### 10.2 使用 Java 标准库（无额外依赖）

| 功能 | Java 标准库类 |
|------|---------------|
| ECC 密钥对生成 | `KeyPairGenerator("EC")` + `ECGenParameterSpec("secp256r1")` |
| ECDH 密钥协商 | `KeyAgreement("ECDH")` |
| ECDSA 签名 | `Signature("SHA256withECDSA")` |
| AES-GCM 加密 | `Cipher("AES/GCM/NoPadding")` |
| HmacSHA256 | `Mac("HmacSHA256")` |
| HKDF | 自己实现（基于 HmacSHA256，RFC 5869）|
| NBT 序列化 | `FriendlyByteBuf`（NeoForge 提供）|
| JSON 处理 | `Gson`（NeoForge 已捆绑）|

> ✅ **无需引入 BouncyCastle 等第三方加密库**，Java 21 标准库已完整支持 ECC。

---

## 十一、设计决策记录

### 11.1 已确认事项

| # | 问题 | 决定 |
|---|------|------|
| 1 | **公钥分发方式** | 管理员手动交换 JSON 文件（通过 /sneakernet showpub 和 /sneakernet trust 命令） |
| 2 | **凭据文件传输方式（Phase 1）** | 保存到客户端目录（.minecraft/sneakernet/vouchers/），玩家手动拷贝 |
| 3 | **HKDF 实现** | 自写（基于 HmacSHA256，无外部依赖） |
| 4 | **ECC 曲线** | P-256（secp256r1），性能更好，128bit 安全足够 |
| 5 | **客户端模组（Phase 1）** | 仅服务端，客户端目录由服务端指令直接操作 |
| 6 | **多物品支持（Phase 1）** | 支持导出整个容器（箱子/潜影盒），导入时自动扫描并导入所有凭证 |
| 7 | **密钥格式** | 私钥：DER 二进制（PKCS#8）；公钥：Base64(DER) 字符串（X.509）；不使用 PEM |
| 8 | **黑名单数据库位置** | config/sneakernet/blacklist.db |
| 9 | **客户端目录** | .minecraft/sneakernet/（与游戏本体 jar 包同一目录） |
| 10 | **核销后文件处理** | 移动到 redeemed/ 目录，删除失败也没关系（通过黑名单检测） |
| 11 | **道具物品设计** | Ticket 可合成（8纸+1墨囊→8个），Package 不可合成（import 后获得） |
| 12 | **容器导出方式** | 手持 Ticket 右键容器，异步加密后保存 voucher.json |
| 13 | **容器导入方式** | 使用 /sneakernet import 导入后获得 Package 物品，右键放置容器 |
| 14 | **多线程优化** | 导出/导入使用 CompletableFuture 异步执行，Package 使用同步（主线程） |
| 15 | **Package 可交易性** | ✅ 可以交易给其他玩家（数据存储在 NBT 中） |

### 11.2 优化建议（已实现）

1. **凭证文件管理**：核销后移动到 `redeemed/` 而不是直接删除，防止误删
2. **导入时遍历策略**：扫描 `vouchers/` 下所有 `*.json` 文件，并行解析，快速跳过无效凭证
3. **汇总报告**：导入完成后显示"成功 N 个，失败 M 个（原因：xxx）"
4. **公钥文件分发**：使用 JSON 文件避免聊天栏 256 字符限制
5. **容器模式**：Package 右键时重新放置容器方块（不是直接生成物品）

---

## 十二、文件结构（最终实现）

```
src/main/java/com/yansunsky/sneakernet/
├── SneakerNet.java               # Mod 主类
├── SneakerNetClient.java          # 客户端入口（Phase 1 留空）
├── config/
│   └── Config.java                # ForgeConfigSpec 配置
├── crypto/
│   ├── KeyManager.java            # ECC 密钥对管理（DER + Base64）
│   ├── VoucherCrypto.java         # ECDH + ECDSA + AES + HKDF
│   └── HkdfUtil.java             # HKDF 实现（无依赖）
├── data/
│   ├── Voucher.java               # 凭据数据结构（record）
│   ├── VoucherBlacklist.java      # SQLite 黑名单
│   └── ItemNbtUtil.java          # NBT 序列化工具
├── items/
│   ├── ModItems.java              # 物品注册
│   ├── TicketItem.java            # Ticket 物品行为（右键容器导出）
│   └── PackageItem.java          # Package 物品行为（右键放置容器）
├── commands/
│   └── SneakerNetCommands.java    # Brigadier 命令
├── executor/
│   └── CryptoExecutor.java        # 多线程管理（CompletableFuture）
└── network/
    └── (预留，Phase 2 用)

src/main/resources/
├── META-INF/neoforge.mods.toml
├── data/sneakernet/recipes/
│   └── sneakernet_ticket.json    # Ticket 合成配方
└── assets/sneakernet/lang/
    ├── en_us.json
    └── zh_cn.json
```

---

## 十三、参考资料

| 资料 | 链接 |
|------|------|
| ECC 在 Java 中的使用 | [Oracle JCA Reference - EC](https://docs.oracle.com/en/java/javase/21/security/java-security-standard-algorithm-names.html) |
| HKDF RFC 5869 | [RFC 5869](https://www.rfc-editor.org/rfc/rfc5869) |
| AES-GCM 最佳实践 | [NIST SP 800-38D](https://nvlpubs.nist.gov/nistpubs/Legacy/SP/nistspecialpublication800-38d.pdf) |
| NeoForge 1.21.1 API | [NeoForged Docs](https://docs.neoforged.net/) |
| FriendlyByteBuf | [NeoForge Javadoc](https://neoforged.github.io/NeoForge/javadoc/) |

---

*本文档为技术设计计划书，实现细节以实际代码为准。*

---

## 十四、伪代码审查修正（v0.5）

基于完整伪代码审查，以下问题需要在实现中修正：

### 14.1 TicketItem 交互方式修正

伪代码中使用 `player.pick(5.0, 0, false)` 做射线追踪，但这在服务端不可靠。正确做法：

```java
// 方案：重写 Item.use() 方法，检查玩家注视的方块
@Override
public InteractionResult use(Level level, Player player, InteractionHand hand) {
    // 在服务端，使用 player.getBlockReach() 范围内的目标
    BlockHitResult hitResult = getPlayerPOVHitResult(level, player, ClipContext.Fluid.NONE);
    if (hitResult.getType() == HitResult.Type.BLOCK) {
        // ... 处理容器导出
    }
}
```

使用 Minecraft 原生的 `getPlayerPOVHitResult()` 方法（类似 `SpyglassItem` 的实现）。

### 14.2 导出策略确认

伪代码中对**每个可信服务器各生成一份凭据**。Phase 1 确认此行为：
- 右键容器时，为所有已配置的可信服务器各生成一个独立的 voucher.json
- 文件名格式：`{serverName}_export_{timestamp}_{random}.json`
- Phase 2 增加目标服务器选择 GUI

### 14.3 Package NBT 结构统一

伪代码中的 NBT 结构与计划书有差异，统一为：

```java
// SneakerNet Package 物品的 NBT
{
  "sneakernet:package": {
    "version": 2,
    "issuerKeyId": "a1b2c3d4e5f6a7b8",
    "playerUuid": "xxxx-xxxx-xxxx-xxxx",
    "exportedAt": 1717286400,
    "containerType": "minecraft:chest",
    "customName": "{...}",  // 可选，Component JSON
    "items": {
      "0": { itemNbt },
      "1": { itemNbt },
      ...
    }
  }
}
```

### 14.4 客户端目录定位

使用 NeoForge 提供的 `FMLPaths.GAMEDIR.get()` 获取游戏根目录：

```java
Path gameDir = FMLPaths.GAMEDIR.get();
Path vouchersDir = gameDir.resolve("sneakernet/vouchers/");
Path redeemedDir = gameDir.resolve("sneakernet/redeemed/");
```

### 14.5 Shulker Box 颜色处理

导入时需要根据 NBT 中的颜色数据选择对应的潜影盒方块：

```java
static Block getContainerBlock(String containerType, @Nullable DyeColor color) {
    if (containerType.equals("minecraft:shulker_box")) {
        return color != null ? ShulkerBoxBlock.getBlockByColor(color) : Blocks.SHULKER_BOX;
    }
    // ... 其他容器类型
}
```

### 14.6 安全检查顺序调整

伪代码中的验证顺序需要微调（签名验证应在 ECDH 解密之前，以尽早拒绝无效凭证）：

1. JSON 解析 → 版本号检查
2. **签发者查找** → issuerKeyId 在 trusted_keys.json 中？
3. **ECDSA 签名验证** → 数据完整性（此步不解密，最快拒绝伪造凭证）
4. ECDH + AES-GCM 解密
5. itemHash 校验
6. TTL 过期检查
7. UUID 绑定检查
8. 黑名单检查（防双花）

### 14.7 NeoForge 1.21.1 API 注意事项

| API | 说明 |
|-----|------|
| `ItemStack.save(RegistryAccess)` | 返回 `Tag`，需强转为 `CompoundTag` |
| `ItemStack.parseOptional(RegistryAccess, CompoundTag)` | 从 NBT 恢复物品 |
| `CompoundTag` | NeoForge 1.21.1 使用 Mojang 映射 |
| `Component.Serializer.toJson()` | 序列化文本组件 |
| `FMLPaths.GAMEDIR.get()` | 获取游戏根目录 |
| `DeferredRegister.createItems()` | 创建物品注册器 |
| `Item.Properties().setId(ResourceKey)` | **必须**显式设置 ID（26.1.2+）|
