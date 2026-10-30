#!/usr/bin/env bash
# Integration check for test database creation; requires the Compose database.
set -euo pipefail
cd "$(dirname "$0")/.."

test_db="jiaxian_setup_test_$$"
test_user="${GS_USERNAME:-jiaxian_app}"
admin_sql() {
  docker exec -u opengauss -e LD_LIBRARY_PATH=/usr/local/opengauss/lib jiaxian-opengauss \
    /usr/local/opengauss/bin/gsql -v ON_ERROR_STOP=1 -d "$1" -q -t -A -c "$2"
}

[[ -z $(admin_sql postgres "SELECT 1 FROM pg_database WHERE datname = '$test_db'") ]]
trap 'admin_sql postgres "DROP DATABASE IF EXISTS $test_db" > /dev/null' EXIT

TEST_DB_NAME="$test_db" scripts/create-test-db.sh
[[ $(admin_sql postgres "SELECT pg_get_userbyid(datdba) FROM pg_database WHERE datname = '$test_db'") == "$test_user" ]]
[[ $(admin_sql "$test_db" "SELECT 1 FROM pg_namespace WHERE nspname = '$test_user'") == 1 ]]
TEST_DB_NAME="$test_db" scripts/create-test-db.sh
echo "Test database creation and repeated setup passed."
