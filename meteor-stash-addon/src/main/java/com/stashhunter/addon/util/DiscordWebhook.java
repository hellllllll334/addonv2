package com.stashhunter.addon.util;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;

import java.io.ByteArrayOutputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Map;

public class DiscordWebhook {
    private static final HttpClient CLIENT = HttpClient.newHttpClient();

    /** Blocking. Call from a background thread. png may be null. */
    public static void send(String url, String title, Map<String, String> fields, byte[] png) {
        try {
            JsonObject embed = new JsonObject();
            embed.addProperty("title", title);
            embed.addProperty("color", 0x57F287);
            JsonArray arr = new JsonArray();
            for (Map.Entry<String, String> e : new LinkedHashMap<>(fields).entrySet()) {
                JsonObject f = new JsonObject();
                f.addProperty("name", e.getKey());
                f.addProperty("value", e.getValue());
                f.addProperty("inline", true);
                arr.add(f);
            }
            embed.add("fields", arr);
            if (png != null) {
                JsonObject img = new JsonObject();
                img.addProperty("url", "attachment://stash.png");
                embed.add("image", img);
            }
            JsonObject payload = new JsonObject();
            JsonArray embeds = new JsonArray();
            embeds.add(embed);
            payload.add("embeds", embeds);

            HttpRequest.Builder req = HttpRequest.newBuilder(URI.create(url));
            if (png == null) {
                req.header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(payload.toString(), StandardCharsets.UTF_8));
            } else {
                String b = "----stashhunter" + System.nanoTime();
                ByteArrayOutputStream out = new ByteArrayOutputStream();
                out.write(("--" + b + "\r\nContent-Disposition: form-data; name=\"payload_json\"\r\n"
                    + "Content-Type: application/json\r\n\r\n" + payload + "\r\n").getBytes(StandardCharsets.UTF_8));
                out.write(("--" + b + "\r\nContent-Disposition: form-data; name=\"files[0]\"; filename=\"stash.png\"\r\n"
                    + "Content-Type: image/png\r\n\r\n").getBytes(StandardCharsets.UTF_8));
                out.write(png);
                out.write(("\r\n--" + b + "--\r\n").getBytes(StandardCharsets.UTF_8));
                req.header("Content-Type", "multipart/form-data; boundary=" + b)
                    .POST(HttpRequest.BodyPublishers.ofByteArray(out.toByteArray()));
            }
            HttpResponse<String> res = CLIENT.send(req.build(), HttpResponse.BodyHandlers.ofString());
            if (res.statusCode() >= 300) System.err.println("[StashHunter] Webhook failed: " + res.statusCode() + " " + res.body());
        } catch (Exception ex) {
            System.err.println("[StashHunter] Webhook error: " + ex);
        }
    }
}
