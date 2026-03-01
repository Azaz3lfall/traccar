#!/bin/bash
# Traccar realtime connection monitor. Run: ./conn-monitor.sh

CONN_LOG="${CONN_MONITOR_LOG:-/tmp/traccar-conn.log}"
# Keep last 10 minutes (600 samples at 1/sec)
KEEP_LINES=600

# Helper script for watch (sh doesn't inherit exported bash functions)
CONN_AVG_SCRIPT="${CONN_MONITOR_AVG_SCRIPT:-/tmp/traccar-conn-avg.sh}"
cat > "$CONN_AVG_SCRIPT" << 'INNER'
#!/bin/sh
# Usage: traccar-conn-avg.sh <log_file> <window_seconds>
# Prints average of column 2 for lines where column 1 (epoch) >= now - window
log=$1
w=$2
now=$(date +%s)
awk -v now="$now" -v w="$w" '$1 >= now - w { s += $2; n++ } END { if (n) printf "%.1f", s/n; else printf "0" }' "$log"
INNER
chmod +x "$CONN_AVG_SCRIPT"

watch -n1 "
# Snapshot current established count and append (timestamp epoch, count)
EST=\$(ss -tan state established | wc -l)
echo \"\$(date +%s) \$EST\" >> $CONN_LOG
tail -n $KEEP_LINES $CONN_LOG > ${CONN_LOG}.tmp && mv ${CONN_LOG}.tmp $CONN_LOG

echo '=== Traccar Realtime Monitor ==='
date
echo

echo 'Total TCP connections:'
ss -tan | wc -l

echo
echo 'Established connections (current):'
echo \$EST

echo
echo '--- Established averages (accumulators) ---'
echo -n 'Last 1 min avg:   '
$CONN_AVG_SCRIPT $CONN_LOG 60
echo
echo -n 'Last 5 min avg:  '
$CONN_AVG_SCRIPT $CONN_LOG 300
echo
echo -n 'Last 10 min avg: '
$CONN_AVG_SCRIPT $CONN_LOG 600
echo

echo
echo 'TIME_WAIT connections:'
ss -tan state time-wait | wc -l

echo
echo 'Web UI / WebSocket (8082):'
ss -tan | grep :8082 | wc -l

echo
echo 'Java (Traccar) sockets:'
ss -tanp | grep java | wc -l
"
