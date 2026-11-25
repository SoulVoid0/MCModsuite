package dev.soulvoid.longhaul.mixin.xaero;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import dev.soulvoid.longhaul.client.MapOverlay;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.screens.Screen;
import xaero.map.gui.GuiMap;

/** Same hook Xaero Train Map uses: after the world map renders, draw routes in map space. */
@Mixin(value = GuiMap.class, remap = false)
public abstract class GuiMapMixin {

    @Shadow
    private double scale;
    @Shadow
    private double cameraX;
    @Shadow
    private double cameraZ;
    @Shadow
    private int mouseBlockPosX;
    @Shadow
    private int mouseBlockPosZ;

    @Inject(method = "render", at = @At("TAIL"))
    private void longhaultracks$render(final GuiGraphics g, final int mx, final int my, final float pt, final CallbackInfo ci) {
        MapOverlay.render((Screen) (Object) this, g, scale, cameraX, cameraZ, mouseBlockPosX, mouseBlockPosZ);
    }
}
