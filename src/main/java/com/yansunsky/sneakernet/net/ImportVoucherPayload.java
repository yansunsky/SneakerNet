package com.yansunsky.sneakernet.net;

import com.yansunsky.sneakernet.SneakerNet;
import io.netty.buffer.ByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;

/**
 * 导入凭证数据包（客户端 → 服务端）
 * <p>
 * 客户端读取本地 {@code .minecraft/sneakernet/vouchers/} 目录下的 JSON 凭证文件后，
 * 将文件内容通过此包发送给服务端进行处理。
 * </p>
 *
 * @param voucherJson 凭证文件的完整 JSON 内容
 * @param fileName    建议的文件名（仅用于日志和消息显示）
 */
public record ImportVoucherPayload(
        String voucherJson,
        String fileName
) implements CustomPacketPayload {

    public static final CustomPacketPayload.Type<ImportVoucherPayload> TYPE =
            new CustomPacketPayload.Type<>(
                    ResourceLocation.fromNamespaceAndPath(SneakerNet.MOD_ID, "import_voucher"));

    public static final StreamCodec<ByteBuf, ImportVoucherPayload> STREAM_CODEC = StreamCodec.composite(
            ByteBufCodecs.STRING_UTF8, ImportVoucherPayload::voucherJson,
            ByteBufCodecs.STRING_UTF8, ImportVoucherPayload::fileName,
            ImportVoucherPayload::new
    );

    @Override
    public CustomPacketPayload.Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}
