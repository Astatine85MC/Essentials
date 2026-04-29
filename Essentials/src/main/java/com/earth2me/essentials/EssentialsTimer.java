package com.earth2me.essentials;

import net.ess3.api.IEssentials;
import org.bukkit.entity.Player;

import java.util.LinkedList;
import java.util.UUID;
import java.util.logging.Level;

public class EssentialsTimer implements Runnable {
    private final transient IEssentials ess;
    private final LinkedList<Double> history = new LinkedList<>();
    @SuppressWarnings("FieldCanBeLocal")
    private final long tickInterval = 50;
    private transient long lastPoll = System.nanoTime();

    EssentialsTimer(final IEssentials ess) {
        this.ess = ess;
        history.add(20d);
    }

    @Override
    public void run() {
        final long startTime = System.nanoTime();
        final long currentTime = System.currentTimeMillis();
        long timeSpent = (startTime - lastPoll) / 1000;
        if (timeSpent == 0) {
            timeSpent = 1;
        }
        if (history.size() > 10) {
            history.remove();
        }
        final double tps = tickInterval * 1000000.0 / timeSpent;
        if (tps <= 21) {
            history.add(tps);
        }
        lastPoll = startTime;
        for (final Player player : ess.getOnlinePlayers()) {
            ess.scheduleSyncDelayedTaskForEntity(player, () -> tickUser(player.getUniqueId(), currentTime));
        }
    }

    private void tickUser(final UUID uuid, final long currentTime) {
        try {
            final User user = ess.getUser(uuid);
            if (user == null) {
                return;
            }
            user.setLastOnlineActivity(currentTime);
            user.checkActivity();
            if (user.getLastOnlineActivity() < currentTime && user.getLastOnlineActivity() > user.getLastLogout()) {
                if (!user.isHidden()) {
                    user.setLastLogout(user.getLastOnlineActivity());
                }
                return;
            }
            user.checkMuteTimeout(currentTime);
            user.checkJailTimeout(currentTime);
            user.resetInvulnerabilityAfterTeleport();
        } catch (final Exception e) {
            ess.getLogger().log(Level.WARNING, "EssentialsTimer Error:", e);
        }
    }

    public double getAverageTPS() {
        double avg = 0;
        for (final Double f : history) {
            if (f != null) {
                avg += f;
            }
        }
        return avg / history.size();
    }
}
