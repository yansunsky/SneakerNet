package com.yansunsky.sneakernet.items;

import com.yansunsky.sneakernet.Config;
import com.yansunsky.sneakernet.SneakerNet;
import com.yansunsky.sneakernet.crypto.KeyManager;
import com.yansunsky.sneakernet.crypto.VoucherCrypto;
import com.yansunsky.sneakernet.data.ItemNbtUtil;
import com.yansunsky.sneakernet.data.PlayerBindData;
import com.yansunsky.sneakernet.data.Voucher;
import com.yansunsky.sneakernet.net.VoucherSyncPayload;
import net.minecraft.ChatFormatting;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.neoforged.neoforge.capabilities.Capabilities;
import net.neoforged.neoforge.items.IItemHandler;
import net.neoforged.neoforge.items.IItemHandlerModifiable;
import net.neoforged.neoforge.network.PacketDistributor;
import net.minecraft.world.Clearable;
import net.minecraft.world.Nameable;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResultHolder;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;
import net.neoforged.fml.loading.FMLPaths;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.file.Files;
import java.nio.file.Path;
import java.security.KeyPair;
import java.security.PublicKey;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/**
 * SneakerNet Ticket 物品行为
 * <p>
 * 右键容器时，将容器内容加密导出为 Voucher 文件。
 * 异步执行加密操作，不阻塞主线程。
 */
public class TicketItem extends Item {

    private static final Logger LOGGER = LoggerFactory.getLogger(TicketItem.class);

    public TicketItem(Properties props) {
        super(props);
    }

    @Override
    public InteractionResultHolder<ItemStack> use(Level level, Player player, InteractionHand hand) {
        ItemStack itemStack = player.getItemInHand(hand);
        if (level.isClientSide()) return InteractionResultHolder.pass(itemStack); // 仅服务端

        // [1] 检查玩家注视的方块
        HitResult hitResult = player.pick(5.0, 0, false);
        if (hitResult.getType() != HitResult.Type.BLOCK) {
            return InteractionResultHolder.pass(itemStack); // 没瞄准方块
        }
        BlockHitResult blockHitResult = (BlockHitResult) hitResult;

        // [2] 检查目标方块是否是可存储物品的容器
        //     优先使用 NeoForge 的 IItemHandler 能力（原版容器自动注册，
        //     模组容器普遍暴露），从而兼容任意能存储物品的容器。
        BlockPos blockPos = blockHitResult.getBlockPos();
        BlockEntity blockEntity = level.getBlockEntity(blockPos);

        IItemHandler handler = level.getCapability(Capabilities.ItemHandler.BLOCK, blockPos, null);
        if (handler == null) {
            player.sendSystemMessage(Component.translatable("sneakernet.ticket.not_container")
                    .withStyle(ChatFormatting.RED));
            return InteractionResultHolder.fail(itemStack);
        }

        // [3] 检查是否有可信服务器
        KeyManager keyManager = SneakerNet.getKeyManager();
        if (keyManager == null || keyManager.getTrustedServers().isEmpty()) {
            player.sendSystemMessage(Component.translatable("sneakernet.ticket.no_trusted_servers")
                    .withStyle(ChatFormatting.RED));
            return InteractionResultHolder.fail(itemStack);
        }

        // [4] 检查玩家是否绑定了目标服务器
        MinecraftServer server = level.getServer();
        PlayerBindData bindData = SneakerNet.getPlayerBindData(server);
        String targetServerName = bindData.getBinding(player.getUUID());
        if (targetServerName == null) {
            player.sendSystemMessage(Component.translatable("sneakernet.ticket.no_target")
                    .withStyle(ChatFormatting.RED));
            return InteractionResultHolder.fail(itemStack);
        }

        // [5] 查找目标服务器对应的公钥
        PublicKey resolvedPubKey = null;
        for (KeyManager.TrustedServer ts : keyManager.getTrustedServers()) {
            if (ts.name().equals(targetServerName)) {
                try {
                    resolvedPubKey = KeyManager.decodePublicKeyFromBase64(ts.pubKeyBase64());
                } catch (Exception e) {
                    player.sendSystemMessage(Component.literal("解析目标服务器公钥失败: " + e.getMessage())
                            .withStyle(ChatFormatting.RED));
                    return InteractionResultHolder.fail(itemStack);
                }
                break;
            }
        }
        if (resolvedPubKey == null) {
            // 绑定的服务器已被移除，清除绑定
            bindData.removeBinding(player.getUUID());
            player.sendSystemMessage(Component.translatable("sneakernet.ticket.no_target")
                    .withStyle(ChatFormatting.RED));
            return InteractionResultHolder.fail(itemStack);
        }
        // resolvedPubKey 现已有效赋值且不再更改 → effectively final

        // [6] 先扣除 1 个 Ticket（防止重复触发）
        itemStack.shrink(1);

        // [7] 在服务端主线程同步序列化容器（能力对象不可跨线程持有）
        String containerType = resolveContainerType(level, blockPos);
        Component customName = (blockEntity instanceof Nameable nameable) ? nameable.getCustomName() : null;
        byte[] nbtBytes;
        try {
            nbtBytes = ItemNbtUtil.serializeItemHandlerToBytes(
                    handler, containerType, customName, level.registryAccess());
        } catch (Exception e) {
            // 序列化失败，退还 Ticket
            player.getInventory().add(new ItemStack(ModItems.TICKET.get(), 1));
            player.sendSystemMessage(Component.translatable(
                    "sneakernet.ticket.export_failed", e.getMessage()
            ).withStyle(ChatFormatting.RED));
            return InteractionResultHolder.fail(itemStack);
        }

        // [8] 异步执行加密导出（只导出目标服务器的凭证）
        UUID playerUuid = player.getUUID();
        final IItemHandler finalHandler = handler;
        final PublicKey targetPubKey = resolvedPubKey; // 显式 final 供 lambda 捕获
        final byte[] finalNbtBytes = nbtBytes;
        SneakerNet.getCryptoExecutor().supplyAsync(() -> {
            return doExport(finalNbtBytes, keyManager.getLocalKeyPair(), targetPubKey,
                    targetServerName, playerUuid);
        }).thenAccept(result -> {
            if (result.success) {
                // [a] 发送网络包到客户端（任何线程均可发送）
                for (Map.Entry<String, String> entry : result.voucherFiles().entrySet()) {
                    PacketDistributor.sendToPlayer(
                            (ServerPlayer) player,
                            new VoucherSyncPayload(entry.getValue(), entry.getKey())
                    );
                }
                // [b] 切回主线程：清空容器 + 移除方块 + 通知玩家
                server.execute(() -> {
                    // 清空容器物品（防止物品复制）
                    clearContainer(finalHandler, level, blockPos);
                    // 移除容器方块
                    level.setBlock(blockPos, Blocks.AIR.defaultBlockState(),
                            net.minecraft.world.level.block.Block.UPDATE_ALL
                                    | net.minecraft.world.level.block.Block.UPDATE_IMMEDIATE);

                    for (String fileName : result.voucherFiles().keySet()) {
                        player.sendSystemMessage(Component.translatable(
                                "sneakernet.ticket.export_success", fileName
                        ).withStyle(ChatFormatting.GREEN));
                    }
                    // 提示玩家如何导出/导入包裹
                    player.sendSystemMessage(Component.translatable(
                            "sneakernet.ticket.export_hint"
                    ).withStyle(ChatFormatting.YELLOW));
                });
            } else {
                // 导出失败，退还 Ticket
                player.getInventory().add(new ItemStack(ModItems.TICKET.get(), 1));
                player.sendSystemMessage(Component.translatable(
                        "sneakernet.ticket.export_failed", result.failureReason()
                ).withStyle(ChatFormatting.RED));
            }
        });

        return InteractionResultHolder.sidedSuccess(itemStack, level.isClientSide());
    }

    /**
     * 导出逻辑（在线程池中执行，不阻塞主线程）
     * <p>
     * 容器已在主线程同步序列化为二进制，这里只做加密 + 签名 + 落盘，
     * 为玩家绑定的目标服务器生成一份加密凭据。
     * </p>
     *
     * @param nbtBytes     已序列化的容器二进制数据
     * @param localKeyPair 本地 ECC 密钥对
     * @param targetPubKey 目标服务器的公钥
     * @param targetServer 目标服务器名
     * @param playerUuid   导出玩家 UUID
     */
    private static ExportResult doExport(byte[] nbtBytes,
                                         KeyPair localKeyPair,
                                         PublicKey targetPubKey,
                                         String targetServer,
                                         UUID playerUuid) {
        try {
            // [a] 只生成目标服务器的凭据
            Map<String, String> voucherFiles = new HashMap<>();
            int ttlSeconds = Config.VOUCHER_TTL_HOURS.get() * 3600;

            // [b] 加密 + 签名（用目标服务器公钥）
            Voucher voucher = VoucherCrypto.encryptAndSign(
                    localKeyPair,
                    targetPubKey,
                    nbtBytes,
                    playerUuid,
                    ttlSeconds
            );

            // [c] 保存到服务端目录
            Path voucherDir = FMLPaths.GAMEDIR.get().resolve("sneakernet/vouchers/");
            Files.createDirectories(voucherDir);

            String fileName = targetServer + "_" + voucher.generateFileName();
            Path voucherFile = voucherDir.resolve(fileName);
            Files.writeString(voucherFile, voucher.toJson());

            voucherFiles.put(fileName, voucher.toJson());

            LOGGER.info("[SneakerNet] 导出成功：目标服务器={}, 文件名={}", targetServer, fileName);
            return ExportResult.success(voucherFiles);

        } catch (Exception e) {
            LOGGER.error("[SneakerNet] 导出失败", e);
            return ExportResult.fail(e.getMessage());
        }
    }

    /**
     * 解析容器类型标识。
     * <p>
     * 返回方块的注册名（如 "minecraft:chest"、"create:item_vault"），
     * 用于还原时按注册名重新放置同种方块。
     * </p>
     */
    private static String resolveContainerType(Level level, BlockPos blockPos) {
        return net.minecraft.core.registries.BuiltInRegistries.BLOCK
                .getKey(level.getBlockState(blockPos).getBlock()).toString();
    }

    /**
     * 清空容器物品（防止物品复制）。
     * <p>
     * 优先通过 IItemHandler 能力逐槽清空（兼容任意容器）；
     * 若该处方块实体额外实现了 Clearable，再调用一次以兜底
     * （原版 Container 接口本身即继承 Clearable）。
     * </p>
     */
    private static void clearContainer(IItemHandler handler, Level level, BlockPos blockPos) {
        if (handler instanceof IItemHandlerModifiable modifiable) {
            for (int i = 0; i < modifiable.getSlots(); i++) {
                modifiable.setStackInSlot(i, ItemStack.EMPTY);
            }
        }
        // 兜底：部分容器仅通过 Clearable 清空才会触发同步
        BlockEntity be = level.getBlockEntity(blockPos);
        if (be instanceof Clearable clearable) {
            clearable.clearContent();
        }
        if (be != null) {
            be.setChanged();
        }
    }

    // ─── 导出结果 ───

    record ExportResult(
            boolean success,
            Map<String, String> voucherFiles, // filename → JSON content
            String failureReason
    ) {
        static ExportResult success(Map<String, String> files) {
            return new ExportResult(true, files, null);
        }

        static ExportResult fail(String reason) {
            return new ExportResult(false, null, reason);
        }
    }
}
