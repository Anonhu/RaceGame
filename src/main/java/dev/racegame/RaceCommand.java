package dev.racegame;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.minestom.server.command.CommandSender;
import net.minestom.server.command.builder.Command;
import net.minestom.server.command.builder.CommandContext;
import net.minestom.server.command.builder.arguments.ArgumentType;
import net.minestom.server.entity.Player;

public final class RaceCommand extends Command {
    private final RaceManager raceManager;

    public RaceCommand(RaceManager raceManager) {
        super("race", "rift");
        this.raceManager = raceManager;
        setDefaultExecutor((sender, context) -> sendHelp(sender));
        addSyntax(this::execute, ArgumentType.Word("action").from("join", "start", "leave", "status", "cancel"));
    }

    private void execute(CommandSender sender, CommandContext context) {
        String action = context.get("action");
        switch (action) {
            case "join" -> {
                if (sender instanceof Player player) raceManager.join(player);
                else sender.sendMessage(Component.text("Эту команду можно использовать только в игре.", NamedTextColor.RED));
            }
            case "start" -> raceManager.startCountdown();
            case "leave" -> {
                if (sender instanceof Player player) raceManager.leave(player);
                else sender.sendMessage(Component.text("Эту команду можно использовать только в игре.", NamedTextColor.RED));
            }
            case "status" -> raceManager.showStatus(sender);
            case "cancel" -> raceManager.cancelRace();
            default -> sendHelp(sender);
        }
    }

    private static void sendHelp(CommandSender sender) {
        sender.sendMessage(Component.text("CHROMA RIFT // команды", NamedTextColor.AQUA));
        sender.sendMessage(Component.text("/race join · встать на стартовую решётку", NamedTextColor.GRAY));
        sender.sendMessage(Component.text("/race start · начать трёхсекундный отсчёт", NamedTextColor.GRAY));
        sender.sendMessage(Component.text("/race status · статус гонки", NamedTextColor.GRAY));
        sender.sendMessage(Component.text("/race leave · покинуть заезд · /race cancel · остановить всех", NamedTextColor.GRAY));
    }
}
