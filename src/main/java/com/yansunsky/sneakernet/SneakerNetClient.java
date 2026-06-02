package com.yansunsky.sneakernet;

import com.mojang.brigadier.Command;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.yansunsky.sneakernet.net.ImportVoucherPayload;
import net.minecraft.commands.Commands;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.fml.ModContainer;
import net.neoforged.fml.common.Mod;
import net.neoforged.fml.loading.FMLPaths;
import net.neoforged.neoforge.client.event.RegisterClientCommandsEvent;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.network.PacketDistributor;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * SneakerNet 客户端侧启动类 (v0.5 ECC)
 * <p>
 * 注册客户端命令 /sneakernet import，读取本地凭证文件并通过网络包发送到服务端。
 */
@Mod(value = SneakerNet.MOD_ID, dist = Dist.CLIENT)
public class SneakerNetClient {

    public SneakerNetClient(IEventBus modEventBus, ModContainer modContainer) {
        // 注册客户端命令（在 NeoForge 事件总线上监听）
        NeoForge.EVENT_BUS.addListener(this::onRegisterClientCommands);

        SneakerNet.LOGGER.info("[SneakerNet] 客户端侧已加载（ECC 模式）");
    }

    /**
     * 注册客户端命令
     * <p>
     * /sneakernet import &lt;filename&gt; — 读取本地凭证文件并发给服务端
     */
    private void onRegisterClientCommands(RegisterClientCommandsEvent event) {
        event.getDispatcher().register(Commands.literal("sneakernet")
                .then(Commands.literal("import")
                        // /sneakernet import — 扫描并上传所有凭证
                        .executes(ctx -> doClientImportAll())
                        // /sneakernet import <filename> — 上传指定凭证
                        .then(Commands.argument("filename", StringArgumentType.string())
                                .executes(ctx -> {
                                    String filename = StringArgumentType.getString(ctx, "filename");
                                    return doClientImport(filename);
                                })
                        )
                )
        );
        SneakerNet.LOGGER.debug("[SneakerNet] 客户端导入命令已注册");
    }

    /**
     * 扫描目录并逐个上传所有凭证文件
     */
    private int doClientImportAll() {
        Path vouchersDir = FMLPaths.GAMEDIR.get().resolve("sneakernet/vouchers/");
        if (!Files.exists(vouchersDir)) {
            SneakerNet.LOGGER.warn("[SneakerNet] 凭证目录不存在: {}", vouchersDir);
            return 0;
        }

        try (var files = Files.list(vouchersDir)) {
            java.util.List<Path> jsonFiles = files
                    .filter(f -> f.toString().endsWith(".json"))
                    .toList();

            if (jsonFiles.isEmpty()) {
                SneakerNet.LOGGER.info("[SneakerNet] 凭证目录中没有 .json 文件");
                return 0;
            }

            for (Path file : jsonFiles) {
                String filename = file.getFileName().toString();
                doClientImport(filename);
            }
            SneakerNet.LOGGER.info("[SneakerNet] 已批量上传 {} 个凭证文件", jsonFiles.size());
            return jsonFiles.size();
        } catch (IOException e) {
            SneakerNet.LOGGER.error("[SneakerNet] 扫描凭证目录失败", e);
            return 0;
        }
    }

    /**
     * 客户端导入逻辑：读取本地凭证文件，通过网络包发送到服务端
     *
     * @param filename 凭证文件名（相对于 sneakerNet/vouchers/ 目录）
     * @return 命令执行结果
     */
    private int doClientImport(String filename) {
        Path vouchersDir = FMLPaths.GAMEDIR.get().resolve("sneakernet/vouchers/");
        Path file = vouchersDir.resolve(filename);

        if (!Files.exists(file)) {
            SneakerNet.LOGGER.warn("[SneakerNet] 客户端凭证文件不存在: {}", file);
            return 0;
        }

        try {
            String json = Files.readString(file);
            PacketDistributor.sendToServer(new ImportVoucherPayload(json, filename));
            SneakerNet.LOGGER.info("[SneakerNet] 已发送凭证到服务器: {}", filename);
            return 1;
        } catch (IOException e) {
            SneakerNet.LOGGER.error("[SneakerNet] 读取客户端凭证文件失败: {}", file, e);
            return 0;
        }
    }
}
