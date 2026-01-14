#!/bin/bash

# Configuration
# format: "username:password:host"
HOSTS=(
    "root:FurudUtsukNoncomUnrank:codeartisan.cloud"
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

# 1. Build
echo "Building Traccar backend..."
rm -rf target/
./gradlew assemble

if [ $? -ne 0 ]; then
    echo "Build failed! Aborting deployment."
    exit 1
fi

# 2. Deploy to each host
for HOST_INFO in "${HOSTS[@]}"; do
    IFS=':' read -r REMOTE_USER REMOTE_PASS REMOTE_HOST <<< "$HOST_INFO"
    
    echo "--------------------------------------------------"
    echo "Deploying to $REMOTE_HOST..."

    # Remote cleanup
    echo "Cleaning up remote /opt/traccar..."
    sshpass -p "$REMOTE_PASS" ssh -o StrictHostKeyChecking=no "$REMOTE_USER@$REMOTE_HOST" "sudo rm -rf /opt/traccar/lib /opt/traccar/tracker-server.jar"

    # Upload files
    echo "Uploading tracker-server.jar and lib/..."
    sshpass -p "$REMOTE_PASS" scp -o StrictHostKeyChecking=no target/tracker-server.jar "$REMOTE_USER@$REMOTE_HOST:/tmp/"
    sshpass -p "$REMOTE_PASS" scp -o StrictHostKeyChecking=no -r target/lib "$REMOTE_USER@$REMOTE_HOST:/tmp/"

    # Move to /opt/traccar (using sudo if needed)
    sshpass -p "$REMOTE_PASS" ssh -o StrictHostKeyChecking=no "$REMOTE_USER@$REMOTE_HOST" << EOF
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
done

echo "All deployments completed!"