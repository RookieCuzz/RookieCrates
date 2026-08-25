package com.cuzz.rookieCrates;

import com.cuzz.rookieCrates.config.CrateDefaults;
import com.cuzz.rookieCrates.economy.VaultEconomyGateway;
import com.cuzz.rookieCrates.gui.CratesGuiController;
import com.cuzz.rookieCrates.gui.CratesGuiModule;
import com.cuzz.rookieCrates.gui.api.DefaultCratesGuiFacade;
import com.cuzz.rookieCrates.key.PhysicalKeyService;
import com.cuzz.rookieCrates.listener.CrateRuntimeListener;
import com.cuzz.rookieCrates.listener.SceneLifecycleListener;
import com.cuzz.rookieCrates.runtime.CrateRuntime;
import com.cuzz.rookieCrates.runtime.SceneController;
import com.cuzz.rookieCrates.runtime.SceneTiming;
import com.cuzz.rookieCrates.service.ConfigurationTransferService;
import com.cuzz.rookieCrates.service.LegacyConfigMigrator;
import com.cuzz.rookieCrates.service.OpeningCoordinator;
import com.cuzz.rookieCrates.service.PaymentService;
import com.cuzz.rookieCrates.service.PitySelector;
import com.cuzz.rookieCrates.service.PlayerOperationLocks;
import com.cuzz.rookieCrates.service.RewardDeliveryService;
import com.cuzz.rookieCrates.storage.SQLiteDatabase;
import com.cuzz.rookieCrates.storage.SQLiteSceneRecoveryStore;
import com.cuzz.rookieCrates.util.ItemStackCodec;
import com.cuzz.rookieCrates.util.Messages;
import org.bukkit.plugin.java.JavaPlugin;

import java.nio.file.Path;
import java.util.Random;
import java.util.concurrent.TimeUnit;

/** RookieCrates production bootstrap. */
public final class RookieCrates extends JavaPlugin {

    private SQLiteDatabase database;
    private CrateRuntime crateRuntime;
    private SceneController sceneController;
    private DefaultCratesGuiFacade facade;

    @Override
    public void onEnable() {
        try {
            saveDefaultConfig();
            getConfig().options().copyDefaults(true);
            saveConfig();
            CrateDefaults crateDefaults = CrateDefaults.load(getConfig());

            Path databaseFile = getDataFolder().toPath()
                    .resolve(getConfig().getString("database.file", "rookiecrates.db"))
                    .toAbsolutePath()
                    .normalize();
            database = new SQLiteDatabase(
                    databaseFile,
                    getConfig().getInt("database.busy-timeout-ms", 5_000)
            );
            database.start();

            boolean migratedLegacyScene = new LegacyConfigMigrator(this, database)
                    .migrateIfPresent()
                    .get(15, TimeUnit.SECONDS);
            if (migratedLegacyScene) {
                getConfig().set("legacy-migration.completed", true);
                saveConfig();
            }

            Messages messages = new Messages(this);
            ItemStackCodec itemCodec = new ItemStackCodec();
            PhysicalKeyService physicalKeys = new PhysicalKeyService(this);
            VaultEconomyGateway economy = new VaultEconomyGateway(this);
            if (!economy.isAvailable()) {
                getLogger().warning("Vault is present without an Economy provider. Crates with a price above zero will refuse to open.");
            }

            PlayerOperationLocks playerLocks = new PlayerOperationLocks();
            PaymentService payments = new PaymentService(physicalKeys, economy);
            RewardDeliveryService deliveries = new RewardDeliveryService(this, database, itemCodec);

            crateRuntime = new CrateRuntime(this);
            SceneTiming sceneTiming = new SceneTiming(
                    getConfig().getLong("opening.reveal-delay-ticks", 95L),
                    getConfig().getLong("opening.reveal-duration-ticks", 100L),
                    getConfig().getLong("opening.skip-after-ticks", 40L)
            );
            sceneController = new SceneController(
                    this,
                    crateRuntime,
                    new SQLiteSceneRecoveryStore(database),
                    sceneTiming
            );
            OpeningCoordinator openings = new OpeningCoordinator(
                    this,
                    database,
                    payments,
                    deliveries,
                    itemCodec,
                    sceneController,
                    messages,
                    playerLocks,
                    new PitySelector<>(new Random())
            );
            ConfigurationTransferService transfers = new ConfigurationTransferService(
                    database,
                    getDataFolder().toPath()
            );
            facade = new DefaultCratesGuiFacade(
                    this,
                    database,
                    economy,
                    physicalKeys,
                    itemCodec,
                    openings,
                    deliveries,
                    playerLocks,
                    crateRuntime,
                    transfers,
                    crateDefaults
            );

            CratesGuiController gui = CratesGuiModule.install(this, facade);
            crateRuntime.setInteractionFacade((player, interaction) -> {
                if (!player.hasPermission(CratesGuiController.USE_PERMISSION)) {
                    messages.send(player, "no-permission");
                    return;
                }
                facade.preferPlacement(player.getUniqueId(), interaction.placementId());
                gui.openPlayerCrate(player, interaction.crateId(), 0);
            });
            getServer().getPluginManager().registerEvents(new CrateRuntimeListener(crateRuntime), this);
            getServer().getPluginManager().registerEvents(new SceneLifecycleListener(sceneController), this);

            facade.reloadRuntime().whenComplete((ignored, failure) -> {
                if (failure != null) {
                    getLogger().severe("Could not load model crate placements: " + describe(failure));
                } else {
                    getLogger().info("SQLite crate definitions and model placements loaded.");
                }
            });
            sceneController.recoverOnlinePlayers();
            getLogger().info("RookieCrates enabled with SQLite; draw modes: single and seven.");
        } catch (Exception exception) {
            getLogger().severe("RookieCrates could not start: " + describe(exception));
            cleanupAfterFailedEnable();
            getServer().getPluginManager().disablePlugin(this);
        }
    }

    @Override
    public void onDisable() {
        if (sceneController != null) {
            sceneController.shutdown();
        }
        if (crateRuntime != null) {
            crateRuntime.shutdown();
        }
        if (database != null) {
            try {
                database.close();
            } catch (RuntimeException exception) {
                getLogger().severe("Could not close SQLite cleanly: " + describe(exception));
            }
        }
    }

    private void cleanupAfterFailedEnable() {
        if (crateRuntime != null) {
            try {
                crateRuntime.shutdown();
            } catch (RuntimeException ignored) {
                // Startup failure logging above is the primary failure.
            }
        }
        if (database != null) {
            try {
                database.close();
            } catch (RuntimeException ignored) {
                // Startup failure logging above is the primary failure.
            }
        }
    }

    private static String describe(Throwable throwable) {
        Throwable current = throwable;
        while (current.getCause() != null
                && (current instanceof java.util.concurrent.ExecutionException
                || current instanceof java.util.concurrent.CompletionException)) {
            current = current.getCause();
        }
        return current.getMessage() == null ? current.getClass().getSimpleName() : current.getMessage();
    }
}
