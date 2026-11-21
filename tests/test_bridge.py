import importlib.util
from pathlib import Path
import unittest
from unittest.mock import patch
import threading
import json
import urllib.request
import urllib.error
import http.client
import socketserver
from http.server import ThreadingHTTPServer

spec = importlib.util.spec_from_file_location('bridge', Path(__file__).parents[1] / 'quota_bridge.py')
b = importlib.util.module_from_spec(spec)
spec.loader.exec_module(b)

class Stop:
    def __init__(self): self.delays=[]
    def is_set(self): return len(self.delays)>=2
    def wait(self, seconds):
        self.delays.append(seconds)
        return self.is_set()

class BridgeTest(unittest.TestCase):
    def test_normalize_does_not_invent_windows(self):
        r=b.normalize({'rateLimits':{'primary':{'windowDurationMins':300,'usedPercent':27},'secondary':{'windowDurationMins':10080,'usedPercent':85}}})
        self.assertEqual(r['fiveHour']['remainingPercent'],73)
        self.assertEqual(r['weekly']['remainingPercent'],15)
        self.assertIsNone(b.normalize({'rateLimits':{'primary':{'windowDurationMins':60,'usedPercent':2}}})['fiveHour'])

    def test_retry_then_quota_stays_live_when_optional_usage_fails(self):
        state=b.State(); state.stop=Stop()
        attempts=[]
        class RPC:
            def __init__(self): attempts.append(self)
            def call(self, method):
                if len(attempts)==1: raise OSError('transient')
                if method=='account/usage/read':
                    assert state.data['lastUpdated'] is not None
                    raise RuntimeError('unsupported')
                return {'rateLimits':{'primary':{'windowDurationMins':300,'usedPercent':20}}}
            def close(self): pass
        with patch.object(b,'RPC',RPC): state.poll()
        self.assertEqual(state.stop.delays,[2,45])
        self.assertEqual(state.data['fiveHour']['remainingPercent'],80)
        self.assertFalse(state.data['stale'])
        self.assertIsNone(state.data['lifetimeTokens'])

    def test_http_requires_pairing_key(self):
        state=b.State()
        server=ThreadingHTTPServer(('127.0.0.1',0),b.make_handler(state,'unit-test-only'))
        worker=threading.Thread(target=server.serve_forever,daemon=True);worker.start()
        url='http://127.0.0.1:%d/quota'%server.server_port
        try:
            with self.assertRaises(urllib.error.HTTPError) as e: urllib.request.urlopen(url)
            self.assertEqual(e.exception.code,401)
            request=urllib.request.Request(url,headers={'Authorization':'Bearer unit-test-only'})
            with urllib.request.urlopen(request) as response: self.assertIn('fiveHour',json.load(response))
        finally: server.shutdown();server.server_close();worker.join()

    def test_client_disconnect_is_quiet_and_next_request_still_succeeds(self):
        for error in (BrokenPipeError, ConnectionResetError):
            with self.subTest(error=error):
                state=b.State()
                server=ThreadingHTTPServer(('127.0.0.1',0),b.make_handler(state,'unit-test-only'))
                errors=[]
                server.handle_error=lambda *_: errors.append(True)
                worker=threading.Thread(target=server.serve_forever,daemon=True);worker.start()
                original=socketserver._SocketWriter.write
                failed=[]
                def write(stream, data):
                    if data.startswith(b'{"source"') and not failed:
                        failed.append(True)
                        raise error('client disconnected')
                    return original(stream,data)
                url='http://127.0.0.1:%d/quota'%server.server_port
                request=urllib.request.Request(url,headers={'Authorization':'Bearer unit-test-only'})
                try:
                    with patch.object(socketserver._SocketWriter,'write',write):
                        with self.assertRaises(http.client.IncompleteRead):
                            with urllib.request.urlopen(request,timeout=2) as response: response.read()
                        with urllib.request.urlopen(request,timeout=2) as response:
                            self.assertIn('fiveHour',json.load(response))
                    self.assertEqual(failed,[True])
                    self.assertEqual(errors,[])
                finally:
                    server.shutdown();server.server_close();worker.join()

if __name__=='__main__': unittest.main()
