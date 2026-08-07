package org.geysermc.extension.edugeyser;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class JoinCodeManagerTest {

    @TempDir
    Path temporaryDirectory;

    @Test
    void malformedAccountDoesNotPreventLaterAccountsFromLoading() throws IOException {
        Path sessions = temporaryDirectory.resolve("sessions_joincode.yml");
        Files.writeString(sessions, """
                accounts:
                  - refresh-token: first
                    passcode: "0,1,2,3"
                  - refresh-token: malformed
                    passcode: "0,not-a-symbol,2,3"
                  - refresh-token: third
                    passcode: "4,5,6,7"
                """);
        List<Integer> malformedEntries = new ArrayList<>();

        List<JoinCodeAccount> accounts = JoinCodeManager.readAccounts(sessions,
                (entry, error) -> malformedEntries.add(entry));

        assertEquals(List.of(2), malformedEntries);
        assertEquals(2, accounts.size());
        assertEquals("first", accounts.get(0).refreshToken);
        assertEquals("third", accounts.get(1).refreshToken);
    }

    @Test
    void rejectsOutOfRangeJoinCodeSymbols() {
        assertThrows(IllegalArgumentException.class, () -> DiscoveryClient.parseJoinCode("0,1,18,3"));
    }
}
