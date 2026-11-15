package seeyuer.sayin.helperfixture;

import android.app.Instrumentation;
import android.os.Bundle;
import android.net.LocalSocket;
import android.net.LocalSocketAddress;

/** No clipboard data: tests whether untrusted_app can reach the Root socket domain. */
public final class SocketProbe extends Instrumentation {
    @Override public void onCreate(Bundle args) {
        super.onCreate(args);
        Bundle result = new Bundle();
        try (LocalSocket socket = new LocalSocket()) {
            String name = args.getString("socket", "");
            if (!name.startsWith("SAYIN_TEST_") || name.length() > 80) throw new IllegalArgumentException();
            socket.connect(new LocalSocketAddress(name, LocalSocketAddress.Namespace.ABSTRACT));
            socket.setSoTimeout(3000);
            if ("deny".equals(args.getString("mode"))) {
                java.io.DataInputStream input = new java.io.DataInputStream(socket.getInputStream());
                int size = Integer.reverseBytes(input.readInt());
                if (size < 1 || size > 1024) throw new IllegalStateException("frame");
                byte[] bytes = new byte[size]; input.readFully(bytes);
                String reply = new String(bytes, java.nio.charset.StandardCharsets.UTF_8);
                if (!reply.equals("{\"error\":\"client_not_authorized\"}")) throw new IllegalStateException("unauthorized client was not denied");
            } else {
                byte[] reply = new byte[2];
                if (socket.getInputStream().read(reply) != 2 || reply[0] != 'O' || reply[1] != 'K') throw new IllegalStateException();
            }
            result.putString("socket_probe", "pass"); finish(0, result);
        } catch (Exception e) {
            result.putString("socket_probe", e.getClass().getSimpleName() + ": " + e.getMessage()); finish(1,result);
        }
    }
}
