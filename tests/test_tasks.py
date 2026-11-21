import datetime
import importlib.util
import json
from pathlib import Path
import tempfile
import time
import unittest
from unittest.mock import patch

spec = importlib.util.spec_from_file_location("bridge", Path(__file__).parents[1] / "quota_bridge.py")
bridge = importlib.util.module_from_spec(spec)
spec.loader.exec_module(bridge)


def event(kind, **fields):
    return {"type": "event_msg", "payload": {"type": kind, **fields}}


def response(kind, **fields):
    return {"type": "response_item", "payload": {"type": kind, **fields}}


class TaskMonitorTest(unittest.TestCase):
    def setUp(self):
        self.tmp = tempfile.TemporaryDirectory()
        self.addCleanup(self.tmp.cleanup)
        self.home = Path(self.tmp.name)
        self.day = self.home / "sessions" / datetime.date.today().strftime("%Y/%m/%d")
        self.day.mkdir(parents=True)
        self.monitor = bridge.TaskMonitor(bridge.State(), self.home)

    def rollout(self, key, source="cli"):
        path = self.day / ("rollout-" + key + ".jsonl")
        self.append(path, {"type": "session_meta", "payload": {"id": key, "source": source}})
        return path

    def append(self, path, *records):
        with path.open("a", encoding="utf-8") as file:
            for record in records:
                file.write(json.dumps(record, ensure_ascii=False) + "\n")

    def test_local_progress_new_turn_and_partial_line(self):
        path = self.rollout("main")
        self.append(path, event("task_started", turn_id="one", started_at=time.time()),
                    event("user_message", message="<environment_context>internal</environment_context>"),
                    response("message", role="user", content=[{"type": "input_text", "text": "修复龙娘菜单"}]),
                    response("function_call", name="functions.update_plan", arguments=json.dumps({"plan": [
                        {"step": "读源码", "status": "completed"},
                        {"step": "修改菜单", "status": "in_progress"},
                        {"step": "构建", "status": "pending"},
                    ]})))
        status, data = self.monitor.collect(time.time())
        self.assertEqual(status, "active")
        self.assertEqual((data["title"], data["phase"], data["percent"]), ("修复龙娘菜单", "修改菜单", 33))
        with path.open("a") as f:
            f.write('{"type":"event_msg","payload":{"type":"task_complete","turn_id":"one"}}')
        self.assertEqual(self.monitor.collect(time.time())[0], "active")
        with path.open("a") as f:
            f.write("\n")
        self.assertEqual(self.monitor.collect(time.time()), ("idle", None))
        self.append(path, event("turn_started", turn_id="two", started_at=time.time()),
                    event("user_message", message="做新任务"))
        status, data = self.monitor.collect(time.time())
        self.assertEqual((status, data["title"], data["percent"]), ("active", "做新任务", None))

    def test_active_rpc_stage_overrides_plan_and_idle_clears(self):
        path = self.rollout("main")
        self.append(path, event("task_started", turn_id="one", started_at=time.time()),
                    event("user_message", message="检查并构建"))
        self.monitor.notify({"method": "turn/plan/updated", "params": {
            "threadId": "main", "turnId": "one", "plan": [
                {"step": "检查", "status": "completed"}, {"step": "构建", "status": "inProgress"}
            ]}})
        active = {"main": {"id": "main", "path": str(path), "status": {
            "type": "active", "activeFlags": ["waitingOnApproval"]}}}
        with patch.object(self.monitor, "active_threads", return_value=active):
            status, data = self.monitor.collect(time.time())
            self.assertEqual((status, data["phase"], data["percent"]), ("active", "等待批准", 50))
        with patch.object(self.monitor, "active_threads", return_value={
                "main": {"id": "main", "status": {"type": "idle"}}}):
            self.assertEqual(self.monitor.collect(time.time()), ("idle", None))

    def test_rpc_turn_and_live_plan_without_local_rollout(self):
        self.monitor.proxy = type("Proxy", (), {
            "call": lambda _self, _method, _params: {"data": [{
                "id": "turn", "status": "inProgress", "startedAt": time.time(),
                "items": [{"type": "userMessage", "content": [{"type": "text", "text": "本轮实际任务"}]}],
            }]}
        })()
        self.monitor.notify({"method": "turn/plan/updated", "params": {
            "threadId": "active", "turnId": "turn",
            "plan": [{"step": "编译 APK", "status": "inProgress"}],
        }})
        with patch.object(self.monitor, "active_threads", return_value={
                "active": {"name": "旧会话标题", "status": {"type": "active"}}
        }):
            status, task = self.monitor.collect(time.time())
            self.assertEqual((status, task["title"], task["phase"], task["percent"]),
                             ("active", "本轮实际任务", "编译 APK", 0))

    def test_exclude_subagent_and_select_latest_started_task(self):
        now = time.time()
        one = self.rollout("one")
        two = self.rollout("two")
        sub = self.rollout("sub", {"subagent": {"thread_spawn": {"parent_thread_id": "two"}}})
        self.append(one, event("task_started", turn_id="1", started_at=now - 30),
                    event("user_message", message="较早的任务"))
        self.append(two, event("task_started", turn_id="2", started_at=now - 15),
                    event("user_message", message="当前任务"))
        self.append(sub, event("task_started", turn_id="3", started_at=now),
                    event("user_message", message="子任务"))
        self.assertEqual(self.monitor.collect(time.time())[1]["title"], "当前任务")
        self.append(two, event("turn_aborted", turn_id="2"))
        self.assertEqual(self.monitor.collect(time.time())[1]["title"], "较早的任务")

    def test_unknown_progress_is_not_reported_as_zero(self):
        status, data = self.monitor.collect(time.time())
        self.assertEqual((status, data), ("idle", None))
        path = self.rollout("task")
        self.append(path, event("task_started", turn_id="a", started_at=time.time()),
                    event("exec_command_begin", turn_id="a"))
        status, data = self.monitor.collect(time.time())
        self.assertEqual((status, data["phase"], data["percent"]), ("active", "正在执行命令", None))


if __name__ == "__main__":
    unittest.main()
