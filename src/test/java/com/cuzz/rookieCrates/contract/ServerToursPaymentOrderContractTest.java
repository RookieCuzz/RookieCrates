package com.cuzz.rookieCrates.contract;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertTrue;

class ServerToursPaymentOrderContractTest {
    @Test
    void recordedCameraValidationPrecedesQuoteAndCharge() throws IOException {
        Path source = Path.of(
                System.getProperty("user.dir"),
                "src/main/java/com/cuzz/rookieCrates/service/OpeningCoordinator.java"
        );
        String content = Files.readString(source, StandardCharsets.UTF_8);
        int method = content.indexOf("private ChargedOpen prepareCharge");
        int validation = content.indexOf("validateRecordedCamera(player, context.profile())", method);
        int quote = content.indexOf("payments.quote(", method);
        int charge = content.indexOf("payments.charge(", method);
        int methodEnd = content.indexOf("private CompletableFuture<DrawCommit>", method);

        assertTrue(method >= 0 && validation > method,
                "prepareCharge must call recorded-camera validation");
        assertTrue(validation < quote,
                "recorded-camera validation must happen before payment quoting");
        assertTrue(quote < charge && charge < methodEnd,
                "payment charge must remain after validation and inside prepareCharge");
    }
}
