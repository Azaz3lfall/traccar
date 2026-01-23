package org.traccar.protocol;

import org.junit.jupiter.api.Test;
import org.traccar.ProtocolTest;
import org.traccar.model.Position;

public class RstProtocolDecoderTest extends ProtocolTest {

    @Test
    public void testDecode() throws Exception {

        var decoder = inject(new RstProtocolDecoder(null));

        verifyPosition(decoder, text(
                "RST;L;RST-MINI-4Gv3;V10.02;004544344;177;1;09-08-2024 11:35:19;09-08-2024 11:35:21;-19.976116;-47.835190;0;89;218;1;12;0;00;B0;00;1A;02;12.24;4.00;16;23;FE;0000;01;C0;00800061;0;167;2;4;4;0;434.0000;6;0;-66;703;16662;724;255;63;00000000;FIM;"));

        verifyPosition(decoder, text(
                "RST;A;RST-MINI-4Gv2;V8.04;008500359;33;75;22-01-2026 19:26:44;22-01-2026 19:26:44;-15.878986;-48.020077;213465;FIM;"));

        verifyAttribute(decoder, text(
                "RST;A;RST-MINI-4Gv2;V8.04;008500359;40;1;22-01-2026 19:27:38;22-01-2026 19:27:37;-15.879029;-48.020046;0;310;1201;1;3;2;0A;B0;00;1A;02;12.18;4.18;500;10;FE;0000;01;50;00000200;DRIVER123;FIM;"),
                Position.KEY_DRIVER_UNIQUE_ID, "DRIVER123");

        verifyAttribute(decoder, text(
                "RST;A;RST-MINI-4Gv2;V8.04;008500359;41;1;22-01-2026 19:27:38;22-01-2026 19:27:37;-15.879029;-48.020046;0;310;1201;1;3;2;0A;B0;00;1A;02;12.18;4.18;500;10;FE;0000;01;50;00000002;;;90;;800;100;;;40;;;;;;;;80;0;5000;20;10000;10;10;0;100;05;FIM;"),
                Position.KEY_RPM, 800);

        verifyAttribute(decoder, text(
                "RST;A;RST-MINI-4Gv2;V8.04;008500359;25;1;22-01-2026 15:11:26;22-01-2026 15:11:25;-15.878919;-48.020084;0;349;1221;1;5;1;0A;30;00;1A;06;12.23;4.02;20;2;FE;0000;02;50;00800021;0;811;00000000;FIM;\0"),
                Position.KEY_CHARGE, true);

    }

}
