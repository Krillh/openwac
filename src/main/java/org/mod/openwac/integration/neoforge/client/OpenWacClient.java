package org.mod.openwac.integration.neoforge.client;

import net.minecraft.client.Minecraft;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.fml.common.Mod;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.client.network.event.RegisterClientPayloadHandlersEvent;
import org.mod.openwac.integration.neoforge.network.ImporterPayloads;
import net.neoforged.neoforge.client.event.EntityRenderersEvent;
import net.neoforged.neoforge.client.event.RegisterMenuScreensEvent;
import org.mod.openwac.integration.neoforge.registration.ModContent;

/** Loaded only on physical clients; common networking has no Minecraft client dependencies. */
@Mod(value = "openwac", dist = Dist.CLIENT)
public final class OpenWacClient {
    public OpenWacClient(IEventBus bus) {
        bus.addListener(OpenWacClient::register);
        bus.addListener(TtdClientNetwork::register);
        bus.addListener(KeyboardClientNetwork::register);
        NeoForge.EVENT_BUS.addListener(KeyboardClientNetwork::tick);
        NeoForge.EVENT_BUS.addListener(KeyboardClientNetwork::key);
        bus.addListener((RegisterMenuScreensEvent event) -> event.register(ModContent.COMPUTER_CASE_MENU.get(), ComputerCaseScreen::new));
        bus.addListener((EntityRenderersEvent.RegisterRenderers event) -> event.registerBlockEntityRenderer(ModContent.TTD_ENTITY.get(), context -> new TtdRenderer()));
    }
    private static void register(RegisterClientPayloadHandlersEvent event) {
        event.register(ImporterPayloads.Open.TYPE, (payload, context) -> {
            Minecraft client = Minecraft.getInstance();
            if (client.player != null && (client.player.getMainHandItem().getItem() instanceof org.mod.openwac.integration.neoforge.item.FileImporterItem
                    || client.player.getOffhandItem().getItem() instanceof org.mod.openwac.integration.neoforge.item.FileImporterItem))
                client.gui.setScreen(new FileImporterScreen(payload.token(), payload.device()));
        });
        event.register(ImporterPayloads.Reply.TYPE, (payload, context) -> {
            if (Minecraft.getInstance().gui.screen() instanceof FileImporterScreen screen) screen.reply(payload);
        });
    }
}
