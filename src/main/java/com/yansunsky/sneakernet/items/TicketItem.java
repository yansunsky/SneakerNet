package com.yansunsky.sneakernet.items;

import com.yansunsky.sneakernet.Config;
import com.yansunsky.sneakernet.SneakerNet;
import com.yansunsky.sneakernet.crypto.KeyManager;
import com.yansunsky.sneakernet.crypto.VoucherCrypto;
import com.yansunsky.sneakernet.data.ItemNbtUtil;
import com.yansunsky.sneakernet.data.Voucher;
import com.yansunsky.sneakernet.executor.CryptoExecutor;
import net.minecraft.ChatFormatting;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.Container;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResultHolder;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;
import net.neoforged.fml.loading.FMLPaths;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.file.Files;
import java.nio.file.Path;
import java.security.PublicKey;
import java.util.ArrayList;
import java.util.List;
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

        // [2] 检查目标方块是否是容器
        BlockPos blockPos = blockHitResult.getBlockPos();
        BlockEntity blockEntity = level.getBlockEntity(blockPos);

        if (!(blockEntity instanceof Container container)) {
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

        // [4] 先扣除 1 个 Ticket（防止重复触发）
        itemStack.shrink(1);

        // [5] 异步执行导出
        UUID playerUuid = player.getUUID();
        MinecraftServer server = level.getServer();

        SneakerNet.getCryptoExecutor().supplyAsync(() -> {
            return doExport(container, keyManager, playerUuid, level.registryAccess());
        }).thenAccept(result -> {
            // 切回主线程通知玩家
            server.execute(() -> {
                if (result.success) {
                    for (String fileName : result.voucherFileNames()) {
                        player.sendSystemMessage(Component.translatable(
                                "sneakernet.ticket.export_success", fileName
                        ).withStyle(ChatFormatting.GREEN));
                    }
                } else {
                    // 导出失败，退还 Ticket
                    player.getInventory().add(new ItemStack(ModItems.TICKET.get(), 1));
                    player.sendSystemMessage(Component.translatable(
                            "sneakernet.ticket.export_failed", result.failureReason()
                    ).withStyle(ChatFormatting.RED));
                }
            });
        });

        return InteractionResultHolder.sidedSuccess(itemStack, level.isClientSide());
    }

    /**
     * 导出逻辑（在线程池中执行，不阻塞主线程）
     */
    private static ExportResult doExport(Container container, KeyManager keyManager,
                                         UUID playerUuid, net.minecraft.core.RegistryAccess registryAccess) {
        try {
            // [a] 序列化容器 NBT
            byte[] nbtBytes = ItemNbtUtil.serializeContainerToBytes(
                    container, registryAccess
            );

            // [b] 对每个可信服务器各生成一份凭据
            List<String> voucherFileNames = new ArrayList<>();
            int ttlSeconds = Config.VOUCHER_TTL_HOURS.get() * 3600;

            for (KeyManager.TrustedServer trustedServer : keyManager.getTrustedServers()) {
                PublicKey targetPubKey = KeyManager.decodePublicKeyFromBase64(trustedServer.pubKeyBase64());

                // [c] 加密 + 签名
                Voucher voucher = VoucherCrypto.encryptAndSign(
                        keyManager.getLocalKeyPair(),
                        targetPubKey,
                        nbtBytes,
                        playerUuid,
                        ttlSeconds
                );

                // [d] 保存到客户端目录
                Path voucherDir = FMLPaths.GAMEDIR.get().resolve("sneakernet/vouchers/");
                Files.createDirectories(voucherDir);

                String fileName = trustedServer.name() + "_" + voucher.generateFileName();
                Path voucherFile = voucherDir.resolve(fileName);
                voucher.saveToFile(voucherDir);
                voucherFileNames.add(fileName);
            }

            LOGGER.info("[SneakerNet] 导出成功：{} 个凭据", voucherFileNames.size());
            return ExportResult.success(voucherFileNames);

        } catch (Exception e) {
            LOGGER.error("[SneakerNet] 导出失败", e);
            return ExportResult.fail(e.getMessage());
        }
    }

    // ─── 导出结果 ───

    record ExportResult(
            boolean success,
            List<String> voucherFileNames,
            String failureReason
    ) {
        static ExportResult success(List<String> fileNames) {
            return new ExportResult(true, fileNames, null);
        }

        static ExportResult fail(String reason) {
            return new ExportResult(false, null, reason);
        }
    }
}
