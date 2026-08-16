package com.dirges.fxchat.bukkit.hook;

import net.momirealms.craftengine.bukkit.api.CraftEngineBlocks;
import net.momirealms.craftengine.bukkit.block.behavior.SimpleStorageBlockBehavior;
import net.momirealms.craftengine.bukkit.block.entity.DrawerBlockEntityController;
import net.momirealms.craftengine.bukkit.block.entity.SimpleStorageBlockEntityController;
import net.momirealms.craftengine.bukkit.world.BukkitWorldManager;
import net.momirealms.craftengine.core.block.ImmutableBlockState;
import net.momirealms.craftengine.core.block.entity.BlockEntity;
import net.momirealms.craftengine.core.world.BlockPos;
import net.momirealms.craftengine.core.world.CEWorld;
import org.bukkit.block.Block;
import org.bukkit.inventory.Inventory;

/** Reads inventories owned by CraftEngine custom container blocks. */
public final class CraftEngineHook {
    public boolean isCustomBlock(Block block) {
        try {
            return CraftEngineBlocks.isCustomBlock(block);
        } catch (RuntimeException | LinkageError ignored) {
            return false;
        }
    }

    public String containerTitle(Block block) {
        try {
            ImmutableBlockState state = CraftEngineBlocks.getCustomBlockState(block);
            if (state == null || state.isEmpty()) {
                return null;
            }
            SimpleStorageBlockBehavior storage = state.behavior() == null
                    ? null : state.behavior().getFirst(SimpleStorageBlockBehavior.class);
            if (storage == null && state.owner() != null && state.owner().value() != null) {
                ImmutableBlockState defaultState = state.owner().value().defaultState();
                if (defaultState != null && defaultState.behavior() != null) {
                    storage = defaultState.behavior().getFirst(SimpleStorageBlockBehavior.class);
                }
            }
            if (storage == null) {
                return null;
            }
            String title = storage.containerTitle;
            return title == null || title.isBlank() ? null : title;
        } catch (RuntimeException | LinkageError ignored) {
            return null;
        }
    }

    public Inventory inventory(Block block) {
        try {
            if (!CraftEngineBlocks.isCustomBlock(block)) {
                return null;
            }
            CEWorld world = BukkitWorldManager.instance().getWorld(block.getWorld().getUID());
            if (world == null) {
                return null;
            }
            BlockEntity blockEntity = world.getBlockEntityAtIfLoaded(
                    new BlockPos(block.getX(), block.getY(), block.getZ()));
            if (blockEntity == null || !blockEntity.isValid()) {
                return null;
            }
            SimpleStorageBlockEntityController storage = blockEntity.controller.get(
                    SimpleStorageBlockEntityController.class, 0);
            if (storage != null) {
                return storage.inventory();
            }
            DrawerBlockEntityController drawer = blockEntity.controller.get(
                    DrawerBlockEntityController.class, 0);
            return drawer == null ? null : drawer.getInventory();
        } catch (RuntimeException | LinkageError ignored) {
            return null;
        }
    }
}
