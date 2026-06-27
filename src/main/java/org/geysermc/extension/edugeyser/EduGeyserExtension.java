package org.geysermc.extension.edugeyser;

import org.geysermc.event.subscribe.Subscribe;
import org.geysermc.geyser.api.command.Command;
import org.geysermc.geyser.api.command.CommandSource;
import org.geysermc.geyser.api.event.bedrock.SessionLoginEvent;
import org.geysermc.geyser.api.event.lifecycle.GeyserDefineCommandsEvent;
import org.geysermc.geyser.api.event.lifecycle.GeyserPostInitializeEvent;
import org.geysermc.geyser.api.event.lifecycle.GeyserShutdownEvent;
import org.geysermc.geyser.api.extension.Extension;
import org.geysermc.geyser.session.GeyserSession;

public class EduGeyserExtension implements Extension {

    private MessServerListManager serverListManager;
    private JoinCodeManager joinCodeManager;
    private TenantWhitelist tenantWhitelist;

    @Subscribe
    public void onPostInitialize(GeyserPostInitializeEvent event) {
        tenantWhitelist = new TenantWhitelist(this);
        tenantWhitelist.load();

        serverListManager = new MessServerListManager(this);
        serverListManager.initialize();

        joinCodeManager = new JoinCodeManager(this);
        joinCodeManager.initialize();
    }

    @Subscribe
    public void onSessionLogin(SessionLoginEvent event) {
        if (tenantWhitelist == null || !tenantWhitelist.isEnabled()) return;
        if (!(event.connection() instanceof GeyserSession session)) return;
        if (!session.isEducationClient()) return;

        String tenantId = session.getEducationTenantId();
        if (!tenantWhitelist.isAllowed(tenantId)) {
            event.setCancelled(true, "Your organization is not allowed on this server.");
        }
    }

    @Subscribe
    public void onShutdown(GeyserShutdownEvent event) {
        if (joinCodeManager != null) {
            joinCodeManager.shutdown();
        }
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

        event.register(Command.builder(this)
                .source(CommandSource.class)
                .name("joincode")
                .description("Manage Education Edition join codes")
                .permission("edugeyser.command.joincode")
                .executor((source, command, args) -> {
                    if (joinCodeManager == null) {
                        source.sendMessage("EduGeyser extension not initialized yet.");
                        return;
                    }
                    joinCodeManager.handleCommand(source, args);
                })
                .build());
    }
}
