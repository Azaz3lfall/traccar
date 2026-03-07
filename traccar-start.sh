#!/bin/sh
# Wrapper for Traccar so systemd doesn't have to parse the long Java command.
# Deploy to /opt/traccar/start.sh on the server and chmod +x.

cd /opt/traccar
exec /opt/traccar/jre/bin/java \
  -Xms8g \
  -Xmx12g \
  -XX:+UseG1GC \
  -XX:MaxGCPauseMillis=200 \
  -XX:+AlwaysPreTouch \
  -XX:+DisableExplicitGC \
  -XX:+HeapDumpOnOutOfMemoryError \
  -XX:HeapDumpPath=/opt/traccar/logs \
  -Djava.net.preferIPv4Stack=true \
  -cp "tracker-server.jar:lib/*" \
  org.traccar.Main \
  conf/traccar.xml
