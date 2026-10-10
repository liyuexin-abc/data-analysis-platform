from flask import Flask, request, jsonify
app = Flask(__name__)
@app.route("/api/v1/analyze", methods=["POST"])
def analyze():
    d = request.get_json(); meta = d.get("database_meta") or {}
    tables = [t.get("table_name") for t in meta.get("table_summaries", [])]
    chosen = "view_by_voltage" if "view_by_voltage" in tables else "view_fenqu"
    return jsonify({"request_id": d["request_id"], "status": "success", "answer": f"各电压等级线损率 3.2%（{chosen}）", "used_tables": [chosen], "resolved_metrics": ["ll_rate"], "resolved_dims": ["voltage_level"], "failed_steps": [], "steps": [{"step_id": "step_1"}], "prompt_version": (d.get("prompt_overrides") or {}).get("prompt-main", "v1.0.5"), "elapsed_ms": 10})
@app.route("/api/v1/prompts/groups", methods=["POST"])
def groups(): return jsonify({"code": 200, "data": [{"id": 1, "name": "prompt-main"}, {"id": 2, "name": "prompt-summary"}]})
@app.route("/api/v1/prompts/current", methods=["POST"])
def current(): return jsonify({"code": 200, "data": {"version": "v1.0.5", "content": "SYS {current_date}\n=====USER_TEMPLATE=====\n{query} {database_meta_text} {functions_text} {business_logic_knowledge}\n规则一 xxx\n规则二 yyy"}})
@app.route("/api/v1/prompts/list", methods=["POST"])
def plist(): return jsonify({"code": 200, "data": {"list": [{"version": "v1.0.5", "is_active": True}, {"version": "v1.0.4", "is_active": False}]}})
@app.route("/api/v1/prompts/version/content", methods=["POST"])
def pcontent(): return current()
@app.route("/api/v1/prompts/lint", methods=["POST"])
def plint(): return jsonify({"code": 200, "data": {"ok": True, "errors": [], "warnings": [], "placeholders": ["query"]}})
@app.route("/api/v1/prompts/version/create", methods=["POST"])
def pcreate(): return jsonify({"code": 200, "data": {"version": "v1.0.6", "is_active": False, "diff_summary": "+1/−0 行，基于 v1.0.5"}})
@app.route("/api/v1/prompts/switch", methods=["POST"])
def switch(): return jsonify({"code": 200})
@app.route("/api/v1/logs/by-request", methods=["POST"])
def logs(): return jsonify({"code": 200, "data": {"content": "MOCK SYSTEM B LOG\n1、用户问题：...", "log_path": "userlogs/x.log"}})
@app.route("/api/chat-server/metadata/agent", methods=["GET"])
def agent_meta(): return jsonify({"code": 200, "data": {"available_metrics": [{"metric_code": "ll_rate", "description": "线损率"}], "available_dimensions": [{"dimension_code": "voltage_level", "description": "电压等级"}], "table_summaries": [{"table_name": "view_fenqu", "description": "分区线损率", "columns": []}], "business_context": ""}})
@app.route("/api/metadata/sync", methods=["POST"])
def sync(): return jsonify({"code": 200})
app.run(port=5055)
