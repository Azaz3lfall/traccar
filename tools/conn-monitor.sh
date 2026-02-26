#!/bin/bash
# Real-time connection counts for load-test protocol ports only.
# If the load test hits "OSError: [Errno 24] Too many open files", raise the limit:
#   ulimit -n 65535
# (or add to /etc/security/limits.conf and re-login)

PORTS=':5002|:5004|:5009|:5011|:5023|:5027|:5046|:5055'

while true; do
  clear
  echo "=== Load-test ports (5002,5004,5009,5011,5023,5027,5046,5055) — $(date) ==="
  echo ""
  estab=$(ss -tn state established 2>/dev/null | grep -E "$PORTS" | wc -l)
  twait=$(ss -tn state time-wait 2>/dev/null | grep -E "$PORTS" | wc -l)
  echo "  ESTABLISHED: $estab"
  echo "  TIME-WAIT:   $twait"
  echo "  TOTAL:       $((estab + twait))"
  echo ""
  limit=$(ulimit -n)
  pid=$(pgrep -f "load-test\.py" | head -1)
  if [ -n "$pid" ] && [ -d "/proc/$pid/fd" ]; then
    current=$(ls /proc/$pid/fd 2>/dev/null | wc -l)
    echo "  Open files (load-test pid $pid): $current / $limit"
  else
    echo "  Open files: — / $limit  (load-test not running?)"
  fi
  echo "  (If 'Too many open files', run: ulimit -n 65535 before starting load-test)"
  echo ""
  sleep 1
done
