# SneakerNet

An experimental Minecraft 1.21.1 NeoForge mod for transferring items across servers.

> 简体中文文档见 [README.md](README.md)

## Overview

SneakerNet is a secure mod that moves items between Minecraft servers using a physical-medium ("SneakerNet") approach.
It uses **ECC P-256 asymmetric cryptography** (ECDH key agreement + ECDSA digital signatures + AES-256-GCM encryption) to guarantee authentication, tamper resistance, and replay protection for item transfers.

Items are exported as encrypted vouchers (JSON format). Players manually carry the files across servers and import them — no real-time network connection between servers is required.

## Version

- **v0.5.0** — ECC P-256 asymmetric encryption upgrade
- **Mod ID**: `sneakernet`
- **Runtime**: NeoForge 21.1.219+ / Minecraft 1.21.1 / Java 21

## Workflow

The core idea of SneakerNet is "mutually trusting each other's public keys". Admins first generate keys on their respective servers and exchange public keys; players then bind a target server and use the Ticket to transfer items.

### Simplified public-key exchange (recommended: paste the key directly)

```
Server A                                 Server B
  │                                        │
  ├─ /sneakernet keygen                    ├─ /sneakernet keygen
  │   → generate keys, auto-trust self     │   → generate keys, auto-trust self
  │   → print a click-to-copy public key   │   → print a click-to-copy public key
  │                                        │
  │   (send A's public key to B's admin) ─→ │
  │ ←─── (send B's public key to A's admin) │
  │                                        │
  ├─ /sneakernet trustkey B <B's key>      ├─ /sneakernet trustkey A <A's key>
  │                                        │
  ▼                                        ▼
Player on A:                             Player on B:
  ├─ /sneakernet serverlist  view trusted servers
  ├─ /sneakernet bind B      bind target server
  ├─ right-click a container with a Ticket → encrypted export to voucher.json
  │   (auto-synced to the client)
  │   (send the json file to yourself on B) ─→
  │                                        ├─ /sneakernet import  → redeem package item
```

> The public key printed by `keygen` is **click-to-copy** in chat. Send it to the other admin, who completes trust with a single `trustkey` command — no file transfer needed.
> If you prefer files, use `showpub` to export the public key JSON and have the other side import it with `trust <name> <file>`.

## Commands

> Not sure how a command works? Type `/sneakernet help` anytime (commands are clickable to fill into the chat bar).

### Player commands

| Command | Description | Permission |
|---------|-------------|------------|
| `/sneakernet help` | Show descriptions for all commands | All players |
| `/sneakernet serverlist` | View the list of trusted server names | All players |
| `/sneakernet bind <name>` | Bind a target server (required before using a Ticket) | All players |
| `/sneakernet bind clear` | Clear the target server binding | All players |
| `/sneakernet import` | Scan and upload all local client vouchers | All players |
| `/sneakernet import <filename>` | Upload a specific voucher file | All players |

### Admin commands

| Command | Description | Permission |
|---------|-------------|------------|
| `/sneakernet keygen` | Generate/update this server's key pair, auto-trust self, and print a copyable public key | OP (level 2) |
| `/sneakernet pubkey` | Show the current public key in chat, click to copy (does not regenerate the key) | OP (level 2) |
| `/sneakernet showpub` | Export this server's public key to a JSON file (for file exchange) | OP (level 2) |
| `/sneakernet trust <name> <file>` | Import trust from a public key JSON file | OP (level 2) |
| `/sneakernet trustkey <name> <pubkey>` | Trust by pasting the other side's public key (Base64) directly | OP (level 2) |
| `/sneakernet rename <old> <new>` | Rename a trusted server | OP (level 2) |
| `/sneakernet untrust <name>` | Remove a trusted server | OP (level 2) |
| `/sneakernet list` | List all trusted servers with details (KeyID, fingerprint) | OP (level 2) |

### Admin quick start

1. `/sneakernet keygen` — Generate this server's keys. It auto-trusts the local server (default name "当前服务器" / "Current Server") and prints a copyable public key in chat.
2. Send your public key to the other server's admin, and ask for theirs in return.
3. Trust the other side's public key (pick one):
   - **Option A · Trust by command**: `/sneakernet trustkey <their server name> <their public key>` — done in one line.
   - **Option B · JSON file import**: see "Manual JSON import" below.
4. Verify with `/sneakernet list`, and rename with `/sneakernet rename` if needed.

#### Manual JSON import (when the key is too long to send, or command blocks are disabled)

An ECC public key Base64 string is fairly long, and **a single chat message is capped at 256 characters, so you cannot paste a full public key directly**.
If you also have no command block available (some servers disable command blocks — see the note below), exchange files instead:

1. **The exporting side** runs `/sneakernet showpub`, which writes a public key file at `config/sneakernet/local_pub.json`, shaped like:
   ```json
   {
     "serverName": "...",
     "keyId": "...",
     "publicKeyBase64": "<full public key, not subject to the 256-char chat limit>",
     "fingerprint": "..."
   }
   ```
2. Send this `local_pub.json` to the other admin through any out-of-band channel (chat app / email / cloud drive / USB stick, etc.).
3. **The receiving side** places the received file into their own `config/sneakernet/` directory. Renaming it to indicate its source is recommended, e.g. `serverA_pub.json`.
4. **The receiving side** runs `/sneakernet trust <their server name> <filename>`, for example:
   ```
   /sneakernet trust ServerA serverA_pub.json
   ```
   > `<filename>` is a path relative to the `config/sneakernet/` directory — just the file name is enough.
5. After both sides complete the steps above, confirm with `/sneakernet list` that the other server now appears in the trusted list.

> About command blocks: OP commands like `trustkey`/`trust` **can be run from a command block** — command blocks run at permission level 2 (equivalent to OP) by default, and the command block input field has no 256-character chat limit, so it can hold an entire long public key.
> The prerequisite is that the server **has command blocks enabled** (`enable-command-block=true` in `server.properties`). If command blocks are disabled, use the "Manual JSON import" method above.

### Player quick start

1. `/sneakernet serverlist` — See which target servers this server already trusts.
2. `/sneakernet bind <target server name>` — Bind the server you want to ship to.
3. Right-click a container full of items with a **Ticket** to export a voucher (the file syncs to your client at `.minecraft/sneakernet/vouchers/`).
4. Carry the voucher file to the target server (just log in with the same client), then run `/sneakernet import` to redeem a **Package**. Right-click to place it and restore the container and its items.

## Items

| Item | How to obtain | Use |
|------|---------------|-----|
| **SneakerNet Ticket** | Craft: 8 Paper + 1 Ink Sac → 8 | Right-click a container to encrypt and export its contents as a JSON voucher |
| **SneakerNet Package** | Obtained via `/sneakernet import` | Right-click to place and restore the container contents |

## Security mechanisms

| Mechanism | Implementation | Description |
|-----------|----------------|-------------|
| Asymmetric encryption | ECC P-256 (secp256r1) | Independent key pair per server; no shared master key |
| Forward secrecy | Ephemeral ECC key pair per export | A leaked private key does not compromise historical vouchers |
| Digital signature | ECDSA (SHA256withECDSA) | Anti-tamper, anti-forgery |
| Data encryption | AES-256-GCM + HKDF key derivation | Container NBT encrypted in transit |
| Double-spend protection | JSON file blacklist (`blacklist.json`) | A redeemed voucher cannot be used again |
| Player binding | UUID match (optional) | The exporter and importer can be required to match |
| TTL expiry | Unix timestamp comparison | Vouchers expire automatically after timeout |

## Build

```bash
./gradlew build
```

Build artifact: `build/libs/sneakernet-0.5.0.jar`

## Configuration

Server config file: `sneakernet-server.toml` (generated under `world/serverconfig/`)

| Option | Default | Description |
|--------|---------|-------------|
| `voucherTtlHours` | 24 | Voucher validity period (hours) |
| `maxItemsPerVoucher` | 1728 | Max item count per voucher |
| `requirePlayerMatch` | true | Whether the user must match the exporter |
| `debugMode` | false | Debug mode |

## Directory layout

```
config/sneakernet/                    # Server-side keys & config directory
├── local_key.der                     # Local ECC private key (PKCS#8 DER)
├── local_pub.der                     # Local ECC public key (X.509 DER)
├── local_pub.json                    # Exported public key file (for exchange)
├── trusted_keys.json                 # Trusted server public key list
└── blacklist.json                    # Redeemed voucher blacklist

.minecraft/sneakernet/                # Client-side directory
├── vouchers/                         # Vouchers pending import
└── redeemed/                         # Redeemed voucher files
```

## Network protocol

| Payload | Direction | Description |
|---------|-----------|-------------|
| `VoucherSyncPayload` | S2C | Sync the voucher JSON to the client after a successful export |
| `ImportVoucherPayload` | C2S | Client uploads voucher JSON to the server |
| `ImportResultPayload` | S2C | Server returns the import result, triggering client-side redemption |

## Dependencies

- **Pure Java standard library + Gson** — zero external runtime dependencies
- NeoForge built-in API — all mod registration, events, and networking use native NeoForge APIs

## License

This project is licensed under GPLv3.

