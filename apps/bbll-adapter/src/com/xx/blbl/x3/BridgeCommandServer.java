package com.xx.blbl.x3;

import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothServerSocket;
import android.bluetooth.BluetoothSocket;
import android.os.Handler;
import android.os.Looper;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.Charset;
import java.util.Locale;
import java.util.UUID;

final class BridgeCommandServer {
    private static final String SERVICE_NAME = "BBLL_X3pro_Bridge";
    private static final UUID SERVICE_UUID =
            UUID.fromString("6f7d8f3e-67f9-4e8a-bb54-7c94145d3f91");
    private static final Charset UTF8 = Charset.forName("UTF-8");
    private static final Handler MAIN_HANDLER = new Handler(Looper.getMainLooper());

    private final Object lock = new Object();

    private AcceptThread acceptThread;
    private ClientThread clientThread;
    private boolean running;

    void start() {
        synchronized (lock) {
            if (running) {
                return;
            }
            running = true;
            acceptThread = new AcceptThread();
            acceptThread.start();
        }
    }

    void stop() {
        AcceptThread localAccept;
        ClientThread localClient;
        synchronized (lock) {
            running = false;
            localAccept = acceptThread;
            localClient = clientThread;
            acceptThread = null;
            clientThread = null;
        }
        if (localAccept != null) {
            localAccept.closeQuietly();
        }
        if (localClient != null) {
            localClient.closeQuietly();
        }
    }

    private void onClientConnected(BluetoothSocket socket) {
        ClientThread previous;
        ClientThread next = new ClientThread(socket);
        synchronized (lock) {
            previous = clientThread;
            clientThread = next;
        }
        if (previous != null) {
            previous.closeQuietly();
        }
        next.start();
    }

    private void onClientClosed(ClientThread thread) {
        synchronized (lock) {
            if (clientThread == thread) {
                clientThread = null;
            }
        }
    }

    private void dispatchCommand(final String line) {
        if (line == null) {
            return;
        }
        final String command = line.trim();
        if (command.length() == 0) {
            return;
        }
        MAIN_HANDLER.post(
                new Runnable() {
                    @Override
                    public void run() {
                        applyCommand(command);
                    }
                });
    }

    private void applyCommand(String command) {
        String[] parts = command.split("\\s+");
        if (parts.length == 0) {
            return;
        }
        String op = parts[0].toUpperCase(Locale.US);
        if ("MOVE".equals(op) && parts.length >= 3) {
            try {
                float dx = Float.parseFloat(parts[1]);
                float dy = Float.parseFloat(parts[2]);
                RayneoMirrorHelper.applyBridgeMove(dx, dy);
            } catch (Throwable ignored) {
            }
            return;
        }
        if ("CLICK".equals(op)) {
            RayneoMirrorHelper.applyBridgeClick();
            return;
        }
        if ("BACK".equals(op)) {
            RayneoMirrorHelper.applyBridgeBack();
            return;
        }
        if ("SHOW".equals(op)) {
            RayneoMirrorHelper.applyBridgeShowCursor();
        }
    }

    private final class AcceptThread extends Thread {
        private BluetoothServerSocket serverSocket;

        AcceptThread() {
            super("BBLL-Bridge-Accept");
        }

        @Override
        public void run() {
            BluetoothAdapter adapter = BluetoothAdapter.getDefaultAdapter();
            if (adapter == null) {
                return;
            }
            try {
                serverSocket = adapter.listenUsingRfcommWithServiceRecord(SERVICE_NAME, SERVICE_UUID);
            } catch (Throwable ignored) {
                return;
            }

            while (true) {
                synchronized (lock) {
                    if (!running) {
                        break;
                    }
                }

                try {
                    BluetoothSocket socket = serverSocket.accept();
                    if (socket != null) {
                        onClientConnected(socket);
                    }
                } catch (Throwable ignored) {
                    synchronized (lock) {
                        if (!running) {
                            break;
                        }
                    }
                }
            }
            closeQuietly();
        }

        void closeQuietly() {
            if (serverSocket == null) {
                return;
            }
            try {
                serverSocket.close();
            } catch (IOException ignored) {
            }
        }
    }

    private final class ClientThread extends Thread {
        private final BluetoothSocket socket;

        ClientThread(BluetoothSocket socket) {
            super("BBLL-Bridge-Client");
            this.socket = socket;
        }

        @Override
        public void run() {
            BufferedReader reader = null;
            try {
                reader =
                        new BufferedReader(
                                new InputStreamReader(socket.getInputStream(), UTF8));
                String line;
                while ((line = reader.readLine()) != null) {
                    dispatchCommand(line);
                }
            } catch (Throwable ignored) {
            } finally {
                if (reader != null) {
                    try {
                        reader.close();
                    } catch (IOException ignored) {
                    }
                }
                closeQuietly();
                onClientClosed(this);
            }
        }

        void closeQuietly() {
            try {
                socket.close();
            } catch (IOException ignored) {
            }
        }
    }
}
