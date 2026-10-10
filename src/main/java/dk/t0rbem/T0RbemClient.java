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
import net.minecraft.client.network.ClientPlayerEntity;
import net.minecraft.client.option.KeyBinding;
import net.minecraft.command.argument.EntityAnchorArgumentType;
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
    private boolean openGui = false;

    @Override
    public void onInitializeClient() {
        openKey = KeyBindingHelper.registerKeyBinding(new KeyBinding(
            "key.t0rbem.open",
            GLFW.GLFW_KEY_RIGHT_SHIFT,
            KeyBinding.Category.create(Identifier.of("t0rbem_client", "main"))));

        ClientCommandRegistrationCallback.EVENT.register((dispatcher, registryAccess) ->
            dispatcher.register(ClientCommandManager.literal("t0rbem").executes(ctx -> {
                openGui = true;
                return 1;
            })));

        ClientTickEvents.END_CLIENT_TICK.register(mc -> {
            while (openKey.wasPressed()) {
                if (mc.currentScreen == null) openGui = true;
            }
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

    // ───────────────────────── Miner / Builder ─────────────────────────
    enum Mode { MINE, BUILD }

    static class Miner {
        static final Miner INSTANCE = new Miner();
        private static final double REACH = 4.5;

        Schematic schematic;
        String loadedName = null;
        BlockPos origin;
        Mode mode = Mode.MINE;
        boolean running = false;
        String status = "Ingen schematic indlæst.";

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
                status = "Indlæste " + schematic.blocks.size() + " blokke fra " + file;
                rebuild();
            } catch (Exception e) {
                schematic = null;
                loadedName = null;
                status = "Fejl: " + e.getMessage();
            }
        }

        void setOrigin(BlockPos p) {
            origin = p;
            rebuild();
            status = "Origin: " + p.toShortString();
        }

        void toggleMode() {
            mode = (mode == Mode.MINE) ? Mode.BUILD : Mode.MINE;
            rebuild();
        }

        void start() {
            if (schematic == null) { status = "Vælg og indlæs en schematic først."; return; }
            if (origin == null) { status = "Sæt origin først."; return; }
            rebuild();
            running = true;
            status = "Kører...";
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

        int remaining() { return Math.max(0, targets.size() - index); }

        private void rebuild() {
            if (schematic == null || origin == null) return;
            targets = new ArrayList<>();
            for (Entry e : schematic.blocks) targets.add(new Entry(origin.add(e.pos()), e.block()));
            if (mode == Mode.MINE) {
                // øverste lag først, slangemønster
                targets.sort(Comparator.<Entry>comparingInt(e -> -e.pos().getY())
                    .thenComparingInt(e -> e.pos().getZ())
                    .thenComparingInt(e -> (e.pos().getZ() & 1) == 0 ? e.pos().getX() : -e.pos().getX()));
            } else {
                // nederste lag først så der er noget at placere op mod
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
            if (mode == Mode.MINE) tickMine(mc); else tickBuild(mc);
        }

        private void walkTo(MinecraftClient mc, ClientPlayerEntity pl, Vec3d center) {
            pl.lookAt(EntityAnchorArgumentType.EntityAnchor.EYES, center);
            mc.options.forwardKey.setPressed(true);
            mc.options.jumpKey.setPressed(pl.horizontalCollision);
        }

        private void holdStill(MinecraftClient mc) {
            mc.options.forwardKey.setPressed(false);
            mc.options.jumpKey.setPressed(false);
        }

        private void tickMine(MinecraftClient mc) {
            while (index < targets.size() && mc.world.getBlockState(targets.get(index).pos()).isAir()) index++;
            if (index >= targets.size()) { stop(); status = "Færdig!"; msg("Færdig!"); return; }

            BlockPos target = targets.get(index).pos();
            if (mc.world.getBlockState(target).getHardness(mc.world, target) < 0) { index++; return; }

            ClientPlayerEntity pl = mc.player;
            Vec3d center = Vec3d.ofCenter(target);
            status = "Mining: " + remaining() + " tilbage";

            if (pl.getEyePos().distanceTo(center) <= REACH) {
                holdStill(mc);
                pl.lookAt(EntityAnchorArgumentType.EntityAnchor.EYES, center);
                mc.interactionManager.updateBlockBreakingProgress(target, Direction.UP);
                pl.swingHand(Hand.MAIN_HAND);
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
            if (index >= targets.size()) { stop(); status = "Færdig!"; msg("Færdig!"); return; }

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
                status = "Mangler " + e.block() + " i hotbar";
                return;
            }

            Vec3d center = Vec3d.ofCenter(target);
            status = "Bygger: " + remaining() + " tilbage";

            if (pl.getEyePos().distanceTo(center) > REACH) { walkTo(mc, pl, center); return; }
            holdStill(mc);
            if (cooldown > 0) return;

            // find en nabo-blok at placere op mod
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
            pl.lookAt(EntityAnchorArgumentType.EntityAnchor.EYES, center);
            mc.interactionManager.interactBlock(pl, Hand.MAIN_HAND, new BlockHitResult(hitPos, face, neighbor, false));
            pl.swingHand(Hand.MAIN_HAND);
            cooldown = 2;
        }

        private void msg(String s) {
            MinecraftClient mc = MinecraftClient.getInstance();
            if (mc.player != null) mc.player.sendMessage(Text.literal("[T0Rbem] " + s), false);
        }
    }

    // ───────────────────────── GUI ─────────────────────────
    static class T0RbemScreen extends Screen {
        private List<String> files;
        private int fileIdx = 0;

        T0RbemScreen() { super(Text.literal("T0Rbem client")); }

        @Override
        protected void init() {
            files = Miner.listFiles();
            Miner m = Miner.INSTANCE;
            if (m.loadedName != null && files.contains(m.loadedName)) fileIdx = files.indexOf(m.loadedName);

            int cx = width / 2, y = height / 2 - 70, w = 200, h = 20, gap = 24;

            addDrawableChild(ButtonWidget.builder(fileLabel(), b -> {
                if (files.isEmpty()) return;
                fileIdx = (fileIdx + 1) % files.size();
                b.setMessage(fileLabel());
            }).dimensions(cx - w / 2, y, w, h).build());

            addDrawableChild(ButtonWidget.builder(Text.literal("Indlæs schematic"), b -> {
                if (!files.isEmpty()) m.load(files.get(fileIdx));
                else m.status = "Ingen schematic i mappen schematics/";
            }).dimensions(cx - w / 2, y + gap, w, h).build());

            addDrawableChild(ButtonWidget.builder(modeLabel(), b -> {
                m.toggleMode();
                b.setMessage(modeLabel());
            }).dimensions(cx - w / 2, y + gap * 2, w, h).build());

            addDrawableChild(ButtonWidget.builder(Text.literal("Sæt origin til min position"), b -> {
                if (client != null && client.player != null) m.setOrigin(client.player.getBlockPos());
            }).dimensions(cx - w / 2, y + gap * 3, w, h).build());

            addDrawableChild(ButtonWidget.builder(Text.literal("Start"), b -> m.start())
                .dimensions(cx - w / 2, y + gap * 4, w / 2 - 2, h).build());
            addDrawableChild(ButtonWidget.builder(Text.literal("Stop"), b -> m.stop())
                .dimensions(cx + 2, y + gap * 4, w / 2 - 2, h).build());

            addDrawableChild(ButtonWidget.builder(Text.literal("Luk"), b -> close())
                .dimensions(cx - w / 2, y + gap * 5 + 6, w, h).build());
        }

        private Text fileLabel() {
            if (files.isEmpty()) return Text.literal("Ingen filer i schematics/");
            return Text.literal("Fil: " + files.get(fileIdx));
        }

        private Text modeLabel() {
            return Text.literal("Tilstand: " + (Miner.INSTANCE.mode == Mode.MINE ? "Mine (fjern blokke)" : "Build (placer fra hotbar)"));
        }

        @Override
        public void render(DrawContext ctx, int mouseX, int mouseY, float delta) {
            super.render(ctx, mouseX, mouseY, delta);
            Miner m = Miner.INSTANCE;
            int cx = width / 2;
            ctx.drawCenteredTextWithShadow(textRenderer, Text.literal("T0Rbem client"), cx, height / 2 - 100, 0xFFFFFF);
            ctx.drawCenteredTextWithShadow(textRenderer, Text.literal(m.status), cx, height / 2 + 90, 0xAAAAAA);
            ctx.drawCenteredTextWithShadow(textRenderer,
                Text.literal(m.running ? "Status: KØRER" : "Status: stoppet"), cx, height / 2 + 102,
                m.running ? 0x55FF55 : 0xFF5555);
        }

        @Override
        public boolean shouldPause() { return false; }
    }
}
