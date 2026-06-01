package com.yansunsky.sneakernet.commands;

import com.yansunsky.sneakernet.Config;
import com.yansunsky.sneakernet.SneakerNet;
import com.yansunsky.sneakernet.crypto.KeyManager;
import com.yansunsky.sneakernet.crypto.VoucherCrypto;
import com.yansunsky.sneakernet.data.ItemNbtUtil;
import com.yansunsky.sneakernet.data.Voucher;
import com.yansunsky.sneakernet.data.VoucherBlacklist;
import com.yansunsky.sneakernet.items.ModItems;
import com.yansunsky.sneakernet.items.PackageItem;
import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.context.CommandContext;
import net.minecraft.ChatFormatting;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.ItemStack;
import net.neoforged.fml.loading.FMLPaths;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.stream.Stream;

/**
 * /sneakernet 命令树
 * <p>
 * 子命令：
 * <ul>
 *   <li>keygen — 生成服务器密钥对（OP）</li>
 *   <li>showpub — 保存公钥到 JSON 文件（所有玩家）</li>
 *   <li>trust &lt;name&gt; &lt;file&gt; — 从 JSON 导入可信公钥（OP）</li>
 *   <li>untrust &lt;name&gt; — 移除可信服务器（OP）</li>
 *   <li>list — 列出所有可信服务器（OP）</li>
 *   <li>import [filename] — 从文件导入物品（所有玩家）</li>
 * </ul>
 */
public class SneakerNetCommands {

    private static final Logger LOGGER = SneakerNet.LOGGER;

    public static void register(CommandDispatcher<CommandSourceStack> dispatcher) {
        dispatcher.register(Commands.literal("sneakernet")
                // ├── keygen（仅 OP）
                .then(Commands.literal("keygen")
                        .requires(src -> src.hasPermission(2))
                        .executes(ctx -> executeKeygen(ctx)))
                // ├── showpub（所有玩家）
                .then(Commands.literal("showpub")
                        .requires(src -> src.hasPermission(0))
                        .executes(ctx -> executeShowpub(ctx)))
                // ├── trust（仅 OP）
                .then(Commands.literal("trust")
                        .requires(src -> src.hasPermission(2))
                        .then(Commands.argument("name", StringArgumentType.word())
                                .then(Commands.argument("file", StringArgumentType.string())
                                        .executes(ctx -> executeTrust(ctx)))))
                // ├── untrust（仅 OP）
                .then(Commands.literal("untrust")
                        .requires(src -> src.hasPermission(2))
                        .then(Commands.argument("name", StringArgumentType.word())
                                .executes(ctx -> executeUntrust(ctx))))
                // ├── list（仅 OP）
                .then(Commands.literal("list")
                        .requires(src -> src.hasPermission(2))
                        .executes(ctx -> executeList(ctx)))
                // └── import（所有玩家）
                .then(Commands.literal("import")
                        .requires(src -> src.hasPermission(0))
                        .executes(ctx -> executeImportAll(ctx))  // 不指定文件 → 导入全部
                        .then(Commands.argument("filename", StringArgumentType.string())
                                .executes(ctx -> executeImportOne(ctx))))  // 指定文件名
        );
    }

    // ─── /sneakernet keygen ───

    private static int executeKeygen(CommandContext<CommandSourceStack> ctx) {
        CommandSourceStack source = ctx.getSource();
        KeyManager keyManager = SneakerNet.getKeyManager();
        if (keyManager == null) {
            source.sendFailure(Component.literal("密钥管理器未初始化"));
            return 0;
        }

        try {
            // 删除现有密钥文件并重新生成
            java.nio.file.Path configDir = keyManager.getConfigDir();
            java.nio.file.Files.deleteIfExists(configDir.resolve("local_key.der"));
            java.nio.file.Files.deleteIfExists(configDir.resolve("local_pub.der"));

            keyManager.loadOrGenerate();

            String keyId = KeyManager.computeKeyId(keyManager.getPublicKey());
            source.sendSuccess(() -> Component.translatable("sneakernet.keygen.success", keyId)
                    .withStyle(ChatFormatting.GREEN), true);
            LOGGER.info("[SneakerNet] 新密钥对已生成，KeyID: {}", keyId);
            return 1;
        } catch (Exception e) {
            source.sendFailure(Component.literal("生成密钥对失败: " + e.getMessage()));
            return 0;
        }
    }

    // ─── /sneakernet showpub ───

    private static int executeShowpub(CommandContext<CommandSourceStack> ctx) {
        CommandSourceStack source = ctx.getSource();
        KeyManager keyManager = SneakerNet.getKeyManager();
        if (keyManager == null) {
            source.sendFailure(Component.literal("密钥管理器未初始化"));
            return 0;
        }

        try {
            String serverName = source.getServer().getServerModName();
            Path pubJsonPath = keyManager.exportPublicKeyJson(serverName);

            source.sendSuccess(() -> Component.translatable("sneakernet.showpub.success", pubJsonPath.toString())
                    .withStyle(ChatFormatting.GREEN), false);
            source.sendSuccess(() -> Component.translatable("sneakernet.showpub.fingerprint", keyManager.getLocalFingerprint())
                    .withStyle(ChatFormatting.GRAY), false);
            return 1;
        } catch (Exception e) {
            source.sendFailure(Component.literal("导出公钥失败: " + e.getMessage()));
            return 0;
        }
    }

    // ─── /sneakernet trust <name> <file> ───

    private static int executeTrust(CommandContext<CommandSourceStack> ctx) {
        CommandSourceStack source = ctx.getSource();
        String name = StringArgumentType.getString(ctx, "name");
        String fileStr = StringArgumentType.getString(ctx, "file");
        KeyManager keyManager = SneakerNet.getKeyManager();
        if (keyManager == null) {
            source.sendFailure(Component.literal("密钥管理器未初始化"));
            return 0;
        }

        // 解析文件路径（相对于 config/sneakernet/）
        Path pubJsonFile = keyManager.getConfigDir().resolve(fileStr);
        if (!Files.exists(pubJsonFile)) {
            source.sendFailure(Component.translatable("sneakernet.trust.file_not_found", fileStr)
                    .withStyle(ChatFormatting.RED));
            return 0;
        }

        try {
            keyManager.addTrustedServer(name, pubJsonFile);
            source.sendSuccess(() -> Component.translatable("sneakernet.trust.success", name)
                    .withStyle(ChatFormatting.GREEN), true);
            return 1;
        } catch (Exception e) {
            source.sendFailure(Component.literal("添加可信服务器失败: " + e.getMessage()));
            return 0;
        }
    }

    // ─── /sneakernet untrust <name> ───

    private static int executeUntrust(CommandContext<CommandSourceStack> ctx) {
        CommandSourceStack source = ctx.getSource();
        String name = StringArgumentType.getString(ctx, "name");
        KeyManager keyManager = SneakerNet.getKeyManager();
        if (keyManager == null) {
            source.sendFailure(Component.literal("密钥管理器未初始化"));
            return 0;
        }

        try {
            if (keyManager.removeTrustedServer(name)) {
                source.sendSuccess(() -> Component.translatable("sneakernet.untrust.success", name)
                        .withStyle(ChatFormatting.GREEN), true);
                return 1;
            } else {
                source.sendFailure(Component.translatable("sneakernet.untrust.not_found", name)
                        .withStyle(ChatFormatting.RED));
                return 0;
            }
        } catch (IOException e) {
            source.sendFailure(Component.literal("移除可信服务器失败: " + e.getMessage()));
            return 0;
        }
    }

    // ─── /sneakernet list ───

    private static int executeList(CommandContext<CommandSourceStack> ctx) {
        CommandSourceStack source = ctx.getSource();
        KeyManager keyManager = SneakerNet.getKeyManager();
        if (keyManager == null) {
            source.sendFailure(Component.literal("密钥管理器未初始化"));
            return 0;
        }

        var servers = keyManager.getTrustedServers();
        if (servers.isEmpty()) {
            source.sendSuccess(() -> Component.translatable("sneakernet.list.empty")
                    .withStyle(ChatFormatting.GRAY), false);
            return 0;
        }

        for (var server : servers) {
            source.sendSuccess(() -> Component.literal(
                    "  " + server.name() + " (KeyID: " + server.keyId() +
                            ", Fingerprint: " + server.fingerprint() + ")"
            ).withStyle(ChatFormatting.WHITE), false);
        }

        return servers.size();
    }

    // ─── /sneakernet import（全部）───

    private static int executeImportAll(CommandContext<CommandSourceStack> ctx) {
        CommandSourceStack source = ctx.getSource();
        ServerPlayer player;
        try {
            player = source.getPlayerOrException();
        } catch (Exception e) {
            source.sendFailure(Component.literal("仅玩家可执行此命令"));
            return 0;
        }

        // 扫描客户端目录下所有凭证文件
        Path vouchersDir = FMLPaths.GAMEDIR.get().resolve("sneakernet/vouchers/");
        if (!Files.exists(vouchersDir)) {
            source.sendSuccess(() -> Component.translatable("sneakernet.import.no_vouchers")
                    .withStyle(ChatFormatting.GRAY), false);
            return 0;
        }

        List<Path> voucherFiles;
        try (Stream<Path> files = Files.list(vouchersDir)) {
            voucherFiles = files.filter(f -> f.toString().endsWith(".json")).toList();
        } catch (IOException e) {
            source.sendFailure(Component.literal("扫描凭证目录失败: " + e.getMessage()));
            return 0;
        }

        if (voucherFiles.isEmpty()) {
            source.sendSuccess(() -> Component.translatable("sneakernet.import.no_vouchers")
                    .withStyle(ChatFormatting.GRAY), false);
            return 0;
        }

        doImportVouchers(player, voucherFiles);
        return voucherFiles.size();
    }

    // ─── /sneakernet import <filename> ───

    private static int executeImportOne(CommandContext<CommandSourceStack> ctx) {
        CommandSourceStack source = ctx.getSource();
        ServerPlayer player;
        try {
            player = source.getPlayerOrException();
        } catch (Exception e) {
            source.sendFailure(Component.literal("仅玩家可执行此命令"));
            return 0;
        }

        String filename = StringArgumentType.getString(ctx, "filename");
        Path voucherFile = FMLPaths.GAMEDIR.get().resolve("sneakernet/vouchers/" + filename);

        if (!Files.exists(voucherFile)) {
            source.sendFailure(Component.translatable("sneakernet.import.file_not_found", filename)
                    .withStyle(ChatFormatting.RED));
            return 0;
        }

        doImportVouchers(player, List.of(voucherFile));
        return 1;
    }

    // ─── 导入核心逻辑 ───

    private static void doImportVouchers(ServerPlayer player, List<Path> voucherFiles) {
        KeyManager keyManager = SneakerNet.getKeyManager();
        VoucherBlacklist blacklist = SneakerNet.getBlacklist();
        MinecraftServer server = player.getServer();
        boolean requirePlayerMatch = Config.REQUIRE_PLAYER_MATCH.get();

        if (keyManager == null || blacklist == null) {
            player.sendSystemMessage(Component.literal("系统未初始化").withStyle(ChatFormatting.RED));
            return;
        }

        // 异步执行解密
        SneakerNet.getCryptoExecutor().supplyAsync(() -> {
            int successCount = 0;
            int failCount = 0;
            List<String> failReasons = new ArrayList<>();
            List<ItemStack> packages = new ArrayList<>();

            for (Path file : voucherFiles) {
                try {
                    Voucher voucher = Voucher.fromFile(file);
                    VoucherCrypto.DecryptResult result = VoucherCrypto.decryptAndVerify(
                            keyManager.getLocalKeyPair(),
                            voucher,
                            keyManager,
                            player.getUUID(),
                            blacklist,
                            requirePlayerMatch
                    );

                    if (result.success()) {
                        // 创建 Package 物品
                        ItemNbtUtil.ContainerNbtData containerData =
                                ItemNbtUtil.deserializeContainerFromBytes(
                                        result.plaintextNbt(),
                                        player.registryAccess()
                                );

                        ItemStack packageStack = PackageItem.createPackage(
                                containerData,
                                voucher.issuerKeyId(),
                                voucher.playerUuid(),
                                voucher.timestamp(),
                                player.registryAccess()
                        );
                        packages.add(packageStack);
                        successCount++;

                        // 移动凭证到已核销目录
                        try {
                            Path redeemedDir = FMLPaths.GAMEDIR.get().resolve("sneakernet/redeemed/");
                            Files.createDirectories(redeemedDir);
                            Files.move(file, redeemedDir.resolve(file.getFileName()));
                        } catch (IOException e) {
                            LOGGER.warn("[SneakerNet] 移动已核销凭证失败（不影响功能）: {}", file, e);
                        }

                    } else {
                        failCount++;
                        failReasons.add(file.getFileName() + ": " + result.failureReason());
                    }
                } catch (Exception e) {
                    failCount++;
                    failReasons.add(file.getFileName() + ": 解析失败");
                    LOGGER.error("[SneakerNet] 导入凭证失败: {}", file, e);
                }
            }

            return new ImportResult(successCount, failCount, failReasons, packages);

        }).thenAccept(result -> {
            // 切回主线程
            server.execute(() -> {
                // 发放 Package 物品
                for (ItemStack pkg : result.packages()) {
                    if (!player.getInventory().add(pkg)) {
                        // 背包满了 → 掉落到玩家位置
                        player.drop(pkg, false);
                    }
                }

                // 显示结果
                player.sendSystemMessage(Component.translatable(
                        "sneakernet.import.result",
                        result.successCount(), result.failCount()
                ).withStyle(ChatFormatting.GREEN));

                if (!result.failReasons().isEmpty()) {
                    for (String reason : result.failReasons()) {
                        player.sendSystemMessage(Component.literal("  ✗ " + reason)
                                .withStyle(ChatFormatting.RED));
                    }
                }
            });
        });
    }

    // ─── 导入结果 ───

    record ImportResult(
            int successCount,
            int failCount,
            List<String> failReasons,
            List<ItemStack> packages
    ) {
    }
}
