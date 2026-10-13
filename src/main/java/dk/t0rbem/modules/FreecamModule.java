package dk.t0rbem.modules;

import dk.t0rbem.settings.NumberSetting;
import com.mojang.authlib.GameProfile;
import net.minecraft.client.network.ClientPlayerEntity;
import net.minecraft.client.network.OtherClientPlayerEntity;
import net.minecraft.client.world.ClientWorld;
import net.minecraft.client.input.Input;

/**
 * Freecam: kameraet flyttes væk fra din spiller med WASD, Mellemrum og Shift.
 * Din rigtige spiller står stille (input blokeres) og bliver stående, hvor den er.
 * Kameraet drejer med musen (din spillers retning).
 */
public class FreecamModule extends Module {
    private final NumberSetting speed = add(new NumberSetting("Speed", 0.6, 0.1, 3.0, 0.1));

    private static class CamEntity extends OtherClientPlayerEntity {
        CamEntity(ClientWorld world, GameProfile profile) {
            super(world, profile);
        }

        @Override
        public float getYaw(float tickDelta) { return getYaw(); }

        @Override
        public float getPitch(float tickDelta) { return getPitch(); }
    }

    private OtherClientPlayerEntity cam;
    private ClientWorld camWorld;
    private Input savedInput;

    public FreecamModule() {
        super("Freecam", "Flyv kameraet rundt uden at flytte din spiller.", Category.RENDER, false);
    }

    @Override
    public void onEnable() {
        ClientPlayerEntity p = mc.player;
        if (p == null || mc.world == null) {
            enabled = false;
            return;
        }
        camWorld = mc.world;
        cam = new CamEntity(mc.world, p.getGameProfile());
        cam.setPosition(p.getX(), p.getY(), p.getZ());
        cam.setYaw(p.getYaw());
        cam.setPitch(p.getPitch());
        syncPrev();

        savedInput = p.input;
        p.input = new Input(); // tom input: din spiller bevæger sig ikke
        mc.setCameraEntity(cam);
    }

    @Override
    public void onDisable() {
        if (mc.player != null) {
            if (savedInput != null) mc.player.input = savedInput;
            mc.setCameraEntity(mc.player);
        }
        savedInput = null;
        cam = null;
        camWorld = null;
    }

    private void syncPrev() {
        cam.lastRenderX = cam.getX();
        cam.lastRenderY = cam.getY();
        cam.lastRenderZ = cam.getZ();
    }

    @Override
    public void onTick() {
        ClientPlayerEntity p = mc.player;
        if (p == null || cam == null) return;
        if (mc.world != camWorld) { // dimension/server skiftet
            setEnabled(false);
            return;
        }
        // hold din spiller i ro, selv hvis noget andet overskriver input
        if (p.input != null && savedInput != null && p.input == savedInput) p.input = new Input();

        double vx = 0, vy = 0, vz = 0;
        if (mc.currentScreen == null) {
            double f = (mc.options.forwardKey.isPressed() ? 1 : 0) - (mc.options.backKey.isPressed() ? 1 : 0);
            double s = (mc.options.rightKey.isPressed() ? 1 : 0) - (mc.options.leftKey.isPressed() ? 1 : 0);
            double yaw = Math.toRadians(p.getYaw());
            vx = -Math.sin(yaw) * f - Math.cos(yaw) * s;
            vz = Math.cos(yaw) * f - Math.sin(yaw) * s;
            double len = Math.hypot(vx, vz);
            if (len > 0) {
                vx = vx / len * speed.get();
                vz = vz / len * speed.get();
            }
            vy = ((mc.options.jumpKey.isPressed() ? 1 : 0) - (mc.options.sneakKey.isPressed() ? 1 : 0)) * speed.get();
        }

        cam.lastRenderX = cam.getX();
        cam.lastRenderY = cam.getY();
        cam.lastRenderZ = cam.getZ();
        cam.setPosition(cam.getX() + vx, cam.getY() + vy, cam.getZ() + vz);
        cam.setYaw(p.getYaw());
        cam.setPitch(p.getPitch());
    }
}
