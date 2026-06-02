package com.yansunsky.sneakernet.net;

import com.yansunsky.sneakernet.SneakerNet;
import io.netty.buffer.ByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;

/**
 * Voucher 同步数据包（服务端 → 客户端）
 * <p>
 * 当玩家在服务端使用 Ticket 导出容器后，
 * 服务端将加密后的 Voucher JSON 通过此包发送给玩家客户端，
 * 客户端保存到本地 {@code .minecraft/sneakernet/vouchers/} 目录。
 * </p>
 *
 * @param voucherJson       Voucher 完整 JSON 字符串
 * @param suggestedFileName 建议保存的文件名（含扩展名）
 */
public record VoucherSyncPayload(
        String voucherJson,
        String suggestedFileName
) implements CustomPacketPayload {

    public static final CustomPacketPayload.Type<VoucherSyncPayload> TYPE =
            new CustomPacketPayload.Type<>(
                    ResourceLocation.fromNamespaceAndPath(SneakerNet.MOD_ID, "voucher_sync"));

    public static final StreamCodec<ByteBuf, VoucherSyncPayload> STREAM_CODEC = StreamCodec.composite(
            ByteBufCodecs.STRING_UTF8, VoucherSyncPayload::voucherJson,
            ByteBufCodecs.STRING_UTF8, VoucherSyncPayload::suggestedFileName,
            VoucherSyncPayload::new
    );

    @Override
    public CustomPacketPayload.Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}
