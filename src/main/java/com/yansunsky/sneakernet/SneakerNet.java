package com.yansunsky.sneakernet;

import com.mojang.logging.LogUtils;
import com.yansunsky.sneakernet.commands.SneakerNetCommands;
import com.yansunsky.sneakernet.crypto.KeyManager;
import com.yansunsky.sneakernet.data.PlayerBindData;
import com.yansunsky.sneakernet.data.VoucherBlacklist;
import com.yansunsky.sneakernet.executor.CryptoExecutor;
import com.yansunsky.sneakernet.items.ModItems;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.ModContainer;
import net.neoforged.fml.common.Mod;
import net.neoforged.fml.event.lifecycle.FMLCommonSetupEvent;
import net.neoforged.fml.loading.FMLPaths;
import com.yansunsky.sneakernet.net.VoucherSyncPayload;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.event.RegisterCommandsEvent;
import net.neoforged.neoforge.event.server.ServerStartingEvent;
import net.neoforged.neoforge.event.server.ServerStoppingEvent;
import net.minecraft.server.MinecraftServer;
import net.neoforged.neoforge.network.event.RegisterPayloadHandlersEvent;
import net.neoforged.neoforge.network.handling.IPayloadContext;
import net.neoforged.neoforge.network.registration.PayloadRegistrar;
import org.slf4j.Logger;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

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
     * 通用设置：初始化密钥管理器、黑名单、线程池
     */
    private void onCommonSetup(FMLCommonSetupEvent event) {
        // [1] 初始化密码学线程池
        cryptoExecutor = new CryptoExecutor();

        // [2] 初始化密钥管理器
        Path configDir = FMLPaths.CONFIGDIR.get().resolve("sneakernet");
        keyManager = new KeyManager(configDir);

        try {
            keyManager.loadOrGenerate();
            LOGGER.info("[SneakerNet] 密钥管理器初始化成功");
        } catch (Exception e) {
            LOGGER.error("[SneakerNet] 密钥管理器初始化失败！", e);
        }

        // [3] 初始化黑名单数据库
        blacklist = new VoucherBlacklist(configDir);
        try {
            blacklist.initialize();
            blacklist.cleanExpired();
            LOGGER.info("[SneakerNet] 黑名单数据库初始化成功");
        } catch (Exception e) {
            LOGGER.error("[SneakerNet] 黑名单数据库初始化失败！", e);
        }

        // [4] 确保客户端目录存在
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
     * 服务器启动事件（目前无额外操作，密钥和黑名单在 FMLCommonSetup 中已初始化）
     */
    @SubscribeEvent
    public void onServerStarting(ServerStartingEvent event) {
        LOGGER.info("[SneakerNet] 服务器启动中...");
    }

    /**
     * 服务器停止事件：关闭资源
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
        }

        if (cryptoExecutor != null) {
            cryptoExecutor.shutdown();
        }
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
