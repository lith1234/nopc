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
    static final Map<UUID, long[]> strike = new HashMap<>(); // {refusals, lastTick}
    static final ChatFormatting[] COLORS = {ChatFormatting.GREEN, ChatFormatting.AQUA, ChatFormatting.YELLOW, ChatFormatting.GOLD,
            ChatFormatting.LIGHT_PURPLE, ChatFormatting.RED, ChatFormatting.BLUE, ChatFormatting.DARK_AQUA};

    static boolean isTarget(ServerPlayer p) {
        return p.getName().getString().equalsIgnoreCase(Cfg.d.targetUsername.trim());
    }

    /** Runs once per second, and only does work when the target player is online. */
    public static void tick(MinecraftServer s) {
        if (s.getTickCount() % 20 != 0) return;
        ServerPlayer p = null;
        for (ServerPlayer sp : s.getPlayerList().getPlayers())
            if (isTarget(sp)) { p = sp; break; }
        if (p == null || p.isSpectator() || !(p.level() instanceof ServerLevel lv)) {
            if (!active.isEmpty()) active.clear();
            return;
        }
        long now = lv.getGameTime();
        for (var it = active.entrySet().iterator(); it.hasNext(); ) {
            var en = it.next();
            if (en.getValue() <= now) { calm.put(en.getKey(), now + 600); it.remove(); } // 30s break, never stuck
        }
        calm.values().removeIf(t -> t <= now);
        nextSpeak.values().removeIf(t -> t <= now);

        List<Villager> vs = lv.getEntitiesOfClass(Villager.class, p.getBoundingBox().inflate(Cfg.d.radius),
                v -> v.isAlive() && !v.isSleeping());
        if (vs.isEmpty()) return;
        final ServerPlayer pl = p;
        vs.sort(Comparator.comparingDouble(v -> v.distanceToSqr(pl)));

        // Gradual recruitment: max 3 new villagers per second.
        boolean fresh = active.isEmpty();
        int add = 0;
        for (Villager v : vs) {
            if (active.size() >= Cfg.d.maxCrowd || add >= 3) break;
            if (active.containsKey(v.getUUID()) || calm.containsKey(v.getUUID())) continue;
            active.put(v.getUUID(), now + Cfg.d.crowdSeconds * 20L);
            if (fresh && add == 0) say(lv, v, "shout");
            add++;
        }

        List<Villager> crowd = new ArrayList<>();
        for (Villager v : vs) if (active.containsKey(v.getUUID())) crowd.add(v);
        for (Villager v : crowd) {
            int h = v.getUUID().hashCode();
            double ang = ((h & 0xFFFF) / 65536.0) * Math.PI * 2, rad = 2.2 + ((h >>> 16) & 3) * 0.8; // stable ring slot
            Vec3 t = p.position().add(Math.cos(ang) * rad, 0, Math.sin(ang) * rad);
            var br = v.getBrain();
            br.setMemory(MemoryModuleType.LOOK_TARGET, new EntityTracker(p, true));
            if (v.distanceToSqr(t) > 2.25) br.setMemory(MemoryModuleType.WALK_TARGET, new WalkTarget(t, 0.7f, 1));
            else if (v.onGround() && lv.random.nextInt(6) == 0) v.getJumpControl().jump();
        }
        // One crowd line per second at most, per-villager randomized cooldown.
        if (!crowd.isEmpty()) {
            Villager v = crowd.get(lv.random.nextInt(crowd.size()));
            if (!nextSpeak.containsKey(v.getUUID())) {
                int cd = Cfg.d.messageCooldownSeconds * 20;
                nextSpeak.put(v.getUUID(), now + cd + lv.random.nextInt(cd + 1));
                say(lv, v, "recognize");
            }
        }
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

    /** Intercepts right-clicks on villagers by the target only. Items are never consumed, so nothing can be lost or duplicated. */
    public static InteractionResult interact(net.minecraft.world.entity.player.Player pl, net.minecraft.world.level.Level w, InteractionHand hand, net.minecraft.world.entity.Entity target) {
        if (w.isClientSide() || !(target instanceof Villager v) || !(pl instanceof ServerPlayer p) || !isTarget(p)) return InteractionResult.PASS;
        if (hand != InteractionHand.MAIN_HAND || !(p.level() instanceof ServerLevel lv)) return InteractionResult.SUCCESS_SERVER;
        long now = lv.getGameTime();
        long[] st = strike.computeIfAbsent(p.getUUID(), k -> new long[]{0, 0});
        if (now - st[1] > 1200) st[0] = 0;
        if (now - st[1] < 8) return InteractionResult.SUCCESS_SERVER; // anti-spam
        st[0]++; st[1] = now;
        refuse(lv, v);
        say(lv, v, key(p.getMainHandItem()));
        int chanters = (int) Math.min(4, (st[0] - 1) / 2); // protest escalates with repeated attempts
        if (chanters > 0) {
            List<Villager> near = lv.getEntitiesOfClass(Villager.class, v.getBoundingBox().inflate(16), o -> o != v && o.isAlive());
            Collections.shuffle(near);
            for (int i = 0; i < Math.min(chanters, near.size()); i++) {
                Villager c = near.get(i);
                refuse(lv, c);
                c.getBrain().setMemory(MemoryModuleType.LOOK_TARGET, new EntityTracker(p, true));
                say(lv, c, "chant");
            }
        }
        return InteractionResult.SUCCESS_SERVER;
    }

    static void refuse(ServerLevel lv, Villager v) {
        v.setUnhappyCounter(40); // vanilla head-shake animation
        lv.playSound(null, v.blockPosition(), SoundEvents.VILLAGER_NO, SoundSource.NEUTRAL, 1f, 0.9f + lv.random.nextFloat() * 0.2f);
    }

    static void say(ServerLevel lv, Villager v, String key) {
        String text = Talk.pick(key, lv.random);
        int n = Math.floorMod(v.getUUID().hashCode(), 100);
        MutableComponent c = Component.literal("[Villager #" + n + "]: ").withStyle(COLORS[n % COLORS.length], ChatFormatting.BOLD)
                .append(Component.literal(text).withStyle(ChatFormatting.WHITE));
        for (ServerPlayer sp : lv.players()) if (sp.distanceToSqr(v) < 1024) sp.sendSystemMessage(c);
        SoundEvent vo = Voices.find(key, text);
        if (vo != null) lv.playSound(null, v.blockPosition(), vo, SoundSource.VOICE, 2f, 1f);
        else lv.playSound(null, v.blockPosition(), SoundEvents.VILLAGER_AMBIENT, SoundSource.NEUTRAL, 1f, 0.8f + lv.random.nextFloat() * 0.4f);
    }
}
