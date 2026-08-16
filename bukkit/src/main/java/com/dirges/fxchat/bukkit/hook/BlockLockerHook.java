package com.dirges.fxchat.bukkit.hook;

import nl.rutgerkok.blocklocker.BlockLockerAPIv2;
import nl.rutgerkok.blocklocker.SignParser;
import org.bukkit.event.block.SignChangeEvent;

/** Identifies BlockLocker protection signs before their contents are persisted. */
public final class BlockLockerHook {
    private final SignParser signParser;

    private BlockLockerHook(SignParser signParser) {
        this.signParser = signParser;
    }

    public static BlockLockerHook create() {
        return new BlockLockerHook(BlockLockerAPIv2.getPlugin().getSignParser());
    }

    public boolean isProtectionSign(SignChangeEvent event) {
        return signParser.getSignType(event).isPresent();
    }
}
