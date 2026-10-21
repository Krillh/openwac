package org.mod.openwac.integration.neoforge.network;

import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.level.storage.LevelResource;
import net.neoforged.neoforge.network.PacketDistributor;
import org.mod.openwac.integration.neoforge.item.*;
import org.mod.openwac.service.ImportedFileStore;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.file.*;
import java.security.*;
import java.util.*;
import java.util.concurrent.*;

/** Main-thread authorization and acknowledgements; bounded worker handles all server filesystem I/O. */
public final class ImporterUploads implements AutoCloseable {
    private static final Map<MinecraftServer, ImporterUploads> SERVERS = new IdentityHashMap<>();
    private static final long IDLE_TIMEOUT = TimeUnit.MINUTES.toNanos(2);
    private final MinecraftServer server;
    private final ImportedFileStore store;
    private final Map<UUID, Session> sessions = new HashMap<>();
    private final Map<UUID, Long> lastOpen = new HashMap<>();
    private final ThreadPoolExecutor worker = new ThreadPoolExecutor(1, 1, 0, TimeUnit.SECONDS,
            new ArrayBlockingQueue<>(32), r -> Thread.ofPlatform().daemon().name("openwac-import-upload").unstarted(r));
    private boolean closed;

    private static final class Session {
        final UUID token = UUID.randomUUID(), device;
        final ServerPlayer player;
        long touched = System.nanoTime(), size, offset;
        String filename;
        boolean pending, begun;
        volatile boolean cancelled;
        // Temporary path and digest belong to the single worker.
        Path temporary;
        MessageDigest digest;
        Session(ServerPlayer player, UUID device) { this.player = player; this.device = device; }
    }
    private ImporterUploads(MinecraftServer server) {
        this.server = server;
        store = new ImportedFileStore(server.getWorldPath(LevelResource.ROOT).resolve("openwac/imports"));
        worker.execute(() -> { try { store.cleanTemporaryFiles(); } catch (IOException ignored) {} });
    }
    public static ImporterUploads get(ServerPlayer player) {
        MinecraftServer server = Objects.requireNonNull(player.level().getServer());
        return SERVERS.computeIfAbsent(server, ImporterUploads::new);
    }
    public static void tick(MinecraftServer server) {
        ImporterUploads uploads = SERVERS.get(server);
        if (uploads != null) uploads.expire();
    }
    public static void stop(MinecraftServer server) {
        ImporterUploads uploads = SERVERS.remove(server);
        if (uploads != null) uploads.close();
    }
    private static boolean holds(ServerPlayer player, UUID device) {
        if (player.isSpectator() || player.hasDisconnected()) return false;
        for (InteractionHand hand : InteractionHand.values()) {
            var stack = player.getItemInHand(hand);
            if (stack.getItem() instanceof FileImporterItem && ComponentItem.deviceId(stack).equals(device)) return true;
        }
        return false;
    }
    public void open(ServerPlayer player, UUID device) {
        if (closed || !holds(player, device)) return;
        expire();
        long now = System.nanoTime();
        if (now - lastOpen.getOrDefault(player.getUUID(), 0L) < TimeUnit.SECONDS.toNanos(1)) return;
        lastOpen.put(player.getUUID(), now);
        Session previous = sessions.remove(player.getUUID());
        if (previous != null) discard(previous);
        if (sessions.size() >= 8) {
            player.sendSystemMessage(net.minecraft.network.chat.Component.literal("OpenWAC: all upload slots are busy. Try again shortly."));
            return;
        }
        Session session = new Session(player, device);
        sessions.put(player.getUUID(), session);
        PacketDistributor.sendToPlayer(player, new ImporterPayloads.Open(session.token, device));
    }
    private Session authorized(ServerPlayer player, UUID token) {
        Session s = sessions.get(player.getUUID());
        if (s == null || !s.token.equals(token)) return null;
        if (s.cancelled || System.nanoTime() - s.touched > IDLE_TIMEOUT || !holds(player, s.device)) {
            fail(s, "Upload expired or importer is no longer held. Close and reopen it.");
            return null;
        }
        s.touched = System.nanoTime();
        return s;
    }
    public void begin(ServerPlayer player, ImporterPayloads.Begin request) {
        Session s = authorized(player, request.token());
        if (s == null) return;
        if (s.pending || s.begun || request.size() < 0 || request.size() > ImportedFileStore.MAX_FILE_BYTES
                || !ImportedFileStore.validName(request.filename())) {
            fail(s, "Invalid upload. Use an ASCII filename and a file of at most 16 MiB."); return;
        }
        s.begun = true; s.filename = request.filename(); s.size = request.size();
        submit(s, () -> {
            s.temporary = store.createTemporary();
            try { s.digest = MessageDigest.getInstance("SHA-256"); }
            catch (NoSuchAlgorithmException impossible) { throw new IllegalStateException(impossible); }
            if (s.size == 0) publish(s);
        }, 0, s.size == 0);
    }
    public void chunk(ServerPlayer player, ImporterPayloads.Chunk request) {
        Session s = authorized(player, request.token());
        if (s == null) return;
        byte[] bytes = request.data();
        if (!s.begun || s.pending || request.offset() != s.offset || bytes.length == 0
                || bytes.length > ImporterPayloads.CHUNK_BYTES || bytes.length > s.size - s.offset) {
            fail(s, "Invalid or out-of-order upload chunk."); return;
        }
        long end = s.offset + bytes.length;
        submit(s, () -> {
            try (FileChannel channel = FileChannel.open(s.temporary, StandardOpenOption.WRITE)) {
                ByteBuffer data = ByteBuffer.wrap(bytes);
                while (data.hasRemaining()) {
                    if (s.cancelled) throw new IOException("Upload cancelled");
                    int written = channel.write(data, request.offset() + data.position());
                    if (written <= 0) throw new IOException("Incomplete upload write");
                }
            }
            s.digest.update(bytes);
            if (end == s.size) publish(s);
        }, end, end == s.size);
    }
    private void publish(Session s) throws IOException {
        if (s.cancelled) throw new IOException("Upload cancelled");
        store.publish(s.device, s.filename, s.size, HexFormat.of().formatHex(s.digest.digest()), s.temporary);
    }
    @FunctionalInterface private interface IOAction { void run() throws IOException; }
    private void submit(Session s, IOAction action, long nextOffset, boolean finished) {
        s.pending = true;
        try {
            worker.execute(() -> {
                try {
                    if (s.cancelled) return;
                    action.run();
                    server.execute(() -> {
                        if (closed || s.cancelled || sessions.get(s.player.getUUID()) != s) return;
                        s.pending = false; s.offset = nextOffset; s.touched = System.nanoTime();
                        PacketDistributor.sendToPlayer(s.player, new ImporterPayloads.Reply(s.token, nextOffset, finished, ""));
                        if (finished) { sessions.remove(s.player.getUUID()); discard(s); }
                    });
                } catch (IOException | RuntimeException failure) {
                    server.execute(() -> {
                        if (!closed && !s.cancelled) fail(s, "Upload failed: unreadable storage or the 256 MiB world limit was reached.");
                    });
                }
            });
        } catch (RejectedExecutionException busy) { fail(s, "Upload worker is busy. Close and reopen the importer."); }
    }
    public void cancel(ServerPlayer player, UUID token) {
        Session s = sessions.get(player.getUUID());
        if (s != null && s.token.equals(token)) { sessions.remove(player.getUUID()); discard(s); }
    }
    private void fail(Session s, String error) {
        sessions.remove(s.player.getUUID(), s);
        if (!s.player.hasDisconnected()) PacketDistributor.sendToPlayer(s.player, new ImporterPayloads.Reply(s.token, s.offset, false, error));
        discard(s);
    }
    private void discard(Session s) {
        s.cancelled = true;
        try { worker.execute(() -> { try { if (s.temporary != null) Files.deleteIfExists(s.temporary); } catch (IOException ignored) {} }); }
        catch (RejectedExecutionException stopping) { /* Startup cleanup removes interrupted temporary uploads. */ }
    }
    private void expire() {
        for (Session s : List.copyOf(sessions.values())) {
            if (System.nanoTime() - s.touched > IDLE_TIMEOUT || !holds(s.player, s.device))
                fail(s, "Upload expired or importer is no longer held. Close and reopen it.");
        }
        lastOpen.keySet().removeIf(id -> server.getPlayerList().getPlayer(id) == null);
    }
    @Override public void close() {
        closed = true;
        sessions.values().forEach(this::discard);
        sessions.clear(); lastOpen.clear();
        worker.shutdown(); // Drain cleanup without blocking the server thread.
    }
}
