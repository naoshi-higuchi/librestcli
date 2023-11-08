package org.nopware.librestcli;

import com.google.common.io.Resources;
import lombok.extern.slf4j.Slf4j;
import org.junit.jupiter.api.Test;
import picocli.CommandLine;
import picocli.CommandLine.Model.CommandSpec;
import picocli.CommandLine.Model.OptionSpec;

import java.io.IOException;
import java.nio.charset.Charset;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Test for making clear specifications of picocli.
 */
@Slf4j
public class PicoCliSpecTest {
    /*
     * Let's use the GitHub API as an example.
     */
    private static final String GITHUB_API_SPEC;

    static {
        try {
            long begin = System.currentTimeMillis();
            GITHUB_API_SPEC = Resources.toString(
                    Resources.getResource("api.github.com.json"), Charset.defaultCharset());
            long end = System.currentTimeMillis();
            log.debug("Parsing the GitHub API took {} ms.", end - begin);
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
    }

    /**
     * Make clear the specification of
     * {@link picocli.CommandLine.ParseResult#matchedOptionValue(String, Object)}.
     */
    @Test
    public void testOptionWithDefaultValue() {
        OptionSpec optionWithOptionalValue = OptionSpec.builder("--option-with-default-value-and-allowed-zero-arity")
                .arity("0..1")
                .description("Option with default value and allowd 0 arity.")
                .paramLabel("value")
                .required(false)
                .type(String.class)
                .build();

        CommandSpec commandSpec = CommandSpec.create();
        commandSpec.addOption(optionWithOptionalValue);

        AtomicReference<String> optionalValueHolder = new AtomicReference<>();

        CommandLine commandLine = new CommandLine(commandSpec);
        commandLine.setExecutionStrategy(parseResult -> {
            String optionValue = parseResult.matchedOptionValue(optionWithOptionalValue.longestName(),
                    "This default value is not used even when the arity is 0. It is used only when the option is not specified.");
            optionalValueHolder.set(optionValue);
            return 0;
        });

        // Case: Arity is 0.
        commandLine.execute("--option-with-default-value-and-allowed-zero-arity");
        assertThat(optionalValueHolder.get()).isEqualTo(""); // It is not default value but empty string.

        // Case: Arity is 1.
        commandLine.execute("--option-with-default-value-and-allowed-zero-arity", "oneValue");
        assertThat(optionalValueHolder.get()).isEqualTo("oneValue");

        // Case: The option is not specified.
        commandLine.execute();
        assertThat(optionalValueHolder.get()).isEqualTo("This default value is not used even when the arity is 0. It is used only when the option is not specified.");
    }

    /**
     * Test if CommandSpec is immutable.
     * If it is immutable, we can reuse the same CommandSpec instance for multiple CommandLine instances.
     * Let's test its immutability by checking the hash code of CommandLine before and after executing a command.
     * It is a very rough test, but it is enough for now.
     * <p>
     * This test should be dependent only on picocli, but this test uses RestCli because constructing very big command-spec is too tedious other than by RestCli :-)
     */
    @Test
    public void testIfCommandSpecIsImmutable() {
        RestCli.RestCliSpec restCliSpec = RestCli.createRestCliSpec("librestcli", GITHUB_API_SPEC);

        long hash = restCliSpec.hashCode(); // Objects.hash(commandSpec, openAPI);

        int get = RestCli.execute(restCliSpec, "/repos/{owner}/{repo}/issues", "get", "--owner=naoshi-higuchi", "--repo=flist");
        assertThat(get).isZero();

        assertThat(restCliSpec.hashCode()).isEqualTo(hash); // test that it is immutable.
    }

    @Test
    public void testMutuallyExclusiveNonRequiredOptionSpec() {
        OptionSpec optionSpecA = OptionSpec.builder("-a")
                .arity("0")
                .description("Option A")
                .required(false)
                .type(String.class)
                .build();

        OptionSpec optionSpecB = OptionSpec.builder("-b")
                .arity("0")
                .description("Option B")
                .required(false)
                .type(String.class)
                .build();

        CommandLine.Model.ArgGroupSpec argGroupSpec = CommandLine.Model.ArgGroupSpec.builder()
                .exclusive(true)
                .addArg(optionSpecA)
                .addArg(optionSpecB)
                .build();

        CommandSpec commandSpec = CommandSpec.create();
        commandSpec.addArgGroup(argGroupSpec);

        AtomicBoolean isOptionASpecified = new AtomicBoolean(false);
        AtomicBoolean isOptionBSpecified = new AtomicBoolean(false);

        CommandLine commandLine = new CommandLine(commandSpec);
        commandLine.setExecutionStrategy(parseResult -> {
            isOptionASpecified.set(parseResult.hasMatchedOption(optionSpecA));
            isOptionBSpecified.set(parseResult.hasMatchedOption(optionSpecB));
            return 0;
        });

        // Case: Only Option A is specified.
        commandLine.execute("-a");
        assertThat(isOptionASpecified.get()).isTrue();
        assertThat(isOptionBSpecified.get()).isFalse();

        // Case: Only Option B is specified.
        commandLine.execute("-b");
        assertThat(isOptionASpecified.get()).isFalse();
        assertThat(isOptionBSpecified.get()).isTrue();

        // Case: Neither Option A nor Option B is specified. They are not required, so it is OK.
        commandLine.execute();
        assertThat(isOptionASpecified.get()).isFalse();
        assertThat(isOptionBSpecified.get()).isFalse();

        // Case: Both Option A and Option B are specified. They are mutually exclusive, so it is NG.
        assertThat(commandLine.execute("-a", "-b")).isNotZero();
    }
}
