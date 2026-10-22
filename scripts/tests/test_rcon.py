from pathlib import Path
import struct
import sys
import unittest
from unittest import mock

sys.path.insert(0, str(Path(__file__).resolve().parents[1] / "integration"))
from harness import ProbeError, Server


def packet(request_id, kind, body=""):
    payload = struct.pack("<ii", request_id, kind) + body.encode() + b"\0\0"
    return struct.pack("<i", len(payload)) + payload


class RconTest(unittest.TestCase):
    def connection(self, data):
        connection = mock.MagicMock()
        connection.__enter__.return_value = connection
        chunks = bytearray(data)

        def receive(length):
            # A TCP read may split any header or body, even into individual bytes.
            result = bytes(chunks[:min(length, 1)])
            del chunks[:len(result)]
            return result

        connection.recv.side_effect = receive
        return connection

    def test_split_tcp_packets_authenticate_then_execute(self):
        connection = self.connection(packet(1, 2) + packet(2, 0, "Teleported"))
        server = Server("unused", "unused", [], port=25600)
        with mock.patch("harness.socket.create_connection", return_value=connection) as connect:
            self.assertEqual(server.rcon("tp target 0 70 0"), "Teleported")
        connect.assert_called_once_with(("127.0.0.1", 25601), timeout=10)
        self.assertEqual(connection.sendall.call_count, 2)

    def test_rejected_authentication_never_sends_command(self):
        connection = self.connection(packet(-1, 2))
        with mock.patch("harness.socket.create_connection", return_value=connection):
            with self.assertRaisesRegex(ProbeError, "authentication failed"):
                Server("unused", "unused", []).rcon("tp target 0 70 0")
        self.assertEqual(connection.sendall.call_count, 1)

    def test_truncated_and_oversized_responses_fail_boundedly(self):
        for data in (b"\x01", struct.pack("<i", 8 * 1024 * 1024)):
            with self.subTest(data=data):
                with mock.patch("harness.socket.create_connection", return_value=self.connection(data)):
                    with self.assertRaises(ProbeError):
                        Server("unused", "unused", []).rcon("list")


if __name__ == "__main__":
    unittest.main()
