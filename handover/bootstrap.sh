#!/usr/bin/env bash
# 独立验证环境一键拉起（仅 bi-manager + mock 下游 + 可选前端）。在仓库根目录执行。
# 依赖：JDK 17+/21、Maven 3.8+、MySQL/MariaDB 10.6+、Python 3.10+（flask）、可选 Node 16+
set -e
ROOT=$(cd "$(dirname "$0")/.." && pwd)
DB_USER=${DB_USER:-root}; DB_PASS=${DB_PASS:-root}; DB_NAME=${DB_NAME:-quality_test}; DB_HOST=${DB_HOST:-127.0.0.1}
M="mysql -h$DB_HOST -u$DB_USER -p$DB_PASS --default-character-set=utf8mb4"

echo "[1/4] 数据库 $DB_NAME"
$M -e "CREATE DATABASE IF NOT EXISTS \`$DB_NAME\` DEFAULT CHARSET utf8mb4"
$M "$DB_NAME" < "$ROOT/backend/bi-manager/src/main/resources/db/quality.sql"
$M "$DB_NAME" < "$ROOT/handover/seed_minimal.sql"

echo "[2/4] mock System B / chat-server / recall (:5055)"
pip install -q flask 2>/dev/null || true
if ! curl -s -o /dev/null -X POST http://127.0.0.1:5055/api/v1/prompts/groups; then
  setsid nohup python3 "$ROOT/handover/mock/mock_system_b.py" > /tmp/mock_sysb.log 2>&1 < /dev/null &
fi

echo "[3/4] bi-manager (:8591)"
cd "$ROOT/backend/bi-manager"
[ -f target/bi-backend-1.0.0-SNAPSHOT.jar ] || mvn -q -DskipTests package
SPRING_DATASOURCE_URL="jdbc:mysql://$DB_HOST:3306/$DB_NAME?useUnicode=true&characterEncoding=utf-8&useSSL=false&serverTimezone=Asia/Shanghai&allowPublicKeyRetrieval=true" \
SPRING_DATASOURCE_USERNAME="$DB_USER" SPRING_DATASOURCE_PASSWORD="$DB_PASS" \
QUALITY_SYSTEM_B_URL=http://127.0.0.1:5055 QUALITY_CHAT_SERVER_URL=http://127.0.0.1:5055 QUALITY_RECALL_URL=http://127.0.0.1:5055 \
setsid nohup java -Xmx512m -jar target/bi-backend-1.0.0-SNAPSHOT.jar --spring.profiles.active=sandbox > /tmp/bi.log 2>&1 < /dev/null &
for i in $(seq 1 40); do sleep 3; curl -s -o /dev/null -w "%{http_code}" http://127.0.0.1:8591/api/v1/chat/error-types | grep -qE "200|401" && break; done

echo "[4/4] 前端静态代理 (:8600，含模拟登录)"
if [ -n "$FE_DIST" ] && [ -f "$FE_DIST/index.html" ]; then
  FE_DIST="$FE_DIST" setsid nohup python3 "$ROOT/handover/mock/fe_static_proxy.py" > /tmp/fe_serve.log 2>&1 < /dev/null &
else
  echo "    (跳过：未设置 FE_DIST 或未构建前端；仅 API 验证时不需要)"
fi
echo "ready. token: python3 handover/mock/gen_token.py admin"
