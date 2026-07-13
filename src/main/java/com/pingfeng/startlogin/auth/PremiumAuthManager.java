package com.pingfeng.startlogin.auth;

import com.pingfeng.startlogin.StartLogin;
import com.pingfeng.startlogin.config.ConfigManager;
import com.pingfeng.startlogin.thread.ThreadPoolManager;
import net.raphimc.mcauth.MinecraftAuth;
import net.raphimc.mcauth.step.java.StepMCProfile;
import net.raphimc.mcauth.step.msa.StepMsaDeviceCode;
import com.google.gson.Gson;
import com.google.gson.JsonObject;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Consumer;

public class PremiumAuthManager {

    private final StartLogin plugin;
    private final ConfigManager configManager;
    private final ThreadPoolManager threadPoolManager;

    private final Gson gson;

    private final Map<String, DeviceLoginSession> deviceLoginSessions;
    private final Map<String, ProfileCacheEntry> profileCache;
    private final Map<String, SessionVerifyCacheEntry> sessionVerifyCache;

    public PremiumAuthManager(StartLogin plugin, ConfigManager configManager, ThreadPoolManager threadPoolManager) {
        this.plugin = plugin;
        this.configManager = configManager;
        this.threadPoolManager = threadPoolManager;
        this.deviceLoginSessions = new ConcurrentHashMap<>();
        this.profileCache = new ConcurrentHashMap<>();
        this.sessionVerifyCache = new ConcurrentHashMap<>();
        this.gson = new Gson();

        MinecraftAuth.LOGGER = new net.raphimc.mcauth.util.logging.ILogger() {
            @Override
            public void info(String s) {}
            @Override
            public void warn(String s) {}
            @Override
            public void error(String s) {}
        };
    }

    public void startDeviceLogin(DeviceLoginStartCallback callback) {
        threadPoolManager.execute(() -> {
            try {
                org.apache.http.impl.client.CloseableHttpClient httpClient = org.apache.http.impl.client.HttpClients.createDefault();

                Consumer<StepMsaDeviceCode.MsaDeviceCode> deviceCodeConsumer = new Consumer<StepMsaDeviceCode.MsaDeviceCode>() {
                    @Override
                    public void accept(StepMsaDeviceCode.MsaDeviceCode msaDeviceCode) {
                        callback.onDeviceCode(msaDeviceCode.userCode(), msaDeviceCode.verificationUri());
                    }
                };

                StepMsaDeviceCode.MsaDeviceCodeCallback initialInput = new StepMsaDeviceCode.MsaDeviceCodeCallback(deviceCodeConsumer);

                StepMCProfile.MCProfile profile = MinecraftAuth.JAVA_DEVICE_CODE_LOGIN.getFromInput(
                        httpClient,
                        initialInput
                );

                httpClient.close();

                if (profile == null) {
                    callback.onFailure("验证失败");
                    return;
                }

                if (configManager.isVerboseLogging()) {
                    plugin.getLogger().info("设备码登录成功: " + profile.name());
                }

                String mcToken = "";
                long mcTokenExpires = 0;
                String refreshToken = "";

                try {
                    Object prev = profile.prevResult();
                    while (prev != null) {
                        Class<?> clazz = prev.getClass();
                        try {
                            java.lang.reflect.Method accessTokenMethod = clazz.getMethod("access_token");
                            Object accessTokenResult = accessTokenMethod.invoke(prev);
                            if (accessTokenResult != null) {
                                mcToken = accessTokenResult.toString();
                            }
                            java.lang.reflect.Method expireMethod = clazz.getMethod("expireTimeMs");
                            Object expireResult = expireMethod.invoke(prev);
                            if (expireResult != null) {
                                mcTokenExpires = ((Number) expireResult).longValue();
                            }
                            java.lang.reflect.Method refreshMethod = clazz.getMethod("refresh_token");
                            Object refreshResult = refreshMethod.invoke(prev);
                            if (refreshResult != null) {
                                refreshToken = refreshResult.toString();
                            }
                        } catch (Exception ignored) {}
                        try {
                            java.lang.reflect.Method prevResultMethod = clazz.getMethod("prevResult");
                            prev = prevResultMethod.invoke(prev);
                        } catch (Exception e) {
                            break;
                        }
                    }
                } catch (Exception ignored) {}

                callback.onSuccess(profile.id(), profile.name(), mcToken, refreshToken, mcTokenExpires);
            } catch (Exception e) {
                plugin.getLogger().severe("设备码登录失败: " + e.getMessage());
                if (configManager.isVerboseLogging()) {
                    e.printStackTrace();
                }
                callback.onFailure("登录失败: " + e.getMessage());
            }
        });
    }

    public void getMinecraftProfile(String mcToken, MinecraftProfileCallback callback) {
        ProfileCacheEntry cached = profileCache.get(mcToken);
        if (cached != null && !cached.isExpired(configManager.getPremiumCacheDuration())) {
            if (configManager.isVerboseLogging()) {
                plugin.getLogger().info("使用缓存的玩家档案: " + cached.name);
            }
            callback.onSuccess(cached.uuid, cached.name);
            return;
        }

        threadPoolManager.execute(() -> {
            try {
                URL url = new URL("https://api.minecraftservices.com/minecraft/profile");
                HttpURLConnection conn = (HttpURLConnection) url.openConnection();
                conn.setRequestMethod("GET");
                conn.setRequestProperty("Authorization", "Bearer " + mcToken);
                conn.setConnectTimeout(configManager.getPremiumTimeout());
                conn.setReadTimeout(configManager.getPremiumTimeout());

                int responseCode = conn.getResponseCode();
                if (responseCode == 200) {
                    BufferedReader reader = new BufferedReader(new InputStreamReader(conn.getInputStream(), StandardCharsets.UTF_8));
                    StringBuilder response = new StringBuilder();
                    String line;
                    while ((line = reader.readLine()) != null) {
                        response.append(line);
                    }
                    reader.close();

                    JsonObject json = gson.fromJson(response.toString(), JsonObject.class);
                    if (json.has("id") && json.has("name")) {
                        String idStr = json.get("id").getAsString();
                        UUID uuid = UUID.fromString(
                                idStr.substring(0, 8) + "-" +
                                idStr.substring(8, 12) + "-" +
                                idStr.substring(12, 16) + "-" +
                                idStr.substring(16, 20) + "-" +
                                idStr.substring(20)
                        );
                        String name = json.get("name").getAsString();

                        profileCache.put(mcToken, new ProfileCacheEntry(uuid, name));

                        if (configManager.isVerboseLogging()) {
                            plugin.getLogger().info("获取玩家档案成功: " + name);
                        }

                        callback.onSuccess(uuid, name);
                        return;
                    }
                }
                callback.onFailure("获取玩家档案失败: HTTP " + responseCode);
            } catch (Exception e) {
                plugin.getLogger().severe("获取玩家档案失败: " + e.getMessage());
                if (configManager.isVerboseLogging()) {
                    e.printStackTrace();
                }
                callback.onFailure("获取玩家档案失败: " + e.getMessage());
            }
        });
    }

    public void verifyMinecraftSession(String username, String serverId, String ip, SessionVerifyCallback callback) {
        String cacheKey = username + ":" + serverId + ":" + ip;
        SessionVerifyCacheEntry cached = sessionVerifyCache.get(cacheKey);
        if (cached != null && !cached.isExpired(configManager.getPremiumCacheDuration())) {
            if (configManager.isVerboseLogging()) {
                plugin.getLogger().info("使用缓存的会话验证结果: " + username);
            }
            callback.onResult(cached.valid, cached.uuid, cached.name);
            return;
        }

        threadPoolManager.execute(() -> {
            boolean valid = false;
            UUID uuid = null;
            String name = null;

            int maxRetries = configManager.getPremiumMaxRetries();
            int attempt = 0;
            Exception lastException = null;

            while (attempt < maxRetries && !valid) {
                attempt++;
                try {
                    String response = hasJoinedServer(username, serverId, ip);
                    if (response != null && !response.isEmpty()) {
                        JsonObject json = gson.fromJson(response, JsonObject.class);
                        if (json.has("id") && json.has("name")) {
                            valid = true;
                            String idStr = json.get("id").getAsString();
                            uuid = UUID.fromString(
                                    idStr.substring(0, 8) + "-" +
                                    idStr.substring(8, 12) + "-" +
                                    idStr.substring(12, 16) + "-" +
                                    idStr.substring(16, 20) + "-" +
                                    idStr.substring(20)
                            );
                            name = json.get("name").getAsString();

                            if (configManager.isVerboseLogging()) {
                                plugin.getLogger().info("会话验证成功: " + username);
                            }
                        }
                    } else {
                        if (configManager.isVerboseLogging()) {
                            plugin.getLogger().info("会话验证失败: " + username);
                        }
                        break;
                    }
                } catch (Exception e) {
                    lastException = e;
                    plugin.getLogger().warning("会话验证失败 (尝试 " + attempt + "/" + maxRetries + "): " + e.getMessage());
                    if (configManager.isVerboseLogging()) {
                        e.printStackTrace();
                    }
                    if (attempt < maxRetries) {
                        try {
                            Thread.sleep(500L * attempt);
                        } catch (InterruptedException ie) {
                            Thread.currentThread().interrupt();
                            break;
                        }
                    }
                }
            }

            if (!valid && lastException != null) {
                plugin.getLogger().severe("会话验证最终失败: " + username + ", 错误: " + lastException.getMessage());
            }

            sessionVerifyCache.put(cacheKey, new SessionVerifyCacheEntry(valid, uuid, name));
            callback.onResult(valid, uuid, name);
        });
    }

    private String hasJoinedServer(String username, String serverId, String ip) throws Exception {
        StringBuilder urlBuilder = new StringBuilder("https://sessionserver.mojang.com/session/minecraft/hasJoined?");
        urlBuilder.append("username=").append(URLEncoder.encode(username, StandardCharsets.UTF_8));
        urlBuilder.append("&serverId=").append(URLEncoder.encode(serverId, StandardCharsets.UTF_8));
        if (ip != null && !ip.isEmpty()) {
            urlBuilder.append("&ip=").append(URLEncoder.encode(ip, StandardCharsets.UTF_8));
        }

        URL url = new URL(urlBuilder.toString());
        HttpURLConnection conn = (HttpURLConnection) url.openConnection();
        conn.setRequestMethod("GET");
        conn.setConnectTimeout(configManager.getPremiumTimeout());
        conn.setReadTimeout(configManager.getPremiumTimeout());
        conn.setRequestProperty("User-Agent", "StartLogin/" + plugin.getDescription().getVersion());
        conn.setRequestProperty("Accept", "application/json");

        int responseCode = conn.getResponseCode();
        if (responseCode == 200) {
            BufferedReader reader = new BufferedReader(new InputStreamReader(conn.getInputStream(), StandardCharsets.UTF_8));
            StringBuilder response = new StringBuilder();
            String line;
            while ((line = reader.readLine()) != null) {
                response.append(line);
            }
            reader.close();
            return response.toString();
        } else if (responseCode == 204) {
            return "";
        } else if (responseCode == 429) {
            throw new RuntimeException("请求过于频繁 (HTTP 429)");
        } else {
            throw new RuntimeException("HTTP " + responseCode);
        }
    }

    public void clearCache() {
        profileCache.clear();
        sessionVerifyCache.clear();
        deviceLoginSessions.clear();
        if (configManager.isVerboseLogging()) {
            plugin.getLogger().info("正版验证缓存已清空");
        }
    }

    public void cleanupExpiredCache() {
        long cacheDuration = configManager.getPremiumCacheDuration() * 60 * 1000L;
        long now = System.currentTimeMillis();

        profileCache.entrySet().removeIf(entry -> now - entry.getValue().cachedAt > cacheDuration);
        sessionVerifyCache.entrySet().removeIf(entry -> now - entry.getValue().cachedAt > cacheDuration);
        deviceLoginSessions.entrySet().removeIf(entry -> entry.getValue().expired());

        if (configManager.isVerboseLogging()) {
            plugin.getLogger().info("已清理过期缓存");
        }
    }

    private static class DeviceLoginSession {
        StepMsaDeviceCode.MsaDeviceCode deviceCode;
        long createdAt;

        boolean expired() {
            if (deviceCode != null) {
                try {
                    return deviceCode.isExpired();
                } catch (Exception e) {
                    return System.currentTimeMillis() - createdAt > 15 * 60 * 1000L;
                }
            }
            return System.currentTimeMillis() - createdAt > 15 * 60 * 1000L;
        }
    }

    private static class ProfileCacheEntry {
        final UUID uuid;
        final String name;
        final long cachedAt;

        ProfileCacheEntry(UUID uuid, String name) {
            this.uuid = uuid;
            this.name = name;
            this.cachedAt = System.currentTimeMillis();
        }

        boolean isExpired(int cacheMinutes) {
            return System.currentTimeMillis() - cachedAt > cacheMinutes * 60 * 1000L;
        }
    }

    private static class SessionVerifyCacheEntry {
        final boolean valid;
        final UUID uuid;
        final String name;
        final long cachedAt;

        SessionVerifyCacheEntry(boolean valid, UUID uuid, String name) {
            this.valid = valid;
            this.uuid = uuid;
            this.name = name;
            this.cachedAt = System.currentTimeMillis();
        }

        boolean isExpired(int cacheMinutes) {
            return System.currentTimeMillis() - cachedAt > cacheMinutes * 60 * 1000L;
        }
    }

    public interface DeviceLoginStartCallback {
        void onDeviceCode(String userCode, String verificationUrl);
        void onSuccess(UUID uuid, String username, String mcToken, String refreshToken, long accessTokenExpires);
        void onFailure(String error);
    }

    public interface MinecraftProfileCallback {
        void onSuccess(UUID uuid, String username);
        void onFailure(String error);
    }

    public interface SessionVerifyCallback {
        void onResult(boolean valid, UUID uuid, String username);
    }
}
