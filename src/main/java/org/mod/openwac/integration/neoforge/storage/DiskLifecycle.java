package org.mod.openwac.integration.neoforge.storage;

import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.Container;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.storage.LevelResource;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.event.entity.EntityLeaveLevelEvent;
import net.neoforged.neoforge.event.tick.ServerTickEvent;
import org.mod.openwac.integration.neoforge.item.ComponentItem;
import org.mod.openwac.integration.neoforge.item.DiskDriveItem;
import org.mod.openwac.service.FileStorageRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import java.io.UncheckedIOException;
import java.util.*;
import java.util.concurrent.CompletionStage;

/** Server-thread disposal candidates; transfers settle before files are removed. */
public final class DiskLifecycle {
    private static final Logger LOGGER = LoggerFactory.getLogger(DiskLifecycle.class);
    private record Origin(ServerLevel level, Vec3 position) {}
    private static final Map<MinecraftServer, Map<UUID, Origin>> PENDING = new IdentityHashMap<>();
    private DiskLifecycle() {}

    public static Optional<UUID> diskId(ItemStack stack) {
        return !stack.isEmpty() && stack.getItem() instanceof DiskDriveItem
                ? ComponentItem.existingDeviceId(stack) : Optional.empty();
    }
    public static Set<UUID> disks(Container container) {
        Set<UUID> ids = new HashSet<>();
        for (int slot = 0; slot < container.getContainerSize(); slot++) diskId(container.getItem(slot)).ifPresent(ids::add);
        return ids;
    }
    public static void removed(ServerPlayer player, UUID id) {
        queue(player.level(), player.position(), id);
    }
    public static void ejected(ServerLevel level, UUID id, CompletionStage<Void> storageIdle) {
        // Capture only the filesystem repository; completion may run on a computer worker.
        var repository = new FileStorageRepository(level.getServer().getWorldPath(LevelResource.ROOT).resolve("openwac/disks"));
        storageIdle.thenRun(() -> {
            try { repository.deleteIfZero(id, DiskDriveItem.BLOCK_SIZE, DiskDriveItem.BLOCK_COUNT); }
            catch (UncheckedIOException failure) { LOGGER.error("Could not reclaim blank backing file for ejected disk {}", id, failure); }
        });
    }
    private static void queue(ServerLevel level, Vec3 position, UUID id) {
        PENDING.computeIfAbsent(level.getServer(), ignored -> new HashMap<>()).put(id, new Origin(level, position));
    }
    public static void entityLeft(EntityLeaveLevelEvent event) {
        if (!(event.getLevel() instanceof ServerLevel level) || !(event.getEntity() instanceof ItemEntity item)) return;
        var reason = item.getRemovalReason();
        if (reason != null && reason.shouldDestroy())
            diskId(item.getItem()).ifPresent(id -> queue(level, item.position(), id));
    }
    public static void tick(ServerTickEvent.Post event) { drain(event.getServer()); }
    private static void drain(MinecraftServer server) {
        Map<UUID, Origin> candidates = PENDING.remove(server);
        if (candidates == null) return;
        Set<UUID> retained = new HashSet<>();
        for (var player : server.getPlayerList().getPlayers()) {
            retained.addAll(disks(player.getInventory()));
            for (var slot : player.containerMenu.slots) diskId(slot.getItem()).ifPresent(retained::add);
            for (var slot : player.inventoryMenu.slots) diskId(slot.getItem()).ifPresent(retained::add);
            diskId(player.containerMenu.getCarried()).ifPresent(retained::add);
        }
        var repository = new FileStorageRepository(server.getWorldPath(LevelResource.ROOT).resolve("openwac/disks"));
        candidates.forEach((id, origin) -> {
            if (retained.contains(id)) return;
            // Creative slot packets also represent dropping and rearranging items.
            for (var item : origin.level().getEntitiesOfClass(ItemEntity.class,
                    AABB.ofSize(origin.position(), 32, 32, 32), item -> !item.isRemoved())) {
                if (diskId(item.getItem()).filter(id::equals).isPresent()) return;
            }
            try { repository.delete(id); }
            catch (UncheckedIOException failure) { LOGGER.error("Could not delete backing file for destroyed disk {}", id, failure); }
        });
    }
    public static void stop(MinecraftServer server) {
        drain(server);
        PENDING.remove(server);
    }
}
