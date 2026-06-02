package com.yansunsky.sneakernet.net;

import com.yansunsky.sneakernet.SneakerNet;
import io.netty.buffer.ByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;

/**
 * 导入结果回执（服务端 → 客户端）
 * <p>
 * 服务端处理完客户端发送的导入请求后，发送此包通知客户端结果。
 * 客户端收到成功回执后，将本地对应的凭证文件移入已核销目录。
 * </p>
 *
 * @param fileName   凭证文件名
 * @param success    是否导入成功
 */
public record ImportResultPayload(
        String fileName,
        boolean success
) implements CustomPacketPayload {

    public static final CustomPacketPayload.Type<ImportResultPayload> TYPE =
            new CustomPacketPayload.Type<>(
                    ResourceLocation.fromNamespaceAndPath(SneakerNet.MOD_ID, "import_result"));

    public static final StreamCodec<ByteBuf, ImportResultPayload> STREAM_CODEC = StreamCodec.composite(
            ByteBufCodecs.STRING_UTF8, ImportResultPayload::fileName,
            ByteBufCodecs.BOOL, ImportResultPayload::success,
            ImportResultPayload::new
    );

    @Override
    public CustomPacketPayload.Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}
