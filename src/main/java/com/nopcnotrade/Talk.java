package com.nopcnotrade;

import net.minecraft.util.RandomSource;
import java.util.*;

public class Talk {
    public static final Map<String, List<String>> DEFAULTS = new LinkedHashMap<>();
    static void d(String k, String... v) { DEFAULTS.put(k, List.of(v)); }
    static {
        d("shout", "YO! THAT'S THE PC GUY!", "GUYS! HE'S HERE!", "THE PC GUY HAS ARRIVED!", "GET HIM! WE NEED A PC!", "BRO! WHERE'S OUR COMPUTER?");
        d("recognize", "BRO! IT'S THE PC GUY!", "WHERE'S MY GAMING PC?", "I'VE BEEN WAITING FOR MONTHS!", "DON'T WALK AWAY!", "WE NEED RTX 5090!", "THIS GUY OWES US A COMPUTER!", "I'VE BEEN WAITING FOR 6 MONTHS!");
        d("trade", "WILL YOU EVEN WANT TO TRADE WITH ME? GIVE ME A PC FIRST!", "YOU WANNA TRADE WITH ME? GET ME A PC FIRST!", "NO PC, NO TRADE!", "I'M NOT TRADING UNTIL I GET MY GAMING PC!", "BRO, WE'VE ALREADY DISCUSSED THIS!", "STOP SHOWING ME EMERALDS!", "I'M ON STRIKE!", "MY TRADING LICENSE HAS EXPIRED UNTIL I GET A PC!");
        d("chant", "NO PC! NO TRADE!", "WE WANT COMPUTERS!", "RTX! RTX! RTX!", "GIVE US GAMING PCS!");
        d("emerald", "WHAT AM I SUPPOSED TO DO WITH THESE?", "BRO, I ASKED FOR A PC!", "CAN I RUN SHADERS ON EMERALDS?", "KEEP YOUR EMERALDS!", "I CAN'T PLAY CYBERPUNK ON THIS!", "DO I LOOK LIKE I WANT EMERALDS?", "EMERALDS DON'T HAVE RTX!");
        d("furnace", "BRO GAVE ME AN OVEN", "WHAT AM I SUPPOSED TO DO WITH THIS?", "I ASKED FOR A COMPUTER!", "IS THIS YOUR IDEA OF A GAMING PC?", "I'M NOT COOKING, I'M GAMING!", "THIS IS NOT WHAT I MEANT!");
        d("toaster", "BRO, THIS IS A TOASTER!", "I SAID PC, NOT TOASTER!", "CAN THIS THING RUN MINECRAFT?", "WHERE'S THE GRAPHICS CARD?", "THIS IS LITERALLY A BREAD MACHINE!", "DO YOU THINK I'M STUPID?");
        d("dirt", "BRO THINKS DIRT IS A PC.", "WHAT AM I SUPPOSED TO DO WITH THIS?");
        d("coal", "IS THIS THE POWER SUPPLY?", "I NEED A GRAPHICS CARD, NOT COAL!");
        d("redstone", "ARE YOU TRYING TO BUILD A MOTHERBOARD?", "FINALLY, SOME TECHNOLOGY... WAIT, NO.");
        d("iron_ingot", "IS THIS THE CASE?", "WHERE ARE THE OTHER COMPONENTS?");
        d("diamond", "OKAY, NOW WE'RE GETTING SOMEWHERE.", "WAIT... STILL NO PC?", "I CAN'T INSTALL WINDOWS ON A DIAMOND!");
    }

    public static String pick(String key, RandomSource rnd) {
        List<String> l = Cfg.d.dialogue.getOrDefault(key, List.of());
        if (l.isEmpty()) l = DEFAULTS.getOrDefault(key, List.of("NO PC! NO TRADE!"));
        return l.get(rnd.nextInt(l.size()));
    }
}
