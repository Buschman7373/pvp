package dev.duelanalyzer;

import dev.duelanalyzer.ui.AnalysisScreen;
import net.fabricmc.fabric.api.client.message.v1.ClientReceiveMessageEvents;
import net.fabricmc.fabric.api.event.player.AttackEntityCallback;
import net.fabricmc.fabric.api.event.player.UseItemCallback;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.DeathScreen;
import net.minecraft.network.chat.Component;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.phys.Vec3;

/** Silent background recording of duels. Starts on the first hit against a player, ends on a death, a kill or a timeout. */
public final class Recorder {
    private static DuelRecord cur;
    private static Player opp;
    private static Player tracked;
    private static int tick, startTick, lastCombat, combo, readyTick;
    private static int lastPearl = -99, lastGap = -99, lastPot = -99;
    private static float lastHp = 20f, oppHp = 20f;
    private static boolean prevSwing;
    private static ModeDetector.Guess startGuess;
    public static DuelRecord pending;

    public static void init() {
        AttackEntityCallback.EVENT.register((player, level, hand, entity, hit) -> {
            Minecraft mc = Minecraft.getInstance();
            if (player == mc.player && entity instanceof Player target && target != mc.player) onAttack(mc, target);
            return InteractionResult.PASS;
        });
        UseItemCallback.EVENT.register((player, level, hand) -> {
            Minecraft mc = Minecraft.getInstance();
            if (player == mc.player) onUse(player, player.getItemInHand(hand));
            return InteractionResult.PASS;
        });
        ClientReceiveMessageEvents.GAME.register((message, overlay) -> {
            String s = message.getString();
            if (ModeDetector.fromText(s) != null) ModeDetector.lastChatClue = s;
        });
    }

    public static boolean inDuel() { return cur != null; }

    private static void log(String s) {
        if (cur != null) cur.events.add(new DuelRecord.Ev(tick - startTick, s));
    }

    private static void start(Minecraft mc, Player target) {
        cur = new DuelRecord();
        cur.time = System.currentTimeMillis();
        cur.id = String.valueOf(cur.time);
        cur.opponent = target.getName().getString();
        cur.server = ModeDetector.serverKey(mc);
        cur.startDist = mc.player.distanceTo(target);
        opp = target;
        tracked = mc.player;
        startTick = tick;
        lastCombat = tick;
        combo = 0;
        lastHp = mc.player.getHealth();
        oppHp = target.getHealth();
        startGuess = ModeDetector.detect(mc, mc.player);
        log("Fight started");
    }

    private static void onAttack(Minecraft mc, Player target) {
        if (cur == null) start(mc, target);
        if (target != opp) return;
        Player me = mc.player;
        cur.hits++;
        lastCombat = tick;
        combo++;
        if (combo > cur.bestCombo) cur.bestCombo = combo;
        if (combo == 4 || combo == 6 || combo == 8) log(combo + "-hit combo");
        if (me.getAttackStrengthScale(0.5f) < 0.85f) cur.weakHits++;
        Vec3 v = me.getDeltaMovement();
        if (v.y < 0 && !me.onGround()) cur.critHits++;
        if (me.isSprinting()) cur.sprintHits++;
        if (target.isBlocking()) {
            cur.blockedByOpp++;
            combo = 0;
            log("Hit blocked by shield");
        }
    }

    private static void onUse(Player p, ItemStack st) {
        if (cur == null || st.isEmpty()) return;
        String n = ModeDetector.id(st);
        if (n.equals("ender_pearl") && tick - lastPearl > 15) {
            lastPearl = tick;
            cur.pearls++;
            double dist = opp == null ? 0 : p.distanceTo(opp);
            cur.pearlDistSum += dist;
            log(String.format("Pearl thrown (%.0f blocks away)", dist));
        } else if ((n.equals("golden_apple") || n.equals("enchanted_golden_apple")) && tick - lastGap > 20) {
            lastGap = tick;
            cur.gapples++;
            cur.gappleHp.add(p.getHealth());
            log(String.format("Gapple at %.1f HP", p.getHealth()));
        } else if (n.contains("potion") && tick - lastPot > 8) {
            lastPot = tick;
            cur.potions++;
            log("Potion used");
        }
    }

    public static void tick(Minecraft mc) {
        tick++;
        Player me = mc.player;
        if (me == null) {
            cur = null;
            return;
        }
        if (pending != null && tick >= readyTick && mc.screen == null && me.getHealth() > 0) {
            DuelRecord d = pending;
            pending = null;
            mc.setScreen(new AnalysisScreen(d, null));
        }
        if (cur == null) return;

        if (me != tracked || mc.screen instanceof DeathScreen) {
            end(mc, mc.screen instanceof DeathScreen || lastHp < oppHp ? "LOSE" : "WIN", 40);
            return;
        }

        float hp = me.getHealth();
        if (hp < lastHp) {
            float lost = lastHp - hp;
            cur.damageTaken += lost;
            combo = 0;
            lastCombat = tick;
            if (lost >= 4f) log(String.format("Took %.0f damage", lost));
        }
        lastHp = hp;
        cur.lowestHp = Math.min(cur.lowestHp, hp);

        boolean sw = me.swinging;
        if (sw && !prevSwing) cur.swings++;
        prevSwing = sw;

        if (opp != null) oppHp = opp.getHealth() > 0 ? opp.getHealth() : oppHp;

        if (hp <= 0 || me.isDeadOrDying()) {
            end(mc, "LOSE", 40);
        } else if (opp != null && (opp.getHealth() <= 0 || opp.isDeadOrDying())) {
            end(mc, "WIN", 30);
        } else if (opp != null && opp.isRemoved()) {
            end(mc, oppHp <= 6f && tick - lastCombat < 100 ? "WIN" : (lastHp >= oppHp ? "WIN" : "LOSE"), 30);
        } else if (tick - lastCombat > 160) {
            end(mc, lastHp >= oppHp ? "WIN" : "LOSE", 0);
        }

        if (Store.cfg.overlay && cur != null && tick % 10 == 0) {
            me.displayClientMessage(Component.literal(String.format("Duel: %d hits | %d swings | %.0f dmg taken",
                    cur.hits, cur.swings, cur.damageTaken)), true);
        }
    }

    private static void end(Minecraft mc, String result, int delay) {
        DuelRecord d = cur;
        cur = null;
        d.result = result;
        d.ticks = Math.max(1, Math.min(tick, lastCombat + 20) - startTick);
        d.oppEndHp = oppHp;
        ModeDetector.Guess endGuess = mc.player == null ? startGuess : ModeDetector.detect(mc, mc.player);
        ModeDetector.Guess g = endGuess.conf() > startGuess.conf() ? endGuess : startGuess;
        d.mode = g.mode().name();
        d.modeConf = g.conf();
        d.modeConfirmed = true;
        if (delay == 0 && d.hits < 3) return; // timeout without a real fight
        Store.add(d);
        pending = d;
        readyTick = tick + delay;
    }
}
