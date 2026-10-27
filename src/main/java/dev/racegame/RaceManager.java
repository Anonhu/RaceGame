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
import net.minestom.server.entity.GameMode;
import net.minestom.server.entity.Player;
import net.minestom.server.entity.attribute.Attribute;
import net.minestom.server.entity.metadata.minecart.AbstractMinecartMeta;
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
import net.minestom.server.instance.block.Block;
import net.minestom.server.item.ItemStack;
import net.minestom.server.item.Material;
import net.minestom.server.network.packet.client.play.ClientVehicleMovePacket;
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

public final class RaceManager {
    private static final int LAPS = 3;
    private static final int MAX_RACERS = 8;
    private static final int BOOST_CHARGES = 3;
    private static final long BOOST_COOLDOWN_MS = 1_500;
    private static final long PULSE_DURATION_MS = 900;
    private static final long HUD_INTERVAL_MS = 450;
    private static final double NORMAL_MAX_SPEED = 17.5;
    private static final double PULSE_MAX_SPEED = 23.0;
    private static final double MAX_LANE_OFFSET = 5.8;
    private static final double DRIFT_CHARGE_SECONDS = 1.35;
    private static final double TAU = Math.PI * 2.0;
    private static final Path RECORDS_FILE = Path.of("data", "chroma-rift-records.properties");
    private static final Block[] CART_COLORS = {
            Block.CYAN_CONCRETE,
            Block.MAGENTA_CONCRETE,
            Block.LIME_CONCRETE,
            Block.PURPLE_CONCRETE
    };

    private final InstanceContainer instance;
    private final Map<UUID, Racer> racers = new ConcurrentHashMap<>();
    private final Map<UUID, Long> personalRecords = new ConcurrentHashMap<>();
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
        if (racers.size() >= MAX_RACERS) {
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
        broadcast(Component.text(player.getUsername() + " подключился к разлому (" + racers.size() + "/" + MAX_RACERS + ")", NamedTextColor.GRAY));
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
        if (phase == Phase.RUNNING) finishIfEveryoneDone();
        if (phase == Phase.COUNTDOWN && racers.isEmpty()) phase = Phase.WAITING;
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
        if (queued.stream().anyMatch(racer -> racer.vehicle == null)) {
            broadcast(Component.text("Стартовая решётка ещё загружается — попробуй снова через секунду.", NamedTextColor.YELLOW));
            return;
        }

        phase = Phase.COUNTDOWN;
        long generation = ++countdownGeneration;
        broadcast(Component.text("CHROMA RIFT // стартовая последовательность", NamedTextColor.LIGHT_PURPLE, TextDecoration.BOLD));
        countdown(generation, 3);
    }

    public synchronized void cancelRace() {
        if (phase == Phase.WAITING && racers.isEmpty()) {
            broadcast(Component.text("Сейчас нет активного заезда.", NamedTextColor.GRAY));
            return;
        }
        phase = Phase.WAITING;
        countdownGeneration++;
        List<Racer> previousRacers = new ArrayList<>(racers.values());
        racers.clear();
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
        sender.sendMessage(Component.text("CHROMA RIFT // " + state + " // " + racers.size() + "/" + MAX_RACERS, NamedTextColor.AQUA));
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
        player.sendMessage(Component.text("/race join — на старт · /race start — гонка · /race leave — лобби", NamedTextColor.GRAY));
        player.sendMessage(Component.text("Удерживай W + Sprint; A/D меняют полосу, Sprint + A/D заряжает дрифт.", NamedTextColor.DARK_AQUA));
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
                event.isHoldingRightKey(),
                event.isHoldingSprintKey());
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
            player.playSound(Sound.sound(SoundEvent.ENTITY_FIREWORK_ROCKET_LAUNCH, Sound.Source.PLAYER, 0.8f, 1.25f));
            player.sendActionBar(Component.text("PULSE OVERDRIVE  //  " + racer.boostCharges + " ЯЧЕЙКИ", NamedTextColor.AQUA, TextDecoration.BOLD));
        }
    }

    private void onDisconnect(Player player) {
        Racer removed = racers.remove(player.getUuid());
        if (removed != null) {
            removed.status = RacerStatus.LEFT;
            runOnVehicle(removed, () -> removeVehicle(removed));
            if (phase == Phase.RUNNING) finishIfEveryoneDone();
        }
        if (phase == Phase.COUNTDOWN && racers.isEmpty()) phase = Phase.WAITING;
    }

    private void spawnVehicle(Racer racer, Pos grid) {
        if (racers.get(racer.player.getUuid()) != racer || racer.status != RacerStatus.QUEUED) return;

        Entity vehicle = new Entity(EntityType.MINECART);
        AbstractMinecartMeta metadata = (AbstractMinecartMeta) vehicle.getEntityMeta();
        metadata.setCustomBlockState(CART_COLORS[Math.floorMod(racer.slot, CART_COLORS.length)]);
        metadata.setCustomBlockYPosition(6);
        vehicle.setNoGravity(true);
        vehicle.setHasPhysics(false);
        vehicle.setVelocity(Vec.ZERO);

        try {
            vehicle.setInstance(instance, grid).join();
            vehicle.addPassenger(racer.player);
            racer.vehicle = vehicle;
            racer.angle = TrackBuilder.startAngleForSlot(racer.slot);
            racer.laneOffset = TrackBuilder.laneOffsetForSlot(racer.slot);
            racer.lastTickNanos = System.nanoTime();
            racer.tickTask = vehicle.scheduler()
                    .buildTask(() -> tickRacer(racer))
                    .repeat(Duration.ofMillis(50))
                    .schedule();
            racer.player.sendMessage(Component.text("Карт готов. W + Sprint — разгон · A/D — линия · ячейка PULSE — рывок.", NamedTextColor.AQUA));
        } catch (RuntimeException exception) {
            vehicle.remove();
            failJoin(racer, exception);
        }
    }

    private void failJoin(Racer racer, Throwable error) {
        synchronized (this) {
            racers.remove(racer.player.getUuid(), racer);
            racer.status = RacerStatus.LEFT;
            if (phase == Phase.COUNTDOWN && racers.isEmpty()) phase = Phase.WAITING;
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
                racer.speed += (input.sprint() ? 12.0 : 8.5) * deltaSeconds;
            } else {
                racer.speed = Math.max(0.0, racer.speed - 3.2 * deltaSeconds);
            }
            if (input.backward()) racer.speed = Math.max(0.0, racer.speed - 15.0 * deltaSeconds);
            double speedLimit = nowMillis < racer.pulseUntilMillis ? PULSE_MAX_SPEED : NORMAL_MAX_SPEED;
            racer.speed = Math.min(speedLimit, racer.speed);

            if (steering != 0) {
                double steeringRate = 3.1 + racer.speed * 0.055;
                if (input.sprint() && racer.speed >= 7.0) steeringRate *= 1.18;
                racer.laneOffset = clamp(racer.laneOffset + steering * steeringRate * deltaSeconds,
                        -MAX_LANE_OFFSET, MAX_LANE_OFFSET);
            }
            updateDrift(racer, input, steering, deltaSeconds);

            double pathScale = Math.hypot(
                    TrackBuilder.RADIUS_X * Math.sin(racer.angle),
                    TrackBuilder.RADIUS_Z * Math.cos(racer.angle));
            if (pathScale > 0) racer.angle += racer.speed * deltaSeconds / pathScale;
            vehicle.refreshPosition(TrackBuilder.vehiclePosition(racer.angle, racer.laneOffset), false, true);

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

    private void updateDrift(Racer racer, InputState input, double steering, double deltaSeconds) {
        boolean drifting = steering != 0 && input.sprint() && racer.speed >= 7.0;
        if (drifting) {
            racer.driftProgress += deltaSeconds;
            racer.speed = Math.max(0.0, racer.speed - 0.9 * deltaSeconds);
            if (racer.driftProgress >= DRIFT_CHARGE_SECONDS) {
                racer.driftProgress = 0;
                if (racer.boostCharges < BOOST_CHARGES) {
                    racer.boostCharges++;
                    racer.player.getInventory().setItemStack(0, pulseCell(racer.boostCharges));
                    racer.player.playSound(Sound.sound(SoundEvent.BLOCK_NOTE_BLOCK_PLING, Sound.Source.PLAYER, 0.5f, 1.6f));
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
        List<Racer> results = new ArrayList<>(racers.values());
        boolean stillRacing = results.stream().anyMatch(racer ->
                racer.status == RacerStatus.RACING || racer.status == RacerStatus.QUEUED);
        if (stillRacing) return;

        results.sort(Comparator.comparingLong(racer -> racer.finishedMillis == 0 ? Long.MAX_VALUE : racer.finishedMillis));
        broadcast(Component.text("═══ CHROMA RIFT · ФИНИШ ═══", NamedTextColor.LIGHT_PURPLE, TextDecoration.BOLD));
        int place = 1;
        for (Racer racer : results) {
            if (racer.status != RacerStatus.FINISHED) continue;
            broadcast(Component.text(place + ". " + racer.player.getUsername() + "  ·  " + formatTime(racer.finishedMillis),
                    place == 1 ? NamedTextColor.GOLD : NamedTextColor.GRAY));
            place++;
        }
        phase = Phase.WAITING;
        racers.clear();
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
            racer.vehicle.refreshPosition(TrackBuilder.vehiclePosition(racer.angle, racer.laneOffset), false, true);
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

    private int firstFreeSlot() {
        boolean[] occupied = new boolean[MAX_RACERS];
        for (Racer racer : racers.values()) {
            if (racer.slot >= 0 && racer.slot < MAX_RACERS) occupied[racer.slot] = true;
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
        return new InputState(input.forward(), input.backward(), input.left(), input.right(), input.sprint());
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

    private record InputState(boolean forward, boolean backward, boolean left, boolean right, boolean sprint) {
        private static final InputState NONE = new InputState(false, false, false, false, false);
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

    private static final class Racer {
        private final Player player;
        private final int slot;
        private volatile RacerStatus status = RacerStatus.QUEUED;
        private volatile Entity vehicle;
        private Task tickTask;
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
            this.angle = TrackBuilder.startAngleForSlot(slot);
            this.laneOffset = TrackBuilder.laneOffsetForSlot(slot);
        }
    }
}
