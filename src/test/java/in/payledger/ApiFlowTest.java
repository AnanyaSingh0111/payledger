package in.payledger;

import com.jayway.jsonpath.JsonPath;
import in.payledger.support.CleanDatabaseConfig;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;

import java.util.UUID;

import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.hasKey;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** End-to-end through HTTP: security filter, @PreAuthorize, validation, error format. */
@SpringBootTest(properties = {
        "app.admin.email=admin@payledger.test",
        "app.admin.password=admin-password-123"
})
@AutoConfigureMockMvc
@Import(CleanDatabaseConfig.class)
class ApiFlowTest {

    @Autowired MockMvc mvc;

    private Session alice;
    private Session bob;

    record Session(long userId, String token) {
        String bearer() { return "Bearer " + token; }
    }

    @BeforeEach
    void setUp() throws Exception {
        alice = registerAndLogin("Alice");
        bob = registerAndLogin("Bob");
    }

    // ---------- helpers ----------

    private Session registerAndLogin(String name) throws Exception {
        String email = name.toLowerCase() + "-" + UUID.randomUUID() + "@Example.COM";
        mvc.perform(post("/api/users").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"" + name + "\",\"email\":\"" + email + "\",\"password\":\"password-123\"}"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.email").value(email.toLowerCase()));   // normalised
        return login(email, "password-123");
    }

    private Session login(String email, String password) throws Exception {
        String body = mvc.perform(post("/api/auth/login").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"email\":\"" + email + "\",\"password\":\"" + password + "\"}"))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        return new Session(((Number) JsonPath.read(body, "$.userId")).longValue(), JsonPath.read(body, "$.token"));
    }

    private long createAccount(Session s, String name, String initialBalance) throws Exception {
        String body = mvc.perform(post("/api/users/{id}/accounts", s.userId()).header("Authorization", s.bearer())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"" + name + "\",\"type\":\"WALLET\",\"initialBalance\":" + initialBalance + "}"))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        return ((Number) JsonPath.read(body, "$.id")).longValue();
    }

    private ResultActions transfer(Session s, String key, long from, long to, String amount) throws Exception {
        return mvc.perform(post("/api/users/{id}/transactions", s.userId()).header("Authorization", s.bearer())
                .header("Idempotency-Key", key)
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"type\":\"TRANSFER\",\"sourceAccountId\":" + from
                        + ",\"destinationAccountId\":" + to + ",\"amount\":" + amount + "}"));
    }

    // ---------- tests ----------

    @Test
    void requestsWithoutATokenGet401InTheStandardErrorFormat() throws Exception {
        mvc.perform(get("/api/users/{id}/accounts", alice.userId()))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.status").value(401))
                .andExpect(jsonPath("$.path").value("/api/users/" + alice.userId() + "/accounts"));
    }

    @Test
    void aTamperedTokenIsRejected() throws Exception {
        mvc.perform(get("/api/users/{id}/accounts", alice.userId()).header("Authorization", alice.bearer() + "x"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void usersCannotReadEachOthersData() throws Exception {
        mvc.perform(get("/api/users/{id}/accounts", bob.userId()).header("Authorization", alice.bearer()))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.status").value(403));
    }

    @Test
    void p2pTransferIsIdempotentOverHttp() throws Exception {
        long a = createAccount(alice, "Main", "100.00");
        long b = createAccount(bob, "Main", "0");
        String key = UUID.randomUUID().toString();

        String first = transfer(alice, key, a, b, "25.00").andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        String retry = transfer(alice, key, a, b, "25.00").andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();

        Number firstId = JsonPath.read(first, "$.id");
        Number retryId = JsonPath.read(retry, "$.id");
        org.assertj.core.api.Assertions.assertThat(retryId.longValue()).isEqualTo(firstId.longValue());

        mvc.perform(get("/api/users/{id}/accounts/{acc}", alice.userId(), a).header("Authorization", alice.bearer()))
                .andExpect(jsonPath("$.account.balance").value(75.00))
                .andExpect(jsonPath("$.balanceMatchesLedger").value(true));

        // same key, different amount
        transfer(alice, key, a, b, "30.00").andExpect(status().isUnprocessableEntity());

        // the receiver sees it in their history
        mvc.perform(get("/api/users/{id}/transactions", bob.userId()).header("Authorization", bob.bearer())
                        .param("type", "TRANSFER"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalElements").value(1));
    }

    @Test
    void badInputIsRejectedWithFieldErrors() throws Exception {
        long a = createAccount(alice, "Main", "100.00");
        long b = createAccount(alice, "Savings", "0");

        // 3 decimal places would be silently rounded by DECIMAL(19,2), so it is refused
        transfer(alice, "k1", a, b, "10.005")
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors", hasKey("amount")));

        transfer(alice, "k2", a, b, "-5")
                .andExpect(status().isBadRequest());

        // missing Idempotency-Key header
        mvc.perform(post("/api/users/{id}/transactions", alice.userId()).header("Authorization", alice.bearer())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"type\":\"TRANSFER\",\"sourceAccountId\":" + a + ",\"destinationAccountId\":" + b + ",\"amount\":1}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message", containsString("Idempotency-Key")));

        // overdraft
        transfer(alice, "k3", a, b, "1000.00")
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.message", containsString("Insufficient balance")));
    }

    @Test
    void duplicateEmailRegistrationIsAConflictRegardlessOfCase() throws Exception {
        String email = "dup-" + UUID.randomUUID() + "@x.com";
        String body = "{\"name\":\"A\",\"email\":\"%s\",\"password\":\"password-123\"}";
        mvc.perform(post("/api/users").contentType(MediaType.APPLICATION_JSON).content(body.formatted(email)))
                .andExpect(status().isCreated());
        mvc.perform(post("/api/users").contentType(MediaType.APPLICATION_JSON).content(body.formatted(email.toUpperCase())))
                .andExpect(status().isConflict());
    }

    @Test
    void adminCanFreezeAccountsDisableUsersAndRunReconciliation() throws Exception {
        Session admin = login("admin@payledger.test", "admin-password-123");
        long a = createAccount(alice, "Main", "100.00");
        long b = createAccount(bob, "Main", "0");

        // regular users cannot reach admin endpoints
        mvc.perform(post("/api/admin/reconciliation/run").header("Authorization", alice.bearer()))
                .andExpect(status().isForbidden());

        mvc.perform(post("/api/admin/reconciliation/run").header("Authorization", admin.bearer()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.healthy").value(true));

        // freeze Bob's wallet: Alice can no longer pay into it
        mvc.perform(post("/api/admin/accounts/{acc}/freeze", b).header("Authorization", admin.bearer())
                        .contentType(MediaType.APPLICATION_JSON).content("{\"reason\":\"chargeback investigation\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("FROZEN"));
        transfer(alice, UUID.randomUUID().toString(), a, b, "10.00").andExpect(status().isConflict());

        // disable Bob: his existing token stops working at once
        mvc.perform(post("/api/admin/users/{id}/disable", bob.userId()).header("Authorization", admin.bearer()))
                .andExpect(status().isOk());
        mvc.perform(get("/api/users/{id}/accounts", bob.userId()).header("Authorization", bob.bearer()))
                .andExpect(status().isUnauthorized());

        // and every one of those actions is in the audit trail
        mvc.perform(get("/api/admin/audit-logs").header("Authorization", admin.bearer())
                        .param("entityType", "ACCOUNT").param("entityId", String.valueOf(b)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content[0].action").value("ACCOUNT_FROZEN"))
                .andExpect(jsonPath("$.content[0].details").value("chargeback investigation"));
    }
}
