/*
 * Copyright 2025 Anton Tananaev (anton@traccar.org)
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.traccar.command;

import com.google.auth.oauth2.GoogleCredentials;
import com.google.firebase.FirebaseApp;
import com.google.firebase.FirebaseOptions;
import com.google.firebase.messaging.AndroidConfig;
import com.google.firebase.messaging.ApnsConfig;
import com.google.firebase.messaging.Aps;
import com.google.firebase.messaging.FirebaseMessaging;
import com.google.firebase.messaging.MessagingErrorCode;
import com.google.firebase.messaging.MulticastMessage;
import jakarta.inject.Inject;
import jakarta.inject.Singleton;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.traccar.config.Config;
import org.traccar.config.Keys;
import org.traccar.model.Command;
import org.traccar.model.Device;
import org.traccar.storage.Storage;
import org.traccar.storage.query.Columns;
import org.traccar.storage.query.Condition;
import org.traccar.storage.query.Request;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.LinkedList;
import java.util.List;

@Singleton
public class FirebaseCommandSender implements CommandSender {

    private static final Logger LOGGER = LoggerFactory.getLogger(FirebaseCommandSender.class);

    private final FirebaseMessaging firebaseMessaging;
    private final Storage storage;

    @Inject
    public FirebaseCommandSender(Config config, Storage storage) throws IOException {
        this.storage = storage;
        InputStream serviceAccount = new ByteArrayInputStream(
                config.getString(Keys.COMMAND_CLIENT_SERVICE_ACCOUNT).getBytes());

        FirebaseOptions options = FirebaseOptions.builder()
                .setCredentials(GoogleCredentials.fromStream(serviceAccount))
                .build();

        firebaseMessaging = FirebaseMessaging.getInstance(
                FirebaseApp.initializeApp(options, "client"));
    }

    @Override
    public Collection<String> getSupportedCommands() {
        return List.of(
                Command.TYPE_POSITION_SINGLE,
                Command.TYPE_POSITION_PERIODIC,
                Command.TYPE_POSITION_STOP,
                Command.TYPE_FACTORY_RESET);
    }

    @Override
    public void sendCommand(Device device, Command command) throws Exception {
        if (!device.hasAttribute("notificationTokens")) {
            throw new RuntimeException("Missing device notification tokens");
        }

        List<String> registrationTokens = new ArrayList<>(
                Arrays.asList(device.getString("notificationTokens").split("[, ]")));

        AndroidConfig androidConfig = AndroidConfig.builder()
                .setPriority(AndroidConfig.Priority.HIGH)
                .build();

        ApnsConfig apnsConfig = ApnsConfig.builder()
                .setAps(Aps.builder().setContentAvailable(true).build())
                .putHeader("apns-push-type", "background")
                .putHeader("apns-priority", "10")
                .build();

        var messageBuilder = MulticastMessage.builder()
                .putData("command", command.getType())
                .putData("deviceId", device.getUniqueId());

        if (command.getType().equals(Command.TYPE_POSITION_PERIODIC)) {
            messageBuilder.putData("interval", String.valueOf(command.getInteger(Command.KEY_FREQUENCY)));
        }

        MulticastMessage message = messageBuilder
                .setAndroidConfig(androidConfig)
                .setApnsConfig(apnsConfig)
                .addAllTokens(registrationTokens)
                .build();

        var result = firebaseMessaging.sendEachForMulticast(message);
        if (result.getFailureCount() > 0) {
            List<String> failedTokens = new LinkedList<>();
            var iterator = result.getResponses().listIterator();
            while (iterator.hasNext()) {
                int index = iterator.nextIndex();
                var response = iterator.next();
                if (!response.isSuccessful()) {
                    MessagingErrorCode error = response.getException().getMessagingErrorCode();
                    if (error == MessagingErrorCode.INVALID_ARGUMENT || error == MessagingErrorCode.UNREGISTERED) {
                        failedTokens.add(registrationTokens.get(index));
                    }
                    LOGGER.warn("Firebase command device {} error", device.getId(), response.getException());
                }
            }
            if (!failedTokens.isEmpty()) {
                registrationTokens.removeAll(failedTokens);
                if (registrationTokens.isEmpty()) {
                    device.removeAttribute("notificationTokens");
                } else {
                    device.set("notificationTokens", String.join(",", registrationTokens));
                }
                try {
                    storage.updateObject(device, new Request(
                            new Columns.Include("attributes"),
                            new Condition.Equals("id", device.getId())));
                } catch (Exception e) {
                    LOGGER.warn("Failed to clean up device tokens", e);
                }
            }
            if (result.getSuccessCount() == 0) {
                throw result.getResponses().iterator().next().getException();
            }
        }
    }

}
