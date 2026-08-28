package com.cuzz.rookieCrates.runtime;

import com.melluh.servertours.api.PlaybackManager;
import com.melluh.servertours.api.RouteManager;
import com.melluh.servertours.api.ServerToursAPI;
import com.melluh.servertours.api.TouringPlayer;
import com.melluh.servertours.api.event.RoutePlaybackEndEvent;
import com.melluh.servertours.api.object.Route;
import com.melluh.servertours.api.playback.PlaybackValidation;
import com.melluh.servertours.api.playback.track.EventTrackRuntime;
import com.melluh.servertours.api.playback.track.TimelineEvent;
import com.melluh.servertours.api.playback.track.TrackContext;
import com.melluh.servertours.api.playback.track.TrackRegistration;
import org.bukkit.Bukkit;
import org.bukkit.NamespacedKey;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.plugin.Plugin;
import org.bukkit.plugin.java.JavaPlugin;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

/** ServerTours-backed recorded camera and RookieCrates event-track integration. */
public final class ServerToursSceneBridge implements RecordedCameraBridge, Listener {
    private static final int TRACK_PRIORITY = 100;

    private final JavaPlugin plugin;
    private final Plugin serverToursPlugin;
    private final SceneTiming timing;
    private final RouteManager routes;
    private final PlaybackManager playback;
    private final TrackRegistration trackRegistration;
    private final Map<UUID, Binding> bindings = new HashMap<>();
    private boolean closed;

    public ServerToursSceneBridge(JavaPlugin plugin, SceneTiming timing) {
        this.plugin = Objects.requireNonNull(plugin, "plugin");
        this.timing = Objects.requireNonNull(timing, "timing");
        this.serverToursPlugin = Objects.requireNonNull(
                Bukkit.getPluginManager().getPlugin("ServerTours"),
                "ServerTours is not installed"
        );
        if (!this.serverToursPlugin.isEnabled()) {
            throw new IllegalStateException("ServerTours is not enabled");
        }
        this.routes = ServerToursAPI.getRouteManager();
        this.playback = ServerToursAPI.getPlaybackManager();
        this.trackRegistration = this.playback.registerTrackFactory(
                plugin,
                new NamespacedKey(plugin, "crate-scene"),
                TRACK_PRIORITY,
                this::createTrack
        );
        Bukkit.getPluginManager().registerEvents(this, plugin);
    }

    @Override
    public Validation validate(Player player, String routeName) {
        requireMainThread();
        Objects.requireNonNull(player, "player");
        String normalized = normalizeRoute(routeName);
        if (normalized == null) {
            return Validation.rejected("路线名不能为空。");
        }
        if (closed || !serverToursPlugin.isEnabled()) {
            return Validation.rejected("ServerTours 当前未启用。");
        }
        try {
            Route route = routes.getRoute(normalized);
            PlaybackValidation validation = playback.validateTour(player, route);
            if (!validation.isValid()) {
                return Validation.rejected(message(validation));
            }
            return Validation.accepted(route.getName());
        } catch (RuntimeException | LinkageError failure) {
            return Validation.rejected("ServerTours 预检失败：" + describe(failure));
        }
    }

    @Override
    public void start(SceneSession session, String routeName) {
        requireMainThread();
        Objects.requireNonNull(session, "session");
        Validation validation = validate(session.player(), routeName);
        if (!validation.valid()) {
            throw cameraFailure(validation.message(), null);
        }
        Route route = routes.getRoute(validation.canonicalRoute());
        Binding binding = new Binding(session, route);
        if (bindings.putIfAbsent(session.playerId(), binding) != null) {
            throw cameraFailure("该玩家已有 ServerTours 宝箱镜头会话。", null);
        }
        try {
            TouringPlayer touringPlayer = playback.showTour(session.player(), route);
            if (touringPlayer == null || touringPlayer.getRoute() != route || !binding.trackCreated) {
                throw new IllegalStateException("ServerTours did not start the requested recorded route");
            }
            binding.touringPlayer = touringPlayer;
        } catch (Throwable failure) {
            bindings.remove(session.playerId(), binding);
            binding.detached = true;
            TouringPlayer touringPlayer = binding.touringPlayer;
            if (touringPlayer != null) {
                try {
                    touringPlayer.exit();
                } catch (RuntimeException cleanupFailure) {
                    failure.addSuppressed(cleanupFailure);
                }
            }
            throw cameraFailure("ServerTours 镜头启动失败：" + describe(failure), failure);
        }
    }

    @Override
    public void stop(SceneSession session) {
        requireMainThread();
        Binding binding = bindings.get(session.playerId());
        if (binding == null || binding.session != session
                || !bindings.remove(session.playerId(), binding)) {
            return;
        }
        binding.detached = true;
        TouringPlayer touringPlayer = binding.touringPlayer;
        if (touringPlayer != null) {
            try {
                touringPlayer.exit();
            } catch (RuntimeException failure) {
                plugin.getLogger().severe("Could not stop ServerTours crate camera for "
                        + session.playerId() + ": " + describe(failure));
            }
        }
    }

    @Override
    public void close() {
        requireMainThread();
        if (closed) {
            return;
        }
        closed = true;
        for (Binding binding : List.copyOf(bindings.values())) {
            stop(binding.session);
        }
        trackRegistration.close();
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onTourEnd(RoutePlaybackEndEvent event) {
        Binding binding = bindings.get(event.getBukkitPlayer().getUniqueId());
        if (binding == null || binding.detached
                || binding.route != event.getRoute()
                || binding.touringPlayer != event.getTouringPlayer()) {
            return;
        }
        binding.endReason = event.getReason();
        binding.authorizedExit = event.getReason() == RoutePlaybackEndEvent.EndReason.EXITED
                && binding.session.request().skipAllowed()
                && event.getTouringPlayer().canExit()
                && event.getTouringPlayer().getCurrentFrame() >= timing.skipAfterTicks();
    }

    private Optional<com.melluh.servertours.api.playback.track.TrackRuntime> createTrack(
            TrackContext context
    ) {
        Binding binding = bindings.get(context.getPlayer().getUniqueId());
        if (closed || binding == null || binding.detached
                || binding.route != context.getRoute() || binding.trackCreated) {
            return Optional.empty();
        }
        binding.trackCreated = true;
        long endFrame = totalEndFrame(timing, context.getCameraDurationFrames());
        return Optional.of(new CrateTrack(binding, endFrame));
    }

    private void scheduleFinish(Binding binding) {
        if (binding.detached || binding.completionScheduled) {
            return;
        }
        binding.completionScheduled = true;
        try {
            Bukkit.getScheduler().runTask(plugin, () -> finish(binding));
        } catch (RuntimeException schedulingFailure) {
            plugin.getLogger().severe("Could not schedule ServerTours scene completion for "
                    + binding.session.playerId() + ": " + describe(schedulingFailure));
            finish(binding);
        }
    }

    private void finish(Binding binding) {
        if (binding.detached
                || !bindings.remove(binding.session.playerId(), binding)) {
            return;
        }
        binding.detached = true;
        boolean successful = successfulEnd(binding.endReason, binding.authorizedExit);
        if (successful) {
            binding.session.controller().completeRecorded(binding.session);
            return;
        }
        String reason = binding.endReason == null ? "ERROR" : binding.endReason.name();
        binding.session.controller().abortRecorded(
                binding.session,
                cameraFailure("ServerTours 镜头异常结束：" + reason, null)
        );
    }

    private final class CrateTrack implements EventTrackRuntime {
        private final Binding binding;
        private final long endFrame;
        private final List<TimelineEvent> events;

        private CrateTrack(Binding binding, long endFrame) {
            this.binding = binding;
            this.endFrame = endFrame;
            List<TimelineEvent> configured = new ArrayList<>(3);
            configured.add(new TimelineEvent(
                    "spawn-crate",
                    0L,
                    (context, frame) -> binding.session.beginRecordedScene()
            ));
            configured.add(new TimelineEvent(
                    "reveal-rewards",
                    timing.revealDelayTicks(),
                    (context, frame) -> binding.session.reveal()
            ));
            if (binding.session.request().skipAllowed() && skipEventFits(timing, endFrame)) {
                configured.add(new TimelineEvent(
                        "enable-skip",
                        timing.skipAfterTicks(),
                        (context, frame) -> context.getTouringPlayer().setCanExit(true)
                ));
            }
            this.events = List.copyOf(configured);
        }

        @Override
        public long getEndFrame() {
            return endFrame;
        }

        @Override
        public List<TimelineEvent> events() {
            return events;
        }

        @Override
        public void setup(TrackContext context) {
            binding.touringPlayer = context.getTouringPlayer();
            context.getTouringPlayer().setProgressBarEnabled(false);
            context.getTouringPlayer().setActionBarEnabled(false);
            context.getTouringPlayer().setExitByMoving(false);
            context.getTouringPlayer().setCanExit(false);
        }

        @Override
        public void teardown(TrackContext context) {
            scheduleFinish(binding);
        }
    }

    private static SceneAbortedException cameraFailure(String message, Throwable cause) {
        return new SceneAbortedException(
                SceneAbortReason.CAMERA_FAILURE,
                new IllegalStateException(message, cause)
        );
    }

    private static String message(PlaybackValidation validation) {
        return switch (validation.status()) {
            case ROUTE_NOT_FOUND -> "ServerTours 路线不存在。";
            case INVALID_ROUTE_TYPE -> "该路线不是 ServerTours 管理的路线。";
            case NO_POINTS -> "ServerTours 路线没有可播放点位。";
            case CAMERA_SOURCE_NOT_RECORDED -> "该路线不是 RECORDED 录制镜头。";
            case RECORDING_NOT_ASSIGNED -> "该路线没有绑定录制文件。";
            case RECORDING_UNAVAILABLE -> "该路线的录制文件缺失或尚未就绪。";
            case PLAYER_RECORDING -> "玩家正在录制 ServerTours 镜头。";
            case PLAYER_BUSY -> "玩家正在观看另一个 ServerTours 镜头。";
            case MANAGER_STOPPING -> "ServerTours 播放管理器正在停止。";
            case UNSUPPORTED_CLIENT -> "该客户端不支持 ServerTours 录制镜头。";
            case VALID -> "ready";
        };
    }

    private static String normalizeRoute(String routeName) {
        if (routeName == null) {
            return null;
        }
        String normalized = routeName.trim().toLowerCase(Locale.ROOT);
        return normalized.isEmpty() ? null : normalized;
    }

    static long totalEndFrame(SceneTiming timing, long cameraDurationFrames) {
        Objects.requireNonNull(timing, "timing");
        if (cameraDurationFrames < 0L) {
            throw new IllegalArgumentException("cameraDurationFrames may not be negative");
        }
        long modelEndFrame = Math.addExact(timing.revealDelayTicks(), timing.resultDisplayTicks());
        return Math.max(cameraDurationFrames, modelEndFrame);
    }

    static boolean skipEventFits(SceneTiming timing, long endFrame) {
        return Objects.requireNonNull(timing, "timing").skipAfterTicks() <= endFrame;
    }

    static boolean successfulEnd(
            RoutePlaybackEndEvent.EndReason reason,
            boolean authorizedExit
    ) {
        return reason == RoutePlaybackEndEvent.EndReason.FINISHED
                || reason == RoutePlaybackEndEvent.EndReason.EXITED && authorizedExit;
    }

    private static String describe(Throwable failure) {
        String message = failure.getMessage();
        return message == null || message.isBlank() ? failure.getClass().getSimpleName() : message;
    }

    private static void requireMainThread() {
        if (!Bukkit.isPrimaryThread()) {
            throw new IllegalStateException("ServerTours camera integration requires the Bukkit main thread");
        }
    }

    private static final class Binding {
        private final SceneSession session;
        private final Route route;
        private TouringPlayer touringPlayer;
        private RoutePlaybackEndEvent.EndReason endReason;
        private boolean trackCreated;
        private boolean authorizedExit;
        private boolean completionScheduled;
        private boolean detached;

        private Binding(SceneSession session, Route route) {
            this.session = session;
            this.route = route;
        }
    }
}
