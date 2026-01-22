/*
 * Copyright 2019 - 2024 Anton Tananaev (anton@traccar.org)
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
package org.traccar.protocol;

import io.netty.channel.Channel;
import org.traccar.BaseProtocolDecoder;
import org.traccar.session.DeviceSession;
import org.traccar.NetworkMessage;
import org.traccar.Protocol;
import org.traccar.helper.BitUtil;
import org.traccar.helper.Parser;
import org.traccar.helper.PatternBuilder;
import org.traccar.helper.UnitsConverter;
import org.traccar.model.Position;

import java.net.SocketAddress;
import java.util.regex.Pattern;

public class RstProtocolDecoder extends BaseProtocolDecoder {

    public RstProtocolDecoder(Protocol protocol) {
        super(protocol);
    }

    private static final Pattern PATTERN = new PatternBuilder()
            .text("RST;")
            .expression("([AL]);")               // archive
            .expression("([^;]+);")              // model
            .expression("([^;]+);")              // firmware
            .number("(d{9});")                   // serial number
            .number("(d+);")                     // index
            .number("(d+);")                     // type
            .groupBegin()
            .number("(dd)-(dd)-(dddd) ")         // event date
            .number("(dd):(dd):(dd);")           // event time
            .number("(dd)-(dd)-(dddd) ")         // fix date
            .number("(dd):(dd):(dd);")           // fix time
            .number("(-?d+.d+);")                // latitude
            .number("(-?d+.d+);")                // longitude
            .number("(d+);")                     // speed
            .number("(d+);")                     // course
            .number("(-?d+);")                   // altitude
            .number("([01]);")                   // valid
            .number("(d+);")                     // satellites
            .number("(d+);")                     // hdop
            .number("(xx);")                     // inputs 1
            .number("(xx);")                     // inputs 2
            .number("(xx);")                     // inputs 3
            .number("(xx);")                     // outputs 1
            .number("(xx);")                     // outputs 2
            .number("(d+.d+);")                  // power
            .number("(d+.d+);")                  // battery
            .number("(d+);")                     // odometer
            .number("(d+);")                     // rssi
            .number("(xx);")                     // temperature
            .number("x{4};")                     // sensors
            .number("(xx);")                     // status 1
            .number("(xx);")                     // status 2
            .expression("(.*)")                  // additional data
            .groupEnd("?")
            .any()
            .text("FIM;")
            .expression(".*")
            .compile();

    @Override
    protected Object decode(
            Channel channel, SocketAddress remoteAddress, Object msg) throws Exception {

        Parser parser = new Parser(PATTERN, (String) msg);
        if (!parser.matches()) {
            return null;
        }

        String archive = parser.next();
        String model = parser.next();
        String firmware = parser.next();
        String serial = parser.next();
        int index = parser.nextInt();
        int type = parser.nextInt();

        if (channel != null) {
            String response = "RST;A;" + model + ";" + firmware + ";" + serial + ";" + index + ";6;FIM;";
            channel.writeAndFlush(new NetworkMessage(response, remoteAddress));
        }

        DeviceSession deviceSession = getDeviceSession(channel, remoteAddress, serial);
        if (deviceSession == null) {
            return null;
        }

        Position position = new Position(getProtocolName());
        position.setDeviceId(deviceSession.getDeviceId());
        position.set(Position.KEY_ARCHIVE, archive.equals("L"));
        position.set(Position.KEY_INDEX, index);
        position.set(Position.KEY_TYPE, type);

        if (parser.hasNext()) {

            position.setDeviceTime(parser.nextDateTime(Parser.DateTimeFormat.DMY_HMS));
            position.setFixTime(parser.nextDateTime(Parser.DateTimeFormat.DMY_HMS));
            position.setLatitude(parser.nextDouble());
            position.setLongitude(parser.nextDouble());
            position.setSpeed(UnitsConverter.knotsFromKph(parser.nextInt()));
            position.setCourse(parser.nextInt());
            position.setAltitude(parser.nextInt());
            position.setValid(parser.nextInt() > 0);

            position.set(Position.KEY_SATELLITES, parser.nextInt());
            position.set(Position.KEY_HDOP, parser.nextInt());

            int inputs1 = parser.nextHexInt();
            int inputs2 = parser.nextHexInt();
            int inputs3 = parser.nextHexInt();
            position.set(Position.PREFIX_IN + 1, inputs1);
            position.set(Position.PREFIX_IN + 2, inputs2);
            position.set(Position.PREFIX_IN + 3, inputs3);

            position.set(Position.KEY_IGNITION, BitUtil.check(inputs2, 7));

            position.set(Position.PREFIX_OUT + 1, parser.nextHexInt());
            position.set(Position.PREFIX_OUT + 2, parser.nextHexInt());
            position.set(Position.KEY_POWER, parser.nextDouble());
            position.set(Position.KEY_BATTERY, parser.nextDouble());
            position.set(Position.KEY_ODOMETER, parser.nextInt());
            position.set(Position.KEY_RSSI, parser.nextInt());
            position.set(Position.PREFIX_TEMP + 1, (int) parser.nextHexInt().byteValue());

            int status1 = parser.nextHexInt();
            int status2 = parser.nextHexInt();
            position.set(Position.KEY_STATUS, (status1 << 8) + status2);
            int charging = BitUtil.between(status2, 6, 8);
            position.set(Position.KEY_CHARGE, charging == 1 || charging == 3);

            int mode = BitUtil.to(status1, 4);
            if (mode == 1) {
                position.set(Position.KEY_IGNITION, true);
            } else if (mode == 2) {
                position.set(Position.KEY_IGNITION, false);
            } else if (mode == 6) {
                position.set(Position.KEY_ALARM, Position.ALARM_VIBRATION);
            }

            if (type == 20) {
                position.set(Position.KEY_ALARM, Position.ALARM_SOS);
            } else if (type == 40) {
                position.set(Position.KEY_ALARM, Position.ALARM_VIBRATION);
            }

            String tail = parser.next();
            if (tail != null && !tail.isEmpty()) {
                String[] values = tail.split(";");
                int valueIndex = 0;
                if ((type == 1 || type == 2) && values[0].length() == 8) {
                    try {
                        long mask = Long.parseLong(values[valueIndex++], 16);

                        int tempCount = (int) BitUtil.from(mask, 28);
                        for (int i = 0; i < tempCount; i++) {
                            position.set(Position.PREFIX_TEMP + (i + 2), Integer.parseInt(values[valueIndex++]));
                        }

                        if (BitUtil.check(mask, 27) || BitUtil.check(mask, 26)) {
                            valueIndex += 4; // pulse sensor 1
                        }
                        if (BitUtil.check(mask, 25) || BitUtil.check(mask, 24)) {
                            valueIndex += 4; // pulse sensor 2
                        }
                        if (BitUtil.check(mask, 23)) {
                            position.set("partialDistance", Integer.parseInt(values[valueIndex++]));
                        }
                        if (BitUtil.check(mask, 22)) {
                            valueIndex += 30; // LBS info (6 groups of 5 values)
                        }
                        if (BitUtil.check(mask, 21)) {
                            position.set("trailerId", values[valueIndex++]);
                        }
                        if (BitUtil.check(mask, 20)) {
                            valueIndex += 1; // betoneira
                        }
                        if (BitUtil.check(mask, 19)) {
                            valueIndex += 1; // analog fuel
                        }
                        if (BitUtil.check(mask, 18)) {
                            valueIndex += 5; // analog inputs
                        }
                        if (BitUtil.check(mask, 17)) {
                            valueIndex += 1; // sensor maquina
                        }
                        if (BitUtil.check(mask, 16)) {
                            valueIndex += 1; // horimetro RPM
                        }
                        if (BitUtil.check(mask, 15)) {
                            valueIndex += 1; // omnicomm fuel
                        }
                        if (BitUtil.check(mask, 14)) {
                            valueIndex += 2; // humidity
                        }
                        if (BitUtil.check(mask, 13)) {
                            valueIndex += 1; // analog 5
                        }
                        if (BitUtil.check(mask, 12)) {
                            valueIndex += 1; // accelerometer angle
                        }
                        if (BitUtil.check(mask, 11)) {
                            valueIndex += 1; // omnicomm raw
                        }
                        if (BitUtil.check(mask, 10)) {
                            valueIndex += 1; // max vibration
                        }
                        if (BitUtil.check(mask, 9)) {
                            position.set(Position.KEY_DRIVER_UNIQUE_ID, values[valueIndex++]);
                        }
                        if (BitUtil.check(mask, 5)) {
                            position.set(Position.KEY_ODOMETER, Integer.parseInt(values[valueIndex++]));
                        }
                    } catch (NumberFormatException e) {
                        valueIndex = 0;
                    }
                }

                if (type == 55 && valueIndex < values.length) {
                    position.set(Position.KEY_DRIVER_UNIQUE_ID, values[valueIndex]);
                }
            }

            return position;

        } else if (type == 134 || type == 6) {

            getLastLocation(position, null);
            position.set(Position.KEY_RESULT, String.valueOf(type));
            return position;

        } else {

            return null;

        }
    }

}
