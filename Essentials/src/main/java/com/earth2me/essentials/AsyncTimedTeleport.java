package com.earth2me.essentials;

import com.earth2me.essentials.utils.DateUtil;
import org.bukkit.Location;
import org.bukkit.boss.BarColor;
import org.bukkit.boss.BarStyle;
import org.bukkit.boss.BossBar;
import org.bukkit.event.player.PlayerTeleportEvent.TeleportCause;

import java.util.Locale;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

import net.ess3.api.IEssentials;
import net.ess3.api.IUser;
import net.essentialsx.api.v2.events.TeleportWarmupCancelledEvent;
import net.essentialsx.api.v2.events.TeleportWarmupCancelledEvent.CancelReason;

public class AsyncTimedTeleport implements Runnable {
    private static final double MOVE_CONSTANT = 0.3;
    private final IUser teleportOwner;
    private final IEssentials ess;
    private final AsyncTeleport teleport;
    private final UUID timer_teleportee;
    private final long timer_started; // time this task was initiated
    private final long timer_delay; // how long to delay the teleportPlayer
    private final CompletableFuture<Boolean> parentFuture;
    // note that I initially stored a clone of the location for reference, but...
    // when comparing locations, I got incorrect mismatches (rounding errors, looked like)
    // so, the X/Y/Z values are stored instead and rounded off
    private final long timer_initX;
    private final long timer_initY;
    private final long timer_initZ;
    private final ITarget timer_teleportTarget;
    private final boolean timer_respawn;
    private final boolean timer_canMove;
    private final Trade timer_chargeFor;
    private final TeleportCause timer_cause;
    private final BossBar timer_bossBar;
    private int timer_task;
    private double timer_health;

    AsyncTimedTeleport(final IUser user, final IEssentials ess, final AsyncTeleport teleport, final long delay, final IUser teleportUser, final ITarget target, final Trade chargeFor, final TeleportCause cause, final boolean respawn) {
        this(user, ess, teleport, delay, null, teleportUser, target, chargeFor, cause, respawn);
    }

    AsyncTimedTeleport(final IUser user, final IEssentials ess, final AsyncTeleport teleport, final long delay, final CompletableFuture<Boolean> future, final IUser teleportUser, final ITarget target, final Trade chargeFor, final TeleportCause cause, final boolean respawn) {
        this.teleportOwner = user;
        this.ess = ess;
        this.teleport = teleport;
        this.timer_started = System.currentTimeMillis();
        this.timer_delay = delay;
        this.timer_health = teleportUser.getBase().getHealth();
        this.timer_initX = Math.round(teleportUser.getBase().getLocation().getX() * MOVE_CONSTANT);
        this.timer_initY = Math.round(teleportUser.getBase().getLocation().getY() * MOVE_CONSTANT);
        this.timer_initZ = Math.round(teleportUser.getBase().getLocation().getZ() * MOVE_CONSTANT);
        this.timer_teleportee = teleportUser.getBase().getUniqueId();
        this.timer_teleportTarget = target;
        this.timer_chargeFor = chargeFor;
        this.timer_cause = cause;
        this.timer_respawn = respawn;
        this.timer_canMove = user.isAuthorized("essentials.teleport.timer.move");
        this.timer_bossBar = ess.getServer().createBossBar(getBossBarTitle(teleportUser), getBossBarColor(), BarStyle.SOLID);
        this.timer_bossBar.addPlayer(teleportUser.getBase());
        updateBossBar(teleportUser);

        timer_task = ess.scheduleSyncRepeatingTaskForEntity(teleportUser.getBase(), this, 20, 20);
        if (timer_task == -1) {
            removeBossBar();
        }

        if (future != null) {
            this.parentFuture = future;
            return;
        }

        final CompletableFuture<Boolean> cFuture = new CompletableFuture<>();
        cFuture.exceptionally(e -> {
            ess.showError(teleportOwner.getSource(), e, "\\ teleport");
            return false;
        });
        this.parentFuture = cFuture;
    }

    @Override
    public void run() {

        if (teleportOwner == null || !teleportOwner.getBase().isOnline() || teleportOwner.getBase().getLocation() == null) {
            cancelTimer(false);
            return;
        }

        final IUser teleportUser = ess.getUser(this.timer_teleportee);

        if (teleportUser == null || !teleportUser.getBase().isOnline()) {
            cancelTimer(false);
            return;
        }

        final Location currLocation = teleportUser.getBase().getLocation();
        if (currLocation == null) {
            cancelTimer(false);
            return;
        }

        if (!timer_canMove && (Math.round(currLocation.getX() * MOVE_CONSTANT) != timer_initX || Math.round(currLocation.getY() * MOVE_CONSTANT) != timer_initY || Math.round(currLocation.getZ() * MOVE_CONSTANT) != timer_initZ || teleportUser.getBase().getHealth() < timer_health)) {
            // user moved, cancelTimer teleportPlayer
            cancelTimer(true);
            return;
        }

        updateBossBar(teleportUser);

        class DelayedTeleportTask implements Runnable {
            @Override
            public void run() {

                timer_health = teleportUser.getBase().getHealth(); // in case user healed, then later gets injured
                final long now = System.currentTimeMillis();
                if (now > timer_started + timer_delay) {
                    try {
                        teleport.cooldown(false);
                    } catch (final Throwable ex) {
                        teleportOwner.sendTl("cooldownWithMessage", ex.getMessage());
                        if (teleportOwner != teleportUser) {
                            teleportUser.sendTl("cooldownWithMessage", ex.getMessage());
                        }
                    }
                    try {
                        cancelTimer(false);
                        teleportUser.sendTl("teleportationCommencing");

                        if (timer_chargeFor != null) {
                            timer_chargeFor.isAffordableFor(teleportOwner);
                        }

                        if (timer_respawn) {
                            teleport.respawnNow(teleportUser, timer_cause, parentFuture);
                        } else {
                            teleport.nowAsync(teleportUser, timer_teleportTarget, timer_cause, parentFuture);
                        }
                        parentFuture.thenAccept(success -> {
                            if (timer_chargeFor != null) {
                                try {
                                    timer_chargeFor.charge(teleportOwner);
                                } catch (final ChargeException ex) {
                                    ess.showError(teleportOwner.getSource(), ex, "\\ teleport");
                                }
                            }
                        });

                    } catch (final Exception ex) {
                        ess.showError(teleportOwner.getSource(), ex, "\\ teleport");
                    }
                }
            }
        }

        new DelayedTeleportTask().run();
    }

    //If we need to cancelTimer a pending teleportPlayer call this method
    void cancelTimer(final boolean notifyUser) {
        if (timer_task == -1) {
            removeBossBar();
            return;
        }
        try {
            ess.cancelTask(timer_task);

            final IUser teleportUser = ess.getUser(this.timer_teleportee);
            if (teleportUser != null && teleportUser.getBase() != null) {
                final TeleportWarmupCancelledEvent.CancelReason cancelReason = teleportUser.getBase().isOnline() ? CancelReason.MOVE : CancelReason.LEAVE;
                final TeleportWarmupCancelledEvent event = new TeleportWarmupCancelledEvent(teleportUser.getBase(), this.teleport.getTpType(), cancelReason, notifyUser);
                ess.getServer().getPluginManager().callEvent(event);
            }

            if (notifyUser) {
                teleportOwner.sendTl("pendingTeleportCancelled");
                if (timer_teleportee != null && !timer_teleportee.equals(teleportOwner.getBase().getUniqueId())) {
                    ess.getUser(timer_teleportee).sendTl("pendingTeleportCancelled");
                }
            }
        } finally {
            removeBossBar();
            timer_task = -1;
        }
    }

    private void updateBossBar(final IUser teleportUser) {
        final long remaining = Math.max(0L, timer_started + timer_delay - System.currentTimeMillis());
        timer_bossBar.setTitle(getBossBarTitle(teleportUser));
        timer_bossBar.setProgress(timer_delay <= 0L ? 0D : Math.max(0D, Math.min(1D, (double) remaining / timer_delay)));
    }

    private String getBossBarTitle(final IUser teleportUser) {
        final long remaining = Math.max(0L, timer_started + timer_delay - System.currentTimeMillis());
        return ess.getAdventureFacet().miniToLegacy(teleportUser.playerTl("dontMoveMessage", DateUtil.formatDateDiff(System.currentTimeMillis() + remaining)));
    }

    private void removeBossBar() {
        timer_bossBar.removeAll();
    }

    private BarColor getBossBarColor() {
        final String color = ess.getSettings().getPrimaryColor();
        if (color.startsWith("#") && color.length() == 7) {
            try {
                return nearestBossBarColor(Integer.parseInt(color.substring(1), 16));
            } catch (final NumberFormatException ignored) {
            }
        }

        switch (color.toLowerCase(Locale.ENGLISH)) {
            case "dark_blue":
            case "blue":
            case "dark_aqua":
            case "aqua":
                return BarColor.BLUE;
            case "dark_green":
            case "green":
                return BarColor.GREEN;
            case "dark_red":
            case "red":
                return BarColor.RED;
            case "dark_purple":
            case "light_purple":
                return BarColor.PURPLE;
            case "gold":
            case "yellow":
                return BarColor.YELLOW;
            case "white":
            case "gray":
            case "dark_gray":
            case "black":
                return BarColor.WHITE;
            default:
                return BarColor.YELLOW;
        }
    }

    private BarColor nearestBossBarColor(final int rgb) {
        final int red = (rgb >> 16) & 0xFF;
        final int green = (rgb >> 8) & 0xFF;
        final int blue = rgb & 0xFF;

        BarColor nearest = BarColor.YELLOW;
        int nearestDistance = Integer.MAX_VALUE;
        for (final BossBarColor color : BossBarColor.values()) {
            final int distance = distance(red, green, blue, color.red, color.green, color.blue);
            if (distance < nearestDistance) {
                nearest = color.barColor;
                nearestDistance = distance;
            }
        }
        return nearest;
    }

    private int distance(final int red, final int green, final int blue, final int targetRed, final int targetGreen, final int targetBlue) {
        final int redDiff = red - targetRed;
        final int greenDiff = green - targetGreen;
        final int blueDiff = blue - targetBlue;
        return redDiff * redDiff + greenDiff * greenDiff + blueDiff * blueDiff;
    }

    private enum BossBarColor {
        PINK(BarColor.PINK, 255, 85, 255),
        BLUE(BarColor.BLUE, 85, 85, 255),
        RED(BarColor.RED, 255, 85, 85),
        GREEN(BarColor.GREEN, 85, 255, 85),
        YELLOW(BarColor.YELLOW, 255, 255, 85),
        PURPLE(BarColor.PURPLE, 170, 0, 170),
        WHITE(BarColor.WHITE, 255, 255, 255);

        private final BarColor barColor;
        private final int red;
        private final int green;
        private final int blue;

        BossBarColor(final BarColor barColor, final int red, final int green, final int blue) {
            this.barColor = barColor;
            this.red = red;
            this.green = green;
            this.blue = blue;
        }
    }
}
