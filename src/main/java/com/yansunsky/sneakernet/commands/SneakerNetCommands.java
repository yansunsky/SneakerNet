package com.yansunsky.sneakernet.commands;

import com.yansunsky.sneakernet.Config;
import com.yansunsky.sneakernet.SneakerNet;
import com.yansunsky.sneakernet.crypto.KeyManager;
import com.yansunsky.sneakernet.crypto.VoucherCrypto;
import com.yansunsky.sneakernet.data.ItemNbtUtil;
import com.yansunsky.sneakernet.data.PlayerBindData;
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
 *   <li>keygen — 生成/更新本服密钥对，自动信任本服并输出可复制公钥（OP）</li>
 *   <li>showpub — 导出本服公钥到 JSON 文件（OP）</li>
 *   <li>trust &lt;name&gt; &lt;file&gt; — 从 JSON 文件导入可信公钥（OP）</li>
 *   <li>trustkey &lt;name&gt; &lt;base64&gt; — 直接用公钥字符串信任服务器（OP）</li>
 *   <li>untrust &lt;name&gt; — 移除可信服务器（OP）</li>
 *   <li>rename &lt;old&gt; &lt;new&gt; — 重命名可信服务器（OP）</li>
 *   <li>list — 列出所有可信服务器及详细信息（OP）</li>
 *   <li>serverlist — 查看可信服务器名称列表（所有玩家）</li>
 *   <li>bind &lt;name&gt; / bind clear — 绑定/解绑目标服务器（所有玩家）</li>
 *   <li>import [filename] — 从文件导入物品（所有玩家）</li>
 *   <li>help — 显示所有命令说明（所有玩家）</li>
 * </ul>
 */
public class SneakerNetCommands {

    private static final Logger LOGGER = SneakerNet.LOGGER;

    /** keygen 后自动信任本服时使用的默认服务器名 */
    private static final String SELF_SERVER_NAME = "当前服务器";

    public static void register(CommandDispatcher<CommandSourceStack> dispatcher) {
        dispatcher.register(Commands.literal("sneakernet")
                // ├── keygen（仅 OP）
                .then(Commands.literal("keygen")
                        .requires(src -> src.hasPermission(2))
                        .executes(ctx -> executeKeygen(ctx)))
                // ├── showpub（仅 OP）
                .then(Commands.literal("showpub")
                        .requires(src -> src.hasPermission(2))
                        .executes(ctx -> executeShowpub(ctx)))
                // ├── trust（仅 OP）— 从文件导入
                .then(Commands.literal("trust")
                        .requires(src -> src.hasPermission(2))
                        .then(Commands.argument("name", StringArgumentType.word())
                                .then(Commands.argument("file", StringArgumentType.string())
                                        .executes(ctx -> executeTrust(ctx)))))
                // ├── trustkey（仅 OP）— 直接输入公钥信任
                .then(Commands.literal("trustkey")
                        .requires(src -> src.hasPermission(2))
                        .then(Commands.argument("name", StringArgumentType.word())
                                .then(Commands.argument("pubkey", StringArgumentType.greedyString())
                                        .executes(ctx -> executeTrustKey(ctx)))))
                // ├── untrust（仅 OP）
                .then(Commands.literal("untrust")
                        .requires(src -> src.hasPermission(2))
                        .then(Commands.argument("name", StringArgumentType.word())
                                .executes(ctx -> executeUntrust(ctx))))
                // ├── rename（仅 OP）
                .then(Commands.literal("rename")
                        .requires(src -> src.hasPermission(2))
                        .then(Commands.argument("oldname", StringArgumentType.word())
                                .then(Commands.argument("newname", StringArgumentType.word())
                                        .executes(ctx -> executeRename(ctx)))))
                // ├── list（仅 OP）— 详细信息
                .then(Commands.literal("list")
                        .requires(src -> src.hasPermission(2))
                        .executes(ctx -> executeList(ctx)))
                // ├── serverlist（所有玩家）— 仅服务器名
                .then(Commands.literal("serverlist")
                        .requires(src -> src.hasPermission(0))
                        .executes(ctx -> executeServerList(ctx)))
                // ├── bind（所有玩家）
                .then(Commands.literal("bind")
                        .requires(src -> src.hasPermission(0))
                        // /sneakernet bind <name>
                        .then(Commands.argument("name", StringArgumentType.word())
                                .executes(ctx -> executeBind(ctx)))
                        // /sneakernet bind clear
                        .then(Commands.literal("clear")
                                .executes(ctx -> executeBindClear(ctx))))
                // ├── import（所有玩家）
                .then(Commands.literal("import")
                        .requires(src -> src.hasPermission(0))
                        .executes(ctx -> executeImportAll(ctx))  // 不指定文件 → 导入全部
                        .then(Commands.argument("filename", StringArgumentType.string())
                                .executes(ctx -> executeImportOne(ctx))))  // 指定文件名
                // └── help（所有玩家）
                .then(Commands.literal("help")
                        .requires(src -> src.hasPermission(0))
                        .executes(ctx -> executeHelp(ctx)))
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
            // 删除现有密钥文件并重新生成（已有则更新）
            java.nio.file.Path configDir = keyManager.getConfigDir();
            java.nio.file.Files.deleteIfExists(configDir.resolve("local_key.der"));
            java.nio.file.Files.deleteIfExists(configDir.resolve("local_pub.der"));

            keyManager.loadOrGenerate();

            // 生成后直接信任本服，默认名"当前服务器"
            keyManager.trustSelf(SELF_SERVER_NAME);

            String keyId = keyManager.getLocalKeyId();
            String pubKeyBase64 = keyManager.getLocalPublicKeyBase64();

            source.sendSuccess(() -> Component.translatable("sneakernet.keygen.success", keyId)
                    .withStyle(ChatFormatting.GREEN), true);
            source.sendSuccess(() -> Component.translatable("sneakernet.keygen.trusted_self", SELF_SERVER_NAME)
                    .withStyle(ChatFormatting.GRAY), false);

            // 提示行
            source.sendSuccess(() -> Component.translatable("sneakernet.keygen.pubkey_hint")
                    .withStyle(ChatFormatting.YELLOW), false);
            // 可点击复制的公钥行（点击即复制完整 Base64 到剪贴板）
            source.sendSuccess(() -> Component.literal(pubKeyBase64)
                    .withStyle(style -> style
                            .withColor(ChatFormatting.AQUA)
                            .withUnderlined(true)
                            .withClickEvent(new net.minecraft.network.chat.ClickEvent(
                                    net.minecraft.network.chat.ClickEvent.Action.COPY_TO_CLIPBOARD, pubKeyBase64))
                            .withHoverEvent(new net.minecraft.network.chat.HoverEvent(
                                    net.minecraft.network.chat.HoverEvent.Action.SHOW_TEXT,
                                    Component.translatable("sneakernet.keygen.pubkey_copy_tooltip")))), false);

            LOGGER.info("[SneakerNet] 新密钥对已生成并信任本服，KeyID: {}", keyId);
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

    // ─── /sneakernet trustkey <name> <pubkey> ───

    /**
     * 直接输入服务器名与对方公钥（Base64）来信任服务器，无需文件交换。
     */
    private static int executeTrustKey(CommandContext<CommandSourceStack> ctx) {
        CommandSourceStack source = ctx.getSource();
        String name = StringArgumentType.getString(ctx, "name");
        String pubKey = StringArgumentType.getString(ctx, "pubkey");
        KeyManager keyManager = SneakerNet.getKeyManager();
        if (keyManager == null) {
            source.sendFailure(Component.literal("密钥管理器未初始化"));
            return 0;
        }

        try {
            keyManager.addTrustedServerByBase64(name, pubKey);
            source.sendSuccess(() -> Component.translatable("sneakernet.trust.success", name)
                    .withStyle(ChatFormatting.GREEN), true);
            return 1;
        } catch (Exception e) {
            source.sendFailure(Component.translatable("sneakernet.trustkey.failed", e.getMessage())
                    .withStyle(ChatFormatting.RED));
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

    // ─── /sneakernet rename <oldname> <newname> ───

    /**
     * 重命名一个已有的可信服务器。
     */
    private static int executeRename(CommandContext<CommandSourceStack> ctx) {
        CommandSourceStack source = ctx.getSource();
        String oldName = StringArgumentType.getString(ctx, "oldname");
        String newName = StringArgumentType.getString(ctx, "newname");
        KeyManager keyManager = SneakerNet.getKeyManager();
        if (keyManager == null) {
            source.sendFailure(Component.literal("密钥管理器未初始化"));
            return 0;
        }

        try {
            if (keyManager.renameTrustedServer(oldName, newName)) {
                source.sendSuccess(() -> Component.translatable("sneakernet.rename.success", oldName, newName)
                        .withStyle(ChatFormatting.GREEN), true);
                return 1;
            } else {
                source.sendFailure(Component.translatable("sneakernet.rename.not_found", oldName)
                        .withStyle(ChatFormatting.RED));
                return 0;
            }
        } catch (IOException e) {
            source.sendFailure(Component.literal("重命名失败: " + e.getMessage()));
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

    // ─── /sneakernet serverlist ───

    /**
     * 列出所有可信服务器名称（供普通玩家查看，仅展示服务器名）。
     */
    private static int executeServerList(CommandContext<CommandSourceStack> ctx) {
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

        source.sendSuccess(() -> Component.translatable("sneakernet.serverlist.header")
                .withStyle(ChatFormatting.GOLD), false);
        for (var server : servers) {
            source.sendSuccess(() -> Component.literal("  - " + server.name())
                    .withStyle(ChatFormatting.WHITE), false);
        }
        return servers.size();
    }

    // ─── /sneakernet bind <name> ───

    /**
     * 将当前玩家绑定到指定的可信服务器
     * <p>
     * 绑定后，使用 Ticket 导出时只会生成该服务器的凭证。
     * 玩家需要先绑定目标服务器才能使用 Ticket。
     * </p>
     */
    private static int executeBind(CommandContext<CommandSourceStack> ctx) {
        CommandSourceStack source = ctx.getSource();
        ServerPlayer player;
        try {
            player = source.getPlayerOrException();
        } catch (Exception e) {
            source.sendFailure(Component.literal("仅玩家可执行此命令"));
            return 0;
        }

        String targetName = StringArgumentType.getString(ctx, "name");
        KeyManager keyManager = SneakerNet.getKeyManager();
        if (keyManager == null) {
            source.sendFailure(Component.literal("密钥管理器未初始化"));
            return 0;
        }

        // 验证目标服务器名是否存在于可信服务器列表中
        boolean found = false;
        for (KeyManager.TrustedServer server : keyManager.getTrustedServers()) {
            if (server.name().equals(targetName)) {
                found = true;
                break;
            }
        }

        if (!found) {
            source.sendFailure(Component.translatable("sneakernet.bind.not_found", targetName)
                    .withStyle(ChatFormatting.RED));
            return 0;
        }

        // 保存绑定
        PlayerBindData bindData = SneakerNet.getPlayerBindData(source.getServer());
        bindData.setBinding(player.getUUID(), targetName);

        source.sendSuccess(() -> Component.translatable("sneakernet.bind.success", targetName)
                .withStyle(ChatFormatting.GREEN), false);
        return 1;
    }

    // ─── /sneakernet bind clear ───

    /**
     * 清除当前玩家的目标服务器绑定
     */
    private static int executeBindClear(CommandContext<CommandSourceStack> ctx) {
        CommandSourceStack source = ctx.getSource();
        ServerPlayer player;
        try {
            player = source.getPlayerOrException();
        } catch (Exception e) {
            source.sendFailure(Component.literal("仅玩家可执行此命令"));
            return 0;
        }

        PlayerBindData bindData = SneakerNet.getPlayerBindData(source.getServer());
        if (!bindData.hasBinding(player.getUUID())) {
            source.sendSuccess(() -> Component.translatable("sneakernet.bind.not_bound")
                    .withStyle(ChatFormatting.GRAY), false);
            return 0;
        }

        bindData.removeBinding(player.getUUID());
        source.sendSuccess(() -> Component.translatable("sneakernet.bind.cleared")
                .withStyle(ChatFormatting.GREEN), false);
        return 1;
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

    // ─── /sneakernet help ───

    /**
     * 显示所有命令的说明。OP 会额外看到管理员命令。
     */
    private static int executeHelp(CommandContext<CommandSourceStack> ctx) {
        CommandSourceStack source = ctx.getSource();
        boolean isOp = source.hasPermission(2);

        source.sendSuccess(() -> Component.translatable("sneakernet.help.header")
                .withStyle(ChatFormatting.GOLD, ChatFormatting.BOLD), false);

        // 玩家命令
        source.sendSuccess(() -> Component.translatable("sneakernet.help.section_player")
                .withStyle(ChatFormatting.AQUA), false);
        helpLine(source, "/sneakernet help", "sneakernet.help.cmd.help");
        helpLine(source, "/sneakernet serverlist", "sneakernet.help.cmd.serverlist");
        helpLine(source, "/sneakernet bind ", "sneakernet.help.cmd.bind");
        helpLine(source, "/sneakernet bind clear", "sneakernet.help.cmd.bind_clear");
        helpLine(source, "/sneakernet import", "sneakernet.help.cmd.import");

        // 管理员命令
        if (isOp) {
            source.sendSuccess(() -> Component.translatable("sneakernet.help.section_admin")
                    .withStyle(ChatFormatting.LIGHT_PURPLE), false);
            helpLine(source, "/sneakernet keygen", "sneakernet.help.cmd.keygen");
            helpLine(source, "/sneakernet showpub", "sneakernet.help.cmd.showpub");
            helpLine(source, "/sneakernet trust ", "sneakernet.help.cmd.trust");
            helpLine(source, "/sneakernet trustkey ", "sneakernet.help.cmd.trustkey");
            helpLine(source, "/sneakernet rename ", "sneakernet.help.cmd.rename");
            helpLine(source, "/sneakernet untrust ", "sneakernet.help.cmd.untrust");
            helpLine(source, "/sneakernet list", "sneakernet.help.cmd.list");
        }
        return 1;
    }

    /**
     * 输出一行帮助：可点击的命令（点击填入聊天栏）+ 说明文本。
     */
    private static void helpLine(CommandSourceStack source, String command, String descKey) {
        source.sendSuccess(() -> Component.literal(command)
                .withStyle(style -> style
                        .withColor(ChatFormatting.YELLOW)
                        .withClickEvent(new net.minecraft.network.chat.ClickEvent(
                                net.minecraft.network.chat.ClickEvent.Action.SUGGEST_COMMAND, command))
                        .withHoverEvent(new net.minecraft.network.chat.HoverEvent(
                                net.minecraft.network.chat.HoverEvent.Action.SHOW_TEXT,
                                Component.translatable("sneakernet.help.click_to_fill"))))
                .append(Component.literal(" - ").withStyle(ChatFormatting.DARK_GRAY))
                .append(Component.translatable(descKey).withStyle(ChatFormatting.GRAY)), false);
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
