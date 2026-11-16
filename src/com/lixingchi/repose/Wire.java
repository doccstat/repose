package com.lixingchi.repose;

import android.net.LocalSocket;
import android.net.LocalSocketAddress;
import java.io.*;
import java.nio.charset.StandardCharsets;
import org.json.JSONObject;

public final class Wire {
    static final String SOCKET = "com.lixingchi.repose.engine.v1";
    static final int LIMIT = 32768;
    public static JSONObject read(InputStream stream) throws Exception {
        DataInputStream input = new DataInputStream(stream);
        int size = input.readInt();
        if (size < 2 || size > LIMIT) throw new IOException("invalid_frame");
        byte[] bytes = new byte[size]; input.readFully(bytes);
        return new JSONObject(new String(bytes, StandardCharsets.UTF_8));
    }
    public static void write(OutputStream stream, JSONObject message) throws Exception {
        byte[] bytes = message.toString().getBytes(StandardCharsets.UTF_8);
        if (bytes.length > LIMIT) throw new IOException("invalid_frame");
        DataOutputStream output = new DataOutputStream(stream);
        output.writeInt(bytes.length); output.write(bytes); output.flush();
    }
    public static JSONObject call(JSONObject message) throws Exception {
        try (LocalSocket socket = new LocalSocket()) {
            socket.connect(new LocalSocketAddress(SOCKET));
            socket.setSoTimeout(5000);
            if (socket.getPeerCredentials().getUid() != 0) throw new SecurityException("untrusted_engine");
            write(socket.getOutputStream(), message);
            JSONObject reply = read(socket.getInputStream());
            if (!reply.optBoolean("ok")) throw new IOException(reply.optString("error", "engine_error"));
            return reply;
        }
    }
    private Wire() { }
}
