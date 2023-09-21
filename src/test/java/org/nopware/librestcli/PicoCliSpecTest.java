package org.nopware.librestcli;

import org.junit.jupiter.api.Test;
import picocli.CommandLine;
import picocli.CommandLine.Model.CommandSpec;
import picocli.CommandLine.Model.OptionSpec;

import java.io.IOException;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Test for making clear specifications of picocli.
 */
public class PicoCliSpecTest {

    /**
     * Make clear the specification of {@link picocli.CommandLine.ParseResult#matchedOptionValue(String, Object)}.
     */
    @Test
    public void testOptionalOptionValue() {
        OptionSpec optionWithOptionalValue = OptionSpec.builder("--option-with-optional-value")
                .arity("0..1")
                .description("Option with optional value")
                .paramLabel("value")
                .type(String.class)
                .build();

        CommandSpec commandSpec = CommandSpec.create();
        commandSpec.addOption(optionWithOptionalValue);

        AtomicReference<String> optionalValueHolder = new AtomicReference<>();

        CommandLine commandLine = new CommandLine(commandSpec);
        commandLine.setExecutionStrategy(parseResult -> {
            String optionValue = parseResult.matchedOptionValue(optionWithOptionalValue.longestName(), "This default value is not used even when the arity is 0. I don't know when this default value is used.");
            optionalValueHolder.set(optionValue);
            return 0;
        });

        // Case: Arity is 0.
        commandLine.execute("--option-with-optional-value");
        assertThat(optionalValueHolder.get()).isEqualTo(""); // The default value is not used.

        // Case: Arity is 1.
        commandLine.execute("--option-with-optional-value", "oneValue");
        assertThat(optionalValueHolder.get()).isEqualTo("oneValue");
    }
}
