package dev.racegame;

import net.kyori.adventure.sound.Sound;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.TextDecoration;
import net.minestom.server.MinecraftServer;
import net.minestom.server.command.CommandSender;
import net.minestom.server.component.DataComponents;
import net.minestom.server.coordinate.Pos;
import net.minestom.server.coordinate.Vec;
import net.minestom.server.entity.Entity;
import net.minestom.server.entity.EntityType;
import net.minestom.server.color.Color;
import net.minestom.server.entity.GameMode;
import net.minestom.server.entity.Player;
import net.minestom.server.entity.attribute.Attribute;
import net.minestom.server.entity.metadata.display.AbstractDisplayMeta;
import net.minestom.server.entity.metadata.display.ItemDisplayMeta;
import net.minestom.server.entity.metadata.other.ArmorStandMeta;
import net.minestom.server.utils.entity.EntityUtils;
import net.minestom.server.event.Event;
import net.minestom.server.event.EventNode;
import net.minestom.server.event.player.AsyncPlayerConfigurationEvent;
import net.minestom.server.event.player.PlayerBlockBreakEvent;
import net.minestom.server.event.player.PlayerBlockPlaceEvent;
import net.minestom.server.event.player.PlayerDisconnectEvent;
import net.minestom.server.event.player.PlayerInputEvent;
import net.minestom.server.event.player.PlayerMoveEvent;
import net.minestom.server.event.player.PlayerPacketEvent;
import net.minestom.server.event.player.PlayerSpawnEvent;
import net.minestom.server.event.player.PlayerUseItemEvent;
import net.minestom.server.instance.InstanceContainer;
import net.minestom.server.item.ItemStack;
import net.minestom.server.item.Material;
import net.minestom.server.network.packet.client.play.ClientVehicleMovePacket;
import net.minestom.server.network.packet.server.play.ParticlePacket;
import net.minestom.server.particle.Particle;
import net.minestom.server.sound.SoundEvent;
import net.minestom.server.timer.Task;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;

public final class RaceManager {
    private static final int LAPS = 3;
    private static final int MAX_RACERS = 8;
    private static final int BOOST_CHARGES = 3;
    private static final long BOOST_COOLDOWN_MS = 1_200;
    private static final long PULSE_DURATION_MS = 1_000;
    private static final long HUD_INTERVAL_MS = 450;
    private static final double NORMAL_MAX_SPEED = 21.5;
    private static final double PULSE_MAX_SPEED = 29.0;
    private static final double ACCELERATION = 14.0;
    private static final double COAST_DECELERATION = 2.1;
    private static final double BRAKE_DECELERATION = 20.0;
    private static final double STEERING_RATE_BASE = 5.0;
    private static final double STEERING_RATE_SPEED = 0.11;
    private static final double MAX_LANE_OFFSET = 6.5;
    private static final double DRIVER_SEAT_FOOT_OFFSET = 0.38;
    private static final double DRIFT_CHARGE_SECONDS = 1.0;
    private static final double TAU = Math.PI * 2.0;
    private static final Path RECORDS_FILE = Path.of("data", "chroma-rift-records.properties");
    private static final KartStyle[] KART_STYLES = {
            new KartStyle("chroma_rift:kart_cyan", 0x29DFFF),
            new KartStyle("chroma_rift:kart_magenta", 0xFF42B8),
            new KartStyle("chroma_rift:kart_lime", 0xA9F748),
            new KartStyle("chroma_rift:kart_violet", 0xA678FF)
    };

    private final InstanceContainer instance;
    private final Map<UUID, Racer> racers = new ConcurrentHashMap<>();
    private final Map<UUID, Long> personalRecords = new ConcurrentHashMap<>();
    private final List<RaceBot> bots = new CopyOnWriteArrayList<>();
    private volatile Phase phase = Phase.WAITING;
    private long countdownGeneration;

    public RaceManager(InstanceContainer instance) {
        this.instance = instance;
        loadRecords();
    }

    public void registerEvents() {
        EventNode<Event> node = EventNode.all("chroma-rift-race");
        node.addListener(AsyncPlayerConfigurationEvent.class, event -> {
            event.setSpawningInstance(instance);
            event.getPlayer().setRespawnPoint(TrackBuilder.LOBBY);
        });
        node.addListener(PlayerSpawnEvent.class, event -> onSpawn(event.getPlayer()));
        node.addListener(PlayerMoveEvent.class, this::onMove);
        node.addListener(PlayerInputEvent.class, this::onInput);
        node.addListener(PlayerPacketEvent.class, this::onPacket);
        node.addListener(PlayerUseItemEvent.class, this::onUseItem);
        node.addListener(PlayerDisconnectEvent.class, event -> onDisconnect(event.getPlayer()));
        node.addListener(PlayerBlockBreakEvent.class, event -> event.setCancelled(true));
        node.addListener(PlayerBlockPlaceEvent.class, event -> event.setCancelled(true));
        MinecraftServer.getGlobalEventHandler().addChild(node);
    }

    public synchronized void join(Player player) {
        if (phase != Phase.WAITING) {
            player.sendMessage(Component.text("Заезд уже начался. Дождись следующего старта.", NamedTextColor.RED));
            return;
        }
        if (racers.containsKey(player.getUuid())) {
            player.sendMessage(Component.text("Ты уже в стартовом списке.", NamedTextColor.YELLOW));
            return;
        }
        if (racers.size() + bots.size() >= MAX_RACERS) {
            player.sendMessage(Component.text("Стартовая решётка заполнена (максимум " + MAX_RACERS + ").", NamedTextColor.RED));
            return;
        }

        int slot = firstFreeSlot();
        Racer racer = new Racer(player, slot);
        racer.input = currentInput(player);
        racers.put(player.getUuid(), racer);
        prepareRacer(player);
        Pos grid = TrackBuilder.gridPosition(slot);

        // Wait for the player's destination chunk, then create and mount their server-side cart.
        player.teleport(grid).whenComplete((ignored, error) -> {
            if (error != null) {
                failJoin(racer, error);
                return;
            }
            player.scheduler().scheduleNextTick(() -> spawnVehicle(racer, grid));
        });
        broadcast(Component.text(player.getUsername() + " подключился к разлому (" + entrantCount() + "/" + MAX_RACERS + ")", NamedTextColor.GRAY));
    }

    public synchronized void startSoloTest(Player player) {
        if (phase != Phase.WAITING) {
            player.sendMessage(Component.text("Соло-заезд можно подготовить только до старта.", NamedTextColor.RED));
            return;
        }
        if (!racers.containsKey(player.getUuid())) join(player);
        if (!racers.containsKey(player.getUuid())) return;
        addBotsInternal(Math.max(0, 3 - bots.size()));
        player.sendMessage(Component.text("Соло-сетка готова: AI-соперников — " + bots.size() + ". Запусти /race start.", NamedTextColor.LIGHT_PURPLE, TextDecoration.BOLD));
    }

    public synchronized void addBots(Player player, int requested) {
        if (phase != Phase.WAITING) {
            player.sendMessage(Component.text("Добавить ботов можно только до старта.", NamedTextColor.RED));
            return;
        }
        if (!racers.containsKey(player.getUuid())) {
            player.sendMessage(Component.text("Сначала зайди в очередь: /race join", NamedTextColor.YELLOW));
            return;
        }
        int added = addBotsInternal(requested);
        player.sendMessage(Component.text(added == 0
                        ? "На стартовой решётке нет свободных мест."
                        : "Добавлено AI-пилотов: " + added + ". Запусти /race start.",
                added == 0 ? NamedTextColor.YELLOW : NamedTextColor.LIGHT_PURPLE));
    }

    public synchronized void leave(Player player) {
        Racer racer = racers.remove(player.getUuid());
        if (racer == null) {
            player.sendMessage(Component.text("Ты не участвуешь в заезде.", NamedTextColor.GRAY));
            return;
        }
        racer.status = RacerStatus.LEFT;
        runOnVehicle(racer, () -> {
            removeVehicle(racer);
            restorePlayer(player);
            player.teleport(TrackBuilder.LOBBY);
            player.sendMessage(Component.text("Ты вернулся в лобби разлома.", NamedTextColor.GRAY));
        });
        if (racers.isEmpty()) {
            clearBots();
            phase = Phase.WAITING;
        } else if (phase == Phase.RUNNING) {
            finishIfEveryoneDone();
        }
    }

    public synchronized void startCountdown() {
        if (phase != Phase.WAITING) {
            broadcast(Component.text("Стартовая последовательность уже активна.", NamedTextColor.YELLOW));
            return;
        }
        List<Racer> queued = queuedRacers();
        if (queued.isEmpty()) {
            broadcast(Component.text("Сначала зайди в заезд: /race join", NamedTextColor.YELLOW));
            return;
        }
        if (queued.stream().anyMatch(racer -> racer.vehicle == null)
                || bots.stream().anyMatch(bot -> bot.vehicle == null)) {
            broadcast(Component.text("Стартовая решётка ещё загружается — попробуй снова через секунду.", NamedTextColor.YELLOW));
            return;
        }

        phase = Phase.COUNTDOWN;
        long generation = ++countdownGeneration;
        broadcast(Component.text("CHROMA RIFT // стартовая последовательность", NamedTextColor.LIGHT_PURPLE, TextDecoration.BOLD));
        countdown(generation, 3);
    }

    public synchronized void cancelRace() {
        if (phase == Phase.WAITING && racers.isEmpty() && bots.isEmpty()) {
            broadcast(Component.text("Сейчас нет активного заезда.", NamedTextColor.GRAY));
            return;
        }
        phase = Phase.WAITING;
        countdownGeneration++;
        List<Racer> previousRacers = new ArrayList<>(racers.values());
        racers.clear();
        clearBots();
        for (Racer racer : previousRacers) {
            racer.status = RacerStatus.LEFT;
            runOnVehicle(racer, () -> {
                removeVehicle(racer);
                restorePlayer(racer.player);
                racer.player.teleport(TrackBuilder.LOBBY);
                racer.player.sendActionBar(Component.text("ЗАЕЗД ОТМЕНЁН", NamedTextColor.RED));
            });
        }
        broadcast(Component.text("Заезд остановлен. Трасса снова ждёт пилотов.", NamedTextColor.GRAY));
    }

    public void showStatus(CommandSender sender) {
        String state = switch (phase) {
            case WAITING -> "ОЖИДАНИЕ ПИЛОТОВ";
            case COUNTDOWN -> "СТАРТОВАЯ ПОСЛЕДОВАТЕЛЬНОСТЬ";
            case RUNNING -> "ЗАЕЗД ИДЁТ";
        };
        sender.sendMessage(Component.text("CHROMA RIFT // " + state + " // " + entrantCount() + "/" + MAX_RACERS, NamedTextColor.AQUA));
        if (sender instanceof Player player) {
            Racer racer = racers.get(player.getUuid());
            if (racer != null) {
                synchronized (racer) {
                    sender.sendMessage(Component.text("Круг " + racer.lap + "/" + LAPS + " · следующая контрольная точка " + (racer.nextCheckpoint + 1) + "/4", NamedTextColor.GRAY));
                }
            }
        }
    }

    private void onSpawn(Player player) {
        player.setGameMode(GameMode.ADVENTURE);
        player.setRespawnPoint(TrackBuilder.LOBBY);
        player.getAttribute(Attribute.MOVEMENT_SPEED).setBaseValue(Attribute.MOVEMENT_SPEED.defaultValue());
        player.getInventory().clear();
        if (player.getInstance() != instance) player.teleport(TrackBuilder.LOBBY);
        player.sendMessage(Component.text("◆ CHROMA RIFT // MINESTOM 26.3", NamedTextColor.AQUA, TextDecoration.BOLD));
        player.sendMessage(Component.text("/race join — старт · /race solo — гонка с AI · /race start — старт отсчёта", NamedTextColor.GRAY));
        player.sendMessage(Component.text("/race leave — в лобби · /race status — статус гонки", NamedTextColor.GRAY));
        player.sendMessage(Component.text("Управление: W — газ · A/D — поворот · S — тормоз · ПКМ — импульс. Дрифт заряжается в повороте.", NamedTextColor.DARK_AQUA));
        ResourcePackHost.sendTo(player);
    }

    private void onMove(PlayerMoveEvent event) {
        Racer racer = racers.get(event.getPlayer().getUuid());
        if (racer != null && (racer.status == RacerStatus.QUEUED || racer.status == RacerStatus.RACING)) {
            // The cart is authoritative; discard player-position packets while mounted or queued.
            event.setCancelled(true);
        }
    }

    private void onInput(PlayerInputEvent event) {
        Racer racer = racers.get(event.getPlayer().getUuid());
        if (racer == null) return;
        racer.input = new InputState(
                event.isHoldingForwardKey(),
                event.isHoldingBackwardKey(),
                event.isHoldingLeftKey(),
                event.isHoldingRightKey());
    }

    private void onPacket(PlayerPacketEvent event) {
        if (event.getPacket() instanceof ClientVehicleMovePacket
                && racers.containsKey(event.getPlayer().getUuid())) {
            // Minestom normally trusts this packet and moves the vehicle. Our kart uses server physics.
            event.setCancelled(true);
        }
    }

    private void onUseItem(PlayerUseItemEvent event) {
        if (event.getItemStack().material() != Material.FIREWORK_ROCKET) return;
        event.setCancelled(true);

        Player player = event.getPlayer();
        Racer racer = racers.get(player.getUuid());
        if (racer == null || phase != Phase.RUNNING || racer.status != RacerStatus.RACING) {
            player.sendActionBar(Component.text("Импульс доступен только во время заезда.", NamedTextColor.GRAY));
            return;
        }

        synchronized (racer) {
            long now = System.currentTimeMillis();
            if (racer.boostCharges <= 0) {
                player.sendActionBar(Component.text("ЯЧЕЙКИ ИМПУЛЬСА ИЗРАСХОДОВАНЫ", NamedTextColor.RED));
                return;
            }
            if (now < racer.boostReadyAt) {
                long seconds = Math.max(1, (racer.boostReadyAt - now + 999) / 1_000);
                player.sendActionBar(Component.text("ИМПУЛЬС ПЕРЕЗАРЯЖАЕТСЯ · " + seconds + "с", NamedTextColor.YELLOW));
                return;
            }

            racer.boostCharges--;
            racer.boostReadyAt = now + BOOST_COOLDOWN_MS;
            racer.pulseUntilMillis = now + PULSE_DURATION_MS;
            racer.speed = Math.min(PULSE_MAX_SPEED, Math.max(13.0, racer.speed + 8.0));
            player.getInventory().setItemStack(0, pulseCell(racer.boostCharges));
            Pos pulsePosition = TrackBuilder.vehiclePosition(racer.angle, racer.laneOffset);
            emitPulseBurst(pulsePosition, racer.style.accentRgb());
            playSoundAt(SoundEvent.ENTITY_FIREWORK_ROCKET_LAUNCH, pulsePosition, 0.8f, 1.25f);
            player.sendActionBar(Component.text("PULSE OVERDRIVE  //  " + racer.boostCharges + " ЯЧЕЙКИ", NamedTextColor.AQUA, TextDecoration.BOLD));
        }
    }

    private void onDisconnect(Player player) {
        ResourcePackHost.forget(player);
        Racer removed = racers.remove(player.getUuid());
        if (removed != null) {
            removed.status = RacerStatus.LEFT;
            runOnVehicle(removed, () -> removeVehicle(removed));
            if (racers.isEmpty()) {
                clearBots();
                phase = Phase.WAITING;
            } else if (phase == Phase.RUNNING) {
                finishIfEveryoneDone();
            }
        }
        if (phase == Phase.COUNTDOWN && racers.isEmpty()) {
            clearBots();
            phase = Phase.WAITING;
        }
    }

    private int addBotsInternal(int requested) {
        String[] names = {"NOVA", "VEX", "ECHO", "GLITCH", "PULSE", "ION", "COMET"};
        double[] targetSpeeds = {17.2, 18.0, 18.8, 19.5, 20.2, 18.4, 21.0};
        int added = 0;
        while (added < requested && entrantCount() < MAX_RACERS) {
            int profile = bots.size() % names.length;
            int slot = firstFreeSlot();
            RaceBot bot = new RaceBot(names[profile], slot, targetSpeeds[profile],
                    KART_STYLES[Math.floorMod(slot, KART_STYLES.length)]);
            bots.add(bot);
            spawnBot(bot);
            added++;
        }
        return added;
    }

    private void spawnBot(RaceBot bot) {
        instance.scheduleNextTick(ignored -> spawnBotOnInstance(bot));
    }

    private void spawnBotOnInstance(RaceBot bot) {
        if (!bots.contains(bot) || bot.status != BotStatus.QUEUED) return;
        Pos grid = TrackBuilder.gridPosition(bot.slot);
        Entity vehicle = createSeatAnchor();
        vehicle.setCustomName(Component.text("AI · " + bot.name, NamedTextColor.LIGHT_PURPLE));
        vehicle.setCustomNameVisible(true);

        try {
            vehicle.setInstance(instance, grid).join();
            if (!bots.contains(bot) || bot.status != BotStatus.QUEUED) {
                vehicle.remove();
                return;
            }
            bot.vehicle = vehicle;
            bot.kartDisplay = createKartDisplay(bot.style, grid);
            if (!bots.contains(bot) || bot.status != BotStatus.QUEUED) {
                removeBotEntities(bot);
                return;
            }
            bot.lastTickNanos = System.nanoTime();
            bot.tickTask = vehicle.scheduler()
                    .buildTask(() -> tickBot(bot))
                    .repeat(Duration.ofMillis(50))
                    .schedule();
        } catch (RuntimeException exception) {
            if (bot.kartDisplay != null && !bot.kartDisplay.isRemoved()) bot.kartDisplay.remove();
            vehicle.remove();
            bots.remove(bot);
            bot.status = BotStatus.LEFT;
            System.err.println("Could not spawn AI kart " + bot.name + ": " + exception.getMessage());
        }
    }

    private void activateBot(RaceBot bot, long startAt) {
        synchronized (bot) {
            if (phase != Phase.RUNNING || !bots.contains(bot) || bot.vehicle == null) return;
            bot.status = BotStatus.RACING;
            bot.lap = 1;
            bot.nextCheckpoint = 0;
            bot.angle = TrackBuilder.startAngleForSlot(bot.slot);
            bot.laneOffset = TrackBuilder.laneOffsetForSlot(bot.slot);
            bot.speed = 0;
            bot.driftProgress = 0;
            bot.startedAtMillis = startAt;
            bot.finishedMillis = 0;
            bot.boostCharges = BOOST_CHARGES;
            bot.boostReadyAt = 0;
            bot.pulseUntilMillis = 0;
            bot.lastBotPulseAt = startAt;
            bot.lastEffectsAt = 0;
            bot.lastTickNanos = System.nanoTime();
            Pos position = TrackBuilder.vehiclePosition(bot.angle, bot.laneOffset);
            bot.vehicle.refreshPosition(position, false, true);
            updateKartDisplay(bot.kartDisplay, position);
            playSoundAt(SoundEvent.ENTITY_FIREWORK_ROCKET_LAUNCH, position, 0.45f, 0.9f);
        }
    }

    private void tickBot(RaceBot bot) {
        synchronized (bot) {
            if (!bots.contains(bot) || bot.status == BotStatus.LEFT || bot.status == BotStatus.FINISHED || bot.vehicle == null) return;
            long nowNanos = System.nanoTime();
            double deltaSeconds = Math.min(0.12, Math.max(0.0, (nowNanos - bot.lastTickNanos) / 1_000_000_000.0));
            bot.lastTickNanos = nowNanos;
            if (phase != Phase.RUNNING || bot.status != BotStatus.RACING) return;

            long nowMillis = System.currentTimeMillis();
            if (bot.boostCharges > 0 && nowMillis >= bot.boostReadyAt
                    && nowMillis - bot.lastBotPulseAt >= bot.boostIntervalMillis) {
                bot.boostCharges--;
                bot.boostReadyAt = nowMillis + BOOST_COOLDOWN_MS;
                bot.pulseUntilMillis = nowMillis + PULSE_DURATION_MS;
                bot.speed = Math.min(PULSE_MAX_SPEED, Math.max(13.0, bot.speed + 7.5));
                bot.lastBotPulseAt = nowMillis;
                Pos pulsePosition = bot.vehicle.getPosition();
                emitPulseBurst(pulsePosition, bot.style.accentRgb());
                playSoundAt(SoundEvent.ENTITY_FIREWORK_ROCKET_LAUNCH, pulsePosition, 0.48f, 1.1f);
            }

            InputState input = botInput(bot, nowMillis);
            double steering = (input.right() ? 1.0 : 0.0) - (input.left() ? 1.0 : 0.0);
            if (input.forward()) bot.speed += ACCELERATION * deltaSeconds;
            else bot.speed = Math.max(0.0, bot.speed - COAST_DECELERATION * deltaSeconds);
            if (input.backward()) bot.speed = Math.max(0.0, bot.speed - BRAKE_DECELERATION * deltaSeconds);
            double speedLimit = nowMillis < bot.pulseUntilMillis ? PULSE_MAX_SPEED : NORMAL_MAX_SPEED;
            bot.speed = Math.min(speedLimit, bot.speed);

            if (steering != 0) {
                double steeringRate = STEERING_RATE_BASE + Math.min(bot.speed, NORMAL_MAX_SPEED) * STEERING_RATE_SPEED;
                bot.laneOffset = clamp(bot.laneOffset + steering * steeringRate * deltaSeconds,
                        -MAX_LANE_OFFSET, MAX_LANE_OFFSET);
            }
            updateBotDrift(bot, steering, deltaSeconds);

            double pathScale = Math.hypot(TrackBuilder.RADIUS_X * Math.sin(bot.angle),
                    TrackBuilder.RADIUS_Z * Math.cos(bot.angle));
            if (pathScale > 0) bot.angle += bot.speed * deltaSeconds / pathScale;
            Pos position = TrackBuilder.vehiclePosition(bot.angle, bot.laneOffset);
            bot.vehicle.refreshPosition(position, false, true);
            updateKartDisplay(bot.kartDisplay, position);
            bot.lastEffectsAt = emitKartTrail(position, bot.angle, bot.speed, bot.style.accentRgb(),
                    bot.driftProgress >= 0.35, bot.lastEffectsAt, nowMillis);

            while (bot.status == BotStatus.RACING
                    && bot.angle + 1.0e-9 >= checkpointTargetAngle(bot.lap, bot.nextCheckpoint)) {
                if (bot.nextCheckpoint < 3) {
                    bot.nextCheckpoint++;
                    playSoundAt(SoundEvent.BLOCK_NOTE_BLOCK_PLING, position, 0.22f, 1.25f);
                } else {
                    bot.nextCheckpoint = 0;
                    if (bot.lap < LAPS) {
                        bot.lap++;
                        playSoundAt(SoundEvent.BLOCK_NOTE_BLOCK_PLING, position, 0.4f, 1.6f);
                    } else {
                        finishBot(bot, nowMillis);
                    }
                }
            }
        }
    }

    private InputState botInput(RaceBot bot, long nowMillis) {
        double laneWave = Math.sin(nowMillis / 2_300.0 + bot.slot * 1.7) * 1.35;
        double targetLane = clamp(bot.baseLaneOffset + laneWave, -4.9, 4.9);
        boolean left = bot.laneOffset > targetLane + 0.12;
        boolean right = bot.laneOffset < targetLane - 0.12;
        boolean brake = bot.speed > bot.targetSpeed + 0.9;
        return new InputState(!brake, brake, left, right);
    }

    private void updateBotDrift(RaceBot bot, double steering, double deltaSeconds) {
        boolean drifting = steering != 0 && bot.speed >= 6.0;
        if (drifting) {
            bot.driftProgress += deltaSeconds;
            bot.speed = Math.max(0.0, bot.speed - 0.4 * deltaSeconds);
            if (bot.driftProgress >= DRIFT_CHARGE_SECONDS) {
                bot.driftProgress = 0;
                if (bot.boostCharges < BOOST_CHARGES) {
                    bot.boostCharges++;
                    Pos position = bot.vehicle.getPosition();
                    emitDriftCharge(position, bot.style.accentRgb());
                    playSoundAt(SoundEvent.BLOCK_NOTE_BLOCK_PLING, position, 0.25f, 1.55f);
                }
            }
        } else {
            bot.driftProgress = Math.max(0.0, bot.driftProgress - deltaSeconds * 1.5);
        }
    }

    private void finishBot(RaceBot bot, long nowMillis) {
        if (bot.status != BotStatus.RACING) return;
        bot.status = BotStatus.FINISHED;
        bot.finishedMillis = Math.max(0, nowMillis - bot.startedAtMillis);
        Pos finishPosition = bot.vehicle.getPosition();
        emitFinishBurst(finishPosition, bot.style.accentRgb());
        removeBotEntities(bot);
        finishIfEveryoneDone();
    }

    private void removeBotEntities(RaceBot bot) {
        Task task = bot.tickTask;
        if (task != null) {
            task.cancel();
            bot.tickTask = null;
        }
        Entity display = bot.kartDisplay;
        bot.kartDisplay = null;
        if (display != null && !display.isRemoved()) display.remove();
        Entity vehicle = bot.vehicle;
        bot.vehicle = null;
        if (vehicle != null && !vehicle.isRemoved()) vehicle.remove();
    }

    private void stopBot(RaceBot bot) {
        bot.status = BotStatus.LEFT;
        Entity vehicle = bot.vehicle;
        if (vehicle != null && vehicle.isActive() && !vehicle.isRemoved()) {
            vehicle.scheduler().scheduleNextTick(() -> removeBotEntities(bot));
        } else {
            removeBotEntities(bot);
        }
    }

    private void clearBots() {
        List<RaceBot> previous = new ArrayList<>(bots);
        bots.clear();
        for (RaceBot bot : previous) stopBot(bot);
    }

    private void spawnVehicle(Racer racer, Pos grid) {
        if (racers.get(racer.player.getUuid()) != racer || racer.status != RacerStatus.QUEUED) return;

        Entity vehicle = createSeatAnchor();

        try {
            vehicle.setInstance(instance, grid).join();
            if (racers.get(racer.player.getUuid()) != racer || racer.status != RacerStatus.QUEUED) {
                vehicle.remove();
                return;
            }
            vehicle.refreshPosition(seatAnchorPosition(grid, vehicle, racer.player), false, true);
            vehicle.addPassenger(racer.player);
            racer.vehicle = vehicle;
            racer.kartDisplay = createKartDisplay(racer.style, grid);
            racer.angle = TrackBuilder.startAngleForSlot(racer.slot);
            racer.laneOffset = TrackBuilder.laneOffsetForSlot(racer.slot);
            racer.lastTickNanos = System.nanoTime();
            racer.tickTask = vehicle.scheduler()
                    .buildTask(() -> tickRacer(racer))
                    .repeat(Duration.ofMillis(50))
                    .schedule();
            racer.player.sendMessage(Component.text("Карт готов. W — газ · A/D — поворот · S — тормоз · ПКМ — PULSE.", NamedTextColor.AQUA));
        } catch (RuntimeException exception) {
            if (racer.kartDisplay != null && !racer.kartDisplay.isRemoved()) racer.kartDisplay.remove();
            vehicle.remove();
            failJoin(racer, exception);
        }
    }

    private Entity createSeatAnchor() {
        Entity seat = new Entity(EntityType.ARMOR_STAND);
        ArmorStandMeta metadata = (ArmorStandMeta) seat.getEntityMeta();
        metadata.setInvisible(true);
        metadata.setHasNoBasePlate(true);
        seat.setNoGravity(true);
        seat.setHasPhysics(false);
        seat.setVelocity(Vec.ZERO);
        return seat;
    }

    private static Pos seatAnchorPosition(Pos carPosition, Entity seat, Player player) {
        double passengerOffsetY = EntityUtils.getPassengerPositionOffset(seat, player, 0).y();
        return new Pos(carPosition.x(), carPosition.y() + DRIVER_SEAT_FOOT_OFFSET - passengerOffsetY,
                carPosition.z(), carPosition.yaw(), 0);
    }

    private Entity createKartDisplay(KartStyle style, Pos vehiclePosition) {
        Entity display = new Entity(EntityType.ITEM_DISPLAY);
        ItemDisplayMeta metadata = (ItemDisplayMeta) display.getEntityMeta();
        metadata.setItemStack(ItemStack.of(Material.PAPER).withItemModel(style.itemModel()));
        metadata.setDisplayContext(ItemDisplayMeta.DisplayContext.NONE);
        metadata.setScale(new Vec(1.55, 0.94, 2.0));
        metadata.setTranslation(displayTranslation(vehiclePosition.yaw()));
        metadata.setBrightness(15, 15);
        metadata.setShadowRadius(0.55f);
        metadata.setShadowStrength(0.8f);
        metadata.setViewRange(2.0f);
        // The display follows the seat anchor every server tick; zero interpolation keeps it tight to the rider.
        metadata.setTransformationInterpolationDuration(0);
        metadata.setPosRotInterpolationDuration(0);
        metadata.setLeftRotation(yawRotation(vehiclePosition.yaw()));
        display.setNoGravity(true);
        display.setHasPhysics(false);
        display.setInstance(instance, displayPosition(vehiclePosition)).join();
        return display;
    }

    private static Pos displayPosition(Pos vehiclePosition) {
        return new Pos(vehiclePosition.x(), vehiclePosition.y() + 0.12, vehiclePosition.z(), 0, 0);
    }

    private static float[] yawRotation(float yawDegrees) {
        double halfAngle = Math.toRadians(-yawDegrees) / 2.0;
        return new float[]{0, (float) Math.sin(halfAngle), 0, (float) Math.cos(halfAngle)};
    }

    private static Vec displayTranslation(float yawDegrees) {
        Vec modelCenter = new Vec(1.55 * 0.5, 0, 2.0 * 0.5)
                .rotateAroundY(Math.toRadians(-yawDegrees));
        return new Vec(-modelCenter.x(), 0, -modelCenter.z());
    }

    private void updateKartDisplay(Entity display, Pos vehiclePosition) {
        if (display == null || display.isRemoved()) return;
        display.refreshPosition(displayPosition(vehiclePosition), false, true);
        AbstractDisplayMeta metadata = (AbstractDisplayMeta) display.getEntityMeta();
        metadata.setLeftRotation(yawRotation(vehiclePosition.yaw()));
        metadata.setTranslation(displayTranslation(vehiclePosition.yaw()));
    }

    private void failJoin(Racer racer, Throwable error) {
        synchronized (this) {
            racers.remove(racer.player.getUuid(), racer);
            racer.status = RacerStatus.LEFT;
            if (racers.isEmpty()) {
                clearBots();
                phase = Phase.WAITING;
            }
        }
        System.err.println("Could not put racer on the grid: " + error.getMessage());
        runOnVehicle(racer, () -> {
            removeVehicle(racer);
            restorePlayer(racer.player);
            racer.player.teleport(TrackBuilder.LOBBY);
            racer.player.sendMessage(Component.text("Не удалось загрузить стартовую решётку. Попробуй /race join ещё раз.", NamedTextColor.RED));
        });
    }

    private void tickRacer(Racer racer) {
        synchronized (racer) {
            if (racers.get(racer.player.getUuid()) != racer || racer.vehicle == null
                    || racer.status == RacerStatus.LEFT || racer.status == RacerStatus.FINISHED) {
                return;
            }

            Entity vehicle = racer.vehicle;
            if (racer.player.getVehicle() != vehicle) vehicle.addPassenger(racer.player);

            long nowNanos = System.nanoTime();
            double deltaSeconds = Math.min(0.12, Math.max(0.0, (nowNanos - racer.lastTickNanos) / 1_000_000_000.0));
            racer.lastTickNanos = nowNanos;
            if (phase != Phase.RUNNING || racer.status != RacerStatus.RACING) return;

            long nowMillis = System.currentTimeMillis();
            InputState input = racer.input;
            double steering = (input.right() ? 1.0 : 0.0) - (input.left() ? 1.0 : 0.0);

            if (input.forward()) {
                racer.speed += ACCELERATION * deltaSeconds;
            } else {
                racer.speed = Math.max(0.0, racer.speed - COAST_DECELERATION * deltaSeconds);
            }
            if (input.backward()) racer.speed = Math.max(0.0, racer.speed - BRAKE_DECELERATION * deltaSeconds);
            double speedLimit = nowMillis < racer.pulseUntilMillis ? PULSE_MAX_SPEED : NORMAL_MAX_SPEED;
            racer.speed = Math.min(speedLimit, racer.speed);

            if (steering != 0) {
                double steeringRate = STEERING_RATE_BASE + Math.min(racer.speed, NORMAL_MAX_SPEED) * STEERING_RATE_SPEED;
                racer.laneOffset = clamp(racer.laneOffset + steering * steeringRate * deltaSeconds,
                        -MAX_LANE_OFFSET, MAX_LANE_OFFSET);
            }
            updateDrift(racer, steering, deltaSeconds);

            double pathScale = Math.hypot(
                    TrackBuilder.RADIUS_X * Math.sin(racer.angle),
                    TrackBuilder.RADIUS_Z * Math.cos(racer.angle));
            if (pathScale > 0) racer.angle += racer.speed * deltaSeconds / pathScale;
            Pos vehiclePosition = TrackBuilder.vehiclePosition(racer.angle, racer.laneOffset);
            vehicle.refreshPosition(seatAnchorPosition(vehiclePosition, vehicle, racer.player), false, true);
            updateKartDisplay(racer.kartDisplay, vehiclePosition);
            racer.lastEffectsAt = emitKartTrail(vehiclePosition, racer.angle, racer.speed,
                    racer.style.accentRgb(), racer.driftProgress >= 0.35, racer.lastEffectsAt, nowMillis);

            while (racer.status == RacerStatus.RACING
                    && racer.angle + 1.0e-9 >= checkpointTargetAngle(racer.lap, racer.nextCheckpoint)) {
                checkpointReached(racer);
            }

            if (racer.status == RacerStatus.RACING && nowMillis - racer.lastHudAt >= HUD_INTERVAL_MS) {
                racer.lastHudAt = nowMillis;
                long elapsed = Math.max(0, nowMillis - racer.startedAtMillis);
                Component message = Component.text("LAP " + racer.lap + "/" + LAPS
                                + "   ·   GATE " + (racer.nextCheckpoint + 1) + "/4   ·   "
                                + formatTime(elapsed) + "   ·   PULSE " + racer.boostCharges,
                        NamedTextColor.AQUA);
                racer.player.sendActionBar(message);
            }
        }
    }

    private long emitKartTrail(Pos position, double angle, double speed, int accentRgb,
                               boolean drifting, long lastEmittedAt, long nowMillis) {
        if (speed < 3.0 || nowMillis - lastEmittedAt < 100) return lastEmittedAt;
        double tangentX = -TrackBuilder.RADIUS_X * Math.sin(angle);
        double tangentZ = TrackBuilder.RADIUS_Z * Math.cos(angle);
        double tangentLength = Math.hypot(tangentX, tangentZ);
        if (tangentLength == 0) return lastEmittedAt;
        tangentX /= tangentLength;
        tangentZ /= tangentLength;
        Pos exhaust = new Pos(position.x() - tangentX * 0.95, position.y() - 0.18,
                position.z() - tangentZ * 0.95);
        float size = drifting ? 1.15f : 0.72f;
        emitParticle(Particle.DUST.withProperties(new Color(accentRgb), size), exhaust,
                new Vec(drifting ? 0.24 : 0.11, 0.055, drifting ? 0.24 : 0.11), 0.01f, drifting ? 3 : 1);
        if (drifting) {
            emitParticle(Particle.CLOUD, exhaust, new Vec(0.22, 0.05, 0.22), 0.012f, 2);
        } else if (speed > 14.0 && nowMillis % 200 < 100) {
            emitParticle(Particle.END_ROD, exhaust, new Vec(0.08, 0.04, 0.08), 0.015f, 1);
        }
        return nowMillis;
    }

    private void emitPulseBurst(Pos position, int accentRgb) {
        emitParticle(Particle.DUST.withProperties(new Color(accentRgb), 1.55f), position.add(0, 0.08, 0),
                new Vec(0.38, 0.24, 0.38), 0.08f, 14);
        emitParticle(Particle.ELECTRIC_SPARK, position.add(0, 0.1, 0), new Vec(0.32, 0.2, 0.32), 0.08f, 10);
        emitParticle(Particle.END_ROD, position.add(0, 0.12, 0), new Vec(0.24, 0.18, 0.24), 0.04f, 6);
    }

    private void emitDriftCharge(Pos position, int accentRgb) {
        emitParticle(Particle.DUST.withProperties(new Color(accentRgb), 1.2f), position.add(0, 0.28, 0),
                new Vec(0.3, 0.2, 0.3), 0.04f, 10);
        emitParticle(Particle.ENCHANT, position.add(0, 0.35, 0), new Vec(0.24, 0.2, 0.24), 0.03f, 8);
    }

    private void emitFinishBurst(Pos position, int accentRgb) {
        emitParticle(Particle.DUST.withProperties(new Color(accentRgb), 1.8f), position.add(0, 0.3, 0),
                new Vec(0.48, 0.38, 0.48), 0.08f, 24);
        emitParticle(Particle.FIREWORK, position.add(0, 0.5, 0), new Vec(0.3, 0.35, 0.3), 0.06f, 12);
        playSoundAt(SoundEvent.ENTITY_FIREWORK_ROCKET_BLAST, position, 0.65f, 1.15f);
    }

    private void emitParticle(Particle particle, Pos position, Vec spread, float speed, int count) {
        ParticlePacket packet = new ParticlePacket(particle, position, spread, speed, count);
        for (Player viewer : instance.getPlayers()) {
            Pos viewerPosition = viewer.getPosition();
            double dx = viewerPosition.x() - position.x();
            double dy = viewerPosition.y() - position.y();
            double dz = viewerPosition.z() - position.z();
            if (dx * dx + dy * dy + dz * dz <= 64 * 64) viewer.sendPacket(packet);
        }
    }

    private void playSoundAt(SoundEvent event, Pos position, float volume, float pitch) {
        Sound sound = Sound.sound(event, Sound.Source.MASTER, volume, pitch);
        for (Player viewer : instance.getPlayers()) {
            Pos viewerPosition = viewer.getPosition();
            double dx = viewerPosition.x() - position.x();
            double dy = viewerPosition.y() - position.y();
            double dz = viewerPosition.z() - position.z();
            if (dx * dx + dy * dy + dz * dz <= 48 * 48) viewer.playSound(sound, position);
        }
    }

    private void updateDrift(Racer racer, double steering, double deltaSeconds) {
        boolean drifting = steering != 0 && racer.speed >= 6.0;
        if (drifting) {
            racer.driftProgress += deltaSeconds;
            racer.speed = Math.max(0.0, racer.speed - 0.4 * deltaSeconds);
            if (racer.driftProgress >= DRIFT_CHARGE_SECONDS) {
                racer.driftProgress = 0;
                if (racer.boostCharges < BOOST_CHARGES) {
                    racer.boostCharges++;
                    racer.player.getInventory().setItemStack(0, pulseCell(racer.boostCharges));
                    Pos driftPosition = TrackBuilder.vehiclePosition(racer.angle, racer.laneOffset);
                    emitDriftCharge(driftPosition, racer.style.accentRgb());
                    playSoundAt(SoundEvent.BLOCK_NOTE_BLOCK_PLING, driftPosition, 0.5f, 1.6f);
                    racer.player.sendActionBar(Component.text("DRIFT CHARGED  //  +1 PULSE", NamedTextColor.LIGHT_PURPLE, TextDecoration.BOLD));
                }
            }
        } else {
            racer.driftProgress = Math.max(0.0, racer.driftProgress - deltaSeconds * 1.5);
        }
    }

    private void checkpointReached(Racer racer) {
        Player player = racer.player;
        int passed = racer.nextCheckpoint;
        if (passed < 3) {
            racer.nextCheckpoint++;
            player.playSound(Sound.sound(SoundEvent.BLOCK_NOTE_BLOCK_PLING, Sound.Source.PLAYER, 0.45f, 1.2f + passed * 0.12f));
            player.sendActionBar(Component.text("GATE " + (passed + 1) + "/4  ·  LAP " + racer.lap + "/" + LAPS, NamedTextColor.GREEN));
            return;
        }

        racer.nextCheckpoint = 0;
        if (racer.lap < LAPS) {
            racer.lap++;
            player.playSound(Sound.sound(SoundEvent.BLOCK_NOTE_BLOCK_PLING, Sound.Source.PLAYER, 0.8f, 1.6f));
            player.sendMessage(Component.text("КРУГ " + racer.lap + "/" + LAPS + " — держи темп!", NamedTextColor.LIGHT_PURPLE, TextDecoration.BOLD));
            return;
        }

        finishRacer(racer);
    }

    private synchronized void finishRacer(Racer racer) {
        if (racer.status != RacerStatus.RACING) return;
        racer.status = RacerStatus.FINISHED;
        racer.finishedMillis = Math.max(0, System.currentTimeMillis() - racer.startedAtMillis);
        long previousBest = personalRecords.getOrDefault(racer.player.getUuid(), Long.MAX_VALUE);
        long best = Math.min(previousBest, racer.finishedMillis);
        personalRecords.put(racer.player.getUuid(), best);
        saveRecords();

        if (racer.vehicle != null) {
            emitFinishBurst(TrackBuilder.vehiclePosition(racer.angle, racer.laneOffset), racer.style.accentRgb());
        }
        removeVehicle(racer);
        restorePlayer(racer.player);
        racer.player.teleport(TrackBuilder.LOBBY);
        racer.player.playSound(Sound.sound(SoundEvent.UI_TOAST_CHALLENGE_COMPLETE, Sound.Source.PLAYER, 0.9f, 1.1f));
        racer.player.sendActionBar(Component.text("ФИНИШ · " + formatTime(racer.finishedMillis), NamedTextColor.GREEN, TextDecoration.BOLD));
        racer.player.sendMessage(Component.text("Ты завершил заезд за " + formatTime(racer.finishedMillis) + ". Лучшее время: " + formatTime(best) + ".", NamedTextColor.GOLD));
        finishIfEveryoneDone();
    }

    private synchronized void finishIfEveryoneDone() {
        if (phase != Phase.RUNNING) return;
        boolean humanStillRacing = racers.values().stream().anyMatch(racer ->
                racer.status == RacerStatus.RACING || racer.status == RacerStatus.QUEUED);
        boolean botStillRacing = bots.stream().anyMatch(bot ->
                bot.status == BotStatus.RACING || bot.status == BotStatus.QUEUED);
        if (humanStillRacing || botStillRacing) return;

        List<FinishEntry> results = new ArrayList<>();
        for (Racer racer : racers.values()) {
            if (racer.status == RacerStatus.FINISHED) results.add(new FinishEntry(racer.player.getUsername(), racer.finishedMillis, false));
        }
        for (RaceBot bot : bots) {
            if (bot.status == BotStatus.FINISHED) results.add(new FinishEntry("AI · " + bot.name, bot.finishedMillis, true));
        }
        results.sort(Comparator.comparingLong(FinishEntry::millis));
        broadcast(Component.text("═══ CHROMA RIFT · ФИНИШ ═══", NamedTextColor.LIGHT_PURPLE, TextDecoration.BOLD));
        int place = 1;
        for (FinishEntry result : results) {
            NamedTextColor color = place == 1 ? NamedTextColor.GOLD : result.bot() ? NamedTextColor.LIGHT_PURPLE : NamedTextColor.GRAY;
            broadcast(Component.text(place + ". " + result.name() + "  ·  " + formatTime(result.millis()), color));
            place++;
        }
        phase = Phase.WAITING;
        racers.clear();
        bots.clear();
    }

    private synchronized void countdown(long generation, int secondsLeft) {
        if (phase != Phase.COUNTDOWN || generation != countdownGeneration) return;
        if (queuedRacers().isEmpty()) {
            phase = Phase.WAITING;
            return;
        }
        if (secondsLeft == 0) {
            beginRace();
            return;
        }
        for (Racer racer : queuedRacers()) {
            racer.player.sendActionBar(Component.text("СТАРТ ЧЕРЕЗ " + secondsLeft, NamedTextColor.YELLOW, TextDecoration.BOLD));
        }
        MinecraftServer.getSchedulerManager()
                .buildTask(() -> countdown(generation, secondsLeft - 1))
                .delay(Duration.ofSeconds(1))
                .schedule();
    }

    private synchronized void beginRace() {
        if (phase != Phase.COUNTDOWN) return;
        phase = Phase.RUNNING;
        long startAt = System.currentTimeMillis() + 50;
        for (Racer racer : queuedRacers()) {
            Entity vehicle = racer.vehicle;
            if (vehicle == null) continue;
            vehicle.scheduler().scheduleNextTick(() -> activateRacer(racer, startAt));
        }
        for (RaceBot bot : bots) {
            Entity vehicle = bot.vehicle;
            if (vehicle != null && bot.status == BotStatus.QUEUED) {
                vehicle.scheduler().scheduleNextTick(() -> activateBot(bot, startAt));
            }
        }
        broadcast(Component.text("СТАРТ! Удачи пилотам CHROMA RIFT.", NamedTextColor.GREEN, TextDecoration.BOLD));
    }

    private void activateRacer(Racer racer, long startAt) {
        synchronized (racer) {
            if (phase != Phase.RUNNING || racers.get(racer.player.getUuid()) != racer || racer.vehicle == null) return;
            racer.status = RacerStatus.RACING;
            racer.lap = 1;
            racer.nextCheckpoint = 0;
            racer.angle = TrackBuilder.startAngleForSlot(racer.slot);
            racer.laneOffset = TrackBuilder.laneOffsetForSlot(racer.slot);
            racer.speed = 0;
            racer.driftProgress = 0;
            racer.startedAtMillis = startAt;
            racer.boostCharges = BOOST_CHARGES;
            racer.boostReadyAt = 0;
            racer.pulseUntilMillis = 0;
            racer.lastHudAt = 0;
            racer.lastTickNanos = System.nanoTime();
            racer.player.getInventory().setItemStack(0, pulseCell(racer.boostCharges));
            Pos vehiclePosition = TrackBuilder.vehiclePosition(racer.angle, racer.laneOffset);
            racer.vehicle.refreshPosition(seatAnchorPosition(vehiclePosition, racer.vehicle, racer.player), false, true);
            updateKartDisplay(racer.kartDisplay, vehiclePosition);
            racer.player.playSound(Sound.sound(SoundEvent.ENTITY_FIREWORK_ROCKET_LAUNCH, Sound.Source.PLAYER, 0.65f, 0.75f));
            racer.player.sendMessage(Component.text("GO! Три круга. Пройди четыре портала в правильном порядке.", NamedTextColor.GREEN, TextDecoration.BOLD));
        }
    }

    private void prepareRacer(Player player) {
        player.setGameMode(GameMode.ADVENTURE);
        player.setVelocity(Vec.ZERO);
        player.getAttribute(Attribute.MOVEMENT_SPEED).setBaseValue(Attribute.MOVEMENT_SPEED.defaultValue());
        player.getInventory().clear();
        player.getInventory().setItemStack(0, pulseCell(BOOST_CHARGES));
        player.setRespawnPoint(TrackBuilder.LOBBY);
    }

    private void restorePlayer(Player player) {
        player.setVelocity(Vec.ZERO);
        player.setSprinting(false);
        player.getAttribute(Attribute.MOVEMENT_SPEED).setBaseValue(Attribute.MOVEMENT_SPEED.defaultValue());
        player.getInventory().clear();
    }

    private void runOnVehicle(Racer racer, Runnable action) {
        Task task = racer.tickTask;
        if (task != null) task.cancel();
        Entity vehicle = racer.vehicle;
        if (vehicle == null) {
            racer.player.scheduler().scheduleNextTick(action);
        } else if (vehicle.isActive() && !vehicle.isRemoved()) {
            vehicle.scheduler().scheduleNextTick(action);
        } else {
            racer.player.scheduler().scheduleNextTick(action);
        }
    }

    private void removeVehicle(Racer racer) {
        Task task = racer.tickTask;
        if (task != null) {
            task.cancel();
            racer.tickTask = null;
        }
        Entity display = racer.kartDisplay;
        racer.kartDisplay = null;
        if (display != null && !display.isRemoved()) display.remove();

        Entity vehicle = racer.vehicle;
        racer.vehicle = null;
        if (vehicle == null || vehicle.isRemoved()) return;
        if (racer.player.getVehicle() == vehicle) vehicle.removePassenger(racer.player);
        vehicle.remove();
    }

    private List<Racer> queuedRacers() {
        return racers.values().stream()
                .filter(racer -> racer.status == RacerStatus.QUEUED)
                .sorted(Comparator.comparingInt(racer -> racer.slot))
                .toList();
    }

    private int entrantCount() {
        return racers.size() + bots.size();
    }

    private int firstFreeSlot() {
        boolean[] occupied = new boolean[MAX_RACERS];
        for (Racer racer : racers.values()) {
            if (racer.slot >= 0 && racer.slot < MAX_RACERS) occupied[racer.slot] = true;
        }
        for (RaceBot bot : bots) {
            if (bot.slot >= 0 && bot.slot < MAX_RACERS) occupied[bot.slot] = true;
        }
        for (int slot = 0; slot < MAX_RACERS; slot++) {
            if (!occupied[slot]) return slot;
        }
        return 0;
    }

    private void broadcast(Component message) {
        for (Player online : MinecraftServer.getConnectionManager().getOnlinePlayers()) {
            online.sendMessage(message);
        }
    }

    private static ItemStack pulseCell(int amount) {
        if (amount <= 0) return ItemStack.AIR;
        return ItemStack.of(Material.FIREWORK_ROCKET, amount)
                .with(DataComponents.CUSTOM_NAME, Component.text("PULSE CELL  //  RIGHT CLICK", NamedTextColor.AQUA, TextDecoration.BOLD));
    }

    private static InputState currentInput(Player player) {
        var input = player.inputs();
        return new InputState(input.forward(), input.backward(), input.left(), input.right());
    }

    private static double checkpointTargetAngle(int lap, int checkpoint) {
        double checkpointAngle = switch (checkpoint) {
            case 0 -> 0.0;
            case 1 -> Math.PI / 2.0;
            case 2 -> Math.PI;
            default -> Math.PI * 1.5;
        };
        return (lap - 1) * TAU + checkpointAngle;
    }

    private static double clamp(double value, double minimum, double maximum) {
        return Math.max(minimum, Math.min(maximum, value));
    }

    private static String formatTime(long millis) {
        long minutes = millis / 60_000;
        long seconds = (millis / 1_000) % 60;
        long remainder = millis % 1_000;
        return String.format("%02d:%02d.%03d", minutes, seconds, remainder);
    }

    private void loadRecords() {
        if (!Files.isRegularFile(RECORDS_FILE)) return;
        Properties properties = new Properties();
        try (InputStream input = Files.newInputStream(RECORDS_FILE)) {
            properties.load(input);
            for (String key : properties.stringPropertyNames()) {
                try {
                    personalRecords.put(UUID.fromString(key), Long.parseLong(properties.getProperty(key)));
                } catch (IllegalArgumentException ignored) {
                    // Ignore a malformed record and keep the rest of the file.
                }
            }
        } catch (IOException exception) {
            System.err.println("Could not load race records: " + exception.getMessage());
        }
    }

    private synchronized void saveRecords() {
        Properties properties = new Properties();
        personalRecords.forEach((uuid, millis) -> properties.setProperty(uuid.toString(), Long.toString(millis)));
        try {
            Files.createDirectories(RECORDS_FILE.getParent());
            try (OutputStream output = Files.newOutputStream(RECORDS_FILE)) {
                properties.store(output, "CHROMA RIFT personal bests");
            }
        } catch (IOException exception) {
            System.err.println("Could not save race records: " + exception.getMessage());
        }
    }

    private record KartStyle(String itemModel, int accentRgb) {
    }

    private record FinishEntry(String name, long millis, boolean bot) {
    }

    private record InputState(boolean forward, boolean backward, boolean left, boolean right) {
        private static final InputState NONE = new InputState(false, false, false, false);
    }

    private enum Phase {
        WAITING,
        COUNTDOWN,
        RUNNING
    }

    private enum RacerStatus {
        QUEUED,
        RACING,
        FINISHED,
        LEFT
    }

    private enum BotStatus {
        QUEUED,
        RACING,
        FINISHED,
        LEFT
    }

    private static final class RaceBot {
        private final String name;
        private final int slot;
        private final double targetSpeed;
        private final long boostIntervalMillis;
        private final double baseLaneOffset;
        private final KartStyle style;
        private volatile BotStatus status = BotStatus.QUEUED;
        private volatile Entity vehicle;
        private volatile Entity kartDisplay;
        private Task tickTask;
        private int lap = 1;
        private int nextCheckpoint;
        private int boostCharges = BOOST_CHARGES;
        private long startedAtMillis;
        private volatile long finishedMillis;
        private long boostReadyAt;
        private long pulseUntilMillis;
        private long lastBotPulseAt;
        private long lastEffectsAt;
        private long lastTickNanos;
        private double angle;
        private double laneOffset;
        private double speed;
        private double driftProgress;

        private RaceBot(String name, int slot, double targetSpeed, KartStyle style) {
            this.name = name;
            this.slot = slot;
            this.targetSpeed = targetSpeed;
            this.style = style;
            this.baseLaneOffset = TrackBuilder.laneOffsetForSlot(slot);
            this.boostIntervalMillis = 5_500L + slot * 260L;
            this.angle = TrackBuilder.startAngleForSlot(slot);
            this.laneOffset = baseLaneOffset;
        }
    }

    private static final class Racer {
        private final Player player;
        private final int slot;
        private final KartStyle style;
        private volatile RacerStatus status = RacerStatus.QUEUED;
        private volatile Entity vehicle;
        private volatile Entity kartDisplay;
        private Task tickTask;
        private long lastEffectsAt;
        private int lap = 1;
        private int nextCheckpoint;
        private int boostCharges = BOOST_CHARGES;
        private long startedAtMillis;
        private long finishedMillis;
        private long boostReadyAt;
        private long pulseUntilMillis;
        private long lastHudAt;
        private long lastTickNanos;
        private double angle;
        private double laneOffset;
        private double speed;
        private double driftProgress;
        private volatile InputState input = InputState.NONE;

        private Racer(Player player, int slot) {
            this.player = player;
            this.slot = slot;
            this.style = KART_STYLES[Math.floorMod(slot, KART_STYLES.length)];
            this.angle = TrackBuilder.startAngleForSlot(slot);
            this.laneOffset = TrackBuilder.laneOffsetForSlot(slot);
        }
    }
}
