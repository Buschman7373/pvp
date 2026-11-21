package dev.duelanalyzer;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Random;

/** Offline rule-based tips. Real numbers from the fight, several wordings per rule, and a no-repeat memory. */
public final class Tips {
    private static final Random RND = new Random();

    private record Cand(String id, int prio, String[] v) {}

    private static String f(String fmt, Object... a) {
        return String.format(Locale.ROOT, fmt, a);
    }

    public static List<String> make(DuelRecord d) {
        List<Cand> c = new ArrayList<>();
        int hits = d.hits, swings = d.swings;
        int pct = (int) Math.round(d.hitRate() * 100);
        int weakPct = hits == 0 ? 0 : (int) Math.round(d.weakHits * 100.0 / hits);
        int sprintPct = hits == 0 ? 0 : (int) Math.round(d.sprintHits * 100.0 / hits);
        int critPct = hits == 0 ? 0 : (int) Math.round(d.critHits * 100.0 / hits);
        int misses = Math.max(0, swings - hits);

        if (d.won()) {
            c.add(new Cand("win_combo", 60, new String[]{
                    f("Great fight! Your best moment was the %d-hit combo.", d.bestCombo),
                    f("Clean win. Your %d-hit combo decided it, repeat that rhythm.", d.bestCombo),
                    f("You won in %s. Keep that tempo next time.", d.duration())}));
        }
        if (d.weakHits >= 2) {
            c.add(new Cand("cooldown", 95, new String[]{
                    f("%d of your %d hits landed before your cooldown was full. Wait for the full charge.", d.weakHits, hits),
                    f("%d%% of your hits were weak swings. Count to the full attack bar before you hit.", weakPct),
                    "Wait for the full attack cooldown before swinging, a charged hit does far more damage.",
                    f("Your weak swings (%d) cost you damage. Slow down by a fraction and hit harder.", d.weakHits)}));
        }
        if (swings >= 8 && d.hitRate() < 0.42) {
            c.add(new Cand("aim", 90, new String[]{
                    f("You missed %d of %d swings (%d%% hit rate). Close the distance before you swing.", misses, swings, pct),
                    f("Only %d%% of your swings connected. Swing when they are in reach, not before.", pct),
                    f("%d misses is a lot. Try to strafe into range, then swing once.", misses)}));
        }
        for (float hp : d.gappleHp) {
            if (hp <= 4f) {
                c.add(new Cand("gapple_late", 92, new String[]{
                        f("You ate your gapple at %.1f HP. Eat around 8 HP, not %.0f HP.", hp, hp),
                        "Eating at that low health is a gamble. Start your gapple around 8 HP.",
                        f("Gapple at %.1f HP nearly cost you the fight. Heal earlier and from a safe spot.", hp)}));
                break;
            }
        }
        if (d.pearls > 0 && d.pearlDistSum / d.pearls > 10) {
            c.add(new Cand("pearl_late", 80, new String[]{
                    f("You threw your pearl at %.0f blocks. Throw it sooner when they gain distance.", d.pearlDistSum / d.pearls),
                    "Your pearl came late. Use it as soon as the gap opens, not when it is already big."}));
        }
        if (d.blockedByOpp >= 2) {
            c.add(new Cand("shield", 85, new String[]{
                    f("They blocked %d of your hits with a shield. Wait a moment after a block, then swing again.", d.blockedByOpp),
                    f("%d shield blocks. Switch to an axe or hit when their shield is down.", d.blockedByOpp),
                    "Don't keep swinging into a raised shield. Back off, bait the lower, then hit."}));
        }
        if (d.damageTaken >= 14f) {
            c.add(new Cand("damage", 88, new String[]{
                    f("You took %.0f damage. Strafe sideways while you hit, don't stand still.", d.damageTaken),
                    f("%.0f HP lost is a lot. Break line of sight after each trade.", d.damageTaken),
                    "Try to hit and step out of reach instead of trading toe to toe."}));
        }
        if (hits >= 6 && d.bestCombo <= 2) {
            c.add(new Cand("combo", 70, new String[]{
                    f("Your longest combo was %d. Keep your target in range to chain hits.", d.bestCombo),
                    "You rarely chained hits. Stay on them after the first hit and keep sprinting."}));
        }
        if (hits >= 6 && sprintPct < 40) {
            c.add(new Cand("sprint", 65, new String[]{
                    f("Only %d%% of your hits were sprint-hits. Keep sprinting between swings.", sprintPct),
                    "Reset your sprint before each hit. Sprint-hits add knockback and control."}));
        }
        if (hits >= 8 && critPct < 15) {
            c.add(new Cand("crit", 55, new String[]{
                    f("Only %d%% crits. Swing while falling after a small jump for extra damage.", critPct),
                    "Time a jump with your swing now and then, a crit hits much harder."}));
        }
        if (d.startDist > 0 && d.startDist < 2.5) {
            c.add(new Cand("close", 50, new String[]{
                    f("You started the fight %.1f blocks away. Open with more spacing so you choose the first hit.", d.startDist)}));
        } else if (d.startDist > 8) {
            c.add(new Cand("far", 50, new String[]{
                    f("You began %.0f blocks away. Close the gap faster so they don't set the pace.", d.startDist)}));
        }
        if (d.lost() && d.oppEndHp > 0 && d.oppEndHp <= 4f) {
            c.add(new Cand("close_loss", 86, new String[]{
                    f("They finished with only %.1f HP. One more clean hit would have won it.", d.oppEndHp),
                    "That was close. A faster follow-up after your last trade decides fights like this."}));
        }
        if (d.lost() && d.pearls == 0 && d.ticks > 600) {
            c.add(new Cand("no_pearl", 60, new String[]{
                    "You didn't use a pearl in a long fight. Keep one ready to reset or escape."}));
        }
        if (d.lost() && d.potions == 0 && d.modeEnum().name().contains("POT")) {
            c.add(new Cand("no_pot", 82, new String[]{
                    "No potions used in a pot fight. Splash heals while they are on cooldown."}));
        }
        c.add(new Cand("mode", 20, new String[]{
                d.modeEnum().label + ": " + d.modeEnum().tip,
                "Mode focus for " + d.modeEnum().label + ": " + d.modeEnum().tip}));
        c.add(new Cand("generic", 10, new String[]{
                "Take a short breath between fights and review the timeline before you queue again.",
                "Watch their first two hits. Most players repeat the same opener.",
                "Stay calm at low HP. A clear plan beats a panic swing."}));

        List<Cand> sorted = new ArrayList<>(c);
        sorted.sort((a, b) -> Integer.compare(b.prio + RND.nextInt(8), a.prio + RND.nextInt(8)));

        List<String> out = new ArrayList<>();
        List<String> hist = Store.cfg.tipHistory;
        for (Cand cand : sorted) {
            if (out.size() >= 3) break;
            List<Integer> free = new ArrayList<>();
            for (int i = 0; i < cand.v.length; i++) {
                if (!hist.contains(cand.id + "#" + i)) free.add(i);
            }
            if (free.isEmpty()) {
                for (int i = 0; i < cand.v.length; i++) hist.remove(cand.id + "#" + i);
                for (int i = 0; i < cand.v.length; i++) free.add(i);
            }
            int pick = free.get(RND.nextInt(free.size()));
            hist.add(cand.id + "#" + pick);
            out.add(cand.v[pick]);
        }
        while (hist.size() > 400) hist.remove(0);
        Store.saveCfg();
        return out;
    }
}
