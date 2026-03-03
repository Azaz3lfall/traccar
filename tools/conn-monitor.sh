#!/bin/bash
# Copyright: Pedroso, Rafael Goulart | WhatsApp: +55 11 93425-1920 | don@codeartisan.cloud
# Traccar realtime connection monitor. Run: ./conn-monitor.sh

CONN_LOG="${CONN_MONITOR_LOG:-/tmp/traccar-conn.log}"
GEO_LOG="${CONN_MONITOR_GEO_LOG:-/tmp/traccar-geo.log}"
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

watch -n1 -t "
# Snapshot current established count and append (timestamp epoch, count)
EST=\$(ss -tan state established | wc -l)
echo \"\$(date +%s) \$EST\" >> $CONN_LOG
tail -n $KEEP_LINES $CONN_LOG > ${CONN_LOG}.tmp && mv ${CONN_LOG}.tmp $CONN_LOG

# Outbound to :8080 = Traccar -> external geocoder (peer port 8080). ss = Netid State Recv-Q Send-Q Local Peer → peer is last field.
G8080_OUT=\$(ss -tan state established | awk 'NF >= 6 && \$NF ~ /:8080\$/ {c++} END {print c+0}')
G8080_IN=\$(ss -tan state established | awk 'NF >= 6 && \$(NF-1) ~ /:8080\$/ {c++} END {print c+0}')
G8080=\$G8080_OUT
echo \"\$(date +%s) \$G8080\" >> $GEO_LOG
tail -n 5 $GEO_LOG > ${GEO_LOG}.tmp && mv ${GEO_LOG}.tmp $GEO_LOG
NOW=\$(date +%s)
G8080_PREV=\$(awk -v now=\"\$NOW\" '\$1 >= now - 2 && \$1 <= now - 1 {print \$2; exit}' $GEO_LOG)
if [ -n \"\$G8080_PREV\" ]; then
  GEO_RPS=\$((G8080 - G8080_PREV))
else
  GEO_RPS=\"n/a\"
fi

echo 'Copyright: Pedroso, Rafael Goulart | WhatsApp: +55 11 93425-1920 | don@codeartisan.cloud'
echo

echo 'Total TCP connections:'
ss -tan | wc -l

echo
echo 'Established connections (current):'
echo \$EST

echo
echo 'TIME_WAIT connections:'
ss -tan state time-wait | wc -l

echo
echo 'Web UI / WebSocket (8082):'
ss -tan | grep :8082 | wc -l

echo
echo 'Java (Traccar) sockets:'
ss -tanp | grep java | wc -l

echo
echo '--- Reverse Geocoder (port 8080) ---'
echo -n 'Outbound to :8080:    '
echo \$G8080_OUT
echo -n 'Inbound on :8080:     '
echo \$G8080_IN
echo -n 'Reverse Geocoder Requests per second: '
echo \$GEO_RPS

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
"
