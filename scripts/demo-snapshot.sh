#!/usr/bin/env bash
# 演示数据的快照与恢复：灌好一次（scripts/seed-demo.sh），存成快照，之后几十秒就能恢复，不用再生成。
#
# 用法：
#   scripts/demo-snapshot.sh save [名字]      保存当前数据库（默认名字 demo），会短暂停止数据库和后端
#   scripts/demo-snapshot.sh restore [名字]   用快照覆盖当前数据库，会短暂停止数据库和后端
#   scripts/demo-snapshot.sh list             列出快照
#   scripts/demo-snapshot.sh delete [名字]    删除快照
#
# 快照 = OpenGauss 数据卷的 tar.gz，存在 .demo-snapshots/（已被 git 忽略，约几百 MB）。
# 只能恢复到同一个 OpenGauss 镜像版本的数据库；要跨机器共享，把 .demo-snapshots/ 里的文件拷过去即可。
# 数据库平时本来就是持久化的（Docker 卷 opengauss-data）：重启、停止、重建镜像都不会丢数据，
# 只有 `docker compose down -v` 会删卷——快照是为"误清空、做了破坏性实验、换机器"准备的。
set -euo pipefail

cd "$(dirname "$0")/.."
SNAPSHOT_DIR="${SNAPSHOT_DIR:-.demo-snapshots}"
DB_CONTAINER=jiaxian-opengauss
BACKEND_CONTAINER=jiaxian-backend
command=${1:-}
name=${2:-demo}

if [[ ! $name =~ ^[A-Za-z0-9_.-]+$ ]]; then
  echo "快照名字只能包含字母、数字、下划线、点和连字符：$name" >&2
  exit 1
fi
file="$SNAPSHOT_DIR/$name.tgz"

volume_name() {
  docker inspect "$DB_CONTAINER" --format '{{range .Mounts}}{{if eq .Destination "/var/lib/opengauss/data"}}{{.Name}}{{end}}{{end}}'
}

# 用数据库镜像自己的 tar（镜像一定在本机），以 root 身份读写数据卷
volume_tar() {
  local mode=$1 volume=$2 image
  image=$(docker inspect "$DB_CONTAINER" --format '{{.Config.Image}}')
  mkdir -p "$SNAPSHOT_DIR"
  local abs_dir
  abs_dir=$(cd "$SNAPSHOT_DIR" && pwd)
  if [[ $mode == save ]]; then
    docker run --rm --user root --entrypoint sh -v "$volume:/data:ro" -v "$abs_dir:/backup" "$image" \
      -c "tar czf /backup/$name.tgz.partial -C /data . && mv /backup/$name.tgz.partial /backup/$name.tgz"
  else
    docker run --rm --user root --entrypoint sh -v "$volume:/data" -v "$abs_dir:/backup:ro" "$image" \
      -c "find /data -mindepth 1 -delete && tar xzf /backup/$name.tgz -C /data"
  fi
}

wait_healthy() {
  local container=$1
  for _ in $(seq 1 90); do
    if [[ $(docker inspect "$container" --format '{{.State.Health.Status}}' 2>/dev/null) == healthy ]]; then
      return 0
    fi
    sleep 2
  done
  echo "$container 一直没有恢复健康" >&2
  return 1
}

# 停掉数据库和后端（后端是否在运行要记下来，做完按原样恢复）
stop_stack() {
  backend_was_running=false
  if [[ $(docker inspect "$BACKEND_CONTAINER" --format '{{.State.Running}}' 2>/dev/null || echo false) == true ]]; then
    backend_was_running=true
    docker stop "$BACKEND_CONTAINER" > /dev/null
  fi
  docker stop "$DB_CONTAINER" > /dev/null
}

start_stack() {
  docker start "$DB_CONTAINER" > /dev/null
  wait_healthy "$DB_CONTAINER"
  if [[ $backend_was_running == true ]]; then
    docker start "$BACKEND_CONTAINER" > /dev/null
    wait_healthy "$BACKEND_CONTAINER"
  fi
}

case $command in
  save)
    volume=$(volume_name)
    [[ -n $volume ]] || { echo "找不到 $DB_CONTAINER 的数据卷，数据库容器在运行吗？" >&2; exit 1; }
    echo "停止数据库和后端，打包数据卷 $volume ..."
    stop_stack
    trap 'start_stack' EXIT
    volume_tar save "$volume"
    trap - EXIT
    start_stack
    echo "快照已保存：$file（$(du -h "$file" | cut -f1)）"
    ;;
  restore)
    [[ -f $file ]] || { echo "没有快照 $file（先 save，或用 list 看有哪些）" >&2; exit 1; }
    volume=$(volume_name)
    [[ -n $volume ]] || { echo "找不到 $DB_CONTAINER 的数据卷，数据库容器在运行吗？" >&2; exit 1; }
    echo "停止数据库和后端，用 $file 覆盖数据卷 $volume ..."
    stop_stack
    trap 'start_stack' EXIT
    volume_tar restore "$volume"
    trap - EXIT
    start_stack
    echo "已恢复快照 $name。"
    ;;
  list)
    if compgen -G "$SNAPSHOT_DIR/*.tgz" > /dev/null; then
      ls -lh "$SNAPSHOT_DIR"/*.tgz | awk '{print $5, $6, $7, $8, $9}'
    else
      echo "还没有快照（scripts/demo-snapshot.sh save）。"
    fi
    ;;
  delete)
    [[ -f $file ]] || { echo "没有快照 $file" >&2; exit 1; }
    rm "$file"
    echo "已删除 $file"
    ;;
  *)
    sed -n '2,12p' "$0" | sed 's/^# \{0,1\}//'
    exit 1
    ;;
esac
