package org.geysermc.extension.edugeyser;

import org.geysermc.event.subscribe.Subscribe;
import org.geysermc.geyser.api.command.Command;
import org.geysermc.geyser.api.command.CommandSource;
import org.geysermc.geyser.api.event.lifecycle.GeyserDefineCommandsEvent;
import org.geysermc.geyser.api.event.lifecycle.GeyserPostInitializeEvent;
import org.geysermc.geyser.api.event.lifecycle.GeyserShutdownEvent;
import org.geysermc.geyser.api.extension.Extension;

public class EduGeyserExtension implements Extension {

    private MessServerListManager serverListManager;

    @Subscribe
    public void onPostInitialize(GeyserPostInitializeEvent event) {
        serverListManager = new MessServerListManager(this);
        serverListManager.initialize();
    }

    @Subscribe
    public void onShutdown(GeyserShutdownEvent event) {
        if (serverListManager != null) {
            serverListManager.shutdown();
        }
    }

    @Subscribe
    public void onDefineCommands(GeyserDefineCommandsEvent event) {
        event.register(Command.builder(this)
                .source(CommandSource.class)
                .name("serverlist")
                .description("Manage Education Edition server list registrations")
                .permission("edugeyser.command.serverlist")
                .executor((source, command, args) -> {
                    if (serverListManager == null) {
                        source.sendMessage("EduGeyser extension not initialized yet.");
                        return;
                    }
                    serverListManager.handleCommand(source, args);
                })
                .build());
    }
}
