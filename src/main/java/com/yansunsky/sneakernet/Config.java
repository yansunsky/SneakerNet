package com.yansunsky.sneakernet;

import net.neoforged.fml.ModContainer;
import net.neoforged.fml.config.ModConfig;
import net.neoforged.neoforge.common.ModConfigSpec;

/**
 * SneakerNet 配置文件（v2 — ECC 非对称加密方案）
 * <p>
 * 配置文件位置：config/sneakernet-server.toml
 * <p>
 * 密钥管理由 KeyManager 负责（config/sneakernet/ 目录下的 DER 文件和 JSON 文件），
 * 不再使用共享主密钥。
 */
public class Config {

    private static final ModConfigSpec.Builder BUILDER = new ModConfigSpec.Builder();

    // ===== 凭据配置 =====

    public static final ModConfigSpec.IntValue VOUCHER_TTL_HOURS = BUILDER
            .comment(
                    "凭据有效期（小时），超过此时间的凭据将被拒绝。",
                    "同时也决定黑名单记录的保留时间。",
                    "默认 24 小时，最大 720 小时（30 天）。"
            )
            .defineInRange("voucherTtlHours", 24, 1, 720);

    public static final ModConfigSpec.IntValue MAX_ITEMS_PER_VOUCHER = BUILDER
            .comment(
                    "单个凭据最大物品数。",
                    "默认 54（双箱），最大 54。"
            )
            .defineInRange("maxItemsPerVoucher", 54, 1, 54);

    public static final ModConfigSpec.BooleanValue REQUIRE_PLAYER_MATCH = BUILDER
            .comment(
                    "是否要求导入玩家与导出玩家 UUID 一致。",
                    "开启后只有导出者本人可以使用凭据。",
                    "关闭后任何玩家都可以导入（但仍受黑名单和 TTL 限制）。"
            )
            .define("requirePlayerMatch", true);

    // ===== 调试配置 =====

    public static final ModConfigSpec.BooleanValue DEBUG_MODE = BUILDER
            .comment("调试模式：在日志中输出详细调试信息")
            .define("debugMode", false);

    public static final ModConfigSpec SPEC = BUILDER.build();

    // ===== 注册配置 =====

    /**
     * 注册服务端配置
     *
     * @param modContainer 模组容器（由主类构造函数提供）
     */
    public static void register(ModContainer modContainer) {
        modContainer.registerConfig(ModConfig.Type.SERVER, SPEC, SneakerNet.MOD_ID + "-server.toml");
    }
}
