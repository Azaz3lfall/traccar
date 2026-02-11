#!/bin/bash

# Configuration
# format: "username:password:host"
# HOSTS=(
#     "root:FurudUtsukNoncomUnrank:codeartisan.cloud"
#     # Add more hosts here
# )

HOSTS=(
    "root:@Ff217ae7:gps.absmultipla.com.br"
    # Add more hosts here
)

TRACCAR_SERVICE_CONTENT="[Unit]
Description=traccar
After=network.target

[Service]
Type=simple
WorkingDirectory=/opt/traccar
# ExecStart=/opt/traccar/jre/bin/java -jar tracker-server.jar conf/traccar.xml
ExecStart=/opt/traccar/jre/bin/java -cp \"tracker-server.jar:lib/*\" org.traccar.Main conf/traccar.xml
SyslogIdentifier=traccar
SuccessExitStatus=143
WatchdogSec=600
Restart=on-failure
RestartSec=10

[Install]
WantedBy=multi-user.target"

# Check for rollback flag
ROLLBACK=false
if [ "$1" == "--rollback" ]; then
    ROLLBACK=true
    echo "Rollback mode enabled. Restoring from last backup..."
fi

# 1. Build (Skip if rollback)
if [ "$ROLLBACK" = false ]; then
    echo "Building Traccar backend..."
    rm -rf target/
    ./gradlew assemble

    if [ $? -ne 0 ]; then
        echo "Build failed! Aborting deployment."
        exit 1
    fi
fi

# 2. Deploy to each host
for HOST_INFO in "${HOSTS[@]}"; do
    IFS=':' read -r REMOTE_USER REMOTE_PASS REMOTE_HOST <<< "$HOST_INFO"
    
    echo "--------------------------------------------------"
    echo "Processing $REMOTE_HOST..."

    if [ "$ROLLBACK" = true ]; then
        echo "Rolling back on $REMOTE_HOST..."
        sshpass -p "$REMOTE_PASS" ssh -o StrictHostKeyChecking=no "$REMOTE_USER@$REMOTE_HOST" << EOF
            if [ -f /opt/traccar/tracker-server.jar.bak ] && [ -d /opt/traccar/lib.bak ]; then
                sudo systemctl stop traccar
                sudo rm -rf /opt/traccar/lib /opt/traccar/tracker-server.jar
                sudo mv /opt/traccar/tracker-server.jar.bak /opt/traccar/tracker-server.jar
                sudo mv /opt/traccar/lib.bak /opt/traccar/lib
                sudo systemctl restart traccar
                echo "Rollback successful on $REMOTE_HOST"
            else
                echo "Error: No backup found on $REMOTE_HOST"
                exit 1
            fi
EOF
    else
        # Normal Deployment with Backup
        echo "Backing up current version on $REMOTE_HOST..."
        sshpass -p "$REMOTE_PASS" ssh -o StrictHostKeyChecking=no "$REMOTE_USER@$REMOTE_HOST" << EOF
            sudo rm -rf /opt/traccar/lib.bak /opt/traccar/tracker-server.jar.bak
            [ -f /opt/traccar/tracker-server.jar ] && sudo cp /opt/traccar/tracker-server.jar /opt/traccar/tracker-server.jar.bak
            [ -d /opt/traccar/lib ] && sudo cp -r /opt/traccar/lib /opt/traccar/lib.bak
EOF

        # Remote cleanup of current (new files will replace these)
        echo "Uploading tracker-server.jar and lib/..."
        sshpass -p "$REMOTE_PASS" scp -o StrictHostKeyChecking=no target/tracker-server.jar "$REMOTE_USER@$REMOTE_HOST:/tmp/"
        sshpass -p "$REMOTE_PASS" scp -o StrictHostKeyChecking=no -r target/lib "$REMOTE_USER@$REMOTE_HOST:/tmp/"

        # Move to /opt/traccar
        sshpass -p "$REMOTE_PASS" ssh -o StrictHostKeyChecking=no "$REMOTE_USER@$REMOTE_HOST" << EOF
            sudo rm -rf /opt/traccar/lib /opt/traccar/tracker-server.jar
            sudo mv /tmp/tracker-server.jar /opt/traccar/
            sudo mv /tmp/lib /opt/traccar/
            sudo chown -R root:root /opt/traccar/lib /opt/traccar/tracker-server.jar
EOF

        # Update Service
        echo "Updating traccar.service..."
        echo "$TRACCAR_SERVICE_CONTENT" > traccar.service.tmp
        sshpass -p "$REMOTE_PASS" scp -o StrictHostKeyChecking=no traccar.service.tmp "$REMOTE_USER@$REMOTE_HOST:/tmp/traccar.service"
        rm traccar.service.tmp

        sshpass -p "$REMOTE_PASS" ssh -o StrictHostKeyChecking=no "$REMOTE_USER@$REMOTE_HOST" << EOF
            sudo mv /tmp/traccar.service /etc/systemd/system/traccar.service
            sudo systemctl daemon-reload
            sudo systemctl restart traccar
EOF
        echo "Deployment to $REMOTE_HOST finished."
    fi
done

echo "Operation completed!"