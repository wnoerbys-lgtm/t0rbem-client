package dk.t0rbem;

import dk.t0rbem.modules.HudModule;
import dk.t0rbem.modules.Module;
import dk.t0rbem.modules.ModuleManager;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.render.RenderTickCounter;

public class T0rbemHud {
    public static void render(DrawContext ctx, RenderTickCounter tickCounter) {
        MinecraftClient mc = MinecraftClient.getInstance();
        if (!T0rbemClient.hudVisible || HudEditorScreen.active) return; // editoren tegner selv
        if (mc.player == null || mc.options.hudHidden) return;

        float pt = tickCounter.getTickProgress(false);
        for (Module m : ModuleManager.all()) {
            if (m.enabled && !(m instanceof HudModule)) m.renderHud(ctx, pt);
        }

        for (HudModule m : ModuleManager.hud()) {
            if (m.enabled) m.render(ctx);
        }
    }
}
