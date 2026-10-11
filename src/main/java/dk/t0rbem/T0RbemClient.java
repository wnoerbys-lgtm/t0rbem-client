package dk.t0rbem;

import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.command.v2.ClientCommandManager;
import net.fabricmc.fabric.api.client.command.v2.ClientCommandRegistrationCallback;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.keybinding.v1.KeyBindingHelper;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.block.Block;
import net.minecraft.block.BlockState;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.client.gui.widget.ButtonWidget;
import net.minecraft.client.gui.widget.TextFieldWidget;
import net.minecraft.client.network.ClientPlayerEntity;
import net.minecraft.client.option.KeyBinding;
import net.minecraft.item.Item;
import net.minecraft.item.Items;
import net.minecraft.nbt.NbtCompound;
import net.minecraft.nbt.NbtIo;
import net.minecraft.nbt.NbtList;
import net.minecraft.nbt.NbtSizeTracker;
import net.minecraft.registry.Registries;
import net.minecraft.text.Text;
import net.minecraft.util.Hand;
import net.minecraft.util.Identifier;
import net.minecraft.util.hit.BlockHitResult;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Direction;
import net.minecraft.util.math.MathHelper;
import net.minecraft.util.math.Vec3d;
import org.lwjgl.glfw.GLFW;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.stream.Stream;

/** T0Rbem client: hele moden i én fil. Right Shift eller /t0rbem åbner GUI'et. */
public class T0RbemClient implements ClientModInitializer {
    private KeyBinding openKey;
    private KeyBinding panicKey;
    private boolean openGui = false;

    @Override
    public void onInitializeClient() {
        KeyBinding.Category category = KeyBinding.Category.create(Identifier.of("t0rbem_client", "main"));
        openKey = KeyBindingHelper.registerKeyBinding(new KeyBinding(
            "key.t0rbem.open", GLFW.GLFW_KEY_RIGHT_SHIFT, category));
        panicKey = KeyBindingHelper.registerKeyBinding(new KeyBinding(
            "Disable all (T0Rbem)", GLFW.GLFW_KEY_END, category));

        ClientCommandRegistrationCallback.EVENT.register((dispatcher, registryAccess) ->
            dispatcher.register(ClientCommandManager.literal("t0rbem").executes(ctx -> {
                openGui = true;
                return 1;
            })));

        ClientTickEvents.END_CLIENT_TICK.register(mc -> {
            while (openKey.wasPressed()) {
                if (mc.currentScreen == null) openGui = true;
            }
            while (panicKey.wasPressed()) Miner.INSTANCE.disableAll();
            Miner.INSTANCE.keepFlight(mc);
            Miner.INSTANCE.tickNoFall(mc);
            if (openGui) {
                openGui = false;
                mc.setScreen(new T0RbemScreen());
            }
            Miner.INSTANCE.tick(mc);
        });
    }

    // ───────────────────────── Schematic-læser ─────────────────────────
    record Entry(BlockPos pos, String block) {}

    static class Schematic {
        final List<Entry> blocks = new ArrayList<>();

        static boolean isAir(String n) {
            return n.equals("minecraft:air") || n.equals("minecraft:cave_air")
                || n.equals("minecraft:void_air") || n.equals("minecraft:structure_void");
        }

        static String clean(String n) {
            int i = n.indexOf('[');
            return i >= 0 ? n.substring(0, i) : n;
        }

        static Schematic load(Path file) throws IOException {
            NbtCompound root = NbtIo.readCompressed(file, NbtSizeTracker.ofUnlimitedBytes());
            String n = file.getFileName().toString().toLowerCase();
            if (n.endsWith(".litematic")) return fromLitematic(root);
            if (n.endsWith(".schem")) return fromSponge(root);
            if (n.endsWith(".nbt")) return fromStructure(root);
            throw new IOException("Ukendt filtype");
        }

        // ---- .litematic ----
        static Schematic fromLitematic(NbtCompound root) {
            NbtCompound regions = root.getCompoundOrEmpty("Regions");
            Schematic s = new Schematic();
            for (String name : regions.getKeys()) {
                NbtCompound r = regions.getCompoundOrEmpty(name);
                NbtCompound pos = r.getCompoundOrEmpty("Position");
                NbtCompound size = r.getCompoundOrEmpty("Size");
                int px = pos.getInt("x", 0), py = pos.getInt("y", 0), pz = pos.getInt("z", 0);
                int sx = size.getInt("x", 0), sy = size.getInt("y", 0), sz = size.getInt("z", 0);
                int ax = Math.abs(sx), ay = Math.abs(sy), az = Math.abs(sz);
                int ox = px + (sx < 0 ? sx + 1 : 0);
                int oy = py + (sy < 0 ? sy + 1 : 0);
                int oz = pz + (sz < 0 ? sz + 1 : 0);

                NbtList palette = r.getListOrEmpty("BlockStatePalette");
                if (palette.isEmpty()) continue;
                String[] names = new String[palette.size()];
                for (int i = 0; i < names.length; i++) names[i] = palette.getCompoundOrEmpty(i).getString("Name", "");

                long[] data = r.getLongArray("BlockStates").orElse(new long[0]);
                int bits = Math.max(2, 32 - Integer.numberOfLeadingZeros(palette.size() - 1));
                long mask = (1L << bits) - 1;
                long total = (long) ax * ay * az;

                for (long i = 0; i < total; i++) {
                    long bitIndex = i * bits;
                    int li = (int) (bitIndex >>> 6);
                    int off = (int) (bitIndex & 63);
                    if (li >= data.length) break;
                    long v = data[li] >>> off;
                    if (off + bits > 64 && li + 1 < data.length) v |= data[li + 1] << (64 - off);
                    int id = (int) (v & mask);
                    if (id >= names.length || isAir(names[id])) continue;
                    int x = (int) (i % ax);
                    int z = (int) ((i / ax) % az);
                    int y = (int) (i / ((long) ax * az));
                    s.blocks.add(new Entry(new BlockPos(ox + x, oy + y, oz + z), names[id]));
                }
            }
            return s;
        }

        // ---- .schem (Sponge v2 og v3) ----
        static Schematic fromSponge(NbtCompound root) throws IOException {
            NbtCompound s0 = root.contains("Schematic") ? root.getCompoundOrEmpty("Schematic") : root;
            int w = s0.getShort("Width", (short) 0) & 0xFFFF;
            int h = s0.getShort("Height", (short) 0) & 0xFFFF;
            int l = s0.getShort("Length", (short) 0) & 0xFFFF;

            NbtCompound palette;
            byte[] data;
            if (s0.contains("Blocks")) {
                NbtCompound b = s0.getCompoundOrEmpty("Blocks");
                palette = b.getCompoundOrEmpty("Palette");
                data = b.getByteArray("Data").orElse(new byte[0]);
            } else {
                palette = s0.getCompoundOrEmpty("Palette");
                data = s0.getByteArray("BlockData").orElse(new byte[0]);
            }
            if (w * h * l == 0 || data.length == 0) throw new IOException("Tom eller ukendt .schem");

            int max = 0;
            for (String k : palette.getKeys()) max = Math.max(max, palette.getInt(k, 0));
            String[] names = new String[max + 1];
            for (String k : palette.getKeys()) names[palette.getInt(k, 0)] = clean(k);

            Schematic s = new Schematic();
            int i = 0, idx = 0, total = w * h * l;
            while (i < data.length && idx < total) {
                int val = 0, shift = 0;
                byte b;
                do {
                    b = data[i++];
                    val |= (b & 0x7F) << shift;
                    shift += 7;
                } while ((b & 0x80) != 0 && i < data.length);

                String name = (val >= 0 && val < names.length) ? names[val] : null;
                if (name != null && !isAir(name)) {
                    int x = idx % w;
                    int z = (idx / w) % l;
                    int y = idx / (w * l);
                    s.blocks.add(new Entry(new BlockPos(x, y, z), name));
                }
                idx++;
            }
            return s;
        }

        // ---- .nbt (vanilla structure) ----
        static Schematic fromStructure(NbtCompound root) {
            NbtList palette = root.getListOrEmpty("palette");
            NbtList blocks = root.getListOrEmpty("blocks");
            Schematic s = new Schematic();
            for (int i = 0; i < blocks.size(); i++) {
                NbtCompound b = blocks.getCompoundOrEmpty(i);
                NbtList pos = b.getListOrEmpty("pos");
                String name = palette.getCompoundOrEmpty(b.getInt("state", 0)).getString("Name", "");
                if (name.isEmpty() || isAir(name)) continue;
                s.blocks.add(new Entry(new BlockPos(pos.getInt(0, 0), pos.getInt(1, 0), pos.getInt(2, 0)), name));
            }
            return s;
        }
    }

    // ───────────────────────── Miner / Builder / Area Miner ─────────────────────────
    enum Mode { MINE, BUILD }

    static class Miner {
        static final Miner INSTANCE = new Miner();
        private static final double REACH = 4.5;

        Schematic schematic;
        String loadedName = null;
        BlockPos origin;
        Mode mode = Mode.MINE;
        boolean running = false;
        boolean area = false;
        int placeDelay = 2;
        boolean flight = false;
        boolean noFall = false;
        int finderIndex = 0;
        static final String[] FINDER_TARGETS = {"diamond", "ancient_debris", "emerald", "gold", "iron", "coal", "redstone", "lapis", "copper"};
        BlockPos areaA, areaB;
        String status = "No schematic loaded.";

        private List<Entry> targets = new ArrayList<>();
        private int index = 0;
        private int attemptsIndex = -1;
        private int attempts = 0;
        private int cooldown = 0;

        static Path folder() {
            Path p = FabricLoader.getInstance().getGameDir().resolve("schematics");
            try { Files.createDirectories(p); } catch (IOException ignored) {}
            return p;
        }

        static List<String> listFiles() {
            List<String> out = new ArrayList<>();
            try (Stream<Path> s = Files.list(folder())) {
                s.map(f -> f.getFileName().toString())
                 .filter(n -> { String l = n.toLowerCase(); return l.endsWith(".litematic") || l.endsWith(".schem") || l.endsWith(".nbt"); })
                 .forEach(out::add);
            } catch (IOException ignored) {}
            out.sort(String::compareToIgnoreCase);
            return out;
        }

        void load(String file) {
            try {
                schematic = Schematic.load(folder().resolve(file));
                loadedName = file;
                status = "Loaded " + schematic.blocks.size() + " blocks from " + file;
                rebuild();
            } catch (Exception e) {
                schematic = null;
                loadedName = null;
                status = "Error: " + e.getMessage();
            }
        }

        void setOrigin(BlockPos p) {
            origin = p;
            rebuild();
            status = "Origin set: " + p.toShortString();
        }

        void toggleMode() {
            mode = (mode == Mode.MINE) ? Mode.BUILD : Mode.MINE;
            rebuild();
        }

        void start() {
            if (schematic == null) { status = "Pick and load a schematic first."; return; }
            if (origin == null) { status = "Set the origin first."; return; }
            area = false;
            rebuild();
            running = true;
            status = "Running...";
        }

        void startArea(MinecraftClient mc) {
            if (areaA == null || areaB == null) { status = "Set both corners first."; return; }
            if (mc.world == null) return;
            int x1 = Math.min(areaA.getX(), areaB.getX()), x2 = Math.max(areaA.getX(), areaB.getX());
            int y1 = Math.min(areaA.getY(), areaB.getY()), y2 = Math.max(areaA.getY(), areaB.getY());
            int z1 = Math.min(areaA.getZ(), areaB.getZ()), z2 = Math.max(areaA.getZ(), areaB.getZ());
            long vol = (long) (x2 - x1 + 1) * (y2 - y1 + 1) * (z2 - z1 + 1);
            if (vol > 300000) { status = "Area too large (max 300000 blocks)."; return; }

            targets = new ArrayList<>();
            for (int y = y1; y <= y2; y++)
                for (int z = z1; z <= z2; z++)
                    for (int x = x1; x <= x2; x++) {
                        BlockPos p = new BlockPos(x, y, z);
                        BlockState st = mc.world.getBlockState(p);
                        if (!st.isAir() && st.getFluidState().isEmpty() && st.getHardness(mc.world, p) >= 0)
                            targets.add(new Entry(p, ""));
                    }
            targets.sort(Comparator.<Entry>comparingInt(e -> -e.pos().getY())
                .thenComparingInt(e -> e.pos().getZ())
                .thenComparingInt(e -> (e.pos().getZ() & 1) == 0 ? e.pos().getX() : -e.pos().getX()));
            index = 0;
            area = true;
            running = true;
            status = "Area miner: " + targets.size() + " blocks";
        }

        void stop() {
            running = false;
            MinecraftClient mc = MinecraftClient.getInstance();
            if (mc.options != null) {
                mc.options.forwardKey.setPressed(false);
                mc.options.jumpKey.setPressed(false);
            }
            if (mc.interactionManager != null) mc.interactionManager.cancelBlockBreaking();
        }

        /** Kill switch: stops everything at once and releases all keys. */
        void disableAll() {
            stop();
            area = false;
            MinecraftClient mc = MinecraftClient.getInstance();
            if (flight) setFlight(mc, false);
            noFall = false;
            status = "All features disabled.";
        }

        /** Flight via the integrated server's abilities. Only works in singleplayer. */
        void setFlight(MinecraftClient mc, boolean on) {
            if (mc.player == null || !mc.isInSingleplayer() || mc.getServer() == null) {
                flight = false;
                status = "Flight only works in singleplayer.";
                return;
            }
            flight = on;
            boolean keep = mc.player.isCreative() || mc.player.isSpectator();
            boolean allow = on || keep;
            var sp = mc.getServer().getPlayerManager().getPlayer(mc.player.getUuid());
            mc.getServer().execute(() -> {
                if (sp != null) {
                    sp.getAbilities().allowFlying = allow;
                    if (on) sp.getAbilities().flying = true;
                    if (!allow) sp.getAbilities().flying = false;
                    sp.sendAbilitiesUpdate();
                }
            });
            mc.player.getAbilities().allowFlying = allow;
            if (on) mc.player.getAbilities().flying = true;
            if (!allow) mc.player.getAbilities().flying = false;
            status = on ? "Flight ON (double-tap jump to toggle)" : "Flight OFF";
        }

        /** No fall damage: resets the fall distance every tick. Singleplayer only. */
        void setNoFall(MinecraftClient mc, boolean on) {
            if (on && (mc.player == null || !mc.isInSingleplayer())) {
                noFall = false;
                status = "No fall only works in singleplayer.";
                return;
            }
            noFall = on;
            status = on ? "No fall ON" : "No fall OFF";
        }

        void tickNoFall(MinecraftClient mc) {
            if (!noFall || mc.player == null) return;
            if (!mc.isInSingleplayer() || mc.getServer() == null) { noFall = false; return; }
            mc.player.fallDistance = 0;
            var sp = mc.getServer().getPlayerManager().getPlayer(mc.player.getUuid());
            if (sp != null) mc.getServer().execute(() -> sp.fallDistance = 0);
        }

        /** Re-applies flight if the game reset the abilities (death, dimension change, etc). */
        void keepFlight(MinecraftClient mc) {
            if (!flight || mc.player == null) return;
            if (!mc.isInSingleplayer()) { flight = false; return; }
            if (!mc.player.getAbilities().allowFlying) setFlight(mc, true);
        }

        /** Lists the nearest matching ore blocks in loaded terrain. Singleplayer only. */
        void scanBlocks(MinecraftClient mc) {
            if (mc.player == null || mc.world == null || !mc.isInSingleplayer()) {
                status = "Block finder only works in singleplayer.";
                return;
            }
            String want = FINDER_TARGETS[finderIndex];
            BlockPos c = mc.player.getBlockPos();
            int r = 24;
            List<BlockPos> hits = new ArrayList<>();
            for (int x = -r; x <= r; x++)
                for (int y = -r; y <= r; y++)
                    for (int z = -r; z <= r; z++) {
                        BlockPos p = c.add(x, y, z);
                        String id = Registries.BLOCK.getId(mc.world.getBlockState(p).getBlock()).getPath();
                        boolean match = want.equals("ancient_debris") ? id.equals("ancient_debris")
                            : (id.endsWith("_ore") && id.contains(want));
                        if (match) hits.add(p);
                    }
            hits.sort(Comparator.comparingDouble(p -> p.getSquaredDistance(c)));
            status = "Found " + hits.size() + " " + want + " within " + r + " blocks";
            msg(status);
            for (int i = 0; i < Math.min(5, hits.size()); i++) {
                BlockPos h = hits.get(i);
                msg(h.getX() + " " + h.getY() + " " + h.getZ() + "  (" + (int) Math.sqrt(h.getSquaredDistance(c)) + " blocks away)");
            }
        }

        int remaining() { return Math.max(0, targets.size() - index); }

        private void rebuild() {
            if (area && running) return;
            if (schematic == null || origin == null) return;
            targets = new ArrayList<>();
            for (Entry e : schematic.blocks) targets.add(new Entry(origin.add(e.pos()), e.block()));
            if (mode == Mode.MINE) {
                targets.sort(Comparator.<Entry>comparingInt(e -> -e.pos().getY())
                    .thenComparingInt(e -> e.pos().getZ())
                    .thenComparingInt(e -> (e.pos().getZ() & 1) == 0 ? e.pos().getX() : -e.pos().getX()));
            } else {
                targets.sort(Comparator.<Entry>comparingInt(e -> e.pos().getY())
                    .thenComparingInt(e -> e.pos().getZ())
                    .thenComparingInt(e -> (e.pos().getZ() & 1) == 0 ? e.pos().getX() : -e.pos().getX()));
            }
            index = 0;
            attemptsIndex = -1;
        }

        void tick(MinecraftClient mc) {
            if (!running) return;
            if (mc.player == null || mc.world == null || mc.interactionManager == null) { stop(); return; }
            if (cooldown > 0) cooldown--;
            if (area || mode == Mode.MINE) tickMine(mc); else tickBuild(mc);
        }

        /** Turns the camera smoothly toward a point. Returns the remaining angle error in degrees. */
        private float smoothLook(ClientPlayerEntity pl, Vec3d target) {
            Vec3d eye = pl.getEyePos();
            double dx = target.x - eye.x, dy = target.y - eye.y, dz = target.z - eye.z;
            double horiz = Math.sqrt(dx * dx + dz * dz);
            float wantYaw = (float) (Math.toDegrees(Math.atan2(dz, dx)) - 90.0);
            float wantPitch = (float) -Math.toDegrees(Math.atan2(dy, horiz));
            float dYaw = MathHelper.wrapDegrees(wantYaw - pl.getYaw());
            float dPitch = wantPitch - pl.getPitch();
            float stepYaw = easeStep(dYaw);
            float stepPitch = easeStep(dPitch);
            pl.setYaw(pl.getYaw() + stepYaw);
            pl.setPitch(MathHelper.clamp(pl.getPitch() + stepPitch, -90f, 90f));
            return Math.max(Math.abs(dYaw - stepYaw), Math.abs(dPitch - stepPitch));
        }

        /** Moves a fraction of the remaining angle each tick, capped, so turns look smooth. */
        private static float easeStep(float d) {
            float a = Math.abs(d);
            float s = Math.min(Math.max(a * 0.25f, Math.min(a, 1.5f)), 10f);
            return Math.copySign(s, d);
        }

        private void walkTo(MinecraftClient mc, ClientPlayerEntity pl, Vec3d center) {
            float err = smoothLook(pl, center);
            mc.options.forwardKey.setPressed(err < 50f);
            mc.options.jumpKey.setPressed(pl.horizontalCollision);
        }

        private void holdStill(MinecraftClient mc) {
            mc.options.forwardKey.setPressed(false);
            mc.options.jumpKey.setPressed(false);
        }

        private void tickMine(MinecraftClient mc) {
            while (index < targets.size() && mc.world.getBlockState(targets.get(index).pos()).isAir()) index++;
            if (index >= targets.size()) { stop(); status = "Done!"; msg("Done!"); return; }

            BlockPos target = targets.get(index).pos();
            if (mc.world.getBlockState(target).getHardness(mc.world, target) < 0) { index++; return; }

            ClientPlayerEntity pl = mc.player;
            Vec3d center = Vec3d.ofCenter(target);
            status = (area ? "Area miner: " : "Mining: ") + remaining() + " left";

            if (pl.getEyePos().distanceTo(center) <= REACH) {
                holdStill(mc);
                float err = smoothLook(pl, center);
                if (err < 20f) {
                    mc.interactionManager.updateBlockBreakingProgress(target, Direction.UP);
                    pl.swingHand(Hand.MAIN_HAND);
                } else {
                    mc.interactionManager.cancelBlockBreaking();
                }
            } else {
                walkTo(mc, pl, center);
            }
        }

        private void tickBuild(MinecraftClient mc) {
            while (index < targets.size()) {
                BlockState st = mc.world.getBlockState(targets.get(index).pos());
                if (st.isAir() || st.isReplaceable()) break;
                index++;
            }
            if (index >= targets.size()) { stop(); status = "Done!"; msg("Done!"); return; }

            if (attemptsIndex != index) { attemptsIndex = index; attempts = 0; }
            Entry e = targets.get(index);
            BlockPos target = e.pos();
            ClientPlayerEntity pl = mc.player;

            Block block = Registries.BLOCK.get(Identifier.of(e.block()));
            Item item = block.asItem();
            if (item == Items.AIR) { index++; return; }

            int slot = -1;
            for (int i = 0; i < 9; i++) {
                if (pl.getInventory().getStack(i).isOf(item)) { slot = i; break; }
            }
            if (slot < 0) {
                holdStill(mc);
                status = "Missing " + e.block() + " in hotbar";
                return;
            }

            Vec3d center = Vec3d.ofCenter(target);
            status = "Building: " + remaining() + " left";

            if (pl.getEyePos().distanceTo(center) > REACH) { walkTo(mc, pl, center); return; }
            holdStill(mc);
            float err = smoothLook(pl, center);
            if (err > 20f || cooldown > 0) return;

            Direction side = null;
            for (Direction d : Direction.values()) {
                if (!mc.world.getBlockState(target.offset(d)).isAir()
                    && !mc.world.getBlockState(target.offset(d)).isReplaceable()) { side = d; break; }
            }
            if (side == null || ++attempts > 20) { index++; return; }

            BlockPos neighbor = target.offset(side);
            Direction face = side.getOpposite();
            Vec3d hitPos = Vec3d.ofCenter(neighbor).add(Vec3d.of(face.getVector()).multiply(0.5));
            pl.getInventory().setSelectedSlot(slot);
            mc.interactionManager.interactBlock(pl, Hand.MAIN_HAND, new BlockHitResult(hitPos, face, neighbor, false));
            pl.swingHand(Hand.MAIN_HAND);
            cooldown = placeDelay;
        }

        private void msg(String s) {
            MinecraftClient mc = MinecraftClient.getInstance();
            if (mc.player != null) mc.player.sendMessage(Text.literal("[T0Rbem] " + s), false);
        }
    }

    // ───────────────────────── GUI (sidebar + search + tabs) ─────────────────────────
    static class T0RbemScreen extends Screen {
        enum Tab {
            BUILDER("Schematic Builder"), AREA("Area Miner"), SINGLEPLAYER("Singleplayer Tools"), SETTINGS("Settings");
            final String label;
            Tab(String label) { this.label = label; }
        }

        private static class Feature {
            final String name; final Tab tab; final ButtonWidget widget;
            Feature(String name, Tab tab, ButtonWidget widget) { this.name = name; this.tab = tab; this.widget = widget; }
        }

        private static Tab currentTab = Tab.BUILDER;
        private static String savedQuery = "";

        private static final int PW = 390, PH = 250;
        private int left, top, cx0;
        private String query = "";
        private List<String> files;
        private int fileIdx = 0;
        private TextFieldWidget search;
        private final List<Feature> features = new ArrayList<>();
        private final List<ButtonWidget> tabButtons = new ArrayList<>();

        T0RbemScreen() { super(Text.literal("T0Rbem Client")); }

        private ButtonWidget feature(Tab tab, String name, Text label, ButtonWidget.PressAction action) {
            ButtonWidget b = ButtonWidget.builder(label, action).dimensions(0, 0, 240, 20).build();
            addDrawableChild(b);
            features.add(new Feature(name, tab, b));
            return b;
        }

        @Override
        protected void init() {
            features.clear();
            tabButtons.clear();
            files = Miner.listFiles();
            Miner m = Miner.INSTANCE;
            if (m.loadedName != null && files.contains(m.loadedName)) fileIdx = files.indexOf(m.loadedName);

            left = (width - PW) / 2;
            top = (height - PH) / 2;
            cx0 = left + 136;
            query = savedQuery;

            search = new TextFieldWidget(textRenderer, cx0, top + 28, 240, 18, Text.literal("Search"));
            search.setPlaceholder(Text.literal("Search features..."));
            search.setText(query);
            search.setChangedListener(s -> { query = s.toLowerCase().trim(); savedQuery = s; layout(); });
            addDrawableChild(search);
            query = query.toLowerCase().trim();

            // Sidebar tabs
            Tab[] tabs = Tab.values();
            for (int i = 0; i < tabs.length; i++) {
                final Tab t = tabs[i];
                ButtonWidget tb = ButtonWidget.builder(Text.literal(t.label), b -> {
                    currentTab = t;
                    search.setText("");
                    layout();
                }).dimensions(left + 8, top + 34 + i * 24, 112, 20).build();
                addDrawableChild(tb);
                tabButtons.add(tb);
            }

            // ----- Schematic Builder -----
            feature(Tab.BUILDER, "file select schematic", fileLabel(), b -> {
                if (files.isEmpty()) return;
                fileIdx = (fileIdx + 1) % files.size();
                b.setMessage(fileLabel());
            });
            feature(Tab.BUILDER, "load schematic", Text.literal("Load schematic"), b -> {
                if (!files.isEmpty()) m.load(files.get(fileIdx));
                else m.status = "No schematic found in schematics/";
            });
            feature(Tab.BUILDER, "mode mine build", modeLabel(), b -> {
                m.toggleMode();
                b.setMessage(modeLabel());
            });
            feature(Tab.BUILDER, "set origin position", Text.literal("Set origin to my position"), b -> {
                if (client != null && client.player != null) m.setOrigin(client.player.getBlockPos());
            });
            feature(Tab.BUILDER, "start builder schematic", Text.literal("Start schematic"), b -> m.start());
            feature(Tab.BUILDER, "stop builder schematic", Text.literal("Stop"), b -> m.stop());

            // ----- Area Miner -----
            feature(Tab.AREA, "area miner corner 1 pos1", Text.literal("Set corner 1 to my position"), b -> {
                if (client != null && client.player != null) {
                    m.areaA = client.player.getBlockPos();
                    m.status = "Corner 1: " + m.areaA.toShortString();
                }
            });
            feature(Tab.AREA, "area miner corner 2 pos2", Text.literal("Set corner 2 to my position"), b -> {
                if (client != null && client.player != null) {
                    m.areaB = client.player.getBlockPos();
                    m.status = "Corner 2: " + m.areaB.toShortString();
                }
            });
            feature(Tab.AREA, "area miner start", Text.literal("Start area miner"), b -> m.startArea(MinecraftClient.getInstance()));
            feature(Tab.AREA, "area miner stop", Text.literal("Stop"), b -> m.stop());

            // ----- Singleplayer Tools -----
            feature(Tab.SINGLEPLAYER, "flight fly", flightLabel(), b -> {
                m.setFlight(MinecraftClient.getInstance(), !m.flight);
                b.setMessage(flightLabel());
            });
            feature(Tab.SINGLEPLAYER, "no fall damage", noFallLabel(), b -> {
                m.setNoFall(MinecraftClient.getInstance(), !m.noFall);
                b.setMessage(noFallLabel());
            });
            feature(Tab.SINGLEPLAYER, "block finder ore target", finderLabel(), b -> {
                m.finderIndex = (m.finderIndex + 1) % Miner.FINDER_TARGETS.length;
                b.setMessage(finderLabel());
            });
            feature(Tab.SINGLEPLAYER, "block finder scan ore", Text.literal("Scan nearby blocks"),
                b -> m.scanBlocks(MinecraftClient.getInstance()));

            // ----- Settings -----
            feature(Tab.SETTINGS, "place delay speed", delayLabel(), b -> {
                m.placeDelay = m.placeDelay >= 6 ? 1 : m.placeDelay + 1;
                b.setMessage(delayLabel());
            });
            ButtonWidget key = feature(Tab.SETTINGS, "keybind menu key right shift", Text.literal("Menu key: Right Shift (change in Controls)"), b -> {});
            key.active = false;

            // Kill switch: always visible on every tab
            addDrawableChild(ButtonWidget.builder(Text.literal("DISABLE ALL"), b -> m.disableAll())
                .dimensions(cx0, top + PH - 42, 240, 20).build());

            layout();
        }

        private void layout() {
            int y = top + 54;
            int maxY = top + PH - 48;
            for (Feature f : features) {
                boolean match = query.isEmpty() ? f.tab == currentTab : (f.name.contains(query) || f.tab.label.toLowerCase().contains(query));
                boolean show = match && y + 20 <= maxY;
                f.widget.visible = show;
                if (show) {
                    f.widget.setPosition(cx0, y);
                    y += 24;
                }
            }
            Tab[] tabs = Tab.values();
            for (int i = 0; i < tabButtons.size(); i++) {
                tabButtons.get(i).setMessage(Text.literal((tabs[i] == currentTab && query.isEmpty() ? "> " : "") + tabs[i].label));
            }
        }

        private Text fileLabel() {
            if (files.isEmpty()) return Text.literal("No files in schematics/");
            return Text.literal("File: " + files.get(fileIdx));
        }

        private Text modeLabel() {
            return Text.literal("Mode: " + (Miner.INSTANCE.mode == Mode.MINE ? "Mine (remove blocks)" : "Build (place from hotbar)"));
        }

        private Text flightLabel() {
            return Text.literal("Flight: " + (Miner.INSTANCE.flight ? "ON" : "OFF") + " (singleplayer)");
        }

        private Text noFallLabel() {
            return Text.literal("No fall: " + (Miner.INSTANCE.noFall ? "ON" : "OFF") + " (singleplayer)");
        }

        private Text finderLabel() {
            return Text.literal("Block finder: " + Miner.FINDER_TARGETS[Miner.INSTANCE.finderIndex]);
        }

        private Text delayLabel() {
            return Text.literal("Place delay: " + Miner.INSTANCE.placeDelay + " ticks");
        }

        @Override
        public void renderBackground(DrawContext ctx, int mouseX, int mouseY, float delta) {
            super.renderBackground(ctx, mouseX, mouseY, delta);
            ctx.fill(left, top, left + PW, top + PH, 0xF0121218);          // panel
            ctx.fill(left, top, left + 128, top + PH, 0xFF1B1B26);         // sidebar
            ctx.fill(left, top, left + PW, top + 2, 0xFF8A5CFF);           // accent line
            ctx.fill(left + 128, top + 2, left + 129, top + PH, 0xFF2A2A3A);
        }

        @Override
        public void render(DrawContext ctx, int mouseX, int mouseY, float delta) {
            super.render(ctx, mouseX, mouseY, delta);
            Miner m = Miner.INSTANCE;
            ctx.drawTextWithShadow(textRenderer, Text.literal("T0Rbem Client"), left + 10, top + 12, 0xFFFFFFFF);
            ctx.drawTextWithShadow(textRenderer, Text.literal(m.running ? "RUNNING" : "Idle"), cx0, top + 10,
                m.running ? 0xFF55FF55 : 0xFFAAAAAA);
            ctx.drawTextWithShadow(textRenderer, Text.literal(m.status), cx0, top + PH - 16, 0xFFBBBBBB);
        }

        @Override
        public boolean shouldPause() { return false; }
    }
}
