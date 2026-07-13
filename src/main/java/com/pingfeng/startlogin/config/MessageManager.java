package com.pingfeng.startlogin.config;

import com.pingfeng.startlogin.StartLogin;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.minimessage.MiniMessage;
import net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.configuration.file.YamlConfiguration;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;

public class MessageManager {

    private final StartLogin plugin;
    private final MiniMessage miniMessage;
    private FileConfiguration messages;
    private File messageFile;

    public MessageManager(StartLogin plugin) {
        this.plugin = plugin;
        this.miniMessage = MiniMessage.miniMessage();
    }

    public void loadMessages() {
        messageFile = new File(plugin.getDataFolder(), "message.yml");
        if (!messageFile.exists()) {
            plugin.saveResource("message.yml", false);
        }
        messages = YamlConfiguration.loadConfiguration(messageFile);
        setDefaults();
    }

    private void setDefaults() {
        InputStream defaultStream = plugin.getResource("message.yml");
        if (defaultStream != null) {
            YamlConfiguration defaultConfig = YamlConfiguration.loadConfiguration(
                    new InputStreamReader(defaultStream, StandardCharsets.UTF_8));
            messages.setDefaults(defaultConfig);
            messages.options().copyDefaults(true);
            saveMessages();
        }
    }

    public void reloadMessages() {
        if (messageFile == null) {
            messageFile = new File(plugin.getDataFolder(), "message.yml");
        }
        messages = YamlConfiguration.loadConfiguration(messageFile);
        setDefaults();
    }

    public void saveMessages() {
        if (messages == null || messageFile == null) {
            return;
        }
        try {
            messages.save(messageFile);
        } catch (IOException e) {
            plugin.getLogger().severe("保存消息配置失败: " + e.getMessage());
        }
    }

    public String getString(String path) {
        String value = messages.getString(path);
        if (value == null) {
            InputStream defaultStream = plugin.getResource("message.yml");
            if (defaultStream != null) {
                YamlConfiguration defaultConfig = YamlConfiguration.loadConfiguration(
                        new InputStreamReader(defaultStream, StandardCharsets.UTF_8));
                value = defaultConfig.getString(path, "");
            }
        }
        return value != null ? value : "";
    }

    public Component getMessage(String path) {
        String text = getString(path);
        return parseMiniMessage(text);
    }

    public Component getMessage(String path, String... replacements) {
        String text = getString(path);
        for (int i = 0; i < replacements.length - 1; i += 2) {
            String key = replacements[i];
            String value = replacements[i + 1];
            text = text.replace("{" + key + "}", value);
        }
        return parseMiniMessage(text);
    }

    public String getRawText(String path) {
        return getString(path);
    }

    public String getPlainMessage(String path) {
        return getString(path);
    }

    public String getPlainMessage(String path, String... replacements) {
        String text = getString(path);
        for (int i = 0; i < replacements.length - 1; i += 2) {
            String key = replacements[i];
            String value = replacements[i + 1];
            text = text.replace("{" + key + "}", value);
        }
        return text;
    }

    public String getPlainText(String path) {
        Component component = getMessage(path);
        return LegacyComponentSerializer.legacySection().serialize(component);
    }

    public Component getDialogText(String path) {
        String text = getString(path);
        text = normalizeLineBreaks(text);
        return parseMiniMessage(text);
    }

    private String normalizeLineBreaks(String text) {
        if (text == null) {
            return "";
        }
        return text.replace("\\n", "\n").replace("\\r\\n", "\n").replace("\r\n", "\n");
    }

    private Component parseMiniMessage(String text) {
        if (text == null || text.isEmpty()) {
            return Component.empty();
        }
        try {
            return miniMessage.deserialize(text);
        } catch (Exception e) {
            plugin.getLogger().warning("MiniMessage解析失败，使用纯文本: " + e.getMessage());
            return Component.text(stripMiniMessageTags(text));
        }
    }

    private String stripMiniMessageTags(String text) {
        return text.replaceAll("<[^>]*>", "");
    }

    public String getPrefix() {
        return getString("general.prefix");
    }
}
