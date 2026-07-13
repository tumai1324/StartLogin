package com.pingfeng.startlogin.ui;

import com.pingfeng.startlogin.StartLogin;
import com.pingfeng.startlogin.config.ConfigManager;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

public class UILock {

    private final StartLogin plugin;
    private final ConfigManager configManager;
    private final Map<UUID, LockState> lockStates;

    private static class LockState {
        boolean locked;
        long cooldownUntil;

        LockState(boolean locked, long cooldownUntil) {
            this.locked = locked;
            this.cooldownUntil = cooldownUntil;
        }
    }

    public UILock(StartLogin plugin, ConfigManager configManager) {
        this.plugin = plugin;
        this.configManager = configManager;
        this.lockStates = new ConcurrentHashMap<>();
    }

    public boolean tryLock(UUID playerUuid) {
        LockState state = lockStates.computeIfAbsent(playerUuid, k -> new LockState(false, 0));

        synchronized (state) {
            if (state.locked) {
                return false;
            }
            long now = System.currentTimeMillis();
            if (now < state.cooldownUntil) {
                return false;
            }
            state.locked = true;
            return true;
        }
    }

    public void unlock(UUID playerUuid) {
        LockState state = lockStates.get(playerUuid);
        if (state == null) {
            return;
        }

        synchronized (state) {
            state.locked = false;
            int cooldownSeconds = configManager.getUiCooldown();
            state.cooldownUntil = System.currentTimeMillis() + cooldownSeconds * 1000L;
        }
    }

    public void forceUnlock(UUID playerUuid) {
        LockState state = lockStates.get(playerUuid);
        if (state == null) {
            return;
        }

        synchronized (state) {
            state.locked = false;
        }
    }

    public boolean isLocked(UUID playerUuid) {
        LockState state = lockStates.get(playerUuid);
        if (state == null) {
            return false;
        }
        synchronized (state) {
            return state.locked;
        }
    }

    public boolean isInCooldown(UUID playerUuid) {
        LockState state = lockStates.get(playerUuid);
        if (state == null) {
            return false;
        }
        synchronized (state) {
            return System.currentTimeMillis() < state.cooldownUntil;
        }
    }

    public void removePlayer(UUID playerUuid) {
        lockStates.remove(playerUuid);
    }

    public void clearAll() {
        lockStates.clear();
    }
}
