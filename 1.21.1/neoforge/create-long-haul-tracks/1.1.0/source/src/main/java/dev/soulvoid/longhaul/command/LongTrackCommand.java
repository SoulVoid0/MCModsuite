package dev.soulvoid.longhaul.command;

import java.util.UUID;

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.context.CommandContext;
import com.mojang.brigadier.exceptions.CommandSyntaxException;

import dev.soulvoid.longhaul.compat.JJThunderCompat;
import dev.soulvoid.longhaul.job.Job;
import dev.soulvoid.longhaul.job.JobManager;
import dev.soulvoid.longhaul.net.Net;
import dev.soulvoid.longhaul.route.Style;
import net.minecraft.commands.CommandBuildContext;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.commands.arguments.blocks.BlockStateArgument;
import net.minecraft.commands.arguments.coordinates.BlockPosArgument;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;

/** /longtrack: the same controls as the surveyor, for exact coordinates and for servers. */
public final class LongTrackCommand {

    public static void register(final CommandDispatcher<CommandSourceStack> d, final CommandBuildContext ctx) {
        d.register(Commands.literal("longtrack")
            .then(Commands.literal("start").then(Commands.argument("pos", BlockPosArgument.blockPos())
                .executes(c -> point(c, true, BlockPosArgument.getBlockPos(c, "pos"), false))))
            .then(Commands.literal("end").then(Commands.argument("pos", BlockPosArgument.blockPos())
                .executes(c -> point(c, false, BlockPosArgument.getBlockPos(c, "pos"), false))))
            .then(Commands.literal("startxz").then(Commands.argument("x", IntegerArgumentType.integer())
                .then(Commands.argument("z", IntegerArgumentType.integer())
                    .executes(c -> point(c, true, new BlockPos(IntegerArgumentType.getInteger(c, "x"), 0,
                        IntegerArgumentType.getInteger(c, "z")), true)))))
            .then(Commands.literal("endxz").then(Commands.argument("x", IntegerArgumentType.integer())
                .then(Commands.argument("z", IntegerArgumentType.integer())
                    .executes(c -> point(c, false, new BlockPos(IntegerArgumentType.getInteger(c, "x"), 0,
                        IntegerArgumentType.getInteger(c, "z")), true)))))
            .then(Commands.literal("bridge").then(Commands.argument("block", BlockStateArgument.block(ctx))
                .executes(c -> setBlock(c, Net.Action.SET_BRIDGE))))
            .then(Commands.literal("lining").then(Commands.argument("block", BlockStateArgument.block(ctx))
                .executes(c -> setBlock(c, Net.Action.SET_LINING))))
            .then(Commands.literal("floor").then(Commands.argument("block", BlockStateArgument.block(ctx))
                .executes(c -> setBlock(c, Net.Action.SET_BRIDGE))))
            .then(Commands.literal("walls").then(Commands.argument("block", BlockStateArgument.block(ctx))
                .executes(c -> setBlock(c, Net.Action.SET_LINING))))
            .then(styleArg("light", Net.Action.SET_LIGHT, Style.Light.values()))
            .then(styleArg("structures", Net.Action.SET_MODE, Style.Mode.values()))
            .then(Commands.literal("plan").executes(c -> act(c, null, Net.Action.REPLAN)))
            .then(Commands.literal("clear").executes(c -> act(c, null, Net.Action.CLEAR_POINTS)))
            .then(Commands.literal("confirm").executes(c -> act(c, null, Net.Action.CONFIRM))
                .then(Commands.argument("id", StringArgumentType.word()).executes(c -> act(c, id(c), Net.Action.CONFIRM))))
            .then(Commands.literal("deposit").executes(c -> act(c, null, Net.Action.DEPOSIT))
                .then(Commands.argument("id", StringArgumentType.word()).executes(c -> act(c, id(c), Net.Action.DEPOSIT))))
            .then(Commands.literal("cancel").executes(c -> act(c, null, Net.Action.CANCEL))
                .then(Commands.argument("id", StringArgumentType.word()).executes(c -> act(c, id(c), Net.Action.CANCEL))))
            .then(Commands.literal("fill").requires(s -> s.hasPermission(2))
                .then(Commands.argument("id", StringArgumentType.word()).executes(c -> act(c, id(c), Net.Action.FILL))))
            .then(Commands.literal("route").requires(s -> s.hasPermission(2))
                .then(Commands.argument("x1", IntegerArgumentType.integer()).then(Commands.argument("z1", IntegerArgumentType.integer())
                    .then(Commands.argument("x2", IntegerArgumentType.integer()).then(Commands.argument("z2", IntegerArgumentType.integer())
                        .executes(LongTrackCommand::route))))))
            .then(Commands.literal("admin").requires(s -> s.hasPermission(2))
                .then(Commands.literal("confirm").then(Commands.argument("id", StringArgumentType.word())
                    .executes(c -> admin(c, Net.Action.CONFIRM))))
                .then(Commands.literal("fill").then(Commands.argument("id", StringArgumentType.word())
                    .executes(c -> admin(c, Net.Action.FILL))))
                .then(Commands.literal("cancel").then(Commands.argument("id", StringArgumentType.word())
                    .executes(c -> admin(c, Net.Action.CANCEL)))))
            .then(Commands.literal("list").executes(LongTrackCommand::list))
            .then(Commands.literal("status").executes(LongTrackCommand::status)));
    }

    private static com.mojang.brigadier.builder.LiteralArgumentBuilder<CommandSourceStack> styleArg(final String name,
            final Net.Action action, final Enum<?>[] values) {
        final var lit = Commands.literal(name);
        for (final Enum<?> v : values) {
            final String key = v.name().toLowerCase(java.util.Locale.ROOT);
            lit.then(Commands.literal(key).executes(c -> {
                final ServerPlayer p = c.getSource().getPlayerOrException();
                JobManager.get(p.server).action(p, null, action, key);
                c.getSource().sendSuccess(() -> Component.literal(name + ": " + key), false);
                return 1;
            }));
        }
        return lit;
    }

    private static UUID id(final CommandContext<CommandSourceStack> c) throws CommandSyntaxException {
        final String s = StringArgumentType.getString(c, "id");
        for (final Job j : JobManager.get(c.getSource().getServer()).jobs())
            if (j.id.toString().startsWith(s))
                return j.id;
        throw new CommandSyntaxException(null, Component.literal("No route with id " + s));
    }

    private static int point(final CommandContext<CommandSourceStack> c, final boolean start, final BlockPos pos,
                             final boolean surface) throws CommandSyntaxException {
        final ServerPlayer p = c.getSource().getPlayerOrException();
        JobManager.get(p.server).setPoint(p, p.serverLevel(), pos, surface, start);
        return 1;
    }

    private static int setBlock(final CommandContext<CommandSourceStack> c, final Net.Action a) throws CommandSyntaxException {
        final ServerPlayer p = c.getSource().getPlayerOrException();
        final var block = BlockStateArgument.getBlock(c, "block").getState().getBlock();
        JobManager.get(p.server).action(p, null, a, BuiltInRegistries.BLOCK.getKey(block).toString());
        return 1;
    }

    private static int act(final CommandContext<CommandSourceStack> c, final UUID id, final Net.Action a) throws CommandSyntaxException {
        final ServerPlayer p = c.getSource().getPlayerOrException();
        JobManager.get(p.server).action(p, id, a, "");
        return 1;
    }

    /** Surface-to-surface route by x/z, owned by the command source (console or command block allowed). */
    private static int route(final CommandContext<CommandSourceStack> c) {
        final var src = c.getSource();
        final java.util.UUID owner = src.getEntity() != null ? src.getEntity().getUUID() : new java.util.UUID(0, 0);
        JobManager.get(src.getServer()).planFor(owner, src.getTextName(), src.getLevel(),
            new BlockPos(IntegerArgumentType.getInteger(c, "x1"), 0, IntegerArgumentType.getInteger(c, "z1")), true,
            new BlockPos(IntegerArgumentType.getInteger(c, "x2"), 0, IntegerArgumentType.getInteger(c, "z2")), true);
        src.sendSuccess(() -> Component.literal("Planning route..."), true);
        return 1;
    }

    private static int admin(final CommandContext<CommandSourceStack> c, final Net.Action a) throws CommandSyntaxException {
        final boolean ok = JobManager.get(c.getSource().getServer()).admin(id(c), a);
        c.getSource().sendSuccess(() -> Component.literal(ok ? "Done." : "Not applicable."), true);
        return ok ? 1 : 0;
    }

    private static int list(final CommandContext<CommandSourceStack> c) {
        final var jobs = JobManager.get(c.getSource().getServer()).jobs();
        if (jobs.isEmpty())
            c.getSource().sendSuccess(() -> Component.translatable("longhaultracks.cmd.none"), false);
        for (final Job j : jobs)
            c.getSource().sendSuccess(() -> Component.literal(String.format("%s  %s  %s -> %s  %d blocks  %s  (%s)",
                j.id.toString().substring(0, 8), j.state, j.start.toShortString(), j.end.toShortString(), j.length,
                j.message, j.ownerName)), false);
        return jobs.size();
    }

    private static int status(final CommandContext<CommandSourceStack> c) {
        final var src = c.getSource();
        final var lvl = src.getLevel();
        src.sendSuccess(() -> Component.literal("Terrain profile: " + JJThunderCompat.profile(lvl).name()
            + " (JJThunder datapack " + (JJThunderCompat.present(src.getServer()) ? "found" : "not found")
            + ", world height " + lvl.getHeight() + ")"), false);
        return 1;
    }

    private LongTrackCommand() {}
}
