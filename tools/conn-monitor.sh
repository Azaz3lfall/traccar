#!/bin/bash
# Traccar realtime connection monitor. Run: ./conn-monitor.sh

watch -n1 '
echo "=== Traccar Realtime Monitor ==="
date
echo

echo "Total TCP connections:"
ss -tan | wc -l

echo
echo "Established connections:"
ss -tan state established | wc -l

echo
echo "TIME_WAIT connections:"
ss -tan state time-wait | wc -l

echo
echo "Web UI / WebSocket (8082):"
ss -tan | grep :8082 | wc -l



echo
echo "Java (Traccar) sockets:"
ss -tanp | grep java | wc -l
'
