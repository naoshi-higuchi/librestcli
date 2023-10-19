package org.nopware.librestcli.kvs;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.context.ConfigurableApplicationContext;

import java.util.concurrent.atomic.AtomicReference;

@SpringBootApplication
@Slf4j
public class Kvs {
    private final AtomicReference<ConfigurableApplicationContext> atomicContext = new AtomicReference<>();

    public static Kvs start(int port) {
        String portOption = String.format("--server.port=%d", port);
        ConfigurableApplicationContext ctx = SpringApplication.run(Kvs.class, portOption);
        ctx.registerShutdownHook();
        Kvs kvs = ctx.getBean(Kvs.class);
        kvs.atomicContext.set(ctx);
        return kvs;
    }

    public void stop() {
        ConfigurableApplicationContext ctx = atomicContext.get();
        if (ctx != null) {
            ctx.close();
        }
    }
}
