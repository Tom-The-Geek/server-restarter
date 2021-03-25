package me.geek.tom.serverrestarter;

import com.google.gson.Gson;
import com.google.gson.JsonObject;
import com.mojang.brigadier.context.CommandContext;
import com.mojang.datafixers.util.Pair;
import net.fabricmc.api.ModInitializer;
import net.fabricmc.fabric.api.command.v1.CommandRegistrationCallback;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.minecraft.network.MessageType;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.command.ServerCommandSource;
import net.minecraft.text.LiteralText;
import net.minecraft.util.Util;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.jetbrains.annotations.Nullable;

import java.io.IOException;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardOpenOption;
import java.time.ZonedDateTime;

import static com.mojang.brigadier.arguments.StringArgumentType.getString;
import static com.mojang.brigadier.arguments.StringArgumentType.greedyString;
import static net.minecraft.server.command.CommandManager.argument;
import static net.minecraft.server.command.CommandManager.literal;

public class ServerRestarter implements ModInitializer {
    public static final Logger LOGGER = LogManager.getLogger();

    private int timer = 0;
    private @Nullable String scheduledReason = null;
    private long lastPlayerSeen;
    private Pair<Config.ScheduledAction, ZonedDateTime> action;

    @Override
    public void onInitialize() {
        if (!"true".equals(System.getenv("SERVER_LAUNCHER"))) {
            LOGGER.warn("=======================================================");
            LOGGER.warn("|  This server is not wrapped using server-launcher!  |");
            LOGGER.warn("|   If you are using an outdated version (<= 1.1.0),  |");
            LOGGER.warn("|     then update or set the environment variable     |");
            LOGGER.warn("|   SERVER_LAUNCHER to 'true' if you don't want to.   |");
            LOGGER.warn("=======================================================");
            return;
        }

        if (!Config.complete()) {
            LOGGER.warn("============================================");
            LOGGER.warn("| server-restarter hasn't been configured! |");
            LOGGER.warn("============================================");
            this.timer = -1; // setting to -1 here prevents the action being set later.
        }

        CommandRegistrationCallback.EVENT.register((dispatcher, dedicated) -> {
            dispatcher.register(literal("restart")
                    .requires(s -> s.hasPermissionLevel(4))
                    .then(literal("now")
                            .then(argument("reason", greedyString())
                                            .executes(ctx -> {
                                                now(ctx, getString(ctx, "reason"));
                                                return 0;
                                            })
                                    ).executes(ctx -> {
                                        now(ctx, "No reason specified");
                                        return 0;
                                    })
                    ).then(literal("schedule")
                            .then(argument("reason", greedyString())
                                    .executes(ctx -> {
                                        schedule(ctx, getString(ctx, "reason"));
                                        return 0;
                                    })
                            ).executes(ctx -> {
                                schedule(ctx, "No reason specified!");
                                return 0;
                            })
                    )
            );
        });
        ServerTickEvents.END_SERVER_TICK.register(this::tick);

        if (this.timer == 0) {
            this.action = Config.get().getNextEvent();
            if (this.action == null) {
                this.timer = -1; // disables ticking of scheduled actions.
            }
        }
    }

    private void tick(MinecraftServer server) {
        if (server.getCurrentPlayerCount() != 0) {
            this.lastPlayerSeen = System.currentTimeMillis();
        } else if (scheduledReason != null) {
            if (System.currentTimeMillis() - this.lastPlayerSeen > 10000L) {
                restart("[SCHEDULED] " + this.scheduledReason, server);
            }
        }

        if (this.timer != -1) {
            this.timer = (this.timer + 1) % 20;
            if (this.timer == 0) {
                if (ZonedDateTime.now().isAfter(this.action.getSecond())) {
                    Config.ScheduledAction action = this.action.getFirst();
                    switch (action.action) {
                        case Stop:
                            server.stop(false);
                            break;
                        case Restart:
                            restart(action.message, server);
                            break;
                    }
                }
            }
        }
    }

    private void schedule(CommandContext<ServerCommandSource> ctx, String reason) {
        if (this.scheduledReason != null) {
            ctx.getSource().sendError(new LiteralText("Restart already scheduled with reason: " + this.scheduledReason));
        }
        ctx.getSource().sendFeedback(new LiteralText("Restart scheduled for when no players are online!"), true);
        ctx.getSource().getMinecraftServer().getPlayerManager().broadcastChatMessage(new LiteralText("[ALERT] Server will restart when no players are online."), MessageType.SYSTEM, Util.NIL_UUID);
        this.scheduledReason = reason;
    }

    private void now(CommandContext<ServerCommandSource> ctx, String reason) {
        ctx.getSource().sendFeedback(new LiteralText("Restarting server..."), true);
        MinecraftServer server = ctx.getSource().getMinecraftServer();
        restart(reason, server);
    }

    private void restart(String reason, MinecraftServer server) {
        server.stop(false);
        Path reasonPath = Paths.get(".restart_reason");
        try {
            Files.write(reasonPath,
                    reason.getBytes(StandardCharsets.UTF_8),
                    StandardOpenOption.CREATE,
                    StandardOpenOption.TRUNCATE_EXISTING);
        } catch (IOException e) {
            LogManager.getLogger().error("Failed to save restart reason!", e);
        }

        Config config = Config.get();
        if (!config.webhookUrl.isEmpty()) {
            try {
                URL url = new URL(config.webhookUrl);
                HttpURLConnection conn = (HttpURLConnection) url.openConnection();
                conn.setRequestMethod("POST");
                conn.setRequestProperty("Content-Type", "application/json");
                conn.setDoOutput(true);
                conn.setRequestProperty("User-Agent", "DiscordBot (https://github.com/Geek202/server-restarter, v1)");
                Gson gson = new Gson();
                JsonObject msg = new JsonObject();
                msg.addProperty("content", "Restart requested: " + reason);
                msg.addProperty("username", "Server");
                String payload = gson.toJson(msg);
                OutputStream os = conn.getOutputStream();
                os.write(payload.getBytes(StandardCharsets.UTF_8));
                os.close();
                int code = conn.getResponseCode();
                if (code < 200 || code >= 300) {
                    throw new IOException(String.valueOf(code));
                }
            } catch (Exception e) {
                LogManager.getLogger().error("Failed to send webhook", e);
            }
        }
    }
}
