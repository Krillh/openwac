package org.mod.openwac.integration.neoforge.client;

import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.*;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import net.neoforged.neoforge.client.network.ClientPacketDistributor;
import org.mod.openwac.integration.neoforge.network.ImporterPayloads;
import org.mod.openwac.service.ImportedFileStore;
import java.io.*;
import java.nio.*;
import java.nio.channels.FileChannel;
import java.nio.file.*;
import java.nio.file.attribute.*;
import java.util.*;
import java.util.concurrent.*;

/** Explicit local selection and upload. Server packets can never request a client path. */
public final class FileImporterScreen extends Screen {
    private static final ThreadPoolExecutor FILES = new ThreadPoolExecutor(1, 1, 0, TimeUnit.SECONDS,
            new ArrayBlockingQueue<>(8), r -> Thread.ofPlatform().daemon().name("openwac-import-read").unstarted(r));
    private final UUID token, device;
    private final Path settings;
    private final String settingsKey;
    private EditBox path;
    private Button upload;
    private String status = "Choose a file on this computer. Maximum size: 16 MiB.";
    private volatile boolean closed;
    private boolean uploading, terminal;
    private long size, acknowledged, lastActivity;
    // Local file state is written/read only by FILES, before sending one acknowledged packet at a time.
    private Path selected;
    private FileTime modified;
    private Object fileKey;
    private boolean loadedSettings;

    public FileImporterScreen(UUID token, UUID device) {
        super(Component.translatable("screen.openwac.file_importer"));
        this.token = token; this.device = device;
        settings = minecraft.gameDirectory.toPath().resolve("config/openwac-importer-paths.properties");
        var server = minecraft.getCurrentServer();
        settingsKey = (server == null ? "local" : server.ip) + "/" + device;
    }
    @Override protected void init() {
        String previous = path == null ? "" : path.getValue();
        int panelWidth = Math.min(440, width - 32), left = (width - panelWidth) / 2, top = Math.max(30, height / 2 - 65);
        path = addRenderableWidget(new EditBox(font, left, top + 26, panelWidth, 20, Component.translatable("screen.openwac.import_path")));
        path.setMaxLength(4096); path.setValue(previous); path.setEditable(!uploading && !terminal);
        upload = addRenderableWidget(Button.builder(Component.translatable("screen.openwac.upload"), button -> begin())
                .bounds(left, top + 56, panelWidth / 2 - 4, 20).build());
        upload.active = !uploading && !terminal;
        addRenderableWidget(Button.builder(Component.translatable("gui.done"), button -> onClose())
                .bounds(left + panelWidth / 2 + 4, top + 56, panelWidth / 2 - 4, 20).build());
        setInitialFocus(path);
        if (!loadedSettings) {
            loadedSettings = true;
            work(() -> {
                Properties saved = loadSettings();
                String remembered = saved.getProperty(settingsKey, "");
                screenExecutor.execute(() -> { if (path.getValue().isEmpty() && !uploading) path.setValue(remembered); });
            });
        }
    }
    private Properties loadSettings() {
        Properties saved = new Properties();
        try {
            if (Files.isRegularFile(settings) && Files.size(settings) <= 1024 * 1024)
                try (var input = Files.newInputStream(settings)) { saved.load(input); }
        } catch (IOException | IllegalArgumentException ignored) {}
        return saved;
    }
    private void remember(String absolutePath) {
        try {
            Properties saved = loadSettings(); saved.setProperty(settingsKey, absolutePath);
            Files.createDirectories(settings.getParent());
            try (var output = Files.newOutputStream(settings)) { saved.store(output, "Local paths only. Never sent to a Minecraft server."); }
        } catch (IOException ignored) { /* Remembering a path is optional; upload remains available. */ }
    }
    private void begin() {
        if (uploading || terminal) return;
        String text = path.getValue();
        uploading = true; upload.active = false; path.setEditable(false);
        status = "Opening local file..."; lastActivity = System.nanoTime();
        work(() -> {
            Path chosen = Path.of(text);
            if (!chosen.isAbsolute()) throw new IOException("Enter an absolute path.");
            String filename = chosen.getFileName() == null ? "" : chosen.getFileName().toString();
            if (!ImportedFileStore.validName(filename)) throw new IOException("The file needs an ASCII basename of at most 255 characters.");
            selected = chosen.toRealPath();
            BasicFileAttributes attributes = Files.readAttributes(selected, BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
            if (!attributes.isRegularFile() || !Files.isReadable(selected)) throw new IOException("Choose a readable regular file.");
            long length = attributes.size();
            if (length > ImportedFileStore.MAX_FILE_BYTES) throw new IOException("The file exceeds the 16 MiB upload limit.");
            modified = attributes.lastModifiedTime(); fileKey = attributes.fileKey();
            remember(text);
            screenExecutor.execute(() -> {
                if (closed) return;
                size = length; status = "Starting upload...";
                ClientPacketDistributor.sendToServer(new ImporterPayloads.Begin(token, filename, size));
            });
        });
    }
    public void reply(ImporterPayloads.Reply reply) {
        if (closed || !token.equals(reply.token())) return;
        lastActivity = System.nanoTime();
        if (!reply.error().isEmpty()) { stopWithError(reply.error()); return; }
        if (!uploading || terminal || reply.offset() < acknowledged || reply.offset() > size
                || reply.offset() - acknowledged > ImporterPayloads.CHUNK_BYTES) return;
        acknowledged = reply.offset();
        if (reply.finished()) {
            if (acknowledged != size) { stopWithError("Server acknowledged an incomplete upload."); return; }
            uploading = false; terminal = true;
            status = "Uploaded. Install the importer and power on the computer.";
            return;
        }
        if (acknowledged == size) { stopWithError("Unexpected upload acknowledgement."); return; }
        status = "Uploading: " + acknowledged + " / " + size + " bytes";
        long offset = acknowledged;
        int count = (int) Math.min(ImporterPayloads.CHUNK_BYTES, size - offset);
        work(() -> {
            BasicFileAttributes attributes = Files.readAttributes(selected, BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
            if (!attributes.isRegularFile() || attributes.size() != size || !attributes.lastModifiedTime().equals(modified)
                    || !Objects.equals(attributes.fileKey(), fileKey)) throw new IOException("The file changed. Close the screen and upload again.");
            byte[] bytes = new byte[count];
            try (FileChannel channel = FileChannel.open(selected, StandardOpenOption.READ, LinkOption.NOFOLLOW_LINKS)) {
                ByteBuffer buffer = ByteBuffer.wrap(bytes);
                while (buffer.hasRemaining()) {
                    int read = channel.read(buffer, offset + buffer.position());
                    if (read <= 0) throw new IOException("The file became unreadable. Upload again.");
                }
            }
            BasicFileAttributes after = Files.readAttributes(selected, BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
            if (!after.isRegularFile() || after.size() != size || !after.lastModifiedTime().equals(modified)
                    || !Objects.equals(after.fileKey(), fileKey)) throw new IOException("The file changed during reading. Upload again.");
            screenExecutor.execute(() -> {
                if (!closed && uploading) ClientPacketDistributor.sendToServer(new ImporterPayloads.Chunk(token, offset, bytes));
            });
        });
    }
    @FunctionalInterface private interface FileTask { void run() throws IOException; }
    private void work(FileTask task) {
        try {
            FILES.execute(() -> {
                if (closed) return;
                try { task.run(); }
                catch (IOException | RuntimeException failure) {
                    screenExecutor.execute(() -> stopWithError(failure instanceof InvalidPathException
                            ? "Invalid local path." : Objects.toString(failure.getMessage(), "Cannot read local file.")));
                }
            });
        } catch (RejectedExecutionException busy) { stopWithError("File reader is busy. Close and reopen the importer."); }
    }
    private void stopWithError(String error) {
        status = error; uploading = false; terminal = true;
        if (upload != null) upload.active = false;
        if (minecraft.getConnection() != null) ClientPacketDistributor.sendToServer(new ImporterPayloads.Cancel(token));
    }
    @Override public void tick() {
        if (uploading && System.nanoTime() - lastActivity > TimeUnit.MINUTES.toNanos(2))
            stopWithError("Upload timed out. Close and reopen the importer.");
    }
    @Override public void removed() {
        closed = true;
        if (minecraft.getConnection() != null && !terminal) ClientPacketDistributor.sendToServer(new ImporterPayloads.Cancel(token));
        super.removed();
    }
    @Override public boolean isPauseScreen() { return false; }
    @Override public void extractRenderState(GuiGraphicsExtractor graphics, int mouseX, int mouseY, float partialTick) {
        super.extractRenderState(graphics, mouseX, mouseY, partialTick);
        int panelWidth = Math.min(440, width - 32), left = (width - panelWidth) / 2, top = Math.max(30, height / 2 - 65);
        graphics.centeredText(font, title, width / 2, top, 0xffffffff);
        graphics.text(font, Component.translatable("screen.openwac.import_path"), left, top + 14, 0xffaaaaaa);
        int y = top + 87;
        for (var line : font.split(Component.literal(status), panelWidth)) {
            graphics.text(font, line, left, y, 0xffffffff); y += 10;
        }
        graphics.text(font, "Only the basename and file contents are uploaded.", left, y + 8, 0xffaaaaaa);
    }
}
