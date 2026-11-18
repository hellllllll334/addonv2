package com.stashhunter.addon.modules;

import com.stashhunter.addon.StashHunterAddon;
import com.stashhunter.addon.util.DiscordWebhook;
import meteordevelopment.meteorclient.events.world.TickEvent;
import meteordevelopment.meteorclient.settings.*;
import meteordevelopment.meteorclient.systems.modules.Module;
import meteordevelopment.meteorclient.utils.network.MeteorExecutor;
import meteordevelopment.meteorclient.utils.player.ChatUtils;
import meteordevelopment.meteorclient.utils.player.FindItemResult;
import meteordevelopment.meteorclient.utils.player.InvUtils;
import meteordevelopment.meteorclient.utils.world.BlockUtils;
import meteordevelopment.orbit.EventHandler;
import net.minecraft.block.ChestBlock;
import net.minecraft.block.ShulkerBoxBlock;
import net.minecraft.block.entity.*;
import net.minecraft.block.enums.ChestType;
import net.minecraft.client.network.ClientPlayerEntity;
import net.minecraft.client.util.ScreenshotRecorder;
import net.minecraft.component.DataComponentTypes;
import net.minecraft.component.type.ContainerComponent;
import net.minecraft.entity.EquipmentSlot;
import net.minecraft.item.BlockItem;
import net.minecraft.item.ItemStack;
import net.minecraft.item.Items;
import net.minecraft.network.packet.c2s.play.ClientCommandC2SPacket;
import net.minecraft.registry.tag.ItemTags;
import net.minecraft.screen.ShulkerBoxScreenHandler;
import net.minecraft.screen.slot.SlotActionType;
import net.minecraft.util.Hand;
import net.minecraft.util.hit.BlockHitResult;
import net.minecraft.util.hit.HitResult;
import net.minecraft.util.math.*;
import net.minecraft.world.RaycastContext;
import net.minecraft.world.chunk.WorldChunk;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;

public class StashHunter extends Module {
    public enum Heading {
        North(180), South(0), East(-90), West(90), NorthEast(-135), NorthWest(135), SouthEast(-45), SouthWest(45);
        public final float yaw;
        Heading(float yaw) { this.yaw = yaw; }
    }

    private enum State { FLY, INVESTIGATE, SNAP, TAKEOFF, REFILL_DESCEND, REFILL_PLACE, REFILL_OPEN, REFILL_TAKE, REFILL_BREAK, REFILL_EQUIP }

    private static class Info {
        int doubles, singles, barrels, shulkers, others, n;
        double sx, sy, sz;
        int total() { return doubles + singles + barrels + shulkers + others; }
        void add(BlockPos p) { sx += p.getX(); sy += p.getY(); sz += p.getZ(); n++; }
        void merge(Info o) {
            doubles += o.doubles; singles += o.singles; barrels += o.barrels; shulkers += o.shulkers; others += o.others;
            sx += o.sx; sy += o.sy; sz += o.sz; n += o.n;
        }
        Vec3d center() { return n == 0 ? Vec3d.ZERO : new Vec3d(sx / n, sy / n, sz / n); }
    }

    // ---------- settings ----------
    private final SettingGroup sgFlight = settings.createGroup("Flight");
    private final SettingGroup sgFind = settings.createGroup("Stash Detection");
    private final SettingGroup sgRefill = settings.createGroup("Elytra Refill");
    private final SettingGroup sgAlerts = settings.createGroup("Alerts");

    private final Setting<Integer> flyY = sgFlight.add(new IntSetting.Builder().name("fly-y").description("Y level to hold while flying.")
        .defaultValue(250).range(-64, 320).sliderRange(-64, 320).build());
    private final Setting<Heading> heading = sgFlight.add(new EnumSetting.Builder<Heading>().name("heading").description("Direction to fly in.")
        .defaultValue(Heading.North).build());
    private final Setting<Integer> maxClimb = sgFlight.add(new IntSetting.Builder().name("max-climb-angle").defaultValue(45).range(10, 80).sliderRange(10, 80).build());
    private final Setting<Integer> maxDive = sgFlight.add(new IntSetting.Builder().name("max-dive-angle").defaultValue(20).range(5, 60).sliderRange(5, 60).build());
    private final Setting<Boolean> avoidBlocks = sgFlight.add(new BoolSetting.Builder().name("avoid-blocks").description("Steers up / sideways to avoid terrain.").defaultValue(true).build());
    private final Setting<Integer> avoidDistance = sgFlight.add(new IntSetting.Builder().name("avoid-distance").defaultValue(30).range(10, 100).sliderRange(10, 100).build());
    private final Setting<Double> minSpeed = sgFlight.add(new DoubleSetting.Builder().name("rocket-below-speed").description("Use a firework when speed (blocks/tick) drops below this.")
        .defaultValue(1.0).range(0.2, 3).sliderRange(0.2, 3).build());
    private final Setting<Integer> rocketDelay = sgFlight.add(new IntSetting.Builder().name("rocket-delay").description("Min ticks between fireworks.").defaultValue(25).range(5, 100).sliderRange(5, 100).build());

    private final Setting<Boolean> chests = sgFind.add(new BoolSetting.Builder().name("chests").defaultValue(true).build());
    private final Setting<Boolean> barrels = sgFind.add(new BoolSetting.Builder().name("barrels").defaultValue(true).build());
    private final Setting<Boolean> shulkers = sgFind.add(new BoolSetting.Builder().name("shulkers").defaultValue(true).build());
    private final Setting<Boolean> others = sgFind.add(new BoolSetting.Builder().name("furnaces-hoppers-dispensers").defaultValue(false).build());
    private final Setting<Integer> minTotal = sgFind.add(new IntSetting.Builder().name("min-containers").description("Containers needed nearby to count as a stash (a double chest counts as 1).")
        .defaultValue(12).range(1, 500).sliderRange(1, 100).build());
    private final Setting<Integer> minDoubles = sgFind.add(new IntSetting.Builder().name("min-double-chests").description("Also trigger on this many double chests. 0 = off.")
        .defaultValue(0).range(0, 200).sliderRange(0, 50).build());
    private final Setting<Integer> mergeRadius = sgFind.add(new IntSetting.Builder().name("merge-radius-blocks").description("Don't report another stash this close to one already reported.")
        .defaultValue(128).range(16, 1000).sliderRange(16, 500).build());

    private final Setting<Boolean> refill = sgRefill.add(new BoolSetting.Builder().name("refill-from-shulker").description("Land, place a shulker holding elytra, take them, break it, take off.").defaultValue(true).build());
    private final Setting<Integer> lowDurability = sgRefill.add(new IntSetting.Builder().name("swap-below-durability").description("Replace elytra when remaining durability is at or below this.")
        .defaultValue(40).range(5, 200).sliderRange(5, 200).build());
    private final Setting<Integer> takeElytra = sgRefill.add(new IntSetting.Builder().name("elytra-to-take").defaultValue(2).range(1, 8).sliderRange(1, 8).build());
    private final Setting<Boolean> takeFireworks = sgRefill.add(new BoolSetting.Builder().name("also-take-fireworks").defaultValue(true).build());

    private final Setting<String> webhook = sgAlerts.add(new StringSetting.Builder().name("discord-webhook").description("Discord webhook URL.").defaultValue("").wide().build());
    private final Setting<Boolean> chat = sgAlerts.add(new BoolSetting.Builder().name("chat-alerts").defaultValue(true).build());
    private final Setting<Boolean> sethome = sgAlerts.add(new BoolSetting.Builder().name("sethome-above-stash").defaultValue(true).build());
    private final Setting<String> sethomeCmd = sgAlerts.add(new StringSetting.Builder().name("sethome-command").description("{n} = stash number.").defaultValue("/sethome stash{n}").build());
    private final Setting<Boolean> screenshot = sgAlerts.add(new BoolSetting.Builder().name("sky-screenshot").description("Look straight down and attach a screenshot.").defaultValue(true).build());
    private final Setting<Integer> homeRadius = sgAlerts.add(new IntSetting.Builder().name("fly-over-radius").description("Horizontal distance to the stash before sethome/screenshot.")
        .defaultValue(10).range(2, 40).sliderRange(2, 40).build());

    // ---------- state ----------
    private State state = State.FLY;
    private final Set<Long> scanned = new HashSet<>();
    private final Map<Long, Info> chunkInfo = new HashMap<>();
    private final List<Vec3d> reported = new ArrayList<>();
    private final Deque<Info> pending = new ArrayDeque<>();
    private Info current;
    private int stashCount, tick, rocketCd, wait, snapTicks, taken;
    private BlockPos placePos;
    private final Set<Integer> takenSlots = new HashSet<>();
    private volatile byte[] shot;
    private volatile boolean shotDone;
    private boolean shotRequested;

    public StashHunter() {
        super(StashHunterAddon.CATEGORY, "stash-hunter", "Auto elytra stash hunter with Discord alerts.");
    }

    @Override
    public void onActivate() {
        scanned.clear(); chunkInfo.clear(); pending.clear(); current = null;
        state = State.FLY; rocketCd = 0; wait = 0; stashCount = 0;
        if (!hasUsableElytra(mc.player.getEquippedStack(EquipmentSlot.CHEST))) swapInFreshElytra();
        if (mc.player.isOnGround()) state = State.TAKEOFF;
    }

    @Override
    public void onDeactivate() {
        mc.options.jumpKey.setPressed(false);
    }

    @EventHandler
    private void onTick(TickEvent.Pre event) {
        if (mc.player == null || mc.world == null) return;
        tick++;
        if (rocketCd > 0) rocketCd--;
        if (tick % 10 == 0) scanChunks();
        ClientPlayerEntity p = mc.player;

        switch (state) {
            case FLY -> {
                if (!pending.isEmpty()) { current = pending.poll(); state = State.INVESTIGATE; return; }
                if (checkElytra()) return;
                flyTowards(null, null);
            }
            case INVESTIGATE -> {
                if (checkElytra()) return;
                Vec3d c = current.center();
                double dx = c.x - p.getX(), dz = c.z - p.getZ();
                if (Math.sqrt(dx * dx + dz * dz) <= homeRadius.get()) {
                    state = State.SNAP; snapTicks = 0; shot = null; shotDone = false; shotRequested = false;
                    return;
                }
                flyTowards(c, null);
            }
            case SNAP -> snap();
            case TAKEOFF -> takeoff();
            case REFILL_DESCEND -> descend();
            case REFILL_PLACE -> refillPlace();
            case REFILL_OPEN -> refillOpen();
            case REFILL_TAKE -> refillTake();
            case REFILL_BREAK -> refillBreak();
            case REFILL_EQUIP -> {
                if (swapInFreshElytra()) { state = State.TAKEOFF; }
                else fail("No usable elytra after refill.");
            }
        }
    }

    // ---------- flight ----------
    private void flyTowards(Vec3d target, Float forcePitch) {
        ClientPlayerEntity p = mc.player;
        if (!p.isGliding()) {
            if (p.isOnGround()) { state = State.TAKEOFF; return; }
            startGlide();
            return;
        }
        float yaw = target != null ? yawTo(target) : heading.get().yaw;
        double dy = flyY.get() - p.getY();
        float pitch = (float) MathHelper.clamp(-dy * 0.8 + p.getVelocity().y * 12, -maxClimb.get(), maxDive.get());
        if (forcePitch != null) pitch = forcePitch;
        float[] r = avoidBlocks.get() && forcePitch == null ? avoid(yaw, pitch) : new float[]{yaw, pitch};
        p.setYaw(r[0]);
        p.setPitch(r[1]);
        if (forcePitch == null && p.getVelocity().length() < minSpeed.get() && rocketCd <= 0) useFirework();
    }

    private float yawTo(Vec3d t) {
        return (float) Math.toDegrees(Math.atan2(-(t.x - mc.player.getX()), t.z - mc.player.getZ()));
    }

    private float[] avoid(float yaw, float pitch) {
        float[][] cands = {{0, pitch}, {0, -25}, {0, -50}, {30, pitch}, {-30, pitch}, {60, pitch}, {-60, pitch}, {0, -75}, {90, pitch}, {-90, pitch}};
        for (float[] c : cands) {
            float y = yaw + c[0];
            if (!blocked(y, c[1])) return new float[]{y, c[1]};
        }
        return new float[]{yaw, -90};
    }

    private boolean blocked(float yaw, float pitch) {
        ClientPlayerEntity p = mc.player;
        double dist = Math.max(avoidDistance.get(), p.getVelocity().length() * 10);
        Vec3d dir = Vec3d.fromPolar(pitch, yaw);
        Vec3d side = new Vec3d(-dir.z, 0, dir.x).normalize().multiply(0.7);
        Vec3d eye = p.getEyePos();
        for (Vec3d off : new Vec3d[]{Vec3d.ZERO, side, side.negate()}) {
            Vec3d s = eye.add(off);
            HitResult h = mc.world.raycast(new RaycastContext(s, s.add(dir.multiply(dist)),
                RaycastContext.ShapeType.COLLIDER, RaycastContext.FluidHandling.NONE, p));
            if (h.getType() != HitResult.Type.MISS) return true;
        }
        return false;
    }

    private void startGlide() {
        mc.getNetworkHandler().sendPacket(new ClientCommandC2SPacket(mc.player, ClientCommandC2SPacket.Mode.START_FALL_FLYING));
        mc.player.startGliding();
    }

    private void useFirework() {
        FindItemResult fw = InvUtils.findInHotbar(Items.FIREWORK_ROCKET);
        if (!fw.found()) {
            FindItemResult inv = InvUtils.find(Items.FIREWORK_ROCKET);
            if (inv.found()) InvUtils.move().from(inv.slot()).toHotbar(8);
            else if (rocketCd <= 0) { rocketCd = 200; if (chat.get()) warning("Out of fireworks!"); }
            return;
        }
        if (InvUtils.swap(fw.slot(), true)) {
            mc.interactionManager.interactItem(mc.player, Hand.MAIN_HAND);
            InvUtils.swapBack();
            rocketCd = rocketDelay.get();
        }
    }

    private void takeoff() {
        ClientPlayerEntity p = mc.player;
        if (p.isGliding()) { mc.options.jumpKey.setPressed(false); state = State.FLY; return; }
        if (checkElytra()) return;
        p.setYaw(heading.get().yaw);
        p.setPitch(-50);
        if (p.isOnGround()) {
            mc.options.jumpKey.setPressed(true);
        } else {
            mc.options.jumpKey.setPressed(false);
            if (p.getVelocity().y < 0) { startGlide(); useFirework(); }
        }
    }

    // ---------- elytra management ----------
    private int remaining(ItemStack s) { return s.getMaxDamage() - s.getDamage(); }
    private boolean hasUsableElytra(ItemStack s) { return s.isOf(Items.ELYTRA) && remaining(s) > 2; }

    /** Returns true if this tick was consumed by elytra handling. */
    private boolean checkElytra() {
        ItemStack chest = mc.player.getEquippedStack(EquipmentSlot.CHEST);
        boolean ok = chest.isOf(Items.ELYTRA) && remaining(chest) > lowDurability.get();
        if (ok) return false;
        if (swapInFreshElytra()) return true;
        if (refill.get() && findShulker() != -1) {
            if (mc.player.isGliding()) state = State.REFILL_DESCEND;
            else if (mc.player.isOnGround()) { state = State.REFILL_PLACE; placePos = null; wait = 0; takenSlots.clear(); taken = 0; }
            return true;
        }
        if (!hasUsableElytra(chest)) fail("Out of elytra (and no shulker with spares).");
        return false;
    }

    private int toId(int idx) { return idx < 9 ? idx + 36 : idx; }

    private boolean swapInFreshElytra() {
        ClientPlayerEntity p = mc.player;
        int best = -1, bestRem = lowDurability.get();
        for (int i = 0; i < 36; i++) {
            ItemStack s = p.getInventory().getStack(i);
            if (s.isOf(Items.ELYTRA) && remaining(s) > bestRem) { best = i; bestRem = remaining(s); }
        }
        if (best == -1) return false;
        int id = toId(best);
        mc.interactionManager.clickSlot(0, id, 0, SlotActionType.PICKUP, p);
        mc.interactionManager.clickSlot(0, 6, 0, SlotActionType.PICKUP, p);
        mc.interactionManager.clickSlot(0, id, 0, SlotActionType.PICKUP, p);
        return true;
    }

    private boolean shulkerHasElytra(ItemStack s) {
        if (!(s.getItem() instanceof BlockItem bi) || !(bi.getBlock() instanceof ShulkerBoxBlock)) return false;
        return s.getOrDefault(DataComponentTypes.CONTAINER, ContainerComponent.DEFAULT).streamNonEmpty()
            .anyMatch(i -> i.isOf(Items.ELYTRA) && remaining(i) > lowDurability.get());
    }

    private int findShulker() {
        for (int i = 0; i < 36; i++) if (shulkerHasElytra(mc.player.getInventory().getStack(i))) return i;
        return -1;
    }

    // ---------- refill sequence ----------
    private void descend() {
        ClientPlayerEntity p = mc.player;
        if (!p.isGliding()) {
            if (p.isOnGround()) { state = State.REFILL_PLACE; placePos = null; wait = 0; takenSlots.clear(); taken = 0; }
            else startGlide();
            return;
        }
        Vec3d from = new Vec3d(p.getX(), p.getY(), p.getZ());
        HitResult h = mc.world.raycast(new RaycastContext(from, from.add(0, -300, 0), RaycastContext.ShapeType.COLLIDER, RaycastContext.FluidHandling.ANY, p));
        double ground = h.getType() == HitResult.Type.MISS ? 300 : from.y - h.getPos().y;
        float pitch = ground > 40 ? 45 : ground > 12 ? 15 : 4;
        float[] r = avoid(heading.get().yaw, pitch);
        p.setYaw(r[0]); p.setPitch(Math.max(r[1], -10));
    }

    private void refillPlace() {
        ClientPlayerEntity p = mc.player;
        mc.options.jumpKey.setPressed(false);
        if (placePos == null) {
            for (Direction d : Direction.Type.HORIZONTAL) {
                BlockPos b = p.getBlockPos().offset(d);
                if (mc.world.getBlockState(b).isAir() && mc.world.getBlockState(b.up()).isAir() && !mc.world.getBlockState(b.down()).isAir()) { placePos = b; break; }
            }
            if (placePos == null && ++wait > 40) fail("No room to place a shulker.");
            return;
        }
        int idx = findShulker();
        if (idx == -1) { fail("Shulker with elytra not found."); return; }
        if (idx >= 9) { InvUtils.move().from(idx).toHotbar(8); return; }
        FindItemResult r = InvUtils.findInHotbar(this::shulkerHasElytra);
        if (r.found() && BlockUtils.place(placePos, r, true, 50)) { state = State.REFILL_OPEN; wait = 0; }
        else if (++wait > 60) fail("Couldn't place shulker.");
    }

    private void refillOpen() {
        ClientPlayerEntity p = mc.player;
        if (p.currentScreenHandler instanceof ShulkerBoxScreenHandler) { state = State.REFILL_TAKE; wait = 0; return; }
        if (!(mc.world.getBlockState(placePos).getBlock() instanceof ShulkerBoxBlock)) { if (++wait > 40) fail("Shulker never appeared."); return; }
        if (wait++ % 10 == 0) {
            mc.interactionManager.interactBlock(p, Hand.MAIN_HAND, new BlockHitResult(Vec3d.ofCenter(placePos), Direction.UP, placePos, false));
        }
        if (wait > 100) fail("Couldn't open shulker.");
    }

    private void refillTake() {
        ClientPlayerEntity p = mc.player;
        if (!(p.currentScreenHandler instanceof ShulkerBoxScreenHandler h)) { state = State.REFILL_BREAK; wait = 0; return; }
        if (wait++ % 2 != 0) return;
        for (int i = 0; i < 27; i++) {
            if (takenSlots.contains(i)) continue;
            ItemStack s = h.getSlot(i).getStack();
            boolean el = s.isOf(Items.ELYTRA) && remaining(s) > lowDurability.get() && taken < takeElytra.get();
            boolean fw = takeFireworks.get() && s.isOf(Items.FIREWORK_ROCKET);
            if (el || fw) {
                mc.interactionManager.clickSlot(h.syncId, i, 0, SlotActionType.QUICK_MOVE, p);
                takenSlots.add(i);
                if (el) taken++;
                return;
            }
        }
        p.closeHandledScreen();
        state = State.REFILL_BREAK; wait = 0;
    }

    private void refillBreak() {
        FindItemResult pick = InvUtils.findInHotbar(s -> s.isIn(ItemTags.PICKAXES));
        if (mc.world.getBlockState(placePos).isAir()) {
            if (++wait > 20) state = State.REFILL_EQUIP; // give item time to be picked up
            return;
        }
        if (pick.found()) InvUtils.swap(pick.slot(), false);
        BlockUtils.breakBlock(placePos, true);
    }

    // ---------- stash detection ----------
    private void scanChunks() {
        int r = Math.min(mc.options.getViewDistance().getValue(), 16);
        ChunkPos cp = mc.player.getChunkPos();
        for (int dx = -r; dx <= r; dx++) {
            for (int dz = -r; dz <= r; dz++) {
                ChunkPos pos = new ChunkPos(cp.x + dx, cp.z + dz);
                if (scanned.contains(pos.toLong())) continue;
                WorldChunk ch = mc.world.getChunkManager().getWorldChunk(pos.x, pos.z);
                if (ch == null) continue;
                scanned.add(pos.toLong());
                scanChunk(ch, pos);
            }
        }
    }

    private void scanChunk(WorldChunk ch, ChunkPos pos) {
        Info info = new Info();
        for (BlockEntity be : ch.getBlockEntities().values()) {
            if (be instanceof ChestBlockEntity) {
                if (!chests.get()) continue;
                ChestType t = be.getCachedState().get(ChestBlock.CHEST_TYPE);
                if (t == ChestType.SINGLE) { info.singles++; info.add(be.getPos()); }
                else if (t == ChestType.LEFT) { info.doubles++; info.add(be.getPos()); }
            } else if (be instanceof BarrelBlockEntity) {
                if (barrels.get()) { info.barrels++; info.add(be.getPos()); }
            } else if (be instanceof ShulkerBoxBlockEntity) {
                if (shulkers.get()) { info.shulkers++; info.add(be.getPos()); }
            } else if (be instanceof AbstractFurnaceBlockEntity || be instanceof HopperBlockEntity || be instanceof DispenserBlockEntity) {
                if (others.get()) { info.others++; info.add(be.getPos()); }
            }
        }
        if (info.total() == 0) return;
        chunkInfo.put(pos.toLong(), info);

        Info agg = new Info();
        for (int dx = -1; dx <= 1; dx++) for (int dz = -1; dz <= 1; dz++) {
            Info i = chunkInfo.get(new ChunkPos(pos.x + dx, pos.z + dz).toLong());
            if (i != null) agg.merge(i);
        }
        boolean hit = agg.total() >= minTotal.get() || (minDoubles.get() > 0 && agg.doubles >= minDoubles.get());
        if (!hit) return;
        Vec3d c = agg.center();
        for (Vec3d r : reported) if (Math.hypot(r.x - c.x, r.z - c.z) < mergeRadius.get()) return;
        reported.add(c);
        stashCount++;
        pending.add(agg);
        if (chat.get()) info("Stash found at (highlight)%d, %d, %d(default) - %d double chests, %d total containers.",
            (int) c.x, (int) c.y, (int) c.z, agg.doubles, agg.total());
    }

    // ---------- alert / screenshot ----------
    private void snap() {
        ClientPlayerEntity p = mc.player;
        snapTicks++;
        Vec3d c = current.center();
        if (snapTicks == 1 && sethome.get()) {
            ChatUtils.sendPlayerMsg(sethomeCmd.get().replace("{n}", String.valueOf(stashCount)));
        }
        if (screenshot.get()) {
            flyTowards(null, 90f);
            p.setYaw(yawTo(c));
            if (snapTicks == 5 && !shotRequested) {
                shotRequested = true;
                ScreenshotRecorder.takeScreenshot(mc.getFramebuffer(), img -> {
                    try {
                        Path tmp = Files.createTempFile("stash", ".png");
                        img.writeTo(tmp);
                        shot = Files.readAllBytes(tmp);
                        Files.deleteIfExists(tmp);
                    } catch (Exception ignored) {
                    } finally {
                        img.close();
                        shotDone = true;
                    }
                });
            }
            if (!(shotDone || snapTicks > 80)) return;
        } else if (snapTicks < 3) return;

        sendWebhook(current, shot);
        current = null;
        state = State.FLY;
    }

    private void sendWebhook(Info s, byte[] png) {
        String url = webhook.get().trim();
        if (url.isEmpty()) return;
        Vec3d c = s.center();
        Map<String, String> f = new LinkedHashMap<>();
        f.put("Coords", String.format("%d, %d, %d", (int) c.x, (int) c.y, (int) c.z));
        f.put("Double chests", String.valueOf(s.doubles));
        f.put("Single chests", String.valueOf(s.singles));
        f.put("Barrels", String.valueOf(s.barrels));
        f.put("Shulkers", String.valueOf(s.shulkers));
        f.put("Total containers", String.valueOf(s.total()));
        f.put("Dimension", mc.world.getRegistryKey().getValue().toString());
        if (mc.getCurrentServerEntry() != null) f.put("Server", mc.getCurrentServerEntry().address);
        if (sethome.get()) f.put("Home", sethomeCmd.get().replace("{n}", String.valueOf(stashCount)).replaceFirst("^/sethome ", ""));
        String title = "Stash #" + stashCount + " found";
        MeteorExecutor.execute(() -> DiscordWebhook.send(url, title, f, png));
    }

    private void fail(String msg) {
        error(msg);
        toggle();
    }
}
