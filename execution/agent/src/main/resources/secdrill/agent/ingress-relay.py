"""Local-trusted ingress relay (ADR 0007, T07).

Docker Desktop cannot route from the host into an --internal network, so each local-trusted Lab gets this relay: it
listens on the published loopback port and forwards every connection to the fixed upstream app:8080 on the Lab
network. It never chooses a destination from client input and logs nothing. Not used by the strong runtime.
"""

import socket
import threading

UPSTREAM = ("app", 8080)


def pump(source, target):
    try:
        while True:
            data = source.recv(65536)
            if not data:
                break
            target.sendall(data)
    except OSError:
        pass
    finally:
        for sock in (source, target):
            try:
                sock.shutdown(socket.SHUT_RDWR)
            except OSError:
                pass


def serve(client):
    try:
        upstream = socket.create_connection(UPSTREAM, timeout=5)
    except OSError:
        client.close()
        return
    upstream.settimeout(None)
    threading.Thread(target=pump, args=(client, upstream), daemon=True).start()
    pump(upstream, client)
    client.close()
    upstream.close()


listener = socket.create_server(("0.0.0.0", 8080), reuse_port=False)
while True:
    connection, _ = listener.accept()
    threading.Thread(target=serve, args=(connection,), daemon=True).start()
