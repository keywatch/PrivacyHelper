#!/system/bin/sh
# Isolated tracefs instance; capture only IPC involving the chosen process.
set -eu
target_pid=32341
trace_dir=/sys/kernel/tracing/instances/kshelper_20261106
mkdir "$trace_dir"
cleanup() {
    printf '0\n' > "$trace_dir/tracing_on"
    printf '0\n' > "$trace_dir/events/binder/binder_transaction/enable"
    printf '0\n' > "$trace_dir/events/binder/binder_transaction_received/enable"
    rmdir "$trace_dir"
}
trap cleanup EXIT HUP INT TERM
printf '0\n' > "$trace_dir/tracing_on"
printf '1024\n' > "$trace_dir/buffer_size_kb"
thread_filter=''
for task_path in /proc/$target_pid/task/*; do
    task_id=${task_path##*/}
    if [ -n "$thread_filter" ]; then
        thread_filter="$thread_filter || "
    fi
    thread_filter="${thread_filter}common_pid == $task_id"
done
printf '%s\n' "to_proc == $target_pid || $thread_filter" > "$trace_dir/events/binder/binder_transaction/filter"
printf '%s\n' "$thread_filter" > "$trace_dir/events/binder/binder_transaction_received/filter"
printf '1\n' > "$trace_dir/events/binder/binder_transaction/enable"
printf '1\n' > "$trace_dir/events/binder/binder_transaction_received/enable"
date -u
printf '1\n' > "$trace_dir/tracing_on"
sleep 30
printf '0\n' > "$trace_dir/tracing_on"
date -u
cat "$trace_dir/trace"
