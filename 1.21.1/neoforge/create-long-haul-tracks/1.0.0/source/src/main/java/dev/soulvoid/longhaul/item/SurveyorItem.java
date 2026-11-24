package dev.soulvoid.longhaul.item;

import java.util.List;

import dev.soulvoid.longhaul.job.JobManager;
import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.InteractionResultHolder;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.TooltipFlag;
import net.minecraft.world.item.context.UseOnContext;
import net.minecraft.world.level.Level;

/**
 * Right-click a block: set the route start, then the end (a track block is joined with a junction).
 * Right-click the sky or distant terrain: pick a point far beyond the loaded area.
 * Sneak + right-click: open the planner.
 */
public final class SurveyorItem extends Item {

    public SurveyorItem(final Properties p) {
        super(p);
    }

    @Override
    public InteractionResult useOn(final UseOnContext ctx) {
        final Player player = ctx.getPlayer();
        if (player == null)
            return InteractionResult.PASS;
        if (player.isShiftKeyDown()) {
            if (ctx.getLevel().isClientSide)
                dev.soulvoid.longhaul.client.ClientActions.openPlanner();
            return InteractionResult.sidedSuccess(ctx.getLevel().isClientSide);
        }
        if (!ctx.getLevel().isClientSide && player instanceof ServerPlayer sp)
            JobManager.get(sp.server).pickBlock(sp, ctx.getClickedPos());
        return InteractionResult.sidedSuccess(ctx.getLevel().isClientSide);
    }

    @Override
    public InteractionResultHolder<ItemStack> use(final Level level, final Player player, final InteractionHand hand) {
        final ItemStack stack = player.getItemInHand(hand);
        if (level.isClientSide) {
            if (player.isShiftKeyDown())
                dev.soulvoid.longhaul.client.ClientActions.openPlanner();
            else
                dev.soulvoid.longhaul.client.ClientActions.longRangePick();
        }
        return InteractionResultHolder.sidedSuccess(stack, level.isClientSide);
    }

    @Override
    public void appendHoverText(final ItemStack stack, final TooltipContext ctx, final List<Component> tip,
                                final TooltipFlag flag) {
        tip.add(Component.translatable("item.longhaultracks.track_surveyor.tip1").withStyle(ChatFormatting.GRAY));
        tip.add(Component.translatable("item.longhaultracks.track_surveyor.tip2").withStyle(ChatFormatting.GRAY));
        tip.add(Component.translatable("item.longhaultracks.track_surveyor.tip3").withStyle(ChatFormatting.GRAY));
    }
}
