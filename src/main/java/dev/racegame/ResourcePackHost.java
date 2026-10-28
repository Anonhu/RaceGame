package dev.racegame;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.minestom.server.entity.Player;
import net.minestom.server.network.packet.server.common.ResourcePackPushPacket;

import java.io.BufferedReader;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.InputStreamReader;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/** Serves the bundled resource pack and offers it to connected Minecraft clients. */
public final class ResourcePackHost {
    private static final String PACK_PATH = "/chroma-rift-pack.zip";
    private static final String HTTP_PATH = "/chroma-rift-pack.zip";
    private static final UUID PACK_ID = UUID.nameUUIDFromBytes("chroma-rift-pack-v2".getBytes(StandardCharsets.UTF_8));
    private static final Set<UUID> OFFERED_PLAYERS = ConcurrentHashMap.newKeySet();
    private static volatile ResourcePackHost active;

    private final byte[] pack;
    private final String sha1;
    private final String advertisedUrl;
    private final ServerSocket socket;

    private ResourcePackHost(byte[] pack, String sha1, String advertisedUrl, ServerSocket socket) {
        this.pack = pack;
        this.sha1 = sha1;
        this.advertisedUrl = advertisedUrl;
        this.socket = socket;
    }

    public static void start() {
        try {
            byte[] pack = readPack();
            String sha1 = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-1").digest(pack));
            String advertisedUrl = System.getenv().getOrDefault(
                    "MINESTOM_RESOURCE_PACK_URL", "http://localhost:25566/chroma-rift-pack.zip");
            ServerSocket socket = bindPackSocket();
            ResourcePackHost host = new ResourcePackHost(pack, sha1, advertisedUrl, socket);
            active = host;
            if (socket != null) {
                Thread.ofVirtual().name("chroma-rift-resource-pack").start(host::serve);
                System.out.println("Resource pack host listening on " + socket.getLocalSocketAddress());
            }
            System.out.println("Resource pack URL advertised to clients: " + advertisedUrl);
        } catch (IOException | NoSuchAlgorithmException exception) {
            System.err.println("Could not prepare CHROMA RIFT resource pack: " + exception.getMessage());
        }
    }

    public static void sendTo(Player player) {
        ResourcePackHost host = active;
        if (host == null || !OFFERED_PLAYERS.add(player.getUuid())) return;
        player.sendPacket(new ResourcePackPushPacket(
                PACK_ID,
                host.advertisedUrl,
                host.sha1,
                false,
                Component.text("Загрузить кастомные модели, ливреи и эффекты CHROMA RIFT?", NamedTextColor.AQUA)));
    }

    public static void forget(Player player) {
        OFFERED_PLAYERS.remove(player.getUuid());
    }

    private static byte[] readPack() throws IOException {
        try (InputStream input = ResourcePackHost.class.getResourceAsStream(PACK_PATH)) {
            if (input == null) throw new IOException("resource pack archive is missing from the application resources");
            ByteArrayOutputStream output = new ByteArrayOutputStream();
            input.transferTo(output);
            return output.toByteArray();
        }
    }

    private static ServerSocket bindPackSocket() {
        String bindHost = System.getenv().getOrDefault("MINESTOM_PACK_HOST", "0.0.0.0");
        int port;
        try {
            port = Integer.parseInt(System.getenv().getOrDefault("MINESTOM_PACK_PORT", "25566"));
        } catch (NumberFormatException exception) {
            System.err.println("Invalid MINESTOM_PACK_PORT; the built-in resource pack host is disabled.");
            return null;
        }
        if (port <= 0 || port > 65_535) {
            System.out.println("Built-in resource pack host disabled by MINESTOM_PACK_PORT.");
            return null;
        }

        try {
            ServerSocket socket = new ServerSocket();
            socket.setReuseAddress(true);
            socket.bind(new InetSocketAddress(bindHost, port), 32);
            return socket;
        } catch (IOException exception) {
            System.err.println("Could not bind resource pack host on " + bindHost + ":" + port
                    + " (set MINESTOM_RESOURCE_PACK_URL to an externally hosted pack): " + exception.getMessage());
            return null;
        }
    }

    private void serve() {
        while (!socket.isClosed()) {
            try {
                Socket client = socket.accept();
                Thread.ofVirtual().name("chroma-rift-pack-request").start(() -> handle(client));
            } catch (IOException exception) {
                if (!socket.isClosed()) {
                    System.err.println("Resource pack host stopped: " + exception.getMessage());
                }
                return;
            }
        }
    }

    private void handle(Socket client) {
        try (client) {
            client.setSoTimeout(5_000);
            BufferedReader input = new BufferedReader(new InputStreamReader(client.getInputStream(), StandardCharsets.US_ASCII));
            String request = input.readLine();
            String header;
            while ((header = input.readLine()) != null && !header.isEmpty()) {
                // Drain request headers before writing the response.
            }

            String[] parts = request == null ? new String[0] : request.split(" ", 3);
            boolean headRequest = parts.length >= 2 && parts[0].equals("HEAD");
            boolean packRequest = parts.length >= 2
                    && (parts[0].equals("GET") || headRequest)
                    && parts[1].split("\\?", 2)[0].equals(HTTP_PATH);
            if (!packRequest) {
                writeResponse(client.getOutputStream(), "404 Not Found", "text/plain; charset=utf-8", new byte[0], true);
                return;
            }
            writeResponse(client.getOutputStream(), "200 OK", "application/zip", pack, headRequest);
        } catch (IOException exception) {
            // Clients can cancel a pack download without affecting the game server.
        }
    }

    private static void writeResponse(OutputStream output, String status, String contentType, byte[] body, boolean headOnly)
            throws IOException {
        String headers = "HTTP/1.1 " + status + "\r\n"
                + "Content-Type: " + contentType + "\r\n"
                + "Content-Length: " + body.length + "\r\n"
                + "Cache-Control: no-cache\r\n"
                + "Connection: close\r\n\r\n";
        output.write(headers.getBytes(StandardCharsets.US_ASCII));
        if (!headOnly) output.write(body);
        output.flush();
    }
}
