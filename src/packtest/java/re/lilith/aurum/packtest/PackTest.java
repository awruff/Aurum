package re.lilith.aurum.packtest;

import net.fabricmc.api.ClientModInitializer;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.util.ScreenshotUtils;
import net.minecraft.world.level.LevelGeneratorType;
import net.minecraft.world.level.LevelInfo;
import net.ornithemc.osl.lifecycle.api.client.MinecraftClientEvents;
import re.lilith.aurum.Aurum;
import re.lilith.aurum.gl.GlDebug;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.ArrayDeque;
import java.util.Arrays;
import java.util.Deque;
import java.util.HashSet;
import java.util.Set;
import java.util.stream.Collectors;
import java.util.stream.Stream;

public class PackTest implements ClientModInitializer {
    private static final int SETTLE_TICKS = Integer.getInteger("aurum.packtest.settleTicks", 160);

    private final Deque<String> queue = new ArrayDeque<>();
    private Path results;
    private String current;
    private int ticks;
    private boolean worldRequested;

    @Override
    public void onInitializeClient() {
        if (!Boolean.getBoolean("aurum.packtest")) {
            return;
        }

        MinecraftClientEvents.TICK_END.register(this::tick);
    }

    private void tick(MinecraftClient client) {
        try {
            step(client);
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
    }

    private void step(MinecraftClient client) throws IOException {
        if (client.world == null || client.player == null) {
            if (!worldRequested && client.currentScreen != null) {
                worldRequested = true;
                if (Boolean.getBoolean("aurum.packtest.glDebug")) {
                    GlDebug.setupDebugMessageCallback();
                }
                client.options.pauseOnLostFocus = false;
                client.options.hudHidden = true;
                client.startIntegratedServer("packtest", "packtest",
                        new LevelInfo(1L, LevelInfo.GameMode.CREATIVE, true, false, LevelGeneratorType.DEFAULT).enableCommands());
            }
            return;
        }

        ticks++;

        if (results == null) {
            if (ticks < 100) {
                return;
            }
            client.player.sendChatMessage("/gamerule doDaylightCycle false");
            client.player.sendChatMessage("/gamerule doWeatherCycle false");
            client.player.sendChatMessage("/weather clear 1000000");
            client.player.sendChatMessage("/time set " + Integer.getInteger("aurum.packtest.time", 6000));
            prepareQueue(client);
            ticks = 0;
            return;
        }

        if (current != null && ticks < SETTLE_TICKS) {
            return;
        }

        if (current != null) {
            ScreenshotUtils.saveScreenshot(client.runDirectory, "packtest/" + sanitize(current) + ".png",
                    client.width, client.height, client.getFramebuffer());
            String status = Aurum.isFallback() ? "FALLBACK" : Aurum.getCurrentPack().isPresent() ? "OK" : "NOT_LOADED";
            record(current + "\t" + status);
            Aurum.LOGGER.info("PACKTEST END {} {}", current, status);
        }

        current = queue.poll();
        ticks = 0;

        if (current == null) {
            Aurum.LOGGER.info("PACKTEST DONE");
            client.scheduleStop();
            return;
        }

        record(current + "\tSTARTED");
        Aurum.LOGGER.info("PACKTEST BEGIN {}", current);
        Aurum.getAurumConfig().setShadersEnabled(true);
        Aurum.getAurumConfig().setShaderPackName(current);
        Aurum.getAurumConfig().save();
        Aurum.reload();
    }

    private void prepareQueue(MinecraftClient client) throws IOException {
        Path dir = client.runDirectory.toPath().resolve("packtest");
        Files.createDirectories(dir);
        Files.createDirectories(client.runDirectory.toPath().resolve("screenshots/packtest"));
        results = dir.resolve("results.txt");

        Set<String> done = new HashSet<>();
        if (Files.exists(results)) {
            for (String line : Files.readAllLines(results)) {
                done.add(line.split("\t")[0]);
            }
        }

        String only = System.getProperty("aurum.packtest.packs");
        try (Stream<Path> packs = Files.list(Aurum.getShaderpacksDirectory())) {
            packs.map(p -> p.getFileName().toString())
                    .filter(name -> !name.endsWith(".txt"))
                    .filter(name -> only == null || Arrays.asList(only.split(",")).contains(name))
                    .filter(name -> !done.contains(name))
                    .sorted()
                    .collect(Collectors.toCollection(() -> queue));
        }

        Aurum.LOGGER.info("PACKTEST queue: {}", queue);
    }

    private void record(String line) throws IOException {
        Files.write(results, (line + "\n").getBytes(), StandardOpenOption.CREATE, StandardOpenOption.APPEND);
    }

    private static String sanitize(String name) {
        return name.replaceAll("[^A-Za-z0-9._-]", "_");
    }
}
