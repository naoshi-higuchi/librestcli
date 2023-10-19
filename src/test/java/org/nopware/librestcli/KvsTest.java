package org.nopware.librestcli;

import org.junit.jupiter.api.Test;
import org.nopware.librestcli.kvs.Kvs;
import org.springframework.boot.autoconfigure.SpringBootApplication;

import javax.swing.*;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;

import static org.assertj.core.api.Assertions.assertThat;

public class KvsTest {
    @Test
    public void test() throws InterruptedException {
        Kvs kvs = Kvs.start(18080);
        Thread.sleep(2000);

        try (HttpClient client = HttpClient.newHttpClient()) {
            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create("http://localhost:18080/v3/api-docs"))
                    .GET()
                    .build();

            HttpResponse<String> apiDocs = client.send(request, HttpResponse.BodyHandlers.ofString());
            assertThat(apiDocs.statusCode()).isEqualTo(200);
            System.out.println(apiDocs.body());
        } catch (IOException e) {
            throw new RuntimeException(e);
        }

        kvs.stop();
    }
}
