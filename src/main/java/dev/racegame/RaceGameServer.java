package dev.racegame;

import net.minestom.server.Auth;
import net.minestom.server.MinecraftServer;
import net.minestom.server.instance.InstanceContainer;
import net.minestom.server.instance.LightingChunk;

public final class RaceGameServer {
    private RaceGameServer() {
    }

    public static void main(String[] args) {
        MinecraftServer.setBrandName("CHROMA RIFT // Minestom 26.3");
        boolean onlineMode = Boolean.parseBoolean(System.getenv().getOrDefault("MINESTOM_ONLINE_MODE", "false"));
        MinecraftServer server = MinecraftServer.init(onlineMode ? new Auth.Online() : new Auth.Offline());
        ResourcePackHost.start();

        InstanceContainer world = MinecraftServer.getInstanceManager().createInstanceContainer();
        world.setChunkSupplier(LightingChunk::new);
        world.setGenerator(TrackBuilder::generate);
        world.setTime(18_000);
        if (world.defaultClock() != null) world.defaultClock().pause();

        RaceManager raceManager = new RaceManager(world);
        raceManager.registerEvents();
        MinecraftServer.getCommandManager().register(new RaceCommand(raceManager));
        MinecraftServer.getCommandManager().setUnknownCommandCallback((sender, command) ->
                sender.sendMessage(net.kyori.adventure.text.Component.text("Неизвестная команда. Используй /race.", net.kyori.adventure.text.format.NamedTextColor.RED)));

        String host = System.getenv().getOrDefault("MINESTOM_HOST", "0.0.0.0");
        int port = Integer.parseInt(System.getenv().getOrDefault("MINESTOM_PORT", "25565"));
        System.out.println("CHROMA RIFT // Minestom 26.3 listening on " + host + ":" + port);
        System.out.println("Join from a Minecraft 26.3 client, then use /race solo or /race join and /race start.");
        server.start(host, port);
    }
}
