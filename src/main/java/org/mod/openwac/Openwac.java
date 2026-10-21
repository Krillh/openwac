package org.mod.openwac;

import net.neoforged.bus.api.IEventBus;
import net.neoforged.fml.common.Mod;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.event.server.ServerStoppingEvent;
import org.mod.openwac.integration.neoforge.registration.ModContent;
import org.mod.openwac.service.ComputerRuntime;
import org.mod.openwac.integration.neoforge.network.*;
import net.neoforged.neoforge.event.tick.ServerTickEvent;
import org.mod.openwac.integration.neoforge.storage.DiskLifecycle;

// The value here should match an entry in the META-INF/neoforge.mods.toml file
@Mod(Openwac.MODID)
public class Openwac {
    public static final String MODID = "openwac";

    public Openwac(IEventBus modEventBus) {
        ModContent.register(modEventBus);
        modEventBus.addListener(ImporterNetwork::register);
        modEventBus.addListener(TtdNetwork::register);
        modEventBus.addListener(KeyboardNetwork::register);
        NeoForge.EVENT_BUS.addListener(this::serverStopping);
        NeoForge.EVENT_BUS.addListener(DiskLifecycle::entityLeft);
        NeoForge.EVENT_BUS.addListener(DiskLifecycle::tick);
        NeoForge.EVENT_BUS.addListener((ServerTickEvent.Post event) -> ImporterUploads.tick(event.getServer()));
        NeoForge.EVENT_BUS.addListener((ServerTickEvent.Post event) -> KeyboardNetwork.tick(event.getServer()));
    }
    private void serverStopping(ServerStoppingEvent event) {
        DiskLifecycle.stop(event.getServer());
        ImporterUploads.stop(event.getServer());
        KeyboardNetwork.stop(event.getServer());
        ComputerRuntime.shutdownAll();
    }
}
