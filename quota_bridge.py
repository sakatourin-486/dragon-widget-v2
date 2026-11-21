#!/usr/bin/env python3
"""Local Codex statistics bridge. Run inside the logged-in Debian environment."""
import datetime
import fcntl
import hmac
import json
import math
import os
from pathlib import Path
import queue
import secrets
import subprocess
import sys
import threading
import time
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer

PORT = 8765
TASK_INTERVAL = 3
ROLLOUT_FRESHNESS = 240
ROLLOUT_TAIL_BYTES = 1024 * 1024


class RPC:
    def __init__(self, command=None, notification=None, timeout=40):
        self.notification = notification
        self.timeout = timeout
        self.proc = subprocess.Popen(
            command or ["codex", "app-server"], stdin=subprocess.PIPE,
            stdout=subprocess.PIPE, stderr=subprocess.DEVNULL,
            text=True, bufsize=1)
        self.responses = queue.Queue()
        self.seq = 0
        threading.Thread(target=self.listen, daemon=True).start()
        try:
            self.call("initialize", {"clientInfo": {
                "name": "sakatourin_widget", "version": "1.3"}})
            self.send({"method": "initialized"})
        except Exception:
            self.close()
            raise

    def listen(self):
        for line in self.proc.stdout:
            try:
                message = json.loads(line)
                if not isinstance(message, dict):
                    continue
                if "id" in message and ("result" in message or "error" in message):
                    self.responses.put(message)
                elif self.notification and "id" not in message:
                    self.notification(message)
            except (TypeError, ValueError):
                continue
        self.responses.put(None)

    def send(self, message):
        self.proc.stdin.write(json.dumps(message) + "\n")
        self.proc.stdin.flush()

    def call(self, method, params=None):
        self.seq += 1
        request_id = self.seq
        self.send({"id": request_id, "method": method, "params": params or {}})
        deadline = time.monotonic() + self.timeout
        while True:
            wait = deadline - time.monotonic()
            if wait <= 0:
                raise TimeoutError("Codex 请求超时")
            try:
                message = self.responses.get(timeout=wait)
            except queue.Empty as exc:
                raise TimeoutError("Codex 请求超时") from exc
            if message is None:
                raise RuntimeError("Codex 读取程序退出")
            if message.get("id") == request_id:
                if "error" in message:
                    raise RuntimeError("官方统计接口暂不可用")
                return message["result"]

    def close(self):
        if self.proc.poll() is None:
            self.proc.terminate()
            try:
                self.proc.wait(timeout=3)
            except subprocess.TimeoutExpired:
                self.proc.kill()
                self.proc.wait()
        for stream in (self.proc.stdout, self.proc.stdin):
            try:
                stream.close()
            except (BrokenPipeError, OSError):
                pass


def number(value):
    return isinstance(value, (int, float)) and not isinstance(value, bool) and math.isfinite(value)


def normalize(result):
    buckets = result.get("rateLimitsByLimitId") or {}
    bucket = buckets.get("codex") or result.get("rateLimits") or {}
    # Never combine unrelated model buckets or assume primary means five hours.
    if bucket.get("limitId") not in (None, "codex"):
        bucket = {}
    output = {"fiveHour": None, "weekly": None}
    for field in ("primary", "secondary"):
        window = bucket.get(field) or {}
        minutes = window.get("windowDurationMins")
        target = {300: "fiveHour", 10080: "weekly"}.get(minutes)
        used = window.get("usedPercent")
        if target and number(used):
            reset = window.get("resetsAt")
            label = "官方暂未返回重置时间"
            if number(reset):
                try:
                    label = "重置 " + datetime.datetime.fromtimestamp(reset).strftime("%m-%d %H:%M")
                except (ValueError, OSError, OverflowError):
                    pass
            output[target] = {"remainingPercent": max(0, min(100, 100-used)),
                              "resetText": label, "resetsAt": reset}
    return output


def clean_text(value, limit=132):
    if not isinstance(value, str):
        return ""
    value = " ".join(value.split())
    return value if len(value) <= limit else value[:limit - 1] + "…"


def user_text(content):
    if isinstance(content, str):
        content = [{"text": content}]
    if not isinstance(content, list):
        return ""
    for item in content:
        if not isinstance(item, dict) or item.get("type") not in (None, "text", "input_text"):
            continue
        value = item.get("text", "")
        if not isinstance(value, str) or value.lstrip().startswith(
                ("<environment_context>", "# AGENTS.md", "<user_instructions>")):
            continue
        if text := clean_text(value):
            return text
    return ""


def normalize_plan(raw):
    if not isinstance(raw, list) or len(raw) > 200:
        return []
    plan = []
    for item in raw:
        if not isinstance(item, dict):
            return []
        title = clean_text(item.get("step"), 145)
        status = item.get("status")
        if status == "in_progress":
            status = "inProgress"
        if not title or status not in ("pending", "inProgress", "completed"):
            return []
        plan.append({"step": title, "status": status})
    return plan


def make_task(title, phase, plan):
    completed = sum(step["status"] == "completed" for step in plan)
    in_progress = next((step["step"] for step in plan if step["status"] == "inProgress"), "")
    return {
        "title": title or "Codex 任务",
        "phase": phase if phase in ("等待批准", "等待输入") else in_progress or phase,
        "percent": round(100 * completed / len(plan)) if plan else None,
        "completedSteps": completed,
        "totalSteps": len(plan),
        "steps": plan[:24],
    }


ITEM_PHASES = {
    "command_execution": "正在执行命令", "commandExecution": "正在执行命令",
    "patch_apply_begin": "正在修改文件", "fileChange": "正在修改文件",
    "file_change": "正在修改文件", "webSearch": "正在检索资料",
    "web_search": "正在检索资料", "imageGeneration": "正在生成图片",
    "mcpToolCall": "正在调用工具", "dynamicToolCall": "正在调用工具",
    "function_call": "正在调用工具", "local_shell_call": "正在执行命令",
    "custom_tool_call": "正在调用工具", "agentMessage": "正在生成回复",
    "reasoning": "正在处理任务", "item_completed": "正在处理下一步",
    "exec_command_begin": "正在执行命令", "mcp_tool_call_begin": "正在调用工具",
}


class Rollout:
    """Read bounded local activity metadata without changing the Codex session."""

    def __init__(self):
        self.position = 0
        self.inode = None
        self.is_subagent = False
        self.thread_id = ""
        self.turn_id = ""
        self.started = 0
        self.active = False
        self.title = ""
        self.phase = "执行中"
        self.plan = []

    @staticmethod
    def decode(line):
        try:
            value = json.loads(line)
            return value if isinstance(value, dict) else {}
        except (ValueError, UnicodeError):
            return {}

    def read(self, path, stat):
        if self.inode != stat.st_ino or self.position > stat.st_size:
            self.__init__()
            self.inode = stat.st_ino
            with path.open("rb") as f:
                first = self.decode(f.readline(65536))
            meta = first.get("payload") or {}
            if not isinstance(meta, dict):
                meta = {}
            source = meta.get("source")
            self.is_subagent = bool(meta.get("parent_thread_id") or meta.get("agent_role")
                                    or source == "subagent"
                                    or (isinstance(source, dict) and "subagent" in source))
            self.thread_id = meta.get("id", "")
        if self.is_subagent or self.position == stat.st_size:
            return

        start = max(self.position, stat.st_size - ROLLOUT_TAIL_BYTES)
        with path.open("rb") as f:
            f.seek(start)
            chunk = f.read(stat.st_size - start)
        end = chunk.rfind(b"\n") + 1
        if not end:
            return
        first_line = chunk.find(b"\n") + 1 if start > self.position else 0
        self.position = start + end
        for line in chunk[first_line:end].splitlines():
            if (b'"event_msg"' not in line and b'"response_item"' not in line):
                continue
            self.consume(self.decode(line))

    def consume(self, event):
        payload = event.get("payload") or {}
        if not isinstance(payload, dict):
            return
        kind = payload.get("type")
        if event.get("type") == "event_msg" and kind in ("task_started", "turn_started"):
            self.turn_id = payload.get("turn_id") or payload.get("turnId") or ""
            self.started = payload.get("started_at") or payload.get("startedAt") or 0
            if not number(self.started):
                self.started = 0
            if not self.started:
                try:
                    self.started = datetime.datetime.fromisoformat(event["timestamp"]).timestamp()
                except (KeyError, ValueError, TypeError):
                    pass
            self.active, self.title, self.phase, self.plan = True, "", "执行中", []
            return
        if not self.active:
            return
        turn = payload.get("turn_id") or payload.get("turnId")
        if turn and self.turn_id and turn != self.turn_id:
            return
        if event.get("type") == "event_msg":
            if kind in ("task_complete", "turn_complete", "turn_completed", "turn_aborted"):
                self.active = False
            elif kind == "user_message":
                self.title = user_text(payload.get("message")) or self.title
            elif kind == "plan_update":
                self.plan = normalize_plan(payload.get("plan"))
            elif kind == "item_completed":
                item = payload.get("item") or {}
                if isinstance(item, dict) and item.get("type") in ("user_message", "userMessage"):
                    self.title = user_text(item.get("content") or item.get("message")) or self.title
                elif isinstance(item, dict) and isinstance(item.get("UserMessage"), dict):
                    self.title = user_text(item["UserMessage"].get("content")) or self.title
                else:
                    self.phase = ITEM_PHASES[kind]
            elif kind in ITEM_PHASES:
                self.phase = ITEM_PHASES[kind]
        elif kind == "message":
            if payload.get("role") == "user":
                self.title = user_text(payload.get("content")) or self.title
            elif payload.get("role") == "assistant":
                self.phase = "正在生成回复"
        elif kind == "function_call":
            name = payload.get("name") or ""
            if isinstance(name, str) and name.rsplit(".", 1)[-1] == "update_plan":
                args = payload.get("arguments")
                if isinstance(args, str):
                    args = self.decode(args)
                if isinstance(args, dict):
                    self.plan = normalize_plan(args.get("plan"))
            else:
                self.phase = ITEM_PHASES[kind]
        elif kind in ITEM_PHASES:
            self.phase = ITEM_PHASES[kind]


class TaskMonitor:
    def __init__(self, state, codex_home=None):
        self.state = state
        self.codex_home = (codex_home or Path(os.environ.get("CODEX_HOME")
                                             or Path.home() / ".codex")).resolve()
        self.sessions = self.codex_home / "sessions"
        self.files = {}
        self.proxy = None
        self.retry_proxy = 0
        self.event_lock = threading.Lock()
        self.live_plans = {}
        self.live_turns = {}
        self.rpc_turns = {}

    def notify(self, message):
        method = message.get("method")
        if method not in ("turn/plan/updated", "turn/started", "turn/completed"):
            return
        params = message.get("params") or {}
        thread = params.get("threadId")
        turn = params.get("turn") or {}
        turn_id = params.get("turnId") or turn.get("id")
        if not isinstance(thread, str) or not isinstance(turn_id, str):
            return
        with self.event_lock:
            if method == "turn/plan/updated":
                self.live_plans[(thread, turn_id)] = normalize_plan(params.get("plan"))
                if len(self.live_plans) > 24:
                    self.live_plans.pop(next(iter(self.live_plans)))
            elif method == "turn/started":
                self.live_turns[thread] = turn_id
                if len(self.live_turns) > 24:
                    self.live_turns.pop(next(iter(self.live_turns)))
            else:
                self.live_plans.pop((thread, turn_id), None)
                if self.live_turns.get(thread) == turn_id:
                    del self.live_turns[thread]

    def read_rpc_turn(self, thread_id, thread):
        if self.proxy is None:
            return {}
        marker = thread.get("updatedAt")
        now = time.monotonic()
        cached = self.rpc_turns.get(thread_id)
        if cached and cached[0] == marker and now - cached[1] < 12:
            return cached[2]
        try:
            page = self.proxy.call("thread/turns/list", {
                "threadId": thread_id, "limit": 1, "itemsView": "summary",
            })
            turn = next((value for value in page.get("data", [])
                         if isinstance(value, dict) and value.get("status") == "inProgress"), {})
            items = turn.get("items") or []
            title, phase = "", "执行中"
            for item in items:
                if not isinstance(item, dict):
                    continue
                kind = item.get("type")
                if kind == "userMessage":
                    title = user_text(item.get("content")) or title
                elif kind in ITEM_PHASES:
                    phase = ITEM_PHASES[kind]
            result = {"id": turn.get("id"), "started": turn.get("startedAt"),
                      "title": title, "phase": phase}
        except (RuntimeError, OSError, TimeoutError, TypeError, ValueError):
            result = {}
        self.rpc_turns[thread_id] = (marker, now, result)
        if len(self.rpc_turns) > 12:
            self.rpc_turns.pop(next(iter(self.rpc_turns)))
        return result

    def active_threads(self):
        if self.proxy is None and time.monotonic() >= self.retry_proxy:
            socket_paths = (self.codex_home / "app-server-control" / "app-server-control.sock",
                            self.codex_home / "app-server-control.sock")
            socket_path = next((p for p in socket_paths if p.is_socket()), None)
            self.retry_proxy = time.monotonic() + 14
            if socket_path:
                try:
                    self.proxy = RPC(["codex", "app-server", "proxy", "--sock", str(socket_path)],
                                     notification=self.notify, timeout=2)
                except (RuntimeError, OSError, TimeoutError):
                    pass
        if self.proxy is None:
            return {}
        try:
            recent = self.proxy.call("thread/list", {
                "limit": 25, "sortKey": "updated_at", "sortDirection": "desc",
                "sourceKinds": ["cli", "vscode", "exec", "appServer"],
            })
            return {item["id"]: item for item in recent.get("data", [])
                    if isinstance(item, dict) and isinstance(item.get("id"), str)
                    and not (item.get("parentThreadId") or item.get("agentRole"))}
        except (RuntimeError, OSError, TimeoutError):
            self.proxy.close()
            self.proxy = None
            self.retry_proxy = time.monotonic() + 10
            with self.event_lock:
                self.live_plans.clear()
                self.live_turns.clear()
            self.rpc_turns.clear()
            return {}

    def recent_files(self, now):
        candidates = []
        today = datetime.date.today()
        for age in range(3):
            day = today - datetime.timedelta(days=age)
            directory = self.sessions / f"{day:%Y/%m/%d}"
            for path in directory.glob("rollout-*.jsonl"):
                try:
                    if path.is_symlink() or not path.is_file():
                        continue
                    stat = path.stat()
                    if 0 <= now - stat.st_mtime < ROLLOUT_FRESHNESS:
                        candidates.append((path, stat))
                except OSError:
                    continue
        candidates.sort(key=lambda pair: pair[1].st_mtime, reverse=True)
        return dict(candidates[:20])

    def read_local(self, path, stat):
        try:
            entry = self.files.setdefault(path, Rollout())
            entry.read(path, stat)
            return entry
        except OSError:
            self.files.pop(path, None)
            return None

    def collect(self, now):
        threads = self.active_threads()
        paths = self.recent_files(now)
        candidates = {}
        for thread_id, thread in threads.items():
            runtime = thread.get("status") or {}
            if runtime.get("type") != "active":
                continue
            path = thread.get("path")
            record = None
            if isinstance(path, str):
                path = Path(path).resolve()
                if path.is_relative_to(self.sessions) and path.name.startswith("rollout-") and path.suffix == ".jsonl":
                    try:
                        paths.setdefault(path, path.stat())
                        record = self.read_local(path, paths[path])
                    except OSError:
                        pass
            active_record = record if record and record.active else None
            api_turn = {} if active_record else self.read_rpc_turn(thread_id, thread)
            flags = runtime.get("activeFlags") or []
            phase = ("等待批准" if "waitingOnApproval" in flags else "等待输入"
                     if "waitingOnUserInput" in flags else active_record.phase if active_record
                     else api_turn.get("phase") or "执行中")
            fallback = clean_text(thread.get("name")) or clean_text(thread.get("preview"))
            title = (active_record.title if active_record else api_turn.get("title")) or fallback
            score = (active_record.started if active_record else api_turn.get("started"))
            if not number(score):
                score = 0
            score = score or thread.get("updatedAt") or 0
            turn = active_record.turn_id if active_record else api_turn.get("id")
            plan = active_record.plan if active_record else []
            with self.event_lock:
                turn = turn or self.live_turns.get(thread_id)
                plan = self.live_plans.get((thread_id, turn), plan)
            candidates[thread_id] = (score if number(score) else 0, make_task(title, phase, plan))

        for path, stat in paths.items():
            entry = self.read_local(path, stat)
            if not entry or not entry.active or entry.is_subagent:
                continue
            if now - stat.st_mtime >= ROLLOUT_FRESHNESS:
                continue
            thread_id = entry.thread_id or str(path)
            if thread_id in candidates:
                continue
            thread = threads.get(thread_id) or {}
            runtime = (thread.get("status") or {}).get("type")
            if runtime in ("idle", "systemError"):
                continue
            fallback = clean_text(thread.get("name")) or clean_text(thread.get("preview"))
            candidates[thread_id] = (entry.started or stat.st_mtime,
                                     make_task(entry.title or fallback, entry.phase, entry.plan))

        for path in set(self.files) - paths.keys():
            del self.files[path]
        if candidates:
            return "active", max(candidates.values(), key=lambda candidate: candidate[0])[1]
        return ("idle" if self.proxy or self.sessions.is_dir() else "unavailable"), None

    def run(self):
        try:
            while not self.state.stop.is_set():
                now = time.time()
                try:
                    status, task = self.collect(now)
                except (OSError, ValueError, TypeError):
                    status, task = "unavailable", None
                with self.state.lock:
                    self.state.data.update(task=task, taskStatus=status, taskUpdated=int(time.time()))
                if self.state.stop.wait(TASK_INTERVAL):
                    break
        finally:
            if self.proxy:
                self.proxy.close()



class State:
    def __init__(self):
        self.lock = threading.Lock()
        self.stop = threading.Event()
        self.data = {"source": "Codex 官方统计", "status": "正在读取账号额度",
                     "fiveHour": None, "weekly": None, "lifetimeTokens": None,
                     "lastUpdated": None, "stale": False, "task": None,
                     "taskStatus": "connecting", "taskUpdated": None}

    def poll(self):
        rpc = None
        token_time = 0
        tokens = None
        failures = 0
        while not self.stop.is_set():
            try:
                if rpc is None:
                    rpc = RPC()
                windows = normalize(rpc.call("account/rateLimits/read"))
                # Publish quota immediately. Optional token statistics must not delay it.
                stamp = int(time.time())
                with self.lock:
                    self.data.update(windows, lifetimeTokens=tokens, lastUpdated=stamp,
                                     stale=False, status="Codex · 更新于 " + time.strftime("%H:%M", time.localtime(stamp)))
                failures = 0
                if time.monotonic() - token_time >= 600:
                    try:
                        usage = rpc.call("account/usage/read")
                        value = (usage.get("summary") or {}).get("lifetimeTokens")
                        tokens = int(value) if number(value) and value >= 0 else None
                        with self.lock:
                            self.data["lifetimeTokens"] = tokens
                    except Exception:
                        # Unsupported token endpoint is separate from quota availability.
                        pass
                    token_time = time.monotonic()
            except Exception:
                failures += 1
                with self.lock:
                    self.data.update(stale=True, status="刷新失败 · 上次数据" if self.data["lastUpdated"] else "无法读取，请检查 Codex 登录与网络")
                if rpc:
                    rpc.close()
                rpc = None
            delay = min(60, 2 ** min(failures, 6)) if failures else 45
            if self.stop.wait(delay):
                break
        if rpc:
            rpc.close()


def make_handler(state, key):
    class Handler(BaseHTTPRequestHandler):
        def setup(self):
            super().setup()
            self.connection.settimeout(8)

        def handle(self):
            # Screen changes, timeout and network cancellation are normal clients.
            # Catch around headers, errors and body, not only wfile.write().
            try:
                super().handle()
            except (BrokenPipeError, ConnectionResetError, ConnectionAbortedError, TimeoutError):
                self.close_connection = True

        def log_message(self, *_):
            pass

        def do_GET(self):
            if self.path != "/quota":
                self.send_error(404)
                return
            expected = "Bearer " + key
            supplied = self.headers.get("Authorization", "")
            if not hmac.compare_digest(supplied.encode(), expected.encode()):
                self.send_error(401)
                return
            with state.lock:
                body = json.dumps(state.data, ensure_ascii=False).encode("utf-8")
            self.send_response(200)
            self.send_header("Content-Type", "application/json; charset=utf-8")
            self.send_header("Cache-Control", "no-store")
            self.send_header("Content-Length", str(len(body)))
            self.end_headers()
            self.wfile.write(body)
    return Handler


def main():
    config = Path.home() / ".sakatourin-widget"
    config.mkdir(mode=0o700, exist_ok=True)
    key_path = config / "pairing_key"
    if not key_path.exists():
        with os.fdopen(os.open(key_path, os.O_WRONLY | os.O_CREAT | os.O_EXCL, 0o600), "w") as f:
            f.write(secrets.token_hex(8))
    key = key_path.read_text().strip()
    if len(key) != 16:
        raise RuntimeError("配对密钥格式异常")
    state = State()
    server = ThreadingHTTPServer(("127.0.0.1", PORT), make_handler(state, key))
    server.daemon_threads = True
    worker = threading.Thread(target=state.poll, daemon=True)
    worker.start()
    task_worker = threading.Thread(target=TaskMonitor(state).run, daemon=True)
    task_worker.start()
    print("\nSaKaTouRin 本地读取服务已启动", flush=True)
    print("数据源：http://127.0.0.1:8765/quota", flush=True)
    print("配对密钥：" + key, flush=True)
    print("沿用原有配对设置。可加 --background 在后台自动重启运行。", flush=True)
    try:
        server.serve_forever()
    except KeyboardInterrupt:
        pass
    finally:
        server.server_close()
        state.stop.set()
        worker.join(timeout=45)
        task_worker.join(timeout=5)


def supervise():
    config = Path.home() / ".sakatourin-widget"
    config.mkdir(mode=0o700, exist_ok=True)
    with (config / "bridge.lock").open("a") as lock:
        try:
            fcntl.flock(lock, fcntl.LOCK_EX | fcntl.LOCK_NB)
        except BlockingIOError:
            return
        while True:
            child = subprocess.Popen([sys.executable, "-u", str(Path(__file__).resolve()), "--serve"])
            try:
                child.wait()
            except KeyboardInterrupt:
                child.terminate()
                child.wait()
                return
            print("读取进程已结束，3秒后重启。", flush=True)
            time.sleep(3)


def background():
    config = Path.home() / ".sakatourin-widget"
    config.mkdir(mode=0o700, exist_ok=True)
    os.umask(0o077)
    with (config / "bridge.log").open("a") as log:
        process = subprocess.Popen(
            [sys.executable, "-u", str(Path(__file__).resolve()), "--supervise"],
            stdin=subprocess.DEVNULL, stdout=log, stderr=subprocess.STDOUT,
            start_new_session=True, close_fds=True)
    try:
        code = process.wait(timeout=.5)
    except subprocess.TimeoutExpired:
        code = None
    if code not in (None, 0):
        raise RuntimeError("后台启动失败，请查看 ~/.sakatourin-widget/bridge.log")
    print("后台守护已启动（已运行时不会重复启动），沿用原有配对密钥。")


if __name__ == "__main__":
    if "--background" in sys.argv:
        background()
    elif "--supervise" in sys.argv:
        supervise()
    else:
        main()
