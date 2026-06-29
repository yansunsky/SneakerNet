package com.yansunsky.sneakernet;

import com.mojang.logging.LogUtils;
import com.yansunsky.sneakernet.commands.SneakerNetCommands;
import com.yansunsky.sneakernet.crypto.KeyManager;
import com.yansunsky.sneakernet.crypto.VoucherCrypto;
import com.yansunsky.sneakernet.data.PlayerBindData;
import com.yansunsky.sneakernet.data.VoucherBlacklist;
import com.yansunsky.sneakernet.executor.CryptoExecutor;
import com.yansunsky.sneakernet.items.ModCreativeTabs;
import com.yansunsky.sneakernet.items.ModItems;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.ModContainer;
import net.neoforged.fml.common.Mod;
import net.neoforged.fml.event.lifecycle.FMLCommonSetupEvent;
import net.neoforged.fml.loading.FMLPaths;
import com.yansunsky.sneakernet.data.ItemNbtUtil;
import com.yansunsky.sneakernet.data.Voucher;
import com.yansunsky.sneakernet.items.PackageItem;
import com.yansunsky.sneakernet.net.ImportVoucherPayload;
import com.yansunsky.sneakernet.net.ImportResultPayload;
import com.yansunsky.sneakernet.net.VoucherSyncPayload;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.event.RegisterCommandsEvent;
import net.neoforged.neoforge.event.server.ServerStartingEvent;
import net.neoforged.neoforge.event.server.ServerStoppingEvent;
import net.minecraft.server.MinecraftServer;
import net.neoforged.neoforge.network.PacketDistributor;
import net.neoforged.neoforge.network.event.RegisterPayloadHandlersEvent;
import net.neoforged.neoforge.network.handling.IPayloadContext;
import net.neoforged.neoforge.network.registration.PayloadRegistrar;
import org.slf4j.Logger;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.ItemStack;

/**
 * SneakerNet — 离线凭证跨服物品传送 Mod 主类 (v0.5 ECC)
 * <p>
 * 核心机制：
 *   - 玩家使用 TicketItem 导出容器内物品为加密凭证文件（.json）
 *   - 玩家手动将文件复制到目标服务器
 *   - 在目标服务器上使用 PackageItem 导入，验证并恢复物品
 *   - 无需服务器间实时通讯
 * </p>
 *
 * 安全模型（v0.5 — ECC P-256 非对称加密）：
 *   - ECDH 密钥协商（临时密钥对 → 前向安全）
 *   - ECDSA 数字签名（SHA256withECDSA → 非否认性）
 *   - AES-256-GCM 对称加密（HKDF 派生密钥）
 *   - SQLite 黑名单（WAL 模式 → 防双花）
 *   - 玩家 UUID 绑定 + TTL 过期保护
 */
@Mod(SneakerNet.MOD_ID)
public class SneakerNet {
    public static final String MOD_ID = "sneakernet";
    public static final String MOD_NAME = "SneakerNet";
    public static final Logger LOGGER = LogUtils.getLogger();

    // 全局组件实例
    private static KeyManager keyManager;
    private static VoucherBlacklist blacklist;
    private static CryptoExecutor cryptoExecutor;

    public SneakerNet(IEventBus modEventBus, ModContainer modContainer) {
        // 注册物品
        ModItems.register(modEventBus);

        // 注册创造物品栏
        ModCreativeTabs.register(modEventBus);

        // 注册配置
        Config.register(modContainer);

        // 注册 mod 生命周期事件
        modEventBus.addListener(this::onCommonSetup);

        // 注册网络包处理器
        modEventBus.addListener(this::onRegisterPayloads);

        // 注册 NeoForge 事件（命令、服务器生命周期）
        NeoForge.EVENT_BUS.register(this);

        LOGGER.info("[SneakerNet] 模组初始化完成（ECC P-256 非对称加密模式）");
    }

    /**
     * 通用设置：仅创建与世界无关的客户端目录。
     * <p>
     * 注意：密钥管理器、黑名单、密码学线程池等<strong>服务器侧</strong>资源
     * 不在此初始化，而是绑定到服务器生命周期（{@link #onServerStarting}），
     * 因为单人模式下每次进入/退出存档都会启动/停止一个集成服务器，
     * 而 {@link FMLCommonSetupEvent} 在整个游戏进程中只触发一次。
     * </p>
     */
    private void onCommonSetup(FMLCommonSetupEvent event) {
        // 确保客户端目录存在（与具体世界无关）
        try {
            Path vouchersDir = FMLPaths.GAMEDIR.get().resolve("sneakernet/vouchers/");
            Files.createDirectories(vouchersDir);
            Path redeemedDir = FMLPaths.GAMEDIR.get().resolve("sneakernet/redeemed/");
            Files.createDirectories(redeemedDir);
            LOGGER.info("[SneakerNet] 客户端目录已就绪");
        } catch (Exception e) {
            LOGGER.error("[SneakerNet] 创建客户端目录失败", e);
        }
    }

    /**
     * 初始化服务器侧资源：密码学线程池、密钥管理器、黑名单。
     * <p>
     * 可重复调用（幂等）：线程池若已关闭会重建，密钥/黑名单会重新加载，
     * 以支持单人模式下反复开关存档。
     * </p>
     */
    private void initServerResources() {
        // [1] 密码学线程池：不存在或已关闭则重建
        if (cryptoExecutor == null || cryptoExecutor.isShutdown()) {
            cryptoExecutor = new CryptoExecutor();
            LOGGER.info("[SneakerNet] 密码学线程池已就绪");
        }

        // [2] 密钥管理器（密钥存于全局 config/sneakernet/，与世界无关）
        Path configDir = FMLPaths.CONFIGDIR.get().resolve("sneakernet");
        LOGGER.info("[SneakerNet] 配置目录: {}", configDir.toAbsolutePath());
        keyManager = new KeyManager(configDir);
        try {
            keyManager.loadOrGenerate();
            LOGGER.info("[SneakerNet] 密钥管理器初始化成功");
        } catch (Exception e) {
            LOGGER.error("[SneakerNet] 密钥管理器初始化失败！", e);
        }

        // [3] 黑名单数据库
        blacklist = new VoucherBlacklist(configDir);
        try {
            blacklist.initialize();
            blacklist.cleanExpired();
            LOGGER.info("[SneakerNet] 黑名单数据库初始化成功");
        } catch (Exception e) {
            LOGGER.error("[SneakerNet] 黑名单数据库初始化失败！", e);
            blacklist = null; // 置 null 防止后续误用无效对象
        }
    }

    /**
     * 服务器启动事件：初始化服务器侧资源（线程池、密钥、黑名单）。
     * <p>
     * 单人模式下每次进入存档都会触发，确保资源是新鲜可用的。
     * </p>
     */
    @SubscribeEvent
    public void onServerStarting(ServerStartingEvent event) {
        LOGGER.info("[SneakerNet] 服务器启动中，初始化资源...");
        initServerResources();
    }

    /**
     * 服务器停止事件：关闭资源并清空引用。
     * <p>
     * 单人模式下退出存档时触发。引用置 null，再次进入存档时由
     * {@link #onServerStarting} 重建，避免使用已终止的线程池/已关闭的数据库。
     * </p>
     */
    @SubscribeEvent
    public void onServerStopping(ServerStoppingEvent event) {
        LOGGER.info("[SneakerNet] 服务器停止中，清理资源...");

        if (blacklist != null) {
            try {
                blacklist.close();
            } catch (Exception e) {
                LOGGER.error("[SneakerNet] 关闭黑名单数据库失败", e);
            }
            blacklist = null;
        }

        if (cryptoExecutor != null) {
            cryptoExecutor.shutdown();
            cryptoExecutor = null;
        }

        keyManager = null;
    }

    /**
     * 注册命令
     */
    @SubscribeEvent
    public void onRegisterCommands(RegisterCommandsEvent event) {
        SneakerNetCommands.register(event.getDispatcher());
    }

    /**
     * 注册网络载荷（CustomPacketPayload）处理器
     * <p>
     * 在 Mod 事件总线上监听 RegisterPayloadHandlersEvent，
     * 注册服务端→客户端的 Voucher 同步数据包。
     * </p>
     */
    private void onRegisterPayloads(RegisterPayloadHandlersEvent event) {
        PayloadRegistrar registrar = event.registrar(MOD_ID);
        registrar.playToClient(
                VoucherSyncPayload.TYPE,
                VoucherSyncPayload.STREAM_CODEC,
                ClientPayloadHandler::handleVoucherSync
        );
        registrar.playToClient(
                ImportResultPayload.TYPE,
                ImportResultPayload.STREAM_CODEC,
                ClientPayloadHandler::handleImportResult
        );
        registrar.playToServer(
                ImportVoucherPayload.TYPE,
                ImportVoucherPayload.STREAM_CODEC,
                ServerPayloadHandler::handleImportVoucher
        );
        LOGGER.info("[SneakerNet] 网络载荷处理器已注册");
    }

    /**
     * 客户端网络包处理器
     */
    private static class ClientPayloadHandler {
        /**
         * 处理 VoucherSyncPayload：将收到的 JSON 保存到客户端本地目录
         */
        public static void handleVoucherSync(VoucherSyncPayload payload, IPayloadContext context) {
            context.enqueueWork(() -> {
                try {
                    Path vouchersDir = FMLPaths.GAMEDIR.get().resolve("sneakernet/vouchers/");
                    Files.createDirectories(vouchersDir);
                    Path targetFile = vouchersDir.resolve(payload.suggestedFileName());
                    Files.writeString(targetFile, payload.voucherJson());
                    LOGGER.info("[SneakerNet] 客户端已保存凭证：{} ({})",
                            payload.suggestedFileName(), targetFile);
                } catch (IOException e) {
                    LOGGER.error("[SneakerNet] 客户端保存凭证失败：{}", e.getMessage());
                }
            });
        }

        /**
         * 处理 ImportResultPayload：导入成功后清理本地凭证文件
         */
        public static void handleImportResult(ImportResultPayload payload, IPayloadContext context) {
            context.enqueueWork(() -> {
                try {
                    Path vouchersDir = FMLPaths.GAMEDIR.get().resolve("sneakernet/vouchers/");
                    Path sourceFile = vouchersDir.resolve(payload.fileName());
                    if (!Files.exists(sourceFile)) return;

                    if (payload.success()) {
                        // 导入成功 → 移到 redeemed/ 目录
                        Path redeemedDir = FMLPaths.GAMEDIR.get().resolve("sneakernet/redeemed/");
                        Files.createDirectories(redeemedDir);
                        Files.move(sourceFile, redeemedDir.resolve(payload.fileName()));
                        LOGGER.info("[SneakerNet] 客户端已核销凭证: {}", payload.fileName());
                    }
                    // 导入失败则不删除，玩家可以重试
                } catch (IOException e) {
                    LOGGER.error("[SneakerNet] 客户端核销凭证失败: {}", e.getMessage());
                }
            });
        }
    }

    /**
     * 服务端网络包处理器
     */
    private static class ServerPayloadHandler {
        /**
         * 处理 ImportVoucherPayload：解析凭证 JSON 并执行导入
         */
        public static void handleImportVoucher(ImportVoucherPayload payload, IPayloadContext context) {
            context.enqueueWork(() -> {
                ServerPlayer player = (ServerPlayer) context.player();
                if (player == null) return;

                boolean success = false;
                try {
                    KeyManager km = getKeyManager();
                    VoucherBlacklist bl = getBlacklist();
                    if (km == null || bl == null) {
                        player.sendSystemMessage(Component.literal("系统未初始化"));
                        return;
                    }

                    Voucher voucher = Voucher.fromJson(payload.voucherJson());

                    VoucherCrypto.DecryptResult result = VoucherCrypto.decryptAndVerify(
                            km.getLocalKeyPair(), voucher, km,
                            player.getUUID(), bl,
                            Config.REQUIRE_PLAYER_MATCH.get()
                    );

                    if (result.success()) {
                        ItemNbtUtil.ContainerNbtData containerData =
                                ItemNbtUtil.deserializeContainerFromBytes(
                                        result.plaintextNbt(), player.registryAccess());
                        ItemStack packageStack = PackageItem.createPackage(
                                containerData, voucher.issuerKeyId(),
                                voucher.playerUuid(), voucher.timestamp(),
                                player.registryAccess());
                        if (!player.getInventory().add(packageStack)) {
                            player.drop(packageStack, false);
                        }
                        player.sendSystemMessage(Component.translatable(
                                "sneakernet.import.result", 1, 0));
                        LOGGER.info("[SneakerNet] 客户端导入成功: {} ({})",
                                payload.fileName(), player.getName().getString());
                        success = true;
                    } else {
                        player.sendSystemMessage(Component.literal(
                                "导入失败: " + result.failureReason()));
                    }
                } catch (Exception e) {
                    LOGGER.error("[SneakerNet] 客户端导入异常", e);
                    player.sendSystemMessage(Component.literal(
                            "导入失败: " + e.getMessage()));
                }

                // 发送结果回执给客户端（客户端据此清理本地文件）
                PacketDistributor.sendToPlayer(player,
                        new ImportResultPayload(payload.fileName(), success));
            });
        }
    }

    // ─── 全局访问器 ───

    /**
     * 获取密钥管理器
     */
    public static KeyManager getKeyManager() {
        return keyManager;
    }

    /**
     * 获取黑名单管理器
     */
    public static VoucherBlacklist getBlacklist() {
        return blacklist;
    }

    /**
     * 获取密码学执行器
     */
    public static CryptoExecutor getCryptoExecutor() {
        return cryptoExecutor;
    }

    /**
     * 获取玩家目标服务器绑定数据
     * <p>
     * 数据持久化在 world/data/sneakernet_player_binds.dat 中，
     * 记录每个玩家绑定的目标服务器名。
     * </p>
     *
     * @param server Minecraft 服务器实例
     * @return PlayerBindData 实例
     */
    public static PlayerBindData getPlayerBindData(MinecraftServer server) {
        return PlayerBindData.get(server);
    }
}
