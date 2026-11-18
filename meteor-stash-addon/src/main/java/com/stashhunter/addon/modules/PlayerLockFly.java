package com.stashhunter.addon.modules;

import com.stashhunter.addon.StashHunterAddon;
import meteordevelopment.meteorclient.events.world.TickEvent;
import meteordevelopment.meteorclient.settings.*;
import meteordevelopment.meteorclient.systems.friends.Friends;
import meteordevelopment.meteorclient.systems.modules.Module;
import meteordevelopment.orbit.EventHandler;
import net.minecraft.client.network.ClientPlayerEntity;
import net.minecraft.entity.EquipmentSlot;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.item.ItemStack;
import net.minecraft.item.Items;
import net.minecraft.network.packet.c2s.play.ClientCommandC2SPacket;
import net.minecraft.screen.slot.SlotActionType;
import net.minecraft.util.hit.HitResult;
import net.minecraft.util.math.MathHelper;
import net.minecraft.util.math.Vec3d;
import net.minecraft.world.RaycastContext;

public class PlayerLockFly extends Module {
    private final SettingGroup sg = settings.getDefaultGroup();

    private final Setting<Double> speed = sg.add(new DoubleSetting.Builder().name("speed").description("Blocks per tick.")
        .defaultValue(1.8).range(0.5, 5).sliderRange(0.5, 5).build());
    private final Setting<Integer> range = sg.add(new IntSetting.Builder().name("range").description("Max distance to lock on.")
        .defaultValue(256).range(16, 512).sliderRange(16, 512).build());
    private final Setting<String> onlyTarget = sg.add(new StringSetting.Builder().name("only-target").description("Only lock this player name. Empty = closest.").defaultValue("").build());
    private final Setting<Boolean> ignoreFriends = sg.add(new BoolSetting.Builder().name("ignore-friends").defaultValue(true).build());
    private final Setting<Boolean> matchY = sg.add(new BoolSetting.Builder().name("match-target-y").description("Gently match the target's height. Off = perfectly level flight.").defaultValue(true).build());
    private final Setting<Integer> maxPitch = sg.add(new IntSetting.Builder().name("max-pitch").description("Max up/down angle when matching Y.").defaultValue(20).range(0, 60).sliderRange(0, 60).build());
    private final Setting<Boolean> predict = sg.add(new BoolSetting.Builder().name("predict-movement").defaultValue(true).build());
    private final Setting<Integer> overshoot = sg.add(new IntSetting.Builder().name("overshoot-ticks").description("Keep going straight this long after passing through the target before turning around.")
        .defaultValue(12).range(0, 60).sliderRange(0, 60).build());
    private final Setting<Boolean> avoidBlocks = sg.add(new BoolSetting.Builder().name("avoid-blocks").description("Climb if terrain is ahead.").defaultValue(true).build());

    private PlayerEntity target;
    private Vec3d lastTargetPos;
    private int hold;
    private float lockYaw, lockPitch;

    public PlayerLockFly() {
        super(StashHunterAddon.CATEGORY, "player-lock-fly", "Level elytra flight that locks onto a player and keeps flying through them.");
    }

    @Override
    public void onActivate() { target = null; lastTargetPos = null; hold = 0; }

    @Override
    public void onDeactivate() { mc.options.jumpKey.setPressed(false); }

    @EventHandler
    private void onTick(TickEvent.Pre event) {
        ClientPlayerEntity p = mc.player;
        if (p == null || mc.world == null) return;

        ItemStack chest = p.getEquippedStack(EquipmentSlot.CHEST);
        if (!chest.isOf(Items.ELYTRA) || chest.getMaxDamage() - chest.getDamage() <= 2) {
            if (!equipElytra()) { error("No usable elytra."); toggle(); return; }
        }

        if (!p.isGliding()) {
            if (p.isOnGround()) { mc.options.jumpKey.setPressed(true); }
            else {
                mc.options.jumpKey.setPressed(false);
                mc.getNetworkHandler().sendPacket(new ClientCommandC2SPacket(p, ClientCommandC2SPacket.Mode.START_FALL_FLYING));
                p.startGliding();
            }
            return;
        }
        mc.options.jumpKey.setPressed(false);

        target = pick(p);
        if (target == null) { hold = 0; return; }

        Vec3d now = new Vec3d(target.getX(), target.getY(), target.getZ());
        Vec3d vel = lastTargetPos == null ? Vec3d.ZERO : now.subtract(lastTargetPos);
        lastTargetPos = now;

        Vec3d me = new Vec3d(p.getX(), p.getY(), p.getZ());
        Vec3d aim = now;
        double dist = me.distanceTo(now);
        if (predict.get()) aim = now.add(vel.multiply(MathHelper.clamp(dist / speed.get(), 0, 20)));

        if (hold > 0) {
            hold--;
        } else {
            double dx = aim.x - me.x, dz = aim.z - me.z;
            lockYaw = (float) Math.toDegrees(Math.atan2(-dx, dz));
            lockPitch = 0;
            if (matchY.get()) {
                double dy = (aim.y + target.getHeight() / 2) - (me.y + p.getHeight() / 2);
                lockPitch = (float) MathHelper.clamp(-dy * 0.8, -maxPitch.get(), maxPitch.get());
            }
            if (dist < 2.0) hold = overshoot.get();
        }

        float pitch = lockPitch;
        if (avoidBlocks.get() && blocked(lockYaw, pitch)) pitch = -45;

        p.setYaw(lockYaw);
        p.setPitch(pitch);
        p.setVelocity(Vec3d.fromPolar(pitch, lockYaw).multiply(speed.get()));
    }

    private PlayerEntity pick(ClientPlayerEntity p) {
        if (target != null && valid(p, target)) return target;
        lastTargetPos = null;
        PlayerEntity best = null;
        double bd = Double.MAX_VALUE;
        for (PlayerEntity e : mc.world.getPlayers()) {
            if (!valid(p, e)) continue;
            double d = p.squaredDistanceTo(e);
            if (d < bd) { bd = d; best = e; }
        }
        return best;
    }

    private boolean valid(ClientPlayerEntity p, PlayerEntity e) {
        if (e == p || !e.isAlive() || e.isSpectator() || e.isRemoved()) return false;
        if (p.distanceTo(e) > range.get()) return false;
        if (ignoreFriends.get() && Friends.get().isFriend(e)) return false;
        String n = onlyTarget.get().trim();
        return n.isEmpty() || e.getName().getString().equalsIgnoreCase(n);
    }

    private boolean blocked(float yaw, float pitch) {
        ClientPlayerEntity p = mc.player;
        Vec3d s = p.getEyePos();
        Vec3d end = s.add(Vec3d.fromPolar(pitch, yaw).multiply(Math.max(20, speed.get() * 12)));
        HitResult h = mc.world.raycast(new RaycastContext(s, end, RaycastContext.ShapeType.COLLIDER, RaycastContext.FluidHandling.NONE, p));
        return h.getType() != HitResult.Type.MISS;
    }

    private boolean equipElytra() {
        ClientPlayerEntity p = mc.player;
        int best = -1, rem = 2;
        for (int i = 0; i < 36; i++) {
            ItemStack s = p.getInventory().getStack(i);
            if (s.isOf(Items.ELYTRA) && s.getMaxDamage() - s.getDamage() > rem) { best = i; rem = s.getMaxDamage() - s.getDamage(); }
        }
        if (best == -1) return false;
        int id = best < 9 ? best + 36 : best;
        mc.interactionManager.clickSlot(0, id, 0, SlotActionType.PICKUP, p);
        mc.interactionManager.clickSlot(0, 6, 0, SlotActionType.PICKUP, p);
        mc.interactionManager.clickSlot(0, id, 0, SlotActionType.PICKUP, p);
        return true;
    }
}
