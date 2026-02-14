#!/bin/bash

# Configuration
# format: "username:password:host"
HOSTS=(
    "root:FurudUtsukNoncomUnrank:codeartisan.cloud"
    # Add more hosts here
)

#HOSTS=(
#    "root:@Ff217ae7:gps.absmultipla.com.br"
#    # Add more hosts here
#)

TRACCAR_SERVICE_CONTENT="[Unit]
Description=traccar
After=network.target

[Service]
Type=simple
WorkingDirectory=/opt/traccar
ExecStart=/opt/traccar/jre/bin/java \
  -Xms8g \
  -Xmx12g \
  -XX:+UseG1GC \
  -XX:MaxGCPauseMillis=200 \
  -XX:+AlwaysPreTouch \
  -XX:+DisableExplicitGC \
  -XX:+HeapDumpOnOutOfMemoryError \
  -XX:HeapDumpPath=/opt/traccar/logs \
  -Djava.net.preferIPv4Stack=true \
  -cp "tracker-server.jar:lib/*" org.traccar.Main conf/traccar.xml
SyslogIdentifier=traccar
SuccessExitStatus=143
WatchdogSec=600
Restart=on-failure
RestartSec=10

[Install]
WantedBy=multi-user.target"

# Progress Function
NUM_HOSTS=${#HOSTS[@]}
# 1 (build) + hosts * 3 (steps per host)
TOTAL_STEPS=$((1 + NUM_HOSTS * 3))
CURRENT_STEP=0

show_progress() {
    ((CURRENT_STEP++))
    echo -e "\033[1;34m[Step $CURRENT_STEP/$TOTAL_STEPS]\033[0m \033[1;32m$1\033[0m"
}

# Check for rollback flag
ROLLBACK=false
if [ "$1" == "--rollback" ]; then
    ROLLBACK=true
    echo "Rollback mode enabled. Restoring from last backup..."
fi

# 1. Build (Skip if rollback)
if [ "$ROLLBACK" = false ]; then
    show_progress "Building Traccar backend..."
    rm -rf target/
    ./gradlew assemble > /dev/null 2>&1

    if [ $? -ne 0 ]; then
        echo "Build failed! Aborting deployment."
        exit 1
    fi
else
    ((CURRENT_STEP++))
fi

# 2. Deploy to each host
for HOST_INFO in "${HOSTS[@]}"; do
    IFS=':' read -r REMOTE_USER REMOTE_PASS REMOTE_HOST <<< "$HOST_INFO"
    
    echo "--------------------------------------------------"
    echo "Processing $REMOTE_HOST..."

    if [ "$ROLLBACK" = true ]; then
        show_progress "Rolling back on $REMOTE_HOST..."
        sshpass -p "$REMOTE_PASS" ssh -o StrictHostKeyChecking=no "$REMOTE_USER@$REMOTE_HOST" << EOF
            if [ -f /opt/traccar/tracker-server.jar.bak ] && [ -d /opt/traccar/lib.bak ]; then
                sudo systemctl stop traccar
                sudo rm -rf /opt/traccar/lib /opt/traccar/tracker-server.jar
                sudo mv /opt/traccar/tracker-server.jar.bak /opt/traccar/tracker-server.jar
                sudo mv /opt/traccar/lib.bak /opt/traccar/lib
                sudo systemctl restart traccar
                echo "Rollback successful"
            else
                echo "Error: No backup found"
                exit 1
            fi
EOF
    else
        # Normal Deployment
        show_progress "Preparing remote and backing up current version..."
        sshpass -p "$REMOTE_PASS" ssh -o StrictHostKeyChecking=no "$REMOTE_USER@$REMOTE_HOST" << EOF
            sudo rm -rf /opt/traccar/lib.bak /opt/traccar/tracker-server.jar.bak
            [ -f /opt/traccar/tracker-server.jar ] && sudo cp /opt/traccar/tracker-server.jar /opt/traccar/tracker-server.jar.bak
            [ -d /opt/traccar/lib ] && sudo cp -r /opt/traccar/lib /opt/traccar/lib.bak
            sudo rm -rf /opt/traccar/lib /opt/traccar/tracker-server.jar
EOF

        # Upload
        show_progress "Uploading binaries..."
        echo "   -> tracker-server.jar"
        sshpass -p "$REMOTE_PASS" scp -o StrictHostKeyChecking=no target/tracker-server.jar "$REMOTE_USER@$REMOTE_HOST:/tmp/"
        echo "   -> lib/ directory"
        sshpass -p "$REMOTE_PASS" scp -o StrictHostKeyChecking=no -r target/lib "$REMOTE_USER@$REMOTE_HOST:/tmp/"

        # Update Service & Cleanup
        show_progress "Updating service and restarting traccar..."
        echo "$TRACCAR_SERVICE_CONTENT" > traccar.service.tmp
        sshpass -p "$REMOTE_PASS" scp -o StrictHostKeyChecking=no traccar.service.tmp "$REMOTE_USER@$REMOTE_HOST:/tmp/traccar.service"
        rm traccar.service.tmp

        sshpass -p "$REMOTE_PASS" ssh -o StrictHostKeyChecking=no "$REMOTE_USER@$REMOTE_HOST" << EOF
            sudo mv /tmp/tracker-server.jar /opt/traccar/
            sudo mv /tmp/lib /opt/traccar/
            sudo mv /tmp/traccar.service /etc/systemd/system/traccar.service
            sudo chown -R root:root /opt/traccar/lib /opt/traccar/tracker-server.jar
            sudo systemctl daemon-reload
            sudo systemctl restart traccar
EOF
    fi
    echo "Host $REMOTE_HOST finished."
done

echo -e "\033[1;32mOperation completed successfully!\033[0m"
