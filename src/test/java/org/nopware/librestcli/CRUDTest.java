package org.nopware.librestcli;

import lombok.extern.slf4j.Slf4j;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.nopware.librestcli.kvs.Kvs;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

@Slf4j
/**
 * Test CRUD operations with local KVS server.
 * <p>
 *     This test focuses on POST, PUT, and DELETE methods.
 *     These methods mutate the state of the server. So, we need local server that can be reset before each test.
 */
public class CRUDTest {
    private static Kvs kvs;
    private static RestCli.RestCliSpec restCliSpec;

    @BeforeAll
    public static void beforeAll() {
        kvs = Kvs.start(18080);

        try (HttpClient client = HttpClient.newHttpClient()) {
            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create("http://localhost:18080/v3/api-docs"))
                    .GET()
                    .build();

            int statusCode = 0;
            HttpResponse<String> response = null;

            while (statusCode != 200) {
                try {
                    log.info("Waiting for the KVS server to start...");
                    Thread.sleep(100);
                    response = client.send(request, HttpResponse.BodyHandlers.ofString());
                    statusCode = response.statusCode();
                } catch (InterruptedException | IOException e) {
                    log.error("Failed to send a request to the KVS server.", e);
                }
            }

            String apiDocs = response.body();
            log.info("API docs: {}", apiDocs);
            restCliSpec = RestCli.createRestCliSpec("kvscli", apiDocs);

            assertThat(RestCli.execute(restCliSpec, "--help")).isZero();
        }
    }

    @BeforeEach
    public void beforeEach() {
        RestCli.execute(restCliSpec, "/", "delete");
    }

    @AfterAll
    public static void afterAll() {
        kvs.stop();
    }

    @Test
    public void testReadAll() {
        int exit = RestCli.execute(restCliSpec, "--assert-http-status-code=200", "/", "get");
        assertThat(exit).isZero();
    }

    @Test
    public void testReadMissing() {
        int exit = RestCli.execute(restCliSpec, "--assert-http-status-code=404", "/{key}", "get", "--key=0");
        assertThat(exit).isZero();
    }

    @Test
    public void testCreate(@TempDir Path tempDir) throws IOException {
        String value = "foo";
        Path valueFile = tempDir.resolve("value.txt");

        int exit;

        exit = RestCli.execute(restCliSpec, "--request-body=" + value, "--output-file=" + valueFile, "--assert-http-status-code=201", "/", "post");
        assertThat(exit).isZero();
        assertThat(valueFile).exists();

        String actualValue = Files.readString(valueFile);
        assertThat(actualValue).isEqualTo(value);

        // TODO: Get Location header and test if the value is actually created.
    }

    @Test
    public void testUpdate(@TempDir Path tempDir) throws IOException {
        String initialValue = "foo";
        Path initialValueFile = tempDir.resolve("initialValue.txt");

        int exit;

        exit = RestCli.execute(restCliSpec, "--request-body=" + initialValue, "--output-file=" + initialValueFile, "--assert-http-status-code=201", "/{key}", "put", "--key=0");
        assertThat(exit).isZero();
        assertThat(initialValueFile).exists();

        String actualValue = Files.readString(initialValueFile);
        assertThat(actualValue).isEqualTo(initialValue);

        String updatedValue = "bar";
        Path updatedValueFile = tempDir.resolve("updatedValue.txt");

        exit = RestCli.execute(restCliSpec, "--request-body=" + updatedValue, "--output-file=" + updatedValueFile, "--assert-http-status-code=200", "/{key}", "put", "--key=0");
        assertThat(exit).isZero();
        assertThat(updatedValueFile).exists();

        actualValue = Files.readString(updatedValueFile);
        assertThat(actualValue).isEqualTo(updatedValue);
    }
}
