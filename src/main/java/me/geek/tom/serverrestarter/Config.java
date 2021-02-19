package me.geek.tom.serverrestarter;

import com.cronutils.model.Cron;
import com.cronutils.model.CronType;
import com.cronutils.model.definition.CronDefinitionBuilder;
import com.cronutils.model.time.ExecutionTime;
import com.cronutils.parser.CronParser;
import com.google.gson.JsonElement;
import com.google.gson.JsonParser;
import com.mojang.datafixers.util.Pair;
import com.mojang.serialization.Codec;
import com.mojang.serialization.DataResult;
import com.mojang.serialization.JsonOps;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.util.StringIdentifiable;

import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.ZonedDateTime;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicReference;

import static me.geek.tom.serverrestarter.ServerRestarter.LOGGER;

public class Config {
    private static final Codec<Config> CODEC = RecordCodecBuilder.create(instance -> instance.group(
            Codec.STRING.fieldOf("webhook_url").forGetter(c -> c.webhookUrl),
            ScheduledAction.CODEC.listOf().fieldOf("scheduled_actions").forGetter(c -> c.scheduledActions)
    ).apply(instance, Config::new));

    private static Config config;

    public final String webhookUrl;
    public final List<ScheduledAction> scheduledActions;
    private final boolean complete;

    private Config(String webhookUrl, List<ScheduledAction> scheduledActions) {
        this.webhookUrl = webhookUrl;
        this.scheduledActions = scheduledActions;
        this.complete = true;
    }

    private Config() {
        this.webhookUrl = "";
        this.scheduledActions = null;
        this.complete = false;
    }

    public static boolean complete() {
        return get().complete;
    }

    public Pair<ScheduledAction, ZonedDateTime> getNextEvent() {
        if (this.scheduledActions == null || this.scheduledActions.isEmpty()) return null;

        ZonedDateTime now = ZonedDateTime.now();

        AtomicReference<ScheduledAction> earliest = new AtomicReference<>(null);
        AtomicReference<ZonedDateTime> actionTime = new AtomicReference<>(null);
        for (ScheduledAction action : this.scheduledActions) {
            Optional<ZonedDateTime> next = action.getNext().nextExecution(now);
            if (earliest.get() == null) {
                next.ifPresent(time -> {
                    earliest.set(action);
                    actionTime.set(time);
                });
            } else {
                next.ifPresent(time -> {
                    if (time.isBefore(actionTime.get())) {
                        earliest.set(action);
                        actionTime.set(time);
                    }
                });
            }
        }

        return Pair.of(earliest.get(), actionTime.get());
    }

    public static Config get() {
        if (config == null) loadConfig();
        return config;
    }

    private static void loadConfig() {
        Path configPath = FabricLoader.getInstance().getConfigDir().resolve("server_restarter.json");
        try (InputStream input = Files.newInputStream(configPath)) {
            JsonElement json = new JsonParser().parse(new InputStreamReader(input));
            DataResult<Config> result = CODEC.decode(JsonOps.INSTANCE, json).map(Pair::getFirst);
            config = result.result().orElseGet(Config::new);
        } catch (IOException e) {
            LOGGER.error("Failed to load config!", e);
            config = new Config();
        }
    }

    public static class ScheduledAction {
        private static final Codec<ScheduledAction> CODEC = RecordCodecBuilder.create(instance -> instance.group(
                StringIdentifiable.createCodec(Action::values, Action::valueOf)
                        .fieldOf("action").forGetter(s -> s.action),
                Codec.STRING.xmap(s -> new CronParser(CronDefinitionBuilder.instanceDefinitionFor(CronType.UNIX)).parse(s), Cron::asString)
                        .fieldOf("cron").forGetter(s -> s.cron),
                Codec.STRING.optionalFieldOf("message", "Scheduled restart").forGetter(s -> s.message)
        ).apply(instance, ScheduledAction::new));
        public final Action action;
        public final Cron cron;
        public final String message;

        private ScheduledAction(Action action, Cron cron, String message) {
            this.action = action;
            this.cron = cron;
            this.message = message;
        }

        public ExecutionTime getNext() {
            return ExecutionTime.forCron(this.cron);
        }
    }

    public enum Action implements StringIdentifiable {
        Stop, Restart;

        @Override
        public String asString() {
            return name();
        }
    }
}
