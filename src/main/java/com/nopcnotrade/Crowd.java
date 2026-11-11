package com.nopcnotrade;

import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvent;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.ai.behavior.EntityTracker;
import net.minecraft.world.entity.ai.memory.MemoryModuleType;
import net.minecraft.world.entity.ai.memory.WalkTarget;
import net.minecraft.world.entity.npc.villager.Villager;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.phys.Vec3;
import java.util.*;

public class Crowd {
    static final Map<UUID, Long> active = new HashMap<>(), calm = new HashMap<>(), nextSpeak = new HashMap<>();
    static final Map<UUID, double[]> prog = new HashMap<>(); // {last distance^2 to slot, stuck seconds}
    static final Map<UUID, long[]> strike = new HashMap<>(); // {refusals, lastTick}
    static final Map<String, Integer> used = new HashMap<>(); // "key|line" -> times said this wave
    static long lastCrowdLine = -10000, lastChant = -10000;
    static final ChatFormatting[] COLORS = {ChatFormatting.GREEN, ChatFormatting.AQUA, ChatFormatting.YELLOW, ChatFormatting.GOLD,
            ChatFormatting.LIGHT_PURPLE, ChatFormatting.RED, ChatFormatting.BLUE, ChatFormatting.DARK_AQUA};

    static boolean isTarget(ServerPlayer p) {
        return p.getName().getString().equalsIgnoreCase(Cfg.d.targetUsername.trim());
    }

    /** Every 0.5s while the target is online: movement. Every 1s: recruiting, expiry, speech. */
    public static void tick(MinecraftServer s) {
        int tc = s.getTickCount();
        if (tc % 10 != 0) return;
        boolean sec = tc % 20 == 0;
        String name = Cfg.d.targetUsername.trim();
        if (name.isEmpty()) return;
        ServerPlayer p = null;
        for (ServerPlayer sp : s.getPlayerList().getPlayers())
            if (isTarget(sp)) { p = sp; break; }
        if (p == null || p.isSpectator() || !(p.level() instanceof ServerLevel lv)) {
            if (!active.isEmpty()) { active.clear(); prog.clear(); }
            return;
        }
        long now = lv.getGameTime();
        if (sec) {
            for (var it = active.entrySet().iterator(); it.hasNext(); ) {
                var en = it.next();
                if (en.getValue() <= now) { calm.put(en.getKey(), now + 600); prog.remove(en.getKey()); it.remove(); }
            }
            calm.values().removeIf(t -> t <= now);
        }
        List<Villager> vs = lv.getEntitiesOfClass(Villager.class, p.getBoundingBox().inflate(Cfg.d.radius),
                v -> v.isAlive() && !v.isSleeping());
        if (vs.isEmpty()) return;
        final ServerPlayer pl = p;
        vs.sort(Comparator.comparingDouble(v -> v.distanceToSqr(pl)));

        if (sec) { // gradual recruitment, max 3 per second
            boolean fresh = active.isEmpty();
            if (fresh) used.keySet().removeIf(k -> k.startsWith("recognize|"));
            int add = 0;
            for (Villager v : vs) {
                if (active.size() >= Cfg.d.maxCrowd || add >= 3) break;
                if (active.containsKey(v.getUUID()) || calm.containsKey(v.getUUID())) continue;
                active.put(v.getUUID(), now + Cfg.d.crowdSeconds * 20L);
                if (fresh && add == 0) { say(lv, v, "shout"); lastCrowdLine = now; }
                add++;
            }
        }

        List<Villager> crowd = new ArrayList<>();
        for (Villager v : vs) if (active.containsKey(v.getUUID())) crowd.add(v);
        for (Villager v : crowd) {
            int h = v.getUUID().hashCode();
            double ang = ((h & 0xFFFF) / 65536.0) * Math.PI * 2, rad = 1.8 + ((h >>> 16) & 3) * 0.7; // stable ring slot
            Vec3 t = p.position().add(Math.cos(ang) * rad, 0, Math.sin(ang) * rad);
            double d2 = v.distanceToSqr(t);
            var br = v.getBrain();
            br.setMemory(MemoryModuleType.LOOK_TARGET, new EntityTracker(p, true));
            if (d2 > 2.25) {
                if (!br.hasMemoryValue(MemoryModuleType.WALK_TARGET)) br.setMemory(MemoryModuleType.WALK_TARGET, new WalkTarget(t, 0.85f, 1));
                if (v.getNavigation().isDone()) v.getNavigation().moveTo(t.x, t.y, t.z, 0.85);
            } else if (sec && v.onGround() && lv.random.nextInt(6) == 0) v.getJumpControl().jump();
            if (sec) { // stuck detection: no progress for 6s -> put it into the crowd
                double[] pr = prog.computeIfAbsent(v.getUUID(), k -> new double[]{1e9, 0});
                if (d2 > 6.25 && d2 > pr[0] - 1.0) pr[1]++; else pr[1] = 0;
                pr[0] = d2;
                if (Cfg.d.teleportStuckVillagers && pr[1] >= 6) {
                    pr[1] = 0;
                    var box = v.getBoundingBox().move(t.x - v.getX(), p.getY() - v.getY(), t.z - v.getZ());
                    if (lv.noCollision(v, box)) { v.getNavigation().stop(); v.setPos(t.x, p.getY(), t.z); }
                }
            }
        }
        // Each line is said by one random villager, at most maxTimesPerLine times per wave; then the crowd goes quiet.
        if (sec && !crowd.isEmpty() && now - lastCrowdLine >= Cfg.d.secondsBetweenCrowdLines * 20L) {
            String text = pickLimited(lv, "recognize");
            if (text != null) {
                lastCrowdLine = now;
                sayText(lv, crowd.get(lv.random.nextInt(crowd.size())), "recognize", text);
            }
        }
    }

    static String pickLimited(ServerLevel lv, String key) {
        List<String> pool = new ArrayList<>();
        for (String l : Talk.lines(key)) if (used.getOrDefault(key + "|" + l, 0) < Cfg.d.maxTimesPerLine) pool.add(l);
        if (pool.isEmpty()) return null;
        String r = pool.get(lv.random.nextInt(pool.size()));
        used.merge(key + "|" + r, 1, Integer::sum);
        return r;
    }

    static String key(ItemStack s) {
        if (s.isEmpty()) return "trade";
        Item i = s.getItem();
        if (i == NoPcNoTrade.TOASTER) return "toaster";
        if (i == Items.EMERALD) return "emerald";
        if (i == Items.FURNACE) return "furnace";
        if (i == Items.DIRT) return "dirt";
        if (i == Items.COAL) return "coal";
        if (i == Items.REDSTONE) return "redstone";
        if (i == Items.IRON_INGOT) return "iron_ingot";
        if (i == Items.DIAMOND) return "diamond";
        return "trade";
    }

    /** Intercepts right-clicks on villagers by the target only. Items are never consumed. */
    public static InteractionResult interact(net.minecraft.world.entity.player.Player pl, net.minecraft.world.level.Level w, InteractionHand hand, net.minecraft.world.entity.Entity target) {
        if (w.isClientSide() || !(target instanceof Villager v) || !(pl instanceof ServerPlayer p) || !isTarget(p)) return InteractionResult.PASS;
        if (hand != InteractionHand.MAIN_HAND || !(p.level() instanceof ServerLevel lv)) return InteractionResult.SUCCESS_SERVER;
        long now = lv.getGameTime();
        long[] st = strike.computeIfAbsent(p.getUUID(), k -> new long[]{0, 0});
        if (now - st[1] > 1200) { st[0] = 0; used.keySet().removeIf(k -> k.startsWith("chant|")); }
        if (now - st[1] < 8) return InteractionResult.SUCCESS_SERVER; // anti-spam
        st[0]++; st[1] = now;
        refuse(lv, v);
        say(lv, v, key(p.getMainHandItem()));
        int chanters = (int) Math.min(2, (st[0] - 1) / 2); // protest escalates, but chants are rate-limited
        if (chanters > 0) {
            List<Villager> near = lv.getEntitiesOfClass(Villager.class, v.getBoundingBox().inflate(16), o -> o != v && o.isAlive());
            Collections.shuffle(near);
            for (int i = 0; i < Math.min(chanters, near.size()); i++) {
                Villager c = near.get(i);
                refuse(lv, c);
                c.getBrain().setMemory(MemoryModuleType.LOOK_TARGET, new EntityTracker(p, true));
                String ct = pickLimited(lv, "chant");
                if (ct == null) break;
                sayText(lv, c, "chant", ct);
            }
        }
        return InteractionResult.SUCCESS_SERVER;
    }

    static void refuse(ServerLevel lv, Villager v) {
        v.setUnhappyCounter(40); // vanilla head-shake animation
        lv.playSound(null, v.blockPosition(), SoundEvents.VILLAGER_NO, SoundSource.NEUTRAL, 1f, 0.9f + lv.random.nextFloat() * 0.2f);
    }

    static void say(ServerLevel lv, Villager v, String key) { sayText(lv, v, key, Talk.pick(key, lv.random)); }

    static void sayText(ServerLevel lv, Villager v, String key, String text) {
        int n = Math.floorMod(v.getUUID().hashCode(), 100);
        MutableComponent c = Component.literal("[Villager #" + n + "]: ").withStyle(COLORS[n % COLORS.length], ChatFormatting.BOLD)
                .append(Component.literal(text).withStyle(ChatFormatting.WHITE));
        for (ServerPlayer sp : lv.players()) if (sp.distanceToSqr(v) < 1024) sp.sendSystemMessage(c);
        SoundEvent vo = Voices.find(key, text);
        if (vo != null) lv.playSound(null, v.blockPosition(), vo, SoundSource.VOICE, 2f, 1f);
        else lv.playSound(null, v.blockPosition(), SoundEvents.VILLAGER_AMBIENT, SoundSource.NEUTRAL, 1f, 0.8f + lv.random.nextFloat() * 0.4f);
    }
}
